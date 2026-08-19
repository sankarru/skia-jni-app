package com.example.skiajni.html;

import java.util.List;
import java.util.Map;

/** A single CSS rule: a selector + a map of declarations. */
public final class CssRule {
    /** A selector component: type tag name, or class name (starts with .), or id (starts with #). */
    public final List<String> parts; // compound selector parts: "tag", ".class", "#id"
    public final Map<String, String> declarations;

    public CssRule(List<String> parts, Map<String, String> declarations) {
        this.parts = parts;
        this.declarations = declarations;
    }

    /** Returns true if the selector matches the given element. */
    public boolean matches(HtmlNode el) {
        // Support simple descendant selectors (space-separated) right-to-left.
        // For simplicity, match the last part against the element, then walk ancestors.
        int idx = parts.size() - 1;
        HtmlNode cur = el;
        while (idx >= 0 && cur != null) {
            if (matchesPart(parts.get(idx), cur)) {
                idx--;
                cur = cur.parent;
            } else {
                // try matching same part on an ancestor (descendant combinator)
                cur = cur.parent;
            }
        }
        return idx < 0;
    }

    private boolean matchesPart(String part, HtmlNode el) {
        if (el.kind != HtmlNode.Kind.ELEMENT) return false;
        if (part.startsWith(".")) {
            String cls = part.substring(1);
            String v = el.attr("class");
            if (v == null) return false;
            for (String c : v.split("\\s+")) if (c.equals(cls)) return true;
            return false;
        }
        if (part.startsWith("#")) return part.substring(1).equals(el.attr("id"));
        return part.equals(el.tag);
    }
}
