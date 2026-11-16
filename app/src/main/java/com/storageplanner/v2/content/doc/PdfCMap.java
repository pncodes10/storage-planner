package com.storageplanner.v2.content.doc;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * ToUnicode CMap subset (PHASE_6 7.2 step 6): codespace length, {@code bfchar}, {@code bfrange}. All CMaps of one PDF are merged
 * into one map, first mapping wins, differing second mappings are counted as conflicts. Bounded: at most {@link #MAX_ENTRIES}
 * entries, ranges of at most 65,536 codes.
 */
final class PdfCMap {
    static final int MAX_ENTRIES = 200000;
    static final int MAX_RANGE = 65536;
    /** Upper bound on mapping operations per merged map (hostile range abuse); work beyond it is ignored. */
    static final int MAX_WORK = 1000000;
    private int work;

    /** Key = (code byte length << 16) | code. */
    final Map<Integer, String> map = new HashMap<Integer, String>();
    /** Longest source code seen in a codespace range or mapping (0 = none). */
    int maxCodeBytes;
    boolean hasOneByte, hasTwoByte;
    int entries, conflicts;

    boolean isEmpty() { return map.isEmpty(); }

    /** true when the merged map is too inconsistent to decode hex strings with (conflicts above 10 % of the entries). */
    boolean unreliable() { return entries > 0 && conflicts * 10L > entries; }

    /** Decodes one 1- or 2-byte code; null when it is not mapped. */
    String get(int code, int bytes) { return map.get((bytes << 16) | code); }

    /** Parses one decoded CMap stream into this map. Never throws. */
    void parse(byte[] d) {
        int[] p = {0};
        String[] tok = new String[1];
        int mode = 0;                                   // 0 none, 1 codespace, 2 bfchar, 3 bfrange
        int guard = 0;
        while (p[0] < d.length && guard++ < 2000000) {
            int kind = next(d, p, tok);
            if (kind == 0) break;
            if (kind == 3) {                            // keyword
                String k = tok[0];
                if (k.equals("begincodespacerange")) mode = 1;
                else if (k.equals("beginbfchar")) mode = 2;
                else if (k.equals("beginbfrange")) mode = 3;
                else if (k.startsWith("end")) mode = 0;
                continue;
            }
            if (kind != 1 || mode == 0) continue;       // only hex strings start an entry
            String a = tok[0];
            if (mode == 1) {
                String b = nextHex(d, p, tok);
                if (b == null) continue;
                note(a.length() / 2);
            } else if (mode == 2) {
                String b = nextHex(d, p, tok);
                if (b == null) continue;
                put(code(a), a.length() / 2, utf16(b));
            } else {
                String hi = nextHex(d, p, tok);
                if (hi == null) continue;
                int save = p[0];
                int kk = next(d, p, tok);
                int lo = code(a), top = code(hi), len = a.length() / 2;
                if (kk == 1) {
                    String dst = utf16(tok[0]);
                    if (lo < 0 || top < lo || dst == null || dst.isEmpty()) continue;
                    int n = Math.min(top - lo, MAX_RANGE - 1);
                    for (int i = 0; i <= n; i++) {
                        if (work >= MAX_WORK) break;
                        char last = (char) (dst.charAt(dst.length() - 1) + i);
                        put(lo + i, len, dst.substring(0, dst.length() - 1) + last);
                    }
                } else if (kk == 2) {                   // '[' array of destinations
                    int i = 0;
                    while (p[0] < d.length) {
                        int k2 = next(d, p, tok);
                        if (k2 != 1) break;             // ']' or anything else ends the array
                        if (i < MAX_RANGE && lo >= 0) put(lo + i, len, utf16(tok[0]));
                        i++;
                    }
                } else { p[0] = save; }
            }
        }
    }

    private void note(int len) {
        if (len < 1 || len > 2) return;
        if (len > maxCodeBytes) maxCodeBytes = len;
        if (len == 1) hasOneByte = true; else hasTwoByte = true;
    }

    private void put(int code, int len, String dst) {
        if (++work > MAX_WORK) return;
        if (code < 0 || len < 1 || len > 2 || dst == null) return;
        note(len);
        int key = (len << 16) | code;
        String old = map.get(key);
        if (old != null) {
            entries++;
            if (!old.equals(dst)) conflicts++;
            return;
        }
        if (map.size() >= MAX_ENTRIES) return;
        map.put(key, dst);
        entries++;
    }

    /** Source code of a hex string of at most 2 bytes, else -1. */
    private static int code(String hex) {
        if (hex.length() == 0 || hex.length() > 4) return -1;
        try { return Integer.parseInt(hex, 16); } catch (NumberFormatException e) { return -1; }
    }

    /** UTF-16BE text of a hex string (odd trailing byte ignored; a single byte is a Latin-1 code); null when invalid. */
    private static String utf16(String hex) {
        int n = hex.length() / 2;
        if (n == 0) return null;
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) {
            int h = Character.digit(hex.charAt(2 * i), 16), l = Character.digit(hex.charAt(2 * i + 1), 16);
            if (h < 0 || l < 0) return null;
            b[i] = (byte) (h * 16 + l);
        }
        if (n == 1) return String.valueOf((char) (b[0] & 0xFF));
        return new String(b, 0, n & ~1, StandardCharsets.UTF_16BE);
    }

    private static String nextHex(byte[] d, int[] p, String[] tok) {
        int save = p[0];
        if (next(d, p, tok) == 1) return tok[0];
        p[0] = save;
        return null;
    }

    /**
     * Next token: 0 = end, 1 = hex string (digits only in tok[0], even length), 2 = '[' (kind 2) or ']' (kind 4), 3 = keyword.
     * Other tokens (names, numbers, dictionaries) are returned as keyword 3 with their text.
     */
    private static int next(byte[] d, int[] p, String[] tok) {
        int i = p[0], n = d.length;
        while (i < n && (d[i] == ' ' || d[i] == '\n' || d[i] == '\r' || d[i] == '\t' || d[i] == 0 || d[i] == '\f')) i++;
        if (i >= n) { p[0] = n; return 0; }
        int c = d[i];
        if (c == '%') { while (i < n && d[i] != '\n' && d[i] != '\r') i++; p[0] = i; return next(d, p, tok); }
        if (c == '<' && i + 1 < n && d[i + 1] != '<') {
            StringBuilder sb = new StringBuilder();
            i++;
            while (i < n && d[i] != '>') {
                int h = Character.digit((char) (d[i] & 0xFF), 16);
                if (h >= 0) sb.append((char) (d[i] & 0xFF));
                i++;
            }
            if (i < n) i++;
            if ((sb.length() & 1) == 1) sb.append('0');
            tok[0] = sb.toString(); p[0] = i;
            return 1;
        }
        if (c == '[') { p[0] = i + 1; tok[0] = "["; return 2; }
        if (c == ']') { p[0] = i + 1; tok[0] = "]"; return 4; }
        int s = i;
        if (c == '<' || c == '>') { i += 2; }
        else {
            i++;
            while (i < n && !(d[i] == ' ' || d[i] == '\n' || d[i] == '\r' || d[i] == '\t' || d[i] == 0 || d[i] == '\f'
                    || d[i] == '<' || d[i] == '>' || d[i] == '[' || d[i] == ']' || d[i] == '/' || d[i] == '(' || d[i] == '%')) i++;
        }
        if (i > n) i = n;
        tok[0] = new String(d, s, Math.min(i, n) - s, StandardCharsets.ISO_8859_1);
        p[0] = i;
        return 3;
    }
}
