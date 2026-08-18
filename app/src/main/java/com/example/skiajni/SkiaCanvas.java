package com.example.skiajni;

/**
 * Reusable JNI wrapper around Skia 2D rendering.
 * Supports both raster (CPU) and Vulkan (GPU) backends.
 *
 * <h3>Raster (offscreen / software)</h3>
 * <pre>
 *   try (SkiaCanvas c = new SkiaCanvas(800, 600)) {
 *       c.drawRect(...);
 *       c.saveToFile("out.png");
 *   }
 * </pre>
 *
 * <h3>Vulkan (GPU via Android SurfaceView)</h3>
 * <pre>
 *   long vkCtx = SkiaCanvas.createVulkanContext(
 *       instance, physDev, device, queue, familyIdx, apiVer);
 *   long canvas = SkiaCanvas.createVulkanCanvas(
 *       vkCtx, vkImage, w, h, VK_FORMAT_R8G8B8A8_UNORM, ...);
 *   // draw...
 *   SkiaCanvas.submitVulkanCanvas(vkCtx, canvas);
 *   SkiaCanvas.destroyVulkanContext(vkCtx);
 * </pre>
 */
public class SkiaCanvas implements AutoCloseable {

    static { System.loadLibrary("skia_jni"); }

    private long handle;
    private final int width;
    private final int height;

    // ── Raster surface ──────────────────────────────────────────────

    public SkiaCanvas(int width, int height) {
        this.width = width;
        this.height = height;
        this.handle = nCreateRaster(width, height);
        if (this.handle == 0) throw new OutOfMemoryError("Skia surface alloc failed");
    }

    public int getWidth()  { return width; }
    public int getHeight() { return height; }

    // ── Drawing ─────────────────────────────────────────────────────

    public void clear(int color)                                          { check(); nClear(handle, color); }
    public void drawRect(float x,float y,float w,float h,int c,float s)  { check(); nDrawRect(handle,x,y,w,h,c,s); }
    public void drawCircle(float cx,float cy,float r,int c,float s)      { check(); nDrawCircle(handle,cx,cy,r,c,s); }
    public void drawLine(float x0,float y0,float x1,float y1,int c,float s){ check(); nDrawLine(handle,x0,y0,x1,y1,c,s); }
    public void drawText(String t,float x,float y,int c,float s)         { check(); nDrawText(handle,t,x,y,c,s); }
    public void fillRect(float x,float y,float w,float h,int c)          { check(); nFillRect(handle,x,y,w,h,c); }
    public void fillCircle(float cx,float cy,float r,int c)              { check(); nFillCircle(handle,cx,cy,r,c); }
    public void drawRoundRect(float x,float y,float w,float h,float rx,float ry,int c,float s) { check(); nDrawRoundRect(handle,x,y,w,h,rx,ry,c,s); }
    public void fillRoundRect(float x,float y,float w,float h,float rx,float ry,int c) { check(); nFillRoundRect(handle,x,y,w,h,rx,ry,c); }
    public void fillOval(float x,float y,float w,float h,int c)          { check(); nFillOval(handle,x,y,w,h,c); }
    public void drawGradient(float x0,float y0,float x1,float y1,int c0,int c1,int mode) { check(); nDrawGradient(handle,x0,y0,x1,y1,c0,c1,mode); }
    public boolean saveToFile(String path)                                { check(); return nSaveToFile(handle,path); }

    /** Returns raw RGBA pixels (w*h*4 bytes), or null on failure. */
    public byte[] getPixels()                                             { check(); return nGetPixels(handle); }

    // ── Vulkan context ──────────────────────────────────────────────

    /**
     * Create a Vulkan GPU context from raw Vulkan handles.
     * Caller must keep VkInstance/Device/Queue alive for the context lifetime.
     *
     * @param vkInstance        VkInstance handle
     * @param vkPhysicalDevice  VkPhysicalDevice handle
     * @param vkDevice          VkDevice handle
     * @param vkQueue           VkQueue handle (graphics queue)
     * @param queueFamilyIndex  graphics queue family index
     * @param maxApiVersion     Vulkan API version (e.g. VK_MAKE_VERSION(1,1,0))
     * @return context handle, or 0 on failure
     */
    public static native long createVulkanContext(
            long vkInstance, long vkPhysicalDevice, long vkDevice,
            long vkQueue, int queueFamilyIndex, int maxApiVersion);

    public static native void destroyVulkanContext(long ctx);

    /**
     * Create a GPU-backed canvas wrapping a VkImage (e.g. swapchain image).
     *
     * @param ctx         Vulkan context handle from {@link #createVulkanContext}
     * @param vkImage     VkImage handle
     * @param width       image width in pixels
     * @param height      image height in pixels
     * @param format      VkFormat (e.g. VK_FORMAT_R8G8B8A8_UNORM = 37)
     * @param usageFlags  VkImageUsageFlags (VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT = 16)
     * @return canvas handle for draw operations
     */
    public static native long createVulkanCanvas(
            long ctx, long vkImage, int width, int height,
            int format, int usageFlags);

    /**
     * Flush all pending draws and submit to the Vulkan queue.
     * Call after drawing, before presenting the swapchain image.
     */
    public static native void submitVulkanCanvas(long ctx, long canvas);

    // ── Lifecycle ───────────────────────────────────────────────────

    @Override
    public void close() {
        if (handle != 0) { nDestroy(handle); handle = 0; }
    }

    private void check() {
        if (handle == 0) throw new IllegalStateException("Canvas closed");
    }

    // ── Raster natives ──────────────────────────────────────────────
    private static native long   nCreateRaster(int w, int h);
    private static native void   nDestroy(long h);
    private static native void   nClear(long h, int c);
    private static native void   nDrawRect(long h,float x,float y,float w,float hh,int c,float s);
    private static native void   nDrawCircle(long h,float cx,float cy,float r,int c,float s);
    private static native void   nDrawLine(long h,float x0,float y0,float x1,float y1,int c,float s);
    private static native void   nDrawText(long h,String t,float x,float y,int c,float s);
    private static native void   nFillRect(long h,float x,float y,float w,float hh,int c);
    private static native void   nFillCircle(long h,float cx,float cy,float r,int c);
    private static native void   nDrawRoundRect(long h,float x,float y,float w,float hh,float rx,float ry,int c,float s);
    private static native void   nFillRoundRect(long h,float x,float y,float w,float hh,float rx,float ry,int c);
    private static native void   nFillOval(long h,float x,float y,float w,float hh,int c);
    private static native void   nDrawGradient(long h,float x0,float y0,float x1,float y1,int c0,int c1,int mode);
    private static native boolean nSaveToFile(long h,String path);
    private static native byte[] nGetPixels(long h);
}
