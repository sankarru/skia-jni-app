#include <jni.h>
#include <cstdio>
#include <vector>
#include <map>
#include <dlfcn.h>

// ── Skia core ──────────────────────────────────────────────────────
#include "include/core/SkCanvas.h"
#include "include/core/SkColor.h"
#include "include/core/SkPaint.h"
#include "include/core/SkSurface.h"
#include "include/core/SkFont.h"
#include "include/core/SkTextBlob.h"
#include "include/core/SkTypeface.h"
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
Java_com_example_skiajni_SkiaCanvas_nDrawText(JNIEnv* env, jclass, jlong h,
        jstring text, jfloat x, jfloat y, jint c, jfloat sz) {
    auto* c2 = getCanvas(h);
    if (!c2) return;
    const char* s = env->GetStringUTFChars(text, nullptr);
    SkPaint p; p.setColor(toARGB(c)); p.setAntiAlias(true);
    SkFont font;
    font.setSize(sz);
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

    auto img = nc->surface->peekPixels()
        ? nc->surface->makeImageSnapshot()
        : nullptr;
    if (!img) return nullptr;

    SkImageInfo info = SkImageInfo::Make(nc->width, nc->height,
                                         kRGBA_8888_SkColorType, kPremul_SkAlphaType);
    jsize size = static_cast<jsize>(info.computeByteSize(info.minRowBytes()));
    jbyteArray out = env->NewByteArray(size);
    if (!out) return nullptr;

    jbyte* dst = env->GetByteArrayElements(out, nullptr);
    bool ok = img->readPixels(info, dst, info.minRowBytes(), 0, 0);
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
