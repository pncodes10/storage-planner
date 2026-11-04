package com.storageplanner.tools;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Canvas;
import android.graphics.ColorSpace;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.os.Build;

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
 * Compression tool: makes a smaller copy of one picture next to the original, never touching the original.
 * See README.md in this folder for how to use it and what it guarantees.
 * <ul>
 * <li>{@link Kind#PHOTO} (camera photos, chat images): every way of saving that works on this phone (each HEIC encoder, and
 *     Android's WebP encoder) finds the lowest quality that still passes the study's "looks identical"
 *     guard (SSIM(Y) >= 0.98 and PSNR(Y) >= 38 dB, see {@link Guard}) plus a colour check, on four
 *     512 px regions. The way with the smallest result is encoded in full and checked again on six other
 *     regions (two of them the top-left and bottom-right corners); if it fails, the next way is tried. EXIF (date
 *     taken, camera, location) is carried over.</li>
 * <li>{@link Kind#LOSSLESS} (screenshots): lossless WebP, and the copy must decode to exactly the same pixels.</li>
 * <li>Never compressed: pictures a copy would not fully keep: Motion Photos, Ultra HDR gain maps, depth maps
 *     ({@link Extras}). Wide-colour pictures (Display P3, as Samsung cameras take them) are kept only by a copy that
 *     carries the same colour space: WebP (which stores the colour profile), never HEIC (stored as sRGB here).</li>
 * </ul>
 * The copy is written to a work file, checked, and only then renamed to its final name. Every step's
 * scores are recorded in the outcome, for the Algorithm Lab.
 */
public final class ImageCompressor {
    private ImageCompressor() {}

    /** What kind of copy to make. */
    public enum Kind {
        /** Looks identical (lossy): HEIC or WebP, whichever passes and is smallest. Camera photos, chat images. */
        PHOTO("heic", "webp"),
        /** Pixel-identical lossless WebP. Screenshots and other flat graphics. */
        LOSSLESS("webp");

        /** Extensions a copy of this kind can have. */
        public final String[] extensions;
        Kind(String... ext) { extensions = ext; }
    }

    /** Bigger images may not fit in memory twice. */
    public static final long MAX_PIXELS = 25_000_000L;
    /** A copy that saves less than this is not worth replacing the original with. */
    public static final double MIN_SAVING = 0.10;
    static final int Q_LOW = 30, Q_HIGH = 95, WEBP_LOW = 50, WEBP_HIGH = 99;
    /** WebP passed at 89-93 on every camera photo tried, so its search starts here rather than mid-range. */
    static final int WEBP_START = 90;
    /** In a real run, after this many photos, ways of saving that never made the kept copy are no longer searched. */
    static final int DROP_AFTER = 5;
    private static final String FILES = "#files", WINS = "#wins:";
    /** Largest average shift of red, green or blue (0..255) allowed in a checked region: catches a colour cast. */
    public static final double MAX_COLOR_SHIFT = 3.0;
    /**
     * Lowest PSNR of the colour (Cb and Cr) allowed in a checked region: catches colour bleeding and blur, which
     * cancel out in the average shift. Copies that pass the brightness guard measured 46-52 dB on the test photos.
     */
    public static final double CHROMA_PSNR_MIN = 38.0;

    /** One full-size copy that was made and checked. */
    public static final class Attempt {
        public String key, label, ext;
        public String path;          // Lab: where this copy was kept (null if not kept)
        public int quality;
        public long bytes;
        public Score score;
        public String note;          // why a real run would not keep it (null = it passes)
    }

    public static final class Outcome {
        public boolean ok;
        public String output;        // path of the compressed copy
        public String key, format;   // the way that made it, e.g. "webp" / "WebP (Android's encoder)"
        public long before, after, ms;
        public int quality = -1;
        /** Worst checked region of the kept copy (-1 = not measured). */
        public double ssim = -1, psnr = -1, shift = -1, chromaPsnr = -1;
        public boolean identicalPixels;   // lossless copies
        public String skipped;       // why nothing was written (ok == false)
        /** Lab only: the copy was kept for comparison, but a real run would have thrown it away for this reason. */
        public String wouldSkip;
        /** Every quality tried and its scores. */
        public final List<String> trace = new ArrayList<String>();
        /** Every full-size copy made (Lab: one per way of saving). */
        public final List<Attempt> attempts = new ArrayList<Attempt>();

        Outcome fail(String why) { ok = false; skipped = why; return this; }
    }

    /** Scores of one comparison (or the worst of several). */
    public static final class Score {
        public double ssim = 1, psnr = 99, shift = 0, chromaPsnr = 99;
        public boolean pass = true;
        /** Android's region decoder (used by galleries to zoom) read the file differently from a full decode. */
        public boolean regionWrong;
        /** What the check noticed about the decoders, logged after the line about this copy. */
        public final List<String> notes = new ArrayList<String>();
        public String why;           // set when the copy could not be compared at all

        void worst(Score s) {
            ssim = Math.min(ssim, s.ssim); psnr = Math.min(psnr, s.psnr); shift = Math.max(shift, s.shift);
            chromaPsnr = Math.min(chromaPsnr, s.chromaPsnr);
            pass &= s.pass;
        }

        @Override public String toString() {
            if (why != null) return "FAIL (" + why + ")";
            return String.format(Locale.US, "%s (worst SSIM %.4f, PSNR %.1f dB, colour shift %.1f, colour PSNR %.1f dB)", pass ? "pass" : "FAIL", ssim, psnr, shift, chromaPsnr);
        }

        static Score failed() { Score s = new Score(); s.ssim = 0; s.psnr = 0; s.shift = 255; s.chromaPsnr = 0; s.pass = false; return s; }
        static Score failed(String why) { Score s = failed(); s.why = why; return s; }
    }

    /** Whether this phone can make this kind of copy at all. */
    public static boolean available(Kind kind) {
        return kind == Kind.LOSSLESS || Build.VERSION.SDK_INT >= 28;
    }

    public static String unavailableReason(Kind kind) {
        if (available(kind)) return null;
        return "Compressing photos needs Android 9 or later (to save HEIC, and to keep a photo's details in WebP).";
    }

    /**
     * Makes the copy next to the original ({@code IMG_1.jpg -> IMG_1.heic / .webp}), kept only if it passes every check.
     * The caller decides which files to compress and must check the file is still the one it means (same size and
     * date as when it was chosen); see README.md.
     * @param hints one map per run of many files: remembers the quality that worked per way of saving, and which
     *              ways made the kept copies, to search faster (updated here; start with an empty map)
     * @param work  folder for temporary test encodes (the app's cache)
     */
    public static Outcome compress(File src, Kind kind, Map<String, Integer> hints, File work) {
        String why = cannotOpen(src);
        if (why != null) return new Outcome().fail(why);
        String base = CopyPaths.base(src.getPath());
        for (String ext : kind.extensions) {
            File out = new File(base + "." + ext);
            if (out.getPath().equals(src.getPath())) return new Outcome().fail("it is already a " + ext.toUpperCase(Locale.US) + " file");
            if (out.exists()) return new Outcome().fail("a compressed copy (" + out.getName() + ") already exists");
        }
        why = wouldLose(src);
        if (why != null) return new Outcome().fail(why);
        Outcome o = write(src, kind, base, hints, work, false);
        if (o.ok && o.key != null) {
            if (o.quality >= 0) hints.put(o.key, o.quality);
            hints.put(WINS + o.key, count(hints, WINS + o.key) + 1);
            hints.put(FILES, count(hints, FILES) + 1);
        }
        return o;
    }

    /**
     * Algorithm Lab: compress any picture to {@code base} + "-" + way + extension, once per way of saving,
     * keeping every copy (even ones a real run would throw away; {@link Attempt#note} says why) so they can be
     * compared. {@link Outcome#output} is the one a real run would choose.
     */
    public static Outcome compressForLab(File src, Kind kind, String base, File work) {
        String why = cannotOpen(src);
        if (why != null) return new Outcome().fail(why);
        String lose = wouldLose(src);
        Outcome o = write(src, kind, base, new HashMap<String, Integer>(), work, true);
        if (lose != null) {                                   // Lab: compressed anyway, to compare
            o.trace.add(0, "a real run would not compress this picture: " + lose);
            if (o.ok) o.wouldSkip = lose;
        }
        return o;
    }

    /** Why the picture cannot be compressed at all (gone, not a picture, too large for memory), or null. */
    private static String cannotOpen(File src) {
        if (!src.isFile()) return "no longer on the phone";
        BitmapFactory.Options b = new BitmapFactory.Options();
        b.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(src.getPath(), b);
        if (b.outWidth <= 0 || b.outHeight <= 0) return "not a picture Android can open";
        if ((long) b.outWidth * b.outHeight > MAX_PIXELS) return "too large to process safely";
        return null;
    }

    /**
     * Why a copy would not keep all of this picture, or null: a Motion Photo, gain map or depth map (see
     * {@link Extras}, which streams the file, so any size is checked). A file that cannot be checked is refused.
     */
    private static String wouldLose(File src) {
        try {
            FileInputStream in = new FileInputStream(src);
            try { return Extras.lostOnCopy(in); } finally { in.close(); }
        } catch (Throwable t) {
            return "it could not be checked for Motion Photo or depth data (" + t.getClass().getSimpleName() + ")";
        }
    }

    /** The picture's colour space if it is not sRGB (e.g. Display P3), else null. */
    private static ColorSpace wideColour(File f) {
        BitmapFactory.Options b = new BitmapFactory.Options();
        b.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), b);
        return b.outColorSpace != null && !b.outColorSpace.isSrgb() ? b.outColorSpace : null;
    }

    /**
     * Null if a copy decodes in the same colour space as its wide-colour original, else what it got instead.
     * HEIC is stored as sRGB / BT.601 without a colour profile, so a Display P3 photo would look duller; Android's
     * WebP encoder writes the profile (ICCP chunk), which {@link WebpMeta} keeps.
     */
    private static String colourLost(File copy, ColorSpace want) {
        BitmapFactory.Options b = new BitmapFactory.Options();
        b.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(copy.getPath(), b);
        if (b.outColorSpace != null && sameColours(b.outColorSpace, want)) return null;
        return "it would not keep the photo's " + want.getName() + " colours (the copy is " + (b.outColorSpace != null ? b.outColorSpace.getName() : "untagged") + ")";
    }

    /** "1.9 MB", "230 KB": the same style as the app's reports. */
    static String size(long b) {
        if (b >= 1e9) return String.format(Locale.US, "%.2f GB", b / 1e9);
        if (b >= 1e6) return String.format(Locale.US, "%.1f MB", b / 1e6);
        return String.format(Locale.US, "%.0f KB", b / 1e3);
    }

    private static int count(Map<String, Integer> m, String k) { Integer v = m.get(k); return v == null ? 0 : v; }

    private static Outcome write(File src, Kind kind, String base, Map<String, Integer> hints, File work, boolean keep) {
        long t0 = System.nanoTime();
        Outcome o = new Outcome();
        List<File> temps = new ArrayList<File>();
        try {
            if (kind == Kind.LOSSLESS) {
                String out = keep ? base + "-lossless.webp" : base + ".webp";
                File tmp = new File(CopyPaths.tempPath(out)); temps.add(tmp);
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
    /**
     * Whether two colour spaces show the same colours: equal, or both RGB with the same primaries and white point
     * and the same transfer curve, within 0.001. A colour profile read back from a copy can come out as an unnamed
     * space with slightly different numbers than the original's.
     */
    static boolean sameColours(ColorSpace a, ColorSpace b) {
        if (a.equals(b)) return true;
        if (!(a instanceof ColorSpace.Rgb) || !(b instanceof ColorSpace.Rgb)) return false;
        ColorSpace.Rgb x = (ColorSpace.Rgb) a, y = (ColorSpace.Rgb) b;
        if (!close(x.getPrimaries(), y.getPrimaries()) || !close(x.getWhitePoint(), y.getWhitePoint())) return false;
        for (double v = 0; v <= 1.0001; v += 0.05)
            if (Math.abs(x.getEotf().applyAsDouble(v) - y.getEotf().applyAsDouble(v)) > 1e-3) return false;
        return true;
    }

    private static boolean close(float[] p, float[] q) {
        if (p.length != q.length) return false;
        for (int i = 0; i < p.length; i++) if (Math.abs(p[i] - q[i]) > 1e-3) return false;
        return true;
    }

    /** HEIC copies count this much bigger when picking the smallest: HEIC must be 5% smaller to win over WebP, which every app can open. */
    static final double HEIC_PENALTY = 1.05;

    private static double weight(Method m, long bytes) { return m != null && m.way != null ? bytes * HEIC_PENALTY : bytes; }

    private static Method byKey(String key) {
        if (methods != null) for (Method m : methods) if (m.key.equals(key)) return m;
        return null;
    }

    /** A way of saving under consideration for one picture. */
    private static final class Cand {
        final Method m; int best = -1; long mosaicBytes;
        final String notInRealRun;   // Lab: tried anyway, but a real run would not use this way, for this reason
        Cand(Method m, String notInRealRun) { this.m = m; this.notInRealRun = notInRealRun; }
    }

    private static void lossy(File src, String base, Map<String, Integer> hints, File work, boolean keep, Outcome o, List<File> temps) throws IOException {
        List<Method> methods = methods(work);
        o.trace.add("ways of saving: " + checked);
        byte[] exif = Exif.fromJpeg(head(src, 256 * 1024));
        int orientation = exif != null ? Exif.orientation(exif) : 1;
        ColorSpace wide = wideColour(src);
        List<Cand> cands = new ArrayList<Cand>();
        for (Method m : methods) {
            if (!m.works) continue;
            String why = exif != null && !m.exifOk ? "it would lose the photo's details (EXIF)"
                    : !m.regionOk ? "Android's region decoder reads its files wrongly, so galleries may show them wrong when zoomed"
                    : wide != null && m.way != null ? "HEIC is stored as sRGB here, so the photo's " + wide.getName() + " colours would look duller" : null;
            if (why != null) { o.trace.add(m.label + ": not used in a real run, " + why); if (!keep) continue; }
            int files = count(hints, FILES);
            if (!keep && files >= DROP_AFTER && count(hints, WINS + m.key) == 0) {
                o.trace.add(m.label + ": skipped, it made none of the " + files + " copies kept so far in this run");
                continue;
            }
            cands.add(new Cand(m, why));
        }
        if (cands.isEmpty()) { o.fail(wide != null ? "no way of saving here keeps its " + wide.getName() + " colours" : "no way of saving worked on this phone"); return; }
        Bitmap img = upright(decode(src, 1), orientation);
        exif = uprightExif(exif, orientation, img);
        o.trace.add(String.format(Locale.US, "%d x %d px, EXIF %s, orientation %d, colours %s", img.getWidth(), img.getHeight(),
                exif != null ? "copied (" + exif.length + " bytes)" : "none", orientation, wide != null ? wide.getName() : "sRGB"));
        o.before = src.length();
        Rect[] rs = Guard.crops(img.getWidth(), img.getHeight(), 0.25, 0.75);
        int c = rs[0].width();
        // in the picture's own colour space: an sRGB mosaic would convert (and clip) Display P3 pixels
        Bitmap mosaic = Bitmap.createBitmap(2 * c, 2 * c, Bitmap.Config.ARGB_8888, true, img.getColorSpace());
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
            Rect[] verify = verifyRegions(img.getWidth(), img.getHeight());
            long limit = (long) (src.length() * (1 - MIN_SAVING));
            Attempt chosen = null;
            for (Cand cd : ranked) {
                String out = keep ? base + "-" + cd.m.key + "." + cd.m.ext : base + "." + cd.m.ext;
                File tmp = new File(CopyPaths.tempPath(out)); temps.add(tmp);
                Attempt a = null;
                for (int q = cd.best; q <= cd.m.hi; q += 3) {
                    encodeTo(cd.m, img, q, exif, tmp);
                    a = new Attempt();
                    a.key = cd.m.key; a.label = cd.m.label; a.ext = cd.m.ext; a.quality = q; a.bytes = tmp.length();
                    String colour = wide != null ? colourLost(tmp, wide) : null;
                    if (colour != null) {
                        a.note = colour;
                        o.trace.add(String.format(Locale.US, "%s, full image at quality %d: %s, %s", cd.m.label, q, size(a.bytes), colour));
                        break;
                    }
                    if (a.bytes > limit) {
                        a.note = "saves less than " + Math.round(MIN_SAVING * 100) + "%";
                        if (keep) a.score = verifyFile(img, tmp, verify, o, keep);
                        o.trace.add(String.format(Locale.US, "%s, full image at quality %d: %s, %s", cd.m.label, q, size(a.bytes),
                                a.score != null ? a.score + ", " + a.note : a.note));
                        if (a.score != null) o.trace.addAll(a.score.notes);
                        break;
                    }
                    a.score = verifyFile(img, tmp, verify, o, keep);
                    o.trace.add(String.format(Locale.US, "%s, full image at quality %d: %s, %s", cd.m.label, q, size(a.bytes), a.score));
                    o.trace.addAll(a.score.notes);
                    if (a.score.pass && a.score.regionWrong) { a.note = REGION_WRONG; break; }   // a higher quality would not change that
                    if (a.score.pass) break;
                    a.note = "did not look identical at full size";
                }
                if (a == null) continue;
                if (a.note == null && cd.notInRealRun != null) a.note = cd.notInRealRun;   // Lab: shown, never chosen
                o.attempts.add(a);
                if (keep && tmp.renameTo(new File(out))) { a.path = out; new File(out).setLastModified(src.lastModified()); }
                if (a.note == null && (!keep || a.path != null) && (chosen == null || weight(cd.m, a.bytes) < weight(byKey(chosen.key), chosen.bytes))) {
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
                for (Attempt a : o.attempts) if (a.note != null && a.note.startsWith("it would not keep")) why = a.note;
                if (!keep || best == null) { o.fail(why); return; }
                o.wouldSkip = best.note;                           // Lab: show the smallest anyway
                chosen = best;
            }
            o.output = chosen.path; o.key = chosen.key; o.format = chosen.label; o.quality = chosen.quality; o.after = chosen.bytes;
            if (chosen.score != null) { o.ssim = chosen.score.ssim; o.psnr = chosen.score.psnr; o.shift = chosen.score.shift; o.chromaPsnr = chosen.score.chromaPsnr; }
            o.ok = true;
        } finally {
            mosaic.recycle();
            img.recycle();
        }
    }

    /**
     * The EXIF block for a copy whose pixels were turned upright: orientation 1, the upright size (cameras record the
     * size before turning), and no thumbnail (it stays in the camera's orientation, so it would show sideways).
     */
    static byte[] uprightExif(byte[] exif, int orientation, Bitmap img) {
        if (exif == null) return null;
        if (orientation != 1) exif = Exif.withoutThumbnail(Exif.withOrientation(exif, 1));
        return Exif.withSize(exif, img.getWidth(), img.getHeight());
    }

    /**
     * Where the full-size copy is checked: four regions other than the search's (0.4 / 0.6), plus the top-left and
     * bottom-right corners, where HEVC tiles are partial (the picture's edge) and encoders go wrong most.
     */
    static Rect[] verifyRegions(int w, int h) {
        Rect[] mid = Guard.crops(w, h, 0.4, 0.6);
        int c = mid[0].width();
        Rect[] r = java.util.Arrays.copyOf(mid, mid.length + 2);   // first and last: also checked with the region decoder
        r[mid.length] = new Rect(0, 0, c, c);
        r[mid.length + 1] = new Rect(w - c, h - c, w, h);
        return r;
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
            int q0 = hint != null && hint >= lo && hint <= hi ? hint : cd.m.start;
            boolean first = q0 >= lo && q0 <= hi;
            while (lo <= hi) {
                int q = first ? q0 : (lo + hi) / 2;
                first = false;
                encodeTo(cd.m, mosaic, q, null, test);
                Score s = mosaicScore(mosaic, test, c);
                o.trace.add(String.format(Locale.US, "%s, regions at quality %d: %s, %s", cd.m.label, q, size(test.length()), s));
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
        if (!bm.compress(Guard.webpLossy(), q, bos)) throw new IOException("the WebP encoder failed");
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

    /** Galleries ask the region decoder for zoom tiles at aligned spots; the app checks it at multiples of this. */
    static final int ALIGN = 16;

    /**
     * Checks the real output on the given regions against the original, with a full decode (exactly what the file
     * holds). Then checks that Android's region decoder, which galleries use to zoom, reads the same pixels at
     * aligned spots; if not, the score is marked {@link Score#regionWrong}. The region decoder is not used for the
     * quality check itself: it moves an unaligned region (WebP to even pixels, so a 512 px region at x 483 comes back
     * 513 px wide starting at 482), which would compare shifted pixels. Without memory for a full decode, the quality
     * is checked with the region decoder at aligned spots.
     */
    private static Score verifyFile(Bitmap img, File enc, Rect[] regions, Outcome o, boolean diagnose) throws IOException {
        Bitmap full = null;
        BitmapRegionDecoder dec = null;
        try {
            try { full = BitmapFactory.decodeFile(enc.getPath(), argb()); } catch (OutOfMemoryError e) { full = null; }
            try { dec = newRegionDecoder(enc.getPath()); } catch (Throwable t) { dec = null; }
            if (full == null) {
                if (dec == null) throw new IOException("the copy could not be opened");
                Rect[] al = new Rect[regions.length];
                for (int i = 0; i < regions.length; i++) al[i] = aligned(regions[i]);
                dec.recycle(); dec = null;
                Score r = regionScore(img, enc, al);
                r.notes.add("  (not enough memory for a full decode: checked with the region decoder at aligned spots)");
                return r;
            }
            if (full.getWidth() != img.getWidth() || full.getHeight() != img.getHeight())
                return Score.failed("a full decode sees " + full.getWidth() + "x" + full.getHeight() + " instead of " + img.getWidth() + "x" + img.getHeight());
            Score worst = new Score();
            for (Rect r : regions) {
                Bitmap a = Bitmap.createBitmap(img, r.left, r.top, r.width(), r.height());
                Bitmap b = Bitmap.createBitmap(full, r.left, r.top, r.width(), r.height());
                worst.worst(score(a, b));
                a.recycle(); b.recycle();
            }
            if (dec != null) {
                for (Rect r : new Rect[]{aligned(regions[0]), aligned(regions[regions.length - 1])}) {
                    String why = regionAgrees(dec, full, r);
                    if (why != null) {
                        worst.regionWrong = true;
                        worst.notes.add("  Android's region decoder reads this copy wrongly at an aligned spot (x " + r.left + ", y " + r.top + "): " + why);
                        break;
                    }
                }
                if (diagnose) {                                // Lab: the same region at the unaligned spot, for comparison
                    Rect odd = regions[0], al = aligned(odd);
                    String w1 = regionAgrees(dec, full, odd), w2 = regionAgrees(dec, full, al);
                    worst.notes.add("  region decoder at x " + odd.left + ", y " + odd.top + ": " + (w1 == null ? "same as a full decode" : w1)
                            + "; at aligned x " + al.left + ", y " + al.top + ": " + (w2 == null ? "same as a full decode" : w2));
                }
            }
            return worst;
        } finally {
            if (dec != null) dec.recycle();
            if (full != null) full.recycle();
        }
    }

    /** The same size region, moved left and up to a multiple of {@link #ALIGN}. */
    static Rect aligned(Rect r) {
        int l = r.left / ALIGN * ALIGN, t = r.top / ALIGN * ALIGN;
        return new Rect(l, t, l + r.width(), t + r.height());
    }

    /** Null if the region decoder returns the same pixels as a full decode of the file for {@code r}, else what differs. */
    private static String regionAgrees(BitmapRegionDecoder dec, Bitmap full, Rect r) {
        Bitmap part = null;
        try {
            part = dec.decodeRegion(r, argb());
            if (part == null) return "nothing came back";
            if (part.getWidth() != r.width() || part.getHeight() != r.height())
                return "a " + r.width() + "x" + r.height() + " region came back " + part.getWidth() + "x" + part.getHeight();
            Bitmap ref = Bitmap.createBitmap(full, r.left, r.top, r.width(), r.height());
            Score s = score(ref, part);
            ref.recycle();
            return s.psnr >= 40 ? null : String.format(Locale.US, "it differs from a full decode (PSNR %.1f dB, SSIM %.4f)", s.psnr, s.ssim);
        } catch (Throwable t) {
            return "it failed (" + t.getClass().getSimpleName() + ")";
        } finally {
            if (part != null) part.recycle();
        }
    }

    /** The quality check with the region decoder at the given (aligned) regions, when a full decode does not fit in memory. */
    private static Score regionScore(Bitmap img, File enc, Rect[] regions) throws IOException {
        BitmapRegionDecoder dec = newRegionDecoder(enc.getPath());
        if (dec == null) throw new IOException("no region decoder for this file");
        try {
            if (dec.getWidth() != img.getWidth() || dec.getHeight() != img.getHeight())
                return Score.failed("the region decoder sees " + dec.getWidth() + "x" + dec.getHeight() + " instead of " + img.getWidth() + "x" + img.getHeight());
            Score worst = new Score();
            for (Rect r : regions) {
                Bitmap a = Bitmap.createBitmap(img, r.left, r.top, r.width(), r.height());
                Bitmap b = dec.decodeRegion(r, argb());
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
            dec.recycle();
        }
    }

    // ------------------------------------------------------------------ which ways of saving work on this phone
    /** A way of saving a photo: one HEIC encoder ({@link #way}) or Android's WebP encoder (way == null). */
    static final class Method {
        final String key, label, ext;
        final HeifEncoder.Way way;
        final int lo, hi, start;     // quality range, and the first quality tried when there is no hint yet
        boolean works, exifOk, regionOk = true;
        String result = "";
        Method(String key, String label, String ext, HeifEncoder.Way way, int lo, int hi, int start) {
            this.key = key; this.label = label; this.ext = ext; this.way = way; this.lo = lo; this.hi = hi; this.start = start;
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
    public static synchronized String encoderCheck(File work) {
        if (checked != null) return checked;
        methods = new ArrayList<Method>();
        if (Build.VERSION.SDK_INT >= 28) {
            for (HeifEncoder.Way w : HeifEncoder.ways()) methods.add(new Method(w.key, w.label(), "heic", w, Q_LOW, Q_HIGH, (Q_LOW + Q_HIGH) / 2));
            methods.add(new Method("webp", "WebP (Android's encoder)", "webp", null, WEBP_LOW, WEBP_HIGH, WEBP_START));
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
        // the copy as galleries read it when zooming: an aligned region across a tile edge and a partial tile
        // decides; an unaligned one is only reported (decoders move those to their own alignment)
        BitmapRegionDecoder dec = null; Bitmap full = null;
        try {
            dec = newRegionDecoder(f.getPath());
            full = BitmapFactory.decodeFile(f.getPath(), argb());
            String al = regionAgrees(dec, full, new Rect(400, 144, 1100, 700)), odd = regionAgrees(dec, full, new Rect(401, 151, 1001, 651));
            m.regionOk = al == null;
            m.result += al == null ? "; zoom (region decoder) reads it right" : "; Android's region decoder reads it wrongly (" + al + ")";
            if (odd != null) m.result += " (at an unaligned spot " + odd + ")";
        } catch (Throwable e) {
            m.result += "; no region decoding (" + e.getClass().getSimpleName() + ")";
        } finally {
            if (dec != null) dec.recycle();
            if (full != null) full.recycle();
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
    public static Score score(Bitmap a, Bitmap b) {
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) return Score.failed();
        int w = a.getWidth(), h = a.getHeight();
        double[] ya = Guard.luma(a), yb = Guard.luma(b);
        double mse = 0;
        for (int i = 0; i < ya.length; i++) { double d = ya[i] - yb[i]; mse += d * d; }
        mse /= ya.length;
        Score s = new Score();
        s.psnr = mse == 0 ? 99 : 10 * Math.log10(255.0 * 255.0 / mse);
        s.ssim = Ssim.ssim(ya, yb, w, h);
        s.shift = colorShift(a, b);
        s.chromaPsnr = chromaPsnr(a, b);
        s.pass = s.psnr >= Guard.PSNR_MIN && s.ssim >= Guard.SSIM_MIN && s.shift <= MAX_COLOR_SHIFT && s.chromaPsnr >= CHROMA_PSNR_MIN;
        return s;
    }

    /** The lower PSNR of the two colour channels Cb and Cr (BT.601), in dB: how much the colour itself changed. */
    private static double chromaPsnr(Bitmap a, Bitmap b) {
        int w = a.getWidth(), h = a.getHeight();
        int[] pa = new int[w * h], pb = new int[w * h];
        a.getPixels(pa, 0, w, 0, 0, w, h);
        b.getPixels(pb, 0, w, 0, 0, w, h);
        double eb = 0, er = 0;
        for (int i = 0; i < pa.length; i++) {
            double dr = ((pa[i] >> 16) & 255) - ((pb[i] >> 16) & 255), dg = ((pa[i] >> 8) & 255) - ((pb[i] >> 8) & 255), db = (pa[i] & 255) - (pb[i] & 255);
            double cb = -0.168736 * dr - 0.331264 * dg + 0.5 * db, cr = 0.5 * dr - 0.418688 * dg - 0.081312 * db;
            eb += cb * cb; er += cr * cr;
        }
        double mse = Math.max(eb, er) / pa.length;
        return mse == 0 ? 99 : 10 * Math.log10(255.0 * 255.0 / mse);
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
        byte[] exif = Exif.fromJpeg(head(src, 256 * 1024));
        int orientation = exif != null ? Exif.orientation(exif) : 1;
        Bitmap bm = upright(decode(src, 1), orientation), back = null;
        exif = uprightExif(exif, orientation, bm);
        o.before = src.length();
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            if (Build.VERSION.SDK_INT >= 30) bm.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, bos);
            else bm.compress(Bitmap.CompressFormat.WEBP, 100, bos);    // quality 100 is lossless before Android 11
            byte[] d = bos.toByteArray();
            if (exif != null) d = WebpMeta.withExif(d, exif, bm.getWidth(), bm.getHeight());
            boolean small = d.length <= src.length() * (1 - MIN_SAVING);
            if (!small && !keep) { o.fail("would save less than " + Math.round(MIN_SAVING * 100) + "%"); return; }
            back = BitmapFactory.decodeByteArray(d, 0, d.length, argb());
            ColorSpace want = bm.getColorSpace();
            if (back != null && want != null && !want.isSrgb() && !want.equals(back.getColorSpace())) {
                String why = "it would not keep the picture's " + want.getName() + " colours";
                if (!keep) { o.fail(why); return; }
                o.wouldSkip = why;
            }
            o.identicalPixels = back != null && back.sameAs(bm);
            o.trace.add(String.format(Locale.US, "%d x %d px, orientation %d, EXIF %s, lossless WebP %s, pixel-identical: %s", bm.getWidth(), bm.getHeight(),
                    orientation, exif != null ? "copied" : "none", size(d.length), o.identicalPixels ? "yes" : "NO"));
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
    public static Bitmap decodeUpright(File f, int maxSide) throws IOException {
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
