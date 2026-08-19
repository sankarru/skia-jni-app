package com.example.skiajni.html;

import java.util.List;
import java.util.Map;

/** Applies CSS rules (and inline styles) to DOM nodes, producing computed styles. */
public final class StyleEngine {

    /** Default styles per tag. */
    private static int defaultColor(String tag) {
        switch (tag) {
            case "h1": return 0xFF1B1B1B;
            default: return 0xFF1B1B1B;
        }
    }

    public static void apply(HtmlNode root, List<CssRule> rules) {
        walk(root, rules);
    }

    private static void walk(HtmlNode node, List<CssRule> rules) {
        if (node.kind == HtmlNode.Kind.ELEMENT) {
            node.computedStyle = compute(node, rules);
            for (HtmlNode c : node.children) walk(c, rules);
        } else {
            for (HtmlNode c : node.children) walk(c, rules);
        }
    }

    private static Style compute(HtmlNode el, List<CssRule> rules) {
        Style s = defaults(el.tag);
        // cascade: last matching rule wins (simple specificity: inline > id > class > tag)
        for (CssRule r : rules) {
            if (r.matches(el)) applyDeclarations(s, r.declarations);
        }
        // inline style attribute overrides
        String inline = el.attr("style");
        if (inline != null) {
            for (String decl : inline.split(";")) {
                int colon = decl.indexOf(':');
                if (colon < 0) continue;
                String prop = decl.substring(0, colon).trim().toLowerCase();
                String val = decl.substring(colon + 1).trim();
                if (!val.isEmpty()) applyProp(s, prop, val);
            }
        }
        return s;
    }

    private static Style defaults(String tag) {
        Style s = new Style();
        switch (tag) {
            case "body": case "html": s.margin = 0; s.padding = 0; s.background = 0xFFECEFF1; break;
            case "h1": s.fontSize = 28; s.bold = true; s.margin = 0; break;
            case "h2": s.fontSize = 22; s.bold = true; s.margin = 0; break;
            case "h3": s.fontSize = 18; s.bold = true; s.margin = 0; break;
            case "p": case "div": case "span": s.margin = 0; break;
            case "a": s.color = 0xFF1565C0; s.display = "inline"; break;
            case "b": s.bold = true; s.display = "inline"; break;
            case "i": s.display = "inline"; break;
            case "em": s.display = "inline"; s.bold = true; break;
            case "small": s.fontSize = 12; s.display = "inline"; break;
            case "li": s.margin = 0; break;
            case "ul": case "ol": s.margin = 0; s.padding = 0; break;
            case "button": s.padding = 10; s.borderWidth = 1; s.borderColor = 0xFF555555;
                s.borderRadius = 8; s.background = 0xFF2196F3; s.color = 0xFFFFFFFF;
                s.textAlign = 1; break;
            case "input": s.display = "block"; s.padding = 8; s.borderWidth = 1;
                s.borderColor = 0xFF90A4AE; s.borderRadius = 6; s.background = 0xFFFFFFFF;
                s.height = 30; break;
            default: break;
        }
        return s;
    }

    private static void applyDeclarations(Style s, Map<String, String> decls) {
        for (Map.Entry<String, String> e : decls.entrySet()) {
            applyProp(s, e.getKey(), e.getValue());
        }
    }

    private static void applyProp(Style s, String prop, String val) {
        switch (prop) {
            case "color": s.color = parseColor(val, s.color); break;
            case "background":
            case "background-color": s.background = parseColor(val, s.background); break;
            case "font-size": {
                float v = parseLength(val, s.fontSize);
                if (v > 0) s.fontSize = v;
                break;
            }
            case "font-weight":
                s.fontWeight = val;
                if (val.equals("bold") || val.equals("700") || val.equals("800") || val.equals("900")) s.bold = true;
                else if (val.equals("normal") || val.equals("400")) s.bold = false;
                break;
            case "text-align":
                if (val.equals("center")) s.textAlign = 1;
                else if (val.equals("right")) s.textAlign = 2;
                else s.textAlign = 0;
                break;
            case "display": s.display = val; break;
            case "flex-direction": s.flexDirection = val; break;
            case "justify-content": s.justifyContent = val; break;
            case "align-items": s.alignItems = val; break;
            case "width": s.width = parseLength(val, s.width); break;
            case "height": s.height = parseLength(val, s.height); break;
            case "margin": s.margin = parseLength(val, 0); break;
            case "padding": s.padding = parseLength(val, 0); break;
            case "border": case "border-width": s.borderWidth = parseLength(val, 0); break;
            case "border-color": s.borderColor = parseColor(val, s.borderColor); break;
            case "border-radius": s.borderRadius = parseLength(val, 0); break;
            case "line-height": s.lineHeight = parseLength(val, s.lineHeight); break;
            case "gap": s.gap = parseLength(val, 0); break;
            default: break;
        }
    }

    /** Parse a length: number + px/pt; if unitless, assume px. */
    private static float parseLength(String val, float def) {
        try {
            val = val.trim();
            int i = 0;
            while (i < val.length() && (Character.isDigit(val.charAt(i)) || val.charAt(i) == '.')) i++;
            if (i == 0) return def;
            float num = Float.parseFloat(val.substring(0, i));
            String unit = val.substring(i).trim().toLowerCase();
            if (unit.equals("pt")) return num * 1.3333f;
            if (unit.equals("em")) return num * 16f;
            if (unit.equals("%")) return num; // handled by caller mostly
            return num; // px or unitless
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** Parse a color: #rgb, #rrggbb, or a named color. */
    static int parseColor(String val, int def) {
        val = val.trim().toLowerCase();
        if (val.startsWith("#")) {
            String hex = val.substring(1);
            try {
                if (hex.length() == 3) {
                    int r = Integer.parseInt(hex.substring(0,1)+hex.substring(0,1), 16);
                    int g = Integer.parseInt(hex.substring(1,2)+hex.substring(1,2), 16);
                    int b = Integer.parseInt(hex.substring(2,3)+hex.substring(2,3), 16);
                    return 0xFF000000 | (r<<16) | (g<<8) | b;
                }
                if (hex.length() == 6) return 0xFF000000 | Integer.parseInt(hex, 16);
            } catch (NumberFormatException ignored) {}
        }
        switch (val) {
            case "transparent": return 0;
            case "black": return 0xFF000000;
            case "white": return 0xFFFFFFFF;
            case "red": return 0xFFF44336;
            case "green": return 0xFF4CAF50;
            case "blue": return 0xFF2196F3;
            case "yellow": return 0xFFFFEB3B;
            case "gray": case "grey": return 0xFF9E9E9E;
            case "lightgray": case "lightgrey": return 0xFFBDBDBD;
            case "darkgray": case "darkgrey": return 0xFF616161;
            case "orange": return 0xFFFF9800;
            case "purple": return 0xFF9C27B0;
            case "pink": return 0xFFE91E63;
            case "teal": return 0xFF009688;
            case "brown": return 0xFF795548;
            case "indigo": return 0xFF3F51B5;
            case "cyan": return 0xFF00BCD4;
            case "lightblue": return 0xFFB3E5FC;
            case "lightgreen": return 0xFFC8E6C9;
            default: return def;
        }
    }
}
