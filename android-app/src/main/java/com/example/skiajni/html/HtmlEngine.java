package com.example.skiajni.html;

import java.util.List;

import com.example.skiajni.SkiaCanvas;

/** Facade: parse HTML+CSS, lay out, and render onto a SkiaCanvas. */
public final class HtmlEngine {

    private final HtmlNode dom;
    private final List<CssRule> rules;
    private final float viewportW;
    private final float viewportH;
    private List<Box> boxes;

    private HtmlEngine(HtmlNode dom, List<CssRule> rules, float viewportW, float viewportH) {
        this.dom = dom;
        this.rules = rules;
        this.viewportW = viewportW;
        this.viewportH = viewportH;
    }

    /** Build an engine from an HTML string and an optional CSS string. */
    public static HtmlEngine create(String html, String css, float viewportW, float viewportH) {
        HtmlNode dom = HtmlParser.parse(html);
        List<CssRule> rules = CssParser.parse(css);
        StyleEngine.apply(dom, rules);
        return new HtmlEngine(dom, rules, viewportW, viewportH);
    }

    /** Run layout using the given canvas for text measurement; must be called before render(). */
    public HtmlEngine layout(SkiaCanvas measureCanvas) {
        boxes = LayoutEngine.layout(dom, viewportW, viewportH,
                (text, size) -> measureCanvas.measureText(text, size));
        return this;
    }

    public HtmlEngine layout() {
        return layout(new SkiaCanvas(1, 1));
    }

    public List<Box> getBoxes() { return boxes; }

    /** Render the laid-out page onto the canvas. */
    public void render(SkiaCanvas canvas) {
        if (boxes == null) layout(canvas);
        HtmlRenderer.render(canvas, boxes);
    }
}
