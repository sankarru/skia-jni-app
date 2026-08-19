package com.example.skiajni;

import android.app.Activity;
import android.os.Bundle;
import android.view.WindowManager;

/**
 * Fullscreen activity that renders via Skia's Vulkan GPU backend.
 * Renders offscreen on the GPU and displays via ImageView (reliable on
 * emulators where direct swapchain-present can deadlock).
 */
public class VulkanActivity extends Activity {

    private VulkanSurfaceView view;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        view = new VulkanSurfaceView(this);
        view.init(dm.widthPixels, dm.heightPixels);
        setContentView(view);
        view.startRendering();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (view != null) view.stopRendering();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (view != null) view.startRendering();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (view != null) view.stopRendering();
    }
}
