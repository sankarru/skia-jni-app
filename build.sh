#!/usr/bin/env bash
set -euo pipefail

# ── Config ──────────────────────────────────────────────────────────
NDK="${NDK:-${ANDROID_NDK:-/opt/android-ndk-r29}}"
API=21
ARCH=aarch64
TARGET="${ARCH}-linux-android"
TRIPLE="${TARGET}${API}"
JOBS="$(nproc)"

SKIA_DIR="${SKIA_DIR:-$HOME/skia}"
DEPOT_TOOLS="${DEPOT_TOOLS:-$HOME/depot_tools}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

# ── 1. depot_tools ─────────────────────────────────────────────────
if [ ! -d "$DEPOT_TOOLS" ]; then
    echo ">>> Cloning depot_tools..."
    git clone --depth 1 https://chromium.googlesource.com/chromium/tools/depot_tools.git "$DEPOT_TOOLS"
fi
export PATH="$DEPOT_TOOLS:$PATH"

# ── 2. Skia source ─────────────────────────────────────────────────
if [ ! -d "$SKIA_DIR/.git" ]; then
    echo ">>> Cloning Skia..."
    git clone --depth 1 https://skia.googlesource.com/skia "$SKIA_DIR"
fi
cd "$SKIA_DIR"

# ── 3. Sync deps ───────────────────────────────────────────────────
echo ">>> Syncing third-party deps..."
python3 tools/git-sync-deps
cd "$SKIA_DIR"
# guard against empty/clobbered externals from previous partial sync
for d in freetype harfbuzz icu zlib libpng libjpeg-turbo libwebp; do
    if [ ! -d "third_party/externals/$d" ] || [ -z "$(ls -A third_party/externals/$d 2>/dev/null)" ]; then
        echo ">>> Re-syncing (missing $d)..."
        python3 tools/git-sync-deps
        break
    fi
done

# ── 4. Build Skia static lib ───────────────────────────────────────
TOOLCHAIN_DIR="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"

echo ">>> Fetching gn binary..."
python3 bin/fetch-gn

echo ">>> Generating GN build files..."
# GN --args needs quoted string values; construct explicitly to preserve quotes.
ARGS_STR="is_official_build=true"
ARGS_STR="$ARGS_STR is_debug=false"
ARGS_STR="$ARGS_STR target_os=\"android\""
ARGS_STR="$ARGS_STR target_cpu=\"arm64\""
ARGS_STR="$ARGS_STR skia_use_system_freetype2=false"
ARGS_STR="$ARGS_STR skia_use_system_harfbuzz=false"
ARGS_STR="$ARGS_STR skia_use_system_icu=false"
ARGS_STR="$ARGS_STR skia_use_system_libjpeg_turbo=false"
ARGS_STR="$ARGS_STR skia_use_system_libpng=false"
ARGS_STR="$ARGS_STR skia_use_system_zlib=false"
ARGS_STR="$ARGS_STR skia_enable_gpu=true"
ARGS_STR="$ARGS_STR skia_enable_graphite=true"
ARGS_STR="$ARGS_STR skia_use_vulkan=true"
ARGS_STR="$ARGS_STR skia_enable_skottie=false"
ARGS_STR="$ARGS_STR skia_enable_svg=false"
ARGS_STR="$ARGS_STR skia_enable_tools=false"
ARGS_STR="$ARGS_STR skia_enable_pdf=false"
ARGS_STR="$ARGS_STR skia_use_gl=false"
ARGS_STR="$ARGS_STR skia_use_egl=false"
ARGS_STR="$ARGS_STR ndk=\"$NDK\""
ARGS_STR="$ARGS_STR ndk_api=$API"
ARGS_STR="$ARGS_STR ndk_target=\"$TARGET\""
ARGS_STR="$ARGS_STR ndk_host=\"linux-x86_64\""

bin/gn gen out/android-arm64 --args="$ARGS_STR"

echo ">>> Building libskia.a (parallel=$JOBS)..."
ninja -C out/android-arm64 -j"$JOBS" skia

SKIA_OUT="$SKIA_DIR/out/android-arm64"
echo ">>> Skia built: $(ls -lh "$SKIA_OUT/libskia.a" 2>/dev/null || echo 'not found')"

# ── 5. Build JNI .so ───────────────────────────────────────────────
echo ">>> Building libskia_jni.so..."
BUILD_DIR="$SCRIPT_DIR/build"
OUT_DIR="$SCRIPT_DIR/libs"
mkdir -p "$BUILD_DIR" "$OUT_DIR"

JNI_SRC="$SCRIPT_DIR/app/src/main/native/skia_jni.cpp"
JNI_OBJ="$BUILD_DIR/skia_jni.o"
JNI_SO="$OUT_DIR/libskia_jni.so"

CXXFLAGS="-std=c++17 -fPIC -O2 -DNDEBUG -Wall -Wextra \
    -I${SKIA_DIR} \
    -I${SKIA_DIR}/include \
    -I${NDK}/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/include"

$TOOLCHAIN_DIR/${TRIPLE}-clang++ $CXXFLAGS -c "$JNI_SRC" -o "$JNI_OBJ"

$TOOLCHAIN_DIR/${TRIPLE}-clang++ -shared \
    -o "$JNI_SO" "$JNI_OBJ" \
    -L"$SKIA_OUT" -lskia \
    -llog -landroid -lvulkan -lm -lz \
    -Wl,--gc-sections -Wl,--strip-all

echo ">>> Done: $JNI_SO ($(du -h "$JNI_SO" | cut -f1))"
