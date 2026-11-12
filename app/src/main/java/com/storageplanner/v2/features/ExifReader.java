package com.storageplanner.v2.features;

import com.storageplanner.v2.model.ExifInfo;
import com.storageplanner.v2.model.ImageInfo;
import com.storageplanner.v2.model.Tri;
import com.storageplanner.v2.model.Unknown;
import com.storageplanner.v2.util.Bytes;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/** TIFF/EXIF IFD reader, bounded (PHASE_2 3.2, 8): entry counts ≤ 1000, offsets checked, IFD loops stop. */
public final class ExifReader {
    private ExifReader() {}
    static final int MAX_ENTRIES = 1000;

    /** EXIF from a JPEG APP1 / WebP EXIF chunk / HEIF Exif payload starting with optional "Exif\0\0". null if not TIFF. */
    public static ExifInfo parse(byte[] b, int off, int len, ProbeCtx c) {
        if (len >= 6 && Bytes.at(b, off + len, off, "Exif\u0000\u0000")) { off += 6; len -= 6; }
        ExifInfo e = new ExifInfo();
        return parseTiff(b, off, len, e, null, c) ? e : null;
    }

    /** Parses a TIFF structure at b[off, off+len). img (may be null) receives width/height (tiff/dng). Returns false if not TIFF. */
    public static boolean parseTiff(byte[] b, int off, int len, ExifInfo e, ImageInfo img, ProbeCtx c) {
        int end = Math.min(b.length, off + len);
        if (end - off < 8) return false;
        boolean le;
        if (b[off] == 'I' && b[off + 1] == 'I') le = true; else if (b[off] == 'M' && b[off + 1] == 'M') le = false; else return false;
        if (Bytes.u16(b, off + 2, le) != 42) return false;
        long ifd0 = Bytes.u32(b, off + 4, le);
        Set<Long> seen = new HashSet<Long>();
        e.hasGps = Tri.FALSE; e.hasThumbnail = Tri.FALSE; e.tzKnown = Tri.FALSE;
        long[] ptrs = new long[2];   // exif, gps
        long next = ifd(b, off, end, ifd0, le, e, img, ptrs, seen, c, 0);
        if (ptrs[0] > 0) ifd(b, off, end, ptrs[0], le, e, img, new long[2], seen, c, 1);
        if (ptrs[1] > 0) e.hasGps = Tri.TRUE;
        if (next > 0 && next < end - off && !seen.contains(next)) e.hasThumbnail = Tri.TRUE;
        if (Unknown.knownTime(e.dateTimeOriginal) && e.offsetTimeOriginal != null) {
            int m = Dates.offsetMinutes(e.offsetTimeOriginal);
            if (m != Integer.MIN_VALUE) { e.dateTimeOriginal -= m * 60000L; e.tzKnown = Tri.TRUE; }
        }
        return true;
    }

    /** Walks one IFD; returns the next-IFD offset (0 if none or invalid). */
    static long ifd(byte[] b, int base, int end, long at, boolean le, ExifInfo e, ImageInfo img, long[] ptrs, Set<Long> seen, ProbeCtx c, int kind) {
        if (at < 8 || base + at + 2 > end || !seen.add(at)) { if (at != 0 && c != null) c.problem("exif:BAD_IFD_OFFSET"); return 0; }
        int p = (int) (base + at);
        int n = Bytes.u16(b, p, le);
        if (n > MAX_ENTRIES) { if (c != null) c.problem("exif:TOO_MANY_ENTRIES"); n = 0; }
        p += 2;
        for (int i = 0; i < n; i++, p += 12) {
            if (p + 12 > end) { if (c != null) c.problem("exif:IFD_PAST_END"); return 0; }
            int tag = Bytes.u16(b, p, le), type = Bytes.u16(b, p + 2, le);
            long count = Bytes.u32(b, p + 4, le);
            int unit = type == 3 ? 2 : type == 4 || type == 9 ? 4 : type == 5 || type == 10 ? 8 : 1;
            long bytes = count * unit;
            long vo = bytes <= 4 ? p + 8 - base : Bytes.u32(b, p + 8, le);
            if (bytes > 1 << 20 || base + vo + bytes > end || vo < 0) { if (bytes > 4 && c != null) c.problem("exif:VALUE_PAST_END"); continue; }
            int v = (int) (base + vo);
            switch (tag) {
                case 0x010F: e.make = str(b, v, bytes); break;
                case 0x0110: e.model = str(b, v, bytes); break;
                case 0x0131: e.software = str(b, v, bytes); break;
                case 0x0132: e.dateTime = Dates.exif(str(b, v, bytes)); break;
                case 0x0112: if (kind == 0) e.orientation = num(b, v, type, le); if (e.orientation < 1 || e.orientation > 8) e.orientation = -1; break;
                case 0x8769: ptrs[0] = num(b, v, type, le); break;
                case 0x8825: ptrs[1] = Math.max(1, num(b, v, type, le)); break;
                case 0x9003: e.dateTimeOriginal = Dates.exif(str(b, v, bytes)); break;
                case 0x9011: e.offsetTimeOriginal = str(b, v, bytes); break;
                case 0xA002: e.pixelX = num(b, v, type, le); break;
                case 0xA003: e.pixelY = num(b, v, type, le); break;
                case 0x0100: if (img != null && kind == 0) img.width = num(b, v, type, le); break;
                case 0x0101: if (img != null && kind == 0) img.height = num(b, v, type, le); break;
                case 0x0102: if (img != null && kind == 0) img.bitDepth = num(b, v, type, le); break;
                default: break;
            }
        }
        if (p + 4 > end) return 0;
        return Bytes.u32(b, p, le);
    }

    static int num(byte[] b, int v, int type, boolean le) {
        if (type == 3) return Bytes.u16(b, v, le);
        if (type == 4 || type == 9) { long x = Bytes.u32(b, v, le); return x > Integer.MAX_VALUE ? -1 : (int) x; }
        if (type == 1 || type == 7) return b[v] & 0xff;
        return -1;
    }

    static String str(byte[] b, int v, long n) {
        int e = v;
        while (e < v + n && b[e] != 0) e++;
        String s = new String(b, v, e - v, StandardCharsets.ISO_8859_1).trim();
        if (s.length() > 64) s = s.substring(0, 64);
        return s.isEmpty() ? null : s;
    }

    /** EXIF orientation of a JPEG from its first bytes (decoder use); 1 when none is found. */
    public static int jpegOrientation(byte[] head, int n) {
        try {
            int p = 2;
            for (int k = 0; k < 64 && p + 4 <= n; k++) {
                if ((head[p] & 0xff) != 0xFF) return 1;
                int m = head[p + 1] & 0xff, len = Bytes.u16be(head, p + 2);
                if (m == 0xDA || m == 0xD9) return 1;
                if (m == 0xE1 && p + 4 + len - 2 <= n) {
                    ExifInfo e = parse(head, p + 4, len - 2, null);
                    if (e != null) return e.orientation > 0 ? e.orientation : 1;
                }
                p += 2 + len;
            }
        } catch (RuntimeException ignored) { /* malformed: no orientation */ }
        return 1;
    }
}
