package com.example.skiajni.html;

import java.util.ArrayList;
import java.util.List;

/** Simple block/inline/flex layout engine producing Box objects. */
public final class LayoutEngine {

    public static List<Box> layout(HtmlNode root, float viewportW, float viewportH,
                                   TextMeasurer measurer) {
        // Root container
        Box rootBox = new Box();
        rootBox.node = root;
        rootBox.style = root.computedStyle != null ? root.computedStyle : new Style();
        rootBox.x = 0;
        rootBox.y = 0;
        rootBox.w = viewportW;
        rootBox.h = viewportH;
        rootBox.margin = 0;
        rootBox.padding = 0;
        rootBox.border = 0;

        // A hidden root container holding the whole layout at y=0
        LayoutCtx ctx = new LayoutCtx();
        ctx.measurer = measurer;
        List<Box> boxes = new ArrayList<>();
        boxes.add(rootBox);

        // Layout the real html/body content inside the viewport box
        layoutChildren(root, rootBox, boxes, ctx, viewportW);
        return boxes;
    }

    /** Layout context shared during layout. */
    private static final class LayoutCtx {
        TextMeasurer measurer;
    }

    /** Layout the children of `parentBox` into it. */
    private static void layoutChildren(HtmlNode parentNode, Box parentBox,
                                       List<Box> boxes, LayoutCtx ctx, float viewportW) {
        Style ps = parentBox.style;
        boolean isFlex = ps != null && ps.display.equals("flex");
        float contentX = parentBox.x + parentBox.padding + parentBox.border;
        float contentW = parentBox.w - 2 * (parentBox.padding + parentBox.border);

        // Separate element and text children
        List<HtmlNode> elementChildren = new ArrayList<>();
        List<HtmlNode> textChildren = new ArrayList<>();
        for (HtmlNode c : parentNode.children) {
            if (c.kind == HtmlNode.Kind.TEXT) textChildren.add(c);
            else elementChildren.add(c);
        }

        if (isFlex) {
            layoutFlex(parentNode, parentBox, elementChildren, boxes, ctx, contentX, contentW);
        } else {
            layoutBlock(parentNode, parentBox, elementChildren, textChildren,
                    boxes, ctx, contentX, contentW);
        }
    }

    private static void layoutBlock(HtmlNode parentNode, Box parentBox,
                                    List<HtmlNode> elementChildren, List<HtmlNode> textChildren,
                                    List<Box> boxes, LayoutCtx ctx,
                                    float contentX, float contentW) {
        float cursorY = parentBox.y + parentBox.padding + parentBox.border;
        float x = contentX;

        for (HtmlNode c : elementChildren) {
            Style cs = c.computedStyle != null ? c.computedStyle : new Style();
            if (cs.display.equals("none")) continue;

            // Inline elements: lay them out on the current line with their text (skip for now)
            if (cs.display.equals("inline")) {
                // treat inline element's text as a text run on the current line
                Box inlineBox = new Box();
                inlineBox.node = c;
                inlineBox.style = cs;
                inlineBox.x = x;
                inlineBox.y = cursorY;
                inlineBox.padding = cs.padding;
                inlineBox.border = cs.borderWidth;
                inlineBox.margin = cs.margin;
                inlineBox.borderRadius = cs.borderRadius;
                inlineBox.isText = true;
                boxes.add(inlineBox);
                float lineH = cs.fontSize * cs.lineHeight;
                float maxW = contentW - (x - contentX);
                float wUsed = layoutInlineText(c, inlineBox, maxW, ctx);
                x += wUsed;
                cursorY = Math.max(cursorY, inlineBox.y);
                continue;
            }

            Box box = new Box();
            box.node = c;
            box.style = cs;
            box.margin = cs.margin;
            box.padding = cs.padding;
            box.border = cs.borderWidth;
            box.borderRadius = cs.borderRadius;
            box.x = contentX + cs.margin;
            box.w = (cs.width > 0) ? cs.width : (contentW - 2 * cs.margin);
            if (box.w < 0) box.w = 0;
            box.y = cursorY + cs.margin;
            box.h = (cs.height > 0) ? cs.height : 0;
            boxes.add(box);

            // layout children to determine height if auto
            float childH = layoutChildrenReturnHeight(c, box, boxes, ctx, viewportWidth(box));
            if (cs.height > 0) box.h = cs.height;
            else box.h = childH;

            float borderBoxH = box.h + 2 * (box.padding + box.border);
            cursorY = box.y + box.margin + borderBoxH;
        }

        // Text-only children
        if (!textChildren.isEmpty()) {
            Box textBox = new Box();
            textBox.node = textChildren.get(0);
            textBox.style = textChildren.get(0).computedStyle != null
                    ? textChildren.get(0).computedStyle : new Style();
            textBox.x = x;
            textBox.y = cursorY;
            textBox.isText = true;
            boxes.add(textBox);
            float maxW = contentW - (x - contentX);
            layoutTextRuns(textChildren, textBox, maxW, ctx);
        }

        // Update parent height if auto to fit content
        float contentBottom = cursorY;
        float parentContentBottom = parentBox.y + parentBox.padding + parentBox.border;
        if (parentBox.style != null && parentBox.style.height <= 0) {
            float needed = contentBottom - parentContentBottom;
            if (needed > parentBox.h) parentBox.h = needed;
        }
    }

    private static void layoutFlex(HtmlNode parentNode, Box parentBox,
                                   List<HtmlNode> elementChildren, List<Box> boxes,
                                   LayoutCtx ctx, float contentX, float contentW) {
        Style ps = parentBox.style;
        boolean row = ps.flexDirection.equals("row");
        float contentY = parentBox.y + parentBox.padding + parentBox.border;

        // compute children sizes
        float available = row ? contentW : (parentBox.h - 2*(parentBox.padding+parentBox.border));
        int n = 0;
        for (HtmlNode c : elementChildren) {
            Style cs = c.computedStyle != null ? c.computedStyle : new Style();
            if (!cs.display.equals("none")) n++;
        }
        float gap = ps.gap;
        float totalGap = gap * Math.max(0, n - 1);

        float cursor = contentX;
        if (row) {
            float flexAvail = available - totalGap;
            // justify-content
            float startX = contentX;
            if (ps.justifyContent.equals("center")) startX = contentX + (flexAvail - sumChildWidth(elementChildren, contentW, ctx)) / 2f;
            else if (ps.justifyContent.equals("space-between") && n > 1) startX = contentX;
            cursor = startX;

            for (HtmlNode c : elementChildren) {
                Style cs = c.computedStyle != null ? c.computedStyle : new Style();
                if (cs.display.equals("none")) continue;
                Box box = new Box();
                box.node = c;
                box.style = cs;
                box.margin = cs.margin;
                box.padding = cs.padding;
                box.border = cs.borderWidth;
                box.borderRadius = cs.borderRadius;
                box.x = cursor + cs.margin;
                box.w = (cs.width > 0) ? cs.width : (contentW - cs.margin*2);
                if (box.w < 0) box.w = 0;
                float childH = layoutChildrenReturnHeight(c, box, boxes, ctx, box.w);
                box.h = (cs.height > 0) ? cs.height : childH;
                // align-items
                box.y = contentY + cs.margin;
                if (ps.alignItems.equals("center")) box.y = contentY + (parentBox.h - box.h)/2f - (parentBox.padding+parentBox.border);
                else if (ps.alignItems.equals("flex-end")) box.y = contentY + parentBox.h - box.h - 2*(parentBox.padding+parentBox.border);
                boxes.add(box);
                cursor += box.w + 2*cs.margin + gap;
            }
        } else {
            // column
            cursor = contentY;
            for (HtmlNode c : elementChildren) {
                Style cs = c.computedStyle != null ? c.computedStyle : new Style();
                if (cs.display.equals("none")) continue;
                Box box = new Box();
                box.node = c;
                box.style = cs;
                box.margin = cs.margin;
                box.padding = cs.padding;
                box.border = cs.borderWidth;
                box.borderRadius = cs.borderRadius;
                box.x = contentX + cs.margin;
                box.w = (cs.width > 0) ? cs.width : (contentW - 2*cs.margin);
                box.y = cursor + cs.margin;
                float childH = layoutChildrenReturnHeight(c, box, boxes, ctx, box.w);
                box.h = (cs.height > 0) ? cs.height : childH;
                boxes.add(box);
                cursor += box.h + 2*cs.margin + gap;
            }
        }
        // parent height
        if (parentBox.style != null && parentBox.style.height <= 0) {
            float needed = cursor - contentY;
            if (needed > parentBox.h) parentBox.h = needed;
        }
    }

    private static float sumChildWidth(List<HtmlNode> children, float contentW, LayoutCtx ctx) {
        float sum = 0;
        for (HtmlNode c : children) {
            Style cs = c.computedStyle != null ? c.computedStyle : new Style();
            if (cs.display.equals("none")) continue;
            sum += (cs.width > 0 ? cs.width : contentW) + 2*cs.margin;
        }
        return sum;
    }

    private static float layoutChildrenReturnHeight(HtmlNode node, Box box, List<Box> boxes,
                                                    LayoutCtx ctx, float contentW) {
        // Compute content height without necessarily adding children boxes for text
        List<Box> temp = new ArrayList<>();
        int before = boxes.size();
        layoutChildren(node, box, boxes, ctx, contentW);
        // measure content height: max of child bottoms minus box top
        float maxBottom = box.y + box.padding + box.border;
        // Use the box's own h which was set by layoutChildren if auto
        return Math.max(0, box.h);
    }

    private static float viewportWidth(Box box) {
        return box.w;
    }

    /** Layout text runs (children text nodes) into a box, wrapping words. */
    private static void layoutTextRuns(List<HtmlNode> textNodes, Box box, float maxW, LayoutCtx ctx) {
        // Concatenate text
        StringBuilder sb = new StringBuilder();
        for (HtmlNode t : textNodes) sb.append(t.text).append(' ');
        box.textLines = wrapText(sb.toString().trim(), maxW, box.style.fontSize, ctx);
        float lineH = box.style.fontSize * box.style.lineHeight;
        box.textLinesY = new float[box.textLines.length];
        box.textLinesX = new float[box.textLines.length];
        for (int i = 0; i < box.textLines.length; i++) {
            box.textLinesX[i] = box.x;
            box.textLinesY[i] = box.y + box.style.fontSize + i * lineH;
        }
        box.h = box.textLines.length * lineH;
        box.w = maxW;
    }

    /** Layout inline element's text. Returns width used on the line. */
    private static float layoutInlineText(HtmlNode c, Box box, float maxW, LayoutCtx ctx) {
        StringBuilder sb = new StringBuilder();
        for (HtmlNode t : c.children) if (t.kind == HtmlNode.Kind.TEXT) sb.append(t.text);
        String text = sb.toString();
        if (text.isEmpty()) return 0;
        box.textLines = wrapText(text, maxW, box.style.fontSize, ctx);
        float lineH = box.style.fontSize * box.style.lineHeight;
        box.textLinesY = new float[box.textLines.length];
        box.textLinesX = new float[box.textLines.length];
        for (int i = 0; i < box.textLines.length; i++) {
            box.textLinesX[i] = box.x;
            box.textLinesY[i] = box.y + box.style.fontSize + i * lineH;
        }
        box.h = box.textLines.length * lineH;
        float wUsed = 0;
        if (box.textLines.length > 0) {
            wUsed = ctx.measurer.measure(box.textLines[0], box.style.fontSize);
        }
        box.w = wUsed;
        return wUsed;
    }

    /** Word-wrap text to fit maxW using the measurer. */
    private static String[] wrapText(String text, float maxW, float fontSize, LayoutCtx ctx) {
        if (text.isEmpty()) return new String[]{""};
        List<String> lines = new ArrayList<>();
        String[] words = text.split("\\s+");
        StringBuilder line = new StringBuilder();
        for (String word : words) {
            String candidate = line.length() == 0 ? word : line.toString() + " " + word;
            if (ctx.measurer.measure(candidate, fontSize) <= maxW || line.length() == 0) {
                line.append(line.length() == 0 ? word : " " + word);
            } else {
                lines.add(line.toString());
                line = new StringBuilder(word);
            }
        }
        if (line.length() > 0) lines.add(line.toString());
        return lines.toArray(new String[0]);
    }

    public interface TextMeasurer {
        float measure(String text, float size);
    }
}
