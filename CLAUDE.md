# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What This Repo Contains

This is the **SimWheel** project — an Android app that turns a phone into a wireless sim racing steering wheel + controller proxy for PC. There are two components:

1. **Android APK (v5.2.2)** — compiled Flutter app, no Dart source code is present. Delivered as a split XAPK across three zip parts.
2. **PC Receiver (C++ source)** — Windows executable that receives UDP data and feeds it into vJoy.

The file named `READ` is a **chat transcript** from a previous Claude session. It is not source code.

There is **no Flutter source code** in this repo. The Dart logic is compiled AOT into `libapp.so` inside the APK splits. All Android modifications must work around this constraint.

## Build Environment (This Container)

Available: **Java 21** (OpenJDK), **Gradle 8.14.3** (`/opt/gradle/bin/gradle`), Python 3.11.

**Android SDK is NOT installed.** To build any Android APK you must first install it:

```bash
# Download command-line tools
wget https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -O /tmp/cmdtools.zip
unzip /tmp/cmdtools.zip -d /tmp/android-sdk/cmdline-tools/latest
export ANDROID_HOME=/tmp/android-sdk
export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools

# Accept licenses and install build tools
yes | sdkmanager --licenses
sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0"
```

For APK decompilation (apktool) — not installed by default:
```bash
wget https://github.com/iBotPeaches/Apktool/releases/download/v2.9.3/apktool_2.9.3.jar -O /usr/local/bin/apktool.jar
# Run as: java -jar /usr/local/bin/apktool.jar d com.ik.simwheel.apk -o apk_decompiled/
```

## Recommended Build Strategy

**Do not patch the Flutter APK with overlays or AccessibilityService.** Previous attempts using these approaches produced apps that did not control the on-screen buttons and therefore the PC receiver never picked them up.

**The correct approach** is a standalone Kotlin Android app that speaks the same UDP JSON protocol as v5.2.2. This sidesteps the AOT binary problem entirely:
- The new app connects to the PS4 controller via Bluetooth HID
- Reads PS4 gyro for steering, L2/R2 triggers for throttle/brake
- Sends the identical UDP JSON to port 4567 that the existing PC receiver parses
- Renders its own UI with steering wheel visualization, calibration, and button mapping
- Receives UDP back from the PC for vibration events (requires receiver modification too — see below)

The v5.2.2 XAPK is the **reference** for the protocol and gyro math, not the base to patch.

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

## APK Architecture (Flutter AOT, for reference)

The XAPK structure — useful for reading the reference app:
- `libapp.so` — Dart AOT binary, all app logic. Cannot be edited without rebuilding from Dart source.
- `libflutter.so` — Flutter engine, do not touch.
- `classes.dex` — Kotlin/Java Android glue code (activities, services, plugins).
- `assets/flutter_assets/` — images, fonts, shaders (can be replaced via apktool if needed).
- The app uses `dev.britannio.in_app_review` and Google Play Billing; those plugins are in `classes.dex`.

Flutter preference keys confirmed in `libapp.so` strings — these are what the v5.2.2 settings screen reads/writes:
`gyro_steering`, `gyro_filter`, `gyro_sensitivity`, `show_degree`, `mouse_sensitivity`, `nor_gyro`, `is_gyro_set`, `btns_gyro`.

## Gyro Math That Must Not Change

v5's gyro is accurate: **90° physical tilt = 90° of steering input**. Range is 90–2520°, default 900°. v6 broke this — a 90° physical tilt registered as ~750°. Never carry forward v6 gyro math.

For a Kotlin implementation, the correct gyro reading uses `SensorManager.SENSOR_DELAY_GAME` on `TYPE_GYROSCOPE` and integrates the Z-axis angular velocity over time (radians → degrees, then clamp to configured range). Do **not** use the raw accelerometer tilt angle directly — that is what caused the v6 ratio error.

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

**Vibration feedback — PC → Phone (not yet implemented in receiver):**  
The current PC receiver (`Receiver.cpp`) only sends one UDP packet back: the discovery reply. To implement vibration feedback, the receiver needs to send:
```json
{"type": "vibration", "intensity": 0.8, "duration": 200}
```
`intensity` is 0.0–1.0, `duration` is milliseconds. The phone app listens for these and calls `Vibrator.vibrate()`. This requires modifying both `Receiver.cpp` (add a `sendto` call when game events occur) and the Android app. The receiver currently has no telemetry-in path from the game itself — vibration events would need to come from a sim telemetry plugin (e.g., via shared memory or named pipe from the sim).

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

## PS4 Controller Gyro on Android

The DualShock 4 exposes a gyroscope through Android's `InputDevice` API when paired via Bluetooth HID. The axes are:
- `MotionEvent.AXIS_RX` — gyro X (pitch)
- `MotionEvent.AXIS_RY` — gyro Y (roll)  
- `MotionEvent.AXIS_RZ` — gyro Z (yaw) — **this is the steering axis**

Read them in `onGenericMotionEvent` on the `Activity` or `View`:
```kotlin
override fun onGenericMotionEvent(event: MotionEvent): Boolean {
    if (event.source and InputDevice.SOURCE_GAMEPAD != 0) {
        val gyroZ = event.getAxisValue(MotionEvent.AXIS_RZ)
        val lt    = event.getAxisValue(MotionEvent.AXIS_LTRIGGER)  // 0.0–1.0
        val rt    = event.getAxisValue(MotionEvent.AXIS_RTRIGGER)  // 0.0–1.0
    }
}
```

Steering angle accumulation:
```kotlin
steeringDeg += gyroZ * dtSeconds * (180f / Math.PI.toFloat()) * sensitivity
steeringDeg = steeringDeg.coerceIn(-maxDegrees, maxDegrees)
```

Recenter button (typically PS button or Options) should reset `steeringDeg = 0`.

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
