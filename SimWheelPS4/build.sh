#!/usr/bin/env bash
# Build SimWheel PS4 APK from raw sources using only the Ubuntu/Debian
# android-sdk-platform-23, aapt, dx, zipalign, and apksigner packages.
# Output: build/SimWheelPS4-debug.apk (sign-key: build/debug.keystore)

set -euo pipefail
cd "$(dirname "$0")"

ANDROID_JAR="${ANDROID_JAR:-/usr/lib/android-sdk/platforms/android-23/android.jar}"
JAVA_HOME_17="${JAVA_HOME_17:-/usr/lib/jvm/java-17-openjdk-amd64}"
JAVAC="$JAVA_HOME_17/bin/javac"
JAVA="$JAVA_HOME_17/bin/java"
KEYTOOL="$JAVA_HOME_17/bin/keytool"
AAPT="${AAPT:-aapt}"
R8_JAR="${R8_JAR:-/opt/android-tools/r8.jar}"
ZIPALIGN="${ZIPALIGN:-zipalign}"
APKSIGNER="${APKSIGNER:-apksigner}"

OUT="build"
GEN="$OUT/gen"
CLASSES="$OUT/classes"
DEX="$OUT/dex"
UNSIGNED="$OUT/app-unsigned.apk"
UNALIGNED="$OUT/app-unaligned.apk"
SIGNED="$OUT/SimWheelPS4-debug.apk"
KS="$OUT/debug.keystore"

rm -rf "$GEN" "$CLASSES" "$DEX" "$UNSIGNED" "$UNALIGNED" "$SIGNED"
mkdir -p "$GEN" "$CLASSES" "$DEX"

echo "[1/6] Generating R.java from resources"
"$AAPT" package -f -m \
    -J "$GEN" \
    -M AndroidManifest.xml \
    -S res \
    -I "$ANDROID_JAR"

echo "[2/6] Compiling Java sources (target 1.8 for dx)"
SRCS=$(find src "$GEN" -name '*.java')
"$JAVAC" \
    -encoding UTF-8 \
    -source 1.8 -target 1.8 \
    -Xlint:none \
    -bootclasspath "$ANDROID_JAR" \
    -classpath "$ANDROID_JAR" \
    -d "$CLASSES" \
    $SRCS

echo "[3/6] Dexing classes -> classes.dex (D8)"
CLASS_FILES=$(find "$CLASSES" -name '*.class')
"$JAVA" -cp "$R8_JAR" com.android.tools.r8.D8 \
    --release \
    --min-api 21 \
    --lib "$ANDROID_JAR" \
    --output "$DEX" \
    $CLASS_FILES

echo "[4/6] Packaging APK"
"$AAPT" package -f \
    -M AndroidManifest.xml \
    -S res \
    -I "$ANDROID_JAR" \
    -F "$UNSIGNED"
# add classes.dex
( cd "$DEX" && "$AAPT" add "$(realpath ../../$UNSIGNED)" classes.dex >/dev/null )

echo "[5/6] Aligning"
"$ZIPALIGN" -f 4 "$UNSIGNED" "$UNALIGNED"

echo "[6/6] Signing (debug keystore)"
if [ ! -f "$KS" ]; then
    "$KEYTOOL" -genkeypair -v \
        -keystore "$KS" -storepass android -keypass android \
        -alias androiddebugkey \
        -dname 'CN=Android Debug, O=Android, C=US' \
        -keyalg RSA -keysize 2048 -validity 10000 >/dev/null 2>&1
fi
"$APKSIGNER" sign \
    --ks "$KS" --ks-pass pass:android --key-pass pass:android \
    --v1-signing-enabled true --v2-signing-enabled true \
    --out "$SIGNED" "$UNALIGNED"

echo ""
echo "=========================================="
echo " APK READY:"
echo "   $(realpath "$SIGNED")"
ls -la "$SIGNED"
sha256sum "$SIGNED"
echo "=========================================="
