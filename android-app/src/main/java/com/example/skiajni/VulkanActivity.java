package com.example.skiajni;

import android.app.Activity;
import android.os.Bundle;
import android.view.WindowManager;

/**
 * Fullscreen activity that renders via Skia's Vulkan backend.
 * Uses a SurfaceView whose native window is the Vulkan swapchain target.
 */
public class VulkanActivity extends Activity {

    private VulkanSurfaceView view;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        view = new VulkanSurfaceView(this);
        setContentView(view);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (view != null) view.stopRendering();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (view != null && view.getHolder().getSurface().isValid()) {
            view.startRendering();
        }
    }
}
