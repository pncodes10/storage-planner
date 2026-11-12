package com.storageplanner.v2.store.disk;

/**
 * Store keys as bytes (PHASE_S_STORE 3.5, REVIEW_S F-03). Encoding is UTF-8, extended to unpaired surrogates (each
 * encoded as its own 3-byte sequence, "WTF-8"), so every Java String round-trips and distinct Strings never share
 * bytes. For valid Strings the bytes equal {@code String.getBytes(UTF_8)}.
 * <p>
 * {@link #compare} orders encoded keys exactly as {@code String.compareTo} orders the Strings (UTF-16 code units),
 * not as raw UTF-8 bytes (code points): they differ when a supplementary character meets one in U+E000–U+FFFF.
 */
public final class KeyCmp {
    private KeyCmp() {}

    public static byte[] encode(String s) {
        int n = s.length();
        int len = 0;
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (c < 0x80) len++;
            else if (c < 0x800) len += 2;
            else if (Character.isHighSurrogate(c) && i + 1 < n && Character.isLowSurrogate(s.charAt(i + 1))) { len += 4; i++; }
            else len += 3;
        }
        byte[] b = new byte[len];
        int p = 0;
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (c < 0x80) b[p++] = (byte) c;
            else if (c < 0x800) { b[p++] = (byte) (0xC0 | (c >> 6)); b[p++] = (byte) (0x80 | (c & 0x3F)); }
            else if (Character.isHighSurrogate(c) && i + 1 < n && Character.isLowSurrogate(s.charAt(i + 1))) {
                int cp = Character.toCodePoint(c, s.charAt(++i));
                b[p++] = (byte) (0xF0 | (cp >> 18)); b[p++] = (byte) (0x80 | ((cp >> 12) & 0x3F));
                b[p++] = (byte) (0x80 | ((cp >> 6) & 0x3F)); b[p++] = (byte) (0x80 | (cp & 0x3F));
            } else { b[p++] = (byte) (0xE0 | (c >> 12)); b[p++] = (byte) (0x80 | ((c >> 6) & 0x3F)); b[p++] = (byte) (0x80 | (c & 0x3F)); }
        }
        return b;
    }

    public static String decode(byte[] b) { return decode(b, 0, b.length); }

    /** Decodes bytes written by {@link #encode}; malformed input throws IllegalArgumentException. */
    public static String decode(byte[] b, int off, int len) {
        char[] out = new char[len];
        int n = 0, p = off, end = off + len;
        while (p < end) {
            int x = b[p] & 0xFF;
            if (x < 0x80) { out[n++] = (char) x; p++; continue; }
            if (x >= 0xC2 && x < 0xE0 && p + 1 < end) { out[n++] = (char) (((x & 0x1F) << 6) | cont(b[p + 1])); p += 2; }
            else if (x >= 0xE0 && x < 0xF0 && p + 2 < end) {
                int c = ((x & 0x0F) << 12) | (cont(b[p + 1]) << 6) | cont(b[p + 2]);
                if (c < 0x800) throw new IllegalArgumentException("overlong key byte sequence");
                out[n++] = (char) c; p += 3;
            } else if (x >= 0xF0 && x < 0xF5 && p + 3 < end) {
                int cp = ((x & 0x07) << 18) | (cont(b[p + 1]) << 12) | (cont(b[p + 2]) << 6) | cont(b[p + 3]);
                if (cp < 0x10000 || cp > 0x10FFFF) throw new IllegalArgumentException("bad key code point");
                out[n++] = Character.highSurrogate(cp); out[n++] = Character.lowSurrogate(cp); p += 4;
            } else throw new IllegalArgumentException("malformed key bytes");
        }
        return new String(out, 0, n);
    }

    private static int cont(byte v) {
        if ((v & 0xC0) != 0x80) throw new IllegalArgumentException("malformed key continuation byte");
        return v & 0x3F;
    }

    public static int compare(byte[] a, byte[] b) { return compare(a, 0, a.length, b, 0, b.length); }

    /** String.compareTo order of the decoded keys. ASCII fast path; the rest is decoded from the first differing char. */
    public static int compare(byte[] a, int ao, int al, byte[] b, int bo, int bl) {
        int n = Math.min(al, bl);
        int i = 0;
        while (i < n && a[ao + i] == b[bo + i]) i++;
        if (i == n) return al - bl;     // one is a prefix of the other (char boundaries coincide: both are whole keys)
        int x = a[ao + i] & 0xFF, y = b[bo + i] & 0xFF;
        if (x < 0x80 && y < 0x80) return x - y;
        if (x < 0x80 && y >= 0xC0) return -1;          // an ASCII unit is below every unit of a multi-byte char
        if (y < 0x80 && x >= 0xC0) return 1;
        int s = i;                                     // back up to the start of the char that differs
        while (s > 0 && (a[ao + s] & 0xC0) == 0x80) s--;
        return decode(a, ao + s, al - s).compareTo(decode(b, bo + s, bl - s));
    }

    // ------------------------------------------------------------------ packed form (in-memory indexes only)

    private static boolean hex32(String s, int from) {
        if (s.length() - from != 32) return false;
        for (int i = from; i < s.length(); i++) { char c = s.charAt(i); if ((c < '0' || c > '9') && (c < 'a' || c > 'f')) return false; }
        return true;
    }

    /**
     * The index form of a key: {@link #encode}, except that 32 lower-case hex characters forming the whole key or
     * following a final NUL are stored as the byte 0xFF (never present in UTF-8) and 16 raw bytes (PHASE_S_STORE 3.5,
     * "packed hex keys"). Order: {@link #comparePacked}, again String order. Frames always carry {@link #encode}.
     */
    public static byte[] pack(String s) {
        int n = s.length(), cut = -1;
        if (n == 32 && hex32(s, 0)) cut = 0;
        else if (n > 32 && s.charAt(n - 33) == '\u0000' && hex32(s, n - 32)) cut = n - 32;
        if (cut < 0) return encode(s);
        byte[] head = encode(s.substring(0, cut));
        byte[] r = new byte[head.length + 17];
        System.arraycopy(head, 0, r, 0, head.length);
        r[head.length] = (byte) 0xFF;
        for (int i = 0; i < 16; i++) r[head.length + 1 + i] = (byte) ((nib(s.charAt(cut + 2 * i)) << 4) | nib(s.charAt(cut + 2 * i + 1)));
        return r;
    }

    private static int nib(char c) { return c <= '9' ? c - '0' : c - 'a' + 10; }
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    /** Offset (relative to off) of the 0xFF marker of a packed key, or -1. */
    private static int packedAt(byte[] b, int off, int len) { return len >= 17 && b[off + len - 17] == (byte) 0xFF ? len - 17 : -1; }

    public static String unpack(byte[] b, int off, int len) { return tail(b, off, len, 0); }

    /** The key from relative position from (a char start, at or before the marker) to its end, as a String. */
    private static String tail(byte[] b, int off, int len, int from) {
        int p = packedAt(b, off, len);
        if (p < 0) return decode(b, off + from, len - from);
        char[] h = new char[32];
        for (int i = 0; i < 16; i++) { int v = b[off + p + 1 + i] & 0xFF; h[2 * i] = HEX[v >> 4]; h[2 * i + 1] = HEX[v & 15]; }
        return decode(b, off + from, p - from) + new String(h);
    }

    /** String.compareTo order of two keys in {@link #pack} form. */
    public static int comparePacked(byte[] a, int ao, int al, byte[] b, int bo, int bl) {
        int n = Math.min(al, bl);
        int i = 0;
        while (i < n && a[ao + i] == b[bo + i]) i++;
        if (i == n) return al - bl;
        int pa = packedAt(a, ao, al), pb = packedAt(b, bo, bl);
        if (pa >= 0 && pa == pb && i > pa) return (a[ao + i] & 0xFF) - (b[bo + i] & 0xFF);   // inside both packed hex runs
        boolean packedHit = (pa >= 0 && i >= pa) || (pb >= 0 && i >= pb);
        if (!packedHit) {
            int x = a[ao + i] & 0xFF, y = b[bo + i] & 0xFF;
            if (x < 0x80 && y < 0x80) return x - y;
            if (x < 0x80 && y >= 0xC0 && y != 0xFF) return -1;
            if (y < 0x80 && x >= 0xC0 && x != 0xFF) return 1;
        }
        int s = i;
        if (pa >= 0 && pa < s) s = pa;
        if (pb >= 0 && pb < s) s = pb;
        while (s > 0 && (a[ao + s] & 0xC0) == 0x80) s--;
        return tail(a, ao, al, s).compareTo(tail(b, bo, bl, s));
    }
}
