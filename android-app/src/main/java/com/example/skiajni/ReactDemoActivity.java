package com.example.skiajni;

import android.app.Activity;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Good Vibes — a wellness dashboard rendered via Hermes + Skia + Yoga. */
public class ReactDemoActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        final int W = dm.widthPixels;
        final int H = dm.heightPixels;

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFFF8FAFC);
        ImageView imageView = new ImageView(this);
        imageView.setScaleType(ImageView.ScaleType.FIT_XY);
        root.addView(imageView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        TextView status = new TextView(this);
        status.setTextColor(0x99FFFFFF);
        status.setTextSize(11);
        status.setShadowLayer(3, 1, 1, 0xFF000000);
        status.setPadding(12, 20, 12, 8);
        root.addView(status, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.LEFT));
        setContentView(root);

        int[] top = {0};
        int[] bottom = {0};
        root.post(new Runnable() {
            @Override public void run() {
                WindowInsets insets = root.getRootWindowInsets();
                if (insets != null) {
                    int topInset = Math.max(
                            insets.getSystemWindowInsetTop(),
                            insets.getDisplayCutout() != null
                                    ? insets.getDisplayCutout().getSafeInsetTop() : 0);
                    int bottomInset = Math.max(
                            insets.getSystemWindowInsetBottom(),
                            insets.getDisplayCutout() != null
                                    ? insets.getDisplayCutout().getSafeInsetBottom() : 0);
                    top[0] = topInset;
                    bottom[0] = bottomInset;
                }
                render(W, H, top[0], bottom[0], imageView, status);
            }
        });
    }

    /** Convert Skia pixel bytes (RGBA) into an Android ARGB_8888 bitmap.
     *  Swaps the red and blue channels so colors render correctly. */
    private static Bitmap toBitmap(int w, int h, byte[] px) {
        byte[] sw = new byte[px.length];
        for (int i = 0; i < px.length; i += 4) {
            sw[i]     = px[i + 2];
            sw[i + 1] = px[i + 1];
            sw[i + 2] = px[i];
            sw[i + 3] = px[i + 3];
        }
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        bmp.copyPixelsFromBuffer(ByteBuffer.wrap(sw).order(ByteOrder.LITTLE_ENDIAN));
        return bmp;
    }

    private void render(int W, int H, int topInset, int bottomInset,
                        ImageView imageView, TextView status) {
        try {
            long t0 = System.nanoTime();
            String runtime = loadAsset("rn_runtime.js");

            try (SkiaCanvas canvas = new SkiaCanvas(W, H);
                 JsCanvas js = new JsCanvas(W, H)) {

                js.eval(runtime);

                int contentH = H - topInset - bottomInset;

                // ── Scene definition ──────────────────────────────
                String jsCode =
                    "clear(_handle, 0xFF000000);" +
                    "fillCircle(_handle, 540, 1200, 300, 0xFF00FF00);" +
                    "'ok'";

js.setCanvas(canvas);
                String result = js.eval(jsCode);

                long dt = (System.nanoTime() - t0) / 1_000_000;
                byte[] px = canvas.getPixels();
                if (px != null) {
                    imageView.setImageBitmap(toBitmap(W, H, px));
                }
                status.setText("Good Vibes | " + W + "x" + H + " | " + dt + "ms | " + result);
            }
        } catch (Throwable t) {
            status.setText("Error: " + t);
        }
    }

    private String loadAsset(String name) throws java.io.IOException {
        try (java.io.InputStream is = getAssets().open(name)) {
            byte[] buf = new byte[is.available()];
            is.read(buf);
            return new String(buf, "UTF-8");
        }
    }
}
