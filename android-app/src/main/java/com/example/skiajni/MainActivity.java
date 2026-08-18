package com.example.skiajni;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.nio.ByteBuffer;

/**
 * Sample UI demonstrating Skia rendering via JNI.
 * Renders 3 scenes (Shapes / Gradient / Text) and lets the user switch.
 */
public class MainActivity extends Activity {

    private static final int W = 1080;
    private static final int H = 1920;

    private TextView status;
    private ImageView imageView;
    private int scene = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(30, 30, 30));

        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(16);
        status.setPadding(24, 24, 24, 8);
        root.addView(status);

        imageView = new ImageView(this);
        imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        root.addView(imageView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        // Scene buttons
        LinearLayout buttons = new LinearLayout(this);
        buttons.setPadding(16, 16, 16, 24);
        String[] labels = {"Shapes", "Gradient", "Text", "Paths"};
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            TextView btn = new TextView(this);
            btn.setText(labels[i]);
            btn.setTextColor(Color.WHITE);
            btn.setTextSize(18);
            btn.setPadding(28, 16, 28, 16);
            btn.setBackgroundColor(Color.rgb(33, 150, 243));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins(8, 0, 8, 0);
            btn.setGravity(android.view.Gravity.CENTER);
            btn.setOnClickListener(v -> renderScene(idx));
            buttons.addView(btn, lp);
        }
        root.addView(buttons);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);

        renderScene(0);
    }

    private void renderScene(int idx) {
        scene = idx;
        long t0 = System.nanoTime();

        try (SkiaCanvas c = new SkiaCanvas(W, H)) {
            switch (scene) {
                case 0: drawShapes(c); break;
                case 1: drawGradient(c); break;
                case 2: drawText(c); break;
                case 3: drawPaths(c); break;
            }

            byte[] px = c.getPixels();
            long dt = (System.nanoTime() - t0) / 1_000_000;

            if (px != null) {
                Bitmap bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
                bmp.copyPixelsFromBuffer(ByteBuffer.wrap(px));
                imageView.setImageBitmap(bmp);
                status.setText("Scene " + (idx + 1) + "/4  ·  " + W + "x" + H +
                        "  ·  " + dt + " ms  ·  Skia JNI");
            } else {
                status.setText("getPixels() returned null");
            }
        } catch (Throwable t) {
            status.setText("Error: " + t.getMessage());
        }
    }

    private void drawShapes(SkiaCanvas c) {
        c.clear(0xFFFAFAFA);

        // Stroked shapes
        c.drawRect(60, 60, 300, 300, 0xFF2196F3, 8);
        c.drawRoundRect(420, 60, 300, 300, 60, 60, 0xFFFF5722, 8);
        c.drawCircle(860, 210, 150, 0xFFE91E63, 8);

        // Filled shapes
        c.fillRect(60, 420, 300, 300, 0xFF4CAF50);
        c.fillRoundRect(420, 420, 300, 300, 80, 80, 0xFF9C27B0);
        c.fillCircle(860, 570, 150, 0xFFFFC107);
        c.fillOval(60, 780, 300, 200, 0xFF00BCD4);

        // Lines
        for (int i = 0; i < 10; i++) {
            c.drawLine(80, 1050 + i * 40, 1000, 900 + i * 40, 0xFF3F51B5, 6);
        }

        // Overlapping circles (alpha)
        c.fillCircle(500, 1400, 180, 0x801D6DD9);
        c.fillCircle(620, 1400, 180, 0x80E91E63);
        c.fillCircle(560, 1540, 180, 0x804CAF50);

        c.drawText("Shape Gallery", 340, 1780, 0xFF212121, 56);
    }

    private void drawGradient(SkiaCanvas c) {
        c.clear(0xFF111111);

        c.drawGradient(0, 0, W, 0, 0xFFE53935, 0xFF3949AB, 0);
        c.drawGradient(0, 400, W, 400, 0xFF43A047, 0xFFFFB300, 0);
        c.drawGradient(0, 800, W, 800, 0xFF8E24AA, 0xFF00ACC1, 0);
        c.drawGradient(0, 1200, W, 1200, 0xFFFF3D00, 0xFF00E676, 0);

        // Gradient circles on top
        c.fillCircle(280, 1800, 120, 0xFFFFEB3B);
        c.fillCircle(540, 1800, 120, 0xFF00E5FF);
        c.fillCircle(800, 1800, 120, 0xFFD500F9);

        c.drawText("Gradients", 420, 200, 0xFFFFFFFF, 60);
    }

    private void drawText(SkiaCanvas c) {
        c.drawGradient(0, 0, W, H, 0xFF1A237E, 0xFF4A148C, 0);

        String[] lines = {
                "Skia rendering engine",
                "powered by JNI + native .so",
                "",
                "Raster backend · CPU",
                "Vulkan backend · GPU",
                "aarch64 cross-compiled",
                "built in GitHub Actions",
                "",
                "Hello from libskia_jni.so!",
                "argb · bezier · fonts",
        };
        float y = 180;
        for (int i = 0; i < lines.length; i++) {
            int col = (i % 2 == 0) ? 0xFFFFFFFF : 0xFFB3E5FC;
            c.drawText(lines[i], 120, y, col, i == 0 ? 72 : 48);
            y += (i == 0) ? 130 : 90;
        }

        // Decorative circles
        c.drawCircle(150, 1500, 100, 0xFFFFFFFF, 4);
        c.drawCircle(930, 1500, 100, 0xFFFFFFFF, 4);
        c.drawLine(150, 1500, 930, 1500, 0xFFFFFFFF, 4);
    }

    private void drawPaths(SkiaCanvas c) {
        c.clear(0xFF0D0D0D);

        // Heart shape using cubic curves (filled + stroked)
        long heart = c.createPath();
        c.pathMoveTo(heart, 540, 600);
        c.pathCubicTo(heart, 540, 420, 300, 380, 300, 580);
        c.pathCubicTo(heart, 300, 780, 540, 880, 540, 980);
        c.pathCubicTo(heart, 540, 880, 780, 780, 780, 580);
        c.pathCubicTo(heart, 780, 380, 540, 420, 540, 600);
        c.pathClose(heart);
        c.drawPath(heart, 0xFFE91E63, 6, true);
        c.destroyPath(heart);

        // Star with transforms (rotate + scale)
        long star = c.createPath();
        for (int i = 0; i < 10; i++) {
            double ang = Math.PI / 5 * i;
            float r = (i % 2 == 0) ? 220 : 100;
            float x = 540 + (float) (r * Math.cos(ang));
            float y = 1500 + (float) (r * Math.sin(ang));
            if (i == 0) c.pathMoveTo(star, x, y);
            else c.pathLineTo(star, x, y);
        }
        c.pathClose(star);
        c.save();
        c.translate(540, 1500);
        c.rotate(15);
        c.scale(0.85f, 0.85f);
        c.drawPath(star, 0xFFFFC107, 4, true);
        c.restore();
        c.destroyPath(star);

        // Spiral via quadratic curves with rotation
        long spiral = c.createPath();
        c.pathMoveTo(spiral, 920, 400);
        for (int i = 0; i < 6; i++) {
            c.pathQuadTo(spiral, 1080, 220 + i * 60, 980, 500 + i * 40);
        }
        c.save();
        c.translate(200, 800);
        c.rotate(90);
        c.drawPath(spiral, 0xFF00E5FF, 5, false);
        c.restore();
        c.destroyPath(spiral);

        // Clip demo: circle clip with rect inside
        c.save();
        long clip = c.createPath();
        c.pathMoveTo(clip, 540, 1700);
        c.pathQuadTo(clip, 540, 1500, 700, 1650);
        c.pathQuadTo(clip, 720, 1780, 540, 1700);
        c.pathClose(clip);
        c.clipPath(clip);
        c.fillRect(400, 1550, 300, 260, 0xFF2196F3);
        c.restore();
        c.destroyPath(clip);

        c.drawText("Paths & Transforms", 300, 150, 0xFFFFFFFF, 52);
    }
}