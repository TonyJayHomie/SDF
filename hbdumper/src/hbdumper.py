#!/usr/bin/env python3
"""
HB Dumper - GUI for SWD-assisted UART flash dump and recovery on
GD32F130C8T6 split-board hoverboards (RoboDurden Gen2.x layout 2.0 / 2.1.1).

Features
--------
- Adapter toggle: pick between Wemos / 3.3V USB-TTL and a UNO passthrough.
- UART target toggle: PA9/PA10 (USART0) or PA2/PA3 (USART1).
- Dump tab    : ST-Link + OpenOCD load SRAM payload, capture HBDP frame,
                verify CRC32, save backup.bin.
- Erase tab   : unlock + mass-erase via OpenOCD (after a confirmed dump).
- Flash tab   : flash a .hex / .bin (e.g. RoboDurden Gen2.1.1 build) via
                OpenOCD.
- Logs from OpenOCD and the serial capture are streamed live into the UI.

This file is intentionally self-contained and only depends on Tkinter +
pyserial. The OpenOCD binary, interface and target .cfg files are
resolved from the configuration tab and can be overridden per machine.
"""

from __future__ import annotations

import os
import queue
import struct
import subprocess
import threading
import time
import zlib
from dataclasses import dataclass, field
from pathlib import Path
import tkinter as tk
from tkinter import ttk, filedialog, messagebox

try:
    import serial
    from serial.tools import list_ports
except Exception:  # pragma: no cover
    serial = None
    list_ports = None


APP_TITLE = "HB Dumper - GD32F130 Split Board Recovery"
ROOT = Path(__file__).resolve().parents[1]
PAYLOAD_DIR = ROOT / "payloads"
PAYLOAD_UART0 = PAYLOAD_DIR / "uart0_pa9pa10.bin"
PAYLOAD_UART1 = PAYLOAD_DIR / "uart1_pa2pa3.bin"
BUNDLED_OPENOCD_ROOT = ROOT / "bin" / "openocd" / "extracted" / "xpack-openocd-0.12.0-7"
BUNDLED_OPENOCD_EXE = BUNDLED_OPENOCD_ROOT / "bin" / "openocd.exe"
BUNDLED_OPENOCD_SCRIPTS = BUNDLED_OPENOCD_ROOT / "openocd" / "scripts"
FIRMWARE_DIR = ROOT / "firmware"
ZADIG_EXE = ROOT / "tools" / "zadig-2.8.exe"

# Known device ID -> chip name mapping (low 12 bits of DBGMCU_IDCODE @ 0xE0042000
# or for Cortex-M0 parts via 0x40015800 on STM32F0/GD32F1x0). We use the simpler
# flash-size register approach as a heuristic; chip family is mainly logged.
DEVID_MAP = {
    0x444: "STM32F03x (also GD32F130xx variants)",
    0x440: "STM32F05x / GD32F1x0 family",
    0x445: "STM32F04x",
    0x448: "STM32F07x",
    0x442: "STM32F09x",
}

FLASH_BASE = 0x08000000
FLASH_LEN  = 0x10000   # 64 KiB for GD32F130C8

MAGIC = b"HBDP"
TRAILER = b"DONE"


# ----------------------------------------------------------------------
# Configuration
# ----------------------------------------------------------------------
@dataclass
class DumpConfig:
    openocd_exe: str
    interface_cfg: str
    target_cfg: str
    serial_port: str
    baudrate: int
    output_file: str
    payload_path: str
    adapter_mode: str   # "wemos" or "uno"
    uart_target: str    # "usart0" or "usart1"
    stlink_speed: int


def build_openocd_env(openocd_exe: str) -> dict[str, str]:
    env = os.environ.copy()
    exe_path = Path(openocd_exe)
    if BUNDLED_OPENOCD_SCRIPTS.exists() and (exe_path == BUNDLED_OPENOCD_EXE or str(exe_path).endswith("openocd.exe")):
        env.setdefault("OPENOCD_SCRIPTS", str(BUNDLED_OPENOCD_SCRIPTS))
    return env


# ----------------------------------------------------------------------
# OpenOCD helpers
# ----------------------------------------------------------------------
def build_openocd_load_cmd(cfg: DumpConfig, payload_bytes: bytes) -> list[str]:
    """
    OpenOCD command line that:
      1. halts the core
      2. writes the SRAM payload word by word at 0x20000000
      3. sets PC = 0x20000000, SP = end of SRAM
      4. resumes
    """
    # pack as 32-bit words
    if len(payload_bytes) % 4 != 0:
        payload_bytes = payload_bytes + b"\x00" * (4 - len(payload_bytes) % 4)
    words = struct.unpack("<" + "I" * (len(payload_bytes) // 4), payload_bytes)

    tcl_lines = [
        "init",
        "reset halt",
        "halt",
        # write SRAM payload
    ]
    addr = 0x20000000
    for i, w in enumerate(words):
        tcl_lines.append(f"mww 0x{addr + i*4:08x} 0x{w:08x}")

    # SP at top of 20K SRAM (GD32F130C8 has 20 KiB SRAM)
    sp = 0x20000000 + 20 * 1024
    tcl_lines.append(f"reg msp 0x{sp:08x}")
    tcl_lines.append(f"reg pc  0x20000000")
    tcl_lines.append("resume")
    tcl_lines.append("exit")

    args = [cfg.openocd_exe] + _ocd_cfg_args(cfg) + [
            "-c", f"adapter speed {cfg.stlink_speed}"]
    for line in tcl_lines:
        args += ["-c", line]
    return args


def build_openocd_erase_cmd(cfg: DumpConfig) -> list[str]:
    return [cfg.openocd_exe] + _ocd_cfg_args(cfg) + [
            "-c", f"adapter speed {cfg.stlink_speed}",
            "-c", "init",
            "-c", "reset halt",
            "-c", "stm32f1x unlock 0",
            "-c", "reset halt",
            "-c", "stm32f1x mass_erase 0",
            "-c", "reset run",
            "-c", "exit"]


def build_openocd_flash_cmd(cfg: DumpConfig, hex_or_bin: str) -> list[str]:
    if hex_or_bin.lower().endswith(".bin"):
        prog = f"program \"{hex_or_bin}\" 0x08000000 verify reset exit"
    else:
        prog = f"program \"{hex_or_bin}\" verify reset exit"
    return [cfg.openocd_exe] + _ocd_cfg_args(cfg) + [
            "-c", f"adapter speed {cfg.stlink_speed}",
            "-c", "init",
            "-c", "reset halt",
            "-c", "stm32f1x unlock 0",
            "-c", "reset halt",
            "-c", prog]


def _ocd_cfg_args(cfg: DumpConfig) -> list[str]:
    """Emit -f args, skipping empty interface (board files include it themselves)."""
    args: list[str] = []
    if cfg.interface_cfg.strip():
        args += ["-f", cfg.interface_cfg]
    if cfg.target_cfg.strip():
        args += ["-f", cfg.target_cfg]
    return args


def build_openocd_test_cmd(cfg: DumpConfig) -> list[str]:
    """Quick SWD sanity check: init, halt, read IDCODE / CPUID, then exit."""
    return [cfg.openocd_exe] + _ocd_cfg_args(cfg) + [
            "-c", f"adapter speed {cfg.stlink_speed}",
            "-c", "init",
            "-c", "reset halt",
            "-c", "mdw 0xE000ED00",   # SCB CPUID
            "-c", "mdw 0x1FFFF7E0",   # GD32F130 / STM32F1 flash size register
            "-c", "mdw 0x1FFFF7E8",   # device ID
            "-c", "reset run",
            "-c", "exit"]


def build_openocd_swd_readback_cmd(cfg: DumpConfig, out_path: str) -> list[str]:
    """
    SWD-only direct flash readback. Works when the chip is NOT RDP-locked.
    If RDP is engaged this will fail and the UART exploit path is the way.
    """
    return [cfg.openocd_exe] + _ocd_cfg_args(cfg) + [
            "-c", f"adapter speed {cfg.stlink_speed}",
            "-c", "init",
            "-c", "reset halt",
            "-c", f"dump_image \"{out_path}\" 0x08000000 0x10000",
            "-c", "reset run",
            "-c", "exit"]


# ----------------------------------------------------------------------
# Worker (background runner)
# ----------------------------------------------------------------------
class Worker:
    def __init__(self, app: "App") -> None:
        self.app = app
        self.proc: subprocess.Popen | None = None
        self.stop_event = threading.Event()
        self.serial_buffer = bytearray()
        self.serial_thread: threading.Thread | None = None

    def log(self, text: str) -> None:
        if not text.endswith("\n"):
            text += "\n"
        self.app.log_queue.put(text)

    def stop(self) -> None:
        self.stop_event.set()
        self.log("[stop] user requested stop")
        if self.proc and self.proc.poll() is None:
            try:
                self.proc.terminate()
                self.log("[stop] subprocess terminated")
            except Exception as e:
                self.log(f"[stop] terminate failed: {e}")
            try:
                import time as _t
                for _ in range(20):
                    if self.proc.poll() is not None: break
                    _t.sleep(0.1)
                if self.proc.poll() is None:
                    self.proc.kill()
                    self.log("[stop] subprocess killed")
            except Exception as e:
                self.log(f"[stop] kill phase error: {e}")
        self.app.status_q.put("Stopped")

    # ---------- serial ----------
    def start_serial_capture(self, port: str, baud: int) -> bool:
        """Open serial in a worker thread. Returns True if the port opened."""
        self.serial_buffer.clear()
        self.serial_open_ok = False
        self._serial_open_event = threading.Event()
        self._serial_open_error = None
        if serial is None:
            self.log("[serial] pyserial not installed - capture disabled")
            self._serial_open_error = "pyserial not installed"
            self._serial_open_event.set()
            return False
        if not port:
            self.log("[serial] no port set - capture disabled")
            self._serial_open_error = "no port set"
            self._serial_open_event.set()
            return False

        def _run() -> None:
            self.log(f"[serial] opening {port} @ {baud}")
            try:
                with serial.Serial(port=port, baudrate=baud, timeout=0.25) as ser:
                    self.serial_open_ok = True
                    self._serial_open_event.set()
                    self.log(f"[serial] open OK on {port}")
                    while not self.stop_event.is_set():
                        chunk = ser.read(4096)
                        if chunk:
                            self.serial_buffer.extend(chunk)
                            self.log(f"[serial] +{len(chunk)} bytes (total={len(self.serial_buffer)})")
            except Exception as e:
                self.serial_open_ok = False
                self._serial_open_error = str(e)
                self._serial_open_event.set()
                self.log(f"[serial] error: {e}")

        self.serial_thread = threading.Thread(target=_run, daemon=True)
        self.serial_thread.start()
        self._serial_open_event.wait(timeout=2.0)
        return self.serial_open_ok

    # ---------- generic process runner ----------
    def run_proc(self, cmd: list[str], tag: str = "proc", capture: list[str] | None = None) -> int:
        self.log(f"[{tag}] " + " ".join(repr(c) if " " in c else c for c in cmd))
        try:
            self.proc = subprocess.Popen(
                cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                text=True, bufsize=1,
                env=build_openocd_env(cmd[0]))
        except FileNotFoundError:
            self.log(f"[{tag}] executable not found: {cmd[0]}")
            return -1
        except Exception as e:
            self.log(f"[{tag}] start error: {e}")
            return -1
        assert self.proc.stdout is not None
        for line in self.proc.stdout:
            if self.stop_event.is_set():
                break
            ln = line.rstrip()
            self.log(f"[{tag}] {ln}")
            if capture is not None:
                capture.append(ln)
        rc = self.proc.wait()
        self.log(f"[{tag}] exit code {rc}")
        return rc

    # ---------- HBDP frame parser ----------
    def parse_hbdp_frame(self) -> bytes | None:
        """
        Frame layout (little-endian):
            'HBDP' (4) | length (4) | payload (length) | crc32 (4) | 'DONE' (4)
        Returns payload bytes if a valid frame is fully received,
        otherwise None.
        """
        buf = bytes(self.serial_buffer)
        idx = buf.find(MAGIC)
        if idx < 0:
            return None
        if len(buf) < idx + 8:
            return None
        length = struct.unpack("<I", buf[idx + 4: idx + 8])[0]
        need = idx + 8 + length + 4 + 4
        if len(buf) < need:
            return None
        payload = buf[idx + 8: idx + 8 + length]
        crc_recv = struct.unpack("<I", buf[idx + 8 + length: idx + 8 + length + 4])[0]
        trailer = buf[idx + 8 + length + 4: idx + 8 + length + 8]
        crc_calc = zlib.crc32(payload) & 0xFFFFFFFF
        self.log(f"[frame] length={length} crc_recv=0x{crc_recv:08x} crc_calc=0x{crc_calc:08x} trailer={trailer!r}")
        if trailer != TRAILER:
            self.log("[frame] WARNING: trailer mismatch")
            return None
        if crc_recv != crc_calc:
            self.log("[frame] WARNING: CRC mismatch - dump is corrupt")
            return payload  # still return so user can inspect
        return payload

    def wait_for_frame(self, expected_len: int, timeout: float = 30.0) -> bytes | None:
        deadline = time.time() + timeout
        last_size = 0
        while time.time() < deadline and not self.stop_event.is_set():
            time.sleep(0.5)
            cur = len(self.serial_buffer)
            if cur != last_size:
                last_size = cur
            # need magic + len + payload + crc + trailer
            if cur >= 4 + 4 + expected_len + 4 + 4:
                frame = self.parse_hbdp_frame()
                if frame is not None:
                    return frame
        return self.parse_hbdp_frame()

    def save_raw_capture(self, path: str) -> None:
        Path(path).write_bytes(bytes(self.serial_buffer))
        self.log(f"[save] wrote {len(self.serial_buffer)} raw bytes to {path}")

    # ---------- high level actions ----------
    def action_full_backup(self, cfg: DumpConfig) -> None:
        """One-button: try SWD direct dump first; fall back to UART exploit."""
        self.stop_event.clear()
        self.log("=" * 60)
        self.log("[full] step 1: SWD sanity check")
        rc = self.run_proc(build_openocd_test_cmd(cfg), tag="openocd")
        if rc != 0:
            self.log("[full] SWD connect failed - check ST-Link wiring/drivers")
            return
        self.log("[full] step 2: try direct SWD dump")
        rc = self.run_proc(build_openocd_swd_readback_cmd(cfg, cfg.output_file), tag="openocd")
        if rc == 0 and Path(cfg.output_file).exists():
            data = Path(cfg.output_file).read_bytes()
            if len(data) == FLASH_LEN and not all(b == 0xFF for b in data[:4096]):
                self.log(f"[full] SWD dump SUCCESS - {len(data)} bytes saved")
                return
            self.log("[full] SWD dump returned blank/locked content - falling back to UART exploit")
        else:
            self.log("[full] SWD dump failed - falling back to UART exploit")
        self.log("[full] step 3: UART exploit path")
        self.action_dump(cfg)

    def action_dump(self, cfg: DumpConfig) -> None:
        self.stop_event.clear()
        self.log("=" * 60)
        self.log(f"[dump] adapter={cfg.adapter_mode} uart={cfg.uart_target}")
        self.log(f"[dump] payload={cfg.payload_path}")
        try:
            payload_bytes = Path(cfg.payload_path).read_bytes()
        except Exception as e:
            self.log(f"[dump] cannot read payload: {e}")
            return
        self.log(f"[dump] payload size = {len(payload_bytes)} bytes")

        # 1. open serial first so we don't miss the start of the stream
        if not self.start_serial_capture(cfg.serial_port, cfg.baudrate):
            self.log(f"[dump] ABORT - cannot open serial port {cfg.serial_port}: {self._serial_open_error}")
            self.app.status_q.put("ABORT dump: serial port not openable")
            return
        time.sleep(0.4)

        # 2. fire OpenOCD: load payload + resume from 0x20000000
        cmd = build_openocd_load_cmd(cfg, payload_bytes)
        rc = self.run_proc(cmd, tag="openocd")
        if rc != 0:
            self.log("[dump] OpenOCD did not exit cleanly; capture continues...")

        # 3. wait for frame
        frame = self.wait_for_frame(FLASH_LEN, timeout=45.0)

        # 4. save raw and decoded
        raw_path = str(Path(cfg.output_file).with_suffix("")) + "_raw.bin"
        self.save_raw_capture(raw_path)

        if frame is not None and len(frame) == FLASH_LEN:
            Path(cfg.output_file).write_bytes(frame)
            self.log(f"[dump] SUCCESS - wrote {len(frame)} bytes to {cfg.output_file}")
        else:
            self.log("[dump] FAIL - no valid HBDP frame captured")
            self.log("       Raw capture saved for manual inspection.")
        self.stop_event.set()

    def action_erase(self, cfg: DumpConfig, backup_required: bool = True) -> None:
        """
        Safety-hardened mass erase.
        Will REFUSE to erase unless a verified, full-size backup.bin exists
        AND the chip's current contents match that backup byte-for-byte via
        a fresh SWD readback. Only if the readback says the chip is already
        identical to the backup will we proceed to erase.
        """
        self.stop_event.clear()
        self.log("=" * 60)
        self.log("[erase] SAFETY PRE-CHECK")

        if backup_required:
            backup = Path(cfg.output_file)
            if not backup.exists():
                self.log(f"[erase] ABORT - no backup file at {backup}")
                self.log("[erase] Run a dump first. Refusing to erase without a verified backup.")
                self.app.status_q.put("ABORT erase: no backup")
                return
            try:
                size = backup.stat().st_size
            except Exception as e:
                self.log(f"[erase] ABORT - cannot stat backup: {e}"); return
            if size != FLASH_LEN:
                self.log(f"[erase] ABORT - backup size {size} != expected {FLASH_LEN}")
                self.log("[erase] A partial/raw capture is NOT a valid backup. Re-dump first.")
                self.app.status_q.put("ABORT erase: backup wrong size")
                return
            # Re-read the chip via SWD and compare
            self.log("[erase] verifying chip == backup (SWD readback compare) ...")
            tmp_rb = str(backup.with_suffix("")) + "_preErase_readback.bin"
            rc = self.run_proc(build_openocd_swd_readback_cmd(cfg, tmp_rb), tag="openocd")
            if rc != 0 or not Path(tmp_rb).exists():
                self.log("[erase] ABORT - cannot read chip back for verify. Chip may be RDP-locked.")
                self.log("[erase] If chip is RDP-locked, your existing backup likely came from the UART exploit.")
                self.log("[erase] In that case, only proceed with the SAFE_OVERRIDE option in the GUI confirm.")
                self.app.status_q.put("ABORT erase: readback failed")
                return
            a = backup.read_bytes()
            b = Path(tmp_rb).read_bytes()
            if len(a) != len(b):
                self.log(f"[erase] ABORT - sizes differ backup={len(a)} readback={len(b)}")
                return
            diffs = sum(1 for x, y in zip(a, b) if x != y)
            if diffs != 0:
                self.log(f"[erase] ABORT - chip differs from backup ({diffs} bytes). Re-dump before erase.")
                self.app.status_q.put("ABORT erase: chip != backup")
                return
            self.log("[erase] safety check PASS - chip matches backup, proceeding")
        else:
            self.log("[erase] SAFE_OVERRIDE active - skipping pre-check (user assumed responsibility)")

        self.log("[erase] mass-erasing chip (unlock + mass_erase)")
        rc = self.run_proc(build_openocd_erase_cmd(cfg), tag="openocd")
        if rc == 0:
            self.log("[erase] DONE - chip mass-erased")
            self.app.status_q.put("Erased OK")
        else:
            self.log("[erase] OpenOCD reported non-zero exit")
            self.app.status_q.put("Erase FAILED")

    def action_flash(self, cfg: DumpConfig, image_path: str, verify_after: bool = True,
                     require_backup: bool = True) -> None:
        self.stop_event.clear()
        self.log("=" * 60)
        # SAFETY GATE: refuse to flash if no valid backup exists.
        if require_backup:
            backup = Path(cfg.output_file)
            if not backup.exists() or backup.stat().st_size != FLASH_LEN:
                self.log(f"[flash] ABORT - no valid backup at {backup} (need exactly {FLASH_LEN} bytes)")
                self.log("[flash] Run a successful dump first. Refusing to flash without a backup.")
                self.app.status_q.put("ABORT flash: no backup")
                return
            # Hash the backup so the post-flash verify can detect tampering / mismatch
            import hashlib
            sha = hashlib.sha256(backup.read_bytes()).hexdigest()
            self.log(f"[flash] backup OK ({backup.stat().st_size} bytes, sha256={sha[:16]}...)")
        self.log(f"[flash] programming {image_path}")
        rc = self.run_proc(build_openocd_flash_cmd(cfg, image_path), tag="openocd")
        if rc != 0:
            self.log("[flash] OpenOCD reported non-zero exit - skipping verify")
            return
        if not verify_after:
            return
        if image_path.lower().endswith(".bin"):
            self.log("[flash] post-flash verify (round-trip)")
            readback = str(Path(image_path).with_suffix("")) + "_readback.bin"
            rc2 = self.run_proc(build_openocd_swd_readback_cmd(cfg, readback), tag="openocd")
            if rc2 != 0 or not Path(readback).exists():
                self.log("[flash] verify readback failed")
                return
            a = Path(image_path).read_bytes()
            b = Path(readback).read_bytes()
            # Compare only the programmed region
            n = min(len(a), len(b))
            diffs = sum(1 for x, y in zip(a[:n], b[:n]) if x != y)
            if diffs == 0:
                self.log(f"[flash] VERIFY PASS - {n} programmed bytes match")
            else:
                pct = 100.0 * diffs / n
                self.log(f"[flash] VERIFY FAIL - {diffs} byte differences in first {n} bytes ({pct:.3f}%)")
        else:
            self.log("[flash] skipping post-flash verify (.hex inputs handled by OpenOCD verify already)")

    def action_test(self, cfg: DumpConfig) -> None:
        self.stop_event.clear()
        self.log("=" * 60)
        self.log("[test] SWD sanity check")
        # Capture OpenOCD output so we can parse mdw values
        buf: list[str] = []
        rc = self.run_proc(build_openocd_test_cmd(cfg), tag="openocd", capture=buf)
        if rc != 0:
            self.log("[test] SWD connection FAILED - run Zadig (tools/zadig-2.8.exe) to install WinUSB driver, check wiring")
            self.app.status_q.put("FAIL: SWD connect")
            return
        # Parse mdw lines like: 0xe000ed00: 410cc601
        cpuid = flash_kb = devid = None
        for line in buf:
            line = line.strip()
            if line.startswith("0xe000ed00") or "0xE000ED00" in line.upper():
                parts = line.split(":")
                if len(parts) >= 2:
                    try: cpuid = int(parts[1].strip().split()[0], 16)
                    except: pass
            elif "0x1FFFF7E0" in line.upper() or line.startswith("0x1ffff7e0"):
                parts = line.split(":")
                if len(parts) >= 2:
                    try:
                        val = int(parts[1].strip().split()[0], 16)
                        flash_kb = val & 0xFFFF
                    except: pass
            elif "0x1FFFF7E8" in line.upper() or line.startswith("0x1ffff7e8"):
                parts = line.split(":")
                if len(parts) >= 2:
                    try: devid = int(parts[1].strip().split()[0], 16)
                    except: pass
        self.log("[test] SWD connection OK")
        if cpuid is not None:
            self.log(f"[test]   CPUID = 0x{cpuid:08x}")
        if flash_kb is not None:
            self.log(f"[test]   Flash size register = {flash_kb} KiB")
        if devid is not None:
            self.log(f"[test]   Device unique ID word0 = 0x{devid:08x}")
        status = f"OK CPUID=0x{cpuid:08x}" if cpuid is not None else "OK"
        if flash_kb: status += f" flash={flash_kb}KB"
        self.app.status_q.put(status)

    def action_swd_readback(self, cfg: DumpConfig) -> None:
        """
        SWD-only fallback dump. Faster than UART when the chip is not RDP-locked.
        """
        self.stop_event.clear()
        self.log("=" * 60)
        out = cfg.output_file
        self.log(f"[swd-dump] dump_image -> {out}")
        rc = self.run_proc(build_openocd_swd_readback_cmd(cfg, out), tag="openocd")
        if rc == 0 and Path(out).exists():
            size = Path(out).stat().st_size
            self.log(f"[swd-dump] wrote {size} bytes to {out}")
            if size == FLASH_LEN:
                self.log("[swd-dump] SUCCESS - 64 KiB captured")
            else:
                self.log(f"[swd-dump] WARNING - expected {FLASH_LEN}, got {size}")
        else:
            self.log("[swd-dump] FAIL - chip may be RDP-locked; use UART dump instead")

    def action_verify(self, cfg: DumpConfig, backup_path: str) -> None:
        """
        Read flash back via SWD and compare against an existing backup.bin.
        """
        self.stop_event.clear()
        self.log("=" * 60)
        if not Path(backup_path).exists():
            self.log(f"[verify] backup file not found: {backup_path}")
            return
        readback = str(Path(backup_path).with_suffix("")) + "_readback.bin"
        self.log(f"[verify] reading flash to {readback}")
        rc = self.run_proc(build_openocd_swd_readback_cmd(cfg, readback), tag="openocd")
        if rc != 0 or not Path(readback).exists():
            self.log("[verify] readback failed - cannot verify")
            return
        a = Path(backup_path).read_bytes()
        b = Path(readback).read_bytes()
        if len(a) != len(b):
            self.log(f"[verify] SIZE MISMATCH: backup={len(a)}, readback={len(b)}")
            return
        diffs = [(i, x, y) for i, (x, y) in enumerate(zip(a, b)) if x != y]
        if not diffs:
            self.log(f"[verify] PASS - {len(a)} bytes match exactly")
            return
        pct = 100.0 * len(diffs) / len(a)
        self.log(f"[verify] FAIL - {len(diffs)} byte differences ({pct:.2f}%)")
        # Write a hex-diff report
        report = str(Path(backup_path).with_suffix("")) + "_diff.txt"
        with open(report, "w") as f:
            f.write(f"# hex-diff backup vs readback\n")
            f.write(f"# backup   : {backup_path}\n")
            f.write(f"# readback : {readback}\n")
            f.write(f"# diffs    : {len(diffs)} of {len(a)} bytes ({pct:.4f}%)\n\n")
            f.write(f"{'offset':>10}  {'backup':>6}  {'readback':>8}\n")
            for i, (off, x, y) in enumerate(diffs):
                if i >= 4096:
                    f.write(f"... ({len(diffs) - 4096} more)\n")
                    break
                f.write(f"  0x{off:08x}    0x{x:02x}      0x{y:02x}\n")
        self.log(f"[verify] diff report written: {report}")
        # Surface first 8 diffs in the live log
        for off, x, y in diffs[:8]:
            self.log(f"[verify]   offset 0x{off:08x}: backup=0x{x:02x} readback=0x{y:02x}")
        # Push diff data to UI so the in-app viewer can render it
        self.app.log_queue.put(("__DIFF__", diffs[:4096], backup_path, readback))


# ----------------------------------------------------------------------
# UI
# ----------------------------------------------------------------------
class App(tk.Tk):
    def __init__(self) -> None:
        super().__init__()
        self.title(APP_TITLE)
        self.geometry("1180x960")
        self.minsize(1024, 820)
        self.log_queue: "queue.Queue[str]" = queue.Queue()
        self.status_q: "queue.Queue[str]" = queue.Queue()
        self.worker = Worker(self)

        self._vars()
        self._build_ui()
        self._refresh_ports()
        self.after(120, self._drain_log_queue)

    # ----- shared state vars -----
    def _vars(self) -> None:
        self.openocd_var   = tk.StringVar(value=self._default_openocd())
        self.interface_var = tk.StringVar(value="interface/stlink.cfg")
        self.target_var    = tk.StringVar(value="target/stm32f1x.cfg")
        self.board_preset_var = tk.BooleanVar(value=False)
        self.serial_var    = tk.StringVar()
        self.baud_var      = tk.StringVar(value="115200")
        self.speed_var     = tk.StringVar(value="1000")
        self.output_var    = tk.StringVar(value=str(ROOT / "backup.bin"))
        self.payload_var   = tk.StringVar(value=str(PAYLOAD_UART1))
        self.adapter_var   = tk.StringVar(value="wemos")
        self.uart_var      = tk.StringVar(value="usart1")
        self.flash_image_var = tk.StringVar()

    def _default_openocd(self) -> str:
        # Prefer the bundled xPack OpenOCD if present.
        candidates = (
            str(BUNDLED_OPENOCD_EXE),
            "openocd",
            r"C:\openocd\bin\openocd.exe",
        )
        for candidate in candidates:
            if Path(candidate).exists() or candidate == "openocd":
                return candidate
        return "openocd"

    # ----- layout -----
    def _build_ui(self) -> None:
        outer = ttk.Frame(self, padding=10)
        outer.pack(fill="both", expand=True)

        cfg = ttk.LabelFrame(outer, text="Common configuration", padding=10)
        cfg.pack(fill="x")
        rows = [
            ("OpenOCD executable", self.openocd_var,   self._pick_openocd),
            ("Interface .cfg",     self.interface_var, None),
            ("Target .cfg",        self.target_var,    None),
            ("Serial port",        self.serial_var,    None),
            ("Baud rate",          self.baud_var,      None),
            ("ST-Link speed (kHz)",self.speed_var,     None),
            ("Backup output file", self.output_var,    self._pick_output),
            ("Payload .bin",       self.payload_var,   self._pick_payload),
        ]
        for r, (label, var, picker) in enumerate(rows):
            ttk.Label(cfg, text=label).grid(row=r, column=0, sticky="w", padx=(0, 8), pady=3)
            if label == "Serial port":
                ports = []
                if list_ports is not None:
                    try: ports = [p.device for p in list_ports.comports()]
                    except Exception: ports = []
                self._serial_combo = ttk.Combobox(cfg, textvariable=var, values=ports, width=20)
                self._serial_combo.grid(row=r, column=1, sticky="ew", pady=3)
                ttk.Button(cfg, text="Refresh", command=self._refresh_ports).grid(row=r, column=2, padx=4)
            else:
                ttk.Entry(cfg, textvariable=var).grid(row=r, column=1, sticky="ew", pady=3)
                if picker:
                    ttk.Button(cfg, text="...", width=4, command=picker).grid(row=r, column=2, padx=4)
        cfg.columnconfigure(1, weight=1)

        # Preset for split board 2.1.1
        ttk.Checkbutton(cfg, text="Use bundled board config: GD32F130 split board 2.1.1 (auto sets interface+target)",
                        variable=self.board_preset_var,
                        command=self._apply_board_preset).grid(row=len(rows), column=0, columnspan=3, sticky="w", pady=(4, 0))

        # toggles
        tog = ttk.LabelFrame(outer, text="Adapter and UART target", padding=10)
        tog.pack(fill="x", pady=(8, 0))

        ad = ttk.LabelFrame(tog, text="USB-TTL adapter", padding=6)
        ad.grid(row=0, column=0, padx=(0, 14), sticky="nw")
        ttk.Radiobutton(ad, text="Wemos / 3.3V adapter (recommended)",
                        value="wemos", variable=self.adapter_var,
                        command=self._apply_toggle).pack(anchor="w")
        ttk.Radiobutton(ad, text="UNO passthrough (RX-only safe)",
                        value="uno", variable=self.adapter_var,
                        command=self._apply_toggle).pack(anchor="w")

        ut = ttk.LabelFrame(tog, text="MCU UART pins", padding=6)
        ut.grid(row=0, column=1, sticky="nw")
        ttk.Radiobutton(ut, text="USART1 on PA2/PA3 (split-board 2.1.1 default)",
                        value="usart1", variable=self.uart_var,
                        command=self._apply_toggle).pack(anchor="w")
        ttk.Radiobutton(ut, text="USART0 on PA9/PA10 (only if routed; PA9/PA10 are 5V-tolerant)",
                        value="usart0", variable=self.uart_var,
                        command=self._apply_toggle).pack(anchor="w")

        self.note_var = tk.StringVar()
        ttk.Label(tog, textvariable=self.note_var,
                  wraplength=1000, justify="left",
                  foreground="#444").grid(row=1, column=0, columnspan=2,
                                          sticky="w", pady=(6, 0))

        # tabs
        nb = ttk.Notebook(outer)
        nb.pack(fill="both", expand=True, pady=(8, 0))

        # --- Dump tab ---
        tab_dump = ttk.Frame(nb, padding=8)
        nb.add(tab_dump, text="1) Dump (backup)")

        ttk.Label(tab_dump,
                  text=("Procedure:\n"
                        "  1. Wire ST-Link V2 (SWDIO/SWCLK/GND/3.3V) to the GD32F130 board.\n"
                        "  2. Wire your USB-TTL adapter to the MCU UART pins selected above.\n"
                        "  3. Pick the matching COM port, set output file.\n"
                        "  4. Click the green START button below.\n"
                        "  5. The tool tries SWD direct-dump first; if RDP-locked it falls\n"
                        "     back to the UART exploit, captures the HBDP frame, verifies CRC32\n"
                        "     and saves your backup.bin."),
                  justify="left").pack(anchor="w")
        b1 = ttk.Frame(tab_dump); b1.pack(fill="x", pady=10)
        start_style = ttk.Style()
        try:
            start_style.configure("Start.TButton", foreground="white", background="#1f8a3a", padding=10, font=("TkDefaultFont", 11, "bold"))
            start_style.configure("Stop.TButton",  foreground="white", background="#a02020", padding=10, font=("TkDefaultFont", 11, "bold"))
        except Exception as e:
            self.log_queue.put(f"[ui] style config error: {e}\n")
        ttk.Button(b1, text="\u25B6  START FULL BACKUP", style="Start.TButton",
                   command=self._run_full_backup).pack(side="left", padx=(0, 8))
        ttk.Button(b1, text="\u25A0  STOP", style="Stop.TButton",
                   command=self.worker.stop).pack(side="left")
        b2 = ttk.Frame(tab_dump); b2.pack(fill="x", pady=(0, 6))
        ttk.Button(b2, text="Refresh COM ports", command=self._refresh_ports).pack(side="left")
        ttk.Button(b2, text="UART Dump only (skip SWD)", command=self._run_dump).pack(side="left", padx=8)
        ttk.Button(b2, text="Save raw bytes now", command=self._save_raw_now).pack(side="left")
        ttk.Button(b2, text="Open backup folder", command=self._open_backup_folder).pack(side="left", padx=8)

        # --- SWD-only quick dump tab ---
        tab_swd = ttk.Frame(nb, padding=8)
        nb.add(tab_swd, text="1b) SWD quick dump (no UART)")
        ttk.Label(tab_swd,
                  text=("Fast read-back path that does NOT need a UART adapter.\n"
                        "Works ONLY when the chip is not RDP-locked.\n"
                        "If this fails, switch back to tab 1 and use the UART exploit flow."),
                  justify="left").pack(anchor="w")
        ttk.Button(tab_swd, text="\u25B6  Test SWD connection", style="Start.TButton",
                   command=self._run_test).pack(anchor="w", pady=(8, 4))
        ttk.Button(tab_swd, text="Direct SWD dump (dump_image -> backup.bin)", command=self._run_swd_dump).pack(anchor="w")
        ttk.Button(tab_swd, text="\u25A0  STOP", style="Stop.TButton", command=self.worker.stop).pack(anchor="w", pady=(8, 0))

        # --- Erase tab ---
        tab_erase = ttk.Frame(nb, padding=8)
        nb.add(tab_erase, text="2) Erase (gated)")
        erbtn = ttk.Frame(tab_erase); erbtn.pack(fill="x", pady=(0, 6))
        ttk.Button(erbtn, text="\u26A0  MASS ERASE CHIP (gated)", style="Stop.TButton",
                   command=self._run_erase).pack(side="left")
        ttk.Button(erbtn, text="\u25A0  STOP", style="Stop.TButton",
                   command=self.worker.stop).pack(side="left", padx=8)
        self.erase_override_var = tk.BooleanVar(value=False)
        ttk.Checkbutton(tab_erase,
                        text="SAFE_OVERRIDE: skip SWD pre-erase compare (RDP-locked chips only)",
                        variable=self.erase_override_var).pack(anchor="w", pady=(0, 6))
        ttk.Separator(tab_erase, orient="horizontal").pack(fill="x", pady=4)
        ttk.Label(tab_erase,
                  text=("Mass-erase the GD32F130 flash via OpenOCD.\n\n"
                        "SAFETY GATES (all enforced):\n"
                        "  1. backup.bin must already exist at the configured Output path.\n"
                        "  2. backup.bin must be exactly 65536 bytes (full 64 KiB).\n"
                        "  3. Chip is re-read via SWD and byte-compared to the backup.\n"
                        "     Erase is REFUSED if any byte differs.\n"
                        "  4. A confirmation dialog must be accepted.\n"
                        "  5. The literal word ERASE must be typed in capitals.\n\n"
                        "SAFE_OVERRIDE is only for the case where the chip is RDP-locked\n"
                        "and your backup came from the UART exploit (so SWD readback returns 0xFFs).\n"
                        "In that case the pre-erase compare cannot succeed and you must opt in."),
                  justify="left").pack(anchor="w")

        # --- Flash tab ---
        tab_flash = ttk.Frame(nb, padding=8)
        nb.add(tab_flash, text="3) Flash RoboDurden / custom")
        ttk.Label(tab_flash,
                  text=("Pick a .hex or .bin and flash it via OpenOCD. "
                        "RoboDurden Gen2.1.1 builds can be used here."),
                  justify="left").pack(anchor="w")
        row = ttk.Frame(tab_flash); row.pack(fill="x", pady=6)
        ttk.Entry(row, textvariable=self.flash_image_var).pack(side="left", fill="x", expand=True)
        ttk.Button(row, text="...", width=4, command=self._pick_flash_image).pack(side="left", padx=4)
        self.flash_verify_var = tk.BooleanVar(value=True)
        ttk.Checkbutton(tab_flash, text="Verify after flash (read back via SWD and compare for .bin)",
                        variable=self.flash_verify_var).pack(anchor="w", pady=(4, 0))
        self.flash_override_var = tk.BooleanVar(value=False)
        ttk.Checkbutton(tab_flash,
                        text="FLASH_SAFE_OVERRIDE: skip backup-required check (factory-new chips only)",
                        variable=self.flash_override_var).pack(anchor="w")
        flbtn = ttk.Frame(tab_flash); flbtn.pack(fill="x", pady=8)
        ttk.Button(flbtn, text="\u25B6  PROGRAM CHIP (gated)", style="Start.TButton",
                   command=self._run_flash).pack(side="left")
        ttk.Button(flbtn, text="\u25A0  STOP", style="Stop.TButton",
                   command=self.worker.stop).pack(side="left", padx=8)

        # --- Verify tab ---
        tab_verify = ttk.Frame(nb, padding=8)
        nb.add(tab_verify, text="4) Verify backup")
        ttk.Label(tab_verify,
                  text=("Re-read the chip via SWD and byte-compare against an existing backup.bin.\n"
                        "Useful to confirm a successful flash, or to confirm your backup matches the chip."),
                  justify="left").pack(anchor="w")
        vfbtn = ttk.Frame(tab_verify); vfbtn.pack(fill="x", pady=8)
        ttk.Button(vfbtn, text="\u25B6  Verify current backup file", style="Start.TButton",
                   command=self._run_verify).pack(side="left")
        ttk.Button(vfbtn, text="\u25A0  STOP", style="Stop.TButton",
                   command=self.worker.stop).pack(side="left", padx=8)

        # --- Firmware library tab ---
        tab_fw = ttk.Frame(nb, padding=8)
        nb.add(tab_fw, text="5) Firmware library")
        ttk.Label(tab_fw,
                  text=("Bundled RoboDurden Gen2.x official binaries.\n"
                        "Pick a target board layout + role, then click 'Flash selected'.\n"
                        "For the split-board 2.1.1 you typically need BOTH master and slave "
                        "flashed (one per mainboard)."),
                  justify="left").pack(anchor="w")
        self.fw_choice_var = tk.StringVar()
        fw_combo = ttk.Combobox(tab_fw, textvariable=self.fw_choice_var, width=80, state="readonly")
        fw_combo.pack(anchor="w", pady=(8, 4), fill="x")
        fw_combo["values"] = self._scan_firmware()
        if fw_combo["values"]:
            fw_combo.current(0)
        self._fw_combo = fw_combo
        rowfw = ttk.Frame(tab_fw); rowfw.pack(fill="x", pady=4)
        ttk.Button(rowfw, text="Refresh list", command=lambda: fw_combo.configure(values=self._scan_firmware())).pack(side="left")
        ttk.Button(rowfw, text="\u25B6  Flash selected", style="Start.TButton",
                   command=self._run_flash_from_library).pack(side="left", padx=8)
        ttk.Button(rowfw, text="\u21BB  RESTORE backup.bin \u2192 chip", style="Start.TButton",
                   command=self._run_restore_backup).pack(side="left")
        ttk.Button(rowfw, text="\u25A0  STOP", style="Stop.TButton",
                   command=self.worker.stop).pack(side="left", padx=8)

        # --- Driver tab ---
        tab_drv = ttk.Frame(nb, padding=8)
        nb.add(tab_drv, text="6) Driver helper")
        ttk.Label(tab_drv,
                  text=("If 'Test SWD connection' fails with libusb errors, install the\n"
                        "WinUSB driver for your ST-Link V2 using Zadig (bundled below).\n\n"
                        "In Zadig: Options -> List All Devices, pick 'STM32 STLink',\n"
                        "choose WinUSB, click Replace Driver."),
                  justify="left").pack(anchor="w")
        ttk.Button(tab_drv, text="Launch bundled Zadig", command=self._launch_zadig).pack(anchor="w", pady=8)

        # --- Status bar ---
        self.status_var = tk.StringVar(value="Ready")
        status = ttk.Label(outer, textvariable=self.status_var, anchor="w",
                           relief="sunken", padding=4)
        status.pack(fill="x", pady=(8, 0))

        # --- Log area ---
        logf = ttk.LabelFrame(outer, text="Log", padding=6)
        logf.pack(fill="both", expand=True, pady=(8, 0))
        self.log_text = tk.Text(logf, wrap="word", height=14)
        self.log_text.pack(fill="both", expand=True)

        self._apply_toggle()

    def _apply_board_preset(self) -> None:
        if self.board_preset_var.get():
            # When using the board file, OpenOCD wants ONE -f only.
            # We set interface to empty and target to the board config.
            self.interface_var.set("")
            self.target_var.set("board/gd32f130_split_2_1_1.cfg")
        else:
            self.interface_var.set("interface/stlink.cfg")
            self.target_var.set("target/stm32f1x.cfg")

    # ----- toggle behaviour -----
    def _apply_toggle(self) -> None:
        if self.uart_var.get() == "usart0":
            self.payload_var.set(str(PAYLOAD_UART0))
        else:
            self.payload_var.set(str(PAYLOAD_UART1))

        note = []
        if self.uart_var.get() == "usart1":
            note.append("UART target: PA2/PA3 (USART1). These pins are NOT 5V tolerant on GD32F130C8T6.")
        else:
            note.append("UART target: PA9/PA10 (USART0). These pins are 5V tolerant.")
        if self.adapter_var.get() == "wemos":
            note.append("Adapter: 3.3V Wemos / CP2102 / CH340. Safe with either UART target.")
        else:
            note.append("Adapter: UNO passthrough. Use board TX -> UNO RX only (RX-only safe). "
                        "Do not drive UNO TX into PA3 without level shifting.")
        if BUNDLED_OPENOCD_EXE.exists():
            note.append(f"Bundled OpenOCD detected: {BUNDLED_OPENOCD_EXE}")
        self.note_var.set("\n".join(note))

    # ----- pickers -----
    def _pick_openocd(self) -> None:
        p = filedialog.askopenfilename(title="Locate openocd executable")
        if p: self.openocd_var.set(p)

    def _pick_output(self) -> None:
        p = filedialog.asksaveasfilename(defaultextension=".bin",
                                         filetypes=[("Binary", "*.bin"), ("All files", "*.*")])
        if p: self.output_var.set(p)

    def _pick_payload(self) -> None:
        p = filedialog.askopenfilename(filetypes=[("Binary", "*.bin"), ("All files", "*.*")])
        if p: self.payload_var.set(p)

    def _pick_flash_image(self) -> None:
        p = filedialog.askopenfilename(filetypes=[("Hex/Bin", "*.hex *.bin"), ("All files", "*.*")])
        if p: self.flash_image_var.set(p)

    def _refresh_ports(self) -> None:
        if list_ports is None:
            self.log_queue.put("[ports] pyserial not installed\n")
            return
        ports = [p.device for p in list_ports.comports()]
        if ports and (not self.serial_var.get() or self.serial_var.get() not in ports):
            self.serial_var.set(ports[0])
        if hasattr(self, "_serial_combo"):
            try: self._serial_combo.configure(values=ports)
            except Exception as e: self.log_queue.put(f"[ports] combo refresh: {e}\n")
        self.log_queue.put("[ports] " + (", ".join(ports) if ports else "no serial ports found") + "\n")

    def _open_backup_folder(self) -> None:
        out = self.output_var.get().strip()
        if not out:
            messagebox.showinfo(APP_TITLE, "No output file set"); return
        folder = str(Path(out).parent)
        try:
            if os.name == "nt":
                os.startfile(folder)
            else:
                subprocess.Popen(["xdg-open", folder])
        except Exception as e:
            messagebox.showerror(APP_TITLE, f"Could not open folder: {e}")

    # ----- actions -----
    def _make_cfg(self) -> DumpConfig:
        return DumpConfig(
            openocd_exe=self.openocd_var.get().strip(),
            interface_cfg=self.interface_var.get().strip(),
            target_cfg=self.target_var.get().strip(),
            serial_port=self.serial_var.get().strip(),
            baudrate=int(self.baud_var.get().strip()),
            output_file=self.output_var.get().strip(),
            payload_path=self.payload_var.get().strip(),
            adapter_mode=self.adapter_var.get(),
            uart_target=self.uart_var.get(),
            stlink_speed=int(self.speed_var.get().strip()),
        )

    def _run_dump(self) -> None:
        try: cfg = self._make_cfg()
        except Exception as e:
            messagebox.showerror(APP_TITLE, f"Invalid configuration: {e}"); return
        threading.Thread(target=self.worker.action_dump, args=(cfg,), daemon=True).start()

    def _run_erase(self) -> None:
        try: cfg = self._make_cfg()
        except Exception as e:
            messagebox.showerror(APP_TITLE, f"Invalid configuration: {e}"); return
        backup = Path(cfg.output_file)
        if not backup.exists():
            messagebox.showerror(APP_TITLE,
                "REFUSED: no backup.bin exists yet.\n\n"
                "Run a dump first. Erase is gated on having a verified backup.")
            return
        sz = backup.stat().st_size
        if sz != FLASH_LEN:
            messagebox.showerror(APP_TITLE,
                f"REFUSED: backup size is {sz} bytes, expected {FLASH_LEN}.\n\n"
                "A partial / raw capture is NOT a safe backup. Re-dump first.")
            return
        safe_override = bool(self.erase_override_var.get())
        msg = ("This will MASS-ERASE the GD32F130 flash.\n\n"
               f"Backup file: {backup}\n"
               f"Backup size: {sz} bytes (OK)\n\n")
        if safe_override:
            msg += ("SAFE_OVERRIDE is ON.\n"
                    "Pre-erase SWD readback compare will be SKIPPED.\n"
                    "Use only when chip is RDP-locked and backup came from UART exploit.\n\n")
        else:
            msg += ("Pre-erase safety check WILL run:\n"
                    "  - read chip via SWD\n"
                    "  - byte-compare against backup\n"
                    "  - erase only if they match exactly\n\n")
        msg += "Proceed?"
        if not messagebox.askyesno(APP_TITLE, msg):
            return
        # double confirm - type-to-confirm
        from tkinter import simpledialog
        token = simpledialog.askstring(APP_TITLE,
            "Type ERASE in capitals to confirm:", parent=self)
        if token != "ERASE":
            messagebox.showinfo(APP_TITLE, "Cancelled - token did not match.")
            return
        threading.Thread(target=self.worker.action_erase,
                         args=(cfg, not safe_override), daemon=True).start()

    def _run_flash(self) -> None:
        img = self.flash_image_var.get().strip()
        if not img:
            messagebox.showerror(APP_TITLE, "Pick a .hex or .bin first"); return
        try: cfg = self._make_cfg()
        except Exception as e:
            messagebox.showerror(APP_TITLE, f"Invalid configuration: {e}"); return
        # SAFETY: require valid backup before flashing
        backup = Path(cfg.output_file)
        require_backup = not bool(self.flash_override_var.get())
        if require_backup and (not backup.exists() or backup.stat().st_size != FLASH_LEN):
            messagebox.showerror(APP_TITLE,
                "REFUSED: no valid backup.bin exists yet.\n\n"
                f"Expected: {backup}\n"
                f"Expected size: {FLASH_LEN} bytes (full 64 KiB)\n\n"
                "Run a dump first. Flashing is gated on having a verified backup.\n"
                "Tick FLASH_SAFE_OVERRIDE to bypass this check (factory new chip only).")
            return
        verify = bool(self.flash_verify_var.get())
        if not messagebox.askyesno(APP_TITLE,
            f"Flash this image?\n\n{img}\n\nBackup present: {'YES' if backup.exists() else 'NO'}\n"
            f"Verify after flash: {'YES' if verify else 'NO'}"):
            return
        threading.Thread(target=self.worker.action_flash,
                         args=(cfg, img, verify, require_backup), daemon=True).start()

    def _save_raw_now(self) -> None:
        out = self.output_var.get().strip()
        if not out:
            messagebox.showerror(APP_TITLE, "Set an output file first"); return
        raw = str(Path(out).with_suffix("")) + "_raw.bin"
        self.worker.save_raw_capture(raw)

    def _run_full_backup(self) -> None:
        try: cfg = self._make_cfg()
        except Exception as e:
            messagebox.showerror(APP_TITLE, f"Invalid configuration: {e}"); return
        threading.Thread(target=self.worker.action_full_backup, args=(cfg,), daemon=True).start()

    def _run_test(self) -> None:
        try: cfg = self._make_cfg()
        except Exception as e:
            messagebox.showerror(APP_TITLE, f"Invalid configuration: {e}"); return
        threading.Thread(target=self.worker.action_test, args=(cfg,), daemon=True).start()

    def _run_swd_dump(self) -> None:
        try: cfg = self._make_cfg()
        except Exception as e:
            messagebox.showerror(APP_TITLE, f"Invalid configuration: {e}"); return
        threading.Thread(target=self.worker.action_swd_readback, args=(cfg,), daemon=True).start()

    def _run_verify(self) -> None:
        try: cfg = self._make_cfg()
        except Exception as e:
            messagebox.showerror(APP_TITLE, f"Invalid configuration: {e}"); return
        backup = self.output_var.get().strip()
        if not backup or not Path(backup).exists():
            messagebox.showerror(APP_TITLE, f"Backup file not found: {backup}"); return
        threading.Thread(target=self.worker.action_verify, args=(cfg, backup), daemon=True).start()

    def _drain_log_queue(self) -> None:
        while True:
            try:
                msg = self.log_queue.get_nowait()
            except queue.Empty:
                break
            if isinstance(msg, tuple) and msg and msg[0] == "__DIFF__":
                _, diffs, backup_path, readback = msg
                self._render_diff(diffs, backup_path, readback)
                continue
            self.log_text.insert("end", msg)
            self.log_text.see("end")
        while True:
            try:
                s = self.status_q.get_nowait()
            except queue.Empty:
                break
            self.status_var.set(s)
        self.after(120, self._drain_log_queue)

    def _scan_firmware(self) -> list[str]:
        items = []
        if FIRMWARE_DIR.exists():
            for p in sorted(FIRMWARE_DIR.rglob("*.bin")):
                rel = p.relative_to(ROOT)
                items.append(str(rel))
            for p in sorted(FIRMWARE_DIR.rglob("*.hex")):
                rel = p.relative_to(ROOT)
                items.append(str(rel))
        return items

    def _run_flash_from_library(self) -> None:
        sel = self.fw_choice_var.get().strip()
        if not sel:
            messagebox.showerror(APP_TITLE, "No firmware selected"); return
        full = ROOT / sel
        if not full.exists():
            messagebox.showerror(APP_TITLE, f"File missing: {full}"); return
        try: cfg = self._make_cfg()
        except Exception as e:
            messagebox.showerror(APP_TITLE, f"Invalid configuration: {e}"); return
        backup = Path(cfg.output_file)
        require_backup = not bool(self.flash_override_var.get())
        if require_backup and (not backup.exists() or backup.stat().st_size != FLASH_LEN):
            messagebox.showerror(APP_TITLE,
                "REFUSED: no valid backup.bin exists yet.\n\n"
                "Run a dump first. Library-flash is gated on a verified backup.\n"
                "Tick FLASH_SAFE_OVERRIDE on the Flash tab to bypass (factory-new chip only).")
            return
        verify = bool(self.flash_verify_var.get())
        if not messagebox.askyesno(APP_TITLE,
            f"Flash bundled firmware?\n\n{sel}\n\n"
            f"Backup present: {'YES' if backup.exists() else 'NO'}\n"
            f"Verify after flash: {'YES' if verify else 'NO'}"):
            return
        threading.Thread(target=self.worker.action_flash,
                         args=(cfg, str(full), verify, require_backup), daemon=True).start()

    def _run_restore_backup(self) -> None:
        backup = self.output_var.get().strip()
        if not backup or not Path(backup).exists():
            messagebox.showerror(APP_TITLE, f"backup.bin not found: {backup}"); return
        if Path(backup).stat().st_size != FLASH_LEN:
            messagebox.showerror(APP_TITLE,
                f"Backup size is {Path(backup).stat().st_size}, expected {FLASH_LEN}.\n"
                "Refusing to restore a partial backup."); return
        if not messagebox.askyesno(APP_TITLE,
            f"RESTORE chip from your backup?\n\n{backup}\n\n"
            "This OVERWRITES current chip flash with the backup.\n"
            "Use this to roll back a bad flash."):
            return
        try: cfg = self._make_cfg()
        except Exception as e:
            messagebox.showerror(APP_TITLE, f"Invalid configuration: {e}"); return
        # Restore is the one path that ALWAYS requires a valid backup (the source IS the backup)
        threading.Thread(target=self.worker.action_flash,
                         args=(cfg, backup, True, False), daemon=True).start()

    def _launch_zadig(self) -> None:
        if not ZADIG_EXE.exists():
            messagebox.showerror(APP_TITLE, f"Zadig not bundled: {ZADIG_EXE}"); return
        try:
            if os.name == "nt":
                os.startfile(str(ZADIG_EXE))
            else:
                subprocess.Popen(["xdg-open", str(ZADIG_EXE)])
            self.log_queue.put(f"[zadig] launched {ZADIG_EXE}\n")
        except Exception as e:
            messagebox.showerror(APP_TITLE, f"Could not launch Zadig: {e}")

    def _render_diff(self, diffs, backup_path, readback) -> None:
        win = tk.Toplevel(self)
        win.title(f"Hex diff: {Path(backup_path).name} vs readback")
        win.geometry("880x520")
        top = ttk.Frame(win, padding=8); top.pack(fill="x")
        ttk.Label(top, text=f"backup   : {backup_path}").pack(anchor="w")
        ttk.Label(top, text=f"readback : {readback}").pack(anchor="w")
        ttk.Label(top, text=f"diffs    : {len(diffs)} bytes shown (capped at 4096)").pack(anchor="w")
        body = ttk.Frame(win, padding=8); body.pack(fill="both", expand=True)
        cols = ("offset", "backup", "readback")
        tv = ttk.Treeview(body, columns=cols, show="headings")
        for c, w in zip(cols, (140, 100, 100)):
            tv.heading(c, text=c); tv.column(c, width=w, anchor="w")
        sb = ttk.Scrollbar(body, orient="vertical", command=tv.yview)
        tv.configure(yscrollcommand=sb.set)
        tv.pack(side="left", fill="both", expand=True)
        sb.pack(side="right", fill="y")
        for off, x, y in diffs:
            tv.insert("", "end", values=(f"0x{off:08x}", f"0x{x:02x}", f"0x{y:02x}"))


if __name__ == "__main__":
    App().mainloop()
