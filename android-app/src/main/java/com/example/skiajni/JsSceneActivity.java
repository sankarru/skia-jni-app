package com.example.skiajni;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.nio.ByteBuffer;

/**
 * Renders a scene defined in JavaScript and executed by the Hermes engine,
 * with all drawing happening through the Skia JNI backend.
 */
public class JsSceneActivity extends Activity {

    private int W, H;
    private ImageView imageView;
    private TextView status;

    private static final String SCENE =
        // h is the Skia canvas handle
        "clear(h, 0xFF101018);\n" +
        "// background gradient bands\n" +
        "fillRect(h, 0, 0, 1080, 300, 0xFF3F51B5);\n" +
        "fillRect(h, 0, 1500, 1080, 420, 0xFF4A148C);\n" +
        "// circles\n" +
        "fillCircle(h, 540, 700, 160, 0xFFE91E63);\n" +
        "fillCircle(h, 400, 950, 120, 0xFFFF9800);\n" +
        "fillCircle(h, 700, 1050, 130, 0xFF4CAF50);\n" +
        "// rings\n" +
        "drawCircle(h, 540, 400, 200, 0xFFFFFFFF, 6);\n" +
        "drawCircle(h, 540, 400, 250, 0xFFFFFFFF, 2);\n" +
        "// text from JavaScript\n" +
        "drawText(h, 'Hello from Hermes + JS!', 100, 1350, 0xFFFFFFFF, 64);\n" +
        "drawText(h, 'scripted UI via Skia JNI', 150, 1420, 0xFFB3E5FC, 36);\n";

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
        render();
    }

    private void render() {
        try {
            long t0 = System.nanoTime();
            try (SkiaCanvas canvas = new SkiaCanvas(W, H);
                 JsCanvas js = new JsCanvas(W, H)) {
                String result = js.drawScript(SCENE, canvas);
                byte[] px = canvas.getPixels();
                long dt = (System.nanoTime() - t0) / 1_000_000;
                if (px != null) {
                    Bitmap bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
                    bmp.copyPixelsFromBuffer(ByteBuffer.wrap(px));
                    imageView.setImageBitmap(bmp);
                }
                status.setText("Hermes JS  ·  " + W + "x" + H + "  ·  " + dt + " ms  ·  " + result);
            }
        } catch (Throwable t) {
            status.setText("Error: " + t.getMessage());
        }
    }
}
