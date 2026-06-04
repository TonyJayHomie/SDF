"""REAL audit. NO STUBS. Tests actual code paths against real openocd binary."""
import sys, os, subprocess, shutil, threading, ast
sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', 'src'))
PROBS = []
def fail(m): PROBS.append(m); print("BUG:", m)
def ok(m): print("OK :", m)

import hbdumper as h
ok("module imports")

for p in (h.PAYLOAD_UART0, h.PAYLOAD_UART1, h.BUNDLED_OPENOCD_SCRIPTS, h.FIRMWARE_DIR, h.ZADIG_EXE):
    if not p.exists(): fail(f"missing: {p}")
    else: ok(f"bundled: {p.name if p.is_file() else p}")

class C: pass
cfg = C()
cfg.openocd_exe = shutil.which("openocd") or "openocd"
cfg.interface_cfg="interface/stlink.cfg"; cfg.target_cfg="target/stm32f1x.cfg"
cfg.serial_port="/dev/null"; cfg.baudrate=115200; cfg.output_file="/tmp/_bk.bin"
cfg.payload_path=str(h.PAYLOAD_UART1); cfg.adapter_mode="wemos"
cfg.uart_target="usart1"; cfg.stlink_speed=1000

for name in ("build_openocd_test_cmd","build_openocd_erase_cmd"):
    cmd = getattr(h,name)(cfg)
    if cmd[0]==cfg.openocd_exe and "-c" in cmd: ok(f"{name}: {len(cmd)} args")
    else: fail(name)

cmd = h.build_openocd_swd_readback_cmd(cfg,"/tmp/_rb.bin")
ok(f"swd_readback: {len(cmd)} args")
cmd = h.build_openocd_flash_cmd(cfg,"/tmp/fake.bin")
s = " ".join(cmd)
if "stm32f1x unlock" in s and "program" in s: ok(f"flash: {len(cmd)} args")
else: fail("flash cmd missing tokens")
payload = h.PAYLOAD_UART1.read_bytes()
cmd = h.build_openocd_load_cmd(cfg, payload)
s = " ".join(cmd)
for token in ("mww 0x20000000","reg pc","reg msp","resume"):
    if token not in s: fail(f"load cmd missing: {token}")
ok(f"load: {len(cmd)} args, {len(payload)//4} mww")

try:
    p = subprocess.run([cfg.openocd_exe,"--version"], capture_output=True, text=True, timeout=10)
    if "Open On-Chip Debugger" in (p.stdout+p.stderr): ok("real openocd reachable")
    else: fail("openocd version unexpected")
except Exception as e: fail(f"openocd run: {e}")

try:
    p = subprocess.run(h.build_openocd_test_cmd(cfg), capture_output=True, text=True, timeout=15)
    if p.returncode != 0 and ("open failed" in p.stderr or "Error" in p.stderr):
        ok(f"test cmd correctly fails without hardware (exit {p.returncode})")
    else: fail(f"test cmd unexpected: {p.returncode}")
except Exception as e: fail(f"test cmd: {e}")

import struct, zlib
payload64 = bytes(range(256))*256
crc = zlib.crc32(payload64) & 0xFFFFFFFF
frame_ok = b"HBDP"+struct.pack("<I",len(payload64))+payload64+struct.pack("<I",crc)+b"DONE"

class FA:
    msgs=[]
    log_queue = type("Q",(),{"put":staticmethod(lambda *a,**kw:None)})()
    status_q  = type("S",(),{"put":staticmethod(lambda *a,**kw: FA.msgs.append(a[0]))})()

def parse(buf):
    w = h.Worker.__new__(h.Worker)
    w.app = FA(); w.serial_buffer = bytearray(buf)
    w.stop_event = threading.Event(); w.proc=None; w.serial_thread=None
    return h.Worker.parse_hbdp_frame(w)

if parse(frame_ok) == payload64: ok("HBDP parser: valid")
else: fail("HBDP valid")
if parse(frame_ok[:-2]) is None: ok("HBDP parser: truncated->None")
else: fail("HBDP truncated")
bad_crc = b"HBDP"+struct.pack("<I",len(payload64))+payload64+struct.pack("<I",0xDEADBEEF)+b"DONE"
if parse(bad_crc) == payload64: ok("HBDP parser: bad CRC returns payload w/ warning")
else: fail("HBDP bad CRC")
bad_tr = b"HBDP"+struct.pack("<I",len(payload64))+payload64+struct.pack("<I",crc)+b"XXXX"
if parse(bad_tr) is None: ok("HBDP parser: bad trailer->None")
else: fail("HBDP bad trailer")

bins = list(h.FIRMWARE_DIR.rglob("*.bin"))
if len(bins) >= 20: ok(f"firmware library: {len(bins)}")
else: fail(f"firmware library small: {len(bins)}")
if any("2.1.1" in p.name for p in bins): ok("2.1.1 binaries present")
else: fail("no 2.1.1 binaries")

def fresh():
    w = h.Worker.__new__(h.Worker)
    w.app=FA(); w.serial_buffer=bytearray(); w.stop_event=threading.Event()
    w.proc=None; w.serial_thread=None
    return w

cfg2 = h.DumpConfig(openocd_exe=cfg.openocd_exe, interface_cfg=cfg.interface_cfg,
    target_cfg=cfg.target_cfg, serial_port="/dev/null", baudrate=115200,
    output_file="/tmp/_HBD_doesnotexist.bin", payload_path=cfg.payload_path,
    adapter_mode="wemos", uart_target="usart1", stlink_speed=1000)
if os.path.exists(cfg2.output_file): os.unlink(cfg2.output_file)

FA.msgs.clear()
h.Worker.action_erase(fresh(), cfg2, backup_required=True)
if any("no backup" in m.lower() or "abort" in m.lower() for m in FA.msgs): ok("erase no-backup -> abort")
else: fail(f"erase no-backup: {FA.msgs}")

with open(cfg2.output_file,"wb") as f: f.write(b"\x00"*100)
FA.msgs.clear()
h.Worker.action_erase(fresh(), cfg2, backup_required=True)
if any("wrong size" in m.lower() or "abort" in m.lower() for m in FA.msgs): ok("erase wrong-size -> abort")
else: fail(f"erase wrong-size: {FA.msgs}")
os.unlink(cfg2.output_file)

FA.msgs.clear()
h.Worker.action_flash(fresh(), cfg2, "/tmp/fake_image.bin", verify_after=False, require_backup=True)
if any("no backup" in m.lower() or "abort" in m.lower() for m in FA.msgs): ok("flash no-backup -> abort")
else: fail(f"flash no-backup: {FA.msgs}")

# AST audit Worker doesn't touch Tk vars
src = open(os.path.join(os.path.dirname(__file__),'..','src','hbdumper.py')).read()
tree = ast.parse(src)
bad = []
for cls in ast.walk(tree):
    if isinstance(cls, ast.ClassDef) and cls.name == "Worker":
        for node in ast.walk(cls):
            if isinstance(node, ast.Attribute) and node.attr == "set":
                v = node.value
                if isinstance(v, ast.Attribute) and v.attr in ("status_var","note_var","output_var","openocd_var"):
                    bad.append(f"Worker.{v.attr}.set @line {node.lineno}")
            if isinstance(node, ast.Attribute) and node.attr == "insert":
                if isinstance(node.value, ast.Attribute) and node.value.attr == "log_text":
                    bad.append(f"Worker.log_text.insert @line {node.lineno}")
if bad:
    for b in bad: fail(b)
else: ok("Worker thread never directly touches Tk vars")

if "shell=True" in src: fail("shell=True present")
else: ok("no shell=True")

import re
bp = re.findall(r'except[^\n]*:\s*\n\s*pass\b', src)
if bp:
    for m in bp[:5]: print("  silent except:", repr(m))
    fail(f"{len(bp)} silent except: pass present")
else: ok("no silent except:pass")

print()
print("="*70)
print(f"AUDIT FOUND {len(PROBS)} PROBLEMS")
for p in PROBS: print("  -", p)
sys.exit(0 if not PROBS else 2)
