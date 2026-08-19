package com.example.skiajni;

import android.content.Context;
import android.graphics.Bitmap;
import android.view.Surface;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.nio.ByteBuffer;

/**
 * Renders via Skia's Vulkan GPU backend into an offscreen surface,
 * reads pixels back to CPU, and displays them. Reliable on emulators
 * where direct swapchain-present can deadlock.
 */
public class VulkanSurfaceView extends FrameLayout {

    private long nativeHandle = 0;
    private boolean rendering = false;
    private Thread renderThread;
    private int width = 1080;
    private int height = 1920;
    private ImageView imageView;
    private Bitmap bitmap;

    static {
        System.loadLibrary("skia_jni");
    }

    public VulkanSurfaceView(Context context) {
        super(context);
        setBackgroundColor(0xFF000000);
        imageView = new ImageView(context);
        imageView.setScaleType(ImageView.ScaleType.FIT_XY);
        addView(imageView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
    }

    public void init(int w, int h) {
        width = w;
        height = h;
        if (nativeHandle == 0) {
            nativeHandle = nCreate(w, h);
            android.util.Log.d("SkiaVk", "init native=" + nativeHandle + " " + w + "x" + h);
        }
    }

    public void startRendering() {
        if (rendering || nativeHandle == 0) return;
        rendering = true;
        renderThread = new Thread(() -> {
            long last = System.nanoTime();
            while (rendering && nativeHandle != 0) {
                try {
                    byte[] px = nRender(nativeHandle);
                    if (px != null && width > 0 && height > 0) {
                        if (bitmap == null) {
                            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                        }
                        bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(px));
                        post(() -> imageView.setImageBitmap(bitmap));
                    }
                } catch (Throwable t) {
                    android.util.Log.d("SkiaVk", "render error: " + t.getMessage());
                }
                long now = System.nanoTime();
                long frameTime = 16_666_667L;
                long sleep = frameTime - (now - last);
                if (sleep > 0) {
                    try { Thread.sleep(sleep / 1_000_000L); } catch (InterruptedException ignored) {}
                }
                last = System.nanoTime();
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
        if (nativeHandle != 0) {
            nDestroy(nativeHandle);
            nativeHandle = 0;
        }
    }

    // ── native methods ──────────────────────────────────────────────
    private static native long nCreate(int width, int height);
    private static native byte[] nRender(long handle);
    private static native void nDestroy(long handle);
}
