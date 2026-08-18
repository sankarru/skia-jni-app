#include <jni.h>
#include <cstdio>
#include <vector>

// ── Skia core ──────────────────────────────────────────────────────
#include "include/core/SkCanvas.h"
#include "include/core/SkColor.h"
#include "include/core/SkPaint.h"
#include "include/core/SkSurface.h"
#include "include/core/SkFont.h"
#include "include/core/SkTextBlob.h"
#include "include/core/SkTypeface.h"
#include "include/codec/SkPngEncoder.h"

// ── Skia GPU / Vulkan ─────────────────────────────────────────────
#include "include/gpu/ganesh/GrDirectContext.h"
#include "include/gpu/ganesh/vk/GrVkDirectContext.h"
#include "include/gpu/ganesh/vk/GrVkBackendSurface.h"
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
    VkDevice device;
};

// ====================================================================
// Helpers
// ====================================================================

static inline SkColor toARGB(jint c) {
    return SkColorSetARGB((c>>24)&0xFF,(c>>16)&0xFF,(c>>8)&0xFF,c&0xFF);
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
        SkPaint p; p.setColor(toARGB(c)); p.setStyle(SkPaintStyle::kStroke);
        p.setStrokeWidth(s); p.setAntiAlias(true);
        c2->drawRect(SkRect::MakeXYWH(x, y, w, hh), p);
    }
}

JNIEXPORT void JNICALL
Java_com_example_skiajni_SkiaCanvas_nDrawCircle(JNIEnv*, jclass, jlong h,
        jfloat cx, jfloat cy, jfloat r, jint c, jfloat s) {
    if (auto* c2 = getCanvas(h)) {
        SkPaint p; p.setColor(toARGB(c)); p.setStyle(SkPaintStyle::kStroke);
        p.setStrokeWidth(s); p.setAntiAlias(true);
        c2->drawCircle(cx, cy, r, p);
    }
}

JNIEXPORT void JNICALL
Java_com_example_skiajni_SkiaCanvas_nDrawLine(JNIEnv*, jclass, jlong h,
        jfloat x0, jfloat y0, jfloat x1, jfloat y1, jint c, jfloat s) {
    if (auto* c2 = getCanvas(h)) {
        SkPaint p; p.setColor(toARGB(c)); p.setStrokeWidth(s);
        p.setStrokeCap(SkPaintCap::kRound); p.setAntiAlias(true);
        c2->drawLine(x0, y0, x1, y1, p);
    }
}

JNIEXPORT void JNICALL
Java_com_example_skiajni_SkiaCanvas_nDrawText(JNIEnv* env, jclass, jlong h,
        jstring text, jfloat x, jfloat y, jint c, jfloat sz) {
    auto* c2 = getCanvas(h);
    if (!c2) return;
    const char* s = env->GetStringUTFChars(text, nullptr);
    SkPaint p; p.setColor(toARGB(c)); p.setAntiAlias(true);
    SkFont font(SkTypeface::MakeDefault(), sz);
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

// ====================================================================
// Vulkan backend
// ====================================================================

static VkPhysicalDeviceMemoryProperties sMemProps;

JNIEXPORT jlong JNICALL
Java_com_example_skiajni_SkiaCanvas_createVulkanContext(JNIEnv*, jclass,
        jlong vkInstance, jlong vkPhysDev, jlong vkDevice,
        jlong vkQueue, jint queueFamily, jint apiVersion) {

    vkGetPhysicalDeviceMemoryProperties(
        reinterpret_cast<VkPhysicalDevice>(vkPhysDev), &sMemProps);

    // Build backend context
    skgpu::VulkanBackendContext backend{};
    backend.fInstance         = reinterpret_cast<VkInstance>(vkInstance);
    backend.fPhysicalDevice   = reinterpret_cast<VkPhysicalDevice>(vkPhysDev);
    backend.fDevice           = reinterpret_cast<VkDevice>(vkDevice);
    backend.fQueue            = reinterpret_cast<VkQueue>(vkQueue);
    backend.fGraphicsQueueIndex = queueFamily;
    backend.fMaxAPIVersion    = apiVersion;

    auto grContext = GrDirectContexts::MakeVulkan(backend);
    if (!grContext) return 0;

    auto vCtx = new VulkanCtx();
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
