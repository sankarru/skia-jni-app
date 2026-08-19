package com.example.skiajni.html;

import java.util.List;

import com.example.skiajni.SkiaCanvas;

/** Draws laid-out HTML/CSS boxes onto a SkiaCanvas. */
public final class HtmlRenderer {

    public static void render(SkiaCanvas c, List<Box> boxes) {
        for (Box b : boxes) {
            if (b.isText) {
                renderText(c, b);
            } else {
                renderBox(c, b);
            }
        }
    }

    private static void renderBox(SkiaCanvas c, Box b) {
        if (b.style == null) return;
        if (b.style.display.equals("none")) return;

        float bx = b.borderBoxLeft();
        float by = b.borderBoxTop();
        float bw = b.borderBoxWidth();
        float bh = b.borderBoxHeight();

        // background
        if (b.style.background != 0 && bw > 0 && bh > 0) {
            if (b.style.borderRadius > 0) {
                c.fillRoundRect(bx, by, bw, bh, b.style.borderRadius, b.style.borderRadius,
                        b.style.background);
            } else {
                c.fillRect(bx, by, bw, bh, b.style.background);
            }
        }

        // border
        if (b.style.borderWidth > 0) {
            if (b.style.borderRadius > 0) {
                c.drawRoundRect(bx, by, bw, bh, b.style.borderRadius, b.style.borderRadius,
                        b.style.borderColor, b.style.borderWidth);
            } else {
                c.drawRect(bx, by, bw, bh, b.style.borderColor, b.style.borderWidth);
            }
        }
    }

    private static void renderText(SkiaCanvas c, Box b) {
        if (b.textLines == null || b.textLines.length == 0) return;
        Style s = b.style;
        int color = s != null ? s.color : 0xFF1B1B1B;
        float size = s != null ? s.fontSize : 16f;
        boolean bold = s != null && s.bold;
        int align = s != null ? s.textAlign : 0;
        // measure width for alignment
        float boxW = b.w;

        for (int i = 0; i < b.textLines.length; i++) {
            String line = b.textLines[i];
            float tx = b.textLinesX != null ? b.textLinesX[i] : b.x;
            float ty = b.textLinesY != null ? b.textLinesY[i] : b.y + size;
            if (align != 0) {
                float w = c.measureText(line, size);
                if (align == 1) tx = b.x + (boxW - w) / 2f;
                else if (align == 2) tx = b.x + (boxW - w);
            }
            if (bold) {
                // fake bold: draw twice with a slight offset
                c.drawText(line, tx + 1, ty, color, size);
                c.drawText(line, tx, ty, color, size);
            } else {
                c.drawText(line, tx, ty, color, size);
            }
        }
    }
}
