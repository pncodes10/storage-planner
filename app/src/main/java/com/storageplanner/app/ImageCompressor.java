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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Compresses one image next to the original, never touching the original:
 * <ul>
 * <li>Photos and chat images become HEIC at the lowest quality that still passes the study's
 *     "looks identical" guard (SSIM(Y) >= 0.98 and PSNR(Y) >= 38 dB, as in {@link Calibrator}), plus a
 *     check that colours did not shift. EXIF (date taken, camera, location) is carried over.</li>
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
    static final int Q_LOW = 30, Q_HIGH = 95;
    /** Largest average shift of red, green or blue (0..255) allowed in a checked region. */
    static final double MAX_COLOR_SHIFT = 3.0;

    static final class Outcome {
        boolean ok;
        String output;       // path of the compressed copy
        long before, after, ms;
        int quality = -1;
        /** Worst checked region of the kept copy (-1 = not measured). */
        double ssim = -1, psnr = -1, shift = -1;
        boolean identicalPixels;   // lossless copies
        String skipped;      // why nothing was written (ok == false)
        /** Lab only: the copy was kept for comparison, but a real run would have thrown it away for this reason. */
        String wouldSkip;
        /** Every quality tried and its scores. */
        final List<String> trace = new ArrayList<String>();

        Outcome fail(String why) { ok = false; skipped = why; return this; }
    }

    /** Scores of one comparison (or the worst of several). */
    static final class Score {
        double ssim = 1, psnr = 99, shift = 0;
        boolean pass = true;

        void worst(Score s) {
            ssim = Math.min(ssim, s.ssim); psnr = Math.min(psnr, s.psnr); shift = Math.max(shift, s.shift);
            pass &= s.pass;
        }

        @Override public String toString() {
            return String.format(Locale.US, "%s (worst SSIM %.4f, PSNR %.1f dB, colour shift %.1f)", pass ? "pass" : "FAIL", ssim, psnr, shift);
        }

        static Score failed() { Score s = new Score(); s.ssim = 0; s.psnr = 0; s.shift = 255; s.pass = false; return s; }
    }

    /** Whether this phone can carry out the recommendation at all. */
    static boolean available(String technique) {
        return Compression.lossless(technique) || HeifEncoder.supported();
    }

    static String unavailableReason(String technique) {
        if (available(technique)) return null;
        return Build.VERSION.SDK_INT < 28 ? "Saving HEIC needs Android 9 or later."
                : "This phone has no HEVC encoder that apps can use, so it cannot save HEIC.";
    }

    /**
     * A real run: the copy goes next to the original, and only if it passes every check.
     * @param hint quality that worked for the previous file of this kind, tried first (-1 = none)
     * @param work folder for temporary test encodes (app cache)
     */
    static Outcome compress(FileItem f, String technique, int hint, File work) {
        File src = new File(f.abs);
        if (!src.isFile()) return new Outcome().fail("no longer on the phone");
        if (src.length() != f.size || Math.abs(src.lastModified() - f.mtime) > 2000) return new Outcome().fail("changed since the analysis");
        String out = Compression.outputPath(f.abs, technique);
        if (new File(out).exists()) return new Outcome().fail("a file named " + new File(out).getName() + " already exists");
        if ((long) f.width * f.height > MAX_PIXELS) return new Outcome().fail("too large to process safely");
        return write(src, technique, out, hint, work, false);
    }

    /**
     * Algorithm Lab: compress any picture to {@code out}. The copy is kept even when a real run would throw
     * it away (then {@link Outcome#wouldSkip} says why), so it can be compared with the original.
     */
    static Outcome compressForLab(File src, String technique, String out, File work) {
        return write(src, technique, out, -1, work, true);
    }

    private static Outcome write(File src, String technique, String out, int hint, File work, boolean keep) {
        long t0 = System.nanoTime();
        Outcome o = new Outcome();
        File tmp = new File(Compression.tempPath(out));
        try {
            if (Compression.lossless(technique)) lossless(src, tmp, keep, o); else lossy(src, tmp, hint, work, keep, o);
            if (!o.ok) return o;
            if (!tmp.renameTo(new File(out))) return o.fail("could not write the copy (" + new File(out).getParent() + ")");
            new File(out).setLastModified(src.lastModified());     // keeps its place in date-sorted views
            o.output = out;
            return o;
        } catch (OutOfMemoryError e) {
            return o.fail("not enough memory");
        } catch (Throwable t) {
            return o.fail("could not be compressed (" + t.getClass().getSimpleName() + (t.getMessage() != null ? ": " + t.getMessage() : "") + ")");
        } finally {
            if (tmp.exists()) tmp.delete();
            o.ms = (System.nanoTime() - t0) / 1_000_000;
        }
    }

    // ------------------------------------------------------------------ photos and chat images -> HEIC
    private static void lossy(File src, File tmp, int hint, File work, boolean keep, Outcome o) throws IOException {
        o.trace.add("encoder: " + encoderCheck(work));
        if (!HeifEncoder.supported()) { o.fail("no way of saving HEIC worked on this phone"); return; }
        byte[] exif = Exif.fromJpeg(head(src, 256 * 1024));
        int orientation = exif != null ? Exif.orientation(exif) : 1;
        Bitmap img = upright(decode(src, 1), orientation);
        if (exif != null && orientation != 1) exif = Exif.withOrientation(exif, 1);   // the pixels are upright now
        o.trace.add(String.format(Locale.US, "%d x %d px, EXIF %s, orientation %d", img.getWidth(), img.getHeight(),
                exif != null ? "copied (" + exif.length + " bytes)" : "none", orientation));
        o.before = src.length();
        try {
            int best = searchQuality(img, hint, work, o);
            if (best < 0) {
                if (!keep) { o.fail("no quality setting looked identical"); return; }
                o.wouldSkip = "no quality setting looked identical";
                best = Q_HIGH;
            }
            Rect[] verify = Calibrator.crops(img.getWidth(), img.getHeight(), 0.4, 0.6);   // other regions than the search
            long limit = (long) (src.length() * (1 - MIN_SAVING));
            for (int q = best; q <= Q_HIGH; q += 3) {
                HeifEncoder.encode(img, q, exif, tmp.getPath());
                boolean small = tmp.length() <= limit;
                if (!small && !keep) { o.fail("would save less than " + Math.round(MIN_SAVING * 100) + "%"); return; }
                Score s = verifyFile(img, tmp, verify, o);
                o.trace.add(String.format(Locale.US, "full image at quality %d: %s, %s", q, com.storageplanner.core.Report.size(tmp.length()), s));
                o.quality = q; o.after = tmp.length(); o.ssim = s.ssim; o.psnr = s.psnr; o.shift = s.shift;
                if (!small) {
                    if (o.wouldSkip == null) o.wouldSkip = "saves less than " + Math.round(MIN_SAVING * 100) + "%";
                    o.ok = true; return;
                }
                if (s.pass) { o.ok = true; return; }
            }
            if (!keep) { o.fail("did not look identical at full size"); return; }
            if (o.wouldSkip == null) o.wouldSkip = "did not look identical at full size";
            o.ok = true;                                           // Lab: keep the last try to look at
        } finally {
            img.recycle();
        }
    }

    /**
     * Lowest quality at which four 512 px regions all pass the guard. The regions are encoded together
     * as one 2x2 mosaic (four tiles, one encode per step). Binary search, starting at the hint.
     */
    private static int searchQuality(Bitmap img, int hint, File work, Outcome o) throws IOException {
        Rect[] rs = Calibrator.crops(img.getWidth(), img.getHeight(), 0.25, 0.75);
        int c = rs[0].width();
        Bitmap mosaic = Bitmap.createBitmap(2 * c, 2 * c, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(mosaic);
        for (int k = 0; k < 4; k++) cv.drawBitmap(img, rs[k], new Rect((k % 2) * c, (k / 2) * c, (k % 2 + 1) * c, (k / 2 + 1) * c), null);
        work.mkdirs();
        File test = new File(work, "search.heic");
        try {
            int lo = Q_LOW, hi = Q_HIGH, best = -1;
            boolean first = hint >= Q_LOW && hint <= Q_HIGH;
            while (lo <= hi) {
                int q = first ? hint : (lo + hi) / 2;
                first = false;
                HeifEncoder.encode(mosaic, q, null, test.getPath());
                Score s = mosaicScore(mosaic, test, c);
                o.trace.add(String.format(Locale.US, "search quality %d: %s", q, s));
                if (s.pass) { best = q; hi = q - 1; } else lo = q + 1;
            }
            return best;
        } finally {
            mosaic.recycle();
            test.delete();
        }
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
        if (s != null && full.pass) o.trace.add("  the region decoder read the copy wrongly (" + s + "); a full decode passes");
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
                if (whole == null) throw new IOException("the HEIC copy could not be opened");
            }
            int w = dec != null ? dec.getWidth() : whole.getWidth(), h = dec != null ? dec.getHeight() : whole.getHeight();
            if (w != img.getWidth() || h != img.getHeight()) return Score.failed();
            Score worst = new Score();
            for (Rect r : regions) {
                Bitmap a = Bitmap.createBitmap(img, r.left, r.top, r.width(), r.height());
                Bitmap b = dec != null ? dec.decodeRegion(r, argb()) : Bitmap.createBitmap(whole, r.left, r.top, r.width(), r.height());
                if (b == null) { a.recycle(); throw new IOException("a region of the HEIC copy could not be opened"); }
                worst.worst(score(a, b));
                a.recycle(); b.recycle();
            }
            return worst;
        } finally {
            if (dec != null) dec.recycle();
            if (whole != null) whole.recycle();
        }
    }

    // ------------------------------------------------------------------ which way of encoding works on this phone
    static final String CHECK_MAKE = "StoragePlanner check";
    private static String checked;

    /**
     * Once per run: encodes a test picture (1100x700, so the grid has partial tiles, with gradients and sharp
     * edges) each possible way and keeps the first that comes back right, preferring ways that keep EXIF:
     * HEIC encoder with EXIF, HEVC tiles with EXIF, HEIC encoder, HEVC tiles. Catches encoders whose output
     * decodes wrongly and phones that store EXIF as picture data or drop it.
     */
    static synchronized String encoderCheck(File work) {
        if (checked != null) return checked;
        if (!HeifEncoder.supported()) return checked = "none";
        work.mkdirs();
        File f = new File(work, "check.heic");
        Bitmap t = testPattern(1100, 700);
        byte[] exif = Exif.minimal(CHECK_MAKE);
        boolean[][] ways = {{true, true}, {false, true}, {true, false}, {false, false}};
        StringBuilder log = new StringBuilder();
        boolean found = false;
        try {
            for (boolean[] w : ways) {
                if (w[0] ? !HeifEncoder.hasHeicEncoder() : !HeifEncoder.hasHevcEncoder()) continue;
                String why = tryWay(t, w[0], w[1] ? exif : null, f);
                log.append(log.length() > 0 ? "; " : "").append(w[0] ? "HEIC encoder" : "HEVC tiles").append(w[1] ? " + EXIF" : "")
                   .append(why == null ? ": ok" : ": " + why);
                if (why == null) { HeifEncoder.choose(w[0], w[1]); found = true; break; }
            }
        } finally {
            t.recycle();
            f.delete();
        }
        if (!found) HeifEncoder.markUnusable();
        return checked = (found ? HeifEncoder.describe() : "no way of saving HEIC worked") + " [check: " + log + "]";
    }

    /** Null if this way works, else why not. */
    private static String tryWay(Bitmap t, boolean heic, byte[] exif, File f) {
        Bitmap d = null;
        try {
            HeifEncoder.encodeWith(heic, t, 90, exif, f.getPath());
            d = BitmapFactory.decodeFile(f.getPath(), argb());
            if (d == null) return "the file could not be opened";
            if (d.getWidth() != t.getWidth() || d.getHeight() != t.getHeight()) return "came back " + d.getWidth() + "x" + d.getHeight();
            Score s = score(t, d);
            if (s.psnr < 30 || s.ssim < 0.9) return "the picture came back wrong, " + s;
            if (exif != null) {
                String make = new android.media.ExifInterface(f.getPath()).getAttribute(android.media.ExifInterface.TAG_MAKE);
                if (make == null || !CHECK_MAKE.equals(make.trim())) return "EXIF was not stored (read back: " + make + ")";
            }
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
