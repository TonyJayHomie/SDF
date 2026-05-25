# PS4 controller integration v2 — SimWheel Connect 5.2.2

Combines the gyro intercept from `patches/ps4_gyro_intercept` with the
`ControllerBridge` native input infrastructure that was already in the v5.1
beta APK. v5.2.2 (the current Play Store build) had stripped that out — this
patch re-injects it.

## What the patched APK does

| Source on controller | Path | Destination |
|---|---|---|
| Gyro | `Ps4Sensor.pick` swaps `sensors_plus` `SensorManager` → controller's | On-screen Flutter steering wheel rotates |
| L2 / R2 analogue | `ControllerBridge.handleMotionEvent` reads `AXIS_BRAKE` / `AXIS_GAS` | UDP packet `brake` / `throttle` field to PC :4567 |
| Face buttons, dpad, L1/R1, options, share | `ControllerBridge.handleKeyEvent` | UDP packet `BTN1..8` / `HORN` / etc to PC :4567 |

So the PC receiver sees both the Flutter app's UDP stream (steering, etc) **and**
the ControllerBridge UDP stream (analog triggers, buttons) and merges them — the
behaviour of the user's own v5.1 beta build, restored.

## What this does NOT do

- It does not visually animate the on-screen throttle/brake sliders from L2/R2.
  The Flutter side of the app draws those sliders from its own state, which is
  internal Dart and not reachable without source. The PC still gets the correct
  values; the on-screen visual just doesn't reflect them. Fixing this requires
  rebuilding from the Flutter source.

## Files

- `Ps4Sensor.smali` — the gyro-redirect helper (unchanged from v1)
- `w1_c.smali.patch` — the sensors_plus interception (unchanged from v1)
- `MainActivity.smali` — wired up: calls `Ps4Sensor.init`, `ControllerBridge.ensureInitialized`, `ControllerBridge.attachOverlay`, and overrides `dispatchKeyEvent` + `dispatchGenericMotionEvent` to route through `ControllerBridge`.
- `build.sh` — reproducible build. Takes the v5.2.2 XAPK and a v5.1 APK (donor for `ControllerBridge` smali) as inputs.

## Rebuild

```
./build.sh path/to/SimWheel-Connect_5.2.2.xapk \
           path/to/SimWheel_Controller_Mapping_v5_1.apk \
           SimWheel_PS4_full_v5.2.2.xapk
```

The v5.1 donor APK only needs to contain `com/ik/simwheel/bridge/*.smali` — the
script copies those into the v5.2.2 base APK during the rebuild.

## Install / verify

1. Uninstall any existing `com.ik.simwheel` (this rebuild is signed with a
   different key from your Play upload).
2. Install with an XAPK installer (SAI, APKMirror Installer, or `adb install-multiple`
   on the extracted split APKs).
3. Pair your DS4 over Bluetooth, launch SimWheel Connect.
4. **Gyro check**: tilt the controller; on-screen wheel should rotate. If it
   doesn't, your phone's HID driver does not expose DS4 IMU sensors via the
   supported `InputDevice.getSensorManager()` API — there is no workaround
   without root.
5. **Trigger / button check**: with the PC receiver running, watch its console.
   Press L2 / R2 / face buttons; the PC should print incoming JSON with
   `throttle` / `brake` / `BTN*` fields updating.
