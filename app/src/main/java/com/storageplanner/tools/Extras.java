package com.storageplanner.tools;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Finds what a JPEG holds besides its picture and EXIF, which a compressed copy would not keep. Plain Java, so it
 * can be tested on a desktop. A copy carries over the pixels and the EXIF block only; anything else is lost once
 * the original is removed:
 * <ul>
 * <li>Motion Photos (Samsung, Google): the short video stored after the JPEG's end-of-image marker;</li>
 * <li>Ultra HDR gain maps and depth maps: a second JPEG after the first, listed in an MPF (APP2) segment or in XMP;</li>
 * <li>XMP (APP1): edit history, ratings, panorama data. Plain XMP is lost silently (see README.md).</li>
 * </ul>
 * {@link #lostOnCopy} names the first of these that makes a copy unacceptable, or returns null.
 */
public final class Extras {
    private Extras() {}

    /** Data after the picture smaller than this is metadata a copy may drop (e.g. Samsung's Image_UTC_Data). */
    static final int SMALL_TRAILER = 64 * 1024;
    private static final byte[] XMP = "http://ns.adobe.com/xap/1.0/\0".getBytes(StandardCharsets.US_ASCII);
    private static final String[] XMP_EXTRAS = {"MotionPhoto", "MicroVideo", "hdrgm:", "GainMap", "Container:Directory", "GDepth:"};

    /** What {@link #check} found. */
    public static final class Result {
        /** Why a copy would lose part of the photo, or null. */
        public String lost;
        /** Bytes up to the picture's end-of-image marker (the picture itself), or -1 if not found. */
        public long pictureEnd = -1;
    }

    /** {@link #lostOnCopy(InputStream)} on a file held in memory (tests). */
    public static String lostOnCopy(byte[] jpeg) {
        try { return lostOnCopy(new ByteArrayInputStream(jpeg)); }
        catch (IOException e) { throw new IllegalStateException(e); }   // cannot happen in memory
    }

    /**
     * Reads the file once, keeping at most one segment (under 64 KB) and {@link #SMALL_TRAILER} bytes of what
     * follows the picture in memory, so any file size can be checked.
     * @return why a copy would lose part of this photo, or null if it holds only the picture, EXIF and small
     *         metadata. Not a JPEG, or damaged before the picture's data: null (the decoder decides).
     */
    public static String lostOnCopy(InputStream raw) throws IOException { return check(raw).lost; }

    /** Like {@link #lostOnCopy(InputStream)}, also saying where the picture ends (to measure savings against it). */
    public static Result check(InputStream raw) throws IOException {
        Result r = new Result();
        r.lost = scan(raw instanceof BufferedInputStream ? raw : new BufferedInputStream(raw, 64 * 1024), r);
        return r;
    }

    private static String scan(InputStream in, Result r) throws IOException {
        long pos = 2;
        if (in.read() != 0xFF || in.read() != 0xD8) return null;
        while (true) {                                                  // segments up to the start of scan
            if (in.read() != 0xFF) return null;
            int m = in.read();
            pos += 2;
            while (m == 0xFF) { m = in.read(); pos++; }                 // fill bytes
            if (m < 0 || m == 0xD9) return null;
            if (m == 0x01 || (m >= 0xD0 && m <= 0xD7)) continue;        // markers without a length
            int hi = in.read(), lo = in.read();
            if (lo < 0) return null;
            int len = (hi << 8) | lo;
            if (len < 2) return null;
            byte[] seg = readFully(in, len - 2);
            if (seg == null) return null;
            pos += len;
            if (m == 0xE1 && seg.length >= XMP.length && startsWith(seg, 0, XMP)) {
                String xmp = new String(seg, XMP.length, seg.length - XMP.length, StandardCharsets.UTF_8);
                for (String k : XMP_EXTRAS)
                    if (xmp.contains(k)) return "it is a Motion Photo or holds a gain map or depth map (XMP " + k.replace(":", "") + "), which a copy would lose";
            }
            if (m == 0xE2 && seg.length >= 4 && seg[0] == 'M' && seg[1] == 'P' && seg[2] == 'F' && seg[3] == 0)
                return "it holds more than one picture (MPF: an Ultra HDR gain map or a depth map), which a copy would lose";
            if (m == 0xDA) break;
        }
        // In the compressed data every 0xFF is followed by 0x00 or a restart marker, so the first FF D9 ends the picture.
        for (int prev = -1, b; ; prev = b) {
            b = in.read();
            if (b < 0) return null;                                     // cut off: the decoder decides
            pos++;
            if (prev == 0xFF && b == 0xD9) break;
        }
        r.pictureEnd = pos;
        byte[] kept = new byte[SMALL_TRAILER];
        long n = 0, end = 0;                                            // end: just after the last non-zero byte
        for (int b; (b = in.read()) >= 0; n++) {
            if (n < kept.length) kept[(int) n] = (byte) b;
            if (b != 0) end = n + 1;                                    // trailing zeros are padding
        }
        if (end == 0) return null;
        if (end >= SMALL_TRAILER)
            return "it holds " + (end / 1024) + " KB of data after the picture (a Motion Photo video, a depth map or a second picture), which a copy would lose";
        int t = (int) end;
        if (contains(kept, 0, t, "MotionPhoto"))                        // Samsung: MotionPhoto_Data in the SEF trailer
            return "it is a Motion Photo (a video is stored after the picture), which a copy would lose";
        for (int i = 0; i + 2 < t; i++)
            if ((kept[i] & 0xFF) == 0xFF && (kept[i + 1] & 0xFF) == 0xD8 && (kept[i + 2] & 0xFF) == 0xFF)
                return "it holds a second picture after the first (a gain map, depth map or preview), which a copy would lose";
        return null;
    }

    /** Exactly {@code n} bytes, or null at the end of the file. */
    private static byte[] readFully(InputStream in, int n) throws IOException {
        byte[] b = new byte[n];
        for (int got = 0, r; got < n; got += r) if ((r = in.read(b, got, n - got)) < 0) return null;
        return b;
    }

    private static boolean startsWith(byte[] d, int p, byte[] prefix) {
        if (p + prefix.length > d.length) return false;
        for (int i = 0; i < prefix.length; i++) if (d[p + i] != prefix[i]) return false;
        return true;
    }

    private static boolean contains(byte[] d, int from, int to, String s) {
        byte[] k = s.getBytes(StandardCharsets.US_ASCII);
        outer:
        for (int i = from; i + k.length <= to; i++) {
            for (int j = 0; j < k.length; j++) if (d[i + j] != k[j]) continue outer;
            return true;
        }
        return false;
    }
}
