#!/usr/bin/env bash
set -euo pipefail

# ── Config ──────────────────────────────────────────────────────────
NDK="${ANDROID_NDK:-/opt/android-ndk-r29}"
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
if [ ! -d "third_party/externals/harfbuzz" ]; then
    echo ">>> Syncing third-party deps..."
    python3 tools/git-sync-deps
fi

# ── 4. Build Skia static lib ───────────────────────────────────────
TOOLCHAIN_DIR="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
GN_ARGS=(
    is_official_build=true
    is_debug=false
    target_os="android"
    target_cpu="arm64"
    skia_use_system_freetype2=false
    skia_use_system_harfbuzz=false
    skia_use_system_icu=false
    skia_use_system_libjpeg_turbo=false
    skia_use_system_libpng=false
    skia_use_system_zlib=false
    skia_enable_gpu=true
    skia_enable_graphite=true
    skia_use_vulkan=true
    skia_enable_skottie=false
    skia_enable_svg=false
    skia_enable_tools=false
    skia_enable_pdf=false
    skia_enable_skottie=false
    skia_use_gl=false
    skia_use_egl=false
    ndk="$NDK"
    ndk_api="$API"
    ndk_target="$TARGET"
    ndk_host="linux-x86_64"
)

echo ">>> Generating GN build files (gn from depot_tools)..."
gn gen out/android-arm64 --args="${GN_ARGS[*]}"

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
