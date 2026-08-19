#!/usr/bin/env bash
# Build Hermes as a static library (libhermes.a) for aarch64-android.
# Uses the NDK toolchain + HERMES_UNICODE_LITE (avoids ICU + fbjni deps).
set -euo pipefail

NDK="${NDK:-${ANDROID_NDK:-/opt/android-ndk-r29}}"
HERMES_DIR="${HERMES_DIR:-$HOME/hermes}"
JOBS="$(nproc)"

if [ ! -d "$HERMES_DIR/.git" ]; then
    echo ">>> Cloning Hermes..."
    git clone --depth 1 https://github.com/facebook/hermes.git "$HERMES_DIR"
fi

BUILD_DIR="$HERMES_DIR/build-android"
rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR"
cd "$BUILD_DIR"

echo ">>> Configuring Hermes (aarch64-android)..."
cmake .. \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a \
    -DANDROID_PLATFORM=android-29 \
    -DCMAKE_BUILD_TYPE=Release \
    -DHERMES_IS_ANDROID=OFF \
    -DHERMES_UNICODE_LITE=ON \
    -DHERMES_BUILD_LEAN_LIBHERMES=OFF \
    -DHERMES_ENABLE_DEBUGGER=OFF \
    -DBUILD_TESTING=OFF \
    -DBUILD_SHARED_LIBS=OFF

echo ">>> Building libhermes.a (parallel=$JOBS)..."
cmake --build . --target hermes -j"$JOBS"

LIBHERMES="$BUILD_DIR/libhermes.a"
echo ">>> Hermes built: $(ls -lh "$LIBHERMES" | awk '{print $5}')"
echo ">>> HERMES_DIR=$HERMES_DIR"
echo ">>> LIBHERMES=$LIBHERMES"
