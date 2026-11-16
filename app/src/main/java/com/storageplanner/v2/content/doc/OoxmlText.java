package com.storageplanner.v2.content.doc;

import com.storageplanner.v2.device.ReadableFile;
import com.storageplanner.v2.features.Dates;
import com.storageplanner.v2.features.Limits;
import com.storageplanner.v2.features.ZipProbe;
import com.storageplanner.v2.model.ArchiveEntry;
import com.storageplanner.v2.model.DocStatus;
import com.storageplanner.v2.model.Tri;
import com.storageplanner.v2.model.Unknown;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Text of docx / xlsx / pptx (PHASE_6 7.3): the zip is listed by {@link ZipProbe} (existing, bounded), one entry is read at a time
 * with {@link ZipProbe#read} (4 MiB out, ratio 200), at most 300 entries and 32 MiB in total; text comes from {@link XmlText}.
 * Never throws; any cap hit is {@code LIMIT}, an encrypted entry {@code ENCRYPTED} (both unknown). Also home of the shared
 * {@link Result} of the S3b extractors.
 */
public final class OoxmlText {
    private OoxmlText() {}

    public static final int MAX_ENTRY = 4 << 20;
    public static final long MAX_TOTAL = 32L << 20;
    public static final int MAX_ENTRIES = 300;
    public static final int MAX_RATIO = 200;
    public static final int MAX_CHARS = 200000;

    /** Result shared by OoxmlText, OdfText, Ole2 and PlainText; text, title and author are for the caller to reduce and drop. */
    public static final class Result {
        public DocStatus status = DocStatus.CORRUPT;
        public String format;
        public final StringBuilder text = new StringBuilder();
        public int pages = -1;
        public long createdAt = Unknown.TIME, modifiedAt = Unknown.TIME;
        public int unsupported;
        public String title, author;
    }

    static Set<String> set(String... s) { return new HashSet<String>(Arrays.asList(s)); }
    static final Set<String> DOCX_TEXT = set("w:t"), DOCX_BREAK = set("w:p");
    static final Set<String> XLSX_TEXT = set("t"), XLSX_BREAK = set("si");
    static final Set<String> PPTX_TEXT = set("a:t"), PPTX_BREAK = set("a:p");

    /** Reads entries of an opened zip under the caps; remembers why it stopped. */
    static final class Reader {
        final ReadableFile rf; final long size; final Result r;
        long total; int reads;
        boolean limit, encrypted;
        Reader(ReadableFile rf, long size, Result r) { this.rf = rf; this.size = size; this.r = r; }

        /** Bytes of the entry, or null (limit / encrypted flags set, or r.unsupported counted). */
        byte[] read(ArchiveEntry e) {
            if (e == null) return null;
            if (e.encrypted == Tri.TRUE) { encrypted = true; return null; }
            if (reads >= MAX_ENTRIES || e.size > MAX_ENTRY || total + Math.max(0, e.size) > MAX_TOTAL) { limit = true; return null; }
            reads++;
            byte[] b;
            try { b = ZipProbe.read(rf, size, e, MAX_ENTRY, MAX_RATIO); }
            catch (IOException ex) { r.unsupported++; return null; }
            if (b == null) { r.unsupported++; return null; }
            total += b.length;
            return b;
        }
    }

    /** Entries at depth 0 by path (first wins); null when the archive cannot be listed. */
    static Map<String, ArchiveEntry> list(ReadableFile rf, long size) throws IOException {
        ZipProbe.Listing l = ZipProbe.list(rf, size, new Limits());
        if (!"OK".equals(l.info.listingStatus)) return null;
        Map<String, ArchiveEntry> m = new HashMap<String, ArchiveEntry>();
        for (ArchiveEntry e : l.entries) if (e.depth == 0 && !e.dir && e.path != null && !m.containsKey(e.path)) m.put(e.path, e);
        return m;
    }

    /** @param format docx, xlsx or pptx */
    public static Result extract(ReadableFile rf, long size, String format) {
        Result r = new Result();
        r.format = format;
        try {
            run(rf, size, format, r);
        } catch (IOException e) {
            r.status = DocStatus.CORRUPT; r.text.setLength(0);
        } catch (RuntimeException e) {
            r.status = DocStatus.CORRUPT; r.text.setLength(0);
        }
        return r;
    }

    private static void run(ReadableFile rf, long size, String format, Result r) throws IOException {
        Map<String, ArchiveEntry> m = list(rf, size);
        if (m == null) { r.status = DocStatus.CORRUPT; return; }
        Reader rd = new Reader(rf, size, r);
        List<ArchiveEntry> parts = new ArrayList<ArchiveEntry>();
        Set<String> textEl, breakEl;
        boolean mainPresent;
        int slideCount = 0;
        if (format.equals("docx")) {
            textEl = DOCX_TEXT; breakEl = DOCX_BREAK;
            mainPresent = m.containsKey("word/document.xml");
            if (mainPresent) parts.add(m.get("word/document.xml"));
            if (m.containsKey("word/footnotes.xml")) parts.add(m.get("word/footnotes.xml"));
        } else if (format.equals("xlsx")) {
            textEl = XLSX_TEXT; breakEl = XLSX_BREAK;
            mainPresent = m.containsKey("xl/workbook.xml");
            if (m.containsKey("xl/sharedStrings.xml")) parts.add(m.get("xl/sharedStrings.xml"));
        } else if (format.equals("pptx")) {
            textEl = PPTX_TEXT; breakEl = PPTX_BREAK;
            final Map<Integer, ArchiveEntry> slides = new HashMap<Integer, ArchiveEntry>();
            for (Map.Entry<String, ArchiveEntry> en : m.entrySet()) {
                int n = slideNumber(en.getKey());
                if (n >= 1 && n <= 200) slides.put(n, en.getValue());
            }
            List<Integer> nums = new ArrayList<Integer>(slides.keySet());
            Collections.sort(nums, new Comparator<Integer>() { public int compare(Integer a, Integer b) { return a.compareTo(b); } });
            for (Integer n : nums) parts.add(slides.get(n));
            slideCount = nums.size();
            mainPresent = slideCount > 0;
        } else { r.status = DocStatus.UNSUPPORTED; return; }

        boolean malformed = false;
        for (ArchiveEntry e : parts) {
            if (r.text.length() >= MAX_CHARS) break;
            byte[] b = rd.read(e);
            if (b == null) { if (rd.limit || rd.encrypted) break; continue; }
            XmlText.Out o = XmlText.text(b, textEl, breakEl, MAX_CHARS - r.text.length());
            if (o.malformed) malformed = true;
            r.text.append(o.text);
        }
        // metadata (small parts; a failure here never changes the status)
        byte[] core = rd.limit || rd.encrypted ? null : rd.read(m.get("docProps/core.xml"));
        if (core != null) {
            r.title = XmlText.firstText(core, "dc:title");
            r.author = XmlText.firstText(core, "dc:creator");
            r.createdAt = isoDate(XmlText.firstText(core, "dcterms:created"));
            r.modifiedAt = isoDate(XmlText.firstText(core, "dcterms:modified"));
        }
        byte[] app = rd.limit || rd.encrypted ? null : rd.read(m.get("docProps/app.xml"));
        if (app != null) {
            int p = intOf(XmlText.firstText(app, format.equals("pptx") ? "Slides" : "Pages"));
            if (p >= 0 && (format.equals("docx") || format.equals("pptx"))) r.pages = p;
        }
        if (r.pages < 0 && format.equals("pptx") && slideCount > 0) r.pages = slideCount;
        if (rd.encrypted) { r.status = DocStatus.ENCRYPTED; r.text.setLength(0); return; }
        if (rd.limit) { r.status = DocStatus.LIMIT; r.text.setLength(0); return; }
        if (!mainPresent) { r.status = DocStatus.UNSUPPORTED; r.text.setLength(0); return; }
        int chars = r.text.length();
        if (malformed && chars < 20) { r.status = DocStatus.CORRUPT; return; }
        if (r.unsupported > 0 && chars < 20) { r.status = DocStatus.UNSUPPORTED; return; }
        r.status = DocStatus.OK;
    }

    /** N of "ppt/slides/slide&lt;N&gt;.xml" (digits only), else -1. */
    static int slideNumber(String path) {
        String pre = "ppt/slides/slide", suf = ".xml";
        if (!path.startsWith(pre) || !path.endsWith(suf) || path.length() <= pre.length() + suf.length()) return -1;
        String d = path.substring(pre.length(), path.length() - suf.length());
        if (d.length() > 6) return -1;
        for (int i = 0; i < d.length(); i++) if (d.charAt(i) < '0' || d.charAt(i) > '9') return -1;
        return Integer.parseInt(d);
    }

    static int intOf(String s) {
        if (s == null || s.isEmpty() || s.length() > 9) return -1;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) < '0' || s.charAt(i) > '9') return -1;
        return Integer.parseInt(s);
    }

    /** ISO-8601 date-time ("2023-05-01T10:15:30Z", fraction and +hh:mm offset allowed; date-only too) to epoch ms; unknown when invalid. */
    static long isoDate(String s) {
        if (s == null) return Unknown.TIME;
        s = s.trim();
        try {
            if (s.length() < 10 || s.charAt(4) != '-' || s.charAt(7) != '-') return Unknown.TIME;
            int y = Integer.parseInt(s.substring(0, 4)), mo = Integer.parseInt(s.substring(5, 7)), d = Integer.parseInt(s.substring(8, 10));
            int h = 0, mi = 0, sec = 0, ms = 0;
            long off = 0;
            if (s.length() > 10) {
                if (s.charAt(10) != 'T' || s.length() < 16) return Unknown.TIME;
                h = Integer.parseInt(s.substring(11, 13)); mi = Integer.parseInt(s.substring(14, 16));
                int p = 16;
                if (p < s.length() && s.charAt(p) == ':') { sec = Integer.parseInt(s.substring(p + 1, p + 3)); p += 3; }
                if (p < s.length() && s.charAt(p) == '.') {
                    int q = p + 1; while (q < s.length() && Character.isDigit(s.charAt(q))) q++;
                    String f = (s.substring(p + 1, q) + "000").substring(0, 3);
                    ms = Integer.parseInt(f); p = q;
                }
                if (p < s.length() && (s.charAt(p) == '+' || s.charAt(p) == '-') && s.length() >= p + 6) {
                    int oh = Integer.parseInt(s.substring(p + 1, p + 3)), om = Integer.parseInt(s.substring(p + 4, p + 6));
                    off = (oh * 60L + om) * 60000L * (s.charAt(p) == '-' ? -1 : 1);
                }
            }
            if (y < 1980 || y > 2200 || mo < 1 || mo > 12 || d < 1 || d > 31 || h > 23 || mi > 59 || sec > 60) return Unknown.TIME;
            return Dates.utc(y, mo, d, h, mi, Math.min(sec, 59), ms) - off;
        } catch (RuntimeException e) {
            return Unknown.TIME;
        }
    }
}
