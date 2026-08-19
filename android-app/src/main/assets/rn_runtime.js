// ── React Native-style renderer for Hermes + Skia ──
// Components (View/Text/Button) are laid out with the Yoga flexbox engine
// (the same layout engine React Native uses) and drawn via Skia host
// functions. Text is pre-measured in JS and given explicit sizes.

// ── Default styles ────────────────────────────────────────────────────

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
  background: 0xFF2563EB, color: 0xFFFFFFFF, fontSize: 16,
  fontWeight: "bold", borderRadius: 10, padding: 16, margin: 0,
  borderWidth: 0, borderColor: 0, textAlign: "center",
  alignItems: "center", justifyContent: "center"
};

function merge(base, over) {
  var r = {};
  for (var k in base) r[k] = base[k];
  if (over) for (var k in over) r[k] = over[k];
  return r;
}

// Resolve the four margins from a style (shorthand `margin` or per-side).
function margins(s) {
  var m = s.margin || 0;
  return {
    l: (s.marginLeft !== undefined) ? s.marginLeft : m,
    r: (s.marginRight !== undefined) ? s.marginRight : m,
    t: (s.marginTop !== undefined) ? s.marginTop : m,
    b: (s.marginBottom !== undefined) ? s.marginBottom : m
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

// ── Convenience components ────────────────────────────────────────────

function Header(title, subtitle) {
  return View({ style: { padding: 24, paddingTop: 16, paddingBottom: 20, background: 0xFF1E40AF } },
    Text({ style: { fontSize: 28, fontWeight: "bold", color: 0xFFFFFFFF } }, title),
    subtitle
      ? Text({ style: { fontSize: 14, color: 0xFFBFDBFE, marginTop: 6 } }, subtitle)
      : null
  );
}

function StatCard(value, label, desc) {
  return View({ style: { padding: 16, background: 0xFFFFFFFF, borderRadius: 12,
      borderWidth: 1, borderColor: 0xFFE2E8F0, flexGrow: 1 } },
    Text({ style: { fontSize: 24, fontWeight: "bold", color: 0xFF16A34A } }, value),
    Text({ style: { fontSize: 12, color: 0xFF64748B, marginTop: 4 } }, label),
    desc
      ? Text({ style: { fontSize: 12, color: 0xFF94A3B8, marginTop: 4 } }, desc)
      : null
  );
}

function Card(title, body) {
  return View({ style: { padding: 16, background: 0xFFFFFFFF, borderRadius: 12,
      borderWidth: 1, borderColor: 0xFFE2E8F0, flexGrow: 1 } },
    Text({ style: { fontSize: 16, fontWeight: "bold", color: 0xFF1E40AF } }, title),
    body
      ? Text({ style: { fontSize: 13, color: 0xFF475569, marginTop: 4 } }, body)
      : null
  );
}

function Footer(text) {
  return Text({ style: { fontSize: 12, color: 0xFF64748B, textAlign: "center",
      marginTop: 24 } }, text);
}

// ── Yoga layout tree construction ─────────────────────────────────────

// Build a Yoga node for `node` and its subtree. Returns the YGNode handle.
function buildYoga(node) {
  var s = node._style;
  var yn = ygNewNode();
  node._yoga = yn;

  // Per-side margins
  var mg = margins(s);

  if (node.type === "Text") {
    var txt = node.children.filter(function(c) { return typeof c === "string"; }).join("");
    node._text = txt;
    var fs = s.fontSize || 16;
    var tw = measureText(txt, fs);
    ygSetWidth(yn, tw);
    ygSetHeight(yn, fs * 1.4);
    ygSetMargin(yn, mg.l); // horizontal margin approximated via all-edge set
    if (mg.t !== mg.l || mg.b !== mg.l || mg.r !== mg.l) {
      ygSetMarginTop(yn, mg.t);
      ygSetMarginBottom(yn, mg.b);
      ygSetMarginLeft(yn, mg.l);
      ygSetMarginRight(yn, mg.r);
    }
    node._fs = fs;
    node._color = s.color;
    node._bold = s.fontWeight === "bold";
    node._align = s.textAlign || "left";
    return yn;
  }

  if (node.type === "Button") {
    var bt = node.children.filter(function(c) { return typeof c === "string"; }).join("");
    node._text = bt;
    var bfs = s.fontSize || 15;
    var contentW = measureText(bt, bfs);
    var contentH = bfs * 1.4;
    var pad = s.padding || 12, bor = s.borderWidth || 1;
    if (s.width > 0) ygSetWidth(yn, s.width);
    else ygSetWidth(yn, contentW + 2 * (pad + bor));
    if (s.height > 0) ygSetHeight(yn, s.height);
    else ygSetHeight(yn, contentH + 2 * (pad + bor));
    ygSetPadding(yn, pad);
    ygSetBorder(yn, bor);
    ygSetAlignItems(yn, "center");
    ygSetJustifyContent(yn, "center");
    node._fs = bfs;
    node._color = s.color;
    return yn;
  }

  // View
  ygSetFlexDirection(yn, s.flexDirection || "column");
  ygSetJustifyContent(yn, s.justifyContent || "flex-start");
  ygSetAlignItems(yn, s.alignItems || "stretch");
  ygSetGap(yn, s.gap || 0);
  ygSetPadding(yn, s.padding || 0);
  ygSetBorder(yn, s.borderWidth || 0);
  if (s.width > 0) ygSetWidth(yn, s.width);
  if (s.height > 0) ygSetHeight(yn, s.height);
  if (s.flexGrow) ygSetFlexGrow(yn, s.flexGrow);
  if (s.flexShrink !== undefined) ygSetFlexShrink(yn, s.flexShrink);
  ygSetMargin(yn, mg.l);
  if (mg.t !== mg.l || mg.b !== mg.l || mg.r !== mg.l) {
    ygSetMarginTop(yn, mg.t);
    ygSetMarginBottom(yn, mg.b);
    ygSetMarginLeft(yn, mg.l);
    ygSetMarginRight(yn, mg.r);
  }

  var kids = node.children.filter(function(c) {
    return c != null && typeof c !== "string";
  });
  for (var i = 0; i < kids.length; i++) {
    ygInsertChild(yn, buildYoga(kids[i]), i);
  }
  return yn;
}

// ── Renderer ──────────────────────────────────────────────────────────

function renderNode(handle, node, absX, absY, topInset) {
  if (node == null || node._yoga == null) return;
  var s = node._style || {};
  var yn = node._yoga;
  var x = absX + ygGetLeft(yn);
  var y = absY + ygGetTop(yn) + topInset;
  var w = ygGetWidth(yn);
  var h = ygGetHeight(yn);

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
    var fs = node._fs || 16;
    var color = node._color || 0xFF1B1B1B;
    var tx = x;
    if (node._align === "center") tx = x + (w - ygGetWidth(yn)) / 2;
    else if (node._align === "right") tx = x + w - ygGetWidth(yn);
    var ty = y + fs;
    if (node._bold) drawText(handle, node._text, tx + 1, ty, color, fs);
    drawText(handle, node._text, tx, ty, color, fs);
  }

  // Button text (centered by Yoga)
  if (node.type === "Button" && node._text) {
    var fs = node._fs || 15;
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
      renderNode(handle, kids[i], x, y, topInset);
    }
  }
}

// ── Public API ────────────────────────────────────────────────────────

function render(handle, root, vpW, vpH, topInset) {
  topInset = topInset || 0;
  clear(handle, root._style ? (root._style.background || 0xFF0F172A) : 0xFF0F172A);
  var rootY = buildYoga(root);
  ygSetWidth(rootY, vpW);
  ygSetHeight(rootY, vpH);
  ygCalculateLayout(rootY, vpW, vpH, 0);
  renderNode(handle, root, 0, 0, topInset);
  ygFreeRecursive(rootY);
}
