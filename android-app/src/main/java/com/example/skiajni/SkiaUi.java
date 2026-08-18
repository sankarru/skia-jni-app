package com.example.skiajni;

import android.graphics.Color;
import android.view.MotionEvent;

/**
 * A tiny Compose-like UI toolkit built on the SkiaCanvas JNI layer.
 * Widgets are Java objects that render themselves via Skia primitives
 * and handle touch input. No Android Canvas used for drawing.
 */
public class SkiaUi {

    // ── Widget base ─────────────────────────────────────────────────
    public abstract static class Widget {
        public float x, y, w, h;

        Widget(float x, float y, float w, float h) {
            this.x = x; this.y = y; this.w = w; this.h = h;
        }

        abstract void draw(SkiaCanvas c);
        boolean onTouch(float tx, float ty) { return false; }
        boolean contains(float tx, float ty) {
            return tx >= x && tx <= x + w && ty >= y && ty <= y + h;
        }
    }

    // ── Text label ──────────────────────────────────────────────────
    public static class Text extends Widget {
        String label;
        int color, size;

        public Text(String label, float x, float y, int color, int size) {
            super(x, y, 0, 0);
            this.label = label; this.color = color; this.size = size;
        }

        @Override
        void draw(SkiaCanvas c) {
            c.drawText(label, x, y + size, color, size);
        }
    }

    // ── EditText ────────────────────────────────────────────────────
    public static class EditText extends Widget {
        public String text = "";
        boolean focused = false;

        public EditText(float x, float y, float w, float h) {
            super(x, y, w, h);
        }

        @Override
        void draw(SkiaCanvas c) {
            int bg = focused ? 0xFFECEFF1 : 0xFFCFD8DC;
            c.fillRoundRect(x, y, w, h, 12, 12, bg);
            c.drawRoundRect(x, y, w, h, 12, 12,
                    focused ? 0xFF2196F3 : 0xFF90A4AE, 2);
            String shown = text;
            if (focused && (System.currentTimeMillis() / 500) % 2 == 0) shown += "|";
            c.drawText(shown.isEmpty() ? "  " : shown,
                    x + 16, y + h * 0.62f, 0xFF263238, (int) (h * 0.4f));
        }

        @Override
        boolean onTouch(float tx, float ty) {
            if (contains(tx, ty)) { focused = true; return true; }
            focused = false;
            return false;
        }
    }

    // ── Button ──────────────────────────────────────────────────────
    public static class Button extends Widget {
        String label;
        int color;
        boolean pressed = false;

        public Button(String label, float x, float y, float w, float h, int color) {
            super(x, y, w, h);
            this.label = label; this.color = color;
        }

        @Override
        void draw(SkiaCanvas c) {
            int base = pressed ? 0xFF1565C0 : color;
            c.fillRoundRect(x, y, w, h, 14, 14, base);
            c.drawRoundRect(x, y, w, h, 14, 14, 0xFF0D47A1, 2);
            int fs = (int) (h * 0.42f);
            float tw = c.measureText(label, fs);
            // Center horizontally and vertically (baseline offset by ~1/3 of size)
            c.drawText(label,
                    x + (w - tw) / 2,
                    y + h / 2 + fs * 0.35f,
                    0xFFFFFFFF, fs);
        }

        @Override
        boolean onTouch(float tx, float ty) {
            if (contains(tx, ty)) { pressed = true; return true; }
            return false;
        }

        void release() { pressed = false; }
    }

    // ── Card / container ────────────────────────────────────────────
    public static class Card extends Widget {
        int bg;

        public Card(float x, float y, float w, float h, int bg) {
            super(x, y, w, h);
            this.bg = bg;
        }

        @Override
        void draw(SkiaCanvas c) {
            c.fillRoundRect(x, y, w, h, 20, 20, bg);
            c.drawRoundRect(x, y, w, h, 20, 20, 0x22000000, 2);
        }
    }

    // ── Row of widgets (simple linear layout) ───────────────────────
    public static class Row extends Widget {
        final java.util.List<Widget> children = new java.util.ArrayList<>();

        public Row(float x, float y, float w, float h) {
            super(x, y, w, h);
        }

        void add(Widget child) {
            children.add(child);
            // Position children horizontally with margins
            float cx = x + 20;
            for (Widget c2 : children) {
                if (c2 instanceof Text) { c2.x = cx; cx += 16 + 60; }
                else { c2.x = cx; cx += c2.w + 20; }
                c2.y = y + 12;
            }
        }

        @Override
        void draw(SkiaCanvas c) {
            for (Widget child : children) child.draw(c);
        }

        @Override
        boolean onTouch(float tx, float ty) {
            for (Widget child : children)
                if (child.onTouch(tx, ty)) return true;
            return false;
        }
    }

    // ── Column ──────────────────────────────────────────────────────
    public static class Column extends Widget {
        final java.util.List<Widget> children = new java.util.ArrayList<>();
        float nextY;

        public Column(float x, float y, float w, float h) {
            super(x, y, w, h);
            nextY = y + 30;
        }

        void add(Widget child) {
            child.x = x + 30;
            child.y = nextY;
            nextY += child.h + 24;
            children.add(child);
        }

        void addText(String t, int color, int size) {
            Text txt = new Text(t, x + 30, nextY, color, size);
            nextY += size + 16;
            children.add(txt);
        }

        @Override
        void draw(SkiaCanvas c) {
            for (Widget child : children) child.draw(c);
        }

        @Override
        boolean onTouch(float tx, float ty) {
            for (Widget child : children)
                if (child.onTouch(tx, ty)) return true;
            return false;
        }
    }
}