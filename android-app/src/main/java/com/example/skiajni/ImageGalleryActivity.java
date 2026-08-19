package com.example.skiajni;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Smooth infinite-scroll online image viewer rendered via Skia.
 * Renders only the visible cards each frame at a reduced internal
 * resolution (fast CPU readback), then upscales — Compose-like smoothness.
 */
public class ImageGalleryActivity extends Activity {

    private int W, H;              // display size
    private int RW, RH;            // internal render size (reduced)
    private float scale;           // internal -> display scale
    private static final float CARD_H_R = 300f; // card height in render space
    private static final int PAGE_SIZE = 5;

    private static class Item {
        long imageHandle = 0;
        byte[] bytes = null;
        boolean loading = false;
        int srcW = 0, srcH = 0;
    }

    private final List<Item> items = new ArrayList<>();

    private ImageView imageView;
    private TextView status;

    private SkiaCanvas canvas;
    private Bitmap renderBitmap;

    // Scroll state (in render space)
    private float scrollY = 0;
    private float velocity = 0;
    private float lastTouchDY = 0;
    private boolean dragging = false;
    private long lastFrameNs = 0;

    private boolean loading = false;
    private int pendingFetches = 0;
    private int loadedPages = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        W = dm.widthPixels;
        H = dm.heightPixels;

        // Internal render resolution ~ 720 wide for fast readback
        scale = W / 720f;
        RW = 720;
        RH = (int) (H / scale);
        CARD_H = CARD_H_R * scale;

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        imageView = new ImageView(this);
        imageView.setScaleType(ImageView.ScaleType.FIT_XY);
        root.addView(imageView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        status = new TextView(this);
        status.setTextColor(Color.argb(200, 255, 255, 255));
        status.setTextSize(13);
        status.setShadowLayer(3, 1, 1, Color.BLACK);
        status.setPadding(16, 28, 16, 8);
        root.addView(status, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.LEFT));

        setContentView(root);
        imageView.setOnTouchListener((v, ev) -> { handleTouch(ev); return true; });

        loadNextPage();
        startRenderLoop();
    }

    private float CARD_H;

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
                velocity *= 0.9f;
                if (Math.abs(velocity) < 0.4f) velocity = 0;
            }
            clampScroll();
            maybeLoadMore();
        }
        lastFrameNs = frameTimeNanos;
        renderVisible();
    }

    private void clampScroll() {
        float contentH = items.size() * CARD_H;
        if (scrollY < 0) { scrollY = 0; velocity = 0; }
        float max = Math.max(0, contentH - RH);
        if (scrollY > max) { scrollY = max; velocity = 0; }
    }

    private void handleTouch(MotionEvent ev) {
        switch (ev.getAction()) {
            case MotionEvent.ACTION_DOWN:
                dragging = true; velocity = 0;
                lastTouchDY = ev.getY() / scale;
                break;
            case MotionEvent.ACTION_MOVE:
                float dy = ev.getY() / scale - lastTouchDY;
                lastTouchDY = ev.getY() / scale;
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
        if (loading) return;
        float contentH = items.size() * CARD_H;
        if (scrollY + RH > contentH - RH * 0.5f) loadNextPage();
    }

    private void loadNextPage() {
        if (loading) return;
        loading = true;
        int start = items.size();
        for (int i = 0; i < PAGE_SIZE; i++) items.add(new Item());
        for (int i = 0; i < PAGE_SIZE; i++) {
            final int idx = start + i;
            int seed = 100 + loadedPages * PAGE_SIZE + i;
            final String url = "https://picsum.photos/seed/g" + seed + "/600/400";
            items.get(idx).loading = true;
            pendingFetches++;
            ImageLoader.fetch(url, bytes -> {
                items.get(idx).bytes = bytes;
                items.get(idx).loading = false;
                pendingFetches--;
                if (pendingFetches == 0) loading = false;
            });
        }
        loadedPages++;
    }

    // Decode pending bytes into Skia image handles (reuses the same canvas).
    private void decodePending(SkiaCanvas c) {
        for (Item it : items) {
            if (it.bytes != null) {
                if (it.imageHandle != 0) c.destroyImage(it.imageHandle);
                it.imageHandle = c.createImage(it.bytes);
                it.srcW = c.imageWidth(it.imageHandle);
                it.srcH = c.imageHeight(it.imageHandle);
                it.bytes = null;
            }
        }
        updateStatus();
    }

    // Render ONLY visible cards each frame at reduced resolution.
    private void renderVisible() {
        try {
            if (canvas == null) canvas = new SkiaCanvas(RW, RH);
            decodePending(canvas);

            canvas.clear(0xFF101014);
            int first = (int) (scrollY / CARD_H);
            int last = (int) ((scrollY + RH) / CARD_H) + 1;
            if (first < 0) first = 0;
            if (last >= items.size()) last = items.size() - 1;

            for (int i = first; i <= last; i++) {
                float y = i * CARD_H - scrollY;
                drawCard(canvas, items.get(i), i, y);
            }

            // bottom loader
            float contentH = items.size() * CARD_H;
            if (scrollY + RH > contentH - RH * 0.3f) {
                canvas.drawText("loading more...", RW / 2 - 100, RH - 40, 0xFFFFFFFF, 24);
            }

            byte[] px = canvas.getPixels();
            if (px != null) {
                if (renderBitmap == null) renderBitmap = Bitmap.createBitmap(RW, RH, Bitmap.Config.ARGB_8888);
                renderBitmap.copyPixelsFromBuffer(ByteBuffer.wrap(px));
                imageView.setImageBitmap(renderBitmap);
            }
        } catch (Throwable ignored) {}
    }

    private void drawCard(SkiaCanvas c, Item it, int index, float y) {
        float margin = RW * 0.04f;
        float cardW = RW - margin * 2;
        float cardH = CARD_H;

        c.fillRoundRect(margin, y + 6, cardW, cardH - 12, 12, 12, 0xFF1E1E24);
        c.drawRoundRect(margin, y + 6, cardW, cardH - 12, 12, 12, 0xFF33333C, 2);

        float imgX = margin + 8;
        float imgY = y + 14;
        float imgW = cardW - 16;
        float imgH = cardH - 12 - 40;

        if (it.imageHandle != 0) {
            c.drawImage(it.imageHandle, imgX, imgY, imgW, imgH, 1.0f);
        } else {
            c.fillRoundRect(imgX, imgY, imgW, imgH, 10, 10, 0xFF2A2A33);
            c.drawText(it.loading ? "loading..." : "no image",
                    imgX + imgW / 2 - 50, imgY + imgH / 2, 0xFF888899, 22);
        }

        c.drawText("Image #" + (index + 1) + "  ·  picsum.photos",
                imgX, y + cardH - 18, 0xFFCCCCCC, 20);
    }

    private void updateStatus() {
        int loaded = 0;
        for (Item it : items) if (it.imageHandle != 0) loaded++;
        status.setText("Feed · " + items.size() + " items · " + loaded + " images");
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (canvas != null) { canvas.close(); canvas = null; }
    }
}
