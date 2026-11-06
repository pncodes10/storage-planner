package com.storageplanner.tools;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the parts of an MP4 / MOV file the video tool needs to decide whether a copy would lose anything, and
 * writes the original dates back into a copy (Android's MediaMuxer stamps every file with the time it was written,
 * which would move the video to "today" in the gallery). Plain Java, so it is tested on a desktop.
 *
 * Only the box headers and the small boxes in {@code moov} are read; the media data is skipped.
 */
public final class Mp4 {
    private Mp4() {}

    /** Seconds between 1904-01-01 (MP4 time) and 1970-01-01 (Unix time). */
    public static final long EPOCH_1904 = 2082844800L;
    /** Larger moov boxes are not read into memory (a 3-hour 60 fps video has a moov of about 10 MB). */
    static final long MAX_MOOV = 64L << 20;
    /** Data after the last box beyond this size is a Samsung trailer or other data a copy would lose. */
    public static final long SMALL_TRAILER = 64 * 1024;

    public static final class Track {
        /** Handler type: "vide", "soun", "meta", "text", "sbtl", "hint", ... */
        public String handler = "?";
        /** Creation / modification time in MP4 seconds (since 1904); 0 = not set. */
        public long created, modified;
    }

    public static final class Info {
        /** Top-level box types in file order, e.g. [ftyp, mdat, moov]. */
        public final List<String> top = new ArrayList<String>();
        public final List<Track> tracks = new ArrayList<Track>();
        /** Box types inside moov/udta (Samsung writes smta, ©xyz, ...). */
        public final List<String> udta = new ArrayList<String>();
        /** From mvhd, in MP4 seconds (0 = not set). */
        public long created, modified;
        /** Bytes after the last complete box (ignoring zero padding). */
        public long trailerBytes;
        /** The file ends with Samsung's "SEFT" trailer signature. */
        public boolean samsungTrailer;
        /**
         * Android's capture-speed key (com.android.capture.fps) is in the metadata. Samsung writes it in ordinary
         * videos too (its value is then the playback rate), so the value decides whether it is slow motion.
         */
        public boolean captureFps;
        /** Its value in frames per second (NaN when the key is missing or its value could not be read). */
        public double captureFpsValue = Double.NaN;
        /** Set when the file is not a readable MP4; everything else may be incomplete. */
        public String problem;

        public int count(String handler) {
            int n = 0;
            for (Track t : tracks) if (t.handler.equals(handler)) n++;
            return n;
        }

        /** Unix milliseconds of the creation time, or -1 when the file has none. */
        public long createdMillis() { return created > 0 ? (created - EPOCH_1904) * 1000L : -1; }
    }

    public static Info read(File f) throws IOException {
        Info info = new Info();
        RandomAccessFile in = new RandomAccessFile(f, "r");
        try {
            long len = in.length(), pos = 0, end = 0;
            while (pos + 8 <= len) {
                in.seek(pos);
                long size = in.readInt() & 0xFFFFFFFFL;
                String type = type(in.readInt());
                int header = 8;
                if (size == 1) { if (pos + 16 > len) break; size = in.readLong(); header = 16; }
                else if (size == 0) size = len - pos;
                if (size < header || pos + size > len || !plausible(type)) break;
                info.top.add(type);
                if (type.equals("moov")) {
                    if (size > MAX_MOOV) { info.problem = "its index (moov) is " + (size >> 20) + " MB"; }
                    else {
                        byte[] moov = new byte[(int) (size - header)];
                        in.readFully(moov);
                        moov(moov, info);
                    }
                }
                pos += size;
                end = pos;
            }
            if (!info.top.contains("moov") && info.problem == null) info.problem = "it has no MP4 index (moov)";
            long tail = len - end;
            if (tail > 0) {
                // trailing zeros are padding, not data
                long last = len;
                byte[] buf = new byte[8192];
                while (last > end) {
                    int n = (int) Math.min(buf.length, last - end);
                    in.seek(last - n);
                    in.readFully(buf, 0, n);
                    int i = n - 1;
                    while (i >= 0 && buf[i] == 0) i--;
                    if (i >= 0) { last = last - n + i + 1; break; }
                    last -= n;
                }
                info.trailerBytes = last - end;
                if (len >= 4) {
                    byte[] sig = new byte[4];
                    in.seek(len - 4);
                    in.readFully(sig);
                    info.samsungTrailer = new String(sig, StandardCharsets.ISO_8859_1).equals("SEFT");
                }
            }
        } finally {
            in.close();
        }
        return info;
    }

    private static void moov(byte[] b, Info info) {
        info.captureFps = indexOf(b, "com.android.capture.fps".getBytes(StandardCharsets.ISO_8859_1)) >= 0;
        if (info.captureFps) info.captureFpsValue = metaValue(b, 0, b.length, "com.android.capture.fps", 0);
        for (int p = 0; p + 8 <= b.length; ) {
            int size = box(b, p);
            if (size <= 0) break;
            String t = type(b, p + 4);
            if (t.equals("mvhd")) { long[] tm = times(b, p + 8); info.created = tm[0]; info.modified = tm[1]; }
            else if (t.equals("trak")) info.tracks.add(trak(b, p + 8, p + size));
            else if (t.equals("udta")) for (int q = p + 8; q + 8 <= p + size; ) {
                int s = box(b, q);
                if (s <= 0 || q + s > p + size) break;
                info.udta.add(type(b, q + 4));
                q += s;
            }
            p += size;
        }
    }

    private static Track trak(byte[] b, int from, int to) {
        Track t = new Track();
        for (int p = from; p + 8 <= to; ) {
            int size = box(b, p);
            if (size <= 0 || p + size > to) break;
            String type = type(b, p + 4);
            if (type.equals("tkhd")) { long[] tm = times(b, p + 8); t.created = tm[0]; t.modified = tm[1]; }
            else if (type.equals("mdia")) for (int q = p + 8; q + 8 <= p + size; ) {
                int s = box(b, q);
                if (s <= 0 || q + s > p + size) break;
                // hdlr: version/flags (4), pre_defined (4), handler_type (4)
                if (type(b, q + 4).equals("hdlr") && q + 20 <= b.length) t.handler = type(b, q + 16);
                q += s;
            }
            p += size;
        }
        return t;
    }

    /**
     * The number stored for a key in an Apple-style metadata box (meta: hdlr 'mdta', keys, ilst), searched in moov,
     * its udta and their meta boxes. NaN when not found. Values: float32 (type 23), float64 (24), big-endian
     * integers (21, 22, 65-75) or a number written as text (1).
     */
    static double metaValue(byte[] b, int from, int to, String key, int depth) {
        if (depth > 4) return Double.NaN;
        for (int p = from; p + 8 <= to; ) {
            int size = box(b, p);
            if (size <= 0 || p + size > to) break;
            String t = type(b, p + 4);
            if (t.equals("meta")) {
                int c = p + 8;
                // ISO meta is a full box (4 bytes of version and flags); QuickTime's is not
                if (c + 12 <= p + size && u32(b, c) == 0 && plausible(type(b, c + 8))) c += 4;
                double v = keys(b, c, p + size, key);
                if (!Double.isNaN(v)) return v;
            } else if (t.equals("udta") || t.equals("trak")) {
                double v = metaValue(b, p + 8, p + size, key, depth + 1);
                if (!Double.isNaN(v)) return v;
            }
            p += size;
        }
        return Double.NaN;
    }

    private static double keys(byte[] b, int from, int to, String key) {
        int index = -1;
        for (int p = from; p + 8 <= to; ) {
            int size = box(b, p);
            if (size <= 0 || p + size > to) break;
            String t = type(b, p + 4);
            if (t.equals("keys") && p + 16 <= p + size) {
                long n = u32(b, p + 12);
                int q = p + 16;
                for (int i = 1; i <= n && q + 8 <= p + size; i++) {
                    int ks = (int) u32(b, q);
                    if (ks < 8 || q + ks > p + size) break;
                    if (new String(b, q + 8, ks - 8, StandardCharsets.ISO_8859_1).equals(key)) index = i;
                    q += ks;
                }
            } else if (t.equals("ilst") && index > 0) {
                for (int q = p + 8; q + 8 <= p + size; ) {
                    int is = box(b, q);
                    if (is <= 0 || q + is > p + size) break;
                    if (u32(b, q + 4) == index) {
                        for (int d = q + 8; d + 16 <= q + is; ) {
                            int ds = box(b, d);
                            if (ds <= 0 || d + ds > q + is) break;
                            if (type(b, d + 4).equals("data")) return number(b, d + 16, ds - 16, (int) (u32(b, d + 8) & 0xFFFFFF));
                            d += ds;
                        }
                    }
                    q += is;
                }
            }
            p += size;
        }
        return Double.NaN;
    }

    private static double number(byte[] b, int p, int n, int type) {
        if (n <= 0 || p + n > b.length) return Double.NaN;
        if (type == 23 && n == 4) return Float.intBitsToFloat((int) u32(b, p));
        if (type == 24 && n == 8) return Double.longBitsToDouble(u64(b, p));
        if (type == 21 || type == 22 || (type >= 65 && type <= 75)) {
            if (n > 8) return Double.NaN;
            long v = 0;
            for (int i = 0; i < n; i++) v = (v << 8) | (b[p + i] & 0xFF);
            if (type == 21 && n < 8 && (b[p] & 0x80) != 0) v -= 1L << (8 * n);
            return v;
        }
        if (type == 1) {
            try { return Double.parseDouble(new String(b, p, n, StandardCharsets.UTF_8).trim()); }
            catch (NumberFormatException e) { return Double.NaN; }
        }
        return Double.NaN;
    }

    /** Creation and modification time of a full box (mvhd, tkhd, mdhd) whose body starts at p. */
    private static long[] times(byte[] b, int p) {
        if (p + 20 > b.length) return new long[]{0, 0};
        if (b[p] == 1) return new long[]{u64(b, p + 4), u64(b, p + 12)};
        return new long[]{u32(b, p + 4), u32(b, p + 8)};
    }

    /**
     * Writes the given creation and modification times (MP4 seconds) into mvhd and every track's tkhd and mdhd,
     * in place (the boxes keep their size). Returns how many boxes were changed.
     */
    public static int writeTimes(File f, long created, long modified) throws IOException {
        RandomAccessFile io = new RandomAccessFile(f, "rw");
        try {
            long len = io.length(), pos = 0;
            while (pos + 8 <= len) {
                io.seek(pos);
                long size = io.readInt() & 0xFFFFFFFFL;
                String type = type(io.readInt());
                int header = 8;
                if (size == 1) { size = io.readLong(); header = 16; }
                else if (size == 0) size = len - pos;
                if (size < header || pos + size > len) break;
                if (type.equals("moov")) {
                    if (size > MAX_MOOV) throw new IOException("moov too large");
                    byte[] moov = new byte[(int) (size - header)];
                    io.readFully(moov);
                    int n = patch(moov, 0, moov.length, created, modified);
                    io.seek(pos + header);
                    io.write(moov);
                    return n;
                }
                pos += size;
            }
            throw new IOException("no moov");
        } finally {
            io.close();
        }
    }

    private static int patch(byte[] b, int from, int to, long created, long modified) {
        int n = 0;
        for (int p = from; p + 8 <= to; ) {
            int size = box(b, p);
            if (size <= 0 || p + size > to) break;
            String t = type(b, p + 4);
            if (t.equals("mvhd") || t.equals("tkhd") || t.equals("mdhd")) {
                int q = p + 8;
                if (q + 20 <= p + size) {
                    if (b[q] == 1) { put64(b, q + 4, created); put64(b, q + 12, modified); }
                    else { put32(b, q + 4, created); put32(b, q + 8, modified); }
                    n++;
                }
            } else if (t.equals("trak") || t.equals("mdia")) n += patch(b, p + 8, p + size, created, modified);
            p += size;
        }
        return n;
    }

    // ------------------------------------------------------------------ bytes
    /** Size of the box at p inside a moov buffer (64-bit sizes are not used inside moov by any phone; 0 = stop). */
    private static int box(byte[] b, int p) {
        long s = u32(b, p);
        if (s < 8 || p + s > b.length) return 0;
        return (int) s;
    }

    private static boolean plausible(String t) {
        for (int i = 0; i < 4; i++) { char c = t.charAt(i); if (c < 0x20 || c > 0x7E) if (c != 0xA9) return false; }
        return true;
    }

    private static String type(int v) {
        return new String(new byte[]{(byte) (v >> 24), (byte) (v >> 16), (byte) (v >> 8), (byte) v}, StandardCharsets.ISO_8859_1);
    }

    private static String type(byte[] b, int p) { return p + 4 <= b.length ? new String(b, p, 4, StandardCharsets.ISO_8859_1) : "????"; }

    private static long u32(byte[] b, int p) {
        return ((b[p] & 0xFFL) << 24) | ((b[p + 1] & 0xFFL) << 16) | ((b[p + 2] & 0xFFL) << 8) | (b[p + 3] & 0xFFL);
    }

    private static long u64(byte[] b, int p) { return (u32(b, p) << 32) | u32(b, p + 4); }

    private static void put32(byte[] b, int p, long v) {
        b[p] = (byte) (v >> 24); b[p + 1] = (byte) (v >> 16); b[p + 2] = (byte) (v >> 8); b[p + 3] = (byte) v;
    }

    private static void put64(byte[] b, int p, long v) { put32(b, p, v >>> 32); put32(b, p + 4, v); }

    static int indexOf(byte[] hay, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= hay.length; i++) {
            for (int j = 0; j < needle.length; j++) if (hay[i + j] != needle[j]) continue outer;
            return i;
        }
        return -1;
    }
}
