package com.storageplanner.app;

import android.content.ContentUris;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.net.Uri;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
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
import com.storageplanner.tools.MotionPhoto;

import java.io.File;
import java.io.FileInputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Algorithm Lab for Motion Photos (debug): pick photos and make test copies without their short video, with the same
 * code and checks a real run will use (tools/MotionPhoto), or survey the camera folder for how much removing the
 * videos would free. The picked photos are only read. Copies (and each removed video, to check it) go to
 * Download/StoragePlanner/compressed/motion-<time>/, and every step is written to the Lab's log.
 */
final class MotionLabScreen extends Screen {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<Picked> photos = new ArrayList<Picked>();
    private volatile boolean busy, stop;
    private TextView info, out;
    private ProgressBar spinner;
    private LinearLayout results;
    private Button stopButton;
    private final List<Button> runButtons = new ArrayList<Button>();
    private final StringBuilder shown = new StringBuilder();
    private final List<String[]> made = new ArrayList<String[]>();     // {facts, photo path, photo rel, video path, video rel}

    MotionLabScreen(MainActivity a) { super(a); }

    @Override public String title() { return "Motion Photo Lab"; }

    @Override public String descriptor() { return "motionlab"; }

    protected View build() {
        ScrollView sv = new ScrollView(a);
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(10), dp(14), dp(24));
        sv.addView(root);
        Ui.add(root, Ui.text(a, "Debug tool. A Motion Photo stores a short video after the photo. Pick photos to make test copies "
                + "without the video: the photo itself stays byte for byte the same. The originals are only read, never changed. "
                + "Copies, and each removed video so you can check it, go to Download/StoragePlanner/compressed/.", 14, Ui.text2, false), 0);
        LinearLayout pickRow = Ui.row(a);
        Button pick = Ui.button(a, "Pick photos", true);
        pick.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { if (!busy) a.pickFiles(MotionLabScreen.this, "image/*"); } });
        Button clear = Ui.button(a, "Clear", false);
        clear.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { if (!busy) { photos.clear(); showInfo(); } } });
        LinearLayout.LayoutParams l0 = new LinearLayout.LayoutParams(0, -2, 2); l0.rightMargin = dp(8);
        pickRow.addView(pick, l0); pickRow.addView(clear, new LinearLayout.LayoutParams(0, -2, 1));
        add(root, pickRow, 10);
        info = Ui.text(a, "", 13, Ui.text2, false);
        add(root, info, 6);
        spinner = new ProgressBar(a);
        spinner.setVisibility(View.GONE);
        add(root, spinner, 6);

        Ui.add(root, Ui.heading(a, "Picked photos", 17), 14);
        addRun(root, "Remove the videos (test copies)", true, new Runnable() { public void run() { strip(); } });
        addRun(root, "Check only (what each photo holds)", false, new Runnable() { public void run() { checkOnly(); } });

        Ui.add(root, Ui.heading(a, "Camera folder", 17), 14);
        addRun(root, "Survey DCIM: how much would removing the videos free?", false, new Runnable() { public void run() { survey(); } });
        stopButton = Ui.button(a, "Stop", false);
        stopButton.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { stop = true; } });
        stopButton.setVisibility(View.GONE);
        add(root, stopButton, 6);

        Ui.add(root, Ui.heading(a, "Results", 17), 14);
        out = Ui.text(a, "Pick photos, then remove the videos or check them.", 12, Ui.text, false);
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
        if (!locationAccess() && Build.VERSION.SDK_INT >= 29)    // without it Android hides the GPS in what the app reads
            a.requestPermissions(new String[]{android.Manifest.permission.ACCESS_MEDIA_LOCATION}, 4);
        return sv;
    }

    /** True when this app reads photos' exact bytes, location included (Android 10+ needs ACCESS_MEDIA_LOCATION). */
    private boolean locationAccess() {
        return Build.VERSION.SDK_INT < 29
                || a.checkSelfPermission(android.Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private static final String NO_LOCATION = "location access not granted: Android hides the GPS location in what this app reads, "
            + "so copies may lack the photo's location (allow it when asked, or in Settings > Apps > Storage Planner > Permissions)";

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
    /** A picked photo: its real file when this app can read it (all files access), else read through its descriptor. */
    private static final class Picked {
        Uri uri, media; String name; long size = -1, modified; File file;
        /** Whether the last read got the photo's exact bytes (location not hidden by Android), and how it was read. */
        boolean exact;
        String how = "";
    }

    /** Called by MainActivity with the picked photos (adds to what is picked). */
    void picked(final List<Uri> uris) {
        spinner.setVisibility(View.VISIBLE);
        worker.execute(new Runnable() {
            public void run() {
                final List<Picked> got = new ArrayList<Picked>();
                for (Uri u : uris) {
                    Picked p = new Picked();
                    p.uri = u; p.name = "photo";
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
                        p.media = mediaUri(u);
                        File f = p.media != null ? dataColumn(p.media) : realFile(u);
                        if (f != null && f.isFile() && f.canRead() && (p.size < 0 || f.length() == p.size)) {
                            p.file = f;
                            if (p.modified <= 0) p.modified = f.lastModified();
                        }
                        got.add(p);
                        LabLog.line(a, String.format(Locale.US, "motion lab: picked %s (%d bytes, modified %d), read %s", p.name, p.size, p.modified,
                                p.file != null ? "from " + p.file : "through the picker"));
                    } catch (Throwable t) {
                        LabLog.error(a, "reading a picked photo", t);
                    }
                }
                ui.post(new Runnable() {
                    public void run() {
                        spinner.setVisibility(View.GONE);
                        photos.addAll(got);
                        if (got.size() < uris.size()) a.toast((uris.size() - got.size()) + " of the photos could not be read.");
                        showInfo();
                    }
                });
            }
        });
    }

    /** The MediaStore item behind a picked document or media URI, or null. */
    private Uri mediaUri(Uri u) {
        try {
            if ("media".equals(u.getAuthority()) && u.getPath() != null && u.getPath().startsWith("/external")) return u;
            if (DocumentsContract.isDocumentUri(a, u) && "com.android.providers.media.documents".equals(u.getAuthority())) {
                String id = DocumentsContract.getDocumentId(u);
                if (id.startsWith("image:"))
                    return ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, Long.parseLong(id.substring(6)));
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** The file behind a picked document or media item, or null (then it is read through its descriptor). */
    private File realFile(Uri u) {
        try {
            if (DocumentsContract.isDocumentUri(a, u)) {
                String id = DocumentsContract.getDocumentId(u);
                if ("com.android.externalstorage.documents".equals(u.getAuthority()) && id.startsWith("primary:"))
                    return new File(Environment.getExternalStorageDirectory(), id.substring("primary:".length()));
                if ("com.android.providers.media.documents".equals(u.getAuthority()) && id.startsWith("image:"))
                    return dataColumn(ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, Long.parseLong(id.substring(6))));
                return null;
            }
            if ("media".equals(u.getAuthority())) return dataColumn(u);
        } catch (Throwable ignored) {}
        return null;
    }

    @SuppressWarnings("deprecation")
    private File dataColumn(Uri u) {
        try {
            Cursor c = a.getContentResolver().query(u, new String[]{MediaStore.MediaColumns.DATA}, null, null, null);
            if (c == null) return null;
            try { return c.moveToFirst() && !c.isNull(0) ? new File(c.getString(0)) : null; }
            finally { c.close(); }
        } catch (Throwable t) {
            return null;                                    // e.g. a photo-picker URI: read it through its descriptor
        }
    }

    private void showInfo() {
        if (info == null) return;
        if (photos.isEmpty()) { info.setText("No photos picked yet."); return; }
        long total = 0;
        StringBuilder s = new StringBuilder();
        for (Picked p : photos) {
            if (p.size > 0) total += p.size;
            if (s.length() < 600) s.append("\n• ").append(p.name).append(p.size >= 0 ? " · " + Report.size(p.size) : "");
        }
        info.setText(photos.size() + " photos · " + Report.size(total) + s);
    }

    // ------------------------------------------------------------------ running
    private interface Job { void run() throws Exception; }

    private void start(final String what, final Job job) {
        busy = true; stop = false;
        for (Button b : runButtons) b.setEnabled(false);
        spinner.setVisibility(View.VISIBLE);
        a.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        shown.setLength(0);
        out.setText("Working…");
        worker.execute(new Runnable() {
            public void run() {
                LabLog.header(a);
                LabLog.line(a, "\n--- " + LabLog.now() + "  motion lab: " + what);
                long t0 = System.nanoTime();
                try { job.run(); }
                catch (Throwable t) { LabLog.error(a, "motion lab", t); line("! failed: " + t); }
                line(String.format(Locale.US, "(took %.1f s)", (System.nanoTime() - t0) / 1e9));
                ui.post(new Runnable() {
                    public void run() {
                        busy = false;
                        for (Button b : runButtons) b.setEnabled(true);
                        spinner.setVisibility(View.GONE);
                        stopButton.setVisibility(View.GONE);
                        a.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                        showMade();
                    }
                });
            }
        });
    }

    /** Runs {@code r} with the photo open: from its file when readable, else through the picker's descriptor. */
    /** Gets the open photo as a FileInputStream on its file or descriptor (seekable, so ExifInterface reads HEIC too). */
    private interface WithChannel<T> { T run(FileInputStream in) throws Exception; }

    private <T> T open(Picked p, WithChannel<T> r) throws Exception {
        if (p.file != null) {                                   // a path read: exact when location access is granted
            p.exact = locationAccess();
            p.how = "file path";
            FileInputStream in = new FileInputStream(p.file);
            try { return r.run(in); } finally { in.close(); }
        }
        ParcelFileDescriptor pfd = null;
        if (p.media != null && Build.VERSION.SDK_INT >= 29 && locationAccess()) {
            try { pfd = a.getContentResolver().openFileDescriptor(MediaStore.setRequireOriginal(p.media), "r"); p.exact = pfd != null; p.how = "MediaStore original"; }
            catch (Exception e) { pfd = null; }                 // no read access to the MediaStore item itself: use the picked URI
        }
        if (pfd == null) {
            pfd = a.getContentResolver().openFileDescriptor(p.uri, "r");
            p.exact = Build.VERSION.SDK_INT < 29;               // Android 10+ may hide the location in what this gives
            p.how = "picked URI";
        }
        if (pfd == null) throw new java.io.IOException("cannot open " + p.name);
        try {
            FileInputStream in = new FileInputStream(pfd.getFileDescriptor());
            try { return r.run(in); } finally { in.close(); }
        } finally { pfd.close(); }
    }

    private void checkOnly() {
        if (photos.isEmpty()) { a.toast("Pick photos first."); return; }
        final List<Picked> src = new ArrayList<Picked>(photos);
        start("check " + src.size() + " photos", new Job() {
            public void run() throws Exception {
                long video = 0; int motion = 0;
                for (final Picked p : src) {
                    MotionPhoto.Found f;
                    try {
                        f = open(p, new WithChannel<MotionPhoto.Found>() {
                            public MotionPhoto.Found run(FileInputStream in) throws Exception { return MotionPhoto.find(in.getChannel()); }
                        });
                    } catch (Throwable t) { line(p.name + ": cannot read: " + t.getMessage()); continue; }
                    line(describe(p.name, f));
                    if (f.motion) { motion++; video += f.videoBytes(); }
                }
                line(String.format(Locale.US, "%d of %d are Motion Photos; their videos take %s", motion, src.size(), Report.size(video)));
            }
        });
    }

    private static String describe(String name, MotionPhoto.Found f) {
        if (f.motion) {
            StringBuilder s = new StringBuilder(String.format(Locale.US, "%s: %s (%s), %s, of which the video is %s (%.0f%%)",
                    name, f.kind, f.format, Report.size(f.size), Report.size(f.videoBytes()), 100.0 * f.videoBytes() / Math.max(1, f.size)));
            for (String n : f.notes) s.append("\n    ").append(n);
            return s.toString();
        }
        return name + ": " + (f.why != null ? "left alone: " + f.why : "not a Motion Photo" + (f.format != null ? " (" + f.format + ")" : ""));
    }

    private void strip() {
        if (photos.isEmpty()) { a.toast("Pick photos first."); return; }
        final List<Picked> src = new ArrayList<Picked>(photos);
        synchronized (made) { made.clear(); }
        showMade();
        start("remove the videos from " + src.size() + " photos", new Job() {
            public void run() throws Exception {
                final File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                        "StoragePlanner/compressed/motion-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()));
                dir.mkdirs();
                String rel = "Download/StoragePlanner/compressed/" + dir.getName() + "/";
                if (!locationAccess()) line("! " + NO_LOCATION);
                else line("location access in photos: granted");
                long before = 0, after = 0; int done = 0;
                List<String> used = new ArrayList<String>();
                for (final Picked p : src) {
                    if (stop) break;
                    String name = unique(used, safe(p.name));
                    final File outFile = new File(dir, name);
                    final File vidFile = new File(dir, unique(used, base(name) + "-video.mp4"));
                    MotionPhoto.Outcome o;
                    try {
                        o = open(p, new WithChannel<MotionPhoto.Outcome>() {
                            public MotionPhoto.Outcome run(FileInputStream in) { return MotionPhoto.strip(in.getChannel(), p.modified, outFile, vidFile); }
                        });
                    } catch (Throwable t) { line(p.name + ": cannot read: " + t.getMessage()); continue; }
                    if (!o.ok) {
                        line(p.name + ": " + (o.found != null && o.found.motion ? "not made: " : "left alone: ") + o.skipped);
                        for (String t : o.trace) LabLog.line(a, "    " + t);
                        continue;
                    }
                    for (String t : o.trace) line("    " + t);
                    String decoded = sameDecode(p, outFile);
                    line("    on this phone: " + decoded);
                    line("    " + location(p, outFile));
                    android.media.MediaScannerConnection.scanFile(a, new String[]{outFile.getPath(), vidFile.getPath()}, null, null);   // so galleries list them
                    if (!p.exact) line("    ! the location may be missing from this copy: this photo could not be read with its location (Android hides it)");
                    String facts = String.format(Locale.US, "%s: %s → %s (%s) · video %s · %.1f s",
                            p.name, Report.size(o.before), Report.size(o.after), LabScreen.change(o.before, o.after),
                            Report.size(o.found.videoBytes()), o.ms / 1000.0);
                    line("  " + facts);
                    before += o.before; after += o.after; done++;
                    synchronized (made) {
                        made.add(new String[]{facts + "\n" + decoded, outFile.getPath(), rel + outFile.getName(), vidFile.getPath(), rel + vidFile.getName()});
                    }
                }
                if (done > 0)
                    line(String.format(Locale.US, "%d Motion Photos: %s → %s, %s freed (%s)", done, Report.size(before), Report.size(after),
                            Report.size(before - after), LabScreen.change(before, after)));
                else line("No Motion Photos among the picked photos.");
                line("Saved in " + rel);
            }
        });
    }

    /** The GPS position in the original (as this app reads it) and in the copy, from their EXIF. */
    private String location(final Picked p, File copy) {
        String orig, cp;
        try {
            orig = open(p, new WithChannel<String>() {
                public String run(FileInputStream in) throws Exception {
                    return latLong(new android.media.ExifInterface(in.getFD()));   // seekable: works for HEIC as well as JPEG
                }
            });
        } catch (Throwable t) { orig = "unreadable (" + t.getMessage() + ")"; }
        try { cp = latLong(new android.media.ExifInterface(copy.getPath())); }
        catch (Throwable t) { cp = "unreadable (" + t.getMessage() + ")"; }
        return "location: original " + orig + " (read by " + p.how + (p.exact ? ", exact" : ", may be hidden") + ") · copy " + cp
                + (orig.equals(cp) ? " · same" : " · DIFFERENT");
    }

    @SuppressWarnings("deprecation")
    private static String latLong(android.media.ExifInterface e) {
        float[] ll = new float[2];
        if (!e.getLatLong(ll)) return e.getAttribute(android.media.ExifInterface.TAG_GPS_LATITUDE) != null ? "zeroed GPS tags" : "none";
        return String.format(Locale.US, "%.5f, %.5f", ll[0], ll[1]);
    }

    /** Decodes the original and the copy (scaled down alike) and compares every pixel. */
    private String sameDecode(Picked p, File copy) {
        try {
            BitmapFactory.Options bo = new BitmapFactory.Options();
            bo.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(copy.getPath(), bo);
            if (bo.outWidth <= 0) return "the copy does not decode!";
            int sample = 1;
            while (Math.max(bo.outWidth, bo.outHeight) / sample > 2048) sample *= 2;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            Bitmap b = BitmapFactory.decodeFile(copy.getPath(), o);
            Bitmap orig;
            if (p.file != null) orig = BitmapFactory.decodeFile(p.file.getPath(), o);
            else {
                java.io.InputStream in = a.getContentResolver().openInputStream(p.uri);
                try { orig = BitmapFactory.decodeStream(in, null, o); } finally { if (in != null) in.close(); }
            }
            if (b == null || orig == null) return "could not decode both to compare";
            try {
                if (b.getWidth() != orig.getWidth() || b.getHeight() != orig.getHeight()) return "the copy decodes to a different size!";
                int w = b.getWidth(), h = b.getHeight();
                int[] r1 = new int[w], r2 = new int[w];
                for (int y = 0; y < h; y++) {
                    b.getPixels(r1, 0, w, 0, y, w, 1);
                    orig.getPixels(r2, 0, w, 0, y, w, 1);
                    if (!Arrays.equals(r1, r2)) return "the copy decodes to different pixels (row " + y + ")!";
                }
                return String.format(Locale.US, "decodes to the same %d×%d pixels as the original (%d×%d, checked at 1/%d)", w, h, bo.outWidth, bo.outHeight, sample);
            } finally { b.recycle(); orig.recycle(); }
        } catch (Throwable t) {
            return "could not compare: " + t;
        }
    }

    private void survey() {
        final File dcim = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM);
        if (!dcim.canRead() || dcim.list() == null) { a.toast("Grant all files access first (the app's setup screen)."); return; }
        stopButton.setVisibility(View.VISIBLE);
        start("survey " + dcim, new Job() {
            public void run() throws Exception {
                List<File> files = new ArrayList<File>();
                walk(dcim, files, 0);
                line(files.size() + " JPEG/HEIC photos in DCIM (only read)");
                long total = 0, video = 0; int motion = 0, refused = 0, n = 0;
                java.util.Map<String, int[]> kinds = new java.util.TreeMap<String, int[]>();
                long t0 = System.nanoTime();
                for (File f : files) {
                    if (stop) { line("stopped"); break; }
                    n++;
                    try {
                        MotionPhoto.Found m = MotionPhoto.find(f);
                        total += f.length();
                        if (m.motion) {
                            motion++; video += m.videoBytes();
                            int[] k = kinds.get(m.kind + " " + m.format);
                            if (k == null) kinds.put(m.kind + " " + m.format, k = new int[1]);
                            k[0]++;
                        } else if (m.why != null) {
                            refused++;
                            LabLog.line(a, "  " + f.getName() + ": " + m.why);
                        }
                    } catch (Throwable t) {
                        LabLog.line(a, "  " + f.getName() + ": cannot read: " + t);
                    }
                    if (n % 200 == 0)
                        line(String.format(Locale.US, "  %d of %d checked: %d Motion Photos, videos %s (%.0f s)", n, files.size(), motion, Report.size(video),
                                (System.nanoTime() - t0) / 1e9));
                }
                for (java.util.Map.Entry<String, int[]> e : kinds.entrySet()) line("  " + e.getValue()[0] + " × " + e.getKey());
                if (refused > 0) line("  " + refused + " left alone (why: in the saved log)");
                line(String.format(Locale.US, "%d of %d photos are Motion Photos. Removing their videos would free %s of %s (%s).",
                        motion, n, Report.size(video), Report.size(total), LabScreen.change(total, total - video)));
            }
        });
    }

    private static void walk(File d, List<File> out, int depth) {
        String[] names = d.list();
        if (names == null || depth > 8) return;
        Arrays.sort(names);
        for (String n : names) {
            if (n.startsWith(".")) continue;                      // .thumbnails, hidden work files
            File f = new File(d, n);
            if (f.isDirectory()) walk(f, out, depth + 1);
            else {
                String l = n.toLowerCase(Locale.US);
                if (l.endsWith(".jpg") || l.endsWith(".jpeg") || l.endsWith(".heic") || l.endsWith(".heif") || l.endsWith(".avif")) out.add(f);
            }
        }
    }

    private static String safe(String n) {
        String s = n == null ? "photo.jpg" : n.replaceAll("[\\\\/:*?\"<>|\\x00-\\x1f]", "_");
        return s.isEmpty() || s.startsWith(".") ? "photo" + s : s;
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
            Button photo = Ui.button(a, "Open photo", false);
            photo.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { openWith(m[1], m[2]); } });
            Button video = Ui.button(a, "Play removed video", false);
            video.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { openWith(m[3], m[4]); } });
            LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(0, -2, 1); l.rightMargin = dp(8);
            row.addView(photo, l); row.addView(video, new LinearLayout.LayoutParams(0, -2, 1));
            add(results, row, 4);
        }
    }

    private void openWith(String path, String rel) {
        File f = new File(path);
        if (!f.isFile()) { a.toast("Not found: " + rel); return; }
        FileActions.openWith(a, new FileItem(rel, path, f.length(), f.lastModified()));
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
