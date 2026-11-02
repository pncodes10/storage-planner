package com.storageplanner.core;

import java.util.Arrays;

/**
 * Just enough EXIF handling to carry a photo's metadata (date taken, camera, location) into a
 * re-encoded copy: find the EXIF block of a JPEG, read its orientation, and set the orientation.
 * A block is "Exif\0\0" followed by the TIFF structure, which is the form HEIF files store.
 */
public final class Exif {
    private Exif() {}

    private static final int TAG_ORIENTATION = 0x0112, TAG_WIDTH = 0x0100, TAG_LENGTH = 0x0101, TAG_EXIF_IFD = 0x8769,
            TAG_PIXEL_X = 0xA002, TAG_PIXEL_Y = 0xA003, TYPE_SHORT = 3, TYPE_LONG = 4;
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
     * A small valid block with the picture size (placeholders: set them with {@link #withSize}), a camera maker
     * name and orientation 1 (upright), used to check whether a phone stores EXIF in a copy correctly: the maker
     * name should be readable from the written file, and the size tags must not confuse its decoders.
     */
    public static byte[] minimal(String make) {
        byte[] m = make.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        int dataAt = 8 + 2 + 4 * 12 + 4;                     // header, entry count, four entries, next-IFD offset
        byte[] e = new byte[TIFF + dataAt + m.length + 1];
        System.arraycopy(new byte[]{'E', 'x', 'i', 'f', 0, 0, 'I', 'I', 42, 0, 8, 0, 0, 0, 4, 0}, 0, e, 0, 16);
        int p = TIFF + 10;                                   // entries in tag order
        le16(e, p, TAG_WIDTH); le16(e, p + 2, TYPE_LONG); le32(e, p + 4, 1); le32(e, p + 8, 1);           // ImageWidth
        p += 12;
        le16(e, p, TAG_LENGTH); le16(e, p + 2, TYPE_LONG); le32(e, p + 4, 1); le32(e, p + 8, 1);          // ImageLength
        p += 12;
        le16(e, p, 0x010F); le16(e, p + 2, 2); le32(e, p + 4, m.length + 1); le32(e, p + 8, dataAt);     // Make (ASCII)
        p += 12;
        le16(e, p, TAG_ORIENTATION); le16(e, p + 2, TYPE_SHORT); le32(e, p + 4, 1); le16(e, p + 8, 1);   // Orientation = 1
        System.arraycopy(m, 0, e, TIFF + dataAt, m.length);   // next-IFD offset and the final NUL are already 0
        return e;
    }

    /**
     * A copy with the picture size tags set to {@code w} x {@code h} where the block has them: ImageWidth /
     * ImageLength (IFD0) and PixelXDimension / PixelYDimension (Exif IFD). Cameras write the size before rotation
     * (a portrait phone photo says 4000 x 1848), so a copy whose pixels were turned upright must say the new size,
     * or decoders that trust these tags read it wrongly.
     */
    public static byte[] withSize(byte[] exif, int w, int h) {
        byte[] out = exif.clone();
        int ifd0 = ifd0(out);
        if (ifd0 < 0) return out;
        boolean le = littleEndian(out);
        setNumber(out, entry(out, ifd0, TAG_WIDTH, le), w, le);
        setNumber(out, entry(out, ifd0, TAG_LENGTH, le), h, le);
        int sub = exifIfd(out, ifd0, le);
        if (sub >= 0) {
            setNumber(out, entry(out, sub, TAG_PIXEL_X, le), w, le);
            setNumber(out, entry(out, sub, TAG_PIXEL_Y, le), h, le);
        }
        return out;
    }

    /** A SHORT or LONG tag of IFD0 ({@code exifIfd} false) or the Exif IFD, or -1 if missing. */
    public static long number(byte[] exif, boolean exifIfd, int tag) {
        int ifd0 = ifd0(exif);
        if (ifd0 < 0) return -1;
        boolean le = littleEndian(exif);
        int ifd = exifIfd ? exifIfd(exif, ifd0, le) : ifd0;
        if (ifd < 0) return -1;
        int p = entry(exif, ifd, tag, le);
        if (p < 0 || u32(exif, p + 4, le) != 1) return -1;
        int type = u16(exif, p + 2, le);
        return type == TYPE_SHORT ? u16(exif, p + 8, le) : type == TYPE_LONG ? u32(exif, p + 8, le) : -1;
    }

    /** Start of IFD0, or -1 if this is not a readable block. */
    private static int ifd0(byte[] e) {
        if (e == null || e.length < TIFF + 8 || e[0] != 'E' || e[1] != 'x' || e[2] != 'i' || e[3] != 'f') return -1;
        if (!(e[TIFF] == 'I' && e[TIFF + 1] == 'I') && !(e[TIFF] == 'M' && e[TIFF + 1] == 'M')) return -1;
        long ifd = u32(e, TIFF + 4, littleEndian(e));
        return ifd < 8 || TIFF + ifd + 2 > e.length ? -1 : (int) (TIFF + ifd);
    }

    private static int exifIfd(byte[] e, int ifd0, boolean le) {
        int p = entry(e, ifd0, TAG_EXIF_IFD, le);
        if (p < 0) return -1;
        long off = u32(e, p + 8, le);
        return off < 8 || TIFF + off + 2 > e.length ? -1 : (int) (TIFF + off);
    }

    /** Offset of a tag's 12-byte entry in the IFD starting at {@code ifd}, or -1. */
    private static int entry(byte[] e, int ifd, int tag, boolean le) {
        int n = u16(e, ifd, le), p = ifd + 2;
        for (int i = 0; i < n; i++, p += 12) {
            if (p + 12 > e.length) return -1;
            if (u16(e, p, le) == tag) return p;
        }
        return -1;
    }

    /** Sets a single SHORT or LONG value in place (a SHORT that cannot hold it is left alone). */
    private static void setNumber(byte[] e, int p, long v, boolean le) {
        if (p < 0 || u32(e, p + 4, le) != 1) return;
        int type = u16(e, p + 2, le);
        if (type == TYPE_SHORT && v <= 0xFFFF) {
            if (le) { e[p + 8] = (byte) v; e[p + 9] = (byte) (v >> 8); } else { e[p + 8] = (byte) (v >> 8); e[p + 9] = (byte) v; }
        } else if (type == TYPE_LONG) {
            for (int i = 0; i < 4; i++) e[p + 8 + (le ? i : 3 - i)] = (byte) (v >> (8 * i));
        }
    }

    private static void le16(byte[] b, int p, int v) { b[p] = (byte) v; b[p + 1] = (byte) (v >> 8); }
    private static void le32(byte[] b, int p, long v) { for (int i = 0; i < 4; i++) b[p + i] = (byte) (v >> (8 * i)); }

    /** Offset of the orientation value in IFD0, or -1. */
    private static int orientationValueAt(byte[] e) {
        int ifd0 = ifd0(e);
        if (ifd0 < 0) return -1;
        boolean le = littleEndian(e);
        int p = entry(e, ifd0, TAG_ORIENTATION, le);
        if (p < 0 || u16(e, p + 2, le) != TYPE_SHORT || u32(e, p + 4, le) != 1) return -1;
        return p + 8;                                         // a single SHORT sits in the value field
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
