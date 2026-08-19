package com.example.skiajni;

import android.app.Activity;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.Gravity;
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
        int W = dm.widthPixels;
        int H = dm.heightPixels;

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

        try {
            long t0 = System.nanoTime();
            // Load RN runtime from assets
            String runtime = loadAsset("rn_runtime.js");

            try (SkiaCanvas canvas = new SkiaCanvas(W, H);
                 JsCanvas js = new JsCanvas(W, H)) {

                // Inject runtime
                js.eval(runtime);

                // Define component tree and render
                String appJs =
                    "var root = render(_handle, " +
                    "  View({ style: { background: 0xFF0F172A, padding: 0, flexDirection: 'column' } }," +
                    "    Header('React Native on Skia', 'Components parsed by Hermes, drawn via JNI')," +
                    "    View({ style: { flexDirection: 'row', gap: 12, padding: 16 } }," +
                    "      StatCard('99%', 'CPU', 'Software raster')," +
                    "      StatCard('60fps', 'FPS', 'Vsync driven')," +
                    "      StatCard('1.4MB', 'APK', 'No WebView')" +
                    "    )," +
                    "    Button({ style: { background: 0xFF2563EB, color: 0xFFFFFFFF," +
                    "      fontSize: 15, fontWeight: 'bold', borderRadius: 8, padding: 14," +
                    "      margin: 16, textAlign: 'center', borderWidth: 1, borderColor: 0xFF1976D2 } }," +
                    "      'Render via Hermes + Skia')," +
                    "    Footer('JSX parsed by Hermes · Flex layout · Skia draw · no HTML/CSS')" +
                    "  )" +
                    ", " + W + ", " + H + "); " +
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
