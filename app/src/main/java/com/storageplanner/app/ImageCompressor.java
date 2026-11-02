package com.storageplanner.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.os.Build;

import com.storageplanner.core.Compression;
import com.storageplanner.core.Exif;
import com.storageplanner.core.FileItem;
import com.storageplanner.core.Report;
import com.storageplanner.core.WebpMeta;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Compresses one image next to the original, never touching the original:
 * <ul>
 * <li>Photos and chat images: every way of saving that works on this phone (each HEIC encoder, and
 *     Android's WebP encoder) finds the lowest quality that still passes the study's "looks identical"
 *     guard (SSIM(Y) >= 0.98 and PSNR(Y) >= 38 dB, as in {@link Calibrator}) plus a colour check, on four
 *     512 px regions. The way with the smallest result is encoded in full and checked again on four other
 *     regions; if it fails, the next way is tried. EXIF (date taken, camera, location) is carried over.</li>
 * <li>Screenshots become lossless WebP, and the copy must decode to exactly the same pixels.</li>
 * </ul>
 * The copy is written to a work file, checked, and only then renamed to its final name. Every step's
 * scores are recorded in the outcome, for the Algorithm Lab.
 */
final class ImageCompressor {
    private ImageCompressor() {}

    /** Bigger images may not fit in memory twice (the calibrator uses the same limit). */
    static final long MAX_PIXELS = 25_000_000L;
    /** A copy that saves less than this is not worth replacing the original with. */
    static final double MIN_SAVING = 0.10;
    static final int Q_LOW = 30, Q_HIGH = 95, WEBP_LOW = 50, WEBP_HIGH = 99;
    /** Largest average shift of red, green or blue (0..255) allowed in a checked region. */
    static final double MAX_COLOR_SHIFT = 3.0;

    /** One full-size copy that was made and checked. */
    static final class Attempt {
        String key, label, ext;
        String path;          // Lab: where this copy was kept (null if not kept)
        int quality;
        long bytes;
        Score score;
        String note;          // why a real run would not keep it (null = it passes)
    }

    static final class Outcome {
        boolean ok;
        String output;        // path of the compressed copy
        String key, format;   // the way that made it, e.g. "webp" / "WebP (Android's encoder)"
        long before, after, ms;
        int quality = -1;
        /** Worst checked region of the kept copy (-1 = not measured). */
        double ssim = -1, psnr = -1, shift = -1;
        boolean identicalPixels;   // lossless copies
        String skipped;       // why nothing was written (ok == false)
        /** Lab only: the copy was kept for comparison, but a real run would have thrown it away for this reason. */
        String wouldSkip;
        /** Every quality tried and its scores. */
        final List<String> trace = new ArrayList<String>();
        /** Every full-size copy made (Lab: one per way of saving). */
        final List<Attempt> attempts = new ArrayList<Attempt>();

        Outcome fail(String why) { ok = false; skipped = why; return this; }
    }

    /** Scores of one comparison (or the worst of several). */
    static final class Score {
        double ssim = 1, psnr = 99, shift = 0;
        boolean pass = true;
        /** Android's region decoder (used by galleries to zoom) read the file differently from a full decode. */
        boolean regionWrong;
        String why;           // set when the copy could not be compared at all

        void worst(Score s) {
            ssim = Math.min(ssim, s.ssim); psnr = Math.min(psnr, s.psnr); shift = Math.max(shift, s.shift);
            pass &= s.pass;
        }

        @Override public String toString() {
            if (why != null) return "FAIL (" + why + ")";
            return String.format(Locale.US, "%s (worst SSIM %.4f, PSNR %.1f dB, colour shift %.1f)", pass ? "pass" : "FAIL", ssim, psnr, shift);
        }

        static Score failed() { Score s = new Score(); s.ssim = 0; s.psnr = 0; s.shift = 255; s.pass = false; return s; }
        static Score failed(String why) { Score s = failed(); s.why = why; return s; }
    }

    /** Whether this phone can carry out the recommendation at all. */
    static boolean available(String technique) {
        return Compression.lossless(technique) || Build.VERSION.SDK_INT >= 28;
    }

    static String unavailableReason(String technique) {
        if (available(technique)) return null;
        return "Compressing photos needs Android 9 or later (to save HEIC, and to keep a photo's details in WebP).";
    }

    /**
     * A real run: the copy goes next to the original, and only if it passes every check.
     * @param hints quality that worked for the previous file, per way of saving (updated here)
     * @param work  folder for temporary test encodes (app cache)
     */
    static Outcome compress(FileItem f, String technique, Map<String, Integer> hints, File work) {
        File src = new File(f.abs);
        if (!src.isFile()) return new Outcome().fail("no longer on the phone");
        if (src.length() != f.size || Math.abs(src.lastModified() - f.mtime) > 2000) return new Outcome().fail("changed since the analysis");
        String base = Compression.base(f.abs);
        for (String ext : Compression.extensions(technique))
            if (new File(base + "." + ext).exists()) return new Outcome().fail("a compressed copy (" + new File(base + "." + ext).getName() + ") already exists");
        if ((long) f.width * f.height > MAX_PIXELS) return new Outcome().fail("too large to process safely");
        Outcome o = write(src, technique, base, hints, work, false);
        if (o.ok && o.quality >= 0 && o.key != null) hints.put(o.key, o.quality);
        return o;
    }

    /**
     * Algorithm Lab: compress any picture to {@code base} + "-" + way + extension, once per way of saving,
     * keeping every copy (even ones a real run would throw away; {@link Attempt#note} says why) so they can be
     * compared. {@link Outcome#output} is the one a real run would choose.
     */
    static Outcome compressForLab(File src, String technique, String base, File work) {
        return write(src, technique, base, new HashMap<String, Integer>(), work, true);
    }

    private static Outcome write(File src, String technique, String base, Map<String, Integer> hints, File work, boolean keep) {
        long t0 = System.nanoTime();
        Outcome o = new Outcome();
        List<File> temps = new ArrayList<File>();
        try {
            if (Compression.lossless(technique)) {
                String out = keep ? base + "-lossless.webp" : base + ".webp";
                File tmp = new File(Compression.tempPath(out)); temps.add(tmp);
                lossless(src, tmp, keep, o);
                if (!o.ok) return o;
                if (!tmp.renameTo(new File(out))) return o.fail("could not write the copy (" + new File(out).getParent() + ")");
                o.output = out; o.key = "lossless"; o.format = "lossless WebP";
            } else {
                lossy(src, base, hints, work, keep, o, temps);
                if (!o.ok) return o;
            }
            new File(o.output).setLastModified(src.lastModified());   // keeps its place in date-sorted views
            return o;
        } catch (OutOfMemoryError e) {
            return o.fail("not enough memory");
        } catch (Throwable t) {
            return o.fail("could not be compressed (" + t.getClass().getSimpleName() + (t.getMessage() != null ? ": " + t.getMessage() : "") + ")");
        } finally {
            for (File t : temps) if (t.exists()) t.delete();
            o.ms = (System.nanoTime() - t0) / 1_000_000;
        }
    }

    // ------------------------------------------------------------------ photos and chat images -> HEIC or WebP
    static final String REGION_WRONG = "Android's region decoder reads it wrongly, so galleries may show it wrong when zoomed";
    /** HEIC must be this much smaller to win over WebP, which every app can open. */
    static final double WEBP_PREFERENCE = 1.05;

    private static double weight(Method m, long bytes) { return m != null && m.way != null ? bytes * WEBP_PREFERENCE : bytes; }

    private static Method byKey(String key) {
        if (methods != null) for (Method m : methods) if (m.key.equals(key)) return m;
        return null;
    }

    /** A way of saving under consideration for one picture. */
    private static final class Cand {
        final Method m; int best = -1; long mosaicBytes;
        Cand(Method m) { this.m = m; }
    }

    private static void lossy(File src, String base, Map<String, Integer> hints, File work, boolean keep, Outcome o, List<File> temps) throws IOException {
        List<Method> methods = methods(work);
        o.trace.add("ways of saving: " + checked);
        byte[] exif = Exif.fromJpeg(head(src, 256 * 1024));
        int orientation = exif != null ? Exif.orientation(exif) : 1;
        List<Cand> cands = new ArrayList<Cand>();
        for (Method m : methods) {
            if (!m.works) continue;
            String why = exif != null && !m.exifOk ? "it would lose the photo's details (EXIF)"
                    : !m.regionOk ? "Android's region decoder reads its files wrongly, so galleries may show them wrong when zoomed" : null;
            if (why != null) { o.trace.add(m.label + ": not used in a real run, " + why); if (!keep) continue; }
            cands.add(new Cand(m));
        }
        if (cands.isEmpty()) { o.fail("no way of saving worked on this phone"); return; }
        Bitmap img = upright(decode(src, 1), orientation);
        if (exif != null && orientation != 1) exif = Exif.withOrientation(exif, 1);   // the pixels are upright now
        if (exif != null) exif = Exif.withSize(exif, img.getWidth(), img.getHeight());  // cameras record the size before turning
        o.trace.add(String.format(Locale.US, "%d x %d px, EXIF %s, orientation %d", img.getWidth(), img.getHeight(),
                exif != null ? "copied (" + exif.length + " bytes)" : "none", orientation));
        o.before = src.length();
        Rect[] rs = Calibrator.crops(img.getWidth(), img.getHeight(), 0.25, 0.75);
        int c = rs[0].width();
        Bitmap mosaic = Bitmap.createBitmap(2 * c, 2 * c, Bitmap.Config.ARGB_8888);
        try {
            Canvas cv = new Canvas(mosaic);
            for (int k = 0; k < 4; k++) cv.drawBitmap(img, rs[k], new Rect((k % 2) * c, (k / 2) * c, (k % 2 + 1) * c, (k / 2 + 1) * c), null);

            // 1) every way: lowest quality that passes on the four regions, and how big that is
            List<Cand> ranked = new ArrayList<Cand>();
            for (Cand cd : cands) {
                search(cd, mosaic, c, hints.get(cd.m.key), work, o);
                if (cd.best >= 0) ranked.add(cd);
                else o.trace.add(cd.m.label + ": no quality looked identical");
            }
            if (ranked.isEmpty()) { o.fail("no quality setting looked identical"); return; }
            Collections.sort(ranked, new Comparator<Cand>() { public int compare(Cand x, Cand y) { return Double.compare(weight(x.m, x.mosaicBytes), weight(y.m, y.mosaicBytes)); } });

            // 2) full size, smallest first; a real run keeps the first that passes and saves enough
            Rect[] verify = Calibrator.crops(img.getWidth(), img.getHeight(), 0.4, 0.6);   // other regions than the search
            long limit = (long) (src.length() * (1 - MIN_SAVING));
            Attempt chosen = null;
            for (Cand cd : ranked) {
                String out = keep ? base + "-" + cd.m.key + "." + cd.m.ext : base + "." + cd.m.ext;
                File tmp = new File(Compression.tempPath(out)); temps.add(tmp);
                Attempt a = null;
                for (int q = cd.best; q <= cd.m.hi; q += 3) {
                    encodeTo(cd.m, img, q, exif, tmp);
                    a = new Attempt();
                    a.key = cd.m.key; a.label = cd.m.label; a.ext = cd.m.ext; a.quality = q; a.bytes = tmp.length();
                    if (a.bytes > limit) {
                        a.note = "saves less than " + Math.round(MIN_SAVING * 100) + "%";
                        if (keep) a.score = verifyFile(img, tmp, verify, o);
                        o.trace.add(String.format(Locale.US, "%s, full image at quality %d: %s, %s", cd.m.label, q, Report.size(a.bytes),
                                a.score != null ? a.score + ", " + a.note : a.note));
                        break;
                    }
                    a.score = verifyFile(img, tmp, verify, o);
                    o.trace.add(String.format(Locale.US, "%s, full image at quality %d: %s, %s", cd.m.label, q, Report.size(a.bytes), a.score));
                    if (a.score.pass && a.score.regionWrong) { a.note = REGION_WRONG; break; }   // a higher quality would not change that
                    if (a.score.pass) break;
                    a.note = "did not look identical at full size";
                }
                if (a == null) continue;
                o.attempts.add(a);
                if (keep && tmp.renameTo(new File(out))) { a.path = out; new File(out).setLastModified(src.lastModified()); }
                if (a.note == null && (chosen == null || weight(cd.m, a.bytes) < weight(byKey(chosen.key), chosen.bytes))) {
                    chosen = a;
                    if (!keep) {
                        if (!tmp.renameTo(new File(out))) { o.fail("could not write the copy (" + new File(out).getParent() + ")"); return; }
                        a.path = out;
                        break;
                    }
                }
            }
            if (chosen == null) {
                Attempt best = null;
                for (Attempt a : o.attempts) if (a.path != null && (best == null || a.bytes < best.bytes)) best = a;
                String why = "did not look identical at full size";
                for (Attempt a : o.attempts) if (a.note != null && a.note.startsWith("saves")) why = "would save less than " + Math.round(MIN_SAVING * 100) + "%";
                for (Attempt a : o.attempts) if (REGION_WRONG.equals(a.note)) why = REGION_WRONG;
                if (!keep || best == null) { o.fail(why); return; }
                o.wouldSkip = best.note;                           // Lab: show the smallest anyway
                chosen = best;
            }
            o.output = chosen.path; o.key = chosen.key; o.format = chosen.label; o.quality = chosen.quality; o.after = chosen.bytes;
            if (chosen.score != null) { o.ssim = chosen.score.ssim; o.psnr = chosen.score.psnr; o.shift = chosen.score.shift; }
            o.ok = true;
        } finally {
            mosaic.recycle();
            img.recycle();
        }
    }

    /**
     * Lowest quality at which the four regions all pass, encoded together as one 2x2 mosaic (one encode per
     * step). Binary search, starting at the quality that worked for the previous picture.
     */
    private static void search(Cand cd, Bitmap mosaic, int c, Integer hint, File work, Outcome o) throws IOException {
        work.mkdirs();
        File test = new File(work, "search." + cd.m.ext);
        try {
            int lo = cd.m.lo, hi = cd.m.hi;
            boolean first = hint != null && hint >= lo && hint <= hi;
            while (lo <= hi) {
                int q = first ? hint : (lo + hi) / 2;
                first = false;
                encodeTo(cd.m, mosaic, q, null, test);
                Score s = mosaicScore(mosaic, test, c);
                o.trace.add(String.format(Locale.US, "%s, regions at quality %d: %s, %s", cd.m.label, q, Report.size(test.length()), s));
                if (s.pass) { cd.best = q; cd.mosaicBytes = test.length(); hi = q - 1; } else lo = q + 1;
            }
        } finally {
            test.delete();
        }
    }

    /** One encode with a way of saving. WebP gets the EXIF block added (Android's WebP encoder writes none). */
    private static void encodeTo(Method m, Bitmap bm, int q, byte[] exif, File out) throws IOException {
        if (m.way != null) { HeifEncoder.encode(m.way, bm, q, m.exifOk ? exif : null, out.getPath()); return; }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        if (!bm.compress(Calibrator.lossy(), q, bos)) throw new IOException("the WebP encoder failed");
        byte[] d = bos.toByteArray();
        if (exif != null && m.exifOk) d = WebpMeta.withExif(d, exif, bm.getWidth(), bm.getHeight());
        FileOutputStream f = new FileOutputStream(out);
        try { f.write(d); f.getFD().sync(); } finally { f.close(); }
        if (out.length() != d.length) throw new IOException("the copy was not written completely");
    }

    private static Score mosaicScore(Bitmap mosaic, File enc, int c) {
        Bitmap d = BitmapFactory.decodeFile(enc.getPath(), argb());
        if (d == null) return Score.failed();
        try {
            if (d.getWidth() != mosaic.getWidth() || d.getHeight() != mosaic.getHeight()) return Score.failed();
            Score worst = new Score();
            for (int k = 0; k < 4; k++) {
                int x = (k % 2) * c, y = (k / 2) * c;
                Bitmap a = Bitmap.createBitmap(mosaic, x, y, c, c), b = Bitmap.createBitmap(d, x, y, c, c);
                worst.worst(score(a, b));
                a.recycle(); b.recycle();
            }
            return worst;
        } finally {
            d.recycle();
        }
    }

    /**
     * Checks the real output on the given regions. Region decoding saves memory, but HEIC region decoding has
     * been unreliable on some phones, so a failure is looked at again with a full decode before it counts.
     */
    private static Score verifyFile(Bitmap img, File enc, Rect[] regions, Outcome o) throws IOException {
        Score s = null;
        try { s = regionScore(img, enc, regions, true); } catch (Throwable t) { s = null; }
        if (s != null && s.pass) return s;
        Score full = regionScore(img, enc, regions, false);
        if (s != null && full.pass) {
            o.trace.add("  Android's region decoder reads this copy wrongly: " + s + "; a full decode passes");
            full.regionWrong = true;
        }
        return full;
    }

    private static Score regionScore(Bitmap img, File enc, Rect[] regions, boolean regionDecoder) throws IOException {
        BitmapRegionDecoder dec = null;
        Bitmap whole = null;
        try {
            if (regionDecoder) {
                dec = newRegionDecoder(enc.getPath());
                if (dec == null) throw new IOException("no region decoder for this file");
            } else {
                whole = BitmapFactory.decodeFile(enc.getPath(), argb());
                if (whole == null) throw new IOException("the copy could not be opened");
            }
            int w = dec != null ? dec.getWidth() : whole.getWidth(), h = dec != null ? dec.getHeight() : whole.getHeight();
            if (w != img.getWidth() || h != img.getHeight())
                return Score.failed((regionDecoder ? "the region decoder" : "a full decode") + " sees " + w + "x" + h + " instead of " + img.getWidth() + "x" + img.getHeight());
            Score worst = new Score();
            for (Rect r : regions) {
                Bitmap a = Bitmap.createBitmap(img, r.left, r.top, r.width(), r.height());
                Bitmap b = dec != null ? dec.decodeRegion(r, argb()) : Bitmap.createBitmap(whole, r.left, r.top, r.width(), r.height());
                if (b == null) { a.recycle(); throw new IOException("a region of the copy could not be opened"); }
                if (b.getWidth() != a.getWidth() || b.getHeight() != a.getHeight()) {
                    Score f = Score.failed("a " + r.width() + "x" + r.height() + " region came back " + b.getWidth() + "x" + b.getHeight());
                    a.recycle(); b.recycle();
                    return f;
                }
                worst.worst(score(a, b));
                a.recycle(); b.recycle();
            }
            return worst;
        } finally {
            if (dec != null) dec.recycle();
            if (whole != null) whole.recycle();
        }
    }

    // ------------------------------------------------------------------ which ways of saving work on this phone
    /** A way of saving a photo: one HEIC encoder ({@link #way}) or Android's WebP encoder (way == null). */
    static final class Method {
        final String key, label, ext;
        final HeifEncoder.Way way;
        final int lo, hi;
        boolean works, exifOk, regionOk = true;
        String result = "";
        Method(String key, String label, String ext, HeifEncoder.Way way, int lo, int hi) {
            this.key = key; this.label = label; this.ext = ext; this.way = way; this.lo = lo; this.hi = hi;
        }
    }

    static final String CHECK_MAKE = "StoragePlanner check";
    private static List<Method> methods;
    private static String checked;

    /** The ways of saving, tested once per run (see {@link #encoderCheck}). */
    static synchronized List<Method> methods(File work) { encoderCheck(work); return methods; }

    /**
     * Once per run: encodes a test picture (1100x700, so grids have partial tiles, with gradients and edges that
     * do not line up with tiles) with every HEIC encoder and with WebP, and records for each whether the picture
     * comes back right, whether the EXIF can be read back (and doesn't break the picture), and whether Android's
     * region decoder (used by galleries to zoom) reads the file correctly.
     */
    static synchronized String encoderCheck(File work) {
        if (checked != null) return checked;
        methods = new ArrayList<Method>();
        if (Build.VERSION.SDK_INT >= 28) {
            for (HeifEncoder.Way w : HeifEncoder.ways()) methods.add(new Method(w.key, w.label(), "heic", w, Q_LOW, Q_HIGH));
            methods.add(new Method("webp", "WebP (Android's encoder)", "webp", null, WEBP_LOW, WEBP_HIGH));
        }
        work.mkdirs();
        Bitmap t = testPattern(1100, 700);
        byte[] exif = Exif.withSize(Exif.minimal(CHECK_MAKE), t.getWidth(), t.getHeight());
        StringBuilder log = new StringBuilder();
        try {
            for (Method m : methods) {
                File f = new File(work, "check." + m.ext);
                try { check(m, t, exif, f); } finally { f.delete(); }
                log.append(log.length() > 0 ? "; " : "").append(m.label).append(": ").append(m.result);
            }
        } finally {
            t.recycle();
        }
        return checked = log.length() > 0 ? log.toString() : "none (needs Android 9)";
    }

    private static void check(Method m, Bitmap t, byte[] exif, File f) {
        m.exifOk = true;
        String bad = pictureProblem(m, t, exif, f);
        if (bad != null) {                                       // maybe the EXIF broke it: try without
            m.exifOk = false;
            String bad2 = pictureProblem(m, t, null, f);
            if (bad2 != null) { m.works = false; m.result = "does not work (" + bad + ")"; return; }
            m.works = true; m.result = "works, but storing EXIF breaks the picture (" + bad + "), so it would lose the photo's details";
        } else {
            m.works = true;
            String make = null;
            try { make = new android.media.ExifInterface(f.getPath()).getAttribute(android.media.ExifInterface.TAG_MAKE); } catch (Throwable ignored) {}
            m.exifOk = make != null && CHECK_MAKE.equals(make.trim());
            m.result = m.exifOk ? "works, EXIF kept" : "works, but EXIF is not kept (read back: " + make + ")";
        }
        // the copy as galleries read it when zooming: a region across a tile edge and a partial tile
        BitmapRegionDecoder dec = null; Bitmap full = null, part = null, ref = null;
        try {
            Rect r = new Rect(400, 150, 1100, 700);
            dec = newRegionDecoder(f.getPath());
            full = BitmapFactory.decodeFile(f.getPath(), argb());
            part = dec.decodeRegion(r, argb());
            ref = Bitmap.createBitmap(full, r.left, r.top, r.width(), r.height());
            Score s = score(ref, part);
            m.regionOk = s.psnr >= 40;
            if (!m.regionOk) m.result += String.format(Locale.US, "; Android's region decoder reads it wrongly (PSNR %.1f dB against a full decode)", s.psnr);
        } catch (Throwable e) {
            m.result += "; no region decoding (" + e.getClass().getSimpleName() + ")";
        } finally {
            if (dec != null) dec.recycle();
            for (Bitmap b : new Bitmap[]{full, part, ref}) if (b != null) b.recycle();
        }
    }

    /** Null if a copy made this way decodes to the test picture, else what went wrong. */
    private static String pictureProblem(Method m, Bitmap t, byte[] exif, File f) {
        Bitmap d = null;
        try {
            encodeTo(m, t, 90, exif, f);
            d = BitmapFactory.decodeFile(f.getPath(), argb());
            if (d == null) return "the file could not be opened";
            if (d.getWidth() != t.getWidth() || d.getHeight() != t.getHeight()) return "came back " + d.getWidth() + "x" + d.getHeight();
            Score s = score(t, d);
            if (s.psnr < 30 || s.ssim < 0.9) return "the picture came back wrong, " + s;
            return null;
        } catch (Throwable e) {
            return e.getClass().getSimpleName() + (e.getMessage() != null ? ": " + e.getMessage() : "");
        } finally {
            if (d != null) d.recycle();
        }
    }

    /** Smooth gradients plus a checkerboard that does not line up with the tiles, so misplaced tiles show. */
    private static Bitmap testPattern(int w, int h) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int r = x * 255 / w, g = y * 255 / h, bl = ((x / 37 + y / 29) & 1) == 0 ? 40 : 210;
                row[x] = 0xFF000000 | (r << 16) | (g << 8) | bl;
            }
            b.setPixels(row, 0, w, 0, y, w, 1);
        }
        return b;
    }

    @SuppressWarnings("deprecation")
    private static BitmapRegionDecoder newRegionDecoder(String path) throws IOException {
        return Build.VERSION.SDK_INT >= 31 ? BitmapRegionDecoder.newInstance(path) : BitmapRegionDecoder.newInstance(path, false);
    }

    /** The study's guard on brightness (same thresholds as the calibrator), plus: no colour channel moved on average. */
    static Score score(Bitmap a, Bitmap b) {
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) return Score.failed();
        int w = a.getWidth(), h = a.getHeight();
        double[] ya = Calibrator.luma(a), yb = Calibrator.luma(b);
        double mse = 0;
        for (int i = 0; i < ya.length; i++) { double d = ya[i] - yb[i]; mse += d * d; }
        mse /= ya.length;
        Score s = new Score();
        s.psnr = mse == 0 ? 99 : 10 * Math.log10(255.0 * 255.0 / mse);
        s.ssim = Ssim.ssim(ya, yb, w, h);
        s.shift = colorShift(a, b);
        s.pass = s.psnr >= Calibrator.PSNR_MIN && s.ssim >= Calibrator.SSIM_MIN && s.shift <= MAX_COLOR_SHIFT;
        return s;
    }

    /** Largest average change of red, green or blue (0..255). */
    private static double colorShift(Bitmap a, Bitmap b) {
        int w = a.getWidth(), h = a.getHeight();
        int[] pa = new int[w * h], pb = new int[w * h];
        a.getPixels(pa, 0, w, 0, 0, w, h);
        b.getPixels(pb, 0, w, 0, 0, w, h);
        long dr = 0, dg = 0, db = 0;
        for (int i = 0; i < pa.length; i++) {
            dr += ((pa[i] >> 16) & 255) - ((pb[i] >> 16) & 255);
            dg += ((pa[i] >> 8) & 255) - ((pb[i] >> 8) & 255);
            db += (pa[i] & 255) - (pb[i] & 255);
        }
        double n = pa.length;
        return Math.max(Math.abs(dr / n), Math.max(Math.abs(dg / n), Math.abs(db / n)));
    }

    // ------------------------------------------------------------------ screenshots -> lossless WebP
    @SuppressWarnings("deprecation")
    private static void lossless(File src, File tmp, boolean keep, Outcome o) throws IOException {
        Bitmap bm = decode(src, 1), back = null;
        o.before = src.length();
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            if (Build.VERSION.SDK_INT >= 30) bm.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, bos);
            else bm.compress(Bitmap.CompressFormat.WEBP, 100, bos);    // quality 100 is lossless before Android 11
            byte[] d = bos.toByteArray();
            boolean small = d.length <= src.length() * (1 - MIN_SAVING);
            if (!small && !keep) { o.fail("would save less than " + Math.round(MIN_SAVING * 100) + "%"); return; }
            back = BitmapFactory.decodeByteArray(d, 0, d.length, argb());
            o.identicalPixels = back != null && back.sameAs(bm);
            o.trace.add(String.format(Locale.US, "%d x %d px, lossless WebP %s, pixel-identical: %s", bm.getWidth(), bm.getHeight(),
                    com.storageplanner.core.Report.size(d.length), o.identicalPixels ? "yes" : "NO"));
            if (!o.identicalPixels) {
                if (!keep) { o.fail("the copy was not pixel-identical"); return; }
                o.wouldSkip = "not pixel-identical";
            } else if (!small) o.wouldSkip = "saves less than " + Math.round(MIN_SAVING * 100) + "%";
            if (o.identicalPixels) { o.ssim = 1; o.psnr = 99; o.shift = 0; }
            FileOutputStream out = new FileOutputStream(tmp);
            try { out.write(d); out.getFD().sync(); } finally { out.close(); }
            if (tmp.length() != d.length) throw new IOException("the copy was not written completely");
            o.ok = true; o.after = d.length;
        } finally {
            bm.recycle();
            if (back != null) back.recycle();
        }
    }

    // ------------------------------------------------------------------ helpers
    private static BitmapFactory.Options argb() {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return o;
    }

    private static Bitmap decode(File f, int sample) throws IOException {
        BitmapFactory.Options o = argb();
        o.inSampleSize = sample;
        Bitmap b = BitmapFactory.decodeFile(f.getPath(), o);
        if (b == null) throw new IOException("the image could not be opened");
        return b;
    }

    /**
     * For showing: decodes with its EXIF orientation applied, at most {@code maxSide} pixels on the long side
     * (power-of-two steps, so an original and its copy of the same size come out the same size).
     */
    static Bitmap decodeUpright(File f, int maxSide) throws IOException {
        BitmapFactory.Options b = new BitmapFactory.Options();
        b.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), b);
        int sample = 1;
        while (Math.max(b.outWidth, b.outHeight) / sample > maxSide) sample *= 2;
        int orientation = Exif.orientation(Exif.fromJpeg(head(f, 256 * 1024)));
        return upright(decode(f, sample), orientation);
    }

    /** Applies the EXIF orientation to the pixels (rotations and mirror images). Recycles the input if it makes a new one. */
    static Bitmap upright(Bitmap b, int orientation) {
        if (orientation <= 1 || orientation > 8) return b;
        Matrix m = new Matrix();
        switch (orientation) {
            case 2: m.setScale(-1, 1); break;
            case 3: m.setRotate(180); break;
            case 4: m.setScale(1, -1); break;
            case 5: m.setRotate(90); m.postScale(-1, 1); break;
            case 6: m.setRotate(90); break;
            case 7: m.setRotate(-90); m.postScale(-1, 1); break;
            case 8: m.setRotate(-90); break;
        }
        Bitmap r = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
        if (r != b) b.recycle();
        return r;
    }

    private static byte[] head(File f, int max) throws IOException {
        FileInputStream in = new FileInputStream(f);
        try {
            byte[] buf = new byte[(int) Math.min(max, f.length())];
            int n = 0, r;
            while (n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0) n += r;
            return n == buf.length ? buf : java.util.Arrays.copyOf(buf, n);
        } finally {
            in.close();
        }
    }
}
