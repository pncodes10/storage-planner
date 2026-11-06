package com.storageplanner.app;

import android.database.Cursor;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
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
import com.storageplanner.tools.ZipCompressor;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Algorithm Lab for zip archives (debug): pick any files and put them into a test .zip at one level or all four, with
 * the same code and checks a real run will use (tools/ZipCompressor). The picked files are only read. Archives go to
 * Download/StoragePlanner/compressed/zip-<time>/, and every step is written to the Lab's log.
 */
final class ZipLabScreen extends Screen {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<ZipCompressor.Source> files = new ArrayList<ZipCompressor.Source>();
    private boolean busy;
    private TextView info, out;
    private ProgressBar spinner;
    private LinearLayout results;
    private final List<Button> runButtons = new ArrayList<Button>();
    private final StringBuilder shown = new StringBuilder();
    private final List<String[]> made = new ArrayList<String[]>();     // {label, path, rel}

    ZipLabScreen(MainActivity a) { super(a); }

    @Override public String title() { return "Zip Lab"; }

    @Override public String descriptor() { return "ziplab"; }

    protected View build() {
        ScrollView sv = new ScrollView(a);
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(10), dp(14), dp(24));
        sv.addView(root);
        Ui.add(root, Ui.text(a, "Debug tool. Pick any files and put them into one .zip at each level, with the same code and checks a real "
                + "run will use. The files are only read, never changed. Archives go to Download/StoragePlanner/compressed/. "
                + "Photos, videos and music are already compressed: a zip barely shrinks them, so they are stored as they are.", 14, Ui.text2, false), 0);
        LinearLayout pickRow = Ui.row(a);
        Button pick = Ui.button(a, "Pick files", true);
        pick.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { if (!busy) a.pickFiles(ZipLabScreen.this); } });
        Button clear = Ui.button(a, "Clear", false);
        clear.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { if (!busy) { files.clear(); showInfo(); } }
        });
        LinearLayout.LayoutParams l0 = new LinearLayout.LayoutParams(0, -2, 2); l0.rightMargin = dp(8);
        pickRow.addView(pick, l0); pickRow.addView(clear, new LinearLayout.LayoutParams(0, -2, 1));
        add(root, pickRow, 10);
        info = Ui.text(a, "", 13, Ui.text2, false);
        add(root, info, 6);
        spinner = new ProgressBar(a);
        spinner.setVisibility(View.GONE);
        add(root, spinner, 6);

        Ui.add(root, Ui.heading(a, "Zip (test archives)", 17), 14);
        addRun(root, "All four levels", true, null);
        for (ZipCompressor.Level l : ZipCompressor.Level.values())
            addRun(root, "Level " + l.number() + ": " + l.label + (l == ZipCompressor.Level.STORE ? " (no compression)" : " (Deflate " + l.deflate + ")"), false, l);

        Ui.add(root, Ui.heading(a, "Results", 17), 14);
        out = Ui.text(a, "Pick files, then a level.", 12, Ui.text, false);
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

    private void addRun(LinearLayout root, String label, boolean primary, final ZipCompressor.Level level) {
        Button b = Ui.button(a, label, primary);
        b.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { run(level); } });
        add(root, b, 6);
        runButtons.add(b);
    }

    // ------------------------------------------------------------------ picking
    /** A picked document, read through the content resolver each time the tool opens it (nothing is copied). */
    private static final class UriSource implements ZipCompressor.Source {
        final MainActivity a; final Uri uri; final String name; final long size, modified;
        UriSource(MainActivity a, Uri uri, String name, long size, long modified) { this.a = a; this.uri = uri; this.name = name; this.size = size; this.modified = modified; }
        public String name() { return name; }
        public long size() { return size; }
        public long modified() { return modified; }
        public InputStream open() throws IOException {
            InputStream in = a.getContentResolver().openInputStream(uri);
            if (in == null) throw new IOException("cannot open " + name);
            return in;
        }
        public File file() { return null; }
        @Override public String toString() { return name; }
    }

    /** Called by MainActivity with the picked files: reads their names, sizes and dates (adds to what is picked). */
    void picked(final List<Uri> uris) {
        spinner.setVisibility(View.VISIBLE);
        worker.execute(new Runnable() {
            public void run() {
                final List<ZipCompressor.Source> got = new ArrayList<ZipCompressor.Source>();
                for (Uri u : uris) {
                    String n = "file"; long size = -1, modified = 0;
                    try {
                        Cursor c = a.getContentResolver().query(u, null, null, null, null);
                        if (c != null) try {
                            if (c.moveToFirst()) {
                                int ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME), si = c.getColumnIndex(OpenableColumns.SIZE),
                                    mi = c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED);
                                if (ni >= 0 && !c.isNull(ni)) n = c.getString(ni);
                                if (si >= 0 && !c.isNull(si)) size = c.getLong(si);
                                if (mi >= 0 && !c.isNull(mi)) modified = c.getLong(mi);
                            }
                        } finally { c.close(); }
                        got.add(new UriSource(a, u, n, size, modified));
                        LabLog.line(a, String.format(Locale.US, "zip lab: picked %s (%d bytes, modified %d)", n, size, modified));
                    } catch (Throwable t) {
                        LabLog.error(a, "reading a picked file", t);
                    }
                }
                ui.post(new Runnable() {
                    public void run() {
                        spinner.setVisibility(View.GONE);
                        files.addAll(got);
                        if (got.size() < uris.size()) a.toast((uris.size() - got.size()) + " of the files could not be read.");
                        showInfo();
                    }
                });
            }
        });
    }

    private void showInfo() {
        if (info == null) return;
        if (files.isEmpty()) { info.setText("No files picked yet."); return; }
        long total = 0;
        StringBuilder s = new StringBuilder();
        for (ZipCompressor.Source f : files) {
            if (f.size() > 0) total += f.size();
            if (s.length() < 600) s.append("\n• ").append(f.name()).append(f.size() >= 0 ? " · " + Report.size(f.size()) : "");
        }
        info.setText(files.size() + " files · " + Report.size(total) + s);
    }

    // ------------------------------------------------------------------ running
    private void run(final ZipCompressor.Level only) {
        if (busy) return;
        if (files.isEmpty()) { a.toast("Pick files first."); return; }
        busy = true;
        for (Button b : runButtons) b.setEnabled(false);
        spinner.setVisibility(View.VISIBLE);
        a.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        shown.setLength(0);
        out.setText("Zipping…");
        synchronized (made) { made.clear(); }
        showMade();
        final List<ZipCompressor.Source> src = new ArrayList<ZipCompressor.Source>(files);
        worker.execute(new Runnable() {
            public void run() {
                LabLog.header(a);
                LabLog.line(a, "\n--- " + LabLog.now() + "  zip lab: " + src.size() + " files" + (only == null ? ", all levels" : ", level " + only.number()));
                long t0 = System.nanoTime();
                try {
                    File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                            "StoragePlanner/compressed/zip-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()));
                    dir.mkdirs();
                    for (ZipCompressor.Level l : ZipCompressor.Level.values()) {
                        if (only != null && l != only) continue;
                        line(String.format(Locale.US, "Level %d (%s) …", l.number(), l.label));
                        File outFile = new File(dir, "files-L" + l.number() + "-" + l.name().toLowerCase(Locale.US) + ".zip");
                        ZipCompressor.Outcome o = ZipCompressor.zip(src, outFile, l);
                        for (String t : o.trace) line("    " + t);
                        if (!o.ok) { line(String.format(Locale.US, "  not made: %s (%.1f s)", o.skipped, o.ms / 1000.0)); continue; }
                        String facts = String.format(Locale.US, "%s → %s (%s) · %d files, %d stored as they are · %.1f s",
                                Report.size(o.before), Report.size(o.after), LabScreen.change(o.before, o.after), o.entries.size(), o.stored(), o.ms / 1000.0);
                        line("  " + facts);
                        synchronized (made) {
                            made.add(new String[]{"Level " + l.number() + " · " + l.label + " · " + facts, outFile.getPath(),
                                    "Download/StoragePlanner/compressed/" + dir.getName() + "/" + outFile.getName()});
                        }
                    }
                    line("Saved in Download/StoragePlanner/compressed/" + dir.getName() + "/");
                } catch (Throwable t) {
                    LabLog.error(a, "zip lab", t);
                    line("! failed: " + t);
                }
                line(String.format(Locale.US, "(took %.1f s)", (System.nanoTime() - t0) / 1e9));
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
            Button share = Ui.button(a, "Share this zip", false);
            share.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    File f = new File(m[1]);
                    FileActions.share(a, new FileItem(m[2], m[1], f.length(), f.lastModified()));
                }
            });
            add(results, share, 4);
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
}
