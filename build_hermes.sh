#!/usr/bin/env bash
# Two-stage Hermes build for aarch64-android.
# Stage 1: build native (x86_64) hermesc so we can compile .hbc on the host.
# Stage 2: cross-compile libhermes.a for aarch64-android using the host hermesc.
set -euo pipefail

NDK="${NDK:-${ANDROID_NDK:-/opt/android-ndk-r29}}"
HERMES_DIR="${HERMES_DIR:-$HOME/hermes}"
JOBS="$(nproc)"

if [ ! -d "$HERMES_DIR/.git" ]; then
    echo ">>> Cloning Hermes..."
    git clone --depth 1 https://github.com/facebook/hermes.git "$HERMES_DIR"
fi

# --- Stage 1: native host hermesc ---
HOST_BUILD="$HERMES_DIR/build-host"
mkdir -p "$HOST_BUILD"
cd "$HOST_BUILD"

echo ">>> Stage 1: Building native hermesc + shermes (x86_64)..."
cmake -S "$HERMES_DIR" -B "$HOST_BUILD" \
    -DCMAKE_BUILD_TYPE=Release \
    -DHERMES_UNICODE_LITE=ON \
    -DHERMES_BUILD_LEAN_LIBHERMES=ON \
    -DHERMES_ENABLE_DEBUGGER=OFF \
    -DHERMES_ENABLE_CONTRIB_EXTENSIONS=OFF \
    -DBUILD_TESTING=OFF \
    -DBUILD_SHARED_LIBS=OFF

cmake --build "$HOST_BUILD" --target hermesc shermes -j"$JOBS"
HOST_HERMESC="$HOST_BUILD/bin/hermesc"
echo ">>> Host hermesc: $HOST_HERMESC"
file "$HOST_HERMESC"

# Hermes auto-generates ImportHostCompilers.cmake during the build
IMPORT_CMAKE="$HOST_BUILD/ImportHostCompilers.cmake"
if [ ! -f "$IMPORT_CMAKE" ]; then
    echo ">>> WARNING: ImportHostCompilers.cmake not found, creating one..."
    IMPORT_CMAKE="$HOST_BUILD/import-host-compilers.cmake"
    cat > "$IMPORT_CMAKE" <<EOF
add_executable(imported-hermesc IMPORTED)
set_target_properties(imported-hermesc PROPERTIES IMPORTED_LOCATION "$HOST_HERMESC")
add_executable(imported-shermes IMPORTED)
set_target_properties(imported-shermes PROPERTIES IMPORTED_LOCATION "$HOST_BUILD/bin/shermes")
EOF
fi

# --- Stage 2: cross-compile for aarch64-android ---
BUILD_DIR="$HERMES_DIR/build-android"
rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR"
echo ">>> Stage 2: Configuring Hermes (aarch64-android)..."
cmake -S "$HERMES_DIR" -B "$BUILD_DIR" \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a \
    -DANDROID_PLATFORM=android-29 \
    -DCMAKE_BUILD_TYPE=Release \
    -DHERMES_IS_ANDROID=OFF \
    -DHERMES_UNICODE_LITE=ON \
    -DHERMES_BUILD_LEAN_LIBHERMES=OFF \
    -DHERMES_ENABLE_DEBUGGER=OFF \
    -DBUILD_TESTING=OFF \
    -DBUILD_SHARED_LIBS=OFF \
    -DIMPORT_HOST_COMPILERS="$IMPORT_CMAKE"

echo ">>> Building libhermes.a (parallel=$JOBS)..."
cmake --build "$BUILD_DIR" --target hermes -j"$JOBS"

LIBHERMES="$BUILD_DIR/libhermes.a"
echo ">>> Hermes built: $(ls -lh "$LIBHERMES" | awk '{print $5}')"
echo ">>> HERMES_DIR=$HERMES_DIR"
echo ">>> HERMES_BUILD=$BUILD_DIR"
echo ">>> LIBHERMES=$LIBHERMES"
