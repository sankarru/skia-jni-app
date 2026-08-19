// ── Good Vibes — React Native-style renderer for Hermes + Skia ──
// Components laid out with Yoga flexbox, drawn via Skia host functions.

// ── Default styles ────────────────────────────────────────────────────

// Density scale: layout dimensions are in physical px (S=1, canvas is 1080x2400).
// Font sizes are scaled by FS so text is properly readable on the high-density
// (420dpi) screen — without enlarging the whole layout off-screen.
var S = 1;
var FS = 2.6;

var DEFAULT_VIEW = {
  background: 0, padding: 0, margin: 0, borderRadius: 0,
  borderWidth: 0, borderColor: 0, width: 0, height: 0,
  flexDirection: "column", justifyContent: "flex-start",
  alignItems: "stretch", gap: 0
};

var DEFAULT_TEXT = {
  fontSize: 16, color: 0xFF1B1B1B, fontWeight: "normal",
  textAlign: "left"
};

var DEFAULT_BUTTON = {
  background: 0xFF7C3AED, color: 0xFFFFFFFF, fontSize: 16,
  fontWeight: "bold", borderRadius: 12, padding: 16, margin: 0,
  borderWidth: 0, borderColor: 0, textAlign: "center",
  alignItems: "center", justifyContent: "center"
};

function merge(base, over) {
  var r = {};
  for (var k in base) r[k] = base[k];
  if (over) for (var k in over) r[k] = over[k];
  return r;
}

function margins(s) {
  var m = (s.margin || 0) * S;
  return {
    l: (s.marginLeft !== undefined) ? s.marginLeft * S : m,
    r: (s.marginRight !== undefined) ? s.marginRight * S : m,
    t: (s.marginTop !== undefined) ? s.marginTop * S : m,
    b: (s.marginBottom !== undefined) ? s.marginBottom * S : m
  };
}

// ── Component constructors ────────────────────────────────────────────

function View(props, ...children) {
  props = props || {};
  return { type: "View", props: props, _style: merge(DEFAULT_VIEW, props.style),
    children: children || [] };
}

function Text(props, ...children) {
  props = props || {};
  return { type: "Text", props: props, _style: merge(DEFAULT_TEXT, props.style),
    children: children || [] };
}

function Button(props, ...children) {
  props = props || {};
  return { type: "Button", props: props, _style: merge(DEFAULT_BUTTON, props.style),
    children: children || [] };
}

// ── Custom primitives ─────────────────────────────────────────────────

function ProgressBar(value, max, color, trackColor, h) {
  value = value || 0;
  max = max || 100;
  color = color || 0xFF7C3AED;
  trackColor = trackColor || 0xFFE2E8F0;
  h = (h || 10) * S;
  var pct = Math.min(value / max, 1) * 100;
  return View({ style: { height: h, borderRadius: h / 2, background: trackColor } },
    View({ style: { height: h, borderRadius: h / 2, background: color,
        widthPercent: Math.floor(pct) } })
  );
}

function Divider() {
  return View({ style: { height: 1 * S, background: 0xFFE2E8F0, marginTop: 12 * S, marginBottom: 12 * S } });
}

function SectionTitle(text) {
  return Text({ style: { fontSize: 14, fontWeight: "bold", color: 0xFF94A3B8,
    letterSpacing: 2 } }, text);
}

function Badge(text, bgColor, textColor) {
  return View({ style: { background: bgColor || 0xFFEDE9FE, borderRadius: 8 * S,
      padding: 6 * S, paddingLeft: 10 * S, paddingRight: 10 * S } },
    Text({ style: { fontSize: 12, fontWeight: "bold", color: textColor || 0xFF7C3AED } }, text)
  );
}

function StatPill(value, label, color) {
  return View({ style: { background: 0xFFFFFFFF, borderRadius: 18 * S, padding: 28 * S,
      borderWidth: 1 * S, borderColor: 0xFFF1F5F9, flexGrow: 1, alignItems: "center" } },
    Text({ style: { fontSize: 34, fontWeight: "bold", color: color || 0xFF7C3AED } }, value),
    Text({ style: { fontSize: 13, color: 0xFF94A3B8, marginTop: 6 } }, label)
  );
}

function HabitItem(emoji, name, done, color) {
  color = done ? (color || 0xFF10B981) : 0xFFCBD5E1;
  var bg = done ? 0xFFECFDF5 : 0xFFF8FAFC;
  return View({ style: { flexDirection: "row", alignItems: "center", padding: 22 * S,
      background: bg, borderRadius: 14 * S, gap: 16 * S } },
    View({ style: { width: 54 * S, height: 54 * S, borderRadius: 27 * S, background: color,
        alignItems: "center", justifyContent: "center" } },
      Text({ style: { fontSize: 20 } }, emoji)
    ),
    Text({ style: { fontSize: 17, color: 0xFF334155, fontWeight: done ? "bold" : "normal" } }, name),
    done
      ? Badge("done", 0xFFDCFCE7, 0xFF16A34A)
      : Badge("pending", 0xFFF1F5F9, 0xFF94A3B8)
  );
}

// ── Yoga layout tree construction ─────────────────────────────────────

function buildYoga(node) {
  var s = node._style;
  var yn = ygNewNode();
  node._yoga = yn;

  var mg = margins(s);

  if (node.type === "Text") {
    var txt = node.children.filter(function(c) { return typeof c === "string"; }).join("");
    node._text = txt;
    var fs = (s.fontSize || 16) * FS;
    var tw = measureText(txt, fs);
    var textBold = s.fontWeight === "bold";
    if (textBold) tw = tw + txt.length * 0.8;
    ygSetWidth(yn, tw);
    ygSetHeight(yn, fs * 1.4);
    ygSetMargin(yn, mg.l);
    if (mg.t !== mg.l || mg.b !== mg.l || mg.r !== mg.l) {
      ygSetMarginTop(yn, mg.t);
      ygSetMarginBottom(yn, mg.b);
      ygSetMarginLeft(yn, mg.l);
      ygSetMarginRight(yn, mg.r);
    }
    node._fs = fs;
    node._color = s.color;
    node._bold = textBold;
    node._align = s.textAlign || "left";
    node._letterSpacing = s.letterSpacing || 0;
    if (s.paddingTop !== undefined) ygSetPaddingTop(yn, s.paddingTop * S);
    if (s.paddingBottom !== undefined) ygSetPaddingBottom(yn, s.paddingBottom * S);
    return yn;
  }

  if (node.type === "Button") {
    var bt = node.children.filter(function(c) { return typeof c === "string"; }).join("");
    node._text = bt;
    var bfs = (s.fontSize || 16) * FS;
    var contentW = measureText(bt, bfs) + bt.length * 0.8;
    var contentH = bfs * 1.4;
    var pad = (s.padding || 16) * S, bor = (s.borderWidth || 0) * S;
    if (s.width > 0) ygSetWidth(yn, s.width * S);
    else if (s.widthPercent > 0) ygSetWidthPercent(yn, s.widthPercent);
    else ygSetWidth(yn, contentW + 2 * (pad + bor));
    if (s.height > 0) ygSetHeight(yn, s.height * S);
    else ygSetHeight(yn, contentH + 2 * (pad + bor));
    ygSetPadding(yn, pad);
    ygSetBorder(yn, bor);
    ygSetAlignItems(yn, "center");
    ygSetJustifyContent(yn, "center");
    node._fs = bfs;
    node._color = s.color;
    node._radius = (s.borderRadius || 0) * S;
    return yn;
  }

  // View
  ygSetFlexDirection(yn, s.flexDirection || "column");
  ygSetJustifyContent(yn, s.justifyContent || "flex-start");
  ygSetAlignItems(yn, s.alignItems || "stretch");
  ygSetGap(yn, (s.gap || 0) * S);
  ygSetPadding(yn, (s.padding || 0) * S);
  if (s.paddingTop !== undefined) ygSetPaddingTop(yn, s.paddingTop * S);
  if (s.paddingBottom !== undefined) ygSetPaddingBottom(yn, s.paddingBottom * S);
  if (s.paddingLeft !== undefined) ygSetPaddingLeft(yn, s.paddingLeft * S);
  if (s.paddingRight !== undefined) ygSetPaddingRight(yn, s.paddingRight * S);
  ygSetBorder(yn, (s.borderWidth || 0) * S);
  if (s.width > 0) ygSetWidth(yn, s.width * S);
  else if (s.widthPercent > 0) ygSetWidthPercent(yn, s.widthPercent);
  if (s.height > 0) ygSetHeight(yn, s.height * S);
  if (s.flexGrow) ygSetFlexGrow(yn, s.flexGrow);
  if (s.flexShrink !== undefined) ygSetFlexShrink(yn, s.flexShrink);
  ygSetMargin(yn, mg.l);
  if (mg.t !== mg.l || mg.b !== mg.l || mg.r !== mg.l) {
    ygSetMarginTop(yn, mg.t);
    ygSetMarginBottom(yn, mg.b);
    ygSetMarginLeft(yn, mg.l);
    ygSetMarginRight(yn, mg.r);
  }
  node._radius = (s.borderRadius || 0) * S;

  var kids = node.children.filter(function(c) {
    return c != null && typeof c !== "string";
  });
  for (var i = 0; i < kids.length; i++) {
    ygInsertChild(yn, buildYoga(kids[i]), i);
  }
  return yn;
}

// ── Renderer ──────────────────────────────────────────────────────────

function renderNode(handle, node, absX, absY) {
  if (node == null || node._yoga == null) return;
  var s = node._style || {};
  var yn = node._yoga;
  var x = absX + ygGetLeft(yn);
  var y = absY + ygGetTop(yn);
  var w = ygGetWidth(yn);
  var h = ygGetHeight(yn);

  // Background
  if (s.background && s.background !== 0) {
    if (node._radius > 0) {
      fillRoundRect(handle, x, y, w, h, node._radius, node._radius, s.background);
    } else {
      fillRect(handle, x, y, w, h, s.background);
    }
  }

  // Border
  var bw = (s.borderWidth || 0) * S;
  if (bw > 0 && s.borderColor) {
    if (node._radius > 0) {
      drawRoundRect(handle, x, y, w, h, node._radius, node._radius, s.borderColor, bw);
    } else {
      drawRect(handle, x, y, w, h, s.borderColor, bw);
    }
  }

  // Text
  if (node.type === "Text" && node._text) {
    var fs = node._fs || 16;
    var color = node._color || 0xFF1B1B1B;
    var tx = x;
    if (node._align === "center") tx = x + (w - ygGetWidth(yn)) / 2;
    else if (node._align === "right") tx = x + w - ygGetWidth(yn);
    var ty = y + fs;
    drawText(handle, node._text, tx, ty, color, fs);
  }

  // Button text
  if (node.type === "Button" && node._text) {
    var fs = node._fs || 16;
    var color = node._color || 0xFFFFFFFF;
    var bw = ygGetWidth(yn);
    var bh = ygGetHeight(yn);
    var tw = measureText(node._text, fs);
    var tx = x + (bw - tw) / 2;
    var ty = y + (bh + fs) / 2;
    drawText(handle, node._text, tx, ty, color, fs);
  }

  // Recurse children
  var kids = node.children || [];
  for (var i = 0; i < kids.length; i++) {
    if (kids[i] != null && typeof kids[i] !== "string") {
      renderNode(handle, kids[i], x, y);
    }
  }
}

// ── Public API ────────────────────────────────────────────────────────

function render(handle, root, vpW, vpH) {
  clear(handle, root._style ? (root._style.background || 0xFFF8FAFC) : 0xFFF8FAFC);
  var rootY = buildYoga(root);
  ygSetWidth(rootY, vpW);
  ygSetHeight(rootY, vpH);
  ygCalculateLayout(rootY, vpW, vpH, 0);
  renderNode(handle, root, 0, 0);
  ygFreeRecursive(rootY);
}
