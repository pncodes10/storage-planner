package com.storageplanner.app;

import android.content.ClipData;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
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
import com.storageplanner.tools.AudioCompressor;
import com.storageplanner.tools.AudioMath;
import com.storageplanner.tools.AudioMath.Level;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Algorithm Lab for audio (debug): pick recordings (call recordings, voice notes, music) and make test copies at
 * one level or all five, with the same code and checks a real run will use (tools/AudioCompressor), or survey the
 * recordings folders for how much the Voice level would free. The picked recordings are only read. Copies go to
 * Download/StoragePlanner/compressed/audio-<time>/, and every step is written to the Lab's log.
 */
final class AudioLabScreen extends Screen {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<Picked> picked = new ArrayList<Picked>();
    private volatile boolean busy;
    private final AtomicBoolean stop = new AtomicBoolean();
    private TextView info, out, progress;
    private ProgressBar spinner;
    private LinearLayout results;
    private Button stopButton;
    private final List<Button> runButtons = new ArrayList<Button>();
    private final StringBuilder shown = new StringBuilder();
    private final List<String[]> made = new ArrayList<String[]>();     // {facts, original uri, copy path, copy rel}

    AudioLabScreen(MainActivity a) { super(a); }

    @Override public String title() { return "Audio Lab"; }

    @Override public String descriptor() { return "audiolab"; }

    protected View build() {
        ScrollView sv = new ScrollView(a);
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(10), dp(14), dp(24));
        sv.addView(root);
        Ui.add(root, Ui.text(a, "Debug tool. Pick recordings (call recordings, voice notes, music) and make smaller test copies. "
                + "Speech needs far fewer bits than recorders use: Opus at 16 kbps keeps a call clear at about a tenth of the size. "
                + "Each copy is decoded again and checked against the original (same length, same loudness over time). "
                + "The originals are only read. Copies go to Download/StoragePlanner/compressed/.", 14, Ui.text2, false), 0);
        LinearLayout pickRow = Ui.row(a);
        Button pick = Ui.button(a, "Pick recordings", true);
        pick.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { if (!busy) a.pickFiles(AudioLabScreen.this, "audio/*"); } });
        Button clear = Ui.button(a, "Clear", false);
        clear.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { if (!busy) { picked.clear(); showInfo(); } } });
        LinearLayout.LayoutParams l0 = new LinearLayout.LayoutParams(0, -2, 2); l0.rightMargin = dp(8);
        pickRow.addView(pick, l0); pickRow.addView(clear, new LinearLayout.LayoutParams(0, -2, 1));
        add(root, pickRow, 10);
        info = Ui.text(a, "", 13, Ui.text2, false);
        add(root, info, 6);
        spinner = new ProgressBar(a);
        spinner.setVisibility(View.GONE);
        add(root, spinner, 6);
        progress = Ui.text(a, "", 13, Ui.text2, false);
        add(root, progress, 2);

        Ui.add(root, Ui.heading(a, "Picked recordings", 17), 14);
        addRun(root, "Compress at Voice (Opus 16 kbps, test copies)", true, new Runnable() { public void run() { compress(new Level[]{Level.VOICE}); } });
        addRun(root, "All five levels (compare)", false, new Runnable() { public void run() { compress(Level.values()); } });
        addRun(root, "Check only (what each recording holds)", false, new Runnable() { public void run() { checkOnly(); } });
        StringBuilder levels = new StringBuilder("Levels:");
        for (Level l : Level.values()) levels.append("\n• ").append(l.label).append(": ").append(l.about);
        add(root, Ui.text(a, levels.toString(), 12, Ui.text2, false), 6);

        Ui.add(root, Ui.heading(a, "Recordings folders", 17), 14);
        addRun(root, "Survey recordings: how much would Voice free?", false, new Runnable() { public void run() { survey(); } });
        stopButton = Ui.button(a, "Stop", false);
        stopButton.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { stop.set(true); } });
        stopButton.setVisibility(View.GONE);
        add(root, stopButton, 6);

        Ui.add(root, Ui.heading(a, "Results", 17), 14);
        out = Ui.text(a, "Pick recordings, then compress or check them.", 12, Ui.text, false);
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

    private void add(LinearLayout p, View v, int top) {
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-1, -2); l.topMargin = dp(top);
        p.addView(v, l);
    }

    private void addRun(LinearLayout root, String label, boolean primary, final Runnable r) {
        Button b = Ui.button(a, label, primary);
        b.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { if (!busy) r.run(); } });
        add(root, b, 6);
        runButtons.add(b);
    }

    // ------------------------------------------------------------------ picking
    private static final class Picked { Uri uri; String name; long size = -1, modified; }

    /** Called by MainActivity with the picked recordings (adds to what is picked). */
    void picked(final List<Uri> uris) {
        spinner.setVisibility(View.VISIBLE);
        worker.execute(new Runnable() {
            public void run() {
                final List<Picked> got = new ArrayList<Picked>();
                for (Uri u : uris) {
                    Picked p = new Picked();
                    p.uri = u; p.name = "recording";
                    try {
                        Cursor c = a.getContentResolver().query(u, null, null, null, null);
                        if (c != null) try {
                            if (c.moveToFirst()) {
                                int ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME), si = c.getColumnIndex(OpenableColumns.SIZE),
                                    mi = c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED);
                                if (ni >= 0 && !c.isNull(ni)) p.name = c.getString(ni);
                                if (si >= 0 && !c.isNull(si)) p.size = c.getLong(si);
                                if (mi >= 0 && !c.isNull(mi)) p.modified = c.getLong(mi);
                            }
                        } finally { c.close(); }
                        if (p.size < 0) {
                            ParcelFileDescriptor pfd = a.getContentResolver().openFileDescriptor(u, "r");
                            if (pfd != null) { p.size = pfd.getStatSize(); pfd.close(); }
                        }
                        got.add(p);
                        LabLog.line(a, String.format(Locale.US, "audio lab: picked %s (%d bytes, modified %d)", p.name, p.size, p.modified));
                    } catch (Throwable t) {
                        LabLog.error(a, "reading a picked recording", t);
                    }
                }
                ui.post(new Runnable() {
                    public void run() {
                        spinner.setVisibility(View.GONE);
                        picked.addAll(got);
                        if (got.size() < uris.size()) a.toast((uris.size() - got.size()) + " of the recordings could not be read.");
                        showInfo();
                    }
                });
            }
        });
    }

    private void showInfo() {
        if (info == null) return;
        if (picked.isEmpty()) { info.setText("No recordings picked yet."); return; }
        long total = 0;
        StringBuilder s = new StringBuilder();
        for (Picked p : picked) {
            if (p.size > 0) total += p.size;
            if (s.length() < 600) s.append("\n• ").append(p.name).append(p.size >= 0 ? " · " + Report.size(p.size) : "");
        }
        info.setText(picked.size() + " recordings · " + Report.size(total) + s);
    }

    // ------------------------------------------------------------------ running
    private interface Job { void run() throws Exception; }

    private void start(final String what, final Job job) {
        busy = true; stop.set(false);
        for (Button b : runButtons) b.setEnabled(false);
        spinner.setVisibility(View.VISIBLE);
        stopButton.setVisibility(View.VISIBLE);
        a.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        shown.setLength(0);
        out.setText("Working…");
        worker.execute(new Runnable() {
            public void run() {
                LabLog.header(a);
                LabLog.line(a, "\n--- " + LabLog.now() + "  audio lab: " + what);
                long t0 = System.nanoTime();
                try { job.run(); }
                catch (Throwable t) { LabLog.error(a, "audio lab", t); line("! failed: " + t); }
                line(String.format(Locale.US, "(took %.1f s)", (System.nanoTime() - t0) / 1e9));
                ui.post(new Runnable() {
                    public void run() {
                        busy = false;
                        for (Button b : runButtons) b.setEnabled(true);
                        spinner.setVisibility(View.GONE);
                        stopButton.setVisibility(View.GONE);
                        progress.setText("");
                        a.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                        showMade();
                    }
                });
            }
        });
    }

    private void checkOnly() {
        if (picked.isEmpty()) { a.toast("Pick recordings first."); return; }
        final List<Picked> src = new ArrayList<Picked>(picked);
        start("check " + src.size() + " recordings", new Job() {
            public void run() throws Exception {
                for (Level l : Level.values()) {
                    String no = AudioCompressor.cannot(l);
                    if (no != null) line(l.label + ": " + no);
                }
                for (Picked p : src) {
                    ParcelFileDescriptor pfd = a.getContentResolver().openFileDescriptor(p.uri, "r");
                    if (pfd == null) { line(p.name + ": cannot open"); continue; }
                    try {
                        AudioCompressor.Info i = AudioCompressor.probe(pfd.getFileDescriptor(), p.size);
                        line(p.name + ": " + i.describe());
                        for (Level l : Level.values()) {
                            AudioMath.Plan pl = AudioMath.plan(l, p.size, i.durationUs, i.rate, i.channels);
                            line(String.format(Locale.US, "    %-12s %s", l.label, pl.skip != null ? "left alone: " + pl.skip
                                    : "about " + Report.size(pl.expectedBytes) + " (" + LabScreen.change(p.size, pl.expectedBytes) + "), " + pl.describe()));
                        }
                    } catch (Throwable t) {
                        line(p.name + ": cannot read: " + t.getMessage());
                    } finally { pfd.close(); }
                }
            }
        });
    }

    private void compress(final Level[] levels) {
        if (picked.isEmpty()) { a.toast("Pick recordings first."); return; }
        final List<Picked> src = new ArrayList<Picked>(picked);
        synchronized (made) { made.clear(); }
        showMade();
        start("compress " + src.size() + " recordings at " + (levels.length == 1 ? levels[0].label : "all levels"), new Job() {
            public void run() throws Exception {
                File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                        "StoragePlanner/compressed/audio-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()));
                dir.mkdirs();
                String rel = "Download/StoragePlanner/compressed/" + dir.getName() + "/";
                long before = 0, after = 0; int done = 0;
                List<String> used = new ArrayList<String>();
                for (final Picked p : src) {
                    for (final Level l : levels) {
                        if (stop.get()) { line("stopped"); return; }
                        File outFile = new File(dir, unique(used, safe(base(p.name)) + "-" + l.name().toLowerCase(Locale.US).replace('_', '-') + l.extension()));
                        ParcelFileDescriptor pfd = a.getContentResolver().openFileDescriptor(p.uri, "r");
                        if (pfd == null) { line(p.name + ": cannot open"); break; }
                        AudioCompressor.Outcome o;
                        try {
                            o = AudioCompressor.compress(pfd.getFileDescriptor(), p.size, p.modified, l, outFile, stop,
                                    new AudioCompressor.Progress() {
                                        public void at(final double d) {
                                            ui.post(new Runnable() { public void run() { progress.setText(String.format(Locale.US, "%s · %s: %.0f%%", p.name, l.label, d * 100)); } });
                                        }
                                    });
                        } finally { pfd.close(); }
                        line(p.name + " · " + l.label + ":");
                        for (String t : o.trace) line("    " + t);
                        if (!o.ok) { line("    not made: " + o.skipped); continue; }
                        android.media.MediaScannerConnection.scanFile(a, new String[]{outFile.getPath()}, null, null);   // so players list it
                        String facts = String.format(Locale.US, "%s · %s: %s → %s (%s) · %.1f s",
                                p.name, l.label, Report.size(o.before), Report.size(o.after), LabScreen.change(o.before, o.after), o.ms / 1000.0);
                        line("  " + facts);
                        before += o.before; after += o.after; done++;
                        synchronized (made) { made.add(new String[]{facts, p.uri.toString(), outFile.getPath(), rel + outFile.getName()}); }
                    }
                }
                if (done > 0 && levels.length == 1)
                    line(String.format(Locale.US, "%d copies: %s → %s, %s freed (%s)", done, Report.size(before), Report.size(after),
                            Report.size(before - after), LabScreen.change(before, after)));
                else if (done == 0) line("No copies made.");
                line("Saved in " + rel);
            }
        });
    }

    // ------------------------------------------------------------------ survey
    static final String[] FOLDERS = {"Recordings", "Call", "Music", "Sounds"};

    private void survey() {
        final File ext = Environment.getExternalStorageDirectory();
        if (!ext.canRead() || ext.list() == null) { a.toast("Grant all files access first (the app's setup screen)."); return; }
        start("survey recordings", new Job() {
            public void run() throws Exception {
                long t0 = System.nanoTime();
                long allBytes = 0, allAfter = 0;
                for (String folder : FOLDERS) {
                    File d = new File(ext, folder);
                    if (!d.isDirectory()) continue;
                    List<File> files = new ArrayList<File>();
                    walk(d, files, 0);
                    Level l = "Music".equals(folder) ? Level.MUSIC : Level.VOICE;
                    long bytes = 0, after = 0, us = 0; int n = 0, small = 0, bad = 0;
                    for (File f : files) {
                        if (stop.get()) { line("stopped"); return; }
                        n++;
                        bytes += f.length();
                        try {
                            ParcelFileDescriptor pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
                            try {
                                AudioCompressor.Info i = AudioCompressor.probe(pfd.getFileDescriptor(), f.length());
                                AudioMath.Plan pl = AudioMath.plan(l, f.length(), i.durationUs, i.rate, i.channels);
                                if (i.durationUs > 0) us += i.durationUs;
                                if (pl.skip != null) { small++; after += f.length(); }
                                else after += pl.expectedBytes;
                            } finally { pfd.close(); }
                        } catch (Throwable t) {
                            bad++; after += f.length();
                            LabLog.line(a, "  " + f.getPath() + ": " + t.getMessage());
                        }
                        if (n % 100 == 0) ui.post(progressText(folder + ": " + n + " of " + files.size()));
                    }
                    if (n == 0) continue;
                    line(String.format(Locale.US, "%s/: %d recordings, %s, %.1f h, about %d kbps on average", folder, n, Report.size(bytes),
                            us / 3.6e9, us > 0 ? Math.round(bytes * 8e3 / us) : 0));
                    line(String.format(Locale.US, "    at %s: about %s, %s freed (%s)%s%s", l.label, Report.size(after), Report.size(bytes - after),
                            LabScreen.change(bytes, after), small > 0 ? ", " + small + " already small" : "", bad > 0 ? ", " + bad + " unreadable" : ""));
                    allBytes += bytes; allAfter += after;
                }
                if (allBytes == 0) line("No recordings in " + Arrays.toString(FOLDERS) + ".");
                else line(String.format(Locale.US, "In all: %s → about %s, %s freed (%.0f s to survey)", Report.size(allBytes), Report.size(allAfter),
                        Report.size(allBytes - allAfter), (System.nanoTime() - t0) / 1e9));
            }
        });
    }

    private Runnable progressText(final String s) { return new Runnable() { public void run() { progress.setText(s); } }; }

    static final String[] AUDIO = {".m4a", ".mp3", ".aac", ".amr", ".3gp", ".3ga", ".wav", ".ogg", ".opus", ".flac", ".awb"};

    private static void walk(File d, List<File> out, int depth) {
        String[] names = d.list();
        if (names == null || depth > 8) return;
        Arrays.sort(names);
        for (String n : names) {
            if (n.startsWith(".")) continue;
            File f = new File(d, n);
            if (f.isDirectory()) walk(f, out, depth + 1);
            else {
                String l = n.toLowerCase(Locale.US);
                for (String e : AUDIO) if (l.endsWith(e)) { out.add(f); break; }
            }
        }
    }

    // ------------------------------------------------------------------ names, results
    private static String safe(String n) {
        String s = n == null ? "recording" : n.replaceAll("[\\\\/:*?\"<>|\\x00-\\x1f]", "_");
        return s.isEmpty() || s.startsWith(".") ? "recording" + s : s;
    }

    private static String base(String n) { int d = n.lastIndexOf('.'); return d > 0 ? n.substring(0, d) : n; }

    private static String unique(List<String> used, String n) {
        String b = base(n), ext = n.substring(b.length()), s = n;
        for (int k = 2; used.contains(s.toLowerCase(Locale.ROOT)); k++) s = b + " (" + k + ")" + ext;
        used.add(s.toLowerCase(Locale.ROOT));
        return s;
    }

    private void line(final String s) {
        LabLog.line(a, s);
        ui.post(new Runnable() { public void run() { shown.append(s).append('\n'); out.setText(shown); } });
    }

    private void showMade() {
        if (results == null) return;
        results.removeAllViews();
        List<String[]> copy;
        synchronized (made) { copy = new ArrayList<String[]>(made); }
        for (final String[] m : copy) {
            add(results, Ui.text(a, m[0], 13, Ui.text, false), 10);
            LinearLayout row = Ui.row(a);
            Button orig = Ui.button(a, "Play original", false);
            orig.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { playOriginal(Uri.parse(m[1])); } });
            Button copyB = Ui.button(a, "Play copy", false);
            copyB.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    File f = new File(m[2]);
                    if (!f.isFile()) { a.toast("Not found: " + m[3]); return; }
                    FileActions.openWith(a, new FileItem(m[3], m[2], f.length(), f.lastModified()));
                }
            });
            LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(0, -2, 1); l.rightMargin = dp(8);
            row.addView(orig, l); row.addView(copyB, new LinearLayout.LayoutParams(0, -2, 1));
            add(results, row, 4);
        }
    }

    private void playOriginal(Uri u) {
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(u, "audio/*");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        i.setClipData(ClipData.newRawUri("original", u));
        try { a.startActivity(Intent.createChooser(i, "Play the original with")); }
        catch (Throwable t) { a.toast("No app can play it."); }
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
}
