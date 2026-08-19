package com.example.skiajni.html;

import java.util.ArrayList;
import java.util.List;

/** A node in the parsed HTML DOM tree: either an element or a text node. */
public final class HtmlNode {
    public enum Kind { ELEMENT, TEXT }

    public final Kind kind;
    public final String tag;             // element tag name (lowercase), null for text
    public final String text;            // text content, null for elements
    public final List<HtmlNode> children = new ArrayList<>();
    public final List<String[]> attrs = new ArrayList<>(); // [name, value] pairs
    public HtmlNode parent;
    public Style computedStyle;

    private HtmlNode(Kind kind, String tag, String text) {
        this.kind = kind; this.tag = tag; this.text = text;
    }

    public static HtmlNode element(String tag) {
        return new HtmlNode(Kind.ELEMENT, tag, null);
    }

    public static HtmlNode text(String text) {
        return new HtmlNode(Kind.TEXT, null, text);
    }

    public void addChild(HtmlNode child) {
        child.parent = this;
        children.add(child);
    }

    public String attr(String name) {
        for (String[] a : attrs) {
            if (a[0].equalsIgnoreCase(name)) return a[1];
        }
        return null;
    }
}
