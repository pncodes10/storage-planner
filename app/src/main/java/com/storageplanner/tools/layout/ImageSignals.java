package com.storageplanner.tools.layout;

import com.storageplanner.tools.quality.PhotoQuality;

/**
 * Cheap picture measurements used to sort photos into categories, from an upright ARGB picture with a long
 * edge of about 1024 px. No machine learning: text is found as rows of character-sized blobs, screenshots
 * by their perfectly flat areas, paper by large bright grey areas, accidental shots by darkness and blur.
 */
public final class ImageSignals {
    private ImageSignals() {}

    /** Measurements of one picture (all scale-free). */
    public static final class S {
        public double meanLuma, darkFrac;   // average brightness 0..255; share of pixels darker than 25
        public double contrast;             // luma standard deviation
        public double colorful;             // Hasler-Suesstrunk colourfulness
        public double paper;                // share of bright, nearly colourless pixels
        public double flat;                 // share of pixels exactly equal to all 4 neighbours (screens, graphics)
        public double sharp;                // PhotoQuality.sharpness
        public int textBlobs;               // character-sized blobs that sit in rows with neighbours
        public double textArea;             // share of the picture covered by those rows' characters
        public int textLines;               // rough number of text lines
        public double textHeight;           // median character height, fraction of the picture height
        public double bigText;              // widest row of large letters (>= 3% of the height), fraction of the width

        /** Enough text to be worth reading (the text reader is the slow part). */
        public boolean wordy() { return textBlobs >= 25 && textArea >= 0.004; }
    }

    public static S measure(int[] argb, int w, int h) {
        S s = new S();
        int n = w * h;
        int[] l = new int[n];
        double sum = 0, sq = 0, rg = 0, rg2 = 0, yb = 0, yb2 = 0; int dark = 0, paper = 0;
        for (int i = 0; i < n; i++) {
            int p = argb[i], r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255;
            int y = (299 * r + 587 * g + 114 * b) / 1000;
            l[i] = y; sum += y; sq += (double) y * y;
            if (y < 25) dark++;
            int mx = Math.max(r, Math.max(g, b)), mn = Math.min(r, Math.min(g, b));
            if (y >= 165 && mx - mn <= 40) paper++;
            double a = r - g, c = 0.5 * (r + g) - b;
            rg += a; rg2 += a * a; yb += c; yb2 += c * c;
        }
        s.meanLuma = sum / n;
        s.contrast = Math.sqrt(Math.max(0, sq / n - s.meanLuma * s.meanLuma));
        s.darkFrac = dark / (double) n;
        s.paper = paper / (double) n;
        double mrg = rg / n, myb = yb / n, srg = Math.sqrt(Math.max(0, rg2 / n - mrg * mrg)), syb = Math.sqrt(Math.max(0, yb2 / n - myb * myb));
        s.colorful = Math.sqrt(srg * srg + syb * syb) + 0.3 * Math.sqrt(mrg * mrg + myb * myb);
        int flat = 0;
        for (int y = 1; y < h - 1; y++)
            for (int x = 1; x < w - 1; x++) {
                int i = y * w + x, p = argb[i] & 0xFFFFFF;
                if ((argb[i - 1] & 0xFFFFFF) == p && (argb[i + 1] & 0xFFFFFF) == p && (argb[i - w] & 0xFFFFFF) == p && (argb[i + w] & 0xFFFFFF) == p) flat++;
            }
        s.flat = flat / (double) Math.max(1, (w - 2) * (h - 2));
        s.sharp = PhotoQuality.sharpness(l, w, h);
        text(l, w, h, s);
        return s;
    }

    /**
     * Text measurements only, on a larger luma picture (for paper-like pictures whose small print is lost at
     * 1024 px); fills textBlobs, textArea, textLines and textHeight of s if they come out higher.
     */
    public static void moreText(int[] luma, int w, int h, S s) {
        S t = new S();
        text(luma, w, h, t);
        if (t.textBlobs > s.textBlobs) { s.textBlobs = t.textBlobs; s.textArea = t.textArea; s.textLines = t.textLines; s.textHeight = t.textHeight; }
        s.bigText = Math.max(s.bigText, t.bigText);
    }

    // ------------------------------------------------------------------ text: rows of character-sized blobs
    static void text(int[] l, int w, int h, S s) {
        long[] ii = new long[(w + 1) * (h + 1)], ii2 = new long[(w + 1) * (h + 1)];
        for (int y = 0; y < h; y++) {
            long rs = 0, rs2 = 0;
            for (int x = 0; x < w; x++) {
                int v = l[y * w + x]; rs += v; rs2 += (long) v * v;
                ii[(y + 1) * (w + 1) + x + 1] = ii[y * (w + 1) + x + 1] + rs;
                ii2[(y + 1) * (w + 1) + x + 1] = ii2[y * (w + 1) + x + 1] + rs2;
            }
        }
        int rad = Math.max(8, Math.max(w, h) / 64);
        byte[] ink = new byte[w * h];                     // 1 = darker than surroundings, 2 = brighter
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                int x0 = Math.max(0, x - rad), x1 = Math.min(w, x + rad + 1), y0 = Math.max(0, y - rad), y1 = Math.min(h, y + rad + 1);
                long a = (long) (x1 - x0) * (y1 - y0);
                long sm = ii[y1 * (w + 1) + x1] - ii[y0 * (w + 1) + x1] - ii[y1 * (w + 1) + x0] + ii[y0 * (w + 1) + x0];
                long sm2 = ii2[y1 * (w + 1) + x1] - ii2[y0 * (w + 1) + x1] - ii2[y1 * (w + 1) + x0] + ii2[y0 * (w + 1) + x0];
                double m = sm / (double) a, sd = Math.sqrt(Math.max(0, sm2 / (double) a - m * m));
                if (sd < 12) continue;                      // flat surroundings: no strokes here
                int v = l[y * w + x];
                double k = Math.max(14, 0.5 * sd);
                if (v < m - k) ink[y * w + x] = 1; else if (v > m + k) ink[y * w + x] = 2;
            }
        int[] lab = new int[w * h];
        int[] par = new int[w * h / 2 + 16];
        int next = 1;
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                int i = y * w + x; byte c = ink[i];
                if (c == 0) continue;
                int left = x > 0 && ink[i - 1] == c ? lab[i - 1] : 0, up = y > 0 && ink[i - w] == c ? lab[i - w] : 0;
                if (left == 0 && up == 0) {
                    if (next >= par.length) par = java.util.Arrays.copyOf(par, par.length * 2);
                    par[next] = next; lab[i] = next++;
                } else if (left != 0 && up != 0) {
                    int a = find(par, left), b = find(par, up);
                    if (a != b) par[Math.max(a, b)] = Math.min(a, b);
                    lab[i] = Math.min(a, b);
                } else lab[i] = left != 0 ? left : up;
            }
        int[] x0 = new int[next], x1 = new int[next], y0 = new int[next], y1 = new int[next], area = new int[next], per = new int[next];
        java.util.Arrays.fill(x0, Integer.MAX_VALUE); java.util.Arrays.fill(y0, Integer.MAX_VALUE);
        java.util.Arrays.fill(x1, -1); java.util.Arrays.fill(y1, -1);
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                if (lab[i] == 0) continue;
                int r = find(par, lab[i]);
                lab[i] = r;
                area[r]++;
                if (x < x0[r]) x0[r] = x; if (x > x1[r]) x1[r] = x;
                if (y < y0[r]) y0[r] = y; if (y > y1[r]) y1[r] = y;
                byte c = ink[i];
                if (x == 0 || y == 0 || x == w - 1 || y == h - 1 || ink[i - 1] != c || ink[i + 1] != c || ink[i - w] != c || ink[i + w] != c) per[r]++;
            }
        // character-like blobs: plausible size and shape, strokes thin compared with the height
        int minH = Math.max(5, h / 200), maxH = h / 6;
        int[] cand = new int[next]; int nc = 0;
        double[] stroke = new double[next];
        for (int r = 1; r < next; r++) {
            if (area[r] == 0) continue;
            int bw = x1[r] - x0[r] + 1, bh = y1[r] - y0[r] + 1;
            if (bh < minH || bh > maxH || bw > 3 * bh || bw * 8 < bh) continue;
            double fill = area[r] / (double) (bw * bh);
            if (fill < 0.12 || fill > 0.92) continue;
            if (x0[r] == 0 || y0[r] == 0 || x1[r] == w - 1 || y1[r] == h - 1) continue;
            stroke[r] = 2.0 * area[r] / Math.max(1, per[r]);
            if (stroke[r] > 0.35 * bh) continue;               // blobs, not strokes
            cand[nc++] = r;
        }
        // chains: blobs joined to similar-sized neighbours on the same line
        Integer[] order = new Integer[nc];
        for (int i = 0; i < nc; i++) order[i] = cand[i];
        final int[] fx0 = x0;
        java.util.Arrays.sort(order, new java.util.Comparator<Integer>() { public int compare(Integer a, Integer b) { return fx0[a] - fx0[b]; } });
        int[] cp = new int[nc];
        for (int i = 0; i < nc; i++) cp[i] = i;
        for (int a = 0; a < nc; a++) {
            int ra = order[a], ha = y1[ra] - y0[ra] + 1;
            for (int b = a + 1; b < nc; b++) {
                int rb = order[b];
                if (x0[rb] - x1[ra] > 1.2 * ha) break;          // sorted by left edge: the rest are further away
                int hb = y1[rb] - y0[rb] + 1;
                if (hb * 10 < ha * 7 || hb * 7 > ha * 10) continue;
                int ov = Math.min(y1[ra], y1[rb]) - Math.max(y0[ra], y0[rb]);
                if (ov < 0.6 * Math.min(ha, hb)) continue;
                if (x0[rb] - x1[ra] < -0.2 * ha) continue;      // overlapping blobs are not neighbours
                double sa = stroke[ra], sb = stroke[rb];
                if (Math.max(sa, sb) > 1.8 * Math.min(sa, sb) + 0.5) continue;   // same pen
                int pa = find(cp, a), pb = find(cp, b);
                if (pa != pb) cp[Math.max(pa, pb)] = Math.min(pa, pb);
            }
        }
        // a chain counts as text when it has 4+ characters in a row: heights agree, tops or bottoms line up,
        // the gaps between letters are regular, and the row is long compared with its height
        int[] members = new int[nc];
        for (int a = 0; a < nc; a++) members[find(cp, a)]++;
        double[] sTop = new double[nc], sTop2 = new double[nc], sBot = new double[nc], sBot2 = new double[nc], sH = new double[nc], sH2 = new double[nc];
        int[] cx0 = new int[nc], cx1 = new int[nc], prevEnd = new int[nc];
        double[] gS = new double[nc], gS2 = new double[nc]; int[] gN = new int[nc];
        java.util.Arrays.fill(cx0, Integer.MAX_VALUE); java.util.Arrays.fill(cx1, -1); java.util.Arrays.fill(prevEnd, Integer.MIN_VALUE);
        for (int a = 0; a < nc; a++) {                      // in left-to-right order
            int r = order[a], c = find(cp, a); double hh = y1[r] - y0[r] + 1;
            sTop[c] += y0[r]; sTop2[c] += (double) y0[r] * y0[r]; sBot[c] += y1[r]; sBot2[c] += (double) y1[r] * y1[r]; sH[c] += hh; sH2[c] += hh * hh;
            cx0[c] = Math.min(cx0[c], x0[r]); cx1[c] = Math.max(cx1[c], x1[r]);
            if (prevEnd[c] != Integer.MIN_VALUE) { double g = Math.max(0, x0[r] - prevEnd[c]); gS[c] += g; gS2[c] += g * g; gN[c]++; }
            prevEnd[c] = Math.max(prevEnd[c], x1[r]);
        }
        boolean[] good = new boolean[nc];
        for (int c = 0; c < nc; c++) {
            int k = members[c];
            if (k < 4) continue;
            double mh = sH[c] / k, sdH = Math.sqrt(Math.max(0, sH2[c] / k - mh * mh));
            double sdT = Math.sqrt(Math.max(0, sTop2[c] / k - (sTop[c] / k) * (sTop[c] / k)));
            double sdB = Math.sqrt(Math.max(0, sBot2[c] / k - (sBot[c] / k) * (sBot[c] / k)));
            double mg = gN[c] > 0 ? gS[c] / gN[c] : 0, sdG = gN[c] > 0 ? Math.sqrt(Math.max(0, gS2[c] / gN[c] - mg * mg)) : 0;
            good[c] = sdH <= 0.2 * mh && Math.min(sdT, sdB) <= 0.15 * mh && sdG <= 0.45 * mh && (cx1[c] - cx0[c] + 1) >= 2.5 * mh;
            if (good[c] && mh >= 0.03 * h) s.bigText = Math.max(s.bigText, (cx1[c] - cx0[c] + 1) / (double) w);
        }
        int cnt = 0; long ar = 0;
        int[] hs = new int[nc]; int nh = 0;
        boolean[] row = new boolean[h];
        for (int a = 0; a < nc; a++) if (good[find(cp, a)]) {
            int r = order[a]; cnt++;
            ar += (long) (x1[r] - x0[r] + 1) * (y1[r] - y0[r] + 1);
            hs[nh++] = y1[r] - y0[r] + 1;
            row[(y0[r] + y1[r]) / 2] = true;
        }
        s.textBlobs = cnt;
        s.textArea = ar / (double) (w * h);
        if (nh > 0) { java.util.Arrays.sort(hs, 0, nh); s.textHeight = hs[nh / 2] / (double) h; }
        int lines = 0, gap = Math.max(2, nh > 0 ? hs[nh / 2] / 2 : 4), last = -1000;
        for (int y = 0; y < h; y++) if (row[y]) { if (y - last > gap) lines++; last = y; }
        s.textLines = lines;
    }

    private static int find(int[] p, int x) {
        while (p[x] != x) { p[x] = p[p[x]]; x = p[x]; }
        return x;
    }
}
