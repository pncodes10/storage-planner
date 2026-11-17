package com.storageplanner.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Scan stage: sorts pictures into categories. Pass 1 measures every picture (text rows, paper, screens,
 * darkness, codes, faces). Pass 2 reads the text of the pictures worth reading, screenshots and newest first,
 * within a time limit; whatever is left is read in the next scan (unchanged pictures are never redone).
 */
final class Sorter {
    private Sorter() {}

    static final int EDGE = 1024, BIG = 2048, TEXT_EDGE = 1600, FACE_EDGE = 512, GREETING_EDGE = 360;
    static final int MAX_TEXT = 4000;

    /**
     * Version of the sorting rules. Pictures sorted under an older version are measured and sorted once more at the
     * next scan (the file is unchanged, but the rules or the measurements are new). Raise it whenever a change
     * should reach pictures that are already sorted. 1: page-of-paper check. 2: eyes open on every photo with a face,
     * cards and papers held up are read (ID cards). 3: focus ignores sensor grain (night skies), glare share.
     */
    static final int VERSION = 3;

    /** True when this earlier result for an unchanged picture can be kept as it is. */
    static boolean current(FileItem p) { return p != null && p.category != null && !p.textPending && p.sortVersion >= VERSION; }

    /** Pass-1 measurements of one picture, kept until it is classified. */
    static final class Prep { ImageSignals.S s; double aspect; }

    /**
     * Measures one picture from ARGB pixels at ~EDGE px (dims = {origW, origH, w, h}): signals, codes and faces.
     * Called by the scanner while it has the pixels anyway (camera photos), or by sort() for the rest.
     */
    static Prep measure(Platform pf, FileItem f, int[] px, int[] dims) {
        int w = dims[2], h = dims[3];
        if (f.width == 0) { f.width = dims[0]; f.height = dims[1]; }
        ImageSignals.S s = ImageSignals.measure(px, w, h);
        int[] l = luma(px);
        f.signals = s;                       // kept on the file (saved with the results) for tags; moreText below refines the same object
        if (s.paper >= 0.2 && s.textBlobs < 150 && Math.max(dims[0], dims[1]) > 1100) {     // small print: look again bigger
            int[] d2 = new int[4];
            int[] big;
            try { big = pf.argb(f.abs, BIG, d2); } catch (Throwable t) { big = null; }
            if (big != null) ImageSignals.moreText(luma(big), d2[2], d2[3], s);
        }
        String[] code = Codes.read(l, w, h, false);
        if (code == null && (s.flat >= 0.1 || s.paper >= 0.2)) code = Codes.read(l, w, h, true);
        f.code = code == null ? null : Codes.describe(code[0], code[1]);
        f.faces = -1;
        f.eyeFaces = -1; f.eyesOpen = -1;
        // a page or a card is looked at for faces too: someone holding it up is a person, and an ID card has a face on it
        if ((!PhotoCategory.worthReading(f, s) || s.page() || PhotoCategory.cardCandidate(s)) && !PhotoCategory.isScreenshot(f) && !PhotoCategory.accidental(s))
            faces(pf, f, px, w, h, l);
        Prep p = new Prep();
        p.s = s; p.aspect = dims[0] / (double) Math.max(1, dims[1]);
        return p;
    }

    static void sort(Platform pf, Scanner.Progress pr, Scanner.Result r, Scanner.Options opt, Map<FileItem, Prep> measured) {
        List<FileItem> pics = new ArrayList<FileItem>();
        for (FileItem f : r.files) if (PhotoCategory.sortable(f)) pics.add(f);
        final Map<FileItem, ImageSignals.S> sig = new HashMap<FileItem, ImageSignals.S>();
        final Map<FileItem, double[]> aspect = new HashMap<FileItem, double[]>();
        List<FileItem> toRead = new ArrayList<FileItem>();
        int[] dims = new int[4];
        for (int k = 0; k < pics.size(); k++) {
            if (pr.cancelled()) return;
            pr.update("Sorting pictures", k + 1, pics.size());
            FileItem f = pics.get(k);
            FileItem p = Scanner.same(f, opt.previous);
            if (current(p)) {                                                  // unchanged and fully sorted last time, by the current rules
                copyOver(p, f);
                continue;
            }
            Prep m = measured == null ? null : measured.get(f);
            if (m == null) {
                int[] px;
                try { px = pf.argb(f.abs, EDGE, dims); } catch (Throwable t) { px = null; }
                if (px == null) {                                          // cannot be decoded now: keep what the older sort found
                    if (p != null && p.category != null) copyOver(p, f);
                    continue;
                }
                m = measure(pf, f, px, dims);
            }
            ImageSignals.S s = m.s;
            if (f.quality != null) f.faces = f.quality.faces;          // measured more carefully for near-identical shots
            // sorted before by older rules: measured again, but a verdict that came from the text or a code stays,
            // and the text already read is not read again
            boolean again = p != null && p.category != null && !p.textPending;
            if (again && !PhotoCategory.PHOTO.equals(p.category) && !PhotoCategory.PEOPLE.equals(p.category) && !PhotoCategory.ACCIDENTAL.equals(p.category)) {
                int ef = f.eyeFaces, eo = f.eyesOpen;                  // just measured: newer than what the older sort kept
                copyOver(p, f);
                f.signals = s; f.sortVersion = VERSION; f.eyeFaces = ef; f.eyesOpen = eo;
                continue;
            }
            boolean read = PhotoCategory.worthReading(f, s);           // a receipt or ticket can carry a code too
            if (again && !((s.page() || PhotoCategory.cardLike(f, s)) && p.text == null)) read = false;   // read only what the newer checks newly send
            PhotoCategory.Result c = PhotoCategory.classify(f, s, f.code, null, f.faces);
            f.category = c.category; f.catDate = again && !read ? p.catDate : 0; f.text = again && !read ? p.text : null;
            f.textPending = read; f.sortVersion = VERSION;
            if (read) { toRead.add(f); sig.put(f, s); aspect.put(f, new double[]{m.aspect}); }
        }
        // ---- pass 2: read text, screenshots first, then newest first, within the time limit
        Collections.sort(toRead, new Comparator<FileItem>() {
            public int compare(FileItem a, FileItem b) {
                boolean sa = PhotoCategory.isScreenshot(a), sb = PhotoCategory.isScreenshot(b);
                if (sa != sb) return sa ? -1 : 1;
                return Long.compare(b.mtime, a.mtime);
            }
        });
        long t0 = System.currentTimeMillis();
        int fails = 0;
        for (int k = 0; k < toRead.size(); k++) {
            if (pr.cancelled()) return;
            if (System.currentTimeMillis() - t0 > opt.textBudgetMs) break;     // the rest stays "not read yet"
            pr.update("Reading text in pictures", k + 1, toRead.size());
            FileItem f = toRead.get(k);
            TextRules.Words words = null;
            try {
                int[] px = pf.argb(f.abs, TEXT_EDGE, dims);
                if (px != null) words = pf.readText(f.abs, px, dims[2], dims[3]);
            } catch (Throwable t) { words = null; }
            if (words == null) {
                if (++fails >= 3 && k + 1 == fails) break;             // no reader on this phone: try again next scan
                if (k + 1 > fails) words = new TextRules.Words();       // the reader works, this picture just gave nothing
                else continue;
            }
            fails = 0;
            ImageSignals.S s = sig.get(f);
            TextRules.Verdict v = TextRules.classify(words, aspect.get(f)[0], s.colorful >= 15 && s.flat < 0.5, PhotoCategory.isScreenshot(f),
                    f.area.startsWith("wa_") || "telegram_media".equals(f.area), r.now);
            boolean chat = f.area.startsWith("wa_") || "telegram_media".equals(f.area);
            boolean photoLike = s.colorful >= 15 && s.flat < 0.5;
            if (v.kind == null && chat && photoLike) {
                // greeting images use big decorative letters; the reader often sees them better on a small copy
                try {
                    int[] small = pf.argb(f.abs, GREETING_EDGE, dims);
                    TextRules.Words w2 = small == null ? null : pf.readText(f.abs, small, dims[2], dims[3]);
                    if (w2 != null) {
                        TextRules.Verdict v2 = TextRules.classify(w2, aspect.get(f)[0], true, false, true, r.now);
                        if (PhotoCategory.GREETING.equals(v2.kind)) { v = v2; words = w2; }
                    }
                } catch (Throwable ignored) {}
            }
            PhotoCategory.Result c = PhotoCategory.classify(f, s, f.code, v, f.faces);
            f.category = c.category; f.catDate = c.date; f.textPending = false;
            String t = TextRules.clean(words);
            f.text = PhotoCategory.ID.equals(c.category) || t.isEmpty() ? null : t.length() > MAX_TEXT ? t.substring(0, MAX_TEXT) : t;
        }
    }

    private static void copyOver(FileItem p, FileItem f) {
        f.category = p.category; f.code = p.code; f.catDate = p.catDate; f.text = p.text; f.faces = p.faces; f.signals = p.signals;
        f.sortVersion = p.sortVersion; f.textPending = p.textPending; f.eyeFaces = p.eyeFaces; f.eyesOpen = p.eyesOpen;
    }

    /** Faces, looked for on a small copy (People vs. other photos only needs to know there is a face). */
    static int faces(Platform pf, String path, int[] px, int w, int h) {
        FileItem f = new FileItem("", path, 0, 0);
        faces(pf, f, px, w, h, null);
        return f.faces;
    }

    /**
     * Sets f.faces from a small copy, and, when there are faces and luma (w x h) is given, f.eyeFaces and f.eyesOpen,
     * judged on the full-size pixels (the eye check needs more detail than finding the face does).
     */
    static void faces(Platform pf, FileItem item, int[] px, int w, int h, int[] luma) {
        double s = Math.min(1.0, FACE_EDGE / (double) Math.max(w, h));
        int sw = Math.max(1, (int) (w * s)), sh = Math.max(1, (int) (h * s));
        int[] small = s >= 1.0 ? px : shrink(px, w, h, sw, sh);
        float[] f;
        try { f = pf.faces(item.abs, small, sw, sh); } catch (Throwable t) { f = null; }
        item.faces = f == null ? -1 : Faces.count(f);
        if (item.faces <= 0 || luma == null) return;
        try {
            double kx = w / (double) sw, ky = h / (double) sh;
            float[] big = f.clone();
            for (int i = 0; i + Faces.F - 1 < big.length; i += Faces.F) {
                big[i] *= kx; big[i + 1] *= ky; big[i + 2] *= kx;
                big[i + 4] *= kx; big[i + 5] *= ky; big[i + 6] *= kx; big[i + 7] *= ky;
            }
            int[] e = PhotoQuality.eyes(luma, w, h, big);
            item.eyeFaces = e[0]; item.eyesOpen = e[1];
        } catch (Throwable t) { item.eyeFaces = -1; item.eyesOpen = -1; }
    }

    /** Box-filter down-scaling of ARGB pixels. */
    static int[] shrink(int[] px, int w, int h, int sw, int sh) {
        int[] o = new int[sw * sh];
        for (int y = 0; y < sh; y++) {
            int y0 = y * h / sh, y1 = Math.max(y0 + 1, (y + 1) * h / sh);
            for (int x = 0; x < sw; x++) {
                int x0 = x * w / sw, x1 = Math.max(x0 + 1, (x + 1) * w / sw);
                long rr = 0, gg = 0, bb = 0; int n = 0;
                for (int yy = y0; yy < y1; yy++) for (int xx = x0; xx < x1; xx++) {
                    int c = px[yy * w + xx]; rr += (c >> 16) & 255; gg += (c >> 8) & 255; bb += c & 255; n++;
                }
                o[y * sw + x] = 0xFF000000 | (int) (rr / n) << 16 | (int) (gg / n) << 8 | (int) (bb / n);
            }
        }
        return o;
    }

    static int[] luma(int[] px) {
        int[] l = new int[px.length];
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            l[i] = (299 * ((p >> 16) & 255) + 587 * ((p >> 8) & 255) + 114 * (p & 255)) / 1000;
        }
        return l;
    }
}
