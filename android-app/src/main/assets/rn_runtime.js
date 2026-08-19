// ── React Native-style renderer for Hermes + Skia ──
// Provides: View, Text, Button, Header, Card, StatCard
// Renders via host functions: clear, fillRect, drawRect, drawText, etc.

// ── Component constructors ────────────────────────────────────────────

function View(props, ...children) {
  return { type: "View", props: props || {}, children: children || [] };
}

function Text(props, ...children) {
  return { type: "Text", props: props || {}, children: children || [] };
}

function Button(props, ...children) {
  return { type: "Button", props: props || {}, children: children || [] };
}

// ── Convenience components ────────────────────────────────────────────

function Header(title, subtitle) {
  return View({ style: { padding: 24, background: 0xFF1E293B } },
    Text({ style: { fontSize: 32, fontWeight: "bold", color: 0xFFF8FAFC } }, title),
    subtitle
      ? Text({ style: { fontSize: 14, color: 0xFF94A3B8, marginTop: 4 } }, subtitle)
      : null
  );
}

function StatCard(value, label, desc) {
  return View({ style: { padding: 16, background: 0xFF1E293B, borderRadius: 12,
      borderWidth: 1, borderColor: 0xFF334155, width: 160 } },
    Text({ style: { fontSize: 26, fontWeight: "bold", color: 0xFF4ADE80 } }, value),
    Text({ style: { fontSize: 12, color: 0xFF64748B } }, label),
    desc
      ? Text({ style: { fontSize: 13, color: 0xFFCBD5E1, marginTop: 4 } }, desc)
      : null
  );
}

function Card(title, body) {
  return View({ style: { padding: 16, background: 0xFF1E293B, borderRadius: 12,
      borderWidth: 1, borderColor: 0xFF334155, width: 160 } },
    Text({ style: { fontSize: 16, fontWeight: "bold", color: 0xFF38BDF8 } }, title),
    body
      ? Text({ style: { fontSize: 13, color: 0xFFCBD5E1, marginTop: 4 } }, body)
      : null
  );
}

function Footer(text) {
  return Text({ style: { fontSize: 12, color: 0xFF64748B, textAlign: "center",
      padding: 16 } }, text);
}

// ── Default styles ────────────────────────────────────────────────────

var DEFAULT_VIEW = {
  background: 0, padding: 0, margin: 0, borderRadius: 0,
  borderWidth: 0, borderColor: 0, width: 0, height: 0,
  flexDirection: "row", justifyContent: "flex-start",
  alignItems: "stretch", gap: 0
};

var DEFAULT_TEXT = {
  fontSize: 16, color: 0xFF1B1B1B, fontWeight: "normal",
  textAlign: "left"
};

var DEFAULT_BUTTON = {
  background: 0xFF2196F3, color: 0xFFFFFFFF, fontSize: 15,
  fontWeight: "bold", borderRadius: 8, padding: 12, margin: 0,
  borderWidth: 1, borderColor: 0xFF1976D2, textAlign: "center"
};

function merge(base, over) {
  var r = {};
  for (var k in base) r[k] = base[k];
  if (over) for (var k in over) r[k] = over[k];
  return r;
}

// ── Flex Layout Engine ────────────────────────────────────────────────

function layout(node, x, y, availW) {
  var s = node._style;
  var padL = s.padding || 0, padR = s.padding || 0;
  var padT = s.padding || 0, padB = s.padding || 0;
  var borW = s.borderWidth || 0;
  var cx = x + padL + borW;
  var cy = y + padT + borW;
  var cw = availW - 2 * (padL + borW);
  if (cw < 0) cw = 0;

  var children = (node.children || []).filter(function(c) { return c != null; });
  var isFlex = node.type === "View" || node.type === "Button";
  var isRow = s.flexDirection === "row";
  var gap = s.gap || 0;

  // Text nodes: measure and set size
  if (node.type === "Text") {
    var txt = node.children.filter(function(c) { return typeof c === "string"; }).join("");
    node._text = txt;
    var fs = s.fontSize || 16;
    node._w = measureText(txt, fs);
    node._h = fs * 1.4;
    node._x = x;
    node._y = y;
    return;
  }

  // Leaf Button (no children View/Text)
  if (node.type === "Button") {
    var btnText = node.children.filter(function(c) { return typeof c === "string"; }).join("");
    var btnFs = s.fontSize || 15;
    node._text = btnText;
    node._w = (s.width > 0) ? s.width : (measureText(btnText, btnFs) + 2 * (s.padding || 12));
    node._h = (s.height > 0) ? s.height : (btnFs * 1.4 + 2 * (s.padding || 12));
    node._x = x;
    node._y = y;
    return;
  }

  // Layout children recursively
  var visible = children.filter(function(c) {
    return typeof c !== "string" || c.trim().length > 0;
  });

  if (isRow) {
    // ── FLEX ROW ──
    var totalGap = gap * Math.max(0, visible.length - 1);
    var widths = [];
    var totalIntrinsic = 0;
    for (var i = 0; i < visible.length; i++) {
      var c = visible[i];
      var cs = c._style || {};
      var cw_i = (cs.width > 0) ? cs.width : cw / Math.max(1, visible.length);
      widths.push(cw_i);
      totalIntrinsic += cw_i;
    }

    var flexAvail = cw - totalGap;
    var scale = (totalIntrinsic > 0 && totalIntrinsic !== flexAvail)
        ? flexAvail / totalIntrinsic : 1;

    var startX = cx;
    var totalUsed = totalIntrinsic * scale + totalGap;
    if (s.justifyContent === "center") startX = cx + (flexAvail - totalUsed) / 2;
    else if (s.justifyContent === "flex-end") startX = cx + flexAvail - totalUsed;

    var cursor = startX;
    var maxH = 0;

    for (var i = 0; i < visible.length; i++) {
      var c = visible[i];
      var cs = c._style || {};
      var childW = widths[i] * scale;
      layout(c, cursor, cy, childW);
      var childBH = c._h + 2 * ((cs.padding || 0) + (cs.borderWidth || 0));

      // align-items (cross axis)
      if (s.alignItems === "center") {
        c._y = cy + (cw > 0 ? (s.height > 0 ? s.height : childBH) : 0) / 2 - childBH / 2;
      } else if (s.alignItems === "flex-end") {
        // stretch is default
      } else {
        c._y = cy;
      }

      cursor += childW + gap;
      if (childBH > maxH) maxH = childBH;
    }

    node._x = x;
    node._y = y;
    node._w = (s.width > 0) ? s.width : cw;
    node._h = (s.height > 0) ? s.height : maxH;

  } else {
    // ── FLEX COLUMN (default) ──
    var cursorY = cy;
    for (var i = 0; i < visible.length; i++) {
      var c = visible[i];
      var cs = c._style || {};
      var childW = (cs.width > 0) ? cs.width : cw;
      layout(c, cx, cursorY, childW);
      var childBH = c._h + 2 * ((cs.padding || 0) + (cs.borderWidth || 0));
      cursorY += childBH + gap;
    }

    node._x = x;
    node._y = y;
    node._w = (s.width > 0) ? s.width : cw;
    node._h = (s.height > 0) ? s.height : Math.max(0, cursorY - cy - gap);
  }
}

// ── Renderer ──────────────────────────────────────────────────────────

function renderNode(handle, node, depth) {
  if (node == null) return;
  if (typeof node === "string") return;
  var s = node._style || {};
  var x = node._x || 0;
  var y = node._y || 0;
  var w = node._w || 0;
  var h = node._h || 0;

  // Background
  if (s.background && s.background !== 0) {
    if (s.borderRadius && s.borderRadius > 0) {
      fillRoundRect(handle, x, y, w, h, s.borderRadius, s.borderRadius, s.background);
    } else {
      fillRect(handle, x, y, w, h, s.background);
    }
  }

  // Border
  if (s.borderWidth && s.borderWidth > 0 && s.borderColor) {
    if (s.borderRadius && s.borderRadius > 0) {
      drawRoundRect(handle, x, y, w, h, s.borderRadius, s.borderRadius, s.borderColor, s.borderWidth);
    } else {
      drawRect(handle, x, y, w, h, s.borderColor, s.borderWidth);
    }
  }

  // Text
  if (node.type === "Text" && node._text) {
    var fs = s.fontSize || 16;
    var color = s.color || 0xFF1B1B1B;
    var bold = s.fontWeight === "bold";
    var tx = x;
    if (s.textAlign === "center") tx = x + (w - node._w) / 2;
    else if (s.textAlign === "right") tx = x + w - node._w;
    var ty = y + fs;
    if (bold) {
      drawText(handle, node._text, tx + 1, ty, color, fs);
    }
    drawText(handle, node._text, tx, ty, color, fs);
  }

  // Button text
  if (node.type === "Button" && node._text) {
    var fs = s.fontSize || 15;
    var color = s.color || 0xFFFFFFFF;
    var tx = x + (w - node._w + 2 * (s.padding || 12)) / 2;
    var ty = y + (s.padding || 12) + fs;
    drawText(handle, node._text, tx, ty, color, fs);
  }

  // Recurse children
  var kids = node.children || [];
  for (var i = 0; i < kids.length; i++) {
    if (typeof kids[i] !== "string") renderNode(handle, kids[i], depth + 1);
  }
}

// ── Public API ────────────────────────────────────────────────────────

function render(handle, root, vpW, vpH) {
  clear(handle, root._style ? (root._style.background || 0xFF0F172A) : 0xFF0F172A);
  layout(root, 0, 0, vpW);
  renderNode(handle, root, 0);
}

function createApp(rootComponent, vpW, vpH) {
  var root = rootComponent();
  layout(root, 0, 0, vpW);
  return { root: root, vpW: vpW, vpH: vpH };
}
