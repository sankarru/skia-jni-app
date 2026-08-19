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

import java.nio.ByteBuffer;

/** Good Vibes — a wellness dashboard rendered via Hermes + Skia + Yoga. */
public class ReactDemoActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        final int W = dm.widthPixels;
        final int H = dm.heightPixels;

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

            try (SkiaCanvas canvas = new SkiaCanvas(W, H);
                 JsCanvas js = new JsCanvas(W, H)) {

                js.eval(runtime);

                int contentH = H - topInset - bottomInset;

                // ── Scene definition ──────────────────────────────
                String jsCode =
                    "var root = render(_handle," +
                    "  View({ style: { background: 0xFFF8FAFC, padding: 0, gap: 0 } }," +
                    "    View({ style: { background: 0xFF6D28D9, padding: 20, paddingTop: 16, paddingBottom: 24 } }," +
                    "      Text({ style: { fontSize: 14, color: 0xFFC4B5FD } }, 'Good Morning')," +
                    "      Text({ style: { fontSize: 26, fontWeight: 'bold', color: 0xFFFFFFFF, marginTop: 4 } }, 'Alex')," +
                    "      View({ style: { flexDirection: 'row', gap: 8, marginTop: 12, alignItems: 'center' } }," +
                    "        Badge('MINDFUL', 0xFFEDE9FE, 0xFF7C3AED)," +
                    "        Text({ style: { fontSize: 12, color: 0xFFC4B5FD } }, 'Aug 19, 2026')" +
                    "      )" +
                    "    )," +
                    "    View({ style: { flexDirection: 'row', gap: 10, padding: 16 } }," +
                    "      StatPill('7,243', 'Steps', 0xFF7C3AED)," +
                    "      StatPill('85', 'Mood', 0xFFF59E0B)," +
                    "      StatPill('12', 'Day Streak', 0xFF10B981)" +
                    "    )," +
                    "    View({ style: { margin: 16, marginTop: 4, padding: 18, background: 0xFFFFFFFF," +
                    "        borderRadius: 14, borderWidth: 1, borderColor: 0xFFF1F5F9 } }," +
                    "      View({ style: { flexDirection: 'row', gap: 8, alignItems: 'center' } }," +
                    "        Badge('QUOTE', 0xFFFDF4FF, 0xFFA855F7)," +
                    "        Text({ style: { fontSize: 11, color: 0xFF94A3B8 } }, 'Daily')" +
                    "      )," +
                    "      Text({ style: { fontSize: 16, color: 0xFF334155, marginTop: 12, fontWeight: 'bold' } }," +
                    "        'The only way to do great work is to love what you do.')," +
                    "      Text({ style: { fontSize: 13, color: 0xFF94A3B8, marginTop: 6 } }, 'Steve Jobs')" +
                    "    )," +
                    "    View({ style: { padding: 16, paddingTop: 4 } }," +
                    "      SectionTitle('DAILY HABITS')," +
                    "      View({ style: { gap: 8, marginTop: 10 } }," +
                    "        HabitItem('yoga', 'Morning Yoga', true, 0xFF10B981)," +
                    "        HabitItem('water', 'Hydration Goal', true, 0xFF3B82F6)," +
                    "        HabitItem('book', 'Read 20 Pages', false, 0xFFF59E0B)," +
                    "        HabitItem('walk', 'Evening Walk', false, 0xFFF43F5E)," +
                    "        HabitItem('sleep', 'Sleep by 10 PM', false, 0xFF8B5CF6)" +
                    "      )" +
                    "    )," +
                    "    View({ style: { margin: 16, marginTop: 4, padding: 18, background: 0xFFFFFFFF," +
                    "        borderRadius: 14, borderWidth: 1, borderColor: 0xFFF1F5F9 } }," +
                    "      SectionTitle('WEEKLY PROGRESS')," +
                    "      View({ style: { gap: 10, marginTop: 10 } }," +
                    "        View({ style: { gap: 4 } }," +
                    "          View({ style: { flexDirection: 'row', justifyContent: 'space-between' } }," +
                    "            Text({ style: { fontSize: 13, color: 0xFF475569 } }, 'Meditation')," +
                    "            Text({ style: { fontSize: 13, fontWeight: 'bold', color: 0xFF7C3AED } }, '85%')" +
                    "          )," +
                    "          ProgressBar(85, 100, 0xFF7C3AED, 0xFFEDE9FE, 8)" +
                    "        )," +
                    "        View({ style: { gap: 4 } }," +
                    "          View({ style: { flexDirection: 'row', justifyContent: 'space-between' } }," +
                    "            Text({ style: { fontSize: 13, color: 0xFF475569 } }, 'Exercise')," +
                    "            Text({ style: { fontSize: 13, fontWeight: 'bold', color: 0xFF10B981 } }, '72%')" +
                    "          )," +
                    "          ProgressBar(72, 100, 0xFF10B981, 0xFFDCFCE7, 8)" +
                    "        )," +
                    "        View({ style: { gap: 4 } }," +
                    "          View({ style: { flexDirection: 'row', justifyContent: 'space-between' } }," +
                    "            Text({ style: { fontSize: 13, color: 0xFF475569 } }, 'Nutrition')," +
                    "            Text({ style: { fontSize: 13, fontWeight: 'bold', color: 0xFFF59E0B } }, '60%')" +
                    "          )," +
                    "          ProgressBar(60, 100, 0xFFF59E0B, 0xFFFEF3C7, 8)" +
                    "        )" +
                    "      )" +
                    "    )," +
                    "    View({ style: { margin: 16, marginTop: 4, padding: 18, background: 0xFF7C3AED," +
                    "        borderRadius: 14, alignItems: 'center' } }," +
                    "      Text({ style: { fontSize: 14, color: 0xFFEDE9FE } }, 'TODAY\\'S AFFIRMATION')," +
                    "      Text({ style: { fontSize: 17, fontWeight: 'bold', color: 0xFFFFFFFF," +
                    "        marginTop: 8, textAlign: 'center' } }," +
                    "        'I am worthy of love, peace, and joy.')" +
                    "    )," +
                    "    View({ style: { padding: 16, paddingTop: 4 } }," +
                    "      Button({ style: { background: 0xFF7C3AED, color: 0xFFFFFFFF," +
                    "        fontSize: 16, fontWeight: 'bold', borderRadius: 12, padding: 16," +
                    "        borderWidth: 0, alignItems: 'center', justifyContent: 'center' } }," +
                    "        'Start Evening Routine')" +
                    "    )," +
                    "    Text({ style: { fontSize: 11, color: 0xFF94A3B8," +
                    "      textAlign: 'center', marginTop: 4, marginBottom: 16 } }," +
                    "      'Good Vibes  |  Yoga + Hermes + Skia')" +
                    "  )" +
                    ", " + W + ", " + contentH + ", " + topInset + "); " +
                    "'ok'";

                js.setCanvas(canvas);
                String result = js.eval(jsCode);

                long dt = (System.nanoTime() - t0) / 1_000_000;
                byte[] px = canvas.getPixels();
                if (px != null) {
                    imageView.setImageBitmap(toBitmap(W, H, px));
                }
                status.setText("Good Vibes | " + W + "x" + H + " | " + dt + "ms | " + result);
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
