#!/usr/bin/env bash
# Build the Skia JNI demo Android APK using the SDK's aapt2 / d8 / apksigner.
set -euo pipefail

SDK="${ANDROID_SDK:-/opt/android-sdk-linux}"
BT="${SDK}/build-tools/37.0.0"
PLATFORM="${SDK}/platforms/android-34"
AAPT2="$BT/aapt2"
D8="$BT/d8"
ZIPALIGN="$BT/zipalign"
APKSIGNER="$BT/apksigner"

ROOT="$(cd "$(dirname "$0")" && pwd)"
SRC="$ROOT/src/main/java"
RES="$ROOT/src/main/res"
MANIFEST="$ROOT/AndroidManifest.xml"
OUT="$ROOT/build"
LIBSO="${SKIA_SO:-$ROOT/../libs/libskia_jni.so}"
KEY="$ROOT/debug.keystore"

echo ">>> Output dir: $OUT"
rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/stage"

echo ">>> [1/6] Compiling resources with aapt2..."
"$AAPT2" compile --dir "$RES" -o "$OUT/res.zip"

echo ">>> [2/6] Linking resources + manifest..."
"$AAPT2" link -o "$OUT/app.unsigned.apk" \
    -I "$PLATFORM/android.jar" \
    --manifest "$MANIFEST" \
    --java "$OUT/gen" \
    "$OUT/res.zip"

echo ">>> [3/6] Compiling Java..."
find "$SRC" -name "*.java" > "$OUT/sources.txt"
javac -source 8 -target 8 \
    -classpath "$PLATFORM/android.jar" \
    -d "$OUT/classes" \
    @"$OUT/sources.txt"

echo ">>> [4/6] Dexing with d8..."
"$D8" --release --lib "$PLATFORM/android.jar" --output "$OUT/stage" \
    $(find "$OUT/classes" -name "*.class")

echo ">>> [5/6] Adding classes.dex + native lib to APK..."
mkdir -p "$OUT/stage/lib/arm64-v8a"
cp "$LIBSO" "$OUT/stage/lib/arm64-v8a/"
(
    cd "$OUT/stage"
    zip -q -j "$OUT/app.unsigned.apk" classes.dex
    zip -q -r "$OUT/app.unsigned.apk" lib
)

echo ">>> [6/6] Zipalign + sign..."
"$ZIPALIGN" -f 4 "$OUT/app.unsigned.apk" "$OUT/app.aligned.apk"

if [ ! -f "$KEY" ]; then
    echo ">>> Generating debug keystore..."
    keytool -genkeypair -keystore "$KEY" -storepass android \
        -keypass android -alias androiddebugkey \
        -dname "CN=Android Debug,O=Android,C=US" -keyalg RSA -validity 10000 2>/dev/null
fi

"$APKSIGNER" sign --ks "$KEY" --ks-pass pass:android \
    --key-pass pass:android --out "$OUT/skia-jni-demo.apk" "$OUT/app.aligned.apk"

"$APKSIGNER" verify "$OUT/skia-jni-demo.apk"
echo ">>> DONE: $OUT/skia-jni-demo.apk"
ls -lh "$OUT/skia-jni-demo.apk"