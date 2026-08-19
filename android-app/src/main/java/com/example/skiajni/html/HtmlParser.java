package com.example.skiajni.html;

import java.util.List;

/** Minimal, tolerant HTML tokenizer/parser producing a DOM tree. */
public final class HtmlParser {

    private final String src;
    private int pos = 0;

    private HtmlParser(String src) { this.src = src; }

    /** Tags that are void (self-closing) in HTML5. */
    private static boolean isVoid(String tag) {
        switch (tag) {
            case "br": case "hr": case "img": case "input": case "meta":
            case "link": case "wbr": case "col": case "embed":
                return true;
            default: return false;
        }
    }

    public static HtmlNode parse(String html) {
        HtmlParser p = new HtmlParser(html);
        HtmlNode root = HtmlNode.element("html");
        p.parseChildren(root, null);
        return root;
    }

    private void parseChildren(HtmlNode parent, String stopTag) {
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == '<') {
                if (pos + 1 < src.length() && src.charAt(pos + 1) == '/') {
                    // closing tag
                    int close = src.indexOf('>', pos);
                    if (close < 0) break;
                    String tag = src.substring(pos + 2, close).trim().toLowerCase();
                    pos = close + 1;
                    if (stopTag != null && tag.equals(stopTag)) return;
                    continue;
                }
                if (pos + 1 < src.length() && (src.charAt(pos + 1) == '!'
                        || src.charAt(pos + 1) == '?')) {
                    // comment or doctype or processing instruction
                    int close = src.indexOf('>', pos);
                    if (close < 0) break;
                    pos = close + 1;
                    continue;
                }
                int close = src.indexOf('>', pos);
                if (close < 0) break;
                String openTag = src.substring(pos + 1, close).trim();
                pos = close + 1;
                parseOpenTag(parent, openTag);
            } else {
                int next = src.indexOf('<', pos);
                int end = (next < 0) ? src.length() : next;
                String text = src.substring(pos, end).trim();
                pos = (next < 0) ? src.length() : next;
                if (!text.isEmpty()) {
                    HtmlNode tn = HtmlNode.text(text);
                    parent.addChild(tn);
                }
            }
        }
    }

    private void parseOpenTag(HtmlNode parent, String openTag) {
        // split tag name from attributes
        int sp = indexOfWhitespace(openTag);
        String tag;
        String attrStr;
        if (sp < 0) { tag = openTag; attrStr = ""; }
        else { tag = openTag.substring(0, sp); attrStr = openTag.substring(sp + 1); }

        tag = tag.toLowerCase();
        HtmlNode el = HtmlNode.element(tag);
        parseAttrs(el, attrStr);
        parent.addChild(el);

        if (isVoid(tag)) return;
        parseChildren(el, tag);
    }

    private void parseAttrs(HtmlNode el, String attrStr) {
        int i = 0;
        int n = attrStr.length();
        while (i < n) {
            while (i < n && Character.isWhitespace(attrStr.charAt(i))) i++;
            int start = i;
            while (i < n && !Character.isWhitespace(attrStr.charAt(i))
                    && attrStr.charAt(i) != '=') i++;
            if (start == i) { i++; continue; }
            String name = attrStr.substring(start, i).toLowerCase();
            while (i < n && Character.isWhitespace(attrStr.charAt(i))) i++;
            String value = "";
            if (i < n && attrStr.charAt(i) == '=') {
                i++;
                while (i < n && Character.isWhitespace(attrStr.charAt(i))) i++;
                if (i < n && (attrStr.charAt(i) == '"' || attrStr.charAt(i) == '\'')) {
                    char q = attrStr.charAt(i);
                    i++;
                    int vs = i;
                    while (i < n && attrStr.charAt(i) != q) i++;
                    value = attrStr.substring(vs, i);
                    if (i < n) i++;
                } else {
                    int vs = i;
                    while (i < n && !Character.isWhitespace(attrStr.charAt(i))) i++;
                    value = attrStr.substring(vs, i);
                }
            }
            el.attrs.add(new String[]{name, value});
        }
    }

    private static int indexOfWhitespace(String s) {
        for (int i = 0; i < s.length(); i++)
            if (Character.isWhitespace(s.charAt(i))) return i;
        return -1;
    }
}
