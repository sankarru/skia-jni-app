package com.example.skiajni.html;

/** A laid-out rectangular box for an element (or text run). */
public final class Box {
    public HtmlNode node;
    public Style style;
    public float x, y;        // content-box top-left (relative to root)
    public float w, h;        // content-box size
    public float margin, padding, border;
    public float borderRadius;
    public float[] textLinesX; // for text nodes: x of each line
    public float[] textLinesY; // for text nodes: y baseline of each line
    public String[] textLines;
    public boolean isText;

    public float borderBoxLeft() { return x - margin - border; }
    public float borderBoxTop()  { return y - margin - border; }
    public float borderBoxWidth()  { return w + 2*(padding + border); }
    public float borderBoxHeight() { return h + 2*(padding + border); }
}
