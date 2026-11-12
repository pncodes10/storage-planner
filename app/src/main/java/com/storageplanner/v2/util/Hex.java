package com.storageplanner.v2.util;

public final class Hex {
    private static final char[] D = "0123456789abcdef".toCharArray();
    private Hex() {}
    public static String of(byte[] b) { return of(b, 0, b.length); }
    public static String of(byte[] b, int off, int len) {
        char[] c = new char[len * 2];
        for (int i = 0; i < len; i++) { int v = b[off + i] & 0xff; c[2 * i] = D[v >>> 4]; c[2 * i + 1] = D[v & 15]; }
        return new String(c);
    }
    public static String of8(long v) { String s = Long.toHexString(v & 0xffffffffL); while (s.length() < 8) s = "0" + s; return s; }
    public static String of16(long v) { String s = Long.toHexString(v); while (s.length() < 16) s = "0" + s; return s; }
    public static long parse16(String s) { return new java.math.BigInteger(s, 16).longValue(); }
}
