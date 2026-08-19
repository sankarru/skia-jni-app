package com.example.skiajni;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.nio.ByteBuffer;

/**
 * Infinite image scroller rendered via Hermes + Yoga + Skia (React Native style).
 * Touch/scroll/fling managed in Java; all drawing done in JS.
 */
public class RnGalleryActivity extends Activity {

    private int W, H;
    private float scale;
    private int loadedPages = 0;
    private static final int PAGE_SIZE = 5;

    private ImageView imageView;
    private TextView status;

    private Bitmap viewBitmap;
    private android.graphics.Canvas viewCanvas;

    private JsCanvas jsCanvas;
    private SkiaCanvas skiaCanvas;

    // Scroll + fling state
    private float scrollY = 0;
    private float velocity = 0;
    private float lastTouchY = 0;
    private boolean dragging = false;
    private long lastFrameNs = 0;
    private int pendingFetches = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        configureEdgeToEdge();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        android.util.DisplayMetrics real = new android.util.DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(real);
        W = real.widthPixels;
        H = real.heightPixels;
        scale = W / 720f;

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        imageView = new ImageView(this);
        imageView.setScaleType(ImageView.ScaleType.FIT_XY);
        root.addView(imageView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        status = new TextView(this);
        status.setTextColor(Color.argb(200, 255, 255, 255));
        status.setTextSize(11);
        status.setShadowLayer(3, 1, 1, Color.BLACK);
        status.setPadding(16, 28, 16, 8);
        root.addView(status, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.LEFT));
        setContentView(root);

        configureEdgeToEdge();
        root.post(new Runnable() {
            @Override public void run() {
                imageView.setOnTouchListener(new View.OnTouchListener() {
                    @Override public boolean onTouch(View v, MotionEvent ev) {
                        handleTouch(ev); return true;
                    }
                });

                // Init Hermes + Skia
                skiaCanvas = new SkiaCanvas(W, H);
                jsCanvas = new JsCanvas(W, H);
                jsCanvas.setCanvas(skiaCanvas);

                String runtime = loadAsset("rn_runtime.js");
                jsCanvas.eval(runtime);

                String gallery = loadAsset("rn_gallery.js");
                jsCanvas.eval(gallery);

                jsCanvas.eval("galleryInit(_handle, " + W + ", " + H + ")");

                loadNextPage();
                startRenderLoop();
            }
        });
    }

    private void configureEdgeToEdge() {
        getWindow().setDecorFitsSystemWindows(false);
        getWindow().setStatusBarColor(0x00000000);
        getWindow().setNavigationBarColor(0x00000000);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            android.view.WindowInsetsController ctrl = getWindow().getInsetsController();
            if (ctrl != null) {
                ctrl.hide(android.view.WindowInsets.Type.systemBars());
                ctrl.setSystemBarsBehavior(
                    android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    private void startRenderLoop() {
        Choreographer.getInstance().postFrameCallback(new Choreographer.FrameCallback() {
            @Override public void doFrame(long t) {
                tick(t);
                Choreographer.getInstance().postFrameCallback(this);
            }
        });
    }

    private void tick(long frameTimeNanos) {
        float dt = (frameTimeNanos - lastFrameNs) / 1_000_000_000f;
        if (dt > 0 && dt < 0.1f) {
            if (!dragging) {
                scrollY += velocity;
                velocity *= 0.92f;
                if (Math.abs(velocity) < 0.3f) velocity = 0;
            }
            clampScroll();
            maybeLoadMore();
        }
        lastFrameNs = frameTimeNanos;

        // Pump JS timers
        long nowMs = System.currentTimeMillis();
        JsCanvas.nPumpTimers(jsCanvas.getHandle(), nowMs);

        // Draw scene via JS
        jsCanvas.eval("render(_handle," + scrollY + ")");
        byte[] px = skiaCanvas.getPixels();
        if (px != null) {
            showViewport(px);
        }
        updateStatus();
    }

    private void clampScroll() {
        float contentH = getGalleryContentHeight();
        if (scrollY < 0) { scrollY = 0; velocity = 0; }
        float max = Math.max(0, contentH - H);
        if (scrollY > max) { scrollY = max; velocity = 0; }
    }

    private void handleTouch(MotionEvent ev) {
        switch (ev.getAction()) {
            case MotionEvent.ACTION_DOWN:
                dragging = true; velocity = 0; lastTouchY = ev.getY() / scale;
                break;
            case MotionEvent.ACTION_MOVE:
                float dy = ev.getY() / scale - lastTouchY;
                lastTouchY = ev.getY() / scale;
                scrollY -= dy;
                velocity = -dy;
                clampScroll();
                maybeLoadMore();
                break;
            case MotionEvent.ACTION_UP:
                dragging = false;
                break;
        }
    }

    private void maybeLoadMore() {
        float contentH = getGalleryContentHeight();
        if (scrollY + H > contentH - H * 0.5f) loadNextPage();
    }

    private float getGalleryContentHeight() {
        try {
            return Float.parseFloat(jsCanvas.eval("''+galleryGetCount()*galleryGetCardHeight()").trim());
        } catch (Exception e) { return 0; }
    }

    private int getGalleryCount() {
        try {
            return (int) Float.parseFloat(jsCanvas.eval("''+galleryGetCount()").trim());
        } catch (Exception e) { return 0; }
    }

    private void loadNextPage() {
        int start = getGalleryCount();
        for (int i = 0; i < PAGE_SIZE; i++) {
            int idx = start + i;
            int seed = 100 + loadedPages * PAGE_SIZE + i;
            String url = "https://picsum.photos/seed/g" + seed + "/600/400";
            String label = "Image #" + (idx + 1) + "  \u00b7  picsum.photos";
            jsCanvas.eval("galleryLoadImage(" + idx + ",'" + url + "','" + label + "')");
        }
        loadedPages++;
    }

    private void showViewport(byte[] px) {
        if (viewBitmap == null) {
            viewBitmap = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
            viewCanvas = new android.graphics.Canvas(viewBitmap);
        }
        viewBitmap.copyPixelsFromBuffer(ByteBuffer.wrap(px));
        imageView.setImageBitmap(viewBitmap);
    }

    private void updateStatus() {
        status.setText("Gallery | " + W + "x" + H + " | " + getGalleryCount() + " items | scroll=" + (int) scrollY);
    }

    private String loadAsset(String name) {
        try (java.io.InputStream is = getAssets().open(name)) {
            byte[] buf = new byte[is.available()];
            is.read(buf);
            return new String(buf, "UTF-8");
        } catch (Exception e) {
            return "// error loading " + name + ": " + e;
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (jsCanvas != null) jsCanvas.close();
        if (skiaCanvas != null) skiaCanvas.close();
        jsCanvas = null;
        skiaCanvas = null;
    }
}
