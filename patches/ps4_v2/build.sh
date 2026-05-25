#!/usr/bin/env bash
# Build the v2 PS4-controller XAPK for SimWheel Connect 5.2.2.
#
# This rebuilds the Play Store v5.2.2 XAPK with PS4-controller integration:
#   1. Gyro intercept: the sensors_plus plugin's SensorManager is redirected
#      to a paired Bluetooth controller's InputDevice.getSensorManager() (API 31+).
#      The Flutter on-screen wheel rotates from controller IMU instead of phone IMU.
#   2. ControllerBridge: the native input bridge from the user's own v5.1 beta
#      is re-injected. It reads gamepad MotionEvents (L2/R2 analogue triggers,
#      face buttons, dpad) and sends them as UDP/JSON to the PC receiver on
#      port 4567 — alongside the Flutter app's own UDP stream.
#
# Inputs (positional):
#   $1 = v5.2.2 XAPK
#   $2 = a v5.1 APK that contains com/ik/simwheel/bridge/ControllerBridge.smali
#        (used as a donor for those classes)
#   $3 = output XAPK path
#
# Requirements: apktool, apksigner, zipalign, keytool, openjdk.

set -euo pipefail

XAPK_IN="${1:?usage: $0 <v5.2.2.xapk> <v5.1_donor.apk> <output.xapk>}"
V51_DONOR="${2:?usage: $0 <v5.2.2.xapk> <v5.1_donor.apk> <output.xapk>}"
XAPK_OUT="${3:?usage: $0 <v5.2.2.xapk> <v5.1_donor.apk> <output.xapk>}"
HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

KS="${HOME}/.android/sdf.keystore"
if [[ ! -f "$KS" ]]; then
  mkdir -p "$(dirname "$KS")"
  keytool -genkeypair -keystore "$KS" -storepass android -alias sdf \
    -keypass android -keyalg RSA -keysize 2048 -validity 36500 \
    -dname "CN=SDF, O=SDF, C=US" >/dev/null
fi

unzip -q "$XAPK_IN" -d "$WORK/xapk"
apktool d -q -f -o "$WORK/decoded" "$WORK/xapk/com.ik.simwheel.apk"
apktool d -q -f -o "$WORK/donor"   "$V51_DONOR"

# (1) Gyro intercept helper + w1/c patch
mkdir -p "$WORK/decoded/smali/com/sdf/ps4"
cp "$HERE/Ps4Sensor.smali" "$WORK/decoded/smali/com/sdf/ps4/"
patch -p1 -d "$WORK/decoded" < "$HERE/w1_c.smali.patch"

# (2) ControllerBridge — lift directly from the v5.1 donor APK
mkdir -p "$WORK/decoded/smali/com/ik/simwheel/bridge"
cp "$WORK/donor"/smali/com/ik/simwheel/bridge/*.smali "$WORK/decoded/smali/com/ik/simwheel/bridge/"

# (3) MainActivity wiring (replaces the file)
cp "$HERE/MainActivity.smali" "$WORK/decoded/smali/com/ik/simwheel/MainActivity.smali"

# (4) AndroidManifest: register ControllerMappingActivity
python3 - "$WORK/decoded/AndroidManifest.xml" <<'PY'
import sys, re, pathlib
p = pathlib.Path(sys.argv[1])
txt = p.read_text()
ins = '<activity android:exported="false" android:label="Controller Mapping" android:name="com.ik.simwheel.bridge.ControllerMappingActivity" android:theme="@android:style/Theme.DeviceDefault.NoActionBar.Fullscreen"/>'
if 'com.ik.simwheel.bridge.ControllerMappingActivity' not in txt:
    txt = txt.replace(
        '<meta-data android:name="flutterEmbedding" android:value="2"/>',
        '<meta-data android:name="flutterEmbedding" android:value="2"/>\n        ' + ins,
        1)
    p.write_text(txt)
PY

# Build
apktool b --use-aapt2 -q -f -o "$WORK/base.apk" "$WORK/decoded"

# Sign every split with one key, repack XAPK
mkdir -p "$WORK/signed"
cp "$WORK/xapk/manifest.json" "$WORK/signed/"
sign_one() {
  local in="$1" out="$2"
  zipalign -p -f 4 "$in" "$WORK/za_$out"
  apksigner sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
    --v1-signing-enabled true --v2-signing-enabled true \
    --out "$WORK/signed/$out" "$WORK/za_$out" >/dev/null
}
sign_one "$WORK/base.apk"                      com.ik.simwheel.apk
sign_one "$WORK/xapk/config.arm64_v8a.apk"    config.arm64_v8a.apk
sign_one "$WORK/xapk/config.armeabi_v7a.apk"  config.armeabi_v7a.apk
sign_one "$WORK/xapk/config.en.apk"            config.en.apk
sign_one "$WORK/xapk/config.fr.apk"            config.fr.apk
sign_one "$WORK/xapk/config.xxhdpi.apk"       config.xxhdpi.apk

(cd "$WORK/signed" && rm -f *.idsig && \
  zip -j -X -q "$XAPK_OUT" manifest.json com.ik.simwheel.apk \
    config.arm64_v8a.apk config.armeabi_v7a.apk \
    config.en.apk config.fr.apk config.xxhdpi.apk)

# Also build a single fat APK (arm64-v8a only) for the common case of
# users who don't have an XAPK installer — installs by tapping the file.
FATBASE="$WORK/decoded_fat"
cp -a "$WORK/decoded" "$FATBASE"
# Drop the requiredSplitTypes attribute so Android doesn't refuse to install
# without sibling splits.
python3 - "$FATBASE/AndroidManifest.xml" <<'PY'
import sys, pathlib, re
p = pathlib.Path(sys.argv[1])
txt = p.read_text()
txt = re.sub(r' android:requiredSplitTypes="[^"]*"', '', txt)
txt = re.sub(r' android:splitTypes="[^"]*"',         '', txt)
p.write_text(txt)
PY
apktool b --use-aapt2 -q -f -o "$WORK/fat_base.apk" "$FATBASE"
# Inject arm64 libs from the split
(cd "$WORK/xapk" && rm -rf libdump && mkdir libdump && unzip -q -o config.arm64_v8a.apk -d libdump && \
 cd libdump && zip -X -q -r "$WORK/fat_base.apk" lib/)
# Inject xxhdpi density resources from the split
(cd "$WORK/xapk" && rm -rf hidpidump && mkdir hidpidump && unzip -q -o config.xxhdpi.apk -d hidpidump && \
 cd hidpidump && zip -X -q -r "$WORK/fat_base.apk" res/ 2>/dev/null || true)
zipalign -p -f 4 "$WORK/fat_base.apk" "$WORK/fat_aligned.apk"
APK_OUT="${XAPK_OUT%.xapk}.apk"
apksigner sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --v1-signing-enabled true --v2-signing-enabled true \
  --out "$APK_OUT" "$WORK/fat_aligned.apk" >/dev/null

echo "Built XAPK:  $XAPK_OUT"
echo "Built APK:   $APK_OUT  (arm64-v8a only, taps to install)"
