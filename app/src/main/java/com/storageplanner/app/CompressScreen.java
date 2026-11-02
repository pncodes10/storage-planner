package com.storageplanner.app;

import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import com.storageplanner.core.Compression;
import com.storageplanner.core.FileItem;
import com.storageplanner.core.Option;
import com.storageplanner.core.Report;
import com.storageplanner.core.Review;
import com.storageplanner.core.Techniques;
import com.storageplanner.core.Unit;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Carries out a compression recommendation: what will happen, Start, live progress, the outcome, and
 * the originals waiting to be moved to the trash. With technique == null it shows only the progress
 * and the waiting originals (opened from the overview).
 */
public final class CompressScreen extends Screen {
    private final String key;
    private LinearLayout content;

    public CompressScreen(MainActivity a, String technique) { super(a); this.key = technique; }

    public String title() { return key != null ? "Compress " + Compression.noun(key, 2) : "Compressed images"; }

    @Override public String descriptor() { return key != null ? "compress:" + key : "compress:*"; }

    protected View build() {
        ScrollView sv = new ScrollView(a);
        content = new LinearLayout(a);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(12), dp(16), dp(32));
        sv.addView(content);
        refresh();
        return sv;
    }

    @Override public void refresh() {
        if (content == null) return;
        content.removeAllViews();
        if (key != null && Session.hasResults()) intro();
        if (CompressionState.running) progress();
        else if (CompressionState.technique != null && (key == null || key.equals(CompressionState.technique))) outcome();
        originals();
    }

    /** Files this run would compress: the recommendation's targets that have no compressed copy yet. */
    private List<FileItem> targets() {
        List<FileItem> out = new ArrayList<FileItem>();
        for (Unit u : Compression.targets(Session.candidates.get(key), key, Session.kept, Session.plan.chosen))
            if (!hasCopy(u.file.abs) && new File(u.file.abs).isFile()) out.add(u.file);
        return out;
    }

    // ------------------------------------------------------------------ what will happen + Start
    private void intro() {
        boolean lossless = Compression.lossless(key);
        Ui.add(content, Ui.heading(a, key, 21), 0);
        Ui.add(content, Ui.text(a, lossless
                ? "Each screenshot is saved again as lossless WebP. The copy must have exactly the same pixels, or it is thrown away."
                : "Each " + Compression.noun(key, 1) + " is saved again as HEIC or WebP, whichever is smaller, at the lowest quality that still passes the "
                  + "\"looks identical\" check (SSIM 0.98 and PSNR 38 dB, as in the study), and colours must not shift. "
                  + "Date taken, camera and location are copied over. A copy that fails, or saves less than 10%, is thrown away.",
                15, Ui.text, false), 8);
        Ui.add(content, Ui.text(a, "The copy is saved next to the original (" + example() + "). Your originals are not touched. "
                + "When it's done, you choose whether to move them to the trash.", 15, Ui.text, false), 8);
        String note = Techniques.CHAT_IMAGES.equals(key)
                ? "WhatsApp will not find the new copies: once the originals are gone, those images show as missing inside chats. "
                  + "Your gallery shows the copies."
                : !lossless ? "If Google Photos or another app backs up your camera, it will upload the new copies too." : null;
        if (note != null) Ui.add(content, Ui.text(a, note, 14, Ui.tier[3], true), 8);

        String why = ImageCompressor.unavailableReason(key);
        if (why != null) {
            Ui.add(content, Ui.text(a, why + " Nothing can be compressed on this phone.", 15, Ui.tier[4], true), 16);
            return;
        }
        if (CompressionState.running) return;
        final List<FileItem> files = targets();
        long est = 0;
        for (FileItem f : files) {
            Unit u = Session.unitById.get(f.rel);
            Option o = u != null ? Review.optionFor(u, key) : null;
            if (o != null) est += o.saved;
        }
        if (files.isEmpty()) {
            Ui.add(content, Ui.text(a, "Nothing left to compress here: every item is kept by you, planned another way, or already has a compressed copy.",
                    15, Ui.text2, false), 16);
            return;
        }
        Ui.add(content, Ui.text(a, files.size() + " " + Compression.noun(key, files.size()) + " · about " + Report.size(est)
                + " smaller (estimate). Items you kept, and items the plan removes another way, are left out.", 15, Ui.text, true), 16);
        Button go = Ui.button(a, "Compress " + files.size() + " " + Compression.noun(key, files.size()), true);
        go.setContentDescription("Start compressing " + files.size() + " " + Compression.noun(key, files.size())
                + ". Originals are kept until you move them to the trash.");
        go.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { a.startCompression(key, files); } });
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(-1, -2); gl.topMargin = dp(12);
        content.addView(go, gl);
        Ui.add(content, Ui.text(a, "It runs in the background, and you can use other apps meanwhile. Each " + Compression.noun(key, 1)
                + " takes a few seconds.", 14, Ui.text2, false), 8);
    }

    private boolean hasCopy(String path) {
        for (String ext : Compression.extensions(key)) if (new File(Compression.outputPath(path, ext)).exists()) return true;
        return false;
    }

    private String example() {
        return Compression.lossless(key) ? "Screenshot_1.png → Screenshot_1.webp" : "IMG_1.jpg → IMG_1.heic or IMG_1.webp";
    }

    // ------------------------------------------------------------------ running
    private void progress() {
        String t = CompressionState.technique;
        Ui.add(content, Ui.heading(a, "Compressing " + Compression.noun(t, 2), 18), 24);
        Ui.add(content, Ui.text(a, CompressionState.done + " of " + CompressionState.total + " done · "
                + CompressionState.compressed + " compressed · " + Report.size(CompressionState.saved()) + " smaller so far"
                + (CompressionState.skippedCount() > 0 ? " · " + CompressionState.skippedCount() + " skipped" : ""), 15, Ui.text, true), 6);
        android.widget.ProgressBar bar = new android.widget.ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(Math.max(1, CompressionState.total));
        bar.setProgress(CompressionState.done);
        bar.setContentDescription("Compression progress");
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, dp(24)); bl.topMargin = dp(8);
        content.addView(bar, bl);
        Button stop = Ui.button(a, CompressionState.cancelled ? "Stopping…" : "Stop", false);
        stop.setEnabled(!CompressionState.cancelled);
        stop.setContentDescription("Stop compressing after the current file. Copies already made are kept.");
        stop.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { a.stopCompression(); } });
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-2, -2); sl.topMargin = dp(12);
        content.addView(stop, sl);
    }

    // ------------------------------------------------------------------ last run
    private void outcome() {
        String t = CompressionState.technique;
        int n = CompressionState.compressed;
        Ui.add(content, Ui.heading(a, CompressionState.cancelled ? "Stopped" : "Done", 18), 24);
        Ui.add(content, Ui.text(a, n + " " + Compression.noun(t, n) + " compressed: " + Report.size(CompressionState.before) + " → "
                + Report.size(CompressionState.after) + " (" + Report.size(CompressionState.saved()) + " smaller).", 15, Ui.text, true), 6);
        Map<String, Integer> sk = CompressionState.skippedCopy();
        if (!sk.isEmpty()) {
            StringBuilder s = new StringBuilder("Skipped, original left as it is:");
            for (Map.Entry<String, Integer> e : sk.entrySet()) s.append("\n• ").append(e.getValue()).append(": ").append(e.getKey());
            Ui.add(content, Ui.text(a, s.toString(), 14, Ui.text2, false), 6);
        }
        if (n > 0) Ui.add(content, Ui.text(a, "Tap Analyse on the start screen later to update your plan.", 14, Ui.text2, false), 6);
    }

    // ------------------------------------------------------------------ originals waiting for the trash
    private void originals() {
        final List<Originals.Pair> ps = Originals.pending(a);
        if (ps.isEmpty()) {
            if (key == null && !CompressionState.running)
                Ui.add(content, Ui.text(a, "No originals are waiting. Compressed copies whose originals you removed are not listed.", 15, Ui.text2, false), 8);
            return;
        }
        Ui.add(content, Ui.heading(a, "Originals still on your phone", 18), 24);
        Ui.add(content, Ui.text(a, ps.size() + (ps.size() == 1 ? " original has" : " originals have") + " a checked, compressed copy. Moving them to the trash frees "
                + Report.size(Originals.savedBytes(ps)) + ".", 15, Ui.text, true), 6);
        Ui.add(content, Ui.text(a, android.os.Build.VERSION.SDK_INT >= 30
                ? "Android asks you to confirm. You can restore them from the trash for 30 days; the space is freed when the trash is emptied."
                : "This version of Android has no trash: the originals are deleted for good after you confirm.", 14, Ui.text2, false), 4);
        if (CompressionState.running) {
            Ui.add(content, Ui.text(a, "You can move them once compression has finished.", 14, Ui.text2, false), 6);
            return;
        }
        LinearLayout row = Ui.row(a);
        Button trash = Ui.button(a, (android.os.Build.VERSION.SDK_INT >= 30 ? "Move " : "Delete ") + Math.min(ps.size(), MainActivity.TRASH_BATCH)
                + (ps.size() == 1 ? " original" : " originals") + (android.os.Build.VERSION.SDK_INT >= 30 ? " to trash" : ""), true);
        trash.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { a.trashOriginals(ps.subList(0, Math.min(ps.size(), MainActivity.TRASH_BATCH))); }
        });
        Button keep = Ui.button(a, "Keep both", false);
        keep.setContentDescription("Keep the originals and the compressed copies, and stop offering to remove the originals");
        keep.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Set<String> s = new HashSet<String>();
                for (Originals.Pair p : ps) s.add(p.original);
                Originals.forget(a, s);
                refresh();
                v.announceForAccessibility("Originals kept.");
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2); lp.rightMargin = dp(12);
        row.addView(trash, lp); row.addView(keep);
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2); rl.topMargin = dp(12);
        content.addView(row, rl);
        // look before trashing: the largest savings first, where a change would be most likely to show
        List<Originals.Pair> look = new ArrayList<Originals.Pair>(ps);
        java.util.Collections.sort(look, new java.util.Comparator<Originals.Pair>() {
            public int compare(Originals.Pair x, Originals.Pair y) { return Long.compare(y.savedBytes(), x.savedBytes()); }
        });
        Ui.add(content, Ui.text(a, "Check a few before you move them:", 15, Ui.text, true), 16);
        for (int i = 0; i < Math.min(5, look.size()); i++) {
            final Originals.Pair p = look.get(i);
            final String name = new File(p.original).getName();
            Button c = Ui.button(a, "Compare " + name + " · " + Report.size(new File(p.original).length()) + " → " + Report.size(new File(p.copy).length()), false);
            c.setContentDescription("Compare " + name + " with its compressed copy");
            c.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { a.push(new CompressCompareScreen(a, p.original, p.copy, name, null)); }
            });
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, -2); cl.topMargin = dp(6);
            content.addView(c, cl);
        }
        if (ps.size() > MainActivity.TRASH_BATCH)
            Ui.add(content, Ui.text(a, "Originals go " + MainActivity.TRASH_BATCH + " at a time; tap again for the next ones.", 14, Ui.text2, false), 6);
    }
}
