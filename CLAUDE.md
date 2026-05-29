# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What This Repo Contains

This is the **SimWheel** project — an Android app that turns a phone into a wireless sim racing steering wheel + controller proxy for PC. There are two components:

1. **Android APK (v5.2.2)** — compiled Flutter app, no Dart source code is present. Delivered as a split XAPK across three zip parts.
2. **PC Receiver (C++ source)** — Windows executable that receives UDP data and feeds it into vJoy.

There is **no Flutter source code** in this repo. The Dart logic is compiled AOT into `libapp.so` inside the APK splits. All Android modifications must work around this constraint.

## Assembling the XAPK

The XAPK is split across three zip files. Reassemble with:
```bash
unzip -p simwheel_xapk_part_1_of_3.zip com.ik.simwheel_5.2.2.xapk.part01.bin > /tmp/part1.bin
unzip -p simwheel_xapk_part_2_of_3.zip com.ik.simwheel_5.2.2.xapk.part02.bin > /tmp/part2.bin
unzip -p simwheel_xapk_part_3_of_3.zip com.ik.simwheel_5.2.2.xapk.part03.bin > /tmp/part3.bin
cat /tmp/part1.bin /tmp/part2.bin /tmp/part3.bin > simwheel_5.2.2.xapk
```

The resulting XAPK contains:
- `com.ik.simwheel.apk` — base APK (Flutter assets, classes.dex, resources)
- `config.arm64_v8a.apk` — `lib/arm64-v8a/libapp.so` (Dart AOT) + `libflutter.so`
- `config.armeabi_v7a.apk` — same for 32-bit ARM
- Language/density splits

To decompile for modification:
```bash
apktool d com.ik.simwheel.apk -o apk_decompiled/
```

## APK Architecture (Flutter AOT)

Since Dart is compiled AOT, **the app cannot be modified by editing Dart code**. The two valid modification approaches are:

**Option A — Kotlin wrapper layer (recommended):**
- Decompile with apktool
- Add a new Kotlin/Java `Activity` or `Service` that handles PS4 Bluetooth HID
- Forward data into Flutter via `MethodChannel` or by injecting touch/motion events into the Flutter view
- Repackage and sign

**Option B — Intercept at the UDP layer:**
- Add a Kotlin service that connects to the PS4 controller via Bluetooth
- Translates PS4 gyro/trigger/button data into the SimWheel UDP JSON format
- Sends the JSON to the PC receiver directly (bypassing or replacing the Flutter send path)

The Flutter layer already handles the main UI (steering wheel animation, throttle/brake bars). The goal is to feed PS4 controller data into that pipeline.

## v5 Gyro — What Works and Must Not Break

The v5 `libapp.so` has accurate gyro math: a **90° physical phone tilt = 90° of steering input**. The range is configurable from 90° to 2520°, default 900°. This is correct and must be preserved. v6 had broken gyro math that caused 90° physical tilt to register as ~750° — do not carry forward any v6 gyro math.

Known Flutter preference keys inside `libapp.so` (from string analysis):
- `gyro_steering` — boolean, whether gyro steering is active
- `gyro_filter` — 0–99 smoothing, default 80
- `gyro_sensitivity` — gyro sensitivity multiplier
- `show_degree` — boolean, display current degrees on screen
- `mouse_sensitivity`
- `nor_gyro`, `is_gyro_set`, `btns_gyro`

Phone sensor access uses the `dev.fluttercommunity.plus/sensors/accelerometer` Flutter plugin.

## UDP Protocol (Port 4567, JSON)

The app communicates with the PC receiver over local WiFi UDP on port 4567. JSON is sent only on change.

**Phone → PC (control data):**
```json
{
  "phoneName": "MyPhone",
  "steering": 450.0,
  "throttle": 0.75,
  "brake": 0.25,
  "zaxis": 0.5,
  "1": true,
  "201": false
}
```

- `steering`: float in degrees; range 90–2520 (default 900), centered at 0
- `throttle` / `brake` / `zaxis`: float 0.0–1.0
- Button keys are string integers; values are bool

**Discovery handshake:**
- Phone broadcasts `{"type": "discover", "phoneName": "..."}` 
- PC replies `{"type": "discover_reply", "name": "PC_NAME", "connection": "WiFi"}`

## Button Code System (PC Receiver)

| Code Range | Mapping |
|-----------|---------|
| 1–199 | vJoy joystick buttons |
| 200–225 | Keyboard A–Z |
| 230–239 | Space, Enter, Backspace, Tab, Shift, Ctrl, Alt, Win, ESC, CapsLock |
| 250–259 | Symbol keys (-, =, [, ], \\, ;, ', ,, ., /) |
| 300–309 | Number keys 0–9 |
| 350–353 | Arrow keys (Left, Right, Up, Down) |
| 360–363 | Home, End, PageUp, PageDown |
| 370–371 | Delete, Insert |
| 400–411 | F1–F12 |
| 500 / 501 / 503 | Left / Right / Middle mouse click |

## PC Receiver Build (C++)

Source: `SimWheeel_Receiver_-main.zip` → `SimWheeel_Receiver/`
- Solution: `SimWheeel_Receiver.sln` (Visual Studio)
- Single source file: `SimWheeel_Receiver/Receiver.cpp`
- Dependencies: **Winsock2**, **vJoy SDK** (`vJoyInterface.h` + `vJoyInterface.lib`), **nlohmann/json**, **iphlpapi**

vJoy axis mappings:
- Steering → `HID_USAGE_X` (normalized to −1..1, mapped to 0..32768)
- Throttle → `HID_USAGE_Y`
- Brake → `HID_USAGE_Z`
- Z-Axis (clutch) → `HID_USAGE_RZ`

At startup the receiver prompts for steering range (90–2520°, default 900). This must match the app's configured range.

## PS4 Controller on Android (Bluetooth HID)

The DualShock 4 exposes itself as a standard Bluetooth HID device. Android's `InputDevice` and `MotionEvent` APIs can read it without root:
- Gyro/accelerometer: available via `InputDevice.getMotionRange()` after pairing
- Analog triggers (L2/R2): `MotionEvent.AXIS_LTRIGGER` / `AXIS_RTRIGGER`, float 0.0–1.0, full analog precision
- Buttons: standard `KeyEvent` codes
- Vibration: `Vibrator` or `InputDevice.getVibrator()`

Android requires `BLUETOOTH`, `BLUETOOTH_ADMIN`, `BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN` permissions (with runtime request for Android 12+).

## Signing APKs

After apktool repack, sign with:
```bash
apktool b apk_decompiled/ -o unsigned.apk
zipalign -v 4 unsigned.apk aligned.apk
apksigner sign --ks keystore.jks --out signed.apk aligned.apk
```

For a debug/test keystore:
```bash
keytool -genkeypair -v -keystore debug.jks -alias debug -keyalg RSA -keysize 2048 -validity 10000
```
