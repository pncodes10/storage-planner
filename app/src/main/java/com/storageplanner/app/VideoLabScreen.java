package com.storageplanner.app;

import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import com.storageplanner.core.FileItem;
import com.storageplanner.core.Report;
import com.storageplanner.tools.VideoCompressor;
import com.storageplanner.tools.VideoLevel;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Algorithm Lab for videos (debug): pick one video and compress test copies of it at one level or all five, with the
 * same code and checks a real run will use (tools/VideoCompressor). The original is never touched: the Lab works on a
 * private copy, and the copies go to Download/StoragePlanner/compressed/<name>-<time>/. Everything is written to the
 * Lab's log, so a run on the phone can be sent back and the levels calibrated.
 */
final class VideoLabScreen extends Screen {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private File video;            // the Lab's private copy
    private Uri picked;            // the picked original, to play it
    private String name;
    private boolean busy;
    private TextView info, out;
    private ProgressBar spinner;
    private LinearLayout results;
    private final List<Button> runButtons = new ArrayList<Button>();
    private final StringBuilder shown = new StringBuilder();
    private final List<Made> made = new ArrayList<Made>();

    private static final class Made {
        String label, path, rel, facts;
        long worstUs;
        boolean kept;
    }

    VideoLabScreen(MainActivity a) { super(a); }

    @Override public String title() { return "Video Lab"; }

    @Override public String descriptor() { return "videolab"; }

    protected View build() {
        ScrollView sv = new ScrollView(a);
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(10), dp(14), dp(24));
        sv.addView(root);
        Ui.add(root, Ui.text(a, "Debug tool. Pick a video and make test copies at each compression level, with the same code and checks a "
                + "real run will use. The original is never changed. Copies go to Download/StoragePlanner/compressed/. Keep the app open "
                + "while it runs: a minute of 1080p video takes about a minute per level.", 14, Ui.text2, false), 0);
        Button pick = Ui.button(a, "Pick a video", true);
        pick.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { if (!busy) a.pickVideo(VideoLabScreen.this); } });
        add(root, pick, 10);
        info = Ui.text(a, "No video picked yet.", 13, Ui.text2, false);
        add(root, info, 6);
        spinner = new ProgressBar(a);
        spinner.setVisibility(View.GONE);
        add(root, spinner, 6);

        Ui.add(root, Ui.heading(a, "Compress (test copies)", 17), 14);
        addRun(root, "All five levels", true, null);
        for (VideoLevel l : VideoLevel.values()) addRun(root, "Level " + l.number() + ": " + l.label + describe(l), false, l);

        Ui.add(root, Ui.heading(a, "Results", 17), 14);
        out = Ui.text(a, "Pick a video, then a level.", 12, Ui.text, false);
        out.setTypeface(Typeface.MONOSPACE);
        out.setTextIsSelectable(true);
        out.setBackground(Ui.round(Ui.surface, dp(8)));
        out.setPadding(dp(10), dp(8), dp(10), dp(8));
        add(root, out, 6);
        results = new LinearLayout(a);
        results.setOrientation(LinearLayout.VERTICAL);
        add(root, results, 6);
        showMade();

        LinearLayout logRow = Ui.row(a);
        Button save = Ui.button(a, "Save log", false);
        save.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { saveLog(false); } });
        Button share = Ui.button(a, "Share log", false);
        share.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { saveLog(true); } });
        LinearLayout.LayoutParams l1 = new LinearLayout.LayoutParams(0, -2, 1); l1.rightMargin = dp(8);
        logRow.addView(save, l1); logRow.addView(share, new LinearLayout.LayoutParams(0, -2, 1));
        add(root, logRow, 14);
        showInfo();
        return sv;
    }

    private static String describe(VideoLevel l) {
        if (l.keepsSize()) return " (same size)";
        return " (up to " + l.maxShortSide + "p, " + l.maxFps + " fps" + (l.audioKbps > 0 ? ", smaller sound" : "") + ")";
    }

    private void add(LinearLayout p, View v, int top) {
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-1, -2); l.topMargin = dp(top);
        p.addView(v, l);
    }

    private void addRun(LinearLayout root, final String label, boolean primary, final VideoLevel level) {
        Button b = Ui.button(a, label, primary);
        b.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { run(level); } });
        add(root, b, 6);
        runButtons.add(b);
    }

    // ------------------------------------------------------------------ picking
    /** Called by MainActivity with the picked video: copies it into the app's cache (the original is only read). */
    void picked(final Uri u) {
        spinner.setVisibility(View.VISIBLE);
        info.setText("Copying the video into the Lab…");
        worker.execute(new Runnable() {
            public void run() {
                String n = "video.mp4"; long modified = 0;
                File dst = null;
                try {
                    Cursor c = a.getContentResolver().query(u, null, null, null, null);
                    if (c != null) try {
                        if (c.moveToFirst()) {
                            int ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME), mi = c.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED);
                            if (ni >= 0 && !c.isNull(ni)) n = c.getString(ni);
                            if (mi >= 0 && !c.isNull(mi)) modified = c.getLong(mi);
                        }
                    } finally { c.close(); }
                    // not the cache: Android deletes cache files whenever storage runs low, which it did mid-run
                    File dir = new File(a.getNoBackupFilesDir(), "videolab");
                    dir.mkdirs();
                    dst = new File(dir, "source.video");     // overwritten by the next pick (the app itself deletes nothing)
                    InputStream in = a.getContentResolver().openInputStream(u);
                    if (in == null) throw new Exception("cannot open");
                    FileOutputStream o = new FileOutputStream(dst, false);
                    try {
                        byte[] buf = new byte[1 << 20]; int k;
                        while ((k = in.read(buf)) > 0) o.write(buf, 0, k);
                    } finally { in.close(); o.close(); }
                    if (modified > 0) dst.setLastModified(modified);
                    LabLog.line(a, String.format(Locale.US, "\nvideo lab: picked %s (%d bytes, modified %d)", n, dst.length(), modified));
                } catch (Throwable t) {
                    LabLog.error(a, "copying the video", t);
                    dst = null;
                }
                final File got = dst; final String gotName = n;
                ui.post(new Runnable() {
                    public void run() {
                        spinner.setVisibility(View.GONE);
                        if (got == null) { a.toast("The video could not be opened."); showInfo(); return; }
                        video = got; picked = u; name = gotName; made.clear(); showMade();
                        showInfo();
                    }
                });
            }
        });
    }

    private void showInfo() {
        if (info == null) return;
        info.setText(video == null ? "No video picked yet." : name + " · " + Report.size(video.length()));
    }

    // ------------------------------------------------------------------ running
    private void run(final VideoLevel only) {
        if (busy) return;
        if (video == null) { a.toast("Pick a video first."); return; }
        busy = true;
        for (Button b : runButtons) b.setEnabled(false);
        spinner.setVisibility(View.VISIBLE);
        a.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        shown.setLength(0);
        out.setText("Compressing…");
        final File src = video; final String srcName = name;
        worker.execute(new Runnable() {
            public void run() {
                LabLog.header(a);
                LabLog.line(a, "\n--- " + LabLog.now() + "  video lab: " + srcName + (only == null ? ", all levels" : ", level " + only.number()));
                long t0 = System.nanoTime();
                try {
                    String stem = CopyStem.of(srcName);
                    File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                            "StoragePlanner/compressed/" + stem + "-" + new SimpleDateFormat("HHmmss", Locale.US).format(new Date()));
                    dir.mkdirs();
                    for (VideoLevel l : VideoLevel.values()) {
                        if (only != null && l != only) continue;
                        line(String.format(Locale.US, "Level %d (%s) …", l.number(), l.label));
                        File outFile = new File(dir, stem + "-L" + l.number() + ".mp4");
                        if (!src.isFile()) {
                            line("  stopped: the Lab's copy of the video is gone (" + src + "). Pick the video again.");
                            break;
                        }
                        long free = dir.getUsableSpace();
                        if (free < src.length() * 2 + (200L << 20)) {
                            line(String.format(Locale.US, "  stopped: only %s free; a test copy needs up to twice the video's size (%s) plus room for samples.",
                                    Report.size(free), Report.size(src.length())));
                            break;
                        }
                        VideoCompressor.Outcome o = VideoCompressor.compressForLab(src, l, outFile, new File(a.getNoBackupFilesDir(), "videolab/work"));
                        report(o, outFile, dir);
                    }
                    line("Saved in Download/StoragePlanner/compressed/" + dir.getName() + "/");
                } catch (Throwable t) {
                    LabLog.error(a, "video lab", t);
                    line("! failed: " + t);
                }
                line(String.format(Locale.US, "(took %.0f s)", (System.nanoTime() - t0) / 1e9));
                ui.post(new Runnable() {
                    public void run() {
                        busy = false;
                        for (Button b : runButtons) b.setEnabled(true);
                        spinner.setVisibility(View.GONE);
                        a.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                        showMade();
                    }
                });
            }
        });
    }

    private void report(VideoCompressor.Outcome o, File outFile, File dir) {
        for (String t : o.trace) line("    " + t);      // on screen too: the steps are what a pasted result needs
        if (!o.lost.isEmpty()) line("    a copy does not keep: " + o.lost);
        if (o.output == null) {
            line(String.format(Locale.US, "  not compressed: %s (%.0f s)", o.skipped, o.ms / 1000.0));
            return;
        }
        String score = o.search != null && o.search.score != null ? o.search.score.toString() : "";
        String verdict = o.ok ? "a real run keeps this" : "a real run would not keep this: " + (o.wouldSkip != null ? o.wouldSkip : o.skipped);
        String facts = String.format(Locale.US, "%s → %s (%s) · %s · %s · %s, %d kbps · audio %s · %.0f s · %s",
                Report.size(o.before), Report.size(o.after), LabScreen.change(o.before, o.after), o.level.label,
                o.encoder, o.plan.width + "x" + o.plan.height + String.format(Locale.US, " %.0f fps", o.plan.fps), o.search.bitrate / 1000,
                o.audio, o.ms / 1000.0, score);
        line("  " + facts);
        line("  " + verdict);
        Made m = new Made();
        m.label = "Level " + o.level.number() + " · " + o.level.label + " · " + LabScreen.change(o.before, o.after);
        m.path = outFile.getPath();
        m.rel = "Download/StoragePlanner/compressed/" + dir.getName() + "/" + outFile.getName();
        m.facts = facts + " · " + verdict;
        m.worstUs = o.search.score != null ? o.search.score.worstAtUs : 0;
        m.kept = o.ok;
        synchronized (made) { made.add(m); }
    }

    private void line(final String s) {
        LabLog.line(a, s);
        ui.post(new Runnable() { public void run() { shown.append(s).append('\n'); out.setText(shown); } });
    }

    // ------------------------------------------------------------------ results: play, compare a frame
    private void showMade() {
        if (results == null) return;
        results.removeAllViews();
        List<Made> copy;
        synchronized (made) { copy = new ArrayList<Made>(made); }
        if (copy.isEmpty()) return;
        Button orig = Ui.button(a, "Play the original", false);
        orig.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { play(picked); } });
        add(results, orig, 6);
        for (final Made m : copy) {
            add(results, Ui.text(a, m.label + (m.kept ? "" : " (not kept in a real run)"), 14, Ui.text, true), 12);
            add(results, Ui.text(a, m.facts, 12, Ui.text2, false), 2);
            LinearLayout row = Ui.row(a);
            Button p = Ui.button(a, "Play copy", false);
            p.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { File f = new File(m.path); play(FileAccessProvider.uriFor(new FileItem(m.rel, m.path, f.length(), f.lastModified()))); }
            });
            Button c = Ui.button(a, "Compare worst frame", false);
            c.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { compare(m); } });
            LinearLayout.LayoutParams l1 = new LinearLayout.LayoutParams(0, -2, 1); l1.rightMargin = dp(8);
            row.addView(p, l1); row.addView(c, new LinearLayout.LayoutParams(0, -2, 1));
            add(results, row, 4);
        }
    }

    private void play(Uri u) {
        if (u == null) return;
        try {
            Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(u, "video/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            a.startActivity(Intent.createChooser(i, "Play with"));
        } catch (Throwable t) { a.toast("No app can play this video."); }
    }

    /** Takes the frame the check scored worst from the original and the copy, and opens them side by side. */
    private void compare(final Made m) {
        if (busy || video == null) return;
        spinner.setVisibility(View.VISIBLE);
        final File src = video;
        worker.execute(new Runnable() {
            public void run() {
                File dir = new File(a.getCacheDir(), "videolab/frames");
                dir.mkdirs();
                final File fo = new File(dir, "original.png"), fc = new File(dir, "copy.png");
                boolean ok = frame(src.getPath(), m.worstUs, fo) && frame(m.path, m.worstUs, fc);
                final boolean done = ok;
                ui.post(new Runnable() {
                    public void run() {
                        spinner.setVisibility(View.GONE);
                        if (!done) { a.toast("Could not read the frames."); return; }
                        a.push(new CompressCompareScreen(a, fo.getPath(), fc.getPath(), m.label + String.format(Locale.US, " · frame at %.2f s", m.worstUs / 1e6), m.facts));
                    }
                });
            }
        });
    }

    private static boolean frame(String video, long us, File png) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(video);
            Bitmap b = r.getFrameAtTime(Math.max(0, us), MediaMetadataRetriever.OPTION_CLOSEST);
            if (b == null) return false;
            FileOutputStream o = new FileOutputStream(png);
            try { b.compress(Bitmap.CompressFormat.PNG, 100, o); } finally { o.close(); }
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            try { r.release(); } catch (Throwable ignored) {}
        }
    }

    private void saveLog(boolean share) {
        try {
            File f = LabLog.export(a);
            if (!share) { a.toast("Saved to Download/StoragePlanner/" + f.getName()); return; }
            FileActions.share(a, new FileItem("Download/StoragePlanner/" + f.getName(), f.getAbsolutePath(), f.length(), f.lastModified()));
        } catch (Throwable t) {
            a.toast("Could not save the log: " + t.getMessage());
        }
    }

    /** A file name without its extension, safe as a folder name. */
    static final class CopyStem {
        static String of(String name) {
            int d = name.lastIndexOf('.');
            String s = d > 0 ? name.substring(0, d) : name;
            s = s.replaceAll("[^A-Za-z0-9._-]", "_");
            return s.isEmpty() ? "video" : s;
        }
    }
}
