package com.example.skiajni;

import android.app.Activity;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Environment;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;

public class MainActivity extends Activity {

    private static final int W = 1080;
    private static final int H = 1920;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        TextView status = new TextView(this);
        status.setText("Rendering via Skia (JNI)...");
        root.addView(status);

        try {
            long t0 = System.nanoTime();

            try (SkiaCanvas c = new SkiaCanvas(W, H)) {
                c.clear(0xFFFAFAFA);

                // Background rectangle
                c.drawRect(40, 40, W - 80, H - 80, 0xFF2196F3, 8.0f);

                // Circles
                c.drawCircle(300, 500, 160, 0xFFE91E63, 10.0f);
                c.drawCircle(540, 680, 220, 0xFFFF9800, 12.0f);
                c.drawCircle(780, 500, 160, 0xFF4CAF50, 10.0f);

                // Lines
                for (int i = 0; i < 12; i++) {
                    float y = 950 + i * 28;
                    c.drawLine(60, y, W - 60, y + 120, 0xFF3F51B5, 5.0f);
                }

                // Text
                c.drawText("Skia via JNI on Android", 120, 1450, 0xFF212121, 72.0f);
                c.drawText("aarch64 native .so", 260, 1560, 0xFF616161, 48.0f);

                byte[] px = c.getPixels();
                long dt = (System.nanoTime() - t0) / 1_000_000;

                if (px != null) {
                    Bitmap bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
                    bmp.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(px));

                    ImageView img = new ImageView(this);
                    img.setImageBitmap(bmp);
                    root.addView(img);

                    // Save to app-external storage for inspection
                    savePng(c, new File(getExternalFilesDir(null), "skia_output.png"));

                    status.setText("Rendered " + W + "x" + H + " in " + dt + " ms");
                } else {
                    status.setText("getPixels() returned null");
                }
            }
        } catch (Throwable t) {
            status.setText("Error: " + t.getMessage());
            Toast.makeText(this, t.getMessage(), Toast.LENGTH_LONG).show();
        }

        setContentView(root);
    }

    private void savePng(SkiaCanvas c, File out) {
        try {
            if (!c.saveToFile(out.getAbsolutePath())) return;
            FileOutputStream fos = new FileOutputStream(out);
            fos.close();
        } catch (Exception e) {
            // ignore; display is the primary goal
        }
    }
}