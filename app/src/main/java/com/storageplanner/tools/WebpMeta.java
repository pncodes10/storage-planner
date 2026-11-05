package com.storageplanner.tools;

import java.io.ByteArrayOutputStream;

/**
 * Adds an EXIF chunk to a WebP file. Android's WebP encoder writes pixels only, so without this a
 * photo saved as WebP would lose its date taken, camera and location. The file becomes the extended
 * format: a VP8X chunk (canvas size, "has EXIF" flag), the original chunks, then the EXIF chunk.
 * Spec: https://developers.google.com/speed/webp/docs/riff_container
 */
public final class WebpMeta {
    private WebpMeta() {}

    private static final int FLAG_EXIF = 0x08;

    /**
     * @param webp a complete WebP file
     * @param exif an "Exif\0\0" block (as {@link Exif#fromJpeg}); the chunk stores the TIFF part after it
     * @param width canvas width in pixels, {@code height} likewise
     * @return a new file with the EXIF chunk (an existing EXIF chunk is replaced)
     */
    public static byte[] withExif(byte[] webp, byte[] exif, int width, int height) {
        if (webp.length < 12 || !tag(webp, 0, "RIFF") || !tag(webp, 8, "WEBP")) throw new IllegalArgumentException("not a WebP file");
        int start = exif.length >= 6 && exif[0] == 'E' && exif[1] == 'x' && exif[2] == 'i' && exif[3] == 'f' && exif[4] == 0 && exif[5] == 0 ? 6 : 0;
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        byte[] vp8x = null;
        int p = 12;
        while (p + 8 <= webp.length) {
            String id = new String(webp, p, 4, java.nio.charset.StandardCharsets.US_ASCII);
            int size = le32(webp, p + 4);
            int total = 8 + size + (size & 1);
            if (size < 0 || p + 8 + size > webp.length) throw new IllegalArgumentException("damaged WebP chunk " + id);
            if (id.equals("VP8X")) vp8x = java.util.Arrays.copyOfRange(webp, p + 8, p + 8 + size);
            else if (!id.equals("EXIF")) {
                int have = Math.min(total, webp.length - p);
                body.write(webp, p, have);
                if (have < total) body.write(0);                    // a last odd-sized chunk without its pad byte
            }
            p += total;
        }
        if (vp8x == null) {
            vp8x = new byte[10];
            put24(vp8x, 4, width - 1);
            put24(vp8x, 7, height - 1);
        }
        vp8x[0] |= FLAG_EXIF;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write('R'); out.write('I'); out.write('F'); out.write('F');
        writeLe32(out, 0);                                       // filled in below
        out.write('W'); out.write('E'); out.write('B'); out.write('P');
        chunk(out, "VP8X", vp8x, 0, vp8x.length);
        byte[] b = body.toByteArray();
        out.write(b, 0, b.length);
        chunk(out, "EXIF", exif, start, exif.length - start);
        byte[] r = out.toByteArray();
        int riff = r.length - 8;
        r[4] = (byte) riff; r[5] = (byte) (riff >> 8); r[6] = (byte) (riff >> 16); r[7] = (byte) (riff >> 24);
        return r;
    }

    private static void chunk(ByteArrayOutputStream o, String id, byte[] d, int off, int len) {
        for (int i = 0; i < 4; i++) o.write(id.charAt(i));
        writeLe32(o, len);
        o.write(d, off, len);
        if ((len & 1) == 1) o.write(0);                          // chunks are padded to an even size
    }

    private static boolean tag(byte[] b, int p, String t) {
        for (int i = 0; i < 4; i++) if (b[p + i] != t.charAt(i)) return false;
        return true;
    }

    static int le32(byte[] b, int p) { return (b[p] & 0xFF) | (b[p + 1] & 0xFF) << 8 | (b[p + 2] & 0xFF) << 16 | (b[p + 3] & 0xFF) << 24; }
    private static void writeLe32(ByteArrayOutputStream o, int v) { o.write(v); o.write(v >> 8); o.write(v >> 16); o.write(v >> 24); }
    private static void put24(byte[] b, int p, int v) { b[p] = (byte) v; b[p + 1] = (byte) (v >> 8); b[p + 2] = (byte) (v >> 16); }
}
