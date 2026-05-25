#!/usr/bin/env bash
# Build SimWheel PS4 APK using the modern aapt2 + D8 + apksigner pipeline.
# Compiles against android-34 (Android 14) so the APK installs cleanly on
# every shipping Android device. Output: build/SimWheelPS4.apk
set -euo pipefail
cd "$(dirname "$0")"

ANDROID_JAR="${ANDROID_JAR:-/opt/android-tools/android-34.jar}"
JAVA_HOME_17="${JAVA_HOME_17:-/usr/lib/jvm/java-17-openjdk-amd64}"
JAVAC="$JAVA_HOME_17/bin/javac"
JAVA="$JAVA_HOME_17/bin/java"
KEYTOOL="$JAVA_HOME_17/bin/keytool"
AAPT2="${AAPT2:-aapt2}"
ZIPALIGN="${ZIPALIGN:-zipalign}"
APKSIGNER="${APKSIGNER:-apksigner}"
R8_JAR="${R8_JAR:-/opt/android-tools/r8.jar}"

OUT="build"
GEN="$OUT/gen"
CLASSES="$OUT/classes"
DEX="$OUT/dex"
RES_FLAT="$OUT/res_flat"
UNSIGNED="$OUT/app-unsigned.apk"
UNALIGNED="$OUT/app-unaligned.apk"
SIGNED="$OUT/SimWheelPS4.apk"
KS="$OUT/debug.keystore"

rm -rf "$GEN" "$CLASSES" "$DEX" "$RES_FLAT" "$UNSIGNED" "$UNALIGNED" "$SIGNED"
mkdir -p "$GEN/com/sdf/simwheelps4" "$CLASSES" "$DEX" "$RES_FLAT"

echo "[1/7] aapt2 compile (resources -> flat files)"
"$AAPT2" compile --dir res -o "$RES_FLAT/res.zip"

echo "[2/7] aapt2 link (generate base APK + R.java)"
"$AAPT2" link \
    --manifest AndroidManifest.xml \
    -I "$ANDROID_JAR" \
    --min-sdk-version 21 \
    --target-sdk-version 34 \
    --version-code 2 \
    --version-name 1.1 \
    --java "$GEN" \
    -o "$UNSIGNED" \
    "$RES_FLAT/res.zip"

echo "[3/7] javac (target 1.8 for D8)"
SRCS=$(find src "$GEN" -name '*.java')
"$JAVAC" \
    -encoding UTF-8 \
    -source 1.8 -target 1.8 \
    -Xlint:none -nowarn \
    -bootclasspath "$ANDROID_JAR" \
    -classpath "$ANDROID_JAR" \
    -d "$CLASSES" \
    $SRCS

echo "[4/7] D8 (.class -> classes.dex)"
CLASS_FILES=$(find "$CLASSES" -name '*.class')
"$JAVA" -cp "$R8_JAR" com.android.tools.r8.D8 \
    --release \
    --min-api 21 \
    --lib "$ANDROID_JAR" \
    --output "$DEX" \
    $CLASS_FILES

echo "[5/7] Adding classes.dex to APK (stored uncompressed for Android 9+)"
cp "$DEX/classes.dex" "$OUT/classes.dex"
( cd "$OUT" && zip -q -j -X -0 "$(basename "$UNSIGNED")" classes.dex && rm -f classes.dex )

echo "[6/7] zipalign"
"$ZIPALIGN" -f -p 4 "$UNSIGNED" "$UNALIGNED"

echo "[7/7] apksigner (debug keystore, v1+v2+v3)"
if [ ! -f "$KS" ]; then
    "$KEYTOOL" -genkeypair -v \
        -keystore "$KS" -storepass android -keypass android \
        -alias androiddebugkey \
        -dname 'CN=Android Debug, O=Android, C=US' \
        -keyalg RSA -keysize 2048 -validity 10000 >/dev/null 2>&1
fi
"$APKSIGNER" sign \
    --ks "$KS" --ks-pass pass:android --key-pass pass:android \
    --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
    --min-sdk-version 21 \
    --out "$SIGNED" "$UNALIGNED"

echo ""
echo "=========================================="
echo " APK READY:"
echo "   $(realpath "$SIGNED")"
ls -la "$SIGNED"
sha256sum "$SIGNED"
echo "=========================================="
echo " Verifying..."
"$APKSIGNER" verify --verbose --print-certs "$SIGNED" 2>&1 | head -8
echo ""
"$AAPT2" dump badging "$SIGNED" 2>&1 | head -15
