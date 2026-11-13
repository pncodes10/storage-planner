package com.storageplanner.v2.util;

import java.util.Arrays;
import java.util.TreeMap;

/**
 * Append-only list of file ids (PHASE_S_STORE section 4 item 1). A 32-character lower-case hex id (every id
 * {@code FileIds.of} makes) is kept as two {@code long}s (16 B per entry); any other id is kept as its String in a side map.
 * {@code get(i)} returns a String equal to the one appended.
 */
public final class FileIdList {
    private long[] hi = new long[16], lo = new long[16];
    private TreeMap<Integer, String> other;
    private int size;

    public int size() { return size; }

    public void add(String id) {
        if (size == hi.length) { int n = size + (size >> 1) + 16; hi = Arrays.copyOf(hi, n); lo = Arrays.copyOf(lo, n); }
        if (isHex32(id)) { hi[size] = parse(id, 0); lo[size] = parse(id, 16); }
        else { if (other == null) other = new TreeMap<Integer, String>(); other.put(size, id); }
        size++;
    }

    public String get(int i) {
        if (i < 0 || i >= size) throw new IndexOutOfBoundsException(i + " of " + size);
        if (other != null) { String s = other.get(i); if (s != null) return s; }
        char[] c = new char[32];
        put(c, 0, hi[i]); put(c, 16, lo[i]);
        return new String(c);
    }

    /** Approximate heap bytes of the arrays (tests and profiles). */
    public long bytes() { return 16L * hi.length + 32 + (other == null ? 0 : other.size() * 120L); }

    static boolean isHex32(String s) {
        if (s == null || s.length() != 32) return false;
        for (int i = 0; i < 32; i++) { char ch = s.charAt(i); if (!(ch >= '0' && ch <= '9' || ch >= 'a' && ch <= 'f')) return false; }
        return true;
    }

    private static long parse(String s, int off) {
        long v = 0;
        for (int i = off; i < off + 16; i++) { char ch = s.charAt(i); v = (v << 4) | (ch <= '9' ? ch - '0' : ch - 'a' + 10); }
        return v;
    }

    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private static void put(char[] c, int off, long v) { for (int i = 15; i >= 0; i--) { c[off + i] = HEX[(int) (v & 15)]; v >>>= 4; } }
}
