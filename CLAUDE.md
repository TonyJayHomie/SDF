# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What This Repo Contains

This is the **SimWheel** project — an Android app that turns a phone into a wireless sim racing steering wheel + controller proxy for PC. There are two components:

1. **Android APK (v5.2.2)** — compiled Flutter app, no Dart source code is present. Delivered as a split XAPK across three zip parts.
2. **PC Receiver (C++ source)** — Windows executable that receives UDP data and feeds it into vJoy.

The file named `READ` is a **chat transcript** from a previous Claude session. It is not source code.

There is **no Flutter source code** in this repo. The Dart logic is compiled AOT into `libapp.so` inside the APK splits. All Android modifications must work around this constraint.

## Plugin Java Sources

The three Java plugin classes live in `plugin_src/com/ik/simwheel/sim/`:
- `SimApp.java` — `Application` subclass; wraps `Window.Callback` to intercept gamepad events; injects a ⚙ gear button into `android.R.id.content` on every Activity resume
- `SimBridge.java` — PS4 input handler (gyro steering, analog triggers, button mapping), UDP relay to PC, 6-month promo bypass
- `SettingsActivity.java` — full-screen settings UI: live axis bars, deadzone/curve sliders, vibration controls, 16-button vJoy remap spinners, profile save/load

These are the sources of truth. When rebuilding, compile these → DEX → smali → copy into `build_work/base_dec/smali/com/ik/simwheel/sim/`.

## Build Environment (This Container)

Available: **Java 21** (OpenJDK), Python 3.11.

**Do NOT use dl.google.com** — it returns 403 host_not_allowed in this environment.

Install the Android build toolchain via apt:

```bash
sudo apt-get update -qq
DEBIAN_FRONTEND=noninteractive sudo apt-get install -qq -y \
    default-jdk-headless apktool android-sdk-build-tools
```

**Pre-downloaded JARs** (already committed in `build_work/sdk/` — do NOT re-download):
- `build_work/sdk/android.jar` — Android 34 bootclasspath
- `build_work/sdk/r8.jar` — D8/R8 DEX compiler
- `build_work/sdk/baksmali.jar` — DEX → smali disassembler

Tool paths after apt install:
- `apktool` → `/usr/bin/apktool`
- `aapt2` / `zipalign` / `apksigner` → `/usr/lib/android-sdk/build-tools/debian/` (also on PATH)
- `javac` → `/usr/bin/javac`

Compile Java plugin and convert to DEX (run from `build_work/`):
```bash
SDK=build_work/sdk/android.jar
mkdir -p build_work/build/classes
javac -source 1.8 -target 1.8 -bootclasspath $SDK \
      -d build_work/build/classes \
      $(find plugin_src -name '*.java')
java -cp build_work/sdk/r8.jar com.android.tools.r8.D8 --release --min-api 21 \
     --lib $SDK \
     --output build_work/build/dex \
     $(find build_work/build/classes -name '*.class')
java -jar build_work/sdk/baksmali.jar d build_work/build/dex/classes.dex \
     -o build_work/build/smali_out
cp -r build_work/build/smali_out/com/ik/simwheel/sim \
      build_work/base_dec/smali/com/ik/simwheel/sim
```

**Important Java source constraints** (required to compile against android.jar):
- Use `-source 1.8 -target 1.8` — no lambdas (use anonymous inner classes)
- No switch-on-strings (use if/else chains)
- No `buildMap{}` or other Kotlin stdlib calls
- Theme references in manifest: use `@android:style/Theme.Holo.Light` not Material (avoids aapt2 missing-resource errors)

## Build Strategy — Patching 5.2.2 Directly

**5.2.2 is the only base. No standalone app. No overlay. No parallel app.**

The correct approach patches the 5.2.2 XAPK directly via smali injection:

1. Decode base APK: `apktool d -f -q --use-aapt2 com.ik.simwheel.apk -o base_dec`
2. Neutralize Pairip: replace `LicenseContentProvider.smali` with a no-op, patch `LicenseClient.checkLicense()` to return-void
3. Write Java plugin classes (SimApp + SimBridge + SettingsActivity) → compile → DEX via r8/D8 → disassemble to smali via baksmali → copy smali files into `base_dec/smali/com/ik/simwheel/sim/`
4. Patch `base_dec/AndroidManifest.xml`: remove split requirements, set `android:name="com.ik.simwheel.sim.SimApp"`, remove Pairip entries, add BT/gamepad permissions, add SettingsActivity (no LAUNCHER intent-filter)
5. Build carrier: `apktool b -q --use-aapt2 base_dec -o carrier.apk`
6. Compose universal APK in Python: take carrier.apk, merge `lib/arm64-v8a/` + `lib/armeabi-v7a/` from original arm splits, bundle all 6 original split APKs as `assets/original_splits/` (padding to ≥99MB)
7. `zipalign -p -f 4` then `apksigner sign` with v1+v2+v3

**Do NOT merge xxhdpi/en/fr resources into base_dec** — the 9-patch PNGs from splits cause aapt2 compile errors. The base APK's own resources are sufficient.

**Proof the gyro is untouched:** `lib/arm64-v8a/libapp.so` in the final APK must match SHA256 `fdbe3192c28cc2c0c197d1a23b13fee67e14d56ba81817359344c2d1d6dc3648` from the original 5.2.2 arm64 split.

**6-month promo:** On first launch, `SimBridge.install()` writes `promo_install_time` to "SimWheelPromo" prefs. Each launch computes elapsed; if < 6 months writes `flutter.is_premium = true` (boolean) to "FlutterSharedPreferences" prefs. After 6 months writes false.

**Single icon:** SettingsActivity has NO LAUNCHER intent-filter. It opens when the user triple-presses the PS4 Options button (KEYCODE_BUTTON_START 3× within 1 second) — detected in SimBridge.onKey().

**Analog triggers:** Read from `SOURCE_JOYSTICK` events: `AXIS_BRAKE` (L2), `AXIS_GAS` (R2). Apply deadzone then gamma curve. Never threshold to binary 0/1.

The v5.2.2 XAPK is reassembled from the 3 zip parts in this repo before decoding.

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

## Compose + Sign (Full Build Pipeline)

After patching `build_work/base_dec/` and injecting smali:

```bash
# 1. Build carrier APK from patched base_dec
apktool b -q --use-aapt2 build_work/base_dec -o build_work/build/carrier.apk

# 2. Compose universal APK (merges arm64+armv7 libs, bundles splits as assets, pads to ≥99MB)
python3 build_work/merge.py
# Output: build_work/build/universal_unsigned.apk

# 3. Align and sign
zipalign -p -f 4 build_work/build/universal_unsigned.apk build_work/build/aligned.apk
apksigner sign \
  --ks build_work/build/sim_new.ks \
  --ks-key-alias sim \
  --ks-pass pass:simwheel123 \
  --key-pass pass:simwheel123 \
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
  --out SimWheel_5.2.2_FINAL_vN.apk \
  build_work/build/aligned.apk
```

**Keystore:** `build_work/build/sim_new.ks` — alias `sim`, password `simwheel123`.

## Paywall Bypass (Smali-Level)

`flutter.is_premium` is read by Dart via the Flutter SharedPreferences plugin bridge at `build_work/base_dec/smali/Q1/a.smali`. The method `b(Ljava/lang/String;Ljava/util/List;)Ljava/util/HashMap;` builds and returns a HashMap of all prefs to Dart. Inject `flutter.is_premium = Boolean.TRUE` into that map before the `return-object v1` at the end of method `b` to hard-bake premium=true at the smali level with no external code.

The `SimBridge.applyPromo()` approach (writing to SharedPreferences from Java) is a backup; the smali patch is the primary and most reliable route since it intercepts the read path directly.
