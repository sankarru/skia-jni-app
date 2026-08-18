package com.example.skiajni;

/**
 * Minimal test runner — renders a scene and saves to PNG.
 * Works on any platform that loads libskia_jni.so.
 */
public class Demo {
    public static void main(String[] args) {
        String out = args.length > 0 ? args[0] : "skia_output.png";

        try (SkiaCanvas c = new SkiaCanvas(800, 600)) {
            c.clear(0xFFFAFAFA);

            // filled rect
            c.drawRect(40, 40, 200, 120, 0xFF2196F3, 3.0f);

            // circle
            c.drawCircle(500, 200, 90, 0xFFE91E63, 4.0f);

            // diagonal line
            c.drawLine(50, 450, 750, 150, 0xFF4CAF50, 3.0f);

            // text
            c.drawText("Skia via JNI — aarch64", 180, 520, 0xFF212121, 40.0f);

            if (c.saveToFile(out)) {
                System.out.println("[OK] " + c.getWidth() + "x" + c.getHeight() + " -> " + out);
            } else {
                System.err.println("[FAIL] saveToFile returned false");
                System.exit(1);
            }
        }
    }
}
