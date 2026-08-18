#!/usr/bin/env bash
# Quick native build for host (x86_64) — useful for testing JNI without Android.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SKIA_DIR="${SKIA_DIR:-$HOME/skia}"
NATIVE_DIR="$SCRIPT_DIR/app/src/main/native"
BUILD_DIR="$NATIVE_DIR/build"
OUT_DIR="$NATIVE_DIR/libs"
mkdir -p "$BUILD_DIR" "$OUT_DIR"

# Build host Skia if needed
if [ ! -f "$SKIA_DIR/out/host/libskia.a" ]; then
    cd "$SKIA_DIR"
    python3 bin/gn gen out/host --args='is_official_build=true is_debug=false skia_enable_tools=false skia_enable_pdf=false skia_enable_svg=false skia_use_vulkan=true skia_enable_gpu=true'
    ninja -C out/host -j$(nproc) skia
fi

echo ">>> Compiling JNI .so for host..."
g++ -std=c++17 -fPIC -shared -O2 \
    -I"$SKIA_DIR" \
    -I/usr/include \
    "$NATIVE_DIR/skia_jni.cpp" \
    -L"$SKIA_DIR/out/host" -lskia \
    -lvulkan -lpthread -lm -lz \
    -o "$OUT_DIR/libskia_jni.so"

echo ">>> Built: $OUT_DIR/libskia_jni.so"

# Compile & run demo
echo ">>> Compiling demo..."
javac -d "$BUILD_DIR" "$SCRIPT_DIR/app/src/main/java/com/example/skiajni/"*.java

echo ">>> Running demo..."
java -Djava.library.path="$OUT_DIR" -cp "$BUILD_DIR" com.example.skiajni.Demo "$SCRIPT_DIR/skia_output.png"
