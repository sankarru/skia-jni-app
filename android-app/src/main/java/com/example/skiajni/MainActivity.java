package com.example.skiajni;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.nio.ByteBuffer;

import com.example.skiajni.SkiaUi.*;

/**
 * Skia UI demo — a real widget screen (Text, EditText, Button, Cards)
 * rendered entirely through the Skia JNI .so, with touch + soft keyboard.
 */
public class MainActivity extends Activity {

    private int W, H;
    private int cutoutTop, cutoutLeft, cutoutRight; // safe-area insets (px)
    private ImageView imageView;
    private TextView status;
    private SkiaCanvas canvas;
    private Bitmap bitmap;
    private String keystrokes = "";
    private EditText field;
    private Button submitBtn, clearBtn, colorBtn;
    private boolean darkMode = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Fullscreen immersive + draw under the cutout (notch)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
        }
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);

        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        W = dm.widthPixels;
        H = dm.heightPixels;

        // Compute safe-area insets (status bar + display cutout)
        computeSafeInsets();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        imageView = new ImageView(this);
        imageView.setScaleType(ImageView.ScaleType.FIT_XY);
        root.addView(imageView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        status = new TextView(this);
        status.setTextColor(Color.argb(180, 255, 255, 255));
        status.setTextSize(12);
        status.setShadowLayer(3, 1, 1, Color.BLACK);
        status.setPadding(16, cutoutTop + 8, 16, 8);
        root.addView(status, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.LEFT));

        setContentView(root);

        imageView.setOnTouchListener((v, ev) -> { handleTouch(ev); return true; });

        render();
    }

    private void handleTouch(MotionEvent ev) {
        float tx = ev.getX() / imageView.getWidth() * W;
        float ty = ev.getY() / imageView.getHeight() * H;

        switch (ev.getAction()) {
            case MotionEvent.ACTION_DOWN:
                if (field.contains(tx, ty)) {
                    field.focused = true;
                    showKeyboard();
                } else {
                    field.focused = false;
                }
                if (submitBtn.contains(tx, ty) || colorBtn.contains(tx, ty)
                        || clearBtn.contains(tx, ty)) {
                    if (submitBtn.contains(tx, ty)) submitBtn.pressed = true;
                    if (colorBtn.contains(tx, ty)) colorBtn.pressed = true;
                    if (clearBtn.contains(tx, ty)) clearBtn.pressed = true;
                }
                render();
                break;
            case MotionEvent.ACTION_UP:
                submitBtn.pressed = colorBtn.pressed = clearBtn.pressed = false;
                if (submitBtn.contains(tx, ty)) onSubmit();
                else if (colorBtn.contains(tx, ty)) onToggleColor();
                else if (clearBtn.contains(tx, ty)) onClear();
                render();
                break;
        }
        return;
    }

    private void onSubmit() {
        keystrokes = "Submitted: \"" + field.text + "\"";
    }

    private void onClear() {
        field.text = "";
        keystrokes = "Cleared";
    }

    private void onToggleColor() {
        darkMode = !darkMode;
        keystrokes = darkMode ? "Theme: Dark" : "Theme: Light";
    }

    private void showKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.toggleSoftInput(InputMethodManager.SHOW_IMPLICIT, 0);
    }

    /** Compute safe-area insets from system bars + display cutout. */
    private void computeSafeInsets() {
        final View decor = getWindow().getDecorView();
        decor.post(() -> {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                android.graphics.Insets si = decor.getRootWindowInsets().getInsets(
                        android.view.WindowInsets.Type.systemBars()
                        | android.view.WindowInsets.Type.displayCutout());
                cutoutTop = si.top;
                cutoutLeft = si.left;
                cutoutRight = si.right;
            } else {
                android.graphics.Rect rect = new android.graphics.Rect();
                decor.getWindowVisibleDisplayFrame(rect);
                cutoutTop = rect.top;
            }
            render();
        });
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
            computeSafeInsets();
        }
    }

    /** Called by the soft keyboard (hooked via onKeyDown in Activity). */
    @Override
    public boolean onKeyDown(int keyCode, android.view.KeyEvent event) {
        if (field == null || !field.focused) return super.onKeyDown(keyCode, event);
        int unicodeChar = event.getUnicodeChar();
        if (unicodeChar != 0 && keyCode != android.view.KeyEvent.KEYCODE_DEL) {
            field.text += (char) unicodeChar;
            render();
            return true;
        }
        if (keyCode == android.view.KeyEvent.KEYCODE_DEL && field.text.length() > 0) {
            field.text = field.text.substring(0, field.text.length() - 1);
            render();
            return true;
        }
        if (keyCode == android.view.KeyEvent.KEYCODE_ENTER) {
            field.focused = false;
            onSubmit();
            render();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void render() {
        long t0 = System.nanoTime();
        try {
            if (canvas != null) canvas.close();
            canvas = new SkiaCanvas(W, H);
            drawUi(canvas);
            byte[] px = canvas.getPixels();
            long dt = (System.nanoTime() - t0) / 1_000_000;

            if (px != null) {
                if (bitmap != null) bitmap.recycle();
                bitmap = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
                bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(px));
                imageView.setImageBitmap(bitmap);
            }
            status.setText("Skia UI  ·  " + W + "x" + H + "  ·  " + dt + " ms"
                    + "  ·  " + (darkMode ? "dark" : "light"));
        } catch (Throwable t) {
            status.setText("Error: " + t.getMessage());
        }
    }

    // ── Build the widget tree and draw it via Skia ──────────────────
    private void drawUi(SkiaCanvas c) {
        int bg = darkMode ? 0xFF121212 : 0xFFECEFF1;
        int cardBg = darkMode ? 0xFF1E1E1E : 0xFFFFFFFF;
        int labelCol = darkMode ? 0xFFFFFFFF : 0xFF37474F;
        c.clear(bg);

        float margin = W * 0.06f;
        float cardW = W - margin * 2;
        // Start below the status bar / cutout
        float topInset = Math.max(cutoutTop, 24);
        float y = topInset + H * 0.03f;

        // Header card
        SkiaUi.Card header = new SkiaUi.Card(margin, y, cardW, H * 0.14f, cardBg);
        header.draw(c);
        c.drawText("Skia UI Toolkit", margin + 30, y + H * 0.06f, labelCol, 54);
        c.drawText("Text · EditText · Buttons · Cards",
                margin + 30, y + H * 0.11f, darkMode ? 0xFFB0BEC5 : 0xFF78909C, 30);

        // Form card
        float y2 = y + H * 0.14f + H * 0.02f;
        SkiaUi.Card form = new SkiaUi.Card(margin, y2, cardW, H * 0.40f, cardBg);
        form.draw(c);

        field = new EditText(margin + 30, y2 + H * 0.08f, cardW - 60, H * 0.07f);

        float btnW = (cardW - 60 - 40) / 3f;
        submitBtn = new Button("Submit", margin + 30, y2 + H * 0.22f,
                btnW, H * 0.06f, 0xFF2196F3);
        colorBtn = new Button("Theme", margin + 50 + btnW, y2 + H * 0.22f,
                btnW, H * 0.06f, 0xFF9C27B0);
        clearBtn = new Button("Clear", margin + 70 + btnW * 2, y2 + H * 0.22f,
                btnW, H * 0.06f, 0xFFE53935);

        c.drawText("Your name", margin + 30, y2 + H * 0.055f, labelCol, 30);
        field.draw(c);
        submitBtn.draw(c);
        colorBtn.draw(c);
        clearBtn.draw(c);

        // Output card
        float y3 = y2 + H * 0.40f + H * 0.02f;
        SkiaUi.Card out = new SkiaUi.Card(margin, y3, cardW, H * 0.28f, cardBg);
        out.draw(c);
        c.drawText("Output", margin + 30, y3 + H * 0.05f, labelCol, 34);
        c.drawText(keystrokes.isEmpty() ? "  " : keystrokes,
                margin + 30, y3 + H * 0.16f,
                darkMode ? 0xFF80D8FF : 0xFF0277BD, 40);
        c.drawText("Skia is drawing everything here.",
                margin + 30, y3 + H * 0.22f,
                darkMode ? 0xFFB0BEC5 : 0xFF78909C, 28);

        // Image card — decoded + drawn by Skia JNI
        float y4 = y3 + H * 0.28f + H * 0.02f;
        SkiaUi.Card imgCard = new SkiaUi.Card(margin, y4, cardW, H * 0.30f, cardBg);
        imgCard.draw(c);
        c.drawText("Skia-decode + drawImage (JNI)", margin + 30, y4 + H * 0.05f,
                labelCol, 30);
        try {
            byte[] png = readAsset("sample.png");
            long img = c.createImage(png);
            if (img != 0) {
                float iw = imgCard.w * 0.4f;
                float ih = iw * c.imageHeight(img) / c.imageWidth(img);
                float ix = margin + 30;
                float iy = y4 + H * 0.07f;
                c.drawImageRounded(img, ix, iy, iw, ih, 16);
                c.drawText("w=" + c.imageWidth(img) + " h=" + c.imageHeight(img),
                        ix + iw + 24, iy + H * 0.05f, labelCol, 30);
                c.destroyImage(img);
            }
        } catch (Exception e) {
            c.drawText("img error: " + e.getMessage(), margin + 30, y4 + H * 0.15f,
                    0xFFE53935, 28);
        }
    }

    private byte[] readAsset(String name) {
        try {
            java.io.InputStream is = getAssets().open(name);
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
            is.close();
            return bos.toByteArray();
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }
}