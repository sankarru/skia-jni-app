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
 * Each card is pre-rendered once into its own cached bitmap, so loading a
 * new image only re-renders that one card (no full-feed rebuild). Scrolling
 * composes visible cards via hardware-accelerated blits — zero jank.
 */
public class ImageGalleryActivity extends Activity {

    private int W, H;              // display size
    private int RW, RH;            // internal render size (reduced)
    private float scale;
    private static final int PAGE_SIZE = 5;
    private float CARD_H;

    private static class Item {
        long imageHandle = 0;
        byte[] bytes = null;
        boolean loading = false;

        // This card's pre-rendered bitmap (re-created only when its image loads)
        SkiaCanvas cardCanvas = null;
        Bitmap cardBitmap = null;
    }

    private final List<Item> items = new ArrayList<>();

    private ImageView imageView;
    private TextView status;

    private Bitmap viewBitmap;
    private android.graphics.Canvas viewCanvas;

    // Scroll + fling
    private float scrollY = 0;
    private float velocity = 0;
    private float lastTouchY = 0;
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

        scale = W / 720f;
        RW = 720;
        RH = (int) (H / scale);
        CARD_H = 320f;

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
        showViewport(); // compose visible cached cards
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
        if (loading) return;
        float contentH = items.size() * CARD_H;
        if (scrollY + RH > contentH - RH * 0.5f) loadNextPage();
    }

    private void loadNextPage() {
        if (loading) return;
        loading = true;
        int start = items.size();
        for (int i = 0; i < PAGE_SIZE; i++) {
            Item it = new Item();
            items.add(it);
            it.loading = true;
            renderCard(it, false, start + i); // placeholder immediately (cheap)
        }
        for (int i = 0; i < PAGE_SIZE; i++) {
            final int idx = start + i;
            int seed = 100 + loadedPages * PAGE_SIZE + i;
            final String url = "https://picsum.photos/seed/g" + seed + "/600/400";
            pendingFetches++;
            ImageLoader.fetch(url, bytes -> {
                Item it = items.get(idx);
                it.bytes = bytes;
                it.loading = false;
                pendingFetches--;
                if (pendingFetches == 0) loading = false;
                runOnUiThread(() -> {
                    renderCard(it, true, idx); // re-render ONLY this card
                    updateStatus();
                });
            });
        }
        loadedPages++;
    }

    // Render a single card into its own cached canvas/bitmap.
    // Replaces the card's bitmap only (cheap, no full-feed rebuild).
    private void renderCard(Item it, boolean withImage, int index) {
        if (withImage && it.bytes != null) {
            if (it.cardCanvas != null) it.cardCanvas.close();
            it.cardCanvas = new SkiaCanvas(RW, (int) CARD_H);
            if (it.imageHandle != 0) it.cardCanvas.destroyImage(it.imageHandle);
            it.imageHandle = it.cardCanvas.createImage(it.bytes);
            it.bytes = null;
            drawCard(it.cardCanvas, it, index);
        } else {
            if (it.cardCanvas != null) it.cardCanvas.close();
            it.cardCanvas = new SkiaCanvas(RW, (int) CARD_H);
            drawCard(it.cardCanvas, it, index);
        }

        byte[] px = it.cardCanvas.getPixels();
        if (px != null) {
            if (it.cardBitmap != null) it.cardBitmap.recycle();
            it.cardBitmap = Bitmap.createBitmap(RW, (int) CARD_H, Bitmap.Config.ARGB_8888);
            it.cardBitmap.copyPixelsFromBuffer(ByteBuffer.wrap(px));
        }
    }

    // Compose the viewport from cached card bitmaps (hardware-accelerated).
    private void showViewport() {
        if (viewBitmap == null) {
            viewBitmap = Bitmap.createBitmap(RW, RH, Bitmap.Config.ARGB_8888);
            viewCanvas = new android.graphics.Canvas(viewBitmap);
        }
        viewCanvas.drawColor(0xFF101014);

        int first = (int) (scrollY / CARD_H);
        int last = (int) ((scrollY + RH) / CARD_H) + 1;
        if (first < 0) first = 0;
        if (last >= items.size()) last = items.size() - 1;

        for (int i = first; i <= last; i++) {
            Item it = items.get(i);
            if (it.cardBitmap == null) continue;
            float cardY = i * CARD_H - scrollY;
            int srcY = 0, srcH = it.cardBitmap.getHeight();
            int dstY = (int) cardY;
            int dstH = (int) CARD_H;
            // Crop if the card overflows the viewport
            if (dstY < 0) { srcY = -dstY; srcH = dstH + dstY; dstY = 0; }
            if (dstY + dstH > RH) dstH = RH - dstY;
            if (srcH <= 0 || dstH <= 0) continue;
            android.graphics.Rect src = new android.graphics.Rect(0, srcY, RW, srcY + srcH);
            android.graphics.Rect dst = new android.graphics.Rect(0, dstY, RW, dstY + dstH);
            viewCanvas.drawBitmap(it.cardBitmap, src, dst, null);
        }

        // bottom loader
        float contentH = items.size() * CARD_H;
        if (scrollY + RH > contentH - RH * 0.3f) {
            android.graphics.Paint p = new android.graphics.Paint();
            p.setColor(Color.WHITE); p.setTextSize(22); p.setAntiAlias(true);
            viewCanvas.drawText("loading more...", RW / 2 - 100, RH - 30, p);
        }

        imageView.setImageBitmap(viewBitmap);
    }

    private void drawCard(SkiaCanvas c, Item it, int index) {
        float margin = RW * 0.04f;
        float cardW = RW - margin * 2;
        float cardH = CARD_H;

        c.fillRoundRect(margin, 6, cardW, cardH - 12, 12, 12, 0xFF1E1E24);
        c.drawRoundRect(margin, 6, cardW, cardH - 12, 12, 12, 0xFF33333C, 2);

        float imgX = margin + 8;
        float imgY = 14;
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
                imgX, cardH - 18, 0xFFCCCCCC, 20);
    }

    private void updateStatus() {
        int loaded = 0;
        for (Item it : items) if (it.imageHandle != 0) loaded++;
        status.setText("Feed · " + items.size() + " items · " + loaded + " images");
    }

    @Override
    protected void onPause() {
        super.onPause();
        for (Item it : items) {
            if (it.cardCanvas != null) { it.cardCanvas.close(); it.cardCanvas = null; }
        }
    }
}
