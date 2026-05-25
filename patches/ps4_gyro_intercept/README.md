# PS4-controller gyro intercept for SimWheel Connect v5.2.2

## What this patch does (and does not do)

**Does:** When a Bluetooth game controller is paired with the phone and Android
exposes that controller's IMU through `InputDevice.getSensorManager()` (added
in Android 12 / API 31), the Flutter `sensors_plus` plugin is transparently
redirected to read the **controller's** gyro/accel instead of the phone's. The
on-screen steering wheel — which is rendered by the existing Flutter UI from
sensor input — now rotates from controller tilt. No Dart code is modified; the
swap happens entirely at the native plugin layer.

**Does not yet:**
- Drive the on-screen throttle / brake sliders from controller L2/R2. Those
  sliders are Dart widgets that read touch input, and there is no native↔Dart
  channel in this APK we can hook to push trigger values. Doing this requires
  either Dart source changes (not possible without the project source) or a
  synthetic-touch injection path that needs a per-device UI calibration screen.
- Vibration feedback, in-app calibration UI, button-mapping UI, profile save/load.

**Compatibility:** Android 12+ phones whose kernel HID driver actually exposes
DS4 sensors. On older Android or phones where the driver does not, the patch
silently falls back to the phone's gyro — i.e. the app behaves exactly like the
unmodified version. No "silent fallback to phone gyro and pretend it's the
controller" — when the controller's sensors are present they fully replace the
phone's.

## Files

- `Ps4Sensor.smali` — the new helper class (`Lcom/sdf/ps4/Ps4Sensor;`). Stores
  the Application context (`init`) and picks the right `SensorManager` for a
  requested sensor type (`pick`).
- `w1_c.smali.patch` — patches `Lw1/c;` (the `sensors_plus` plugin's stream
  handler) so its `register`/`unregister` calls go through `Ps4Sensor.pick`.
- `MainActivity.smali.patch` — adds `onCreate` override that calls
  `Ps4Sensor.init(this)`.
- `build.sh` — end-to-end rebuild script. Takes the official v5.2.2 XAPK in,
  produces a re-signed XAPK with the patch applied.

## How to rebuild

```
./build.sh path/to/SimWheel-Connect_5.2.2.xapk SimWheel_PS4Gyro_v5.2.2.xapk
```

Install with any XAPK installer (SAI, APKMirror Installer, Play Console for
internal testing). It will install side-by-side with the Play Store version
only if you uninstall the Play Store one first — the signing certificate is
different from your Play upload key.

## How to verify it's working on device

1. Pair a DualShock 4 over Bluetooth (`Settings → Bluetooth → Wireless
   Controller`). Hold PS+Share until the light bar blinks fast.
2. Launch SimWheel Connect.
3. With the controller held flat, the on-screen wheel should sit at centre.
   Tilt the controller left/right; the on-screen wheel should rotate in the
   same direction. If it tracks the **phone's** tilt and ignores the controller,
   then your phone/Android version does not expose DS4 sensors via the
   supported API and there is no workaround that doesn't require root.
4. Look in `logcat | grep -i sensor` after pairing — `SensorService` should
   show registrations against the DS4 input device.
