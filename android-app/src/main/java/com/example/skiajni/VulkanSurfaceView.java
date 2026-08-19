package com.example.skiajni;

import android.content.Context;
import android.util.AttributeSet;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

/**
 * A SurfaceView that renders via Skia's Vulkan backend.
 * Manages the Vulkan swapchain native to an ANativeWindow and renders
 * each frame on the GPU, presenting to the screen.
 */
public class VulkanSurfaceView extends SurfaceView implements SurfaceHolder.Callback {

    private long nativeHandle = 0;
    private boolean rendering = false;
    private Thread renderThread;
    private int width = 1080;
    private int height = 1920;

    static {
        System.loadLibrary("skia_jni");
    }

    public VulkanSurfaceView(Context context) {
        super(context);
        getHolder().addCallback(this);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        // size set in surfaceChanged
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
        width = w;
        height = h;
        if (nativeHandle == 0) {
            nativeHandle = nCreate(holder.getSurface(), w, h);
            android.util.Log.d("SkiaVk", "surfaceCreated native=" + nativeHandle + " " + w + "x" + h);
        }
        if (nativeHandle != 0) {
            startRendering();
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        stopRendering();
        if (nativeHandle != 0) {
            nDestroy(nativeHandle);
            nativeHandle = 0;
        }
    }

    public void startRendering() {
        if (rendering) return;
        rendering = true;
        renderThread = new Thread(() -> {
            while (rendering && nativeHandle != 0) {
                try {
                    nRender(nativeHandle);
                } catch (Throwable t) {
                    android.util.Log.d("SkiaVk", "render error: " + t.getMessage());
                }
            }
        }, "skia-vulkan-render");
        renderThread.start();
    }

    public void stopRendering() {
        rendering = false;
        if (renderThread != null) {
            try { renderThread.join(200); } catch (InterruptedException ignored) {}
            renderThread = null;
        }
    }

    // ── native methods ──────────────────────────────────────────────
    private static native long nCreate(android.view.Surface surface, int width, int height);
    private static native void nRender(long handle);
    private static native void nDestroy(long handle);
}
