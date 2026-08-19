#!/usr/bin/env bash
set -euo pipefail

# ── Config ──────────────────────────────────────────────────────────
NDK="${NDK:-${ANDROID_NDK:-/opt/android-ndk-r29}}"
API=29
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
for d in freetype harfbuzz icu zlib libpng libjpeg-turbo libwebp expat; do
    if [ ! -d "third_party/externals/$d" ] || [ -z "$(ls -A third_party/externals/$d 2>/dev/null)" ]; then
        echo ">>> Re-syncing (missing $d)..."
        python3 tools/git-sync-deps
        break
    fi
done
# Expat is prone to cloning as an empty repo via git-sync-deps; clone manually if needed.
if [ ! -f "third_party/externals/expat/expat/lib/expat.h" ]; then
    echo ">>> Cloning expat manually (from Chromium mirror)..."
    rm -rf third_party/externals/expat
    git clone https://chromium.googlesource.com/external/github.com/libexpat/libexpat.git third_party/externals/expat
fi

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
ARGS_STR="$ARGS_STR skia_use_libwebp_decode=false"
ARGS_STR="$ARGS_STR skia_use_libwebp_encode=false"
ARGS_STR="$ARGS_STR skia_enable_gpu=true"
ARGS_STR="$ARGS_STR skia_enable_graphite=true"
ARGS_STR="$ARGS_STR skia_use_vulkan=true"
ARGS_STR="$ARGS_STR skia_enable_skottie=false"
ARGS_STR="$ARGS_STR skia_enable_svg=false"
ARGS_STR="$ARGS_STR skia_enable_tools=false"
ARGS_STR="$ARGS_STR skia_enable_pdf=false"
ARGS_STR="$ARGS_STR skia_use_gl=false"
ARGS_STR="$ARGS_STR skia_use_egl=false"
ARGS_STR="$ARGS_STR skia_use_expat=true"
ARGS_STR="$ARGS_STR skia_use_system_expat=false"
ARGS_STR="$ARGS_STR skia_use_partition_alloc=false"
ARGS_STR="$ARGS_STR skia_enable_fontmgr_android=true"
ARGS_STR="$ARGS_STR skia_enable_fontmgr_android_ndk=true"
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
JNI_SO="$OUT_DIR/libskia_jni.so"
mkdir -p "$BUILD_DIR" "$OUT_DIR"

VK_INC="${SKIA_DIR}/include/third_party/vulkan"
HERMES_DIR="${HERMES_DIR:-$HOME/hermes}"
HERMES_BUILD="${HERMES_BUILD:-$HERMES_DIR/build-android}"
HERMES_INC="$HERMES_DIR/API"
HERMES_JSI_INC="$HERMES_DIR/API/jsi"
HERMES_PUBLIC_INC="$HERMES_DIR/public"
# Hermes produces lib/libhermesvm_a.a (aggregate static lib) + jsi + boost context
HERMES_LIB="$HERMES_BUILD/lib/libhermesvm_a.a"
if [ ! -f "$HERMES_LIB" ]; then
    echo ">>> Debug: HERMES_BUILD=$HERMES_BUILD"
    find "$HERMES_BUILD" -name "libhermes*.a" 2>/dev/null | head -10
    HERMES_LIB=$(find "$HERMES_BUILD" -name "libhermesvm_a.a" 2>/dev/null | head -1)
fi
HERMES_JSI="$HERMES_BUILD/jsi/libjsi.a"
HERMES_BOOST=$(find "$HERMES_BUILD" -name "libboost_context.a" 2>/dev/null | head -1)

CXXFLAGS="-std=c++17 -fPIC -O2 -DNDEBUG -Wall -Wextra \
    -I${SKIA_DIR} \
    -I${SKIA_DIR}/include \
    -I${VK_INC} \
    -I${HERMES_INC} \
    -I${HERMES_JSI_INC} \
    -I${HERMES_PUBLIC_INC} \
    -I${NDK}/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/include"

$TOOLCHAIN_DIR/${TRIPLE}-clang++ $CXXFLAGS -c "$SCRIPT_DIR/app/src/main/native/skia_jni.cpp" -o "$BUILD_DIR/skia_jni.o"
$TOOLCHAIN_DIR/${TRIPLE}-clang++ $CXXFLAGS -c "$SCRIPT_DIR/app/src/main/native/vulkan_renderer.cpp" -o "$BUILD_DIR/vulkan_renderer.o"

if [ -f "$HERMES_LIB" ]; then
    echo ">>> Linking Hermes..."
    $TOOLCHAIN_DIR/${TRIPLE}-clang++ $CXXFLAGS -c "$SCRIPT_DIR/app/src/main/native/hermes_bridge.cpp" -o "$BUILD_DIR/hermes_bridge.o"
    $TOOLCHAIN_DIR/${TRIPLE}-clang++ -shared \
        -o "$JNI_SO" "$BUILD_DIR/skia_jni.o" "$BUILD_DIR/vulkan_renderer.o" "$BUILD_DIR/hermes_bridge.o" \
        -L"$SKIA_OUT" -lskia \
        "$HERMES_LIB" ${HERMES_JSI:+"$HERMES_JSI"} ${HERMES_BOOST:+"$HERMES_BOOST"} \
        -llog -landroid -ldl -lm -lz \
        -Wl,--gc-sections -Wl,--strip-all
else
    echo ">>> WARNING: libhermesvm_a.a not found; building without JS support."
    $TOOLCHAIN_DIR/${TRIPLE}-clang++ -shared \
        -o "$JNI_SO" "$BUILD_DIR/skia_jni.o" "$BUILD_DIR/vulkan_renderer.o" \
        -L"$SKIA_OUT" -lskia \
        -llog -landroid -ldl -lm -lz \
        -Wl,--gc-sections -Wl,--strip-all
fi

# Ship libc++_shared.so alongside the JNI lib (NDK C++ runtime).
SYSROOT_LIB="$NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/${TARGET}"
CXX_SHARED=""
for p in "$SYSROOT_LIB/libc++_shared.so" "$SYSROOT_LIB/${API}/libc++_shared.so"; do
    if [ -f "$p" ]; then CXX_SHARED="$p"; break; fi
done
if [ -n "$CXX_SHARED" ]; then
    cp "$CXX_SHARED" "$OUT_DIR/"
    echo ">>> Copied $CXX_SHARED -> $OUT_DIR/libc++_shared.so"
else
    echo ">>> WARNING: libc++_shared.so not found in NDK"
fi

echo ">>> Done: $JNI_SO ($(du -h "$JNI_SO" | cut -f1))"
