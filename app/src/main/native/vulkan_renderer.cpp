#include <jni.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <dlfcn.h>
#include <mutex>
#include <vector>
#include <map>
#include <algorithm>
#include <cstring>

// ── Vulkan ─────────────────────────────────────────────────────────
#include <vulkan/vulkan.h>
#include <vulkan/vulkan_android.h>

// ── Skia GPU ───────────────────────────────────────────────────────
#include "include/core/SkCanvas.h"
#include "include/core/SkColor.h"
#include "include/core/SkColorSpace.h"
#include "include/core/SkPaint.h"
#include "include/core/SkSurface.h"
#include "include/gpu/ganesh/GrDirectContext.h"
#include "include/gpu/ganesh/GrBackendSurface.h"
#include "include/gpu/ganesh/SkSurfaceGanesh.h"
#include "include/gpu/ganesh/vk/GrVkDirectContext.h"
#include "include/gpu/ganesh/vk/GrVkBackendSurface.h"
#include "include/gpu/ganesh/vk/GrVkTypes.h"
#include "include/gpu/vk/VulkanBackendContext.h"
#include "include/gpu/vk/VulkanMemoryAllocator.h"

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "SkiaVk", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "SkiaVk", __VA_ARGS__)

namespace {

// Minimal VulkanMemoryAllocator for Skia (per-request VkDeviceMemory).
class SimpleVma : public skgpu::VulkanMemoryAllocator {
public:
    SimpleVma(VkDevice dev, VkPhysicalDevice phys, void* lib) : fDev(dev) {
        fAlloc      = (PFN_vkAllocateMemory)dlsym(lib, "vkAllocateMemory");
        fFree       = (PFN_vkFreeMemory)dlsym(lib, "vkFreeMemory");
        fMap        = (PFN_vkMapMemory)dlsym(lib, "vkMapMemory");
        fUnmap      = (PFN_vkUnmapMemory)dlsym(lib, "vkUnmapMemory");
        fGetBuf     = (PFN_vkGetBufferMemoryRequirements)dlsym(lib, "vkGetBufferMemoryRequirements");
        fGetImg     = (PFN_vkGetImageMemoryRequirements)dlsym(lib, "vkGetImageMemoryRequirements");
        fGetMemProps= (PFN_vkGetPhysicalDeviceMemoryProperties)dlsym(lib, "vkGetPhysicalDeviceMemoryProperties");
        fGetMemProps(phys, &fProps);
    }

    VkResult allocateImageMemory(VkImage image, uint32_t, skgpu::VulkanBackendMemory* m) override {
        VkMemoryRequirements r; fGetImg(fDev, image, &r); return doAlloc(r, m);
    }
    VkResult allocateBufferMemory(VkBuffer buffer, BufferUsage, uint32_t, skgpu::VulkanBackendMemory* m) override {
        VkMemoryRequirements r; fGetBuf(fDev, buffer, &r); return doAlloc(r, m);
    }
    void getAllocInfo(const skgpu::VulkanBackendMemory& mem, skgpu::VulkanAlloc* a) const override {
        auto it = fAllocs.find(mem); if (it != fAllocs.end()) *a = it->second;
    }
    void* mapMemory(const skgpu::VulkanBackendMemory& mem) override {
        auto it = fAllocs.find(mem); if (it == fAllocs.end()) return nullptr;
        void* d = nullptr;
        if (fMap(fDev, it->second.fMemory, it->second.fOffset, it->second.fSize, 0, &d) != VK_SUCCESS) return nullptr;
        return d;
    }
    void unmapMemory(const skgpu::VulkanBackendMemory& mem) override {
        auto it = fAllocs.find(mem); if (it != fAllocs.end()) fUnmap(fDev, it->second.fMemory);
    }
    void freeMemory(const skgpu::VulkanBackendMemory& mem) override {
        auto it = fAllocs.find(mem);
        if (it != fAllocs.end()) { fFree(fDev, it->second.fMemory, nullptr); fAllocs.erase(it); }
    }
    std::pair<uint64_t,uint64_t> totalAllocatedAndUsedMemory() const override {
        uint64_t t=0; for (auto& [k,v] : fAllocs) t+=v.fSize; return {t,t};
    }

private:
    VkResult doAlloc(const VkMemoryRequirements& req, skgpu::VulkanBackendMemory* out) {
        uint32_t idx=0; bool found=false;
        for (uint32_t i=0;i<fProps.memoryTypeCount;i++) if (req.memoryTypeBits & (1u<<i)) {
            idx=i; found=true;
            if (fProps.memoryTypes[i].propertyFlags & VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT) break;
        }
        if (!found) return VK_ERROR_OUT_OF_DEVICE_MEMORY;
        VkMemoryAllocateInfo ai{}; ai.sType=VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
        ai.allocationSize=req.size; ai.memoryTypeIndex=idx;
        VkDeviceMemory mem;
        if (fAlloc(fDev,&ai,nullptr,&mem)!=VK_SUCCESS) return VK_ERROR_OUT_OF_DEVICE_MEMORY;
        intptr_t key=(intptr_t)mem;
        skgpu::VulkanAlloc a; a.fMemory=mem; a.fOffset=0; a.fSize=req.size; a.fBackendMemory=key;
        fAllocs[key]=a; *out=key; return VK_SUCCESS;
    }
    VkDevice fDev;
    VkPhysicalDeviceMemoryProperties fProps{};
    std::map<intptr_t, skgpu::VulkanAlloc> fAllocs;
    PFN_vkAllocateMemory fAlloc; PFN_vkFreeMemory fFree; PFN_vkMapMemory fMap; PFN_vkUnmapMemory fUnmap;
    PFN_vkGetBufferMemoryRequirements fGetBuf; PFN_vkGetImageMemoryRequirements fGetImg;
    PFN_vkGetPhysicalDeviceMemoryProperties fGetMemProps;
};

// Vulkan swapchain renderer bound to an ANativeWindow.
struct VulkanRenderer {
    void* vkLib = nullptr;

    VkInstance instance = VK_NULL_HANDLE;
    VkSurfaceKHR surface = VK_NULL_HANDLE;
    VkPhysicalDevice physDev = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;
    uint32_t queueFamily = 0;
    VkQueue queue = VK_NULL_HANDLE;
    VkSwapchainKHR swapchain = VK_NULL_HANDLE;
    VkFormat format = VK_FORMAT_UNDEFINED;
    VkExtent2D extent{};

    std::vector<VkImage> images;
    std::vector<VkImageView> imageViews;
    std::vector<sk_sp<SkSurface>> skSurfaces;

    sk_sp<GrDirectContext> grContext;
    sk_sp<skgpu::VulkanMemoryAllocator> allocator;

    PFN_vkGetInstanceProcAddr pfnGetInstanceProcAddr = nullptr;

    // Vulkan function pointers (loaded from libvulkan.so)
    PFN_vkCreateInstance pCreateInstance;
    PFN_vkDestroyInstance pDestroyInstance;
    PFN_vkEnumeratePhysicalDevices pEnumPhys;
    PFN_vkGetPhysicalDeviceQueueFamilyProperties pGetQueueProps;
    PFN_vkGetPhysicalDeviceSurfaceSupportKHR pGetSurfaceSupport;
    PFN_vkGetPhysicalDeviceSurfaceFormatsKHR pGetSurfaceFormats;
    PFN_vkGetPhysicalDeviceSurfaceCapabilitiesKHR pGetSurfaceCaps;
    PFN_vkCreateDevice pCreateDevice;
    PFN_vkGetDeviceQueue pGetDeviceQueue;
    PFN_vkDestroyDevice pDestroyDevice;
    PFN_vkDestroySurfaceKHR pDestroySurface;
    PFN_vkGetDeviceProcAddr pGetDeviceProcAddr;
    PFN_vkCreateAndroidSurfaceKHR pCreateAndroidSurface;
    PFN_vkCreateSwapchainKHR pCreateSwapchain;
    PFN_vkGetSwapchainImagesKHR pGetSwapchainImages;
    PFN_vkDestroySwapchainKHR pDestroySwapchain;
    PFN_vkCreateImageView pCreateImageView;
    PFN_vkDestroyImageView pDestroyImageView;
    PFN_vkAcquireNextImageKHR pAcquireNextImage;
    PFN_vkQueuePresentKHR pQueuePresent;
};

template <typename T>
static T loadInst(PFN_vkGetInstanceProcAddr p, VkInstance inst, const char* name) {
    return reinterpret_cast<T>(p(inst, name));
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_example_skiajni_VulkanSurfaceView_nCreate(JNIEnv* env, jobject,
        jobject androidWindow, jint width, jint height) {
    ANativeWindow* win = ANativeWindow_fromSurface(env, androidWindow);
    if (!win) { LOGE("ANativeWindow_fromSurface failed"); return 0; }

    auto* r = new VulkanRenderer();
    r->vkLib = dlopen("libvulkan.so", RTLD_NOW | RTLD_GLOBAL);
    if (!r->vkLib) { LOGE("dlopen libvulkan.so failed"); delete r; return 0; }

    r->pfnGetInstanceProcAddr = (PFN_vkGetInstanceProcAddr)dlsym(r->vkLib, "vkGetInstanceProcAddr");
    if (!r->pfnGetInstanceProcAddr) { LOGE("no vkGetInstanceProcAddr"); delete r; return 0; }

    // ── Instance ────────────────────────────────────────────────────
    const char* instExts[] = { VK_KHR_SURFACE_EXTENSION_NAME, VK_KHR_ANDROID_SURFACE_EXTENSION_NAME };
    VkApplicationInfo app{}; app.sType=VK_STRUCTURE_TYPE_APPLICATION_INFO;
    app.pApplicationName="skia-jni"; app.applicationVersion=1; app.pEngineName="skia"; app.engineVersion=1;
    app.apiVersion=VK_API_VERSION_1_1;
    VkInstanceCreateInfo ici{}; ici.sType=VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    ici.pApplicationInfo=&app; ici.enabledExtensionCount=2; ici.ppEnabledExtensionNames=instExts;
    r->pCreateInstance = loadInst<PFN_vkCreateInstance>(r->pfnGetInstanceProcAddr, VK_NULL_HANDLE, "vkCreateInstance");
    if (r->pCreateInstance(&ici,nullptr,&r->instance)!=VK_SUCCESS) {
        LOGE("vkCreateInstance failed"); delete r; return 0;
    }
    auto ipa = r->pfnGetInstanceProcAddr;
    r->pDestroyInstance = loadInst<PFN_vkDestroyInstance>(ipa, r->instance, "vkDestroyInstance");
    r->pEnumPhys = loadInst<PFN_vkEnumeratePhysicalDevices>(ipa, r->instance, "vkEnumeratePhysicalDevices");
    r->pGetQueueProps = loadInst<PFN_vkGetPhysicalDeviceQueueFamilyProperties>(ipa, r->instance, "vkGetPhysicalDeviceQueueFamilyProperties");
    r->pGetSurfaceSupport = loadInst<PFN_vkGetPhysicalDeviceSurfaceSupportKHR>(ipa, r->instance, "vkGetPhysicalDeviceSurfaceSupportKHR");
    r->pGetSurfaceFormats = loadInst<PFN_vkGetPhysicalDeviceSurfaceFormatsKHR>(ipa, r->instance, "vkGetPhysicalDeviceSurfaceFormatsKHR");
    r->pGetSurfaceCaps = loadInst<PFN_vkGetPhysicalDeviceSurfaceCapabilitiesKHR>(ipa, r->instance, "vkGetPhysicalDeviceSurfaceCapabilitiesKHR");
    r->pCreateDevice = loadInst<PFN_vkCreateDevice>(ipa, r->instance, "vkCreateDevice");
    r->pGetDeviceQueue = loadInst<PFN_vkGetDeviceQueue>(ipa, r->instance, "vkGetDeviceQueue");
    r->pDestroyDevice = loadInst<PFN_vkDestroyDevice>(ipa, r->instance, "vkDestroyDevice");
    r->pDestroySurface = loadInst<PFN_vkDestroySurfaceKHR>(ipa, r->instance, "vkDestroySurfaceKHR");
    r->pCreateAndroidSurface = loadInst<PFN_vkCreateAndroidSurfaceKHR>(ipa, r->instance, "vkCreateAndroidSurfaceKHR");
    r->pGetDeviceProcAddr = loadInst<PFN_vkGetDeviceProcAddr>(ipa, r->instance, "vkGetDeviceProcAddr");
    if (!r->pCreateAndroidSurface) { LOGE("vkCreateAndroidSurfaceKHR not available"); return 0; }

    // ── Surface ─────────────────────────────────────────────────────
    VkAndroidSurfaceCreateInfoKHR sci{}; sci.sType=VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR;
    sci.window=win;
    if (r->pCreateAndroidSurface(r->instance,&sci,nullptr,&r->surface)!=VK_SUCCESS) {
        LOGE("vkCreateAndroidSurfaceKHR failed"); r->pDestroyInstance(r->instance,nullptr); delete r; return 0;
    }

    // ── Physical device + queue family ──────────────────────────────
    uint32_t nDev=0; r->pEnumPhys(r->instance,&nDev,nullptr);
    std::vector<VkPhysicalDevice> devs(nDev); r->pEnumPhys(r->instance,&nDev,devs.data());
    if (nDev==0) { LOGE("no physical devices"); return 0; }
    r->physDev = devs[0];

    uint32_t nQF=0; r->pGetQueueProps(r->physDev,&nQF,nullptr);
    std::vector<VkQueueFamilyProperties> qfp(nQF); r->pGetQueueProps(r->physDev,&nQF,qfp.data());
    VkBool32 found=VK_FALSE;
    for (uint32_t i=0;i<nQF;i++) {
        VkBool32 supports=VK_FALSE;
        r->pGetSurfaceSupport(r->physDev,i,r->surface,&supports);
        if ((qfp[i].queueFlags & VK_QUEUE_GRAPHICS_BIT) && supports) { r->queueFamily=i; found=VK_TRUE; break; }
    }
    if (!found) { LOGE("no graphics+present queue"); return 0; }

    // ── Device ──────────────────────────────────────────────────────
    const float prio=1.0f;
    VkDeviceQueueCreateInfo dq{}; dq.sType=VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
    dq.queueFamilyIndex=r->queueFamily; dq.queueCount=1; dq.pQueuePriorities=&prio;
    const char* devExts[] = { VK_KHR_SWAPCHAIN_EXTENSION_NAME };
    VkDeviceCreateInfo dci{}; dci.sType=VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
    dci.queueCreateInfoCount=1; dci.pQueueCreateInfos=&dq;
    dci.enabledExtensionCount=1; dci.ppEnabledExtensionNames=devExts;
    if (r->pCreateDevice(r->physDev,&dci,nullptr,&r->device)!=VK_SUCCESS) {
        LOGE("vkCreateDevice failed"); return 0;
    }
    r->pGetDeviceQueue(r->device,r->queueFamily,0,&r->queue);

    // Device-level functions
    auto gdp = r->pGetDeviceProcAddr;
    r->pCreateSwapchain = (PFN_vkCreateSwapchainKHR)gdp(r->device,"vkCreateSwapchainKHR");
    r->pGetSwapchainImages = (PFN_vkGetSwapchainImagesKHR)gdp(r->device,"vkGetSwapchainImagesKHR");
    r->pDestroySwapchain = (PFN_vkDestroySwapchainKHR)gdp(r->device,"vkDestroySwapchainKHR");
    r->pCreateImageView = (PFN_vkCreateImageView)gdp(r->device,"vkCreateImageView");
    r->pDestroyImageView = (PFN_vkDestroyImageView)gdp(r->device,"vkDestroyImageView");
    r->pAcquireNextImage = (PFN_vkAcquireNextImageKHR)gdp(r->device,"vkAcquireNextImageKHR");
    r->pQueuePresent = (PFN_vkQueuePresentKHR)gdp(r->device,"vkQueuePresentKHR");

    // ── Swapchain ───────────────────────────────────────────────────
    VkSurfaceCapabilitiesKHR caps{};
    r->pGetSurfaceCaps(r->physDev,r->surface,&caps);
    uint32_t nFmt=0; r->pGetSurfaceFormats(r->physDev,r->surface,&nFmt,nullptr);
    std::vector<VkSurfaceFormatKHR> fmts(nFmt); r->pGetSurfaceFormats(r->physDev,r->surface,&nFmt,fmts.data());
    r->format = fmts[0].format;
    r->extent.width = std::min((uint32_t)width, caps.maxImageExtent.width);
    r->extent.height = std::min((uint32_t)height, caps.maxImageExtent.height);
    if (r->extent.width==0) r->extent.width=caps.currentExtent.width;
    if (r->extent.height==0) r->extent.height=caps.currentExtent.height;

    VkSwapchainCreateInfoKHR sw{}; sw.sType=VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR;
    sw.surface=r->surface; sw.minImageCount=2; sw.imageFormat=r->format;
    sw.imageColorSpace=fmts[0].colorSpace; sw.imageExtent=r->extent;
    sw.imageArrayLayers=1; sw.imageUsage=VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
    sw.imageSharingMode=VK_SHARING_MODE_EXCLUSIVE; sw.preTransform=caps.currentTransform;
    sw.compositeAlpha=VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR; sw.presentMode=VK_PRESENT_MODE_FIFO_KHR;
    sw.clipped=VK_TRUE; sw.oldSwapchain=VK_NULL_HANDLE;
    if (r->pCreateSwapchain(r->device,&sw,nullptr,&r->swapchain)!=VK_SUCCESS) {
        LOGE("vkCreateSwapchainKHR failed"); return 0;
    }
    uint32_t nImg=0; r->pGetSwapchainImages(r->device,r->swapchain,&nImg,nullptr);
    r->images.resize(nImg); r->pGetSwapchainImages(r->device,r->swapchain,&nImg,r->images.data());

    // Image views
    for (auto img : r->images) {
        VkImageViewCreateInfo iv{}; iv.sType=VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
        iv.image=img; iv.viewType=VK_IMAGE_VIEW_TYPE_2D; iv.format=r->format;
        iv.subresourceRange.aspectMask=VK_IMAGE_ASPECT_COLOR_BIT;
        iv.subresourceRange.levelCount=1; iv.subresourceRange.layerCount=1;
        VkImageView v;
        r->pCreateImageView(r->device,&iv,nullptr,&v);
        r->imageViews.push_back(v);
    }

    // ── Skia GrDirectContext ────────────────────────────────────────
    skgpu::VulkanBackendContext backend{};
    backend.fInstance=r->instance; backend.fPhysicalDevice=r->physDev; backend.fDevice=r->device;
    backend.fQueue=r->queue; backend.fGraphicsQueueIndex=r->queueFamily;
    backend.fMaxAPIVersion=VK_API_VERSION_1_1;
    backend.fGetProc = [r](const char* name, VkInstance inst, VkDevice dev) -> PFN_vkVoidFunction {
        if (dev && r->pGetDeviceProcAddr) {
            PFN_vkVoidFunction fn = r->pGetDeviceProcAddr(dev, name);
            if (fn) return fn;
        }
        return reinterpret_cast<PFN_vkVoidFunction>(r->pfnGetInstanceProcAddr(inst, name));
    };
    r->allocator = sk_make_sp<SimpleVma>(r->device, r->physDev, r->vkLib);
    backend.fMemoryAllocator = r->allocator;
    r->grContext = GrDirectContexts::MakeVulkan(backend);
    if (!r->grContext) { LOGE("GrDirectContexts::MakeVulkan failed"); return 0; }

    LOGI("Vulkan renderer created: %ux%u format=%d images=%zu", r->extent.width, r->extent.height, (int)r->format, r->images.size());
    return reinterpret_cast<jlong>(r);
}

JNIEXPORT void JNICALL
Java_com_example_skiajni_VulkanSurfaceView_nDestroy(JNIEnv*, jobject, jlong h) {
    auto* r = reinterpret_cast<VulkanRenderer*>(h);
    if (!r) return;
    r->grContext.reset();
    for (auto v : r->imageViews) r->pDestroyImageView(r->device, v, nullptr);
    r->imageViews.clear();
    if (r->swapchain) r->pDestroySwapchain(r->device, r->swapchain, nullptr);
    if (r->device) r->pDestroyDevice(r->device, nullptr);
    if (r->surface) r->pDestroySurface(r->instance, r->surface, nullptr);
    if (r->instance) r->pDestroyInstance(r->instance, nullptr);
    if (r->vkLib) dlclose(r->vkLib);
    delete r;
}

// Render one frame: acquire swapchain image, wrap in SkSurface, draw, present.
JNIEXPORT void JNICALL
Java_com_example_skiajni_VulkanSurfaceView_nRender(JNIEnv*, jobject, jlong h) {
    auto* r = reinterpret_cast<VulkanRenderer*>(h);
    if (!r || !r->grContext) return;

    uint32_t imgIndex = 0;
    VkResult res = r->pAcquireNextImage(r->device, r->swapchain, UINT64_MAX,
                                        VK_NULL_HANDLE, VK_NULL_HANDLE, &imgIndex);
    if (res != VK_SUCCESS && res != VK_SUBOPTIMAL_KHR) return;

    // Ensure SkSurface for this swapchain image exists.
    if (r->skSurfaces.size() <= imgIndex || !r->skSurfaces[imgIndex]) {
        GrVkImageInfo vkInfo{};
        vkInfo.fImage = r->images[imgIndex];
        vkInfo.fImageTiling = VK_IMAGE_TILING_OPTIMAL;
        vkInfo.fImageLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        vkInfo.fFormat = r->format;
        vkInfo.fImageUsageFlags = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
        vkInfo.fSampleCount = 1;
        vkInfo.fLevelCount = 1;
        vkInfo.fCurrentQueueFamily = r->queueFamily;
        auto backendRT = GrBackendRenderTargets::MakeVk(r->extent.width, r->extent.height, vkInfo);
        auto surf = SkSurfaces::WrapBackendRenderTarget(
            r->grContext.get(), backendRT, kTopLeft_GrSurfaceOrigin,
            kRGBA_8888_SkColorType, nullptr, nullptr, nullptr, nullptr);
        if (surf) {
            if (r->skSurfaces.size() <= imgIndex) r->skSurfaces.resize(imgIndex + 1);
            r->skSurfaces[imgIndex] = std::move(surf);
        }
    }
    if (imgIndex >= r->skSurfaces.size() || !r->skSurfaces[imgIndex]) return;

    // Basic Skia GPU draw: clear + a shape + text to prove Vulkan rendering.
    auto* canvas = r->skSurfaces[imgIndex]->getCanvas();
    canvas->clear(SkColorSetARGB(255, 18, 18, 18));

    SkPaint p;
    p.setColor(SK_ColorCYAN);
    p.setStyle(SkPaint::kFill_Style);
    p.setAntiAlias(true);
    canvas->drawCircle(r->extent.width * 0.5f, r->extent.height * 0.4f,
                       r->extent.height * 0.2f, p);

    p.setColor(SK_ColorMAGENTA);
    canvas->drawRect(SkRect::MakeXYWH(r->extent.width * 0.1f, r->extent.height * 0.7f,
                                      r->extent.width * 0.8f, r->extent.height * 0.1f), p);

    p.setColor(SK_ColorYELLOW);
    canvas->drawCircle(r->extent.width * 0.8f, r->extent.height * 0.25f,
                       r->extent.height * 0.08f, p);

    // Flush + submit Skia's recorded GPU work.
    r->grContext->flushAndSubmit(r->skSurfaces[imgIndex].get());
    r->grContext->submit();

    // Present.
    VkPresentInfoKHR pi{}; pi.sType=VK_STRUCTURE_TYPE_PRESENT_INFO_KHR;
    pi.swapchainCount=1; pi.pSwapchains=&r->swapchain; pi.pImageIndices=&imgIndex;
    r->pQueuePresent(r->queue, &pi);
}

} // extern "C"
