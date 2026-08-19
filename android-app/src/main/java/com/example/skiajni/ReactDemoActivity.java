package com.example.skiajni;

import android.app.Activity;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

/** Good Vibes — a wellness dashboard rendered via Hermes + Skia + Yoga. */
public class ReactDemoActivity extends Activity {

    /** Draw content edge-to-edge behind the status bar and cutout. */
    private void configureEdgeToEdge() {
        getWindow().setDecorFitsSystemWindows(false);
        getWindow().setStatusBarColor(0x00000000);
        getWindow().setNavigationBarColor(0x00000000);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            android.view.WindowInsetsController ctrl = getWindow().getInsetsController();
            if (ctrl != null) {
                ctrl.hide(android.view.WindowInsets.Type.systemBars());
                ctrl.setSystemBarsBehavior(
                    android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        }
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
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);

        // Set decor fits false BEFORE building views so the window extends
        // behind the status bar and cutout.
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
        }

        // Full physical screen (edge-to-edge), including the status bar and
        // cutout areas so content can render behind them.
        android.util.DisplayMetrics real = new android.util.DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(real);
        final int W = real.widthPixels;
        final int H = real.heightPixels;

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFFF8FAFC);
        ImageView imageView = new ImageView(this);
        imageView.setScaleType(ImageView.ScaleType.FIT_XY);
        root.addView(imageView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        TextView status = new TextView(this);
        status.setTextColor(0x99FFFFFF);
        status.setTextSize(11);
        status.setShadowLayer(3, 1, 1, 0xFF000000);
        status.setPadding(12, 20, 12, 8);
        root.addView(status, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.LEFT));
        setContentView(root);
        configureEdgeToEdge();

        int[] top = {0};
        int[] bottom = {0};
        root.post(new Runnable() {
            @Override public void run() {
                WindowInsets insets = root.getRootWindowInsets();
                if (insets != null) {
                    int topInset = Math.max(
                            insets.getSystemWindowInsetTop(),
                            insets.getDisplayCutout() != null
                                    ? insets.getDisplayCutout().getSafeInsetTop() : 0);
                    int bottomInset = Math.max(
                            insets.getSystemWindowInsetBottom(),
                            insets.getDisplayCutout() != null
                                    ? insets.getDisplayCutout().getSafeInsetBottom() : 0);
                    top[0] = topInset;
                    bottom[0] = bottomInset;
                }
                render(W, H, top[0], bottom[0], imageView, status);
            }
        });
    }

    /** Convert Skia pixel bytes (RGBA) into an Android ARGB_8888 bitmap.
     *  Swaps the red and blue channels so colors render correctly. */
    private static Bitmap toBitmap(int w, int h, byte[] px) {
        return Pixels.toBitmap(w, h, px);
    }

    private void render(int W, int H, int topInset, int bottomInset,
                        ImageView imageView, TextView status) {
        try {
            long t0 = System.nanoTime();
            String runtime = loadAsset("rn_runtime.js");

            // Edge-to-edge: canvas spans the full physical screen. The header
            // extends up behind the status bar/cutout, and the footer clears the
            // system navigation bar.
            int topPad = topInset + 16;
            int bottomPad = bottomInset + 20;
            int headerPadTop = topInset + 14;

            try (SkiaCanvas canvas = new SkiaCanvas(W, H);
                 JsCanvas js = new JsCanvas(W, H)) {

                js.eval(runtime);

                // ── Scene definition ──────────────────────────────
                String jsCode =
                    "var root = render(_handle," +
                    "  View({ style: { background: 0xFFF8FAFC, padding: 0, gap: 0 } }," +
                    // ── Header (edge-to-edge, purple behind status bar) ──
                    "    View({ style: { background: 0xFF6D28D9, paddingTop: " + headerPadTop + "," +
                    "        paddingBottom: 48, paddingLeft: 24, paddingRight: 24 } }," +
                    "      Text({ style: { fontSize: 16, color: 0xFFC4B5FD } }, 'Good Morning')," +
                    "      Text({ style: { fontSize: 36, fontWeight: 'bold', color: 0xFFFFFFFF, marginTop: 6 } }, 'Alex')," +
                    "      View({ style: { flexDirection: 'row', gap: 10, marginTop: 18, alignItems: 'center' } }," +
                    "        Badge('MINDFUL', 0xFFEDE9FE, 0xFF7C3AED)," +
                    "        Text({ style: { fontSize: 14, color: 0xFFC4B5FD } }, 'Aug 19, 2026')" +
                    "      )" +
                    "    )," +
                    // ── Stats row ────────────────────────────────
                    "    View({ style: { flexDirection: 'row', gap: 14, padding: 20, marginTop: 10 } }," +
                    "      StatPill('7,243', 'Steps', 0xFF7C3AED)," +
                    "      StatPill('85', 'Mood', 0xFFF59E0B)," +
                    "      StatPill('12', 'Day Streak', 0xFF10B981)" +
                    "    )," +
                    // ── Quote card ───────────────────────────────
                    "    View({ style: { margin: 20, marginTop: 14, padding: 20, background: 0xFFFFFFFF," +
                    "        borderRadius: 16, borderWidth: 1, borderColor: 0xFFF1F5F9 } }," +
                    "      View({ style: { flexDirection: 'row', gap: 8, alignItems: 'center' } }," +
                    "        Badge('QUOTE', 0xFFFDF4FF, 0xFFA855F7)," +
                    "        Text({ style: { fontSize: 12, color: 0xFF94A3B8 } }, 'Daily')" +
                    "      )," +
                    "      Text({ style: { fontSize: 18, color: 0xFF334155, marginTop: 16, fontWeight: 'bold' } }," +
                    "        'The only way to do great work is to love what you do.')," +
                    "      Text({ style: { fontSize: 14, color: 0xFF94A3B8, marginTop: 8 } }, 'Steve Jobs')" +
                    "    )," +
                    // ── Habits section ───────────────────────────
                    "    View({ style: { padding: 20, paddingTop: 8 } }," +
                    "      SectionTitle('DAILY HABITS')," +
                    "      View({ style: { gap: 10, marginTop: 12 } }," +
                    "        HabitItem('yoga', 'Morning Yoga', true, 0xFF10B981)," +
                    "        HabitItem('water', 'Hydration Goal', true, 0xFF3B82F6)," +
                    "        HabitItem('book', 'Read 20 Pages', false, 0xFFF59E0B)," +
                    "        HabitItem('walk', 'Evening Walk', false, 0xFFF43F5E)," +
                    "        HabitItem('sleep', 'Sleep by 10 PM', false, 0xFF8B5CF6)" +
                    "      )" +
                    "    )," +
                    // ── Progress card ────────────────────────────
                    "    View({ style: { margin: 20, marginTop: 18, padding: 20, background: 0xFFFFFFFF," +
                    "        borderRadius: 16, borderWidth: 1, borderColor: 0xFFF1F5F9 } }," +
                    "      SectionTitle('WEEKLY PROGRESS')," +
                    "      View({ style: { gap: 14, marginTop: 12 } }," +
                    "        View({ style: { gap: 8 } }," +
                    "          View({ style: { flexDirection: 'row', justifyContent: 'space-between' } }," +
                    "            Text({ style: { fontSize: 15, color: 0xFF475569 } }, 'Meditation')," +
                    "            Text({ style: { fontSize: 15, fontWeight: 'bold', color: 0xFF7C3AED } }, '85%')" +
                    "          )," +
                    "          ProgressBar(85, 100, 0xFF7C3AED, 0xFFEDE9FE, 14)" +
                    "        )," +
                    "        View({ style: { gap: 8 } }," +
                    "          View({ style: { flexDirection: 'row', justifyContent: 'space-between' } }," +
                    "            Text({ style: { fontSize: 15, color: 0xFF475569 } }, 'Exercise')," +
                    "            Text({ style: { fontSize: 15, fontWeight: 'bold', color: 0xFF10B981 } }, '72%')" +
                    "          )," +
                    "          ProgressBar(72, 100, 0xFF10B981, 0xFFDCFCE7, 14)" +
                    "        )," +
                    "        View({ style: { gap: 8 } }," +
                    "          View({ style: { flexDirection: 'row', justifyContent: 'space-between' } }," +
                    "            Text({ style: { fontSize: 15, color: 0xFF475569 } }, 'Nutrition')," +
                    "            Text({ style: { fontSize: 15, fontWeight: 'bold', color: 0xFFF59E0B } }, '60%')" +
                    "          )," +
                    "          ProgressBar(60, 100, 0xFFF59E0B, 0xFFFEF3C7, 14)" +
                    "        )" +
                    "      )" +
                    "    )," +
                    // ── Affirmation banner ───────────────────────
                    "    View({ style: { margin: 20, marginTop: 18, padding: 22, background: 0xFF7C3AED," +
                    "        borderRadius: 16, alignItems: 'center' } }," +
                    "      Text({ style: { fontSize: 15, color: 0xFFEDE9FE } }, 'TODAY\\'S AFFIRMATION')," +
                    "      Text({ style: { fontSize: 20, fontWeight: 'bold', color: 0xFFFFFFFF," +
                    "        marginTop: 12, textAlign: 'center' } }," +
                    "        'I am worthy of love, peace, and joy.')" +
                    "    )," +
                    // ── Action button ────────────────────────────
                    "    View({ style: { paddingLeft: 20, paddingRight: 20, paddingTop: 8 } }," +
                    "      Button({ style: { background: 0xFF7C3AED, color: 0xFFFFFFFF," +
                    "        fontSize: 18, fontWeight: 'bold', borderRadius: 14, padding: 20," +
                    "        borderWidth: 0, alignItems: 'center', justifyContent: 'center', widthPercent: 100 } }," +
                    "        'Start Evening Routine')" +
                    "    )," +
                    // ── Footer (clears nav bar) ──────────────────
                    "    Text({ style: { fontSize: 12, color: 0xFF94A3B8," +
                    "      textAlign: 'center', marginTop: 14, paddingBottom: " + bottomPad + " } }," +
                    "      'Good Vibes  |  Yoga + Hermes + Skia')" +
                    "  )" +
                    ", " + W + ", " + H + "); " +
                    "'ok'";

                js.setCanvas(canvas);
                String result = js.eval(jsCode);

                long dt = (System.nanoTime() - t0) / 1_000_000;
                byte[] px = canvas.getPixels();
                if (px != null) {
                    imageView.setImageBitmap(toBitmap(W, H, px));
                }
                status.setText("Good Vibes | " + W + "x" + H + " inset=" + topInset + "/" + bottomInset + " | " + dt + "ms | " + result);
            }
        } catch (Throwable t) {
            status.setText("Error: " + t);
        }
    }

    private String loadAsset(String name) throws java.io.IOException {
        try (java.io.InputStream is = getAssets().open(name)) {
            byte[] buf = new byte[is.available()];
            is.read(buf);
            return new String(buf, "UTF-8");
        }
    }
}
