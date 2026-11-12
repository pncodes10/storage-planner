package com.storageplanner.v2.features;

import com.storageplanner.v2.model.ExifInfo;
import com.storageplanner.v2.model.ImageInfo;
import com.storageplanner.v2.model.Tri;
import com.storageplanner.v2.util.Bytes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** JpegProbe, PngProbe, GifProbe, WebpProbe, BmpProbe and the TIFF probe (PHASE_2 3.2). GIF frame counting is deferred (A11). */
public final class ImageProbes {
    private ImageProbes() {}

    static final int[] STD_LUMA = {16, 11, 10, 16, 24, 40, 51, 61, 12, 12, 14, 19, 26, 58, 60, 55, 14, 13, 16, 24, 40, 57, 69, 56, 14, 17, 22, 29, 51, 87, 80, 62,
        18, 22, 37, 56, 68, 109, 103, 77, 24, 35, 55, 64, 81, 104, 113, 92, 49, 64, 78, 87, 103, 121, 120, 101, 72, 92, 95, 98, 112, 100, 103, 99};
    static final int STD_SUM; static { int s = 0; for (int v : STD_LUMA) s += v; STD_SUM = s; }

    // ------------------------------------------------------------------------------------------------ JPEG

    public static void jpeg(ProbeCtx c) throws IOException {
        ImageInfo img = new ImageInfo(); img.format = "jpeg"; img.motionPhoto = Tri.FALSE;
        c.fr.image = img;
        long p = 2, read = 0;
        boolean sof = false, xmpSeen = false;
        for (int k = 0; k < c.lim.maxMarkers && p + 4 <= c.size && read <= c.lim.jpegMarkerBytes; k++) {
            byte[] h = c.read(p, 4);
            if (h.length < 4) break;
            if ((h[0] & 0xff) != 0xFF) { c.problem("jpeg:BAD_MARKER"); break; }
            int m = h[1] & 0xff;
            if (m == 0xFF) { p++; continue; }
            if (m == 0xD8 || (m >= 0xD0 && m <= 0xD7) || m == 0x01) { p += 2; continue; }
            if (m == 0xD9) break;
            int len = Bytes.u16be(h, 2);
            if (len < 2) { c.problem("jpeg:BAD_LENGTH"); break; }
            if (m == 0xDA) { if (!sof) c.problem("jpeg:NO_SOF"); break; }
            boolean isSof = m >= 0xC0 && m <= 0xCF && m != 0xC4 && m != 0xC8 && m != 0xCC;
            if (isSof || m == 0xDB || m == 0xE1) {
                byte[] s = c.read(p + 4, len - 2);
                read += s.length;
                if (isSof && s.length >= 6) {
                    sof = true;
                    img.bitDepth = s[0] & 0xff; img.height = Bytes.u16be(s, 1); img.width = Bytes.u16be(s, 3);
                    img.progressive = Tri.of(m == 0xC2 || m == 0xC6 || m == 0xCA || m == 0xCE);
                } else if (m == 0xDB && img.jpegQuality < 0) {
                    img.jpegQuality = quality(s);
                } else if (m == 0xE1) {
                    if (Bytes.at(s, s.length, 0, "Exif\u0000\u0000")) {
                        ExifInfo e = ExifReader.parse(s, 0, s.length, c);
                        if (e != null && c.fr.exif == null) c.fr.exif = e;
                    } else if (Bytes.at(s, s.length, 0, "http://ns.adobe.com/xap/1.0/")) {
                        xmpSeen = true;
                        xmp(new String(s, StandardCharsets.ISO_8859_1), img, c.size);
                    }
                }
            }
            p += 2 + len;
        }
        if (!sof && !c.fr.problems.contains("jpeg:NO_SOF")) c.problem("jpeg:NO_SOF");
        if (c.fr.exif != null && c.fr.exif.orientation > 0) img.orientation = c.fr.exif.orientation;
        if (img.motionPhoto == Tri.TRUE) {
            byte[] tl = c.tail();
            if (Bytes.indexOf(tl, 0, tl.length, "ftyp") < 0 && img.motionVideoBytes > c.lim.tailBytes) { /* video beyond the tail: trust XMP */ }
        } else if (!xmpSeen) {
            byte[] tl = c.tail();   // Samsung motion photos without XMP flag: trailing MP4 after EOI
            int f = Bytes.indexOf(tl, 0, tl.length, "ftyp");
            if (f >= 4) { int e = Bytes.lastIndexOf(tl, 0, f, "ÿÙ"); if (e >= 0) { img.motionPhoto = Tri.TRUE; img.motionVideoBytes = tl.length - (f - 4); } }
        }
        img.derive();
    }

    static void xmp(String x, ImageInfo img, long size) {
        if (x.contains("MotionPhoto=\"1\"") || x.contains("MicroVideo=\"1\"") || x.contains("<GCamera:MotionPhoto>1<")) img.motionPhoto = Tri.TRUE;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("MicroVideoOffset=\"(\\d+)\"").matcher(x);
        if (m.find()) img.motionVideoBytes = Long.parseLong(m.group(1));
        m = java.util.regex.Pattern.compile("Item:Semantic=\"MotionPhoto\"[^>]*?Item:Length=\"(\\d+)\"|Item:Length=\"(\\d+)\"[^>]*?Item:Semantic=\"MotionPhoto\"").matcher(x);
        if (m.find()) img.motionVideoBytes = Long.parseLong(m.group(1) != null ? m.group(1) : m.group(2));
        if (img.motionVideoBytes > size) img.motionVideoBytes = -1;
    }

    /** IJG quality estimate from the first 8-bit luminance table of a DQT segment. */
    static int quality(byte[] s) {
        if (s.length < 65) return -1;
        int pq = (s[0] >> 4) & 15, tq = s[0] & 15;
        if (pq != 0 || tq != 0) return -1;
        long sum = 0;
        for (int i = 1; i <= 64; i++) sum += s[i] & 0xff;
        double scale = sum * 100.0 / STD_SUM;
        if (scale <= 0) return 100;
        int q = scale <= 100 ? (int) Math.round((200 - scale) / 2) : (int) Math.round(5000 / scale);
        return Math.max(1, Math.min(100, q));
    }

    // ------------------------------------------------------------------------------------------------ PNG

    public static void png(ProbeCtx c) throws IOException {
        ImageInfo img = new ImageInfo(); img.format = "png"; c.fr.image = img;
        if (c.headLen < 33 || !Bytes.at(c.head, c.headLen, 12, "IHDR")) { c.problem("png:NO_IHDR"); return; }
        img.width = (int) Math.min(Integer.MAX_VALUE, Bytes.u32be(c.head, 16)); img.height = (int) Math.min(Integer.MAX_VALUE, Bytes.u32be(c.head, 20));
        img.bitDepth = c.head[24] & 0xff;
        int ct = c.head[25] & 0xff;
        img.hasAlpha = Tri.of(ct == 4 || ct == 6);
        img.progressive = Tri.of((c.head[28] & 0xff) == 1);
        img.animated = Tri.FALSE; img.frames = 1;
        long p = 8;
        for (int k = 0; k < c.lim.maxChunks && p + 12 <= c.size && p < c.lim.imageProbeBytes; k++) {
            byte[] h = c.read(p, 8);
            if (h.length < 8) break;
            long len = Bytes.u32be(h, 0);
            String type = Bytes.ascii(h, 4, 4);
            if (type.equals("acTL")) {
                byte[] d = c.read(p + 8, 8);
                if (d.length == 8) { img.animated = Tri.TRUE; img.frames = (int) Math.min(1000, Bytes.u32be(d, 0)); }
            } else if (type.equals("tRNS")) img.hasAlpha = Tri.TRUE;
            else if (type.equals("IDAT") || type.equals("IEND")) break;
            p += 12 + len;
        }
        img.derive();
    }

    // ------------------------------------------------------------------------------------------------ GIF

    public static void gif(ProbeCtx c) {
        ImageInfo img = new ImageInfo(); img.format = "gif"; c.fr.image = img;
        if (c.headLen < 13) { c.problem("gif:SHORT_HEADER"); return; }
        img.width = Bytes.u16le(c.head, 6); img.height = Bytes.u16le(c.head, 8); img.bitDepth = (c.head[10] & 7) + 1;
        img.derive();
    }

    // ------------------------------------------------------------------------------------------------ WebP

    public static void webp(ProbeCtx c) throws IOException {
        ImageInfo img = new ImageInfo(); img.format = "webp"; c.fr.image = img;
        img.animated = Tri.FALSE;
        long p = 12, end = Math.min(c.size, 8 + Bytes.u32le(c.head, 4));
        for (int k = 0; k < c.lim.maxChunks && p + 8 <= end && p < c.lim.imageProbeBytes; k++) {
            byte[] h = c.read(p, 8);
            if (h.length < 8) break;
            String type = Bytes.ascii(h, 0, 4);
            long len = Bytes.u32le(h, 4);
            if (type.equals("VP8X")) {
                byte[] d = c.read(p + 8, 10);
                if (d.length == 10) {
                    img.hasAlpha = Tri.of((d[0] & 0x10) != 0); img.animated = Tri.of((d[0] & 0x02) != 0);
                    img.width = 1 + ((d[4] & 0xff) | (d[5] & 0xff) << 8 | (d[6] & 0xff) << 16);
                    img.height = 1 + ((d[7] & 0xff) | (d[8] & 0xff) << 8 | (d[9] & 0xff) << 16);
                }
            } else if (type.equals("VP8 ") && img.width < 0) {
                byte[] d = c.read(p + 8, 10);
                if (d.length == 10 && (d[3] & 0xff) == 0x9D && (d[4] & 0xff) == 0x01 && (d[5] & 0xff) == 0x2A) {
                    img.width = Bytes.u16le(d, 6) & 0x3FFF; img.height = Bytes.u16le(d, 8) & 0x3FFF;
                    if (img.hasAlpha == Tri.UNKNOWN) img.hasAlpha = Tri.FALSE;
                } else c.problem("webp:BAD_VP8");
            } else if (type.equals("VP8L") && img.width < 0) {
                byte[] d = c.read(p + 8, 5);
                if (d.length == 5 && (d[0] & 0xff) == 0x2F) {
                    long bits = Bytes.u32le(d, 1);
                    img.width = (int) (bits & 0x3FFF) + 1; img.height = (int) ((bits >> 14) & 0x3FFF) + 1;
                    img.hasAlpha = Tri.of(((bits >> 28) & 1) != 0);
                } else c.problem("webp:BAD_VP8L");
            } else if (type.equals("ANIM") || type.equals("ANMF")) img.animated = Tri.TRUE;
            else if (type.equals("EXIF") && len <= 1 << 20) {
                byte[] d = c.read(p + 8, (int) len);
                ExifInfo e = ExifReader.parse(d, 0, d.length, c);
                if (e != null) c.fr.exif = e;
            }
            p += 8 + len + (len & 1);
        }
        if (img.width < 0) c.problem("webp:NO_DIMENSIONS");
        if (c.fr.exif != null && c.fr.exif.orientation > 0) img.orientation = c.fr.exif.orientation;
        if (img.animated == Tri.FALSE) img.frames = 1;
        img.derive();
    }

    // ------------------------------------------------------------------------------------------------ BMP, TIFF

    public static void bmp(ProbeCtx c) {
        ImageInfo img = new ImageInfo(); img.format = "bmp"; c.fr.image = img;
        long hs = Bytes.u32le(c.head, 14);
        if (hs == 12) { img.width = Bytes.u16le(c.head, 18); img.height = Bytes.u16le(c.head, 20); img.bitDepth = Bytes.u16le(c.head, 24); }
        else { img.width = Math.abs((int) Bytes.u32le(c.head, 18)); img.height = Math.abs((int) Bytes.u32le(c.head, 22)); img.bitDepth = Bytes.u16le(c.head, 28); }
        img.animated = Tri.FALSE; img.frames = 1;
        img.derive();
    }

    public static void tiff(ProbeCtx c) throws IOException {
        ImageInfo img = new ImageInfo(); img.format = c.type; c.fr.image = img;
        byte[] b = c.headLen >= Math.min(c.size, c.lim.jpegMarkerBytes) ? c.head : c.read(0, (int) Math.min(c.size, c.lim.jpegMarkerBytes));
        ExifInfo e = new ExifInfo();
        if (ExifReader.parseTiff(b, 0, b.length, e, img, c)) c.fr.exif = e; else c.problem("exif:NOT_TIFF");
        if (e.orientation > 0) img.orientation = e.orientation;
        img.derive();
    }
}
