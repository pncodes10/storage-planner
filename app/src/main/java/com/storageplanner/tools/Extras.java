package com.storageplanner.tools;

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

    /**
     * @param jpeg the whole file
     * @return why a copy would lose part of this photo, or null if it holds only the picture, EXIF and small
     *         metadata. Not a JPEG: null (nothing is checked).
     */
    public static String lostOnCopy(byte[] jpeg) {
        if (jpeg == null || jpeg.length < 4 || (jpeg[0] & 0xFF) != 0xFF || (jpeg[1] & 0xFF) != 0xD8) return null;
        int p = 2, scan = -1;
        while (p + 4 <= jpeg.length) {
            if ((jpeg[p] & 0xFF) != 0xFF) return null;                     // damaged: let the decoder decide
            int m = jpeg[p + 1] & 0xFF;
            if (m == 0xFF) { p++; continue; }
            if (m == 0xD9) return null;
            if (m == 0x01 || (m >= 0xD0 && m <= 0xD7)) { p += 2; continue; }
            int len = ((jpeg[p + 2] & 0xFF) << 8) | (jpeg[p + 3] & 0xFF);
            if (len < 2 || p + 2 + len > jpeg.length) return null;
            if (m == 0xE1 && startsWith(jpeg, p + 4, XMP)) {
                String xmp = new String(jpeg, p + 4 + XMP.length, len - 2 - XMP.length, StandardCharsets.UTF_8);
                for (String k : XMP_EXTRAS)
                    if (xmp.contains(k)) return "it is a Motion Photo or holds a gain map or depth map (XMP " + k.replace(":", "") + "), which a copy would lose";
            }
            if (m == 0xE2 && len >= 6 && jpeg[p + 4] == 'M' && jpeg[p + 5] == 'P' && jpeg[p + 6] == 'F' && jpeg[p + 7] == 0)
                return "it holds more than one picture (MPF: an Ultra HDR gain map or a depth map), which a copy would lose";
            p += 2 + len;
            if (m == 0xDA) { scan = p; break; }
        }
        if (scan < 0) return null;
        // In the compressed data every 0xFF is followed by 0x00 or a restart marker, so the first FF D9 ends the picture.
        int eoi = -1;
        for (int i = scan; i + 1 < jpeg.length; i++)
            if ((jpeg[i] & 0xFF) == 0xFF && (jpeg[i + 1] & 0xFF) == 0xD9) { eoi = i + 2; break; }
        if (eoi < 0) return null;
        int end = jpeg.length;
        while (end > eoi && jpeg[end - 1] == 0) end--;                    // zero padding
        int trailer = end - eoi;
        if (trailer == 0) return null;
        if (contains(jpeg, eoi, end, "MotionPhoto"))      // Samsung: MotionPhoto_Data in the SEF trailer
            return "it is a Motion Photo (a video is stored after the picture), which a copy would lose";
        for (int i = eoi; i + 2 < end; i++)
            if ((jpeg[i] & 0xFF) == 0xFF && (jpeg[i + 1] & 0xFF) == 0xD8 && (jpeg[i + 2] & 0xFF) == 0xFF)
                return "it holds a second picture after the first (a gain map, depth map or preview), which a copy would lose";
        if (trailer >= SMALL_TRAILER)
            return "it holds " + (trailer / 1024) + " KB of data after the picture (often a Motion Photo video), which a copy would lose";
        return null;
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
