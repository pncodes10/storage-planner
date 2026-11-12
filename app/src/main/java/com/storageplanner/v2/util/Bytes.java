package com.storageplanner.v2.util;

/** Bounds-checked little/big-endian reads; out-of-range reads throw IndexOutOfBoundsException (probes catch RuntimeException). */
public final class Bytes {
    private Bytes() {}
    static void chk(byte[] b, int off, int n, int len) { if (off < 0 || n < 0 || (long) off + n > Math.min(len, b.length)) throw new IndexOutOfBoundsException("off " + off + "+" + n + " > " + len); }
    public static int u8(byte[] b, int o) { chk(b, o, 1, b.length); return b[o] & 0xff; }
    public static int u16le(byte[] b, int o) { chk(b, o, 2, b.length); return (b[o] & 0xff) | (b[o + 1] & 0xff) << 8; }
    public static int u16be(byte[] b, int o) { chk(b, o, 2, b.length); return (b[o] & 0xff) << 8 | (b[o + 1] & 0xff); }
    public static long u32le(byte[] b, int o) { chk(b, o, 4, b.length); return ((long) (b[o] & 0xff)) | (long) (b[o + 1] & 0xff) << 8 | (long) (b[o + 2] & 0xff) << 16 | (long) (b[o + 3] & 0xff) << 24; }
    public static long u32be(byte[] b, int o) { chk(b, o, 4, b.length); return (long) (b[o] & 0xff) << 24 | (long) (b[o + 1] & 0xff) << 16 | (long) (b[o + 2] & 0xff) << 8 | (long) (b[o + 3] & 0xff); }
    public static long u64le(byte[] b, int o) { return u32le(b, o) | u32le(b, o + 4) << 32; }
    public static long u64be(byte[] b, int o) { return u32be(b, o) << 32 | u32be(b, o + 4); }
    public static int u16(byte[] b, int o, boolean le) { return le ? u16le(b, o) : u16be(b, o); }
    public static long u32(byte[] b, int o, boolean le) { return le ? u32le(b, o) : u32be(b, o); }
    /** True if b[o..] starts with the ASCII string s. */
    public static boolean at(byte[] b, int len, int o, String s) {
        if (o < 0 || o + s.length() > Math.min(len, b.length)) return false;
        for (int k = 0; k < s.length(); k++) if ((b[o + k] & 0xff) != s.charAt(k)) return false;
        return true;
    }
    public static boolean at(byte[] b, int len, int o, int... v) {
        if (o < 0 || o + v.length > Math.min(len, b.length)) return false;
        for (int k = 0; k < v.length; k++) if ((b[o + k] & 0xff) != v[k]) return false;
        return true;
    }
    public static String ascii(byte[] b, int o, int n) {
        chk(b, o, n, b.length);
        StringBuilder s = new StringBuilder(n);
        for (int k = 0; k < n; k++) s.append((char) (b[o + k] & 0xff));
        return s.toString();
    }
    /** Index of the ASCII needle in b[from, to), -1 if absent. */
    public static int indexOf(byte[] b, int from, int to, String needle) {
        to = Math.min(to, b.length);
        outer:
        for (int i = Math.max(0, from); i + needle.length() <= to; i++) {
            for (int k = 0; k < needle.length(); k++) if ((b[i + k] & 0xff) != needle.charAt(k)) continue outer;
            return i;
        }
        return -1;
    }
    public static int lastIndexOf(byte[] b, int from, int to, String needle) {
        to = Math.min(to, b.length);
        outer:
        for (int i = to - needle.length(); i >= Math.max(0, from); i--) {
            for (int k = 0; k < needle.length(); k++) if ((b[i + k] & 0xff) != needle.charAt(k)) continue outer;
            return i;
        }
        return -1;
    }
}
