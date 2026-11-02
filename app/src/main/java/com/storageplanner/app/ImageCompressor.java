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

/**
 * Compresses one image next to the original, never touching the original:
 * <ul>
 * <li>Photos and chat images become HEIC at the lowest quality that still passes the study's
 *     "looks identical" guard (SSIM(Y) >= 0.98 and PSNR(Y) >= 38 dB, as in {@link Calibrator}), plus a
 *     check that colours did not shift. EXIF (date taken, camera, location) is carried over.</li>
 * <li>Screenshots become lossless WebP, and the copy must decode to exactly the same pixels.</li>
 * </ul>
 * The copy is written to a work file, checked, and only then renamed to its final name.
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
        long before, after;
        int quality = -1;
        String skipped;      // why nothing was written (ok == false)

        static Outcome skip(String why) { Outcome o = new Outcome(); o.skipped = why; return o; }
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
     * @param hint quality that worked for the previous file of this kind, tried first (-1 = none)
     * @param work folder for temporary test encodes (app cache)
     */
    static Outcome compress(FileItem f, String technique, int hint, File work) {
        File src = new File(f.abs);
        if (!src.isFile()) return Outcome.skip("no longer on the phone");
        if (src.length() != f.size || Math.abs(src.lastModified() - f.mtime) > 2000) return Outcome.skip("changed since the analysis");
        String out = Compression.outputPath(f.abs, technique);
        if (new File(out).exists()) return Outcome.skip("a file named " + new File(out).getName() + " already exists");
        if ((long) f.width * f.height > MAX_PIXELS) return Outcome.skip("too large to process safely");
        File tmp = new File(Compression.tempPath(out));
        try {
            Outcome o = Compression.lossless(technique) ? lossless(src, tmp) : lossy(src, tmp, hint, work);
            if (!o.ok) return o;
            if (!tmp.renameTo(new File(out))) return Outcome.skip("could not write next to the original");
            new File(out).setLastModified(src.lastModified());     // keeps its place in date-sorted views
            o.output = out;
            return o;
        } catch (OutOfMemoryError e) {
            return Outcome.skip("not enough memory");
        } catch (Throwable t) {
            return Outcome.skip("could not be compressed (" + t.getClass().getSimpleName()
                    + (t.getMessage() != null ? ": " + t.getMessage() : "") + ")");
        } finally {
            if (tmp.exists()) tmp.delete();
        }
    }

    // ------------------------------------------------------------------ photos and chat images -> HEIC
    private static Outcome lossy(File src, File tmp, int hint, File work) throws IOException {
        byte[] exif = Exif.fromJpeg(head(src, 256 * 1024));
        int orientation = exif != null ? Exif.orientation(exif) : 1;
        Bitmap img = upright(decode(src), orientation);
        if (exif != null && orientation != 1) exif = Exif.withOrientation(exif, 1);   // the pixels are upright now
        try {
            int best = searchQuality(img, hint, work);
            if (best < 0) return Outcome.skip("no quality setting looked identical");
            Rect[] verify = Calibrator.crops(img.getWidth(), img.getHeight(), 0.4, 0.6);   // other regions than the search
            long limit = (long) (src.length() * (1 - MIN_SAVING));
            for (int q = best; q <= Q_HIGH; q += 3) {
                HeifEncoder.encode(img, q, exif, tmp.getPath());
                if (tmp.length() > limit) return Outcome.skip("would save less than " + Math.round(MIN_SAVING * 100) + "%");
                if (verifyFile(img, tmp, verify)) {
                    Outcome o = new Outcome();
                    o.ok = true; o.before = src.length(); o.after = tmp.length(); o.quality = q;
                    return o;
                }
            }
            return Outcome.skip("did not look identical at full size");
        } finally {
            img.recycle();
        }
    }

    /**
     * Lowest quality at which four 512 px regions all pass the guard. The regions are encoded together
     * as one 2x2 mosaic (four tiles, one encode per step). Binary search, starting at the hint.
     */
    private static int searchQuality(Bitmap img, int hint, File work) throws IOException {
        Rect[] rs = Calibrator.crops(img.getWidth(), img.getHeight(), 0.25, 0.75);
        int c = rs[0].width();
        Bitmap mosaic = Bitmap.createBitmap(2 * c, 2 * c, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(mosaic);
        for (int k = 0; k < 4; k++) cv.drawBitmap(img, rs[k], new Rect((k % 2) * c, (k / 2) * c, (k % 2 + 1) * c, (k / 2 + 1) * c), null);
        File test = new File(work, "search.heic");
        try {
            int lo = Q_LOW, hi = Q_HIGH, best = -1;
            boolean first = hint >= Q_LOW && hint <= Q_HIGH;
            while (lo <= hi) {
                int q = first ? hint : (lo + hi) / 2;
                first = false;
                HeifEncoder.encode(mosaic, q, null, test.getPath());
                if (mosaicPasses(mosaic, test, c)) { best = q; hi = q - 1; } else lo = q + 1;
            }
            return best;
        } finally {
            mosaic.recycle();
            test.delete();
        }
    }

    private static boolean mosaicPasses(Bitmap mosaic, File enc, int c) {
        Bitmap d = BitmapFactory.decodeFile(enc.getPath(), argb());
        if (d == null) return false;
        try {
            if (d.getWidth() != mosaic.getWidth() || d.getHeight() != mosaic.getHeight()) return false;
            for (int k = 0; k < 4; k++) {
                int x = (k % 2) * c, y = (k / 2) * c;
                Bitmap a = Bitmap.createBitmap(mosaic, x, y, c, c), b = Bitmap.createBitmap(d, x, y, c, c);
                boolean ok = same(a, b);
                a.recycle(); b.recycle();
                if (!ok) return false;
            }
            return true;
        } finally {
            d.recycle();
        }
    }

    /** Checks the real output: region decoding where the phone supports it for HEIC, else a full decode. */
    private static boolean verifyFile(Bitmap img, File enc, Rect[] regions) throws IOException {
        BitmapRegionDecoder dec = null;
        Bitmap whole = null;
        try {
            try { dec = newRegionDecoder(enc.getPath()); } catch (Throwable t) { dec = null; }
            if (dec == null) {
                whole = BitmapFactory.decodeFile(enc.getPath(), argb());
                if (whole == null) throw new IOException("the HEIC copy could not be opened");
            }
            int w = dec != null ? dec.getWidth() : whole.getWidth(), h = dec != null ? dec.getHeight() : whole.getHeight();
            if (w != img.getWidth() || h != img.getHeight()) return false;
            for (Rect r : regions) {
                Bitmap a = Bitmap.createBitmap(img, r.left, r.top, r.width(), r.height());
                Bitmap b = dec != null ? dec.decodeRegion(r, argb()) : Bitmap.createBitmap(whole, r.left, r.top, r.width(), r.height());
                if (b == null) { a.recycle(); throw new IOException("a region of the HEIC copy could not be opened"); }
                boolean ok = same(a, b);
                a.recycle(); b.recycle();
                if (!ok) return false;
            }
            return true;
        } finally {
            if (dec != null) dec.recycle();
            if (whole != null) whole.recycle();
        }
    }

    @SuppressWarnings("deprecation")
    private static BitmapRegionDecoder newRegionDecoder(String path) throws IOException {
        return Build.VERSION.SDK_INT >= 31 ? BitmapRegionDecoder.newInstance(path) : BitmapRegionDecoder.newInstance(path, false);
    }

    /** The study's guard on brightness, plus: no colour channel moved on average. */
    private static boolean same(Bitmap a, Bitmap b) {
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) return false;
        return Calibrator.guard(Calibrator.luma(a), Calibrator.luma(b), a.getWidth(), a.getHeight()) && colorsClose(a, b);
    }

    private static boolean colorsClose(Bitmap a, Bitmap b) {
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
        return Math.abs(dr / n) <= MAX_COLOR_SHIFT && Math.abs(dg / n) <= MAX_COLOR_SHIFT && Math.abs(db / n) <= MAX_COLOR_SHIFT;
    }

    // ------------------------------------------------------------------ screenshots -> lossless WebP
    @SuppressWarnings("deprecation")
    private static Outcome lossless(File src, File tmp) throws IOException {
        Bitmap bm = decode(src), back = null;
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            if (Build.VERSION.SDK_INT >= 30) bm.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, bos);
            else bm.compress(Bitmap.CompressFormat.WEBP, 100, bos);    // quality 100 is lossless before Android 11
            byte[] d = bos.toByteArray();
            if (d.length > src.length() * (1 - MIN_SAVING)) return Outcome.skip("would save less than " + Math.round(MIN_SAVING * 100) + "%");
            back = BitmapFactory.decodeByteArray(d, 0, d.length, argb());
            if (back == null || !back.sameAs(bm)) return Outcome.skip("the copy was not pixel-identical");
            FileOutputStream out = new FileOutputStream(tmp);
            try { out.write(d); out.getFD().sync(); } finally { out.close(); }
            if (tmp.length() != d.length) throw new IOException("the copy was not written completely");
            Outcome o = new Outcome();
            o.ok = true; o.before = src.length(); o.after = d.length;
            return o;
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

    private static Bitmap decode(File f) throws IOException {
        Bitmap b = BitmapFactory.decodeFile(f.getPath(), argb());
        if (b == null) throw new IOException("the image could not be opened");
        return b;
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
