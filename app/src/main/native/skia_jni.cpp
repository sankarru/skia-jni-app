#include <jni.h>
#include <cstdio>
#include <cstring>
#include <algorithm>
#include <mutex>
#include <vector>
#include <map>
#include <dlfcn.h>

// ── Skia core ──────────────────────────────────────────────────────
#include "include/core/SkCanvas.h"
#include "include/core/SkColor.h"
#include "include/core/SkPaint.h"
#include "include/core/SkSurface.h"
#include "include/core/SkFont.h"
#include "include/core/SkFontMgr.h"
#include "include/core/SkFontStyle.h"
#include "include/core/SkTextBlob.h"
#include "include/core/SkTypeface.h"
#include "include/ports/SkFontMgr_android_ndk.h"
#include "include/ports/SkFontScanner_FreeType.h"
#include "include/core/SkPath.h"
#include "include/core/SkPathBuilder.h"
#include "include/core/SkImage.h"
#include "include/core/SkData.h"
#include "include/effects/SkGradient.h"
#include "include/core/SkShader.h"
#include "include/core/SkTileMode.h"
#include "include/encode/SkPngEncoder.h"

// ── Skia GPU / Vulkan ─────────────────────────────────────────────
#include "include/gpu/ganesh/GrDirectContext.h"
#include "include/gpu/ganesh/GrBackendSurface.h"
#include "include/gpu/ganesh/vk/GrVkDirectContext.h"
#include "include/gpu/ganesh/vk/GrVkBackendSurface.h"
#include "include/gpu/ganesh/vk/GrVkTypes.h"
#include "include/gpu/ganesh/SkSurfaceGanesh.h"
#include "include/gpu/vk/VulkanBackendContext.h"
#include "include/gpu/vk/VulkanMemoryAllocator.h"
#include "include/gpu/vk/VulkanTypes.h"
#include "include/gpu/GpuTypes.h"

// ── Vulkan ─────────────────────────────────────────────────────────
#include <vulkan/vulkan.h>

// ====================================================================
// Unified canvas handle — wraps either raster or Vulkan surface
// ====================================================================

struct NativeCanvas {
    enum Backend { RASTER, VULKAN } backend;
    int width, height;
    sk_sp<SkSurface> surface;

    // Vulkan-only
    sk_sp<GrDirectContext> grContext;
};

struct VulkanCtx {
    sk_sp<GrDirectContext> grContext;
    sk_sp<skgpu::VulkanMemoryAllocator> allocator;
    VkDevice device;
};

// ====================================================================
// Helpers
// ====================================================================

static inline SkColor toARGB(jint c) {
    return SkColorSetARGB((c>>24)&0xFF,(c>>16)&0xFF,(c>>8)&0xFF,c&0xFF);
}

// Font manager for text rendering (NDK system fonts).
static sk_sp<SkFontMgr> gFontMgr;
static sk_sp<SkTypeface> gTypeface;
static std::once_flag gFontInit;

static void initFonts() {
    gFontMgr = SkFontMgr_New_AndroidNDK(true, SkFontScanner_Make_FreeType());
    if (gFontMgr) {
        gTypeface = gFontMgr->matchFamilyStyle(nullptr, SkFontStyle());
    }
}

static sk_sp<SkTypeface> defaultTypeface() {
    std::call_once(gFontInit, initFonts);
    return gTypeface;
}

static SkCanvas* getCanvas(jlong h) {
    auto nc = reinterpret_cast<NativeCanvas*>(h);
    return nc && nc->surface ? nc->surface->getCanvas() : nullptr;
}

// ====================================================================
// JNI — drawing (works for both raster & Vulkan)
// ====================================================================

extern "C" {

// ── Raster create / destroy ────────────────────────────────────────

JNIEXPORT jlong JNICALL
Java_com_example_skiajni_SkiaCanvas_nCreateRaster(JNIEnv*, jclass, jint w, jint h) {
    auto surf = SkSurfaces::Raster(SkImageInfo::MakeN32Premul(w, h), nullptr);
    if (!surf) return 0;
    auto nc = new NativeCanvas();
    nc->backend = NativeCanvas::RASTER;
    nc->width = w;
    nc->height = h;
    nc->surface = std::move(surf);
    return reinterpret_cast<jlong>(nc);
}

JNIEXPORT void JNICALL
Java_com_example_skiajni_SkiaCanvas_nDestroy(JNIEnv*, jclass, jlong h) {
    delete reinterpret_cast<NativeCanvas*>(h);
}

// ── Drawing (shared) ───────────────────────────────────────────────

JNIEXPORT void JNICALL
Java_com_example_skiajni_SkiaCanvas_nClear(JNIEnv*, jclass, jlong h, jint c) {
    if (auto* c2 = getCanvas(h)) c2->clear(toARGB(c));
}

JNIEXPORT void JNICALL
Java_com_example_skiajni_SkiaCanvas_nDrawRect(JNIEnv*, jclass, jlong h,
        jfloat x, jfloat y, jfloat w, jfloat hh, jint c, jfloat s) {
    if (auto* c2 = getCanvas(h)) {
        SkPaint p; p.setColor(toARGB(c)); p.setStyle(SkPaint::kStroke_Style);
        p.setStrokeWidth(s); p.setAntiAlias(true);
        c2->drawRect(SkRect::MakeXYWH(x, y, w, hh), p);
    }
}

JNIEXPORT void JNICALL
Java_com_example_skiajni_SkiaCanvas_nDrawCircle(JNIEnv*, jclass, jlong h,
        jfloat cx, jfloat cy, jfloat r, jint c, jfloat s) {
    if (auto* c2 = getCanvas(h)) {
        SkPaint p; p.setColor(toARGB(c)); p.setStyle(SkPaint::kStroke_Style);
        p.setStrokeWidth(s); p.setAntiAlias(true);
        c2->drawCircle(cx, cy, r, p);
    }
}

JNIEXPORT void JNICALL
Java_com_example_skiajni_SkiaCanvas_nDrawLine(JNIEnv*, jclass, jlong h,
        jfloat x0, jfloat y0, jfloat x1, jfloat y1, jint c, jfloat s) {
    if (auto* c2 = getCanvas(h)) {
        SkPaint p; p.setColor(toARGB(c)); p.setStrokeWidth(s);
        p.setStrokeCap(SkPaint::kRound_Cap); p.setAntiAlias(true);
        c2->drawLine(x0, y0, x1, y1, p);
    }
}

JNIEXPORT void JNICALL
Java_com_example_skiajni_SkiaCanvas_nFillRect(JNIEnv*, jclass, jlong h,
        jfloat x, jfloat y, jfloat w, jfloat hh, jint c) {
    if (auto* c2 = getCanvas(h)) {
        SkPaint p; p.setColor(toARGB(c)); p.setStyle(SkPaint::kFill_Style);
        p.setAntiAlias(true);
        c2->drawRect(SkRect::MakeXYWH(x, y, w, hh), p);
    }
}

JNIEXPORT void JNICALL
Java_com_example_skiajni_SkiaCanvas_nFillCircle(JNIEnv*, jclass, jlong h,
        jfloat cx, jfloat cy, jfloat r, jint c) {
    if (auto* c2 = getCanvas(h)) {
        SkPaint p; p.setColor(toARGB(c)); p.setStyle(SkPaint::kFill_Style);
        p.setAntiAlias(true);
        c2->drawCircle(cx, cy, r, p);
    }
}

JNIEXPORT void JNICALL
Java_com_example_skiajni_SkiaCanvas_nDrawRoundRect(JNIEnv*, jclass, jlong h,
        jfloat x, jfloat y, jfloat w, jfloat hh, jfloat rx, jfloat ry,
        jint c, jfloat s) {
    if (auto* c2 = getCanvas(h)) {
        SkPaint p; p.setColor(toARGB(c)); p.setStyle(SkPaint::kStroke_Style);
        p.setStrokeWidth(s); p.setAntiAlias(true);
        c2->drawRoundRect(SkRect::MakeXYWH(x, y, w, hh), rx, ry, p);
    }
}

JNIEXPORT void JNICALL
Java_com_example_skiajni_SkiaCanvas_nFillRoundRect(JNIEnv*, jclass, jlong h,
        jfloat x, jfloat y, jfloat w, jfloat hh, jfloat rx, jfloat ry, jint c) {
    if (auto* c2 = getCanvas(h)) {
        SkPaint p; p.setColor(toARGB(c)); p.setStyle(SkPaint::kFill_Style);
        p.setAntiAlias(true);
        c2->drawRoundRect(SkRect::MakeXYWH(x, y, w, hh), rx, ry, p);
    }
}

JNIEXPORT void JNICALL
Java_com_example_skiajni_SkiaCanvas_nDrawGradient(JNIEnv*, jclass, jlong h,
        jfloat x0, jfloat y0, jfloat x1, jfloat y1,
        jint c0, jint c1, jint mode) {
    if (auto* c2 = getCanvas(h)) {
        SkPoint pts[2] = {{x0, y0}, {x1, y1}};
        SkColor4f colors[2] = {
            SkColor4f::FromColor(toARGB(c0)),
            SkColor4f::FromColor(toARGB(c1)),
        };
        auto grad = SkGradient::Colors(SkSpan<const SkColor4f>(colors, 2),
                                       SkTileMode::kClamp);
        auto shader = SkShaders::LinearGradient(pts, SkGradient(grad, SkGradient::Interpolation()));

        float left   = std::min(x0, x1);
        float top    = std::min(y0, y1);
        float right  = std::max(x0, x1);
        float bottom = std::max(y0, y1);

        SkPaint p;
        p.setShader(shader);
        p.setAntiAlias(true);
        c2->drawRect(SkRect::MakeLTRB(left, top, right, bottom), p);
    }
}

JNIEXPORT void JNICALL
Java_com_example_skiajni_SkiaCanvas_nFillOval(JNIEnv*, jclass, jlong h,
        jfloat x, jfloat y, jfloat w, jfloat hh, jint c) {
    if (auto* c2 = getCanvas(h)) {
        SkPaint p; p.setColor(toARGB(c)); p.setStyle(SkPaint::kFill_Style);
        p.setAntiAlias(true);
        c2->drawOval(SkRect::MakeXYWH(x, y, w, hh), p);
    }
}

// ── Compose-style primitives: path / transform / clip ─────────────

struct NativePath {
    SkPathBuilder builder;
    SkPath detach() { return builder.detach(); }
};

JNIEXPORT jlong JNICALL
Java_com_example_skiajni_SkiaCanvas_nPathCreate(JNIEnv*, jclass) {
    return reinterpret_cast<jlong>(new NativePath());
}

JNIEXPORT void JNICALL
Java_com_example_skiajni_SkiaCanvas_nPathDestroy(JNIEnv*, jclass, jlong p) {
    delete reinterpret_cast<NativePath*>(p);
}

JNIEXPORT void JNICALL
Java_com_example_skiajni_SkiaCanvas_nPathReset(JNIEnv*, jclass, jlong p) {
    reinterpret_cast<NativePath*>(p)->builder.reset();
}

JNIEXPORT void JNICALL
Java_com_example_skiajni_SkiaCanvas_nPathMoveTo(JNIEnv*, jclass, jlong p,
        jfloat x, jfloat y) {
    reinterpret_cast<NativePath*>(p)->builder.moveTo(x, y);
}

JNIEXPORT void JNICALL
Java_com_example_skiajni_SkiaCanvas_nPathLineTo(JNIEnv*, jclass, jlong p,
        jfloat x, jfloat y) {
    reinterpret_cast<NativePath*>(p)->builder.lineTo(x, y);
}

JNIEXPORT void JNICALL
Java_com_example_skiajni_SkiaCanvas_nPathQuadTo(JNIEnv*, jclass, jlong p,
        jfloat x1, jfloat y1, jfloat x2, jfloat y2) {
    reinterpret_cast<NativePath*>(p)->builder.quadTo(x1, y1, x2, y2);
}

JNIEXPORT void JNICALL
Java_com_example_skiajni_SkiaCanvas_nPathCubicTo(JNIEnv*, jclass, jlong p,
        jfloat x1, jfloat y1, jfloat x2, jfloat y2, jfloat x3, jfloat y3) {
    reinterpret_cast<NativePath*>(p)->builder.cubicTo(x1, y1, x2, y2, x3, y3);
}

JNIEXPORT void JNICALL
Java_com_example_skiajni_SkiaCanvas_nPathClose(JNIEnv*, jclass, jlong p) {
    reinterpret_cast<NativePath*>(p)->builder.close();
}

JNIEXPORT void JNICALL
Java_com_example_skiajni_SkiaCanvas_nDrawPath(JNIEnv*, jclass, jlong h, jlong p,
        jint c, jfloat s, jboolean fill) {
    auto* c2 = getCanvas(h);
    auto* np = reinterpret_cast<NativePath*>(p);
    if (!c2 || !np) return;
    SkPaint paint;
    paint.setColor(toARGB(c));
    paint.setAntiAlias(true);
    paint.setStyle(fill ? SkPaint::kFill_Style : SkPaint::kStroke_Style);
    if (!fill) paint.setStrokeWidth(s);
    c2->drawPath(np->detach(), paint);
}

JNIEXPORT void JNICALL
Java_com_example_skiajni_SkiaCanvas_nSave(JNIEnv*, jclass, jlong h) {
    if (auto* c2 = getCanvas(h)) c2->save();
}

JNIEXPORT void JNICALL
Java_com_example_skiajni_SkiaCanvas_nRestore(JNIEnv*, jclass, jlong h) {
    if (auto* c2 = getCanvas(h)) c2->restore();
}

JNIEXPORT void JNICALL
Java_com_example_skiajni_SkiaCanvas_nTranslate(JNIEnv*, jclass, jlong h,
        jfloat dx, jfloat dy) {
    if (auto* c2 = getCanvas(h)) c2->translate(dx, dy);
}

JNIEXPORT void JNICALL
Java_com_example_skiajni_SkiaCanvas_nScale(JNIEnv*, jclass, jlong h,
        jfloat sx, jfloat sy) {
    if (auto* c2 = getCanvas(h)) c2->scale(sx, sy);
}

JNIEXPORT void JNICALL
Java_com_example_skiajni_SkiaCanvas_nRotate(JNIEnv*, jclass, jlong h,
        jfloat deg) {
    if (auto* c2 = getCanvas(h)) c2->rotate(deg);
}

JNIEXPORT void JNICALL
Java_com_example_skiajni_SkiaCanvas_nClipRect(JNIEnv*, jclass, jlong h,
        jfloat x, jfloat y, jfloat w, jfloat hh) {
    if (auto* c2 = getCanvas(h))
        c2->clipRect(SkRect::MakeXYWH(x, y, w, hh), true);
}

JNIEXPORT void JNICALL
Java_com_example_skiajni_SkiaCanvas_nClipPath(JNIEnv*, jclass, jlong h, jlong p) {
    auto* c2 = getCanvas(h);
    auto* np = reinterpret_cast<NativePath*>(p);
    if (c2 && np) c2->clipPath(np->detach(), true);
}

// ── Text measurement ───────────────────────────────────────────────

JNIEXPORT jfloat JNICALL
Java_com_example_skiajni_SkiaCanvas_nMeasureText(JNIEnv* env, jclass,
        jstring text, jfloat sz) {
    const char* s = env->GetStringUTFChars(text, nullptr);
    SkFont font(defaultTypeface(), sz);
    jfloat w = font.measureText(s, strlen(s), SkTextEncoding::kUTF8);
    env->ReleaseStringUTFChars(text, s);
    return w;
}

// ── Images ─────────────────────────────────────────────────────────

struct NativeImage {
    sk_sp<SkImage> image;
    int width, height;
};

JNIEXPORT jlong JNICALL
Java_com_example_skiajni_SkiaCanvas_nImageCreateFromBytes(JNIEnv* env, jclass,
        jbyteArray data) {
    jsize len = env->GetArrayLength(data);
    if (len <= 0) return 0;
    jbyte* bytes = env->GetByteArrayElements(data, nullptr);
    auto skData = SkData::MakeWithCopy(bytes, static_cast<size_t>(len));
    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);

    auto img = SkImages::DeferredFromEncodedData(std::move(skData));
    if (!img) return 0;

    auto ni = new NativeImage();
    ni->image = std::move(img);
    ni->width = ni->image->width();
    ni->height = ni->image->height();
    return reinterpret_cast<jlong>(ni);
}

JNIEXPORT void JNICALL
Java_com_example_skiajni_SkiaCanvas_nImageDestroy(JNIEnv*, jclass, jlong img) {
    delete reinterpret_cast<NativeImage*>(img);
}

JNIEXPORT jint JNICALL
Java_com_example_skiajni_SkiaCanvas_nImageGetWidth(JNIEnv*, jclass, jlong img) {
    auto ni = reinterpret_cast<NativeImage*>(img);
    return ni ? ni->width : 0;
}

JNIEXPORT jint JNICALL
Java_com_example_skiajni_SkiaCanvas_nImageGetHeight(JNIEnv*, jclass, jlong img) {
    auto ni = reinterpret_cast<NativeImage*>(img);
    return ni ? ni->height : 0;
}

JNIEXPORT void JNICALL
Java_com_example_skiajni_SkiaCanvas_nDrawImage(JNIEnv*, jclass, jlong h, jlong img,
        jfloat x, jfloat y, jfloat w, jfloat hh, jfloat alpha) {
    auto* c2 = getCanvas(h);
    auto* ni = reinterpret_cast<NativeImage*>(img);
    if (!c2 || !ni || !ni->image) return;

    c2->save();
    c2->clipRect(SkRect::MakeXYWH(x, y, w, hh), true);
    SkPaint p;
    p.setAlpha(static_cast<U8CPU>(alpha * 255.0f));
    p.setAntiAlias(true);
    SkRect dst = SkRect::MakeXYWH(x, y, w, hh);
    SkRect src = SkRect::MakeXYWH(0, 0,
                                  ni->image->width(), ni->image->height());
    c2->drawImageRect(ni->image, src, dst,
                      SkSamplingOptions(SkFilterMode::kLinear),
                      &p, SkCanvas::kFast_SrcRectConstraint);
    c2->restore();
}

// ── Round rect image clip (rounded corners) ────────────────────────

JNIEXPORT void JNICALL
Java_com_example_skiajni_SkiaCanvas_nDrawImageRounded(JNIEnv*, jclass, jlong h,
        jlong img, jfloat x, jfloat y, jfloat w, jfloat hh, jfloat r) {
    auto* c2 = getCanvas(h);
    auto* ni = reinterpret_cast<NativeImage*>(img);
    if (!c2 || !ni || !ni->image) return;

    c2->save();
    SkPath clip;
    clip = SkPath::RRect(SkRect::MakeXYWH(x, y, w, hh), r, r);
    c2->clipPath(clip, true);
    SkPaint p;
    p.setAntiAlias(true);
    SkRect dst = SkRect::MakeXYWH(x, y, w, hh);
    SkRect src = SkRect::MakeXYWH(0, 0,
                                  ni->image->width(), ni->image->height());
    c2->drawImageRect(ni->image, src, dst,
                      SkSamplingOptions(SkFilterMode::kLinear),
                      &p, SkCanvas::kFast_SrcRectConstraint);
    c2->restore();
}

JNIEXPORT void JNICALL
Java_com_example_skiajni_SkiaCanvas_nDrawText(JNIEnv* env, jclass, jlong h,
        jstring text, jfloat x, jfloat y, jint c, jfloat sz) {
    auto* c2 = getCanvas(h);
    if (!c2) return;
    const char* s = env->GetStringUTFChars(text, nullptr);
    SkPaint p; p.setColor(toARGB(c)); p.setAntiAlias(true);
    SkFont font(defaultTypeface(), sz);
    c2->drawTextBlob(SkTextBlob::MakeFromString(s, font), x, y, p);
    env->ReleaseStringUTFChars(text, s);
}

JNIEXPORT jboolean JNICALL
Java_com_example_skiajni_SkiaCanvas_nSaveToFile(JNIEnv* env, jclass, jlong h, jstring path) {
    auto nc = reinterpret_cast<NativeCanvas*>(h);
    if (!nc || !nc->surface) return JNI_FALSE;
    auto img = nc->surface->makeImageSnapshot();
    if (!img) return JNI_FALSE;
    const char* cpath = env->GetStringUTFChars(path, nullptr);
    auto data = SkPngEncoder::Encode(nullptr, img.get(), SkPngEncoder::Options());
    bool ok = false;
    if (data) {
        FILE* f = fopen(cpath, "wb");
        if (f) { fwrite(data->data(), 1, data->size(), f); fclose(f); ok = true; }
    }
    env->ReleaseStringUTFChars(path, cpath);
    return ok ? JNI_TRUE : JNI_FALSE;
}

// Copies the raster surface pixels (RGBA) into a Java byte array.
// Returns the byte[] or null if the surface isn't raster-backed.
JNIEXPORT jbyteArray JNICALL
Java_com_example_skiajni_SkiaCanvas_nGetPixels(JNIEnv* env, jclass, jlong h) {
    auto nc = reinterpret_cast<NativeCanvas*>(h);
    if (!nc || !nc->surface) return nullptr;

    auto img = nc->surface->makeImageSnapshot();
    if (!img) return nullptr;

    SkImageInfo info = SkImageInfo::Make(nc->width, nc->height,
                                         kRGBA_8888_SkColorType, kPremul_SkAlphaType);
    jsize size = static_cast<jsize>(info.computeByteSize(info.minRowBytes()));
    jbyteArray out = env->NewByteArray(size);
    if (!out) return nullptr;

    jbyte* dst = env->GetByteArrayElements(out, nullptr);
    SkPixmap pm(info, dst, info.minRowBytes());
    bool ok = img->readPixels(nullptr, pm, 0, 0);
    env->ReleaseByteArrayElements(out, dst, 0);
    return ok ? out : nullptr;
}

// ====================================================================
// Vulkan backend
// ====================================================================

// Minimal VulkanMemoryAllocator — allocates each request as its own
// VkDeviceMemory chunk via vkAllocateMemory (loaded at runtime).
class SimpleVulkanAllocator : public skgpu::VulkanMemoryAllocator {
public:
    SimpleVulkanAllocator(VkDevice device, VkPhysicalDevice physDev,
                          void* vkLib)
        : fDevice(device), fPhysDev(physDev) {
        fVkAllocateMemory = reinterpret_cast<PFN_vkAllocateMemory>(
            dlsym(vkLib, "vkAllocateMemory"));
        fVkFreeMemory = reinterpret_cast<PFN_vkFreeMemory>(
            dlsym(vkLib, "vkFreeMemory"));
        fVkMapMemory = reinterpret_cast<PFN_vkMapMemory>(
            dlsym(vkLib, "vkMapMemory"));
        fVkUnmapMemory = reinterpret_cast<PFN_vkUnmapMemory>(
            dlsym(vkLib, "vkUnmapMemory"));
        fVkGetBufferMemoryRequirements = reinterpret_cast<PFN_vkGetBufferMemoryRequirements>(
            dlsym(vkLib, "vkGetBufferMemoryRequirements"));
        fVkGetImageMemoryRequirements = reinterpret_cast<PFN_vkGetImageMemoryRequirements>(
            dlsym(vkLib, "vkGetImageMemoryRequirements"));
        fVkGetPhysicalDeviceMemoryProperties = reinterpret_cast<PFN_vkGetPhysicalDeviceMemoryProperties>(
            dlsym(vkLib, "vkGetPhysicalDeviceMemoryProperties"));

        fVkGetPhysicalDeviceMemoryProperties(fPhysDev, &fMemProps);
    }

    VkResult allocateImageMemory(VkImage image, uint32_t,
                                 skgpu::VulkanBackendMemory* memory) override {
        VkMemoryRequirements reqs;
        fVkGetImageMemoryRequirements(fDevice, image, &reqs);
        return alloc(reqs, memory);
    }

    VkResult allocateBufferMemory(VkBuffer buffer, BufferUsage,
                                  uint32_t, skgpu::VulkanBackendMemory* memory) override {
        VkMemoryRequirements reqs;
        fVkGetBufferMemoryRequirements(fDevice, buffer, &reqs);
        return alloc(reqs, memory);
    }

    void getAllocInfo(const skgpu::VulkanBackendMemory& memory,
                      skgpu::VulkanAlloc* alloc) const override {
        auto it = fAllocs.find(memory);
        if (it != fAllocs.end()) *alloc = it->second;
    }

    void* mapMemory(const skgpu::VulkanBackendMemory& memory) override {
        auto it = fAllocs.find(memory);
        if (it == fAllocs.end()) return nullptr;
        void* data = nullptr;
        if (fVkMapMemory(fDevice, it->second.fMemory, it->second.fOffset,
                         it->second.fSize, 0, &data) != VK_SUCCESS) return nullptr;
        return data;
    }

    void unmapMemory(const skgpu::VulkanBackendMemory& memory) override {
        auto it = fAllocs.find(memory);
        if (it != fAllocs.end()) fVkUnmapMemory(fDevice, it->second.fMemory);
    }

    void freeMemory(const skgpu::VulkanBackendMemory& memory) override {
        auto it = fAllocs.find(memory);
        if (it != fAllocs.end()) {
            fVkFreeMemory(fDevice, it->second.fMemory, nullptr);
            fAllocs.erase(it);
        }
    }

    std::pair<uint64_t, uint64_t> totalAllocatedAndUsedMemory() const override {
        uint64_t total = 0;
        for (const auto& [k, v] : fAllocs) total += v.fSize;
        return {total, total};
    }

private:
    VkResult alloc(const VkMemoryRequirements& reqs,
                   skgpu::VulkanBackendMemory* memory) {
        // Pick a memory type: prefer device-local, fall back to any usable type.
        uint32_t typeIndex = 0;
        bool found = false;
        for (uint32_t i = 0; i < fMemProps.memoryTypeCount; i++) {
            if (reqs.memoryTypeBits & (1u << i)) {
                typeIndex = i;
                found = true;
                if (fMemProps.memoryTypes[i].propertyFlags &
                        VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT) {
                    break;  // prefer device-local
                }
            }
        }
        if (!found) return VK_ERROR_OUT_OF_DEVICE_MEMORY;

        VkMemoryAllocateInfo info{};
        info.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
        info.allocationSize = reqs.size;
        info.memoryTypeIndex = typeIndex;

        VkDeviceMemory mem;
        if (fVkAllocateMemory(fDevice, &info, nullptr, &mem) != VK_SUCCESS)
            return VK_ERROR_OUT_OF_DEVICE_MEMORY;

        intptr_t key = reinterpret_cast<intptr_t>(mem);
        skgpu::VulkanAlloc alloc;
        alloc.fMemory = mem;
        alloc.fOffset = 0;
        alloc.fSize = reqs.size;
        alloc.fBackendMemory = key;
        fAllocs[key] = alloc;
        *memory = key;
        return VK_SUCCESS;
    }

    VkDevice fDevice;
    VkPhysicalDevice fPhysDev;
    VkPhysicalDeviceMemoryProperties fMemProps{};
    std::map<intptr_t, skgpu::VulkanAlloc> fAllocs;

    PFN_vkAllocateMemory fVkAllocateMemory;
    PFN_vkFreeMemory fVkFreeMemory;
    PFN_vkMapMemory fVkMapMemory;
    PFN_vkUnmapMemory fVkUnmapMemory;
    PFN_vkGetBufferMemoryRequirements fVkGetBufferMemoryRequirements;
    PFN_vkGetImageMemoryRequirements fVkGetImageMemoryRequirements;
    PFN_vkGetPhysicalDeviceMemoryProperties fVkGetPhysicalDeviceMemoryProperties;
};

JNIEXPORT jlong JNICALL
Java_com_example_skiajni_SkiaCanvas_createVulkanContext(JNIEnv*, jclass,
        jlong vkInstance, jlong vkPhysDev, jlong vkDevice,
        jlong vkQueue, jint queueFamily, jint apiVersion) {

    // Load the Vulkan loader at runtime (device library, not available in NDK).
    void* vkLib = dlopen("libvulkan.so", RTLD_NOW | RTLD_GLOBAL);
    if (!vkLib) return 0;

    // Build backend context
    skgpu::VulkanBackendContext backend{};
    backend.fInstance         = reinterpret_cast<VkInstance>(vkInstance);
    backend.fPhysicalDevice   = reinterpret_cast<VkPhysicalDevice>(vkPhysDev);
    backend.fDevice           = reinterpret_cast<VkDevice>(vkDevice);
    backend.fQueue            = reinterpret_cast<VkQueue>(vkQueue);
    backend.fGraphicsQueueIndex = queueFamily;
    backend.fMaxAPIVersion    = apiVersion;
    backend.fGetProc          = [vkLib](const char* name,
                                        VkInstance instance,
                                        VkDevice device) -> PFN_vkVoidFunction {
        if (device) {
            auto vkGetDeviceProcAddr = reinterpret_cast<PFN_vkGetDeviceProcAddr>(
                dlsym(vkLib, "vkGetDeviceProcAddr"));
            if (vkGetDeviceProcAddr) {
                PFN_vkVoidFunction fn = vkGetDeviceProcAddr(device, name);
                if (fn) return fn;
            }
        }
        auto vkGetInstanceProcAddr = reinterpret_cast<PFN_vkGetInstanceProcAddr>(
            dlsym(vkLib, "vkGetInstanceProcAddr"));
        if (vkGetInstanceProcAddr && instance) {
            return vkGetInstanceProcAddr(instance, name);
        }
        return reinterpret_cast<PFN_vkVoidFunction>(dlsym(vkLib, name));
    };

    auto vCtx = new VulkanCtx();
    vCtx->allocator = sk_make_sp<SimpleVulkanAllocator>(
        backend.fDevice, backend.fPhysicalDevice, vkLib);
    backend.fMemoryAllocator = vCtx->allocator;

    auto grContext = GrDirectContexts::MakeVulkan(backend);
    if (!grContext) {
        delete vCtx;
        return 0;
    }

    vCtx->grContext = std::move(grContext);
    vCtx->device = backend.fDevice;

    // Return as opaque handle; we store grContext inside NativeCanvas later
    return reinterpret_cast<jlong>(vCtx);
}

JNIEXPORT void JNICALL
Java_com_example_skiajni_SkiaCanvas_destroyVulkanContext(JNIEnv*, jclass, jlong h) {
    auto vCtx = reinterpret_cast<VulkanCtx*>(h);
    if (vCtx) { vCtx->grContext.reset(); delete vCtx; }
}

JNIEXPORT jlong JNICALL
Java_com_example_skiajni_SkiaCanvas_createVulkanCanvas(JNIEnv*, jclass,
        jlong ctxHandle, jlong vkImage, jint w, jint h,
        jint format, jint usageFlags) {

    auto vCtx = reinterpret_cast<VulkanCtx*>(ctxHandle);
    if (!vCtx || !vCtx->grContext) return 0;

    GrVkImageInfo imageInfo{};
    imageInfo.fImage             = reinterpret_cast<VkImage>(vkImage);
    imageInfo.fImageTiling       = VK_IMAGE_TILING_OPTIMAL;
    imageInfo.fImageLayout       = VK_IMAGE_LAYOUT_UNDEFINED;
    imageInfo.fFormat            = static_cast<VkFormat>(format);
    imageInfo.fImageUsageFlags   = usageFlags;
    imageInfo.fSampleCount       = 1;
    imageInfo.fLevelCount        = 1;
    imageInfo.fCurrentQueueFamily = VK_QUEUE_FAMILY_EXTERNAL;

    auto backendRT = GrBackendRenderTargets::MakeVk(w, h, imageInfo);
    auto surface = SkSurfaces::WrapBackendRenderTarget(
        vCtx->grContext.get(), backendRT,
        kTopLeft_GrSurfaceOrigin, kRGBA_8888_SkColorType,
        nullptr, nullptr, nullptr, nullptr);

    if (!surface) return 0;

    auto nc = new NativeCanvas();
    nc->backend   = NativeCanvas::VULKAN;
    nc->width     = w;
    nc->height    = h;
    nc->surface   = std::move(surface);
    nc->grContext = vCtx->grContext;
    return reinterpret_cast<jlong>(nc);
}

JNIEXPORT void JNICALL
Java_com_example_skiajni_SkiaCanvas_submitVulkanCanvas(JNIEnv*, jclass,
        jlong ctxHandle, jlong canvasHandle) {
    auto vCtx = reinterpret_cast<VulkanCtx*>(ctxHandle);
    auto nc   = reinterpret_cast<NativeCanvas*>(canvasHandle);
    if (vCtx && vCtx->grContext && nc && nc->surface) {
        vCtx->grContext->flushAndSubmit(nc->surface.get());
    }
}

} // extern "C"
