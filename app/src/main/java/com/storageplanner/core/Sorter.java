package com.storageplanner.core;

import com.storageplanner.tools.Pixels;
import com.storageplanner.tools.category.PhotoCategory;
import com.storageplanner.tools.codes.Codes;
import com.storageplanner.tools.exposure.Exposure;
import com.storageplanner.tools.faces.Faces;
import com.storageplanner.tools.layout.ImageSignals;
import com.storageplanner.tools.ocr.TextRules;

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
        int[] l = Pixels.luma(px);
        if (s.paper >= 0.2 && s.textBlobs < 150 && Math.max(dims[0], dims[1]) > 1100) {     // small print: look again bigger
            int[] d2 = new int[4];
            int[] big;
            try { big = pf.argb(f.abs, BIG, d2); } catch (Throwable t) { big = null; }
            if (big != null) ImageSignals.moreText(Pixels.luma(big), d2[2], d2[3], s);
        }
        String[] code = Codes.read(l, w, h, false);
        if (code == null && (s.flat >= 0.1 || s.paper >= 0.2)) code = Codes.read(l, w, h, true);
        f.code = code == null ? null : Codes.describe(code[0], code[1]);
        f.faces = -1;
        // faces also on pictures with text (a family photo with a caption, an ID card): "has people in it" keeps a picture
        if (!PhotoCategory.isScreenshot(f) && !Exposure.accidental(s)) f.faces = faces(pf, f.abs, px, w, h);
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
            if (p != null && p.category != null && !p.textPending) {          // unchanged and fully sorted last time
                f.category = p.category; f.code = p.code; f.catDate = p.catDate; f.text = p.text; f.faces = p.faces;
                if (f.faces < 0 && !PhotoCategory.isScreenshot(f) && !PhotoCategory.ACCIDENTAL.equals(f.category)) {
                    // sorted before 1.9, when pictures with text skipped the face check: count faces once now
                    int[] px;
                    try { px = pf.argb(f.abs, EDGE, dims); } catch (Throwable t) { px = null; }
                    if (px != null) {
                        f.faces = faces(pf, f.abs, px, dims[2], dims[3]);
                        if (f.faces > 0 && PhotoCategory.PHOTO.equals(f.category)) f.category = PhotoCategory.PEOPLE;
                    }
                }
                continue;
            }
            Prep m = measured == null ? null : measured.get(f);
            if (m == null) {
                int[] px;
                try { px = pf.argb(f.abs, EDGE, dims); } catch (Throwable t) { px = null; }
                if (px == null) continue;
                m = measure(pf, f, px, dims);
            }
            ImageSignals.S s = m.s;
            if (f.quality != null) f.faces = f.quality.faces;          // measured more carefully for near-identical shots
            boolean read = PhotoCategory.worthReading(f, s);           // a receipt or ticket can carry a code too
            PhotoCategory.Result c = PhotoCategory.classify(f, s, f.code, null, f.faces);
            f.category = c.category; f.catDate = 0; f.text = null;
            f.textPending = read;
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
            String t = TextRules.withAmounts(TextRules.clean(words), words);
            f.text = PhotoCategory.ID.equals(c.category) || t.isEmpty() ? null : t.length() > MAX_TEXT ? t.substring(0, MAX_TEXT) : t;
        }
    }

    /** Faces, looked for on a small copy (People vs. other photos only needs to know there is a face). */
    static int faces(Platform pf, String path, int[] px, int w, int h) {
        double s = Math.min(1.0, FACE_EDGE / (double) Math.max(w, h));
        int sw = Math.max(1, (int) (w * s)), sh = Math.max(1, (int) (h * s));
        int[] small = s >= 1.0 ? px : Pixels.shrink(px, w, h, sw, sh);
        try {
            float[] f = pf.faces(path, small, sw, sh);
            if (f == null) return -1;
            return Faces.count(f);
        } catch (Throwable t) { return -1; }
    }
}
