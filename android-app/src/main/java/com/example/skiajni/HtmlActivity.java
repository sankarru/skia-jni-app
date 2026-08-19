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

import com.example.skiajni.html.HtmlEngine;

import java.nio.ByteBuffer;

/** Renders a custom HTML/CSS page through the Skia engine. */
public class HtmlActivity extends Activity {

    private static final String CSS =
        "body { background: #0f172a; color: #e2e8f0; font-family: sans-serif; }\n" +
        ".header { background: linear-gradient; padding: 24px; border-bottom: 2px solid #1e293b; }\n" +
        ".title { font-size: 32px; font-weight: bold; color: #f8fafc; }\n" +
        ".subtitle { font-size: 14px; color: #94a3b8; }\n" +
        ".row { display: flex; gap: 12px; margin: 16px; }\n" +
        ".card { background: #1e293b; border: 1px solid #334155; border-radius: 12px; padding: 16px; width: 160px; }\n" +
        ".card-title { font-size: 16px; font-weight: bold; color: #38bdf8; }\n" +
        ".card-body { font-size: 13px; color: #cbd5e1; }\n" +
        ".stat { font-size: 26px; font-weight: bold; color: #4ade80; }\n" +
        ".label { font-size: 12px; color: #64748b; }\n" +
        "button { background: #2563eb; color: white; font-size: 15px; font-weight: bold; " +
        "  border-radius: 8px; padding: 12px; margin: 16px; }\n" +
        ".footer { font-size: 12px; color: #64748b; text-align: center; padding: 16px; }\n";

    private static final String HTML =
        "<html><body>\n" +
        "  <div class='header'>\n" +
        "    <div class='title'>Skia HTML Engine</div>\n" +
        "    <div class='subtitle'>Custom HTML + CSS rendered with Skia — no WebView</div>\n" +
        "  </div>\n" +
        "  <div class='row'>\n" +
        "    <div class='card'><div class='stat'>99%</div><div class='label'>CPU</div>" +
        "      <div class='card-body'>Software raster</div></div>\n" +
        "    <div class='card'><div class='stat'>60fps</div><div class='label'>FPS</div>" +
        "      <div class='card-body'>Vsync driven</div></div>\n" +
        "    <div class='card'><div class='stat'>1.4MB</div><div class='label'>APK</div>" +
        "      <div class='card-body'>No WebView dep</div></div>\n" +
        "  </div>\n" +
        "  <button>Render via Skia JNI</button>\n" +
        "  <div class='footer'>Parsed HTML &gt; CSS &gt; Layout &gt; Skia draw · " +
        "blocks, inline text, flex row, cards</div>\n" +
        "</body></html>\n";

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
        root.setBackgroundColor(Color.BLACK);
        ImageView imageView = new ImageView(this);
        imageView.setScaleType(ImageView.ScaleType.FIT_XY);
        root.addView(imageView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        TextView status = new TextView(this);
        status.setTextColor(Color.argb(180, 255, 255, 255));
        status.setTextSize(12);
        status.setShadowLayer(3, 1, 1, Color.BLACK);
        status.setPadding(16, 24, 16, 8);
        root.addView(status, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.LEFT));
        setContentView(root);

        try {
            long t0 = System.nanoTime();
            try (SkiaCanvas canvas = new SkiaCanvas(W, H)) {
                HtmlEngine engine = HtmlEngine.create(HTML, CSS, W, H);
                engine.layout(canvas);
                canvas.clear(0xFF0F172A);
                engine.render(canvas);
                byte[] px = canvas.getPixels();
                long dt = (System.nanoTime() - t0) / 1_000_000;
                if (px != null) {
                    Bitmap bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
                    bmp.copyPixelsFromBuffer(ByteBuffer.wrap(px));
                    imageView.setImageBitmap(bmp);
                }
                status.setText("HTML+CSS on Skia  ·  " + W + "x" + H + "  ·  " + dt + " ms"
                        + "  ·  " + engine.getBoxes().size() + " boxes");
            }
        } catch (Throwable t) {
            status.setText("Error: " + t);
        }
    }
}
