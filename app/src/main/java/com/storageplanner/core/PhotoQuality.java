package com.storageplanner.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Which of several near-identical shots is the better one. Measured per photo:
 *  - faces found (by the phone's face detector) and, for each face, whether the eyes are open (EyeState);
 *  - face sharpness (Laplacian variance inside each face) and face brightness;
 *  - overall sharpness and exposure (blown highlights, crushed shadows, overall brightness).
 * Shots are then ranked inside their group, so "sharper" or "brighter" is always relative to the others.
 */
public final class PhotoQuality {
    private PhotoQuality() {}

    /**
     * Detector score needed to count a face. YuNet scores: real faces 0.8-0.95 even when small or tilted; at 0.7
     * the test pictures without faces gave 1 false face in 271 (0.5 gave 13) and no real face was lost.
     */
    public static final double MIN_FACE_CONFIDENCE = 0.7;
    /** A face whose eyes are closer together than this share of the biggest face's is a bystander and not counted. */
    public static final double BACKGROUND = 0.3;
    /** A face whose two eyes average below this is reported as "eyes closed". */
    public static final double OPEN = 0.5;
    static final int FACE_FIELDS = 5;

    /** Measurements of one photo (all scale-free, so they survive saving and reloading). */
    public static final class Q {
        public int faces;                  // faces counted
        public int openFaces;              // faces with eyes open
        public double eyes = -1;           // mean eyes-open probability over faces; -1 = no faces
        public double faceSharp = -1;      // mean Laplacian variance inside the faces; -1 = no faces
        public double faceLuma = -1;       // mean face brightness 0..255; -1 = no faces
        public double sharp = -1;          // overall sharpness (same measure as ImageFeatures.sharpness)
        public double meanLuma;            // 0..255
        public double clipHi, clipLo;      // share of pixels blown out (>= 250) / crushed (<= 5)
        /** per face: x, y (fractions of width/height, point between the eyes), eye distance (fraction of width),
         *  eyes-open probability, detector confidence */
        public float[] face = new float[0];

        public double exposure() { return PhotoQuality.exposure(this); }
    }

    /**
     * Measure a photo. luma: 8-bit luma, w x h. faces: the detector's output in the same pixel space,
     * 4 values per face {x between the eyes, y, eye distance, confidence}; may be null.
     */
    public static Q analyse(int[] luma, int w, int h, float[] faces) {
        Q q = new Q();
        long[] hist = new long[256];
        for (int v : luma) hist[Math.max(0, Math.min(255, v))]++;
        double n = luma.length, sum = 0, hi = 0, lo = 0;
        for (int i = 0; i < 256; i++) {
            sum += i * (double) hist[i];
            if (i >= 250) hi += hist[i];
            if (i <= 5) lo += hist[i];
        }
        q.meanLuma = sum / n; q.clipHi = hi / n; q.clipLo = lo / n;
        q.sharp = sharpness(luma, w, h);
        List<float[]> kept = new ArrayList<float[]>();
        double eyes = 0, fs = 0, fl = 0;
        double biggest = 0;                                     // people far in the background don't decide the shot
        if (faces != null)
            for (int i = 0; i + Faces.F - 1 < faces.length; i += Faces.F) if (faces[i + 3] >= MIN_FACE_CONFIDENCE) biggest = Math.max(biggest, faces[i + 2]);
        if (faces != null)
            for (int i = 0; i + Faces.F - 1 < faces.length; i += Faces.F) {
                double mx = faces[i], my = faces[i + 1], d = faces[i + 2], conf = faces[i + 3];
                if (conf < MIN_FACE_CONFIDENCE || d < 6 || d < BACKGROUND * biggest || mx < 0 || my < 0 || mx >= w || my >= h) continue;
                double p = EyeState.openProbability(luma, w, h, faces[i + 4], faces[i + 5], faces[i + 6], faces[i + 7]);
                double[] st = faceStats(luma, w, h, mx, my, d);
                eyes += p; fs += st[0]; fl += st[1];
                if (p >= OPEN) q.openFaces++;
                kept.add(new float[]{(float) (mx / w), (float) (my / h), (float) (d / w), (float) p, (float) conf});
            }
        q.faces = kept.size();
        if (q.faces > 0) { q.eyes = eyes / q.faces; q.faceSharp = fs / q.faces; q.faceLuma = fl / q.faces; }
        q.face = new float[q.faces * FACE_FIELDS];
        for (int i = 0; i < q.faces; i++) System.arraycopy(kept.get(i), 0, q.face, i * FACE_FIELDS, FACE_FIELDS);
        return q;
    }

    /** Face box from the eye position: 2 eye-distances wide, from 0.9 above to 1.4 below the eyes. */
    static int[] faceBox(int w, int h, double mx, double my, double d) {
        int x0 = (int) Math.max(1, Math.floor(mx - d)), x1 = (int) Math.min(w - 2, Math.ceil(mx + d));
        int y0 = (int) Math.max(1, Math.floor(my - 0.9 * d)), y1 = (int) Math.min(h - 2, Math.ceil(my + 1.4 * d));
        return new int[]{x0, y0, x1, y1};
    }

    /**
     * Focus that does not depend on brightness or contrast: per 64 px tile, Laplacian variance divided by the
     * tile's own variance, over tiles with real texture (variance at least 10% of the median tile's); the 90th
     * percentile. A brighter or darker copy of the same photo scores about the same, a blurred one lower, and
     * flat sky or walls (mostly sensor noise) are ignored.
     */
    public static double sharpness(int[] l, int w, int h) {
        int tw = w / 64, th = h / 64;
        if (tw == 0 || th == 0) return -1;
        double[] lap = new double[tw * th], var = new double[tw * th];
        for (int ty = 0; ty < th; ty++)
            for (int tx = 0; tx < tw; tx++) {
                double[] st = boxStats(l, w, h, tx * 64, ty * 64, tx * 64 + 63, ty * 64 + 63);
                lap[ty * tw + tx] = st[0]; var[ty * tw + tx] = st[2];
            }
        double[] sv = var.clone(); java.util.Arrays.sort(sv);
        double min = Math.max(4, 0.1 * sv[sv.length / 2]);
        double[] v = new double[lap.length]; int n = 0;
        for (int i = 0; i < lap.length; i++) if (var[i] >= min) v[n++] = lap[i] / var[i];
        if (n == 0) return 0;
        v = java.util.Arrays.copyOf(v, n);
        java.util.Arrays.sort(v);
        return v[(int) Math.min(n - 1, Math.floor(0.9 * (n - 1) + 0.5))];
    }

    /** {Laplacian variance, mean luma, luma variance} in a box (inclusive, clamped away from the border). */
    static double[] boxStats(int[] l, int w, int h, int x0, int y0, int x1, int y1) {
        x0 = Math.max(1, x0); y0 = Math.max(1, y0); x1 = Math.min(w - 2, x1); y1 = Math.min(h - 2, y1);
        double s = 0, sq = 0, lum = 0, lsq = 0; int n = 0;
        for (int y = y0; y <= y1; y++)
            for (int x = x0; x <= x1; x++) {
                int i = y * w + x;
                double lap = l[i - 1] + l[i + 1] + l[i - w] + l[i + w] - 4.0 * l[i];
                s += lap; sq += lap * lap; lum += l[i]; lsq += (double) l[i] * l[i]; n++;
            }
        if (n == 0) return new double[]{0, 0, 0};
        double m = s / n, ml = lum / n;
        return new double[]{sq / n - m * m, ml, Math.max(0, lsq / n - ml * ml)};
    }

    /** {focus (Laplacian variance / variance), mean luma} inside the face box. */
    static double[] faceStats(int[] l, int w, int h, double mx, double my, double d) {
        int[] b = faceBox(w, h, mx, my, d);
        double[] st = boxStats(l, w, h, b[0], b[1], b[2], b[3]);
        return new double[]{st[2] > 4 ? st[0] / st[2] : 0, st[1]};
    }

    /**
     * 0..1, higher = better exposed. Blown-out highlights cost the most (they can't be recovered), then crushed
     * shadows; overall brightness only counts when far from mid-grey, and a face that is much too dark or too
     * bright counts too.
     */
    public static double exposure(Q q) {
        double bad = 8 * Math.max(0, q.clipHi - 0.005) + 6 * Math.max(0, q.clipLo - 0.01)
                + (q.meanLuma < 118 ? 1.2 : 0.6) * Math.max(0, Math.abs(q.meanLuma - 118) - 40) / 118;   // too dark looks worse than too bright
        if (q.faceLuma >= 0) bad += 0.8 * Math.max(0, Math.abs(q.faceLuma - 140) - 25) / 115;
        return 1 / (1 + bad);
    }

    /** How surely a face's eyes are open, 0..1 (0 below p=0.2, 1 above p=0.8). */
    static double openness(double p) { return Math.max(0, Math.min(1, (p - 0.2) / 0.6)); }

    /**
     * Score of each shot within its group (higher = better). Every person blinking or missing from the shot
     * costs 3 points; focus (faces first) is worth up to 1.5 and exposure up to 1, so a blink outweighs any
     * focus or light difference between near-identical shots.
     */
    public static double[] scores(List<Q> g) {
        double[][] t = terms(g);
        double[] s = new double[g.size()];
        for (int i = 0; i < s.length; i++) for (double x : t[i]) s[i] += x;
        return s;
    }

    /** Names of the parts of a score, in the order terms() returns them. */
    public static final String[] TERMS = {"eyes open (3 per face)", "people missing or blinking (-3 each)", "face sharpness", "overall sharpness", "lighting"};

    /** The parts that add up to each shot's score (see TERMS); used by scores() and shown by the Algorithm Lab. */
    public static double[][] terms(List<Q> g) {
        int maxFaces = 0; double maxFS = 0, maxS = 0;
        for (Q q : g) { maxFaces = Math.max(maxFaces, q.faces); maxFS = Math.max(maxFS, q.faceSharp); maxS = Math.max(maxS, q.sharp); }
        double[][] t = new double[g.size()][5];
        for (int i = 0; i < g.size(); i++) {
            Q q = g.get(i);
            double rs = maxS > 0 && q.sharp > 0 ? q.sharp / maxS : 0;
            if (maxFaces > 0) {
                for (int k = 0; k < q.faces; k++) t[i][0] += 3.0 * openness(q.face[k * FACE_FIELDS + 3]);
                t[i][1] = -3.0 * maxFaces;                          // people missing from this shot count as blinking
                t[i][2] = 1.0 * (q.faceSharp > 0 && maxFS > 0 ? q.faceSharp / maxFS : 0);
                t[i][3] = 0.5 * rs;
            } else t[i][3] = 1.5 * rs;
            t[i][4] = 1.0 * exposure(q);
        }
        return t;
    }

    /** Index of the best shot. */
    public static int best(List<Q> g) {
        double[] s = scores(g);
        int b = 0;
        for (int i = 1; i < s.length; i++) if (s[i] > s[b]) b = i;
        return b;
    }

    /** Short reasons why `a` is better than `b`, most important first; empty if they are practically equal. */
    public static List<String> reasons(Q a, Q b) {
        List<String> r = new ArrayList<String>();
        if (a == null || b == null) return r;
        double oa = 0, ob = 0;
        for (int k = 0; k < a.faces; k++) oa += openness(a.face[k * FACE_FIELDS + 3]);
        for (int k = 0; k < b.faces; k++) ob += openness(b.face[k * FACE_FIELDS + 3]);
        if (oa - ob >= 0.4) {
            int blink = b.faces - b.openFaces;
            if (blink > 0) r.add(a.faces <= 1 ? "Eyes open" : "Eyes open (" + blink + (blink == 1 ? " person blinks" : " people blink") + " in the other)");
            else if (a.faces <= b.faces) r.add("Eyes open wider");
        }
        if (a.faces > b.faces) r.add("More faces visible (" + a.faces + " vs " + b.faces + ")");
        if (a.faceSharp > 0 && b.faceSharp >= 0 && a.faceSharp > 1.25 * b.faceSharp) r.add("Face sharper");
        double ea = exposure(a), eb = exposure(b);
        if (ea - eb > 0.06) {
            if (b.clipHi - a.clipHi > 0.01) r.add("Better lighting (fewer blown-out areas)");
            else if (a.faceLuma >= 0 && b.faceLuma >= 0 && Math.abs(b.faceLuma - 140) - Math.abs(a.faceLuma - 140) > 15)
                r.add(b.faceLuma < a.faceLuma ? "Better lighting (face less dark)" : "Better lighting (face less washed out)");
            else if (b.clipLo - a.clipLo > 0.01 || b.meanLuma < a.meanLuma) r.add("Better lighting (less dark)");
            else r.add("Better lighting");
        }
        if (a.sharp > 0 && b.sharp >= 0 && a.sharp > 1.25 * b.sharp && !r.contains("Face sharper")) r.add("Sharper");
        return r;
    }

    /** Facts about one photo for its caption ("Eyes open", "2 faces, 1 blinking", "No faces"). */
    public static String eyesLabel(Q q) {
        if (q == null) return null;
        if (q.faces == 0) return "No faces found";
        int closed = q.faces - q.openFaces;
        if (q.faces == 1) return closed == 0 ? "Eyes open" : "Eyes closed";
        return closed == 0 ? q.faces + " faces, eyes open" : q.faces + " faces, " + closed + " blinking";
    }
}
