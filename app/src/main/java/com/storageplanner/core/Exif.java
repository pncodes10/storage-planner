package com.storageplanner.core;

import java.util.Arrays;

/**
 * Just enough EXIF handling to carry a photo's metadata (date taken, camera, location) into a
 * re-encoded copy: find the EXIF block of a JPEG, read its orientation, and set the orientation.
 * A block is "Exif\0\0" followed by the TIFF structure, which is the form HEIF files store.
 */
public final class Exif {
    private Exif() {}

    private static final int TAG_ORIENTATION = 0x0112, TYPE_SHORT = 3;
    private static final int TIFF = 6;              // the TIFF header follows "Exif\0\0"

    /** The EXIF block of the first EXIF APP1 segment, or null. {@code d} only needs the start of the file. */
    public static byte[] fromJpeg(byte[] d) {
        if (d == null || d.length < 4 || (d[0] & 0xFF) != 0xFF || (d[1] & 0xFF) != 0xD8) return null;
        int p = 2;
        while (p + 4 <= d.length) {
            if ((d[p] & 0xFF) != 0xFF) return null;
            int m = d[p + 1] & 0xFF;
            if (m == 0xFF) { p++; continue; }                       // fill byte
            if (m == 0xD9 || m == 0xDA) return null;                 // end of image / start of scan: no EXIF
            if (m == 0x01 || (m >= 0xD0 && m <= 0xD7)) { p += 2; continue; }   // markers without a length
            int len = ((d[p + 2] & 0xFF) << 8) | (d[p + 3] & 0xFF);
            if (len < 2 || p + 2 + len > d.length) return null;
            if (m == 0xE1 && len >= 8 && d[p + 4] == 'E' && d[p + 5] == 'x' && d[p + 6] == 'i' && d[p + 7] == 'f'
                    && d[p + 8] == 0 && d[p + 9] == 0)
                return Arrays.copyOfRange(d, p + 4, p + 2 + len);
            p += 2 + len;
        }
        return null;
    }

    /** EXIF orientation 1..8 (1 = upright, also when missing or unreadable). */
    public static int orientation(byte[] exif) {
        int at = orientationValueAt(exif);
        if (at < 0) return 1;
        int v = u16(exif, at, littleEndian(exif));
        return v >= 1 && v <= 8 ? v : 1;
    }

    /** A copy of the block with the orientation set to {@code value}; unchanged copy if it has no orientation tag. */
    public static byte[] withOrientation(byte[] exif, int value) {
        byte[] out = exif.clone();
        int at = orientationValueAt(out);
        if (at < 0) return out;
        if (littleEndian(out)) { out[at] = (byte) value; out[at + 1] = (byte) (value >> 8); }
        else { out[at] = (byte) (value >> 8); out[at + 1] = (byte) value; }
        return out;
    }

    /**
     * A small valid block with a camera maker name and orientation 1 (upright), used to check whether a phone
     * stores EXIF in HEIC files correctly: the maker name should be readable from the written file.
     */
    public static byte[] minimal(String make) {
        byte[] m = make.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        int dataAt = 8 + 2 + 2 * 12 + 4;                     // header, entry count, two entries, next-IFD offset
        byte[] e = new byte[TIFF + dataAt + m.length + 1];
        System.arraycopy(new byte[]{'E', 'x', 'i', 'f', 0, 0, 'I', 'I', 42, 0, 8, 0, 0, 0, 2, 0}, 0, e, 0, 16);
        int p = TIFF + 10;
        le16(e, p, 0x010F); le16(e, p + 2, 2); le32(e, p + 4, m.length + 1); le32(e, p + 8, dataAt);     // Make (ASCII)
        p += 12;
        le16(e, p, TAG_ORIENTATION); le16(e, p + 2, TYPE_SHORT); le32(e, p + 4, 1); le16(e, p + 8, 1);   // Orientation = 1
        System.arraycopy(m, 0, e, TIFF + dataAt, m.length);   // next-IFD offset and the final NUL are already 0
        return e;
    }

    private static void le16(byte[] b, int p, int v) { b[p] = (byte) v; b[p + 1] = (byte) (v >> 8); }
    private static void le32(byte[] b, int p, long v) { for (int i = 0; i < 4; i++) b[p + i] = (byte) (v >> (8 * i)); }

    /** Offset of the orientation value in IFD0, or -1. */
    private static int orientationValueAt(byte[] e) {
        if (e == null || e.length < TIFF + 8 || e[0] != 'E' || e[1] != 'x' || e[2] != 'i' || e[3] != 'f') return -1;
        boolean le;
        if (e[TIFF] == 'I' && e[TIFF + 1] == 'I') le = true;
        else if (e[TIFF] == 'M' && e[TIFF + 1] == 'M') le = false;
        else return -1;
        long ifd = u32(e, TIFF + 4, le);
        if (ifd < 8 || TIFF + ifd + 2 > e.length) return -1;
        int p = (int) (TIFF + ifd);
        int n = u16(e, p, le);
        p += 2;
        for (int i = 0; i < n; i++, p += 12) {
            if (p + 12 > e.length) return -1;
            if (u16(e, p, le) == TAG_ORIENTATION) {
                if (u16(e, p + 2, le) != TYPE_SHORT || u32(e, p + 4, le) != 1) return -1;
                return p + 8;                                         // a single SHORT sits in the value field
            }
        }
        return -1;
    }

    private static boolean littleEndian(byte[] e) { return e[TIFF] == 'I'; }

    private static int u16(byte[] b, int p, boolean le) {
        int a = b[p] & 0xFF, c = b[p + 1] & 0xFF;
        return le ? a | (c << 8) : (a << 8) | c;
    }

    private static long u32(byte[] b, int p, boolean le) {
        long v = 0;
        for (int i = 0; i < 4; i++) v |= (long) (b[p + (le ? i : 3 - i)] & 0xFF) << (8 * i);
        return v;
    }
}
