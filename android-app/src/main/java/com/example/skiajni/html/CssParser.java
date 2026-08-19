package com.example.skiajni.html;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Minimal CSS parser: extracts `selector { prop: value; ... }` blocks. */
public final class CssParser {

    public static List<CssRule> parse(String css) {
        List<CssRule> rules = new ArrayList<>();
        if (css == null) return rules;
        int i = 0;
        int n = css.length();
        while (i < n) {
            // find selector (up to first '{' not inside a string)
            int brace = css.indexOf('{', i);
            if (brace < 0) break;
            String selector = css.substring(i, brace).trim();
            int close = css.indexOf('}', brace);
            if (close < 0) break;
            String body = css.substring(brace + 1, close);
            i = close + 1;
            if (selector.isEmpty()) continue;

            List<String> parts = new ArrayList<>();
            for (String p : selector.split("\\s+")) {
                if (!p.isEmpty()) {
                    // handle compound like div.foo -> div, .foo
                    splitCompound(p, parts);
                }
            }
            if (parts.isEmpty()) continue;

            Map<String, String> decls = new LinkedHashMap<>();
            for (String decl : body.split(";")) {
                int colon = decl.indexOf(':');
                if (colon < 0) continue;
                String prop = decl.substring(0, colon).trim().toLowerCase();
                String val = decl.substring(colon + 1).trim();
                if (!prop.isEmpty() && !val.isEmpty()) decls.put(prop, val);
            }
            if (!decls.isEmpty()) rules.add(new CssRule(parts, decls));
        }
        return rules;
    }

    private static void splitCompound(String sel, List<String> parts) {
        // tokenize into tag / .class / #id segments
        StringBuilder tag = new StringBuilder();
        for (int i = 0; i < sel.length(); i++) {
            char c = sel.charAt(i);
            if (c == '.' || c == '#') {
                if (tag.length() > 0) { parts.add(tag.toString()); tag.setLength(0); }
                StringBuilder seg = new StringBuilder();
                seg.append(c);
                i++;
                while (i < sel.length() && sel.charAt(i) != '.' && sel.charAt(i) != '#') {
                    seg.append(sel.charAt(i));
                    i++;
                }
                i--;
                parts.add(seg.toString());
            } else {
                tag.append(c);
            }
        }
        if (tag.length() > 0) parts.add(tag.toString());
    }
}
