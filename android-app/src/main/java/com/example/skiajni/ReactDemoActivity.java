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

/** Renders a React Native-style component tree via Hermes + Skia. */
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
        root.setBackgroundColor(0xFF000000);
        ImageView imageView = new ImageView(this);
        imageView.setScaleType(ImageView.ScaleType.FIT_XY);
        root.addView(imageView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        TextView status = new TextView(this);
        status.setTextColor(0xBBFFFFFF);
        status.setTextSize(12);
        status.setShadowLayer(3, 1, 1, 0xFF000000);
        status.setPadding(16, 24, 16, 8);
        root.addView(status, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.LEFT));
        setContentView(root);

        // ── Cutout / safe-area fix ─────────────────────────────────
        // The content is drawn fullscreen via Skia, so we offset the UI
        // below the display cutout (notch) and above the system nav bar.
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

    private void render(int W, int H, int topInset, int bottomInset,
                        ImageView imageView, TextView status) {
        try {
            long t0 = System.nanoTime();
            String runtime = loadAsset("rn_runtime.js");

            try (SkiaCanvas canvas = new SkiaCanvas(W, H);
                 JsCanvas js = new JsCanvas(W, H)) {

                js.eval(runtime);

                // Root is laid out between top and bottom insets (height = H - top - bottom).
                int contentH = H - topInset - bottomInset;

                String appJs =
                    "var root = render(_handle, " +
                    "  View({ style: { background: 0xFF0F172A, flexGrow: 1 } }," +
                    "    Header('React Native on Skia', 'Yoga flexbox layout, drawn via JNI')," +
                    "    Text({ style: { fontSize: 12, color: 0xFF64748B, marginTop: 20," +
                    "      marginLeft: 16 } }, 'PERFORMANCE')," +
                    "    View({ style: { flexDirection: 'row', gap: 12, padding: 16 } }," +
                    "      StatCard('99%', 'CPU', 'Software raster')," +
                    "      StatCard('60fps', 'FPS', 'Vsync driven')," +
                    "      StatCard('1.4MB', 'APK', 'No WebView')" +
                    "    )," +
                    "    Text({ style: { fontSize: 12, color: 0xFF64748B, marginTop: 20," +
                    "      marginLeft: 16 } }, 'ACTIONS')," +
                    "    Button({ style: { background: 0xFF2563EB, color: 0xFFFFFFFF," +
                    "      fontSize: 15, fontWeight: 'bold', borderRadius: 8, padding: 14," +
                    "      margin: 16, borderWidth: 1, borderColor: 0xFF1976D2 } }," +
                    "      'Render via Hermes + Yoga')," +
                    "    Footer('Yoga flexbox · Hermes JS · Skia draw · no HTML/CSS')" +
                    "  )" +
                    ", " + W + ", " + contentH + ", " + topInset + "); " +
                    "'ok'";

                js.setCanvas(canvas);
                String result = js.eval(appJs);

                long dt = (System.nanoTime() - t0) / 1_000_000;
                byte[] px = canvas.getPixels();
                if (px != null) {
                    Bitmap bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
                    bmp.copyPixelsFromBuffer(ByteBuffer.wrap(px));
                    imageView.setImageBitmap(bmp);
                }
                status.setText("Hermes RN · " + W + "x" + H + " · " + dt + " ms · " + result);
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
