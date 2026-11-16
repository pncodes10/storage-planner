package com.storageplanner.v2.content.doc;

import com.storageplanner.v2.model.DocStatus;
import com.storageplanner.v2.model.PdfInfo;
import com.storageplanner.v2.model.Tri;
import com.storageplanner.v2.util.BoundedInflater;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Embedded text of a PDF in pure Java (PHASE_6 7.2). No object graph is followed (so circular references cannot loop), every
 * stream is inflated through {@link BoundedInflater} (8 MiB per stream, 64 MiB per file), at most 10,000 streams are looked at and
 * the text is capped at 200,000 chars. Never throws; an encrypted file is {@code ENCRYPTED} (unknown), never "no text".
 * The returned text exists in memory only; the caller derives counts and drops it.
 */
public final class PdfText {
    private PdfText() {}

    public static final int MAX_STREAMS = 10000;
    public static final int MAX_STREAM_OUT = 8 << 20;
    public static final long MAX_TOTAL_OUT = 64L << 20;
    public static final int MAX_CHARS = 200000;
    static final int DICT_BACK = 8192;
    static final int MAX_OPS = 100000;

    public static final class Result {
        public DocStatus status = DocStatus.CORRUPT;
        public final StringBuilder text = new StringBuilder();
        public int pages = -1;
        public int unsupported;
        public String title, author;
    }

    /** One stream found in the file: data window and what its dictionary says. */
    private static final class Strm {
        int start, end;
        boolean skip, objStm, meta;
        List<String> filters = new ArrayList<String>();
        boolean predictor, badFilterSyntax;
    }

    public static Result extract(byte[] b, PdfInfo info) {
        Result r = new Result();
        try {
            run(b, info, r);
        } catch (RuntimeException e) {
            r.status = DocStatus.CORRUPT;
            r.text.setLength(0);
        }
        return r;
    }

    private static void run(byte[] b, PdfInfo info, Result r) {
        // 1. encrypted = unknown. PdfInfo first; our own scan of the raw bytes too (PdfProbe only looks at head/tail of big files).
        if ((info != null && info.encrypted == Tri.TRUE) || hasEncrypt(b)) { r.status = DocStatus.ENCRYPTED; return; }
        boolean header = indexOf(b, "%PDF-".getBytes(StandardCharsets.ISO_8859_1), 0) >= 0 && indexOf(b, "%PDF-".getBytes(StandardCharsets.ISO_8859_1), 0) < 1024;

        // 2-3. streams
        List<Strm> streams = findStreams(b);

        // pass 1: CMaps, object streams (page objects), XMP; total decoded bytes bounded
        PdfCMap cmap = new PdfCMap();
        long total = 0;
        int objStmPages = 0;
        String xmpTitle = null, xmpAuthor = null;
        for (Strm s : streams) {
            if (s.skip && !s.meta) continue;
            if (s.badFilterSyntax || s.predictor) { r.unsupported++; continue; }
            byte[] d = decode(b, s, r);
            if (d == null) continue;
            total += d.length;
            if (total > MAX_TOTAL_OUT) { r.status = DocStatus.LIMIT; r.text.setLength(0); return; }
            if (s.meta) {
                String x = new String(d, StandardCharsets.UTF_8);
                if (xmpTitle == null) xmpTitle = xmp(x, "dc:title");
                if (xmpAuthor == null) xmpAuthor = xmp(x, "dc:creator");
            } else if (s.objStm) {
                objStmPages += countPages(d);
            } else if (contains(d, BEGINCMAP)) {
                cmap.parse(d);
            }
        }
        boolean cmapOk = !cmap.isEmpty() && !cmap.unreliable();
        boolean hexUnreliable = !cmap.isEmpty() && cmap.unreliable();

        // pass 2: content streams
        for (Strm s : streams) {
            if (s.skip || s.objStm || s.badFilterSyntax || s.predictor) continue;
            if (r.text.length() >= MAX_CHARS) break;
            byte[] d = decode(b, s, null);
            if (d == null || contains(d, BEGINCMAP)) continue;
            StringBuilder t = new StringBuilder();
            content(d, t, cmapOk ? cmap : null, hexUnreliable, MAX_CHARS - r.text.length());
            int tl = t.length();
            while (tl > 0 && (t.charAt(tl - 1) == '\n' || t.charAt(tl - 1) == ' ')) tl--;
            t.setLength(tl);
            if (tl == 0) continue;
            int ctl = 0;
            for (int i = 0; i < t.length(); i++) { char c = t.charAt(i); if (c < 0x20 && c != '\n' && c != '\r' && c != '\t' && c != '\f') ctl++; }
            if (ctl * 10L > t.length() * 3L) continue;                               // > 30 % control characters: not text
            if (r.text.length() > 0) r.text.append('\n');                           // streams (pages) are separate lines
            r.text.append(t, 0, Math.min(t.length(), MAX_CHARS - r.text.length()));
        }
        if (hexUnreliable) r.unsupported++;                     // hex strings were not decoded (design 7.2 step 6)

        // 8. metadata
        if (info != null && info.title != null) r.title = info.title; else r.title = xmpTitle;
        if (info != null && info.author != null) r.author = info.author; else r.author = xmpAuthor;
        if (info != null && info.pages >= 0) r.pages = info.pages;
        else {
            int n = countPages(b) + objStmPages;
            r.pages = n > 0 ? n : -1;
        }

        // 10. status
        int chars = r.text.length();
        if (chars >= 20) r.status = DocStatus.OK;
        else if (r.pages != 0 && hasImage(b)) r.status = DocStatus.NO_TEXT_LAYER;
        else if (r.unsupported > 0) r.status = DocStatus.UNSUPPORTED;
        else if (!header || streams.isEmpty()) r.status = DocStatus.CORRUPT;
        else r.status = DocStatus.OK;
    }

    // ---------------------------------------------------------------- streams

    private static final byte[] STREAM = {'s', 't', 'r', 'e', 'a', 'm'};
    private static final byte[] ENDSTREAM = {'e', 'n', 'd', 's', 't', 'r', 'e', 'a', 'm'};
    private static final byte[] BEGINCMAP = {'b', 'e', 'g', 'i', 'n', 'c', 'm', 'a', 'p'};

    static List<Strm> findStreams(byte[] b) {
        List<Strm> out = new ArrayList<Strm>();
        int pos = 0;
        while (out.size() < MAX_STREAMS) {
            int k = indexOf(b, STREAM, pos);
            if (k < 0) break;
            pos = k + 6;
            if (k >= 3 && b[k - 3] == 'e' && b[k - 2] == 'n' && b[k - 1] == 'd') continue;          // "endstream"
            int start;
            if (k + 6 < b.length && b[k + 6] == '\n') start = k + 7;
            else if (k + 7 < b.length && b[k + 6] == '\r' && b[k + 7] == '\n') start = k + 8;
            else continue;
            String dict = dictBefore(b, k);
            if (dict == null) continue;
            Strm s = new Strm();
            s.start = start;
            int len = directLength(dict);
            int end = -1;
            if (len >= 0 && (long) start + len <= b.length && endstreamFollows(b, start + len)) end = start + len;
            if (end < 0) {
                int e = indexOf(b, ENDSTREAM, start);
                if (e < 0) end = b.length;
                else {
                    end = e;
                    if (end > start && b[end - 1] == '\n') end--;
                    if (end > start && b[end - 1] == '\r') end--;
                }
            }
            s.end = end;
            classify(dict, s);
            out.add(s);
            pos = Math.max(pos, end);
        }
        return out;
    }

    private static boolean endstreamFollows(byte[] b, int p) {
        while (p < b.length && p < Integer.MAX_VALUE && (b[p] == '\r' || b[p] == '\n' || b[p] == ' ')) p++;
        return p + 9 <= b.length && matches(b, p, ENDSTREAM);
    }

    /** The dictionary that ends within 3 bytes before the keyword at k (balanced &lt;&lt;..&gt;&gt;, searched back at most 8 KiB). */
    private static String dictBefore(byte[] b, int k) {
        int e = k - 1, skipped = 0;
        while (e >= 0 && (b[e] == ' ' || b[e] == '\r' || b[e] == '\n' || b[e] == '\t') && skipped < 3) { e--; skipped++; }
        if (e < 1 || b[e] != '>' || b[e - 1] != '>') return null;
        int end = e + 1, i = e, depth = 0, lo = Math.max(0, e - DICT_BACK);
        while (i >= lo + 1) {
            if (b[i] == '>' && b[i - 1] == '>') { depth++; i -= 2; }
            else if (b[i] == '<' && b[i - 1] == '<') { depth--; i -= 2; if (depth == 0) return new String(b, i + 1, end - (i + 1), StandardCharsets.ISO_8859_1); }
            else i--;
        }
        return null;
    }

    /** /Length N when N is a direct integer (not "N G R"), else -1. */
    static int directLength(String d) {
        int i = 0;
        while ((i = d.indexOf("/Length", i)) >= 0) {
            int p = i + 7;
            if (p < d.length() && Character.isLetterOrDigit(d.charAt(p))) { i = p; continue; }          // /Length1 ...
            while (p < d.length() && Character.isWhitespace(d.charAt(p))) p++;
            int s = p;
            while (p < d.length() && p - s < 10 && d.charAt(p) >= '0' && d.charAt(p) <= '9') p++;
            if (p == s) return -1;
            int q = p;
            while (q < d.length() && Character.isWhitespace(d.charAt(q))) q++;
            int g = q;
            while (g < d.length() && d.charAt(g) >= '0' && d.charAt(g) <= '9') g++;
            if (g > q) { int h = g; while (h < d.length() && Character.isWhitespace(d.charAt(h))) h++; if (h < d.length() && d.charAt(h) == 'R') return -1; }
            try { return Integer.parseInt(d.substring(s, p)); } catch (NumberFormatException e) { return -1; }
        }
        return -1;
    }

    private static void classify(String d, Strm s) {
        if (hasName(d, "/Subtype", "/Image") || hasName(d, "/Type", "/XRef") || hasName(d, "/Subtype", "/Type1C")
                || d.contains("/FontFile") || d.contains("/Length1")) s.skip = true;
        if (hasName(d, "/Type", "/Metadata")) { s.meta = true; s.skip = true; }
        if (hasName(d, "/Type", "/ObjStm")) { s.objStm = true; }
        int f = d.indexOf("/Filter");
        if (f >= 0) {
            int p = f + 7;
            while (p < d.length() && Character.isWhitespace(d.charAt(p))) p++;
            if (p < d.length() && d.charAt(p) == '/') readName(d, p, s.filters);
            else if (p < d.length() && d.charAt(p) == '[') {
                int e = d.indexOf(']', p);
                if (e < 0) s.badFilterSyntax = true;
                else {
                    int q = p + 1;
                    while (q < e) {
                        if (d.charAt(q) == '/') q = readName(d, q, s.filters); else q++;
                    }
                }
            } else s.badFilterSyntax = true;                                          // indirect /Filter 7 0 R
        }
        int pr = d.indexOf("/Predictor");
        if (pr >= 0) {
            int p = pr + 10;
            while (p < d.length() && Character.isWhitespace(d.charAt(p))) p++;
            int st = p;
            while (p < d.length() && p - st < 6 && Character.isDigit(d.charAt(p))) p++;
            if (p > st && Integer.parseInt(d.substring(st, p)) >= 2) s.predictor = true;
        }
    }

    private static int readName(String d, int p, List<String> out) {
        int q = p + 1;
        while (q < d.length() && !Character.isWhitespace(d.charAt(q)) && "/[]<>()".indexOf(d.charAt(q)) < 0) q++;
        out.add(d.substring(p + 1, q));
        return q;
    }

    /** key followed (after white space) by exactly the name val (a longer name such as /ImageB does not match). */
    private static boolean hasName(String d, String key, String val) {
        int i = 0;
        while ((i = d.indexOf(key, i)) >= 0) {
            int p = i + key.length();
            if (p < d.length() && Character.isLetterOrDigit(d.charAt(p))) { i = p; continue; }
            while (p < d.length() && Character.isWhitespace(d.charAt(p))) p++;
            if (d.startsWith(val, p)) {
                int e = p + val.length();
                if (e >= d.length() || !Character.isLetterOrDigit(d.charAt(e))) return true;
            }
            i = i + key.length();
        }
        return false;
    }

    /** Applies the filters in order; null when a filter is unsupported or fails (counted in r.unsupported when r != null). */
    private static byte[] decode(byte[] b, Strm s, Result r) {
        byte[] d = null;
        int off = s.start, len = s.end - s.start;
        if (len < 0) return null;
        boolean first = true;
        for (String f : s.filters) {
            byte[] in; int io, il;
            if (first) { in = b; io = off; il = len; } else { in = d; io = 0; il = d.length; }
            first = false;
            if (f.equals("FlateDecode") || f.equals("Fl")) {
                d = BoundedInflater.inflateZlib(in, io, il, MAX_STREAM_OUT);
                if (d == null) { if (r != null) r.unsupported++; return null; }
            } else if (f.equals("ASCIIHexDecode") || f.equals("AHx")) d = ascii(in, io, il, false);
            else if (f.equals("ASCII85Decode") || f.equals("A85")) d = ascii(in, io, il, true);
            else { if (r != null) r.unsupported++; return null; }
            if (d == null) { if (r != null) r.unsupported++; return null; }
        }
        if (s.filters.isEmpty()) {
            return java.util.Arrays.copyOfRange(b, off, off + len);
        }
        return d;
    }

    private static byte[] ascii(byte[] in, int off, int len, boolean a85) {
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream(Math.max(16, len));
        int end = off + len;
        if (!a85) {
            int hi = -1;
            for (int i = off; i < end; i++) {
                int c = in[i] & 0xFF;
                if (c == '>') break;
                int v = Character.digit((char) c, 16);
                if (v < 0) continue;
                if (hi < 0) hi = v; else { o.write(hi * 16 + v); hi = -1; }
            }
            if (hi >= 0) o.write(hi * 16);
            return o.toByteArray();
        }
        int i = off;
        while (i + 1 < end && (in[i] == ' ' || in[i] == '\n' || in[i] == '\r')) i++;
        if (i + 1 < end && in[i] == '<' && in[i + 1] == '~') i += 2;
        long[] grp = new long[5]; int n = 0;
        for (; i < end; i++) {
            int c = in[i] & 0xFF;
            if (c == '~') break;
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t' || c == '\f' || c == 0) continue;
            if (o.size() > MAX_STREAM_OUT) return null;
            if (c == 'z' && n == 0) { o.write(0); o.write(0); o.write(0); o.write(0); continue; }
            if (c < '!' || c > 'u') continue;
            grp[n++] = c - '!';
            if (n == 5) {
                long v = ((((grp[0] * 85 + grp[1]) * 85 + grp[2]) * 85 + grp[3]) * 85 + grp[4]);
                o.write((int) (v >> 24) & 0xFF); o.write((int) (v >> 16) & 0xFF); o.write((int) (v >> 8) & 0xFF); o.write((int) v & 0xFF);
                n = 0;
            }
        }
        if (n > 1) {
            for (int j = n; j < 5; j++) grp[j] = 84;
            long v = ((((grp[0] * 85 + grp[1]) * 85 + grp[2]) * 85 + grp[3]) * 85 + grp[4]);
            for (int j = 0; j < n - 1; j++) o.write((int) (v >> (24 - 8 * j)) & 0xFF);
        }
        return o.toByteArray();
    }

    // ---------------------------------------------------------------- content streams

    /** Collects the text of one decoded content stream into t (at most room chars); returns the number of hex strings it ignored. */
    static int content(byte[] d, StringBuilder t, PdfCMap cmap, boolean noHex, int room) {
        int n = d.length, i = 0, ignoredHex = 0;
        List<String> ops = new ArrayList<String>();          // strings since the last operator (a ' ' string = TJ gap)
        boolean inArray = false;
        int guard = 0;
        while (i < n && t.length() < room && guard++ < 20000000) {
            int c = d[i] & 0xFF;
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t' || c == '\f' || c == 0) { i++; continue; }
            if (c == '%') { while (i < n && d[i] != '\n' && d[i] != '\r') i++; continue; }
            if (c == '(') {
                StringBuilder sb = new StringBuilder();
                i = literal(d, i, sb);
                if (ops.size() < MAX_OPS) ops.add(sb.toString());
                continue;
            }
            if (c == '<') {
                if (i + 1 < n && d[i + 1] == '<') { i += 2; continue; }
                int e = i + 1;
                while (e < n && d[e] != '>') e++;
                String h = hex(d, i + 1, e);
                i = Math.min(n, e + 1);
                String s = noHex ? null : hexText(h, cmap);
                if (s == null) ignoredHex++; else if (ops.size() < MAX_OPS) ops.add(s);
                continue;
            }
            if (c == '>') { i++; continue; }
            if (c == '[') { inArray = true; i++; continue; }
            if (c == ']') { inArray = false; i++; continue; }
            if (c == '/') { i++; while (i < n && !delim(d[i])) i++; continue; }
            if (c == '+' || c == '-' || c == '.' || (c >= '0' && c <= '9')) {
                int s = i;
                i++;
                while (i < n && !delim(d[i])) i++;
                if (inArray) {
                    double v = number(d, s, i);
                    if (v < -200 && ops.size() < MAX_OPS) ops.add(" ");
                }
                continue;
            }
            // operator
            int s = i;
            while (i < n && !delim(d[i])) i++;
            if (i == s) { i++; continue; }                    // stray delimiter such as ')' or '}'
            String op = new String(d, s, Math.min(i - s, 8), StandardCharsets.ISO_8859_1);
            if (op.equals("Tj") || op.equals("TJ")) { for (String x : ops) t.append(x); }
            else if (op.equals("'") || op.equals("\"")) { newline(t); for (String x : ops) t.append(x); }
            else if (op.equals("Td") || op.equals("TD") || op.equals("T*") || op.equals("Tm") || op.equals("ET")) newline(t);
            else if (op.equals("ID")) {                       // inline image data: skip to EI
                int e = i;
                while (e + 1 < n && !(d[e] == 'E' && d[e + 1] == 'I' && e > 0 && isWs(d[e - 1]) && (e + 2 >= n || isWs(d[e + 2])))) e++;
                i = Math.min(n, e + 2);
            }
            ops.clear();
            inArray = false;
        }
        if (t.length() > room) t.setLength(room);
        return ignoredHex;
    }

    private static boolean isWs(byte c) { return c == ' ' || c == '\n' || c == '\r' || c == '\t' || c == '\f' || c == 0; }
    private static boolean delim(byte c) { return isWs(c) || c == '(' || c == ')' || c == '<' || c == '>' || c == '[' || c == ']' || c == '{' || c == '}' || c == '/' || c == '%'; }

    private static void newline(StringBuilder t) {
        if (t.length() > 0 && t.charAt(t.length() - 1) != '\n') t.append('\n');
    }

    private static double number(byte[] d, int s, int e) {
        try { return Double.parseDouble(new String(d, s, Math.min(e - s, 24), StandardCharsets.ISO_8859_1)); } catch (NumberFormatException x) { return 0; }
    }

    /** Literal string starting at the '(' at i; returns the index after the closing ')'. */
    private static int literal(byte[] d, int i, StringBuilder sb) {
        int n = d.length, depth = 1;
        i++;
        while (i < n) {
            int c = d[i] & 0xFF;
            if (c == '\\') {
                i++;
                if (i >= n) break;
                int e = d[i] & 0xFF;
                if (e == 'n') { sb.append('\n'); i++; }
                else if (e == 'r') { sb.append('\r'); i++; }
                else if (e == 't') { sb.append('\t'); i++; }
                else if (e == 'b') { sb.append('\b'); i++; }
                else if (e == 'f') { sb.append('\f'); i++; }
                else if (e >= '0' && e <= '7') {
                    int v = 0, k = 0;
                    while (k < 3 && i < n && d[i] >= '0' && d[i] <= '7') { v = v * 8 + (d[i] - '0'); i++; k++; }
                    sb.append(cp1252(v & 0xFF));
                } else if (e == '\r') { i++; if (i < n && d[i] == '\n') i++; }
                else if (e == '\n') i++;
                else { sb.append(cp1252(e)); i++; }
                continue;
            }
            if (c == '(') { depth++; sb.append('('); i++; continue; }
            if (c == ')') { depth--; i++; if (depth == 0) return i; sb.append(')'); continue; }
            sb.append(cp1252(c));
            i++;
        }
        return n;
    }

    private static String hex(byte[] d, int s, int e) {
        StringBuilder sb = new StringBuilder();
        for (int i = s; i < e; i++) if (Character.digit((char) (d[i] & 0xFF), 16) >= 0) sb.append((char) (d[i] & 0xFF));
        if ((sb.length() & 1) == 1) sb.append('0');
        return sb.toString();
    }

    /** Decoded hex string, or null when it cannot be decoded (no CMap for 2-byte codes, or a control byte). */
    private static String hexText(String h, PdfCMap cmap) {
        int n = h.length() / 2;
        if (n == 0) return "";
        int[] by = new int[n];
        for (int i = 0; i < n; i++) by[i] = Integer.parseInt(h.substring(2 * i, 2 * i + 2), 16);
        StringBuilder sb = new StringBuilder();
        if (cmap != null && !cmap.isEmpty()) {
            boolean two = cmap.hasTwoByte && (n % 2 == 0);
            if (two) {
                for (int i = 0; i + 1 < n; i += 2) { String s = cmap.get((by[i] << 8) | by[i + 1], 2); if (s != null) sb.append(s); }
                return sb.toString();
            }
            if (cmap.hasOneByte) {
                for (int i = 0; i < n; i++) { String s = cmap.get(by[i], 1); if (s != null) sb.append(s); }
                return sb.toString();
            }
        }
        for (int i = 0; i < n; i++) { if (by[i] < 0x20) return null; sb.append(cp1252(by[i])); }   // no usable CMap: 1-byte text only
        return sb.toString();
    }

    private static final char[] CP1252 = {
        '€', '\u0081', '‚', 'ƒ', '„', '…', '†', '‡', 'ˆ', '‰', 'Š', '‹', 'Œ', '\u008D', 'Ž', '\u008F',
        '\u0090', '‘', '’', '“', '”', '•', '–', '—', '˜', '™', 'š', '›', 'œ', '\u009D', 'ž', 'Ÿ'};

    static char cp1252(int c) { return c >= 0x80 && c <= 0x9F ? CP1252[c - 0x80] : (char) c; }

    // ---------------------------------------------------------------- helpers

    /** Count of "/Type /Page" objects (not /Pages) in raw bytes. */
    static int countPages(byte[] b) {
        int n = 0, i = 0;
        byte[] pat = {'/', 'P', 'a', 'g', 'e'};
        while ((i = indexOf(b, pat, i)) >= 0) {
            int e = i + 5;
            i = e;
            if (e < b.length && (Character.isLetterOrDigit(b[e] & 0xFF))) continue;
            int p = i - 6;
            while (p >= 0 && isWs(b[p])) p--;
            if (p >= 4 && b[p] == 'e' && b[p - 1] == 'p' && b[p - 2] == 'y' && b[p - 3] == 'T' && b[p - 4] == '/') n++;
        }
        return n;
    }

    private static boolean hasImage(byte[] b) {
        byte[] pat = {'/', 'I', 'm', 'a', 'g', 'e'};
        int i = 0;
        while ((i = indexOf(b, pat, i)) >= 0) {
            int e = i + 6;
            i = e;
            if (e >= b.length || !Character.isLetterOrDigit(b[e] & 0xFF)) return true;
        }
        return false;
    }

    /** /Encrypt followed by an object reference or a dictionary anywhere in the file. */
    static boolean hasEncrypt(byte[] b) {
        byte[] pat = {'/', 'E', 'n', 'c', 'r', 'y', 'p', 't'};
        int i = 0;
        while ((i = indexOf(b, pat, i)) >= 0) {
            int p = i + pat.length;
            i = p;
            if (p < b.length && Character.isLetter(b[p] & 0xFF)) continue;          // /EncryptMetadata
            while (p < b.length && isWs(b[p])) p++;
            if (p + 1 < b.length && b[p] == '<' && b[p + 1] == '<') return true;
            int s = p;
            while (p < b.length && b[p] >= '0' && b[p] <= '9') p++;
            if (p == s) continue;
            int q = p;
            while (q < b.length && isWs(b[q])) q++;
            if (q == p) continue;
            int g = q;
            while (g < b.length && b[g] >= '0' && b[g] <= '9') g++;
            if (g == q) continue;
            while (g < b.length && isWs(b[g])) g++;
            if (g < b.length && b[g] == 'R') return true;
        }
        return false;
    }

    /** First text of an rdf:li inside the XMP element e (dc:title / dc:creator), at most 200 chars; null when absent. */
    static String xmp(String x, String e) {
        int s = x.indexOf("<" + e);
        if (s < 0) return null;
        int en = x.indexOf("</" + e + ">", s);
        if (en < 0) return null;
        int li = x.indexOf("<rdf:li", s);
        if (li < 0 || li > en) return null;
        int gt = x.indexOf('>', li);
        int close = x.indexOf("</rdf:li>", gt);
        if (gt < 0 || close < 0 || close > en) return null;
        String v = x.substring(gt + 1, close).trim();
        return v.isEmpty() ? null : v.length() > 200 ? v.substring(0, 200) : v;
    }

    private static boolean contains(byte[] d, byte[] pat) { return indexOf(d, pat, 0) >= 0; }

    private static boolean matches(byte[] b, int p, byte[] pat) {
        if (p < 0 || p + pat.length > b.length) return false;
        for (int j = 0; j < pat.length; j++) if (b[p + j] != pat[j]) return false;
        return true;
    }

    static int indexOf(byte[] b, byte[] pat, int from) {
        int last = b.length - pat.length;
        byte f = pat[0];
        for (int i = Math.max(0, from); i <= last; i++) {
            if (b[i] != f) continue;
            int j = 1;
            while (j < pat.length && b[i + j] == pat[j]) j++;
            if (j == pat.length) return i;
        }
        return -1;
    }
}
