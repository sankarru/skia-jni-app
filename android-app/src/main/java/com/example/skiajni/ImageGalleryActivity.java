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
 * Pre-renders the feed into a tall offscreen canvas at reduced resolution
 * (cheap rebuild), then scrolls by hardware-accelerated bitmap cropping
 * (zero per-frame Skia work) with vsync fling physics.
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
    }

    private final List<Item> items = new ArrayList<>();

    private ImageView imageView;
    private TextView status;

    // Offscreen pre-rendered feed (at reduced resolution)
    private SkiaCanvas contentCanvas;
    private Bitmap contentBitmap;
    private int contentHeight = 0;

    // Viewport crop (hardware-accelerated)
    private Bitmap viewBitmap;
    private android.graphics.Canvas viewCanvas;

    // Scroll + fling
    private float scrollY = 0;
    private float velocity = 0;
    private float lastTouchY = 0;
    private boolean dragging = false;
    private long lastFrameNs = 0;
    private boolean needsRebuild = true;

    // Loading
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

        // Render at 720 wide for cheap rebuilds
        scale = W / 720f;
        RW = 720;
        RH = (int) (H / scale);
        CARD_H = 320f; // card height in render space

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
        if (needsRebuild) rebuildContent();
        showViewport(); // cheap: hardware-accelerated crop
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
                runOnUiThread(() -> needsRebuild = true);
            });
        }
        loadedPages++;
        needsRebuild = true;
    }

    // Rebuild the tall offscreen feed (only when content changes, at low res).
    private void rebuildContent() {
        needsRebuild = false;
        int newHeight = (int) (items.size() * CARD_H);
        if (contentHeight < newHeight) contentHeight = newHeight;

        if (contentCanvas != null) contentCanvas.close();
        contentCanvas = new SkiaCanvas(RW, contentHeight);

        for (Item it : items) {
            if (it.bytes != null) {
                if (it.imageHandle != 0) contentCanvas.destroyImage(it.imageHandle);
                it.imageHandle = contentCanvas.createImage(it.bytes);
                it.bytes = null;
            }
        }

        drawFeed(contentCanvas);
        byte[] px = contentCanvas.getPixels();
        if (px != null) {
            if (contentBitmap != null) contentBitmap.recycle();
            contentBitmap = Bitmap.createBitmap(RW, contentHeight, Bitmap.Config.ARGB_8888);
            contentBitmap.copyPixelsFromBuffer(ByteBuffer.wrap(px));
        }
        updateStatus();
    }

    // Cheap, hardware-accelerated crop of the cached content bitmap.
    private void showViewport() {
        if (contentBitmap == null) return;
        int y = (int) scrollY;
        if (y + RH > contentHeight) y = Math.max(0, contentHeight - RH);
        if (y < 0) y = 0;

        if (viewBitmap == null) {
            viewBitmap = Bitmap.createBitmap(RW, RH, Bitmap.Config.ARGB_8888);
            viewCanvas = new android.graphics.Canvas(viewBitmap);
        }
        viewCanvas.drawColor(0xFF101014);
        android.graphics.Rect src = new android.graphics.Rect(0, y, RW, y + RH);
        android.graphics.Rect dst = new android.graphics.Rect(0, 0, RW, RH);
        viewCanvas.drawBitmap(contentBitmap, src, dst, null);
        imageView.setImageBitmap(viewBitmap);
    }

    private void drawFeed(SkiaCanvas c) {
        c.clear(0xFF101014);
        for (int i = 0; i < items.size(); i++) {
            drawCard(c, items.get(i), i, i * CARD_H);
        }
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
        if (contentCanvas != null) { contentCanvas.close(); contentCanvas = null; }
    }
}
