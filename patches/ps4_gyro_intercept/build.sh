#!/usr/bin/env bash
# Rebuilds the SimWheel Connect v5.2.2 XAPK with the PS4-controller-gyro intercept.
#
# What this patch does:
#   The Flutter app reads the phone's gyroscope through the `sensors_plus` plugin
#   (smali class Lw1/c;). We swap the SensorManager that the plugin uses for the
#   gyro/accel streams to the SensorManager belonging to a paired Bluetooth game
#   controller — only if Android exposes the controller's IMU via the supported
#   API InputDevice.getSensorManager() (added in API 31 / Android 12). If no
#   controller sensor is available, the call returns the original phone
#   SensorManager and behaviour is unchanged.
#
# Requirements:
#   - apktool (tested with 2.7)
#   - apksigner, zipalign  (Android SDK build-tools)
#   - keytool / OpenJDK    (for the signing keystore)
#
# Inputs:
#   $1 = path to the v5.2.2 XAPK (full, joined)
#   $2 = output XAPK path
#
# Example:
#   ./build.sh ~/Downloads/SimWheel-Connect_5.2.2.xapk SimWheel_PS4Gyro_v5.2.2.xapk
set -euo pipefail

XAPK_IN="${1:?usage: $0 <input.xapk> <output.xapk>}"
XAPK_OUT="${2:?usage: $0 <input.xapk> <output.xapk>}"
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

# Drop in the helper class
mkdir -p "$WORK/decoded/smali/com/sdf/ps4"
cp "$HERE/Ps4Sensor.smali" "$WORK/decoded/smali/com/sdf/ps4/"

# Apply the smali patches
patch -p1 -d "$WORK/decoded" < "$HERE/w1_c.smali.patch"
patch -p1 -d "$WORK/decoded" < "$HERE/MainActivity.smali.patch"

# Rebuild base APK
apktool b --use-aapt2 -q -f -o "$WORK/base.apk" "$WORK/decoded"

# Sign every split with the same key
mkdir -p "$WORK/signed"
cp "$WORK/xapk/manifest.json" "$WORK/signed/"

sign_one() {
  local in="$1" out="$2"
  zipalign -p -f 4 "$in" "$WORK/za_$(basename "$out")"
  apksigner sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
    --v1-signing-enabled true --v2-signing-enabled true \
    --out "$WORK/signed/$out" "$WORK/za_$(basename "$out")" >/dev/null
}

sign_one "$WORK/base.apk"                       com.ik.simwheel.apk
sign_one "$WORK/xapk/config.arm64_v8a.apk"     config.arm64_v8a.apk
sign_one "$WORK/xapk/config.armeabi_v7a.apk"   config.armeabi_v7a.apk
sign_one "$WORK/xapk/config.en.apk"             config.en.apk
sign_one "$WORK/xapk/config.fr.apk"             config.fr.apk
sign_one "$WORK/xapk/config.xxhdpi.apk"        config.xxhdpi.apk

(cd "$WORK/signed" && zip -j -X -q "$XAPK_OUT" \
  manifest.json com.ik.simwheel.apk \
  config.arm64_v8a.apk config.armeabi_v7a.apk \
  config.en.apk config.fr.apk config.xxhdpi.apk)

cp "$WORK/signed/$(basename "$XAPK_OUT")" "$XAPK_OUT" 2>/dev/null || true
[[ -f "$XAPK_OUT" ]] || (cd "$WORK/signed" && cp "$XAPK_OUT" "$XAPK_OUT")
echo "Built: $XAPK_OUT"
