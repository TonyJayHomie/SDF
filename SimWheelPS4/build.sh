#!/bin/bash
set -e

# ── Paths ─────────────────────────────────────────────────────────────────────
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
BUILD_DIR="$SCRIPT_DIR/build"
SRC_DIR="$SCRIPT_DIR/src/main"
KOTLIN_DIR="$SRC_DIR/kotlin"
RES_DIR="$SRC_DIR/res"
MANIFEST="$SRC_DIR/AndroidManifest.xml"

ANDROID_JAR="/tmp/android-sdk/platforms/android-31/android.jar"
AAPT2="/usr/lib/android-sdk/build-tools/debian/aapt2"
DX="/usr/lib/android-sdk/build-tools/debian/dx"
APKSIGNER="/usr/lib/android-sdk/build-tools/debian/apksigner.jar"
KOTLINC="/usr/bin/kotlinc"
ZIPALIGN="/usr/bin/zipalign"

KOTLIN_STDLIB="/usr/share/java/kotlin-stdlib.jar"
KOTLIN_STDLIB_JDK7="/usr/share/java/kotlin-stdlib-jdk7.jar"
KOTLIN_STDLIB_JDK8="/usr/share/java/kotlin-stdlib-jdk8.jar"

OUTPUT_APK="$SCRIPT_DIR/SimWheelPS4.apk"
KEYSTORE="$SCRIPT_DIR/debug.jks"

# ── Create directories ────────────────────────────────────────────────────────
mkdir -p "$BUILD_DIR"/{compiled_res,r_out,classes,dex,apk_contents/lib}

echo "=== [1/7] Compiling resources with aapt2 ==="
mkdir -p "$BUILD_DIR/compiled_res"
find "$RES_DIR" -name "*.xml" -o -name "*.png" | while read -r f; do
    $AAPT2 compile "$f" -o "$BUILD_DIR/compiled_res/" 2>&1 || echo "WARN: compile $f"
done

echo "=== [2/7] Linking resources ==="
$AAPT2 link \
    --manifest "$MANIFEST" \
    -I "$ANDROID_JAR" \
    -o "$BUILD_DIR/linked.apk" \
    --java "$BUILD_DIR/r_out" \
    --min-sdk-version 26 \
    --target-sdk-version 31 \
    --version-code 1 \
    --version-name "1.0" \
    "$BUILD_DIR"/compiled_res/*.flat 2>&1

echo "=== [3/7] Compiling R.java then Kotlin sources ==="

# Compile R.java first so R class is available to Kotlin
RJAVA=$(find "$BUILD_DIR/r_out" -name "*.java" 2>/dev/null | head -1)
if [ -n "$RJAVA" ]; then
    echo "Compiling R.java: $RJAVA"
    javac -source 8 -target 8 \
        -classpath "$ANDROID_JAR" \
        -d "$BUILD_DIR/classes" \
        "$RJAVA" 2>&1
fi

# Collect all Kotlin files
KT_FILES=$(find "$KOTLIN_DIR" -name "*.kt" | tr '\n' ' ')

$KOTLINC \
    -classpath "$ANDROID_JAR:$KOTLIN_STDLIB:$KOTLIN_STDLIB_JDK7:$KOTLIN_STDLIB_JDK8:$BUILD_DIR/classes" \
    -d "$BUILD_DIR/classes" \
    $KT_FILES 2>&1

echo "=== [4/7] DEX compiling with dx ==="
# All class files + stdlib
$DX --dex \
    --output="$BUILD_DIR/classes.dex" \
    "$BUILD_DIR/classes" \
    "$KOTLIN_STDLIB" \
    "$KOTLIN_STDLIB_JDK7" \
    "$KOTLIN_STDLIB_JDK8" \
    2>&1

echo "=== [5/7] Packaging APK ==="
# Start with the linked APK (has resources)
cp "$BUILD_DIR/linked.apk" "$BUILD_DIR/unaligned.apk"
# Add classes.dex
cd "$BUILD_DIR" && zip -j unaligned.apk classes.dex
cd "$SCRIPT_DIR"

echo "=== [6/7] Aligning APK ==="
rm -f "$BUILD_DIR/aligned.apk"
$ZIPALIGN -v 4 "$BUILD_DIR/unaligned.apk" "$BUILD_DIR/aligned.apk" 2>&1

echo "=== [7/7] Signing APK ==="
# Generate debug keystore if missing
if [ ! -f "$KEYSTORE" ]; then
    echo "Generating debug keystore..."
    keytool -genkeypair -v \
        -keystore "$KEYSTORE" \
        -alias debug \
        -keyalg RSA -keysize 2048 \
        -validity 10000 \
        -storepass android \
        -keypass android \
        -dname "CN=Debug,O=SimWheel,C=US" 2>&1
fi

java -jar "$APKSIGNER" sign \
    --ks "$KEYSTORE" \
    --ks-pass pass:android \
    --key-pass pass:android \
    --out "$OUTPUT_APK" \
    "$BUILD_DIR/aligned.apk" 2>&1

echo ""
echo "=== BUILD COMPLETE ==="
echo "APK: $OUTPUT_APK"
ls -lh "$OUTPUT_APK"
