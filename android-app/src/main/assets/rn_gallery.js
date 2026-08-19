// rn_gallery.js — Infinite image scroller runtime for Hermes + Skia.
// Loaded once by RnGalleryActivity; each frame Java calls render(scrollY).
// Images are loaded asynchronously via loadImage() bridge.

var _W = 0, _H = 0;
var _images = [];   // { handle, loading, label }
var _cardH = 0;
var _margin = 0;
var _cardW = 0;
var _gap = 8;
var _placeholderColor = 0xFF2A2A33;

function galleryInit(handle, w, h) {
    _W = w; _H = h;
    _margin = Math.round(w * 0.04);
    _cardW = w - _margin * 2;
    _cardH = Math.round(h * 0.22);
    clear(handle, 0xFF101014);
}

function galleryLoadImage(index, url, label) {
    while (_images.length <= index) {
        _images.push({ handle: 0, loading: false, label: '' });
    }
    var item = _images[index];
    item.loading = true;
    item.label = label || '';
    loadImage(url, function(imgH) {
        item.handle = imgH;
        item.loading = false;
    });
}

function galleryGetCount() { return _images.length; }
function galleryGetCardHeight() { return _cardH + _gap; }

function render(handle, scrollY) {
    clear(handle, 0xFF101014);

    var ch = _cardH + _gap;
    var first = Math.floor(scrollY / ch);
    var last = Math.floor((scrollY + _H) / ch) + 1;
    if (first < 0) first = 0;

    for (var i = first; i <= last && i < _images.length; i++) {
        var cardY = Math.round(i * ch - scrollY);
        var item = _images[i];
        var mx = _margin;
        var cw = _cardW;
        var radius = 12;

        // Card background
        fillRoundRect(handle, mx, cardY, cw, _cardH, radius, radius, 0xFF1E1E24);
        drawRoundRect(handle, mx, cardY, cw, _cardH, radius, radius, 0xFF33333C, 2);

        // Image area
        var imgX = mx + 8;
        var imgY = cardY + 8;
        var imgW = cw - 16;
        var imgH = _cardH - 44;

        if (item.handle) {
            drawImage(handle, item.handle, imgX, imgY, imgW, imgH);
        } else {
            fillRoundRect(handle, imgX, imgY, imgW, imgH, 8, 8, _placeholderColor);
            var txt = item.loading ? 'loading...' : 'no image';
            drawText(handle, txt, imgX + imgW / 2 - 40, imgY + imgH / 2 + 6, 0xFF888899, 20);
        }

        // Label
        var label = item.label || ('Image #' + (i + 1));
        drawText(handle, label, imgX, cardY + _cardH - 16, 0xFFCCCCCC, 18);
    }

    // Loading indicator at bottom
    var contentH = _images.length * ch;
    if (scrollY + _H > contentH - _H * 0.3) {
        drawText(handle, 'loading more...', _W / 2 - 50, _H - 30, 0xFF666677, 18);
    }
}
