package com.example.skiajni;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
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
 * Pre-renders the feed into a tall offscreen canvas and scrolls by cropping,
 * so scrolling is fast (no per-frame re-render of the whole feed).
 */
public class ImageGalleryActivity extends Activity {

    private int W, H;
    private static final float CARD_H = 460f;
    private static final int PAGE_SIZE = 3;

    private static class Item {
        long imageHandle = 0;
        byte[] bytes = null;
        boolean loading = false;
    }

    private final List<Item> items = new ArrayList<>();
    private final List<Float> itemY = new ArrayList<>(); // top Y of each card in content space

    private ImageView imageView;
    private TextView status;

    // Offscreen content canvas (the whole feed) + its bitmap
    private SkiaCanvas contentCanvas;
    private Bitmap contentBitmap;
    private int contentHeight = 0;

    // Viewport bitmap shown on screen
    private Bitmap viewBitmap;

    private float scrollY = 0;
    private float lastY = 0;
    private long lastFrameNs = 0;
    private int loadedPages = 0;
    private boolean loading = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        W = dm.widthPixels;
        H = dm.heightPixels;

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
    }

    private void handleTouch(MotionEvent ev) {
        switch (ev.getAction()) {
            case MotionEvent.ACTION_DOWN:
                lastY = ev.getY();
                break;
            case MotionEvent.ACTION_MOVE:
                scrollY -= (ev.getY() - lastY);
                lastY = ev.getY();
                if (scrollY < 0) scrollY = 0;
                float maxScroll = Math.max(0, contentHeight - H);
                if (scrollY > maxScroll) scrollY = maxScroll;
                showViewport();
                maybeLoadMore();
                break;
        }
    }

    private void loadNextPage() {
        if (loading) return;
        loading = true;
        int start = items.size();
        for (int i = 0; i < PAGE_SIZE; i++) {
            items.add(new Item());
            itemY.add(start * CARD_H + i * CARD_H);
        }
        for (int i = 0; i < PAGE_SIZE; i++) {
            final int idx = start + i;
            int seed = 100 + loadedPages * PAGE_SIZE + i;
            final String url = "https://picsum.photos/seed/g" + seed + "/600/400";
            items.get(idx).loading = true;
            ImageLoader.fetch(url, bytes -> {
                items.get(idx).bytes = bytes;
                items.get(idx).loading = false;
                runOnUiThread(this::scheduleRebuild);
            });
        }
        loadedPages++;
        rebuildContent();
    }

    private void maybeLoadMore() {
        if (scrollY + H > contentHeight - H * 0.5f) {
            loadNextPage();
        }
    }

    private void scheduleRebuild() {
        long now = System.nanoTime();
        if (now - lastFrameNs > 16_666_667L) {
            lastFrameNs = now;
            rebuildContent();
        } else {
            rebuildContent();
        }
    }

    // Rebuild the tall offscreen feed (called when content changes or new items added).
    private void rebuildContent() {
        int newHeight = (int) (items.size() * CARD_H);
        if (contentHeight < newHeight) contentHeight = newHeight;

        if (contentCanvas != null) contentCanvas.close();
        contentCanvas = new SkiaCanvas(W, contentHeight);

        // Decode pending image bytes.
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
            contentBitmap = Bitmap.createBitmap(W, contentHeight, Bitmap.Config.ARGB_8888);
            contentBitmap.copyPixelsFromBuffer(ByteBuffer.wrap(px));
        }

        int loaded = 0;
        for (Item it : items) if (it.imageHandle != 0) loaded++;
        status.setText("Feed · " + items.size() + " items · " + loaded + " images · scroll to load more");

        showViewport();
    }

    // Fast: crop the visible window from the cached content bitmap.
    private void showViewport() {
        if (contentBitmap == null || W == 0 || H == 0) return;
        int y = Math.max(0, Math.min((int) scrollY, contentHeight - H));
        if (y + H > contentHeight) y = Math.max(0, contentHeight - H);
        if (viewBitmap == null) viewBitmap = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas cv = new android.graphics.Canvas(viewBitmap);
        cv.drawColor(0xFF101014);
        android.graphics.Rect src = new android.graphics.Rect(0, y, W, y + H);
        android.graphics.Rect dst = new android.graphics.Rect(0, 0, W, H);
        cv.drawBitmap(contentBitmap, src, dst, null);
        imageView.setImageBitmap(viewBitmap);
    }

    private void drawFeed(SkiaCanvas c) {
        c.clear(0xFF101014);
        for (int i = 0; i < items.size(); i++) {
            drawCard(c, items.get(i), i, itemY.get(i));
        }
    }

    private void drawCard(SkiaCanvas c, Item it, int index, float y) {
        float margin = W * 0.04f;
        float cardW = W - margin * 2;
        float cardH = CARD_H;

        c.fillRoundRect(margin, y + 8, cardW, cardH - 16, 16, 16, 0xFF1E1E24);
        c.drawRoundRect(margin, y + 8, cardW, cardH - 16, 16, 16, 0xFF33333C, 2);

        float imgX = margin + 12;
        float imgY = y + 20;
        float imgW = cardW - 24;
        float imgH = cardH - 16 - 60;

        if (it.imageHandle != 0) {
            c.drawImage(it.imageHandle, imgX, imgY, imgW, imgH, 1.0f);
        } else {
            c.fillRoundRect(imgX, imgY, imgW, imgH, 12, 12, 0xFF2A2A33);
            c.drawText(it.loading ? "loading..." : "no image",
                    imgX + imgW / 2 - 60, imgY + imgH / 2, 0xFF888899, 30);
        }

        String cap = "Image #" + (index + 1) + "  ·  picsum.photos";
        c.drawText(cap, imgX, y + cardH - 24, 0xFFCCCCCC, 26);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (contentCanvas != null) { contentCanvas.close(); contentCanvas = null; }
    }
}
