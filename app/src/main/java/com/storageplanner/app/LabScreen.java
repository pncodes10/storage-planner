package com.storageplanner.app;

import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import com.storageplanner.tools.CopyPaths;
import com.storageplanner.tools.Guard;
import com.storageplanner.tools.HeifEncoder;
import com.storageplanner.tools.ImageCompressor;
import com.storageplanner.core.FileItem;
import com.storageplanner.core.Lab;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Algorithm Lab (debug): pick pictures, run any of the app's picture algorithms on them one by one, see what each
 * decides (faces and eyes are drawn on the picture) and keep a full log that can be saved to Downloads and shared.
 */
public final class LabScreen extends Screen {
    static final int MAX = 20;
    private static final String[] AREAS = {"camera", "screenshot", "wa_image", "download"};
    private static final String[] AREA_LABEL = {"Camera photo", "Screenshot", "WhatsApp image", "Download"};

    private final List<Lab.Pic> pics = new ArrayList<Lab.Pic>();
    private int current, area = -1;          // area -1 = automatic: by each picture's file name
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean busy, headerWritten;

    private LinearLayout strip, patchRow, buttons;
    private ZoomImageView preview;
    private TextView out, info, logInfo;
    private ProgressBar spinner;
    private Button areaBtn;
    private final List<Button> runButtons = new ArrayList<Button>();
    private final StringBuilder shown = new StringBuilder();
    /** Test copies made with "Compress", newest last: one per way of saving for each picture. */
    private final List<Compressed> compressed = new ArrayList<Compressed>();
    private LinearLayout compareBox;

    private static final class Compressed {
        final Lab.Pic pic; final String path, label, facts; final long before, after; final boolean chosen, skipped;
        Compressed(Lab.Pic pic, String path, String label, String facts, long before, long after, boolean chosen, boolean skipped) {
            this.pic = pic; this.path = path; this.label = label; this.facts = facts; this.before = before; this.after = after;
            this.chosen = chosen; this.skipped = skipped;
        }
    }

    public LabScreen(MainActivity a) { super(a); }

    /** Opens the Lab with pictures already chosen (e.g. a group from the compare screen). */
    static LabScreen with(MainActivity a, List<FileItem> files) {
        LabScreen s = new LabScreen(a);
        for (FileItem f : files) {
            if (s.pics.size() >= MAX) break;
            Lab.Pic p = new Lab.Pic(f.abs, f.name(), f.size, f.mtime);
            p.area = f.area != null && f.area.startsWith("wa_") ? "wa_image" : "screenshot".equals(f.area) ? "screenshot" : "download".equals(f.area) ? "download" : "camera";
            s.pics.add(p);
        }
        return s;
    }

    public String title() { return "Algorithm Lab"; }

    @Override public String descriptor() { return "lab"; }

    protected View build() {
        ScrollView sv = new ScrollView(a);
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(10), dp(14), dp(24));
        sv.addView(root);
        Ui.add(root, Ui.text(a, "Debug tool. Pick one or more pictures, then run any algorithm the app uses. Results are the same as in a scan; "
                + "everything is written to a log you can save and send.", 14, Ui.text2, false), 0);

        // videos, zips and Motion Photos have their own screens: no pictures need to be picked for them
        Button videoLab = Ui.button(a, "Video compression: pick a video (test copies at 5 levels)", true);
        videoLab.setContentDescription("Open the Video Lab: compress test copies of a video at each of the five levels");
        videoLab.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { a.push(new VideoLabScreen(a)); } });
        add(root, videoLab, 10);
        Button zipLab = Ui.button(a, "Zip compression: pick files (test archives at 4 levels)", true);
        zipLab.setContentDescription("Open the Zip Lab: put picked files into a test zip archive at each of the four levels");
        zipLab.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { a.push(new ZipLabScreen(a)); } });
        add(root, zipLab, 6);
        Button motionLab = Ui.button(a, "Motion Photos: remove the short video, keep the photo", true);
        motionLab.setContentDescription("Open the Motion Photo Lab: make test copies of Motion Photos without their video, or survey the camera folder");
        motionLab.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { a.push(new MotionLabScreen(a)); } });
        add(root, motionLab, 6);

        Ui.add(root, Ui.heading(a, "Pictures", 17), 14);
        LinearLayout pickRow = Ui.row(a);
        Button pick = Ui.button(a, "Pick pictures", true);
        pick.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { a.pickImages(LabScreen.this); } });
        Button clear = Ui.button(a, "Clear", false);
        clear.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { pics.clear(); current = 0; compressed.clear(); showCompared(); showPics(); }   // slots are reused by the next picks
        });
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(0, -2, 1); pl.rightMargin = dp(8);
        pickRow.addView(pick, pl); pickRow.addView(clear, new LinearLayout.LayoutParams(-2, -2));
        add(root, pickRow, 10);

        HorizontalScrollView hs = new HorizontalScrollView(a);
        strip = Ui.row(a);
        hs.addView(strip);
        add(root, hs, 8);
        info = Ui.text(a, "", 13, Ui.text2, false);
        add(root, info, 4);

        FrameLayout box = new FrameLayout(a);
        box.setBackground(Ui.round(0xFF101014, dp(12)));
        box.setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND);
        box.setClipToOutline(true);
        preview = new ZoomImageView(a);
        box.addView(preview, new FrameLayout.LayoutParams(-1, -1));
        spinner = new ProgressBar(a);
        spinner.setVisibility(View.GONE);
        box.addView(spinner, new FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER));
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, dp(340)); bl.topMargin = dp(8);
        root.addView(box, bl);
        HorizontalScrollView ps = new HorizontalScrollView(a);
        patchRow = Ui.row(a);
        ps.addView(patchRow);
        add(root, ps, 6);

        areaBtn = Ui.button(a, "Treat as: " + areaLabel(), false);
        areaBtn.setContentDescription("How to treat the picture when sorting: " + areaLabel() + ". Double tap to change.");
        areaBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                area = area + 1 >= AREAS.length ? -1 : area + 1;
                areaBtn.setText("Treat as: " + areaLabel());
                areaBtn.setContentDescription("How to treat the picture when sorting: " + areaLabel() + ". Double tap to change.");
                for (Lab.Pic p : pics) p.area = area < 0 ? Lab.areaFromName(p.name) : AREAS[area];
            }
        });
        add(root, areaBtn, 10);

        Ui.add(root, Ui.heading(a, "Run on the selected picture", 17), 14);
        buttons = new LinearLayout(a);
        buttons.setOrientation(LinearLayout.VERTICAL);
        root.addView(buttons, new LinearLayout.LayoutParams(-1, -2));
        addRun("Run everything", true, new Job() { public void run(Lab.Out o) throws Exception { everything(o); } });
        addRun("Face detection", false, new Job() { public void run(Lab.Out o) throws Exception { show(Lab.faces(pf(), pic(), o)); } });
        addRun("Faces: other sizes and tilts", false, new Job() { public void run(Lab.Out o) throws Exception { show(Lab.facesDetailed(pf(), pic(), o)); } });
        addRun("Eyes open or closed", false, new Job() { public void run(Lab.Out o) throws Exception { show(Lab.eyes(pf(), pic(), o)); } });
        addRun("Sharpness / blur", false, new Job() { public void run(Lab.Out o) throws Exception { show(Lab.sharpness(pf(), pic(), o)); } });
        addRun("Lighting", false, new Job() { public void run(Lab.Out o) throws Exception { show(Lab.lighting(pf(), pic(), o)); } });
        addRun("Picture category", false, new Job() { public void run(Lab.Out o) throws Exception { show(Lab.category(pf(), pic(), System.currentTimeMillis(), o)); } });
        addRun("QR code / barcode", false, new Job() { public void run(Lab.Out o) throws Exception { Lab.codes(pf(), pic(), o); } });
        addRun("Read text", false, new Job() { public void run(Lab.Out o) throws Exception { Lab.text(pf(), pic(), System.currentTimeMillis(), o); } });
        addRun("Compress (test copy)", false, new Job() { public void run(Lab.Out o) throws Exception { compress(pic(), o); } });
        Ui.add(root, Ui.heading(a, "Run on all picked pictures", 17), 14);
        LinearLayout groupButtons = new LinearLayout(a);
        groupButtons.setOrientation(LinearLayout.VERTICAL);
        root.addView(groupButtons, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout keep = buttons;
        buttons = groupButtons;
        addRun("Run the algorithm (keep, suggest or delete?)", true, new Job() {
            public void run(Lab.Out o) throws Exception {
                Lab.algorithm(pf(), new ArrayList<Lab.Pic>(pics), System.currentTimeMillis(), com.storageplanner.core.Policy.active, o);
            }
        });
        addRun("Best shot among them", false, new Job() { public void run(Lab.Out o) throws Exception { show(Lab.best(pf(), new ArrayList<Lab.Pic>(pics), o)); } });
        addRun("Near-duplicate check", false, new Job() { public void run(Lab.Out o) throws Exception { Lab.nearDuplicates(pf(), new ArrayList<Lab.Pic>(pics), o); } });
        addRun("Exact duplicate check", false, new Job() { public void run(Lab.Out o) throws Exception { Lab.exactDuplicates(new ArrayList<Lab.Pic>(pics), o); } });
        addRun("Compress all (test copies)", false, new Job() {
            public void run(Lab.Out o) throws Exception { for (Lab.Pic p : new ArrayList<Lab.Pic>(pics)) compress(p, o); }
        });
        buttons = keep;

        Ui.add(root, Ui.heading(a, "Results", 17), 14);
        out = Ui.text(a, "Pick a picture and run an algorithm.", 12, Ui.text, false);
        out.setTypeface(Typeface.MONOSPACE);
        out.setTextIsSelectable(true);
        out.setBackground(Ui.round(Ui.surface, dp(8)));
        out.setPadding(dp(10), dp(8), dp(10), dp(8));
        out.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        add(root, out, 6);
        compareBox = new LinearLayout(a);
        compareBox.setOrientation(LinearLayout.VERTICAL);
        add(root, compareBox, 6);
        showCompared();

        Ui.add(root, Ui.heading(a, "Log", 17), 14);
        logInfo = Ui.text(a, "", 13, Ui.text2, false);
        add(root, logInfo, 2);
        LinearLayout logRow = Ui.row(a);
        Button save = Ui.button(a, "Save log", true);
        save.setContentDescription("Save the log to Download/StoragePlanner");
        save.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { saveLog(false); } });
        Button share = Ui.button(a, "Share log", false);
        share.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { saveLog(true); } });
        Button clearLog = Ui.button(a, "Clear log", false);
        clearLog.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { LabLog.clear(a); headerWritten = false; updateLogInfo(); } });
        Button copy = Ui.button(a, "Copy log", false);
        copy.setContentDescription("Copy the log to the clipboard, to paste it into a message");
        copy.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { copyLog(); } });
        LinearLayout.LayoutParams l1 = new LinearLayout.LayoutParams(0, -2, 1); l1.rightMargin = dp(8);
        logRow.addView(save, l1); logRow.addView(share, new LinearLayout.LayoutParams(0, -2, 1));
        add(root, logRow, 6);
        LinearLayout logRow2 = Ui.row(a);
        LinearLayout.LayoutParams l3 = new LinearLayout.LayoutParams(0, -2, 1); l3.rightMargin = dp(8);
        logRow2.addView(copy, l3); logRow2.addView(clearLog, new LinearLayout.LayoutParams(0, -2, 1));
        add(root, logRow2, 6);
        showPics();
        updateLogInfo();
        return sv;
    }

    private void add(LinearLayout p, View v, int top) {
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-1, -2); l.topMargin = dp(top);
        p.addView(v, l);
    }

    private String areaLabel() { return area < 0 ? "Automatic (by file name)" : AREA_LABEL[area]; }

    interface Job { void run(Lab.Out o) throws Exception; }

    private void addRun(final String label, boolean primary, final Job job) {
        Button b = Ui.button(a, label, primary);
        b.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { run(label, job); } });
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-1, -2); l.topMargin = dp(6);
        buttons.addView(b, l);
        runButtons.add(b);
    }

    private AndroidPlatform platform;
    private AndroidPlatform pf() { if (platform == null) platform = new AndroidPlatform(a); return platform; }
    private Lab.Pic pic() { return pics.get(Math.min(current, pics.size() - 1)); }

    // ------------------------------------------------------------------ running
    private void run(final String label, final Job job) {
        if (busy) return;
        if (pics.isEmpty()) { a.toast("Pick a picture first."); return; }
        busy = true;
        setEnabled(false);
        spinner.setVisibility(View.VISIBLE);
        shown.setLength(0);
        out.setText(label + "…");
        final Lab.Pic p = pic();
        worker.execute(new Runnable() {
            public void run() {
                if (!headerWritten) { LabLog.header(a); headerWritten = true; }
                LabLog.line(a, "\n--- " + LabLog.now() + "  " + label + "  (selected: " + p.name + ", " + pics.size() + " picked)");
                for (Lab.Pic x : pics) LabLog.line(a, String.format(Locale.US, "    picked %s: %d bytes, modified %s, treated as %s", x.name, x.size,
                        new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new java.util.Date(x.mtime)), x.area));
                final long t0 = System.nanoTime();
                Lab.Out o = new Lab.Out() {
                    public void line(final String s) {
                        LabLog.line(a, s);
                        ui.post(new Runnable() { public void run() { shown.append(s).append('\n'); out.setText(shown); } });
                    }
                };
                try { job.run(o); }
                catch (Throwable t) { LabLog.error(a, label, t); o.line("! " + label + " failed: " + t); }
                o.line(String.format(Locale.US, "(%s took %d ms)", label, (System.nanoTime() - t0) / 1000000));
                ui.post(new Runnable() {
                    public void run() {
                        busy = false; setEnabled(true); spinner.setVisibility(View.GONE); updateLogInfo();
                        out.announceForAccessibility(label + " finished");
                    }
                });
            }
        });
    }

    private void everything(Lab.Out o) throws Exception {
        Lab.Pic p = pic();
        show(Lab.faces(pf(), p, o));
        Lab.facesDetailed(pf(), p, o);
        show(Lab.eyes(pf(), p, o));
        Lab.sharpness(pf(), p, o);
        Lab.lighting(pf(), p, o);
        Lab.codes(pf(), p, o);
        Lab.category(pf(), p, System.currentTimeMillis(), o);
        if (pics.size() >= 2) {
            Lab.best(pf(), new ArrayList<Lab.Pic>(pics), o);
            Lab.nearDuplicates(pf(), new ArrayList<Lab.Pic>(pics), o);
            Lab.exactDuplicates(new ArrayList<Lab.Pic>(pics), o);
        }
    }

    /** Draws what a run found on the preview (faces with eyes open / closed) and the eye patches the model saw. */
    private void show(final Lab.Shown s) {
        if (s == null) return;
        ui.post(new Runnable() {
            public void run() {
                preview.setFaces(s.faces != null && s.faces.length > 0 ? s.faces : null, 5);
                if (s.patches.isEmpty()) return;
                patchRow.removeAllViews();
                for (int i = 0; i < s.patches.size(); i++) {
                    LinearLayout c = new LinearLayout(a);
                    c.setOrientation(LinearLayout.VERTICAL);
                    ImageView iv = new ImageView(a);
                    Bitmap b = Bitmap.createBitmap(s.patches.get(i), com.storageplanner.core.EyeState.PW, com.storageplanner.core.EyeState.PH, Bitmap.Config.ARGB_8888);
                    android.graphics.drawable.BitmapDrawable d = new android.graphics.drawable.BitmapDrawable(a.getResources(), b);
                    d.setFilterBitmap(false);                              // show the model's pixels as they are
                    iv.setImageDrawable(d);
                    iv.setScaleType(ImageView.ScaleType.FIT_XY);
                    iv.setContentDescription("Eye patch " + s.patchLabels.get(i));
                    c.addView(iv, new LinearLayout.LayoutParams(dp(96), dp(64)));
                    c.addView(Ui.text(a, s.patchLabels.get(i), 11, Ui.text2, false));
                    LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-2, -2); l.rightMargin = dp(8);
                    patchRow.addView(c, l);
                }
            }
        });
    }

    // ------------------------------------------------------------------ compression test
    /** "Treat as" decides the kind: screenshots become lossless WebP, everything else HEIC or WebP (chat images like photos). */
    private static ImageCompressor.Kind kindFor(Lab.Pic p) {
        return "screenshot".equals(p.area) ? ImageCompressor.Kind.LOSSLESS : ImageCompressor.Kind.PHOTO;
    }

    /**
     * Compresses the Lab's copy of a picture with the same code and checks as a real run, once per way of saving
     * (each HEIC encoder and WebP), into its own folder Download/StoragePlanner/compressed/<name>-<time>/. Every
     * copy is kept, even ones a real run would throw away, so they can be compared; the log says which one a real
     * run would keep and why the others would not be.
     */
    private void compress(final Lab.Pic p, Lab.Out o) throws Exception {
        ImageCompressor.Kind kind = kindFor(p);
        boolean lossless = kind == ImageCompressor.Kind.LOSSLESS;
        o.line("Compress " + p.name + " (treated as " + p.area + ") with the compression tools (tools/README.md) to "
                + (lossless ? "lossless WebP" : "HEIC and WebP, keeping the smallest that passes"));
        if (!lossless) o.line("  encoders on this phone:\n" + HeifEncoder.survey().replaceAll("\\s+$", ""));
        if (!ImageCompressor.available(kind)) { o.line("  not possible on this phone: " + ImageCompressor.unavailableReason(kind)); return; }
        String stem = new File(CopyPaths.base("/" + p.name)).getName();
        File parent = new File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "StoragePlanner/compressed");
        String dirName = stem + "-" + new java.text.SimpleDateFormat("HHmmss", Locale.US).format(new java.util.Date());
        File dir = new File(parent, dirName);
        for (int k = 2; dir.exists(); k++) dir = new File(parent, dirName + "-" + k);
        if (!dir.mkdirs()) throw new Exception("cannot create " + dir);
        final ImageCompressor.Outcome r = ImageCompressor.compressForLab(new File(p.path), kind, new File(dir, stem).getPath(), new File(a.getCacheDir(), "compress"));
        for (String t : r.trace) o.line("  " + t);
        if (!r.ok) { o.line("  not compressed: " + r.skipped + String.format(Locale.US, " (%d ms)", r.ms)); return; }
        final List<Compressed> made = new ArrayList<Compressed>();
        List<String> scan = new ArrayList<String>();
        if (r.attempts.isEmpty()) {                                // lossless: one copy
            made.add(new Compressed(p, r.output, r.format, facts(r.format, r.before, r.after, -1, null, r.identicalPixels, r.wouldSkip),
                    r.before, r.after, r.wouldSkip == null, r.wouldSkip != null));
            scan.add(r.output);
        } else {
            o.line("  every way, at full size:");
            for (ImageCompressor.Attempt t : r.attempts) {
                boolean best = t.path != null && t.path.equals(r.output), chosen = best && r.wouldSkip == null;
                o.line(String.format(Locale.US, "    %s: quality %d, %s (%s)%s%s", t.label, t.quality, com.storageplanner.core.Report.size(t.bytes),
                        change(r.before, t.bytes), t.score != null ? String.format(Locale.US, ", worst SSIM %.4f, PSNR %.1f dB", t.score.ssim, t.score.psnr) : "",
                        chosen ? "  <- a real run keeps this" : best ? "  <- the best copy, but a real run would not compress this picture"
                                : t.note != null ? "  (" + t.note + ")" : "  (passes, but another way is smaller, or HEIC is not 5% smaller than WebP)"));
                if (t.path == null) continue;
                made.add(new Compressed(p, t.path, t.label, facts(t.label, r.before, t.bytes, t.quality, t.score, false, t.note), r.before, t.bytes, chosen, t.note != null));
                scan.add(t.path);
            }
        }
        o.line(String.format(Locale.US, "  %s -> %s (%s) as %s; %d ms", com.storageplanner.core.Report.size(r.before),
                com.storageplanner.core.Report.size(r.after), change(r.before, r.after), r.format, r.ms));
        if (r.pictureBytes > 0 && r.before - r.pictureBytes >= 64 * 1024 && r.after > 0)   // more than a small trailer
            o.line(String.format(Locale.US, "  the picture itself is %s of the file's %s (the rest is data after it); against the picture alone: %s",
                    com.storageplanner.core.Report.size(r.pictureBytes), com.storageplanner.core.Report.size(r.before), change(r.pictureBytes, r.after)));
        if (r.ssim >= 0) o.line(String.format(Locale.US, "  worst checked region: SSIM %.4f (need %.2f), PSNR %.1f dB (need %.0f), colour shift %.1f (max %.0f), colour PSNR %.1f dB (need %.0f)",
                r.ssim, Guard.SSIM_MIN, r.psnr, Guard.PSNR_MIN, r.shift, ImageCompressor.MAX_COLOR_SHIFT, r.chromaPsnr, ImageCompressor.CHROMA_PSNR_MIN));
        o.line(r.wouldSkip == null ? "  A real run would keep the " + r.format + " copy." : "  A real run would NOT keep any copy: " + r.wouldSkip + ".");
        o.line("  Saved in Download/StoragePlanner/compressed/" + dir.getName() + "/");
        try { android.media.MediaScannerConnection.scanFile(a, scan.toArray(new String[0]), null, null); } catch (Throwable ignored) {}
        ui.post(new Runnable() {
            public void run() {
                for (java.util.Iterator<Compressed> it = compressed.iterator(); it.hasNext(); ) if (it.next().pic == p) it.remove();
                compressed.addAll(made);
                showCompared();
            }
        });
    }

    /** One "Compare" button per test copy. */
    private void showCompared() {
        if (compareBox == null) return;
        compareBox.removeAllViews();
        if (compressed.isEmpty()) return;
        Ui.add(compareBox, Ui.text(a, "Compare original vs compressed", 15, Ui.text, true), 4);
        for (int i = compressed.size() - 1; i >= 0; i--) {
            final Compressed c = compressed.get(i);
            Button b = Ui.button(a, (c.chosen ? "★ " : "") + c.pic.name + " · " + shortLabel(c.label) + " · " + com.storageplanner.core.Report.size(c.after)
                    + " (" + change(c.before, c.after) + ")" + (c.skipped ? " · would be skipped" : ""), c.chosen);
            b.setContentDescription("Compare " + c.pic.name + " with its " + c.label + " copy" + (c.chosen ? ", the one a real run keeps" : "") + ". " + c.facts);
            b.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    if (!new File(c.path).isFile() || !new File(c.pic.path).isFile()) { a.toast("The picture or its copy is no longer there."); return; }
                    a.push(new CompressCompareScreen(a, c.pic.path, c.path, c.pic.name + " · " + shortLabel(c.label), c.facts));
                }
            });
            LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-1, -2); l.topMargin = dp(6);
            compareBox.addView(b, l);
        }
        Ui.add(compareBox, Ui.text(a, "★ = the copy a real run would keep.", 12, Ui.text2, false), 4);
    }

    /** "HEIC hardware tiles", "HEIC software", "WebP", ... for buttons. */
    private static String shortLabel(String label) {
        if (label == null) return "";
        if (label.startsWith("WebP")) return "WebP";
        if (label.startsWith("lossless")) return "lossless WebP";
        if (label.contains("HEIC encoder")) return "HEIC (HEIC encoder)";
        return label.contains("software") ? "HEIC (software tiles)" : "HEIC (hardware tiles)";
    }

    /** "55% smaller" or "3% larger". */
    static String change(long before, long after) {
        double pct = 100.0 * (before - after) / Math.max(1, before);
        return pct >= 0 ? String.format(Locale.US, "%.0f%% smaller", pct) : String.format(Locale.US, "%.0f%% larger", -pct);
    }

    private static String facts(String label, long before, long after, int quality, ImageCompressor.Score sc, boolean identical, String note) {
        StringBuilder s = new StringBuilder();
        s.append(label).append(": ").append(com.storageplanner.core.Report.size(before)).append(" → ").append(com.storageplanner.core.Report.size(after))
         .append(" (").append(change(before, after)).append(")");
        if (quality >= 0) s.append(" · quality ").append(quality);
        else s.append(identical ? " · pixel-identical" : " · NOT pixel-identical");
        if (sc != null) s.append(String.format(Locale.US, " · SSIM %.4f · PSNR %.1f dB · colour shift %.1f · colour PSNR %.1f dB", sc.ssim, sc.psnr, sc.shift, sc.chromaPsnr));
        if (note != null) s.append(" · a real run would skip it: ").append(note);
        return s.toString();
    }

    private void setEnabled(boolean on) { for (Button b : runButtons) b.setEnabled(on); }

    private void updateLogInfo() {
        long n = LabLog.size(a);
        logInfo.setText("Log: " + (n < 1024 ? n + " bytes" : (n / 1024) + " KB") + ". Save copies it to Download/StoragePlanner; Share sends that copy; Copy puts it on the clipboard.");
    }

    /** Puts the log on the clipboard (read on the worker thread; a long log is cut to its newest part). */
    private void copyLog() {
        worker.execute(new Runnable() {
            public void run() {
                final String text = LabLog.text(a, 200_000);
                ui.post(new Runnable() {
                    public void run() {
                        if (text.isEmpty()) { a.toast("The log is empty."); return; }
                        try {
                            android.content.ClipboardManager cm = (android.content.ClipboardManager) a.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("Storage Planner Lab log", text));
                            boolean cut = text.startsWith("(earlier lines left out");
                            // Android 13+ shows its own "copied" confirmation; say more only when part was left out
                            if (cut || android.os.Build.VERSION.SDK_INT < 33)
                                a.toast(cut ? "Copied the newest part of the log. Save log has all of it." : "Log copied.");
                            logInfo.announceForAccessibility("Log copied to the clipboard");
                        } catch (Throwable t) {
                            a.toast("Could not copy the log: " + t.getMessage());
                        }
                    }
                });
            }
        });
    }

    private void saveLog(boolean share) {
        try {
            File f = LabLog.export(a);
            if (!share) { a.toast("Saved to Download/StoragePlanner/" + f.getName()); return; }
            FileItem item = new FileItem("Download/StoragePlanner/" + f.getName(), f.getAbsolutePath(), f.length(), f.lastModified());
            FileActions.share(a, item);
        } catch (Throwable t) {
            a.toast("Could not save the log: " + t.getMessage());
        }
    }

    // ------------------------------------------------------------------ picking
    /** Called by MainActivity with the picker's result: copies each picture into the app's cache (slots p0..p19). */
    void picked(final List<Uri> uris) {
        if (uris.isEmpty()) return;
        spinner.setVisibility(View.VISIBLE);
        worker.execute(new Runnable() {
            public void run() {
                final List<Lab.Pic> got = new ArrayList<Lab.Pic>();
                File dir = new File(a.getCacheDir(), "lab");
                if (!dir.isDirectory()) dir.mkdirs();
                int slot = pics.size();
                for (Uri u : uris) {
                    if (slot >= MAX) break;
                    try { got.add(copy(u, dir, slot++)); }
                    catch (Throwable t) { LabLog.error(a, "copying " + u, t); }
                }
                ui.post(new Runnable() {
                    public void run() {
                        spinner.setVisibility(View.GONE);
                        pics.addAll(got);
                        current = pics.size() - got.size();
                        showPics();
                        if (uris.size() > got.size()) a.toast((uris.size() - got.size()) + " picture(s) could not be opened (at most " + MAX + ").");
                    }
                });
            }
        });
    }

    private Lab.Pic copy(Uri u, File dir, int slot) throws Exception {
        String name = "picture" + slot; long size = -1, modified = 0;
        Cursor c = a.getContentResolver().query(u, null, null, null, null);
        if (c != null) try {
            if (c.moveToFirst()) {
                int ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME), si = c.getColumnIndex(OpenableColumns.SIZE),
                    mi = c.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED);
                if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni);
                if (si >= 0 && !c.isNull(si)) size = c.getLong(si);
                if (mi >= 0 && !c.isNull(mi)) modified = c.getLong(mi);
            }
        } finally { c.close(); }
        String ext = FileKind.ext(name);
        File dst = new File(dir, "p" + slot + "." + (ext.isEmpty() ? "jpg" : ext));
        InputStream in = a.getContentResolver().openInputStream(u);
        if (in == null) throw new Exception("cannot open");
        FileOutputStream out = new FileOutputStream(dst, false);
        try {
            byte[] buf = new byte[1 << 16]; int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } finally { in.close(); out.close(); }
        long taken = exifTime(dst);
        Lab.Pic p = new Lab.Pic(dst.getAbsolutePath(), name, size >= 0 ? size : dst.length(), modified > 0 ? modified : taken > 0 ? taken : dst.lastModified());
        p.area = area < 0 ? Lab.areaFromName(name) : AREAS[area];
        LabLog.line(a, String.format(Locale.US, "picked %s -> %s (%d bytes, modified %d, EXIF taken %d)", name, dst.getName(), p.size, modified, taken));
        return p;
    }

    private static long exifTime(File f) {
        try {
            String t = new android.media.ExifInterface(f.getAbsolutePath()).getAttribute(android.media.ExifInterface.TAG_DATETIME_ORIGINAL);
            if (t == null) return 0;
            return new java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(t).getTime();
        } catch (Throwable e) { return 0; }
    }

    private void showPics() {
        strip.removeAllViews();
        for (int i = 0; i < pics.size(); i++) {
            final int k = i;
            Lab.Pic p = pics.get(i);
            FrameLayout fr = new FrameLayout(a);
            fr.setBackground(Ui.round(0xFF101014, dp(8)));
            ImageView iv = new ImageView(a);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            fr.addView(iv, new FrameLayout.LayoutParams(-1, -1));
            Thumbs.load(iv, item(p), dp(96));
            TextView n = Ui.text(a, String.valueOf(i + 1), 12, 0xFFFFFFFF, true);
            n.setBackground(Ui.round(0x99000000, dp(6)));
            n.setPadding(dp(5), 0, dp(5), 0);
            fr.addView(n, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START));
            if (i == current) fr.setForeground(Ui.outlineDrawable(a, Ui.accent, 3));
            fr.setContentDescription("Picture " + (i + 1) + ", " + p.name + (i == current ? ", selected" : "") + ". Double tap to select.");
            fr.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { current = k; showPics(); } });
            LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(dp(72), dp(72)); l.rightMargin = dp(6);
            strip.addView(fr, l);
        }
        if (pics.isEmpty()) {
            info.setText("No pictures picked yet.");
            preview.setImageDrawable(null);
            return;
        }
        final Lab.Pic p = pic();
        info.setText((current + 1) + " of " + pics.size() + ": " + p.name + " · " + com.storageplanner.core.Report.size(p.size));
        preview.setFaces(null, 5);
        patchRow.removeAllViews();
        preview.setImageDrawable(null);
        final int px = Math.max(a.getResources().getDisplayMetrics().widthPixels, dp(340));
        worker.execute(new Runnable() {
            public void run() {
                Bitmap b = null;
                try { b = Thumbs.make(item(p), px); } catch (Throwable ignored) {}
                final Bitmap fb = b;
                ui.post(new Runnable() { public void run() { if (fb != null && p == pic()) preview.setImageBitmap(fb); } });
            }
        });
    }

    private static FileItem item(Lab.Pic p) {
        FileItem f = new FileItem(p.name, p.path, p.size, p.mtime);
        return f;
    }

    @Override public void onHide() { if (platform != null) { platform.close(); platform = null; } }
}
