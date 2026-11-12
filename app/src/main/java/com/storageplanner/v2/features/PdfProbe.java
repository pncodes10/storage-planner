package com.storageplanner.v2.features;

import com.storageplanner.v2.model.PdfInfo;
import com.storageplanner.v2.model.Tri;
import com.storageplanner.v2.model.Unknown;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** PDF basics by regex over head/tail regions (PHASE_2 6). No object parser, no inflate. */
public final class PdfProbe {
    private PdfProbe() {}
    static final Pattern VERSION = Pattern.compile("%PDF-(\\d\\.\\d)");
    static final Pattern ENCRYPT = Pattern.compile("/Encrypt\\s*(?:\\d+\\s+\\d+\\s+R|<<)");
    static final Pattern TRAILER = Pattern.compile("trailer\\s*<<|/Type\\s*/XRef\\b");
    static final Pattern LIN_N = Pattern.compile("/N\\s+(\\d+)");
    static final Pattern PAGES = Pattern.compile("/Type\\s*/Pages\\b");
    static final Pattern OBJ = Pattern.compile("(\\d+)\\s+(\\d+)\\s+obj");
    static final Pattern COUNT = Pattern.compile("/Count\\s+(\\d+)");
    static final Pattern PAGE = Pattern.compile("/Type\\s*/Page(?![s\\w])");

    public static void probe(ProbeCtx c) throws IOException {
        PdfInfo p = new PdfInfo();
        c.fr.pdf = p;
        String[] regions;
        if (c.size <= c.lim.pdfWholeMax) regions = new String[]{latin1(c.size <= c.headLen ? c.head : c.read(0, (int) c.size))};
        else regions = new String[]{latin1(c.read(0, c.lim.pdfRegion)), latin1(c.read(c.size - c.lim.pdfRegion, c.lim.pdfRegion))};
        String head = regions[0];
        Matcher m = VERSION.matcher(head.length() > 1024 ? head.substring(0, 1024) : head);
        if (m.find()) p.version = m.group(1);
        // encryption: head 64 KiB + tail 64 KiB (all regions when the file is small)
        boolean enc = false, trailer = false;
        for (int i = 0; i < regions.length; i++) {
            String r = regions[i];
            String scan = c.size <= c.lim.pdfWholeMax ? r : i == 0 ? r.substring(0, Math.min(r.length(), 65536)) : r.substring(Math.max(0, r.length() - 65536));
            if (ENCRYPT.matcher(scan).find()) enc = true;
            if (TRAILER.matcher(r).find()) trailer = true;
        }
        p.encrypted = enc ? Tri.TRUE : trailer ? Tri.FALSE : Tri.UNKNOWN;
        String first = head.length() > 1024 ? head.substring(0, 1024) : head;
        int lin = first.indexOf("/Linearized");
        p.linearized = Tri.of(lin >= 0);
        if (lin >= 0) {
            int e = first.indexOf(">>", lin);
            Matcher n = LIN_N.matcher(e > 0 ? first.substring(lin, e) : first.substring(lin));
            if (n.find()) { p.pages = Integer.parseInt(n.group(1)); p.pagesSource = "LINEARIZED"; }
        }
        boolean objStm = false;
        for (String r : regions) if (r.contains("/ObjStm")) objStm = true;
        p.objectStreams = Tri.of(objStm);
        if (p.pages < 0) {
            long bestOff = -1; int best = -1;
            for (int ri = 0; ri < regions.length; ri++) {
                String r = regions[ri];
                long base = ri == 0 ? 0 : c.size - r.length();
                Matcher pm = PAGES.matcher(r);
                while (pm.find()) {
                    int s = Math.max(0, pm.start() - 8192);
                    String back = r.substring(s, pm.start());
                    Matcher om = OBJ.matcher(back);
                    int objStart = -1; while (om.find()) objStart = s + om.end();
                    if (objStart < 0) continue;
                    int end = r.indexOf("endobj", pm.start());
                    if (end < 0) continue;
                    String obj = r.substring(objStart, end);
                    Matcher cm = COUNT.matcher(obj);
                    if (cm.find() && !obj.contains("/Parent") && base + pm.start() > bestOff) {
                        bestOff = base + pm.start();
                        try { best = Integer.parseInt(cm.group(1)); } catch (NumberFormatException e) { best = -1; }
                    }
                }
            }
            if (best >= 0) { p.pages = best; p.pagesSource = "PAGES_ROOT"; }
            else if (c.size <= c.lim.pdfWholeMax && !objStm) {
                Matcher pg = PAGE.matcher(head); int n = 0; while (pg.find()) n++;
                if (n > 0) { p.pages = n; p.pagesSource = "PAGE_OBJECTS"; }
            }
            if (p.pages < 0 && objStm) c.problem("pdf:OBJECT_STREAMS");
        }
        int eofs = 0;
        for (String r : regions) { int i = -1; while ((i = r.indexOf("%%EOF", i + 1)) >= 0) eofs++; }
        p.incrementalUpdates = Math.max(0, eofs - 1);
        if (p.encrypted == Tri.FALSE) {
            String all = regions.length == 1 ? head : regions[0] + regions[1];
            p.title = str(all, "Title"); p.author = str(all, "Author"); p.producer = str(all, "Producer");
            p.createdAt = date(str(all, "CreationDate"));
        }
    }

    static String latin1(byte[] b) { return new String(b, StandardCharsets.ISO_8859_1); }

    /** Last /Key (literal) or <hex> value in the text (latest update wins). */
    static String str(String s, String key) {
        int i = s.lastIndexOf("/" + key);
        while (i >= 0) {
            int p = i + key.length() + 1;
            while (p < s.length() && Character.isWhitespace(s.charAt(p))) p++;
            if (p < s.length() && (s.charAt(p) == '(' || (s.charAt(p) == '<' && (p + 1 >= s.length() || s.charAt(p + 1) != '<')))) {
                byte[] raw = s.charAt(p) == '(' ? literal(s, p) : hex(s, p);
                if (raw != null) {
                    String v = raw.length >= 2 && (raw[0] & 0xff) == 0xFE && (raw[1] & 0xff) == 0xFF
                            ? new String(raw, 2, raw.length - 2 - (raw.length & 1), StandardCharsets.UTF_16BE) : new String(raw, StandardCharsets.ISO_8859_1);
                    v = v.trim();
                    return v.isEmpty() ? null : v.length() > 256 ? v.substring(0, 256) : v;
                }
            }
            i = s.lastIndexOf("/" + key, i - 1);
        }
        return null;
    }

    static byte[] literal(String s, int p) {
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        int depth = 0;
        for (int i = p; i < s.length() && i < p + 4096; i++) {
            char ch = s.charAt(i);
            if (ch == '(') { if (depth++ > 0) o.write(ch); continue; }
            if (ch == ')') { if (--depth == 0) return o.toByteArray(); o.write(ch); continue; }
            if (ch == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                switch (n) {
                    case 'n': o.write('\n'); break; case 'r': o.write('\r'); break; case 't': o.write('\t'); break;
                    case 'b': o.write('\b'); break; case 'f': o.write('\f'); break;
                    case '\r': if (i + 1 < s.length() && s.charAt(i + 1) == '\n') i++; break;
                    case '\n': break;
                    default:
                        if (n >= '0' && n <= '7') {
                            int v = n - '0', k = 0;
                            while (k < 2 && i + 1 < s.length() && s.charAt(i + 1) >= '0' && s.charAt(i + 1) <= '7') { v = v * 8 + (s.charAt(++i) - '0'); k++; }
                            o.write(v & 0xff);
                        } else o.write(n);
                }
                continue;
            }
            o.write(ch);
        }
        return null;
    }

    static byte[] hex(String s, int p) {
        int e = s.indexOf('>', p);
        if (e < 0 || e - p > 2048) return null;
        String h = s.substring(p + 1, e).replaceAll("\\s", "");
        if (!h.matches("[0-9A-Fa-f]*")) return null;
        if ((h.length() & 1) == 1) h += "0";
        byte[] b = new byte[h.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(h.substring(2 * i, 2 * i + 2), 16);
        return b;
    }

    static final Pattern DATE = Pattern.compile("^(?:D:)?(\\d{4})(\\d{2})?(\\d{2})?(\\d{2})?(\\d{2})?(\\d{2})?(?:([+\\-Z])(\\d{2})?'?(\\d{2})?'?)?");

    static long date(String s) {
        if (s == null) return Unknown.TIME;
        Matcher m = DATE.matcher(s.trim());
        if (!m.find()) return Unknown.TIME;
        int y = Integer.parseInt(m.group(1));
        int mo = m.group(2) == null ? 1 : Integer.parseInt(m.group(2)), d = m.group(3) == null ? 1 : Integer.parseInt(m.group(3));
        int h = m.group(4) == null ? 0 : Integer.parseInt(m.group(4)), mi = m.group(5) == null ? 0 : Integer.parseInt(m.group(5));
        int se = m.group(6) == null ? 0 : Integer.parseInt(m.group(6));
        long t = Dates.utc(y, mo, d, h, mi, se, 0);
        if (!Unknown.knownTime(t)) return t;
        if (m.group(7) != null && !m.group(7).equals("Z") && m.group(8) != null) {
            int off = Integer.parseInt(m.group(8)) * 60 + (m.group(9) == null ? 0 : Integer.parseInt(m.group(9)));
            t -= (m.group(7).equals("-") ? -1 : 1) * off * 60000L;
        }
        return t;
    }
}
