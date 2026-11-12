package com.storageplanner.v2test;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Image fixture builders: FxJpeg (ImageIO + EXIF APP1 from a tiny TIFF writer), FxPng/Gif/Bmp via ImageIO, WebP and HEIF boxes by hand. */
final class FxImg {
    private FxImg() {}

    static BufferedImage pattern(int w, int h, long seed) {
        BufferedImage b = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        java.util.Random r = new java.util.Random(seed);
        int cx = r.nextInt(w), cy = r.nextInt(h);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int v = ((x / 16 + y / 16) & 1) == 0 ? 200 : 40;
            int d = (x - cx) * (x - cx) + (y - cy) * (y - cy);
            if (d < (w * w) / 16) v = 120;
            b.setRGB(x, y, (Math.min(255, v + x % 50) << 16) | (v << 8) | Math.max(0, v - y % 60));
        }
        return b;
    }

    static byte[] jpeg(BufferedImage img, float quality) throws IOException {
        ImageWriter w = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        MemoryCacheImageOutputStream out = new MemoryCacheImageOutputStream(bos);
        w.setOutput(out);
        ImageWriteParam p = w.getDefaultWriteParam();
        p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT); p.setCompressionQuality(quality);
        w.write(null, new IIOImage(img, null, null), p);
        out.close(); w.dispose();
        return stripApp0(bos.toByteArray());
    }

    static byte[] encode(BufferedImage img, String fmt) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(img, fmt, bos);
        return bos.toByteArray();
    }

    static byte[] stripApp0(byte[] j) {
        if ((j[2] & 0xff) == 0xFF && (j[3] & 0xff) == 0xE0) {
            int len = ((j[4] & 0xff) << 8) | (j[5] & 0xff);
            return cat(new byte[]{(byte) 0xFF, (byte) 0xD8}, java.util.Arrays.copyOfRange(j, 4 + len, j.length));
        }
        return j;
    }

    /** Inserts segment(s) right after SOI. */
    static byte[] withSegment(byte[] jpeg, int marker, byte[] payload) {
        int n = payload.length + 2;
        byte[] seg = cat(new byte[]{(byte) 0xFF, (byte) marker, (byte) (n >> 8), (byte) n}, payload);
        return cat(java.util.Arrays.copyOfRange(jpeg, 0, 2), seg, java.util.Arrays.copyOfRange(jpeg, 2, jpeg.length));
    }

    static byte[] cat(byte[]... p) { int n = 0; for (byte[] x : p) n += x.length; byte[] r = new byte[n]; int o = 0; for (byte[] x : p) { System.arraycopy(x, 0, r, o, x.length); o += x.length; } return r; }
    static byte[] a(String s) { return s.getBytes(StandardCharsets.ISO_8859_1); }

    /** Minimal TIFF (FxTiff): IFD0 {Make, Orientation, [ExifIFD → DateTimeOriginal, OffsetTimeOriginal]}, LE or BE. */
    static byte[] tiff(boolean le, String make, int orientation, String dto, String offset, boolean gps) {
        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(1024).order(le ? java.nio.ByteOrder.LITTLE_ENDIAN : java.nio.ByteOrder.BIG_ENDIAN);
        b.put(a(le ? "II" : "MM")).putShort((short) 42).putInt(8);
        int n0 = 2 + (gps ? 1 : 0) + (dto != null ? 1 : 0);
        int ifd0End = 8 + 2 + 12 * n0 + 4;
        int makeOff = ifd0End, exifOff = makeOff + 32;
        b.putShort((short) n0);
        b.putShort((short) 0x010F).putShort((short) 2).putInt(make.length() + 1).putInt(makeOff);
        b.putShort((short) 0x0112).putShort((short) 3).putInt(1).putShort((short) orientation).putShort((short) 0);
        if (dto != null) b.putShort((short) 0x8769).putShort((short) 4).putInt(1).putInt(exifOff);
        if (gps) b.putShort((short) 0x8825).putShort((short) 4).putInt(1).putInt(exifOff + 200);
        b.putInt(0);
        b.position(makeOff); b.put(a(make)).put((byte) 0);
        if (dto != null) {
            b.position(exifOff);
            int n = offset != null ? 2 : 1;
            int strOff = exifOff + 2 + 12 * n + 4;
            b.putShort((short) n);
            b.putShort((short) 0x9003).putShort((short) 2).putInt(20).putInt(strOff);
            if (offset != null) b.putShort((short) 0x9011).putShort((short) 2).putInt(7).putInt(strOff + 20);
            b.putInt(0);
            b.position(strOff); b.put(a(dto)).put((byte) 0);
            if (offset != null) b.put(a(offset)).put((byte) 0);
        }
        if (gps) { b.position(exifOff + 200); b.putShort((short) 0).putInt(0); }
        return java.util.Arrays.copyOf(b.array(), Math.max(b.position(), exifOff + 260));
    }

    static byte[] exifApp1(byte[] tiff) { return cat(a("Exif\0\0"), tiff); }

    // --- ISO-BMFF (FxHeif)
    static byte[] box(String type, byte[]... body) {
        byte[] b = cat(body);
        java.nio.ByteBuffer h = java.nio.ByteBuffer.allocate(8); h.putInt(b.length + 8).put(a(type));
        return cat(h.array(), b);
    }
    static byte[] full(int v, int flags) { return new byte[]{(byte) v, (byte) (flags >> 16), (byte) (flags >> 8), (byte) flags}; }
    static byte[] u16(int v) { return new byte[]{(byte) (v >> 8), (byte) v}; }
    static byte[] u32(long v) { return new byte[]{(byte) (v >> 24), (byte) (v >> 16), (byte) (v >> 8), (byte) v}; }

    /** HEIC with primary item 1 (ispe w×h, irot), item 2 = Exif at the end of the file. */
    static byte[] heic(int w, int h, int irot, byte[] exifTiff) {
        byte[] exifPayload = exifTiff == null ? new byte[0] : cat(u32(0), exifTiff);
        byte[] ftyp = box("ftyp", a("heic"), u32(0), a("mif1heic"));
        for (int pass = 0; pass < 2; pass++) { /* two passes: the iloc offset depends on the meta size */ }
        byte[] meta0 = meta(w, h, irot, 0, exifPayload.length);
        long exifOff = ftyp.length + meta0.length + 8 + 16;
        byte[] meta = meta(w, h, irot, exifOff, exifPayload.length);
        byte[] mdat = box("mdat", new byte[16], exifPayload);
        return cat(ftyp, meta, mdat);
    }

    static byte[] meta(int w, int h, int irot, long exifOff, int exifLen) {
        byte[] hdlr = box("hdlr", full(0, 0), u32(0), a("pict"), new byte[12], new byte[]{0});
        byte[] pitm = box("pitm", full(0, 0), u16(1));
        byte[] infe1 = box("infe", full(2, 0), u16(1), u16(0), a("hvc1"), new byte[]{0});
        byte[] infe2 = box("infe", full(2, 0), u16(2), u16(0), a("Exif"), new byte[]{0});
        byte[] iinf = box("iinf", full(0, 0), u16(exifLen > 0 ? 2 : 1), infe1, exifLen > 0 ? infe2 : new byte[0]);
        // iloc v0: offset_size 4, length_size 4, base_offset_size 0
        byte[] iloc = box("iloc", full(0, 0), new byte[]{0x44, 0x00}, u16(1), u16(2), u16(0), u16(1), u32(exifOff), u32(exifLen));
        byte[] ispe = box("ispe", full(0, 0), u32(w), u32(h));
        byte[] irotB = box("irot", new byte[]{(byte) irot});
        byte[] ipco = box("ipco", ispe, irotB);
        byte[] ipma = box("ipma", full(0, 0), u32(1), u16(1), new byte[]{2, (byte) 0x81, 0x02});
        byte[] iprp = box("iprp", ipco, ipma);
        return box("meta", full(0, 0), hdlr, pitm, iinf, iloc, iprp);
    }

    /** WebP VP8X canvas w×h, optional ANIM chunk. */
    static byte[] webpVp8x(int w, int h, boolean anim) {
        byte[] vp8x = cat(a("VP8X"), new byte[]{10, 0, 0, 0}, new byte[]{(byte) (anim ? 0x02 : 0), 0, 0, 0},
                new byte[]{(byte) (w - 1), (byte) ((w - 1) >> 8), (byte) ((w - 1) >> 16), (byte) (h - 1), (byte) ((h - 1) >> 8), (byte) ((h - 1) >> 16)});
        byte[] extra = anim ? cat(a("ANIM"), new byte[]{6, 0, 0, 0}, new byte[6]) : new byte[0];
        byte[] body = cat(a("WEBP"), vp8x, extra);
        return cat(a("RIFF"), new byte[]{(byte) body.length, (byte) (body.length >> 8), 0, 0}, body);
    }
}
