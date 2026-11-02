package com.storageplanner.app;

import android.graphics.Bitmap;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.storageplanner.core.Report;

import java.io.File;
import java.util.Locale;

/**
 * Original vs compressed copy of one picture, three ways:
 * <ul>
 * <li>Side by side (stacked for landscape pictures), zooming together, as on the duplicates compare screen;</li>
 * <li>Flip: one picture at a time at the same zoom and spot. Switching back and forth is the easiest way
 *     to spot a change;</li>
 * <li>Difference: where the two differ, amplified 8x (black = identical), with how many pixels changed.</li>
 * </ul>
 */
public final class CompressCompareScreen extends Screen {
    private static final int SIDE = 0, FLIP = 1, DIFF = 2;
    private static final int MAX_SIDE = 4096;          // a 12 MP photo shows at full size
    private static final int AMPLIFY = 8;

    private final String original, copy, name, facts;
    private int mode = SIDE;
    private boolean together = true, showOriginal = true;
    private Bitmap bo, bc, bd;
    private String diffFacts, error;
    private boolean loading;
    private float[] keepState;                         // zoom / spot carried across modes and flips

    private LinearLayout root, stage;

    /**
     * @param original path of the original (shown upright using its EXIF orientation)
     * @param copy     path of the compressed copy
     * @param facts    one line about the copy (sizes, quality, scores), or null
     */
    public CompressCompareScreen(MainActivity a, String original, String copy, String name, String facts) {
        super(a);
        this.original = original; this.copy = copy; this.name = name; this.facts = facts;
    }

    public String title() { return "Original vs compressed"; }

    protected View build() {
        root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(8), dp(12), dp(10));
        render();
        load();
        return root;
    }

    private void load() {
        if (bo != null || loading) return;
        loading = true;
        new Thread(new Runnable() {
            public void run() {
                Bitmap o = null, c = null; String err = null;
                try {
                    o = ImageCompressor.decodeUpright(new File(original), MAX_SIDE);
                    c = ImageCompressor.decodeUpright(new File(copy), MAX_SIDE);
                } catch (OutOfMemoryError e) { err = "Not enough memory to show both pictures.";
                } catch (Throwable t) { err = "Could not open the pictures: " + t.getMessage(); }
                final Bitmap fo = o, fc = c; final String fe = err;
                AnalysisState.UI.post(new Runnable() {
                    public void run() { loading = false; bo = fo; bc = fc; error = fe; render(); }
                });
            }
        }, "compare-load").start();
    }

    // ------------------------------------------------------------------ layout
    private void render() {
        if (root == null) return;
        root.removeAllViews();
        TextView h = Ui.heading(a, name, 17);
        Ui.ellipsizeMiddle(h);
        root.addView(h, new LinearLayout.LayoutParams(-1, -2));
        String sizes = Report.size(new File(original).length()) + " → " + Report.size(new File(copy).length());
        long before = new File(original).length(), after = new File(copy).length();
        if (before > 0) sizes += " (" + LabScreen.change(before, after) + ")";
        Ui.add(root, Ui.text(a, facts != null ? facts : sizes, 13, Ui.text2, false), 2);

        LinearLayout modes = Ui.row(a);
        String[] labels = {"Side by side", "Flip", "Difference"};
        String[] spoken = {"Show both side by side", "Show one at a time and switch between them", "Show where they differ, amplified"};
        for (int i = 0; i < 3; i++) {
            final int m = i;
            Button b = Ui.button(a, labels[i], mode == i);
            b.setContentDescription(spoken[i] + (mode == i ? ", selected" : ""));
            b.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { if (mode != m) { rememberState(); mode = m; render(); } }
            });
            LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(0, -2, 1); if (i < 2) l.rightMargin = dp(6);
            modes.addView(b, l);
        }
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(-1, -2); ml.topMargin = dp(8);
        root.addView(modes, ml);

        stage = new LinearLayout(a);
        stage.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, 0, 1); sl.topMargin = dp(8);
        root.addView(stage, sl);

        if (error != null) { stage.addView(Ui.text(a, error, 15, Ui.tier[4], true)); return; }
        if (bo == null || bc == null) { spinner(); return; }
        if (mode == SIDE) side();
        else if (mode == FLIP) flip();
        else diff();
    }

    private void spinner() {
        FrameLayout f = new FrameLayout(a);
        f.addView(new ProgressBar(a), new FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER));
        stage.addView(f, new LinearLayout.LayoutParams(-1, -1));
    }

    private ZoomImageView[] shownViews = new ZoomImageView[0];

    private void rememberState() {
        if (shownViews.length > 0 && shownViews[0].getDrawable() != null) keepState = shownViews[0].state();
    }

    private FrameLayout pane(ZoomImageView iv, String label) {
        FrameLayout box = new FrameLayout(a);
        box.setBackground(Ui.round(0xFF101014, dp(10)));
        box.setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND);
        box.setClipToOutline(true);
        box.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        TextView t = Ui.text(a, label, 13, 0xFFFFFFFF, true);
        t.setBackground(Ui.round(0x99000000, dp(6)));
        t.setPadding(dp(8), dp(2), dp(8), dp(2));
        FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        tl.leftMargin = dp(6); tl.topMargin = dp(6);
        box.addView(t, tl);
        return box;
    }

    private ZoomImageView view(Bitmap b, String spoken) {
        final ZoomImageView iv = new ZoomImageView(a);
        iv.setImageBitmap(b);
        iv.setContentDescription(spoken);
        if (keepState != null) {
            final float[] st = keepState;
            iv.post(new Runnable() { public void run() { iv.post(new Runnable() { public void run() { iv.applyState(st); } }); } });
        }
        return iv;
    }

    private void side() {
        CheckBox sync = new CheckBox(a);
        sync.setText("Zoom together");
        sync.setTextColor(Ui.text);
        sync.setTextSize(15);
        sync.setMinHeight(dp(48));
        sync.setChecked(together);
        stage.addView(sync, new LinearLayout.LayoutParams(-2, -2));
        final ZoomImageView l = view(bo, "Original"), r = view(bc, "Compressed copy");
        shownViews = new ZoomImageView[]{l, r};
        sync.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean on) { together = on; if (on) r.applyState(l.state()); }
        });
        ZoomImageView.Listener follow = new ZoomImageView.Listener() {
            public void onViewChanged(ZoomImageView v) { if (together) (v == l ? r : l).applyState(v.state()); }
        };
        l.setListener(follow); r.setListener(follow);
        LinearLayout panes = new LinearLayout(a);
        boolean landscape = bo.getWidth() > bo.getHeight();
        panes.setOrientation(landscape ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams p1 = landscape ? new LinearLayout.LayoutParams(-1, 0, 1) : new LinearLayout.LayoutParams(0, -1, 1);
        LinearLayout.LayoutParams p2 = landscape ? new LinearLayout.LayoutParams(-1, 0, 1) : new LinearLayout.LayoutParams(0, -1, 1);
        if (landscape) p1.bottomMargin = dp(6); else p1.rightMargin = dp(6);
        panes.addView(pane(l, "Original · " + Report.size(new File(original).length())), p1);
        panes.addView(pane(r, "Compressed · " + Report.size(new File(copy).length())), p2);
        stage.addView(panes, new LinearLayout.LayoutParams(-1, 0, 1));
        Ui.add(stage, Ui.text(a, "Pinch or double-tap to zoom; both follow when Zoom together is on.", 13, Ui.text2, false), 4);
    }

    private void flip() {
        final ZoomImageView iv = view(showOriginal ? bo : bc, showOriginal ? "Original" : "Compressed copy");
        shownViews = new ZoomImageView[]{iv};
        FrameLayout box = pane(iv, showOriginal ? "Original" : "Compressed");
        stage.addView(box, new LinearLayout.LayoutParams(-1, 0, 1));
        final Button sw = Ui.button(a, showOriginal ? "Showing original · tap for compressed" : "Showing compressed · tap for original", true);
        sw.setContentDescription(showOriginal ? "Showing the original. Double tap to show the compressed copy at the same spot."
                : "Showing the compressed copy. Double tap to show the original at the same spot.");
        sw.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                rememberState();
                showOriginal = !showOriginal;
                render();
                stage.announceForAccessibility(showOriginal ? "Original" : "Compressed copy");
            }
        });
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, -2); bl.topMargin = dp(8);
        stage.addView(sw, bl);
        Ui.add(stage, Ui.text(a, "Zoom into a detail, then switch back and forth: anything that changes jumps out.", 13, Ui.text2, false), 4);
    }

    private void diff() {
        if (bd == null) {
            if (bo.getWidth() != bc.getWidth() || bo.getHeight() != bc.getHeight()) {
                Ui.add(stage, Ui.text(a, "The pictures have different sizes (" + bo.getWidth() + "×" + bo.getHeight() + " and "
                        + bc.getWidth() + "×" + bc.getHeight() + "), so they cannot be compared pixel by pixel.", 15, Ui.tier[4], true), 0);
                return;
            }
            makeDiff();
        }
        ZoomImageView iv = view(bd, "Difference between original and compressed copy, amplified " + AMPLIFY + " times");
        shownViews = new ZoomImageView[]{iv};
        stage.addView(pane(iv, "Difference ×" + AMPLIFY), new LinearLayout.LayoutParams(-1, 0, 1));
        Ui.add(stage, Ui.text(a, diffFacts, 13, Ui.text, true), 6);
        Ui.add(stage, Ui.text(a, "Black means identical. Brighter means a bigger change, amplified " + AMPLIFY
                + " times so small differences show. Fine grain and edges usually light up a little.", 13, Ui.text2, false), 2);
    }

    /** Largest of the red / green / blue differences per pixel, times AMPLIFY, as grey. */
    private void makeDiff() {
        int w = bo.getWidth(), h = bo.getHeight();
        int[] ro = new int[w], rc = new int[w], out = new int[w];
        bd = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        long changed = 0, big = 0; int max = 0; double sum = 0;
        for (int y = 0; y < h; y++) {
            bo.getPixels(ro, 0, w, 0, y, w, 1);
            bc.getPixels(rc, 0, w, 0, y, w, 1);
            for (int x = 0; x < w; x++) {
                int p = ro[x], q = rc[x];
                int d = Math.max(Math.abs(((p >> 16) & 255) - ((q >> 16) & 255)),
                        Math.max(Math.abs(((p >> 8) & 255) - ((q >> 8) & 255)), Math.abs((p & 255) - (q & 255))));
                if (d > 0) changed++;
                if (d > 8) big++;
                if (d > max) max = d;
                sum += d;
                int v = Math.min(255, d * AMPLIFY);
                out[x] = 0xFF000000 | (v << 16) | (v << 8) | v;
            }
            bd.setPixels(out, 0, w, 0, y, w, 1);
        }
        double n = (double) w * h;
        diffFacts = String.format(Locale.US, "Average change %.2f of 255 · largest %d · %.1f%% of pixels changed at all · %.2f%% by more than 8",
                sum / n, max, 100 * changed / n, 100 * big / n);
    }

    @Override public void onHide() { rememberState(); }
}
