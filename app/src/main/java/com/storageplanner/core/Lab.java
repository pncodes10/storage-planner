package com.storageplanner.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Algorithm Lab: runs each picture algorithm of the app on pictures the user picks, step by step, and writes
 * every number to a log. It calls the same code as a scan (same decode sizes, same thresholds), so what the
 * Lab shows is what the scan decides. Pure Java on top of Platform, so it is also tested on the desktop.
 */
public final class Lab {
    private Lab() {}

    /** Where the Lab writes its lines (the screen and the log file). */
    public interface Out { void line(String s); }

    /** A picked picture. mtime is the time the scan would use (the file's modified time). */
    public static final class Pic {
        public final String path, name;
        public final long size, mtime;
        public String area = "camera";        // how to treat it for sorting: camera, screenshot, wa_image, download
        public Pic(String path, String name, long size, long mtime) { this.path = path; this.name = name; this.size = size; this.mtime = mtime; }
    }

    /** What a run found that the screen can draw: faces (PhotoQuality.Q.face layout) and eye patches. */
    public static final class Shown {
        public float[] faces;                                  // x, y, eye distance (fractions), eyes-open p, confidence
        public final List<int[]> patches = new ArrayList<int[]>();   // ARGB, EyeState.PW x EyeState.PH each
        public final List<String> patchLabels = new ArrayList<String>();
    }

    static final class Img { int[] px, l; int w, h, ow, oh; long ms; }

    static Img load(Platform pf, String path, int edge, Out o) {
        long t0 = System.nanoTime();
        int[] d = new int[4];
        int[] px = pf.argb(path, edge, d);
        if (px == null) { o.line("  ! could not decode the picture at " + edge + " px"); return null; }
        Img m = new Img();
        m.px = px; m.l = Sorter.luma(px); m.w = d[2]; m.h = d[3]; m.ow = d[0]; m.oh = d[1];
        m.ms = (System.nanoTime() - t0) / 1000000;
        o.line(f("  decoded %d x %d (original %d x %d, upright) in %d ms", m.w, m.h, m.ow, m.oh, m.ms));
        return m;
    }

    static String f(String fmt, Object... a) { return String.format(Locale.US, fmt, a); }

    // ------------------------------------------------------------------ faces
    /** Face detection exactly as the photo-quality check runs it (1280 px, the phone's detector, confidence >= 0.4). */
    public static Shown faces(Platform pf, Pic p, Out o) {
        o.line("FACE DETECTION (as used to pick the best shot) — " + p.name);
        Shown sh = new Shown();
        Img m = load(pf, p.path, Scanner.QUALITY_EDGE, o);
        if (m == null) return sh;
        long t0 = System.nanoTime();
        float[] raw = pf.faces(p.path, m.px, m.w, m.h);
        long ms = (System.nanoTime() - t0) / 1000000;
        if (raw == null) { o.line("  ! the face detector is not available on this phone"); return sh; }
        o.line(f("  detector returned %d face(s) in %d ms", raw.length / 4, ms));
        List<float[]> kept = new ArrayList<float[]>();
        for (int i = 0; i + 3 < raw.length; i += 4) {
            double mx = raw[i], my = raw[i + 1], d = raw[i + 2], c = raw[i + 3];
            boolean ok = c >= PhotoQuality.MIN_FACE_CONFIDENCE && d >= 6 && mx >= 0 && my >= 0 && mx < m.w && my < m.h;
            o.line(f("  face %d: between the eyes at (%.0f, %.0f) = (%.1f%%, %.1f%%), eye distance %.1f px (%.1f%% of width), confidence %.2f -> %s",
                    i / 4 + 1, mx, my, 100 * mx / m.w, 100 * my / m.h, d, 100 * d / m.w, c,
                    ok ? "counted" : c < PhotoQuality.MIN_FACE_CONFIDENCE ? "ignored (confidence below " + PhotoQuality.MIN_FACE_CONFIDENCE + ")" : "ignored (too small or outside)"));
            if (ok) kept.add(new float[]{(float) (mx / m.w), (float) (my / m.h), (float) (d / m.w), (float) EyeState.openProbability(m.l, m.w, m.h, mx, my, d), (float) c});
        }
        if (kept.isEmpty()) o.line("  => no face counted: this picture is judged on sharpness and light only");
        sh.faces = flat(kept);
        return sh;
    }

    /** The same detector on other sizes and on the picture turned a little: shows whether size or head tilt is the problem. */
    public static Shown facesDetailed(Platform pf, Pic p, Out o) {
        o.line("FACE DETECTION, SIZES AND TILTS — " + p.name);
        Shown best = new Shown();
        int bestN = -1;
        for (int edge : new int[]{640, 1280, 2048}) {
            Img m = load(pf, p.path, edge, o);
            if (m == null) continue;
            for (int deg : edge == 1280 ? new int[]{0, -20, -10, 10, 20} : new int[]{0}) {
                int[] px = deg == 0 ? m.px : rotate(m.px, m.w, m.h, deg);
                long t0 = System.nanoTime();
                float[] raw = pf.faces(p.path, px, m.w, m.h);
                long ms = (System.nanoTime() - t0) / 1000000;
                if (raw == null) { o.line("  ! the face detector is not available on this phone"); return best; }
                StringBuilder b = new StringBuilder();
                int n = 0;
                for (int i = 0; i + 3 < raw.length; i += 4) {
                    if (raw[i + 3] >= PhotoQuality.MIN_FACE_CONFIDENCE) n++;
                    b.append(f(" [eyes %.0f px apart, conf %.2f]", raw[i + 2], raw[i + 3]));
                }
                o.line(f("  up to %4d px, turned %+3d°: %d face(s) counted, %d ms%s%s", edge, deg, n, ms, b, edge == 1280 && deg == 0 ? "   <- what the app uses" : ""));
                if (deg == 0 && n > bestN) {                        // overlay: faces found without turning, at the size that found most
                    bestN = n;
                    List<float[]> kept = new ArrayList<float[]>();
                    for (int i = 0; i + 3 < raw.length; i += 4) if (raw[i + 3] >= PhotoQuality.MIN_FACE_CONFIDENCE)
                        kept.add(new float[]{raw[i] / m.w, raw[i + 1] / m.h, raw[i + 2] / m.w, (float) EyeState.openProbability(m.l, m.w, m.h, raw[i], raw[i + 1], raw[i + 2]), raw[i + 3]});
                    best.faces = flat(kept);
                }
            }
        }
        return best;
    }

    /** Turns a picture by deg degrees around its centre (same size, edges filled grey). */
    static int[] rotate(int[] px, int w, int h, double deg) {
        double a = Math.toRadians(deg), c = Math.cos(a), s = Math.sin(a), cx = w / 2.0, cy = h / 2.0;
        int[] o = new int[w * h];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                double sx = c * (x - cx) + s * (y - cy) + cx, sy = -s * (x - cx) + c * (y - cy) + cy;
                int ix = (int) Math.round(sx), iy = (int) Math.round(sy);
                o[y * w + x] = ix >= 0 && iy >= 0 && ix < w && iy < h ? px[iy * w + ix] : 0xFF808080;
            }
        return o;
    }

    // ------------------------------------------------------------------ eyes
    /** Eyes open / closed for every counted face, with each eye, each small shift, and the patches the model saw. */
    public static Shown eyes(Platform pf, Pic p, Out o) {
        o.line("EYES OPEN OR CLOSED — " + p.name);
        Shown sh = new Shown();
        Img m = load(pf, p.path, Scanner.QUALITY_EDGE, o);
        if (m == null) return sh;
        float[] raw = pf.faces(p.path, m.px, m.w, m.h);
        if (raw == null) { o.line("  ! the face detector is not available on this phone"); return sh; }
        List<float[]> kept = new ArrayList<float[]>();
        int k = 0;
        for (int i = 0; i + 3 < raw.length; i += 4) {
            double mx = raw[i], my = raw[i + 1], d = raw[i + 2], c = raw[i + 3];
            if (c < PhotoQuality.MIN_FACE_CONFIDENCE || d < 6) continue;
            k++;
            double sum = 0; int n = 0;
            StringBuilder shifts = new StringBuilder();
            for (double[] s : EyeState.SHIFTS) {
                double x = mx + s[0] * d, y = my + s[1] * d, dd = d * s[2];
                double l = EyeState.eye(m.l, m.w, m.h, x - dd / 2, y, dd, false), r = EyeState.eye(m.l, m.w, m.h, x + dd / 2, y, dd, true);
                sum += l + r; n += 2;
                shifts.append(f(" %.2f/%.2f", l, r));
            }
            double pOpen = sum / n;
            o.line(f("  face %d (eyes %.0f px apart): eyes-open probability %.2f -> %s", k, d, pOpen,
                    pOpen >= PhotoQuality.OPEN ? "EYES OPEN" : "EYES CLOSED"));
            o.line("    left/right eye at the 7 positions tried (centre, up, down, left, right, smaller, larger):" + shifts);
            o.line(f("    counts towards the score as %.2f of a face (0 below p=0.2, full above p=0.8)", PhotoQuality.openness(pOpen)));
            for (int e = 0; e < 2; e++) {
                double[] patch = EyeState.patch(m.l, m.w, m.h, mx + (e == 0 ? -d / 2 : d / 2), my, d, e == 1);
                sh.patches.add(gray(patch));
                sh.patchLabels.add("face " + k + (e == 0 ? " left eye " : " right eye ")
                        + f("%.2f", EyeState.eye(m.l, m.w, m.h, mx + (e == 0 ? -d / 2 : d / 2), my, d, e == 1)));
            }
            kept.add(new float[]{(float) (mx / m.w), (float) (my / m.h), (float) (d / m.w), (float) pOpen, (float) c});
        }
        if (k == 0) o.line("  => no face counted, so eyes cannot be checked");
        sh.faces = flat(kept);
        return sh;
    }

    static int[] gray(double[] p) {
        int[] o = new int[p.length];
        for (int i = 0; i < p.length; i++) { int v = (int) Math.max(0, Math.min(255, Math.round(p[i]))); o[i] = 0xFF000000 | v << 16 | v << 8 | v; }
        return o;
    }

    // ------------------------------------------------------------------ sharpness and light
    public static Shown sharpness(Platform pf, Pic p, Out o) {
        o.line("SHARPNESS — " + p.name);
        Shown sh = new Shown();
        Img m = load(pf, p.path, Scanner.QUALITY_EDGE, o);
        if (m == null) return sh;
        o.line(f("  focus (used to compare near-identical shots): %.3f   [90th percentile of Laplacian variance / variance, 64 px tiles, 1280 px]", PhotoQuality.sharpness(m.l, m.w, m.h)));
        Img k = load(pf, p.path, Sorter.EDGE, o);
        if (k != null) {
            o.line(f("  blur score (used for 'possibly blurry' and when shots were not measured): %.1f   [90th percentile Laplacian variance, 1024 px]",
                    ImageFeatures.sharpness(k.l, k.w, k.h)));
            o.line("  'possibly blurry' needs a blur score below " + UnitBuilder.BLUR_REL + " x the median of all your camera photos");
        }
        float[] raw = pf.faces(p.path, m.px, m.w, m.h);
        List<float[]> kept = new ArrayList<float[]>();
        if (raw != null) for (int i = 0; i + 3 < raw.length; i += 4) if (raw[i + 3] >= PhotoQuality.MIN_FACE_CONFIDENCE && raw[i + 2] >= 6) {
            double[] st = PhotoQuality.faceStats(m.l, m.w, m.h, raw[i], raw[i + 1], raw[i + 2]);
            o.line(f("  face %d focus: %.3f (brightness %.0f)", kept.size() + 1, st[0], st[1]));
            kept.add(new float[]{raw[i] / m.w, raw[i + 1] / m.h, raw[i + 2] / m.w, (float) EyeState.openProbability(m.l, m.w, m.h, raw[i], raw[i + 1], raw[i + 2]), raw[i + 3]});
        }
        if (kept.isEmpty()) o.line("  no face counted: only the whole picture's focus is used");
        sh.faces = flat(kept);
        return sh;
    }

    public static Shown lighting(Platform pf, Pic p, Out o) {
        o.line("LIGHTING — " + p.name);
        Img m = load(pf, p.path, Scanner.QUALITY_EDGE, o);
        if (m == null) return new Shown();
        PhotoQuality.Q q = PhotoQuality.analyse(m.l, m.w, m.h, pf.faces(p.path, m.px, m.w, m.h));
        double hi = 8 * Math.max(0, q.clipHi - 0.005), lo = 6 * Math.max(0, q.clipLo - 0.01),
               mean = (q.meanLuma < 118 ? 1.2 : 0.6) * Math.max(0, Math.abs(q.meanLuma - 118) - 40) / 118,
               face = q.faceLuma >= 0 ? 0.8 * Math.max(0, Math.abs(q.faceLuma - 140) - 25) / 115 : 0;
        o.line(f("  average brightness %.0f / 255; blown out (>=250) %.2f%%; black (<=5) %.2f%%; faces' brightness %s",
                q.meanLuma, 100 * q.clipHi, 100 * q.clipLo, q.faceLuma >= 0 ? f("%.0f", q.faceLuma) : "no face"));
        o.line(f("  penalties: blown out %.3f + black %.3f + too dark/bright %.3f + face %.3f = %.3f", hi, lo, mean, face, hi + lo + mean + face));
        o.line(f("  lighting score = 1 / (1 + penalties) = %.3f   (1 = perfect)", q.exposure()));
        Shown sh = new Shown();
        sh.faces = q.faces > 0 ? q.face : null;
        return sh;
    }

    // ------------------------------------------------------------------ the full score and the best shot
    public static PhotoQuality.Q quality(Platform pf, Pic p, Out o) {
        Img m = load(pf, p.path, Scanner.QUALITY_EDGE, o);
        if (m == null) return null;
        long t0 = System.nanoTime();
        PhotoQuality.Q q = PhotoQuality.analyse(m.l, m.w, m.h, pf.faces(p.path, m.px, m.w, m.h));
        o.line(f("  measured in %d ms: faces %d (eyes open in %d), eyes %s, face focus %s, focus %.3f, lighting %.3f",
                (System.nanoTime() - t0) / 1000000, q.faces, q.openFaces, q.eyes < 0 ? "-" : f("%.2f", q.eyes),
                q.faceSharp < 0 ? "-" : f("%.3f", q.faceSharp), q.sharp, q.exposure()));
        return q;
    }

    /** Ranks the picked pictures as one group of near-identical shots, with every part of every score. */
    public static Shown best(Platform pf, List<Pic> pics, Out o) {
        o.line("BEST SHOT AMONG " + pics.size() + " PICTURES");
        Shown sh = new Shown();
        if (pics.size() < 2) { o.line("  pick at least two pictures"); return sh; }
        List<PhotoQuality.Q> qs = new ArrayList<PhotoQuality.Q>();
        for (Pic p : pics) {
            o.line(" " + p.name);
            PhotoQuality.Q q = quality(pf, p, o);
            if (q == null) { o.line("  ! cannot rank: this picture could not be measured (the app would then keep the one with the highest blur score)"); return sh; }
            qs.add(q);
        }
        double[][] t = PhotoQuality.terms(qs);
        double[] s = PhotoQuality.scores(qs);
        int b = PhotoQuality.best(qs);
        o.line("  score parts: " + String.join(" | ", PhotoQuality.TERMS));
        for (int i = 0; i < pics.size(); i++)
            o.line(f("  %s %-28s %6.3f = %6.3f %6.3f %6.3f %6.3f %6.3f", i == b ? "★" : " ", cut(pics.get(i).name, 28), s[i], t[i][0], t[i][1], t[i][2], t[i][3], t[i][4]));
        o.line("  => keep " + pics.get(b).name);
        for (int i = 0; i < pics.size(); i++) if (i != b) {
            List<String> r = PhotoQuality.reasons(qs.get(b), qs.get(i));
            o.line("     vs " + pics.get(i).name + ": " + (r.isEmpty() ? "practically the same shot" : String.join(", ", r)));
        }
        int maxFaces = 0; for (PhotoQuality.Q q : qs) maxFaces = Math.max(maxFaces, q.faces);
        if (maxFaces == 0) o.line("  note: no face was counted in any of them, so only focus and lighting decided");
        sh.faces = qs.get(b).faces > 0 ? qs.get(b).face : null;
        return sh;
    }

    // ------------------------------------------------------------------ duplicates
    /** Would the scan put these pictures in one group of near-identical shots? Pairwise, with the reasons. */
    public static void nearDuplicates(Platform pf, List<Pic> pics, Out o) {
        o.line("NEAR-DUPLICATE CHECK (" + pics.size() + " pictures)");
        if (pics.size() < 2) { o.line("  pick at least two pictures"); return; }
        long[] h = new long[pics.size()]; int[][] dims = new int[pics.size()][];
        for (int i = 0; i < pics.size(); i++) {
            o.line(" " + pics.get(i).name);
            Img m = load(pf, pics.get(i).path, Sorter.EDGE, o);
            if (m == null) return;
            h[i] = ImageFeatures.phash(m.l, m.w, m.h);
            dims[i] = new int[]{m.ow, m.oh};
            o.line(f("  perceptual hash %016x", h[i]));
        }
        o.line("  rule: same size, taken within " + UnitBuilder.BURST_WINDOW_MS / 60000 + " min, hash differs in <= " + UnitBuilder.NEAR_DUP_BITS
                + " of 63 bits (WhatsApp copy of a camera photo: <= " + UnitBuilder.SENT_MATCH_BITS + " bits, same shape)");
        for (int i = 0; i < pics.size(); i++)
            for (int j = i + 1; j < pics.size(); j++) {
                int bits = ImageFeatures.hamming(h[i], h[j]);
                long dt = Math.abs(pics.get(i).mtime - pics.get(j).mtime);
                boolean size = dims[i][0] == dims[j][0] && dims[i][1] == dims[j][1], time = dt <= UnitBuilder.BURST_WINDOW_MS, look = bits <= UnitBuilder.NEAR_DUP_BITS;
                o.line(f("  %s ~ %s: %d bits apart (%s), %s apart in time (%s), size %s -> %s", cut(pics.get(i).name, 22), cut(pics.get(j).name, 22), bits,
                        look ? "looks the same" : "looks different", dur(dt), time ? "ok" : "too far", size ? "same" : "different",
                        size && time && look ? "SAME GROUP" : "not grouped directly"));
            }
        o.line("  note: the scan links chains — A~B and B~C put A, B and C in one group even if A and C differ more");
    }

    public static void exactDuplicates(List<Pic> pics, Out o) {
        o.line("EXACT DUPLICATE CHECK (" + pics.size() + " pictures)");
        String[] hs = new String[pics.size()];
        for (int i = 0; i < pics.size(); i++) {
            long t0 = System.nanoTime();
            hs[i] = Scanner.fullHash(pics.get(i).path);
            o.line(f("  %s: %d bytes, SHA-256 %s (%d ms)", pics.get(i).name, pics.get(i).size, hs[i], (System.nanoTime() - t0) / 1000000));
        }
        boolean any = false;
        for (int i = 0; i < pics.size(); i++) for (int j = i + 1; j < pics.size(); j++)
            if (hs[i] != null && hs[i].equals(hs[j])) { o.line("  IDENTICAL: " + pics.get(i).name + " = " + pics.get(j).name); any = true; }
        if (!any) o.line("  no two pictures are byte-for-byte identical");
    }

    // ------------------------------------------------------------------ sorting, codes, text
    public static Shown category(Platform pf, Pic p, long now, Out o) {
        o.line("PICTURE CATEGORY — " + p.name + " (treated as " + p.area + ")");
        Shown sh = new Shown();
        FileItem f = new FileItem(p.area.equals("screenshot") ? "Pictures/Screenshots/" + p.name : "Lab/" + p.name, p.path, p.size, p.mtime);
        f.area = p.area; f.type = p.name.toLowerCase(Locale.ROOT).endsWith(".png") ? "png" : "jpeg";
        Img m = load(pf, p.path, Sorter.EDGE, o);
        if (m == null) return sh;
        int[] d = {m.ow, m.oh, m.w, m.h};
        Sorter.Prep prep = Sorter.measure(pf, f, m.px, d);
        ImageSignals.S s = prep.s;
        o.line(f("  brightness %.0f, contrast %.1f, colourful %.1f, dark %.1f%%, paper-like %.1f%%, flat (screen) %.1f%%, focus %.3f",
                s.meanLuma, s.contrast, s.colorful, 100 * s.darkFrac, 100 * s.paper, 100 * s.flat, s.sharp));
        o.line(f("  text-like marks %d in %d lines covering %.2f%% (letter height %.1f%% of the picture, big lettering %.0f%% of the width)",
                s.textBlobs, s.textLines, 100 * s.textArea, 100 * s.textHeight, 100 * s.bigText));
        o.line("  code: " + (f.code == null ? "none" : f.code) + "; faces counted: " + (f.faces < 0 ? "not checked" : String.valueOf(f.faces)));
        boolean read = PhotoCategory.worthReading(f, s);
        o.line("  accidental shot? " + PhotoCategory.accidental(s) + "; worth reading the text? " + read);
        TextRules.Verdict v = null;
        if (read) v = readAndJudge(pf, f, s, prep.aspect, now, o);
        PhotoCategory.Result r = PhotoCategory.classify(f, s, f.code, v, f.faces);
        o.line("  => " + PhotoCategory.label(r.category) + (r.why.isEmpty() ? "" : "  (" + r.why + ")") + (r.date > 0 ? "; date " + Report.day(r.date) : ""));
        return sh;
    }

    static TextRules.Verdict readAndJudge(Platform pf, FileItem f, ImageSignals.S s, double aspect, long now, Out o) {
        int[] d = new int[4];
        int[] px = pf.argb(f.abs, Sorter.TEXT_EDGE, d);
        if (px == null) { o.line("  ! could not decode for reading"); return null; }
        long t0 = System.nanoTime();
        TextRules.Words w = pf.readText(f.abs, px, d[2], d[3]);
        long ms = (System.nanoTime() - t0) / 1000000;
        if (w == null) { o.line(f("  ! the text reader gave nothing (%d ms): not available or it failed", ms)); return null; }
        o.line(f("  text reader: %d words in %d ms at %d x %d; %d read confidently", w.text.size(), ms, d[2], d[3], TextRules.goodWords(w)));
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < w.text.size(); i++) b.append(w.text.get(i)).append('(').append(w.conf.get(i)).append(") ");
        o.line("  words (confidence): " + b);
        boolean chat = f.area.startsWith("wa_") || "telegram_media".equals(f.area);
        TextRules.Verdict v = TextRules.classify(w, aspect, s.colorful >= 15 && s.flat < 0.5, PhotoCategory.isScreenshot(f), chat, now);
        o.line("  text says: " + (v.kind == null ? "nothing specific" : PhotoCategory.label(v.kind)) + (v.why.isEmpty() ? "" : " — evidence " + v.why));
        return v;
    }

    public static void codes(Platform pf, Pic p, Out o) {
        o.line("QR CODE / BARCODE — " + p.name);
        for (int edge : new int[]{Sorter.EDGE, Sorter.BIG}) {
            Img m = load(pf, p.path, edge, o);
            if (m == null) continue;
            for (boolean thorough : new boolean[]{false, true}) {
                long t0 = System.nanoTime();
                String[] c = Codes.read(m.l, m.w, m.h, thorough);
                o.line(f("  up to %d px, %s search: %s (%d ms)%s", edge, thorough ? "thorough" : "quick", c == null ? "no code"
                        : c[0] + " \"" + cut(c[1], 120) + "\" -> " + Codes.describe(c[0], c[1]), (System.nanoTime() - t0) / 1000000,
                        edge == Sorter.EDGE ? "   <- the scan uses this size" : ""));
            }
        }
    }

    public static void text(Platform pf, Pic p, long now, Out o) {
        o.line("READ TEXT — " + p.name + " (treated as " + p.area + ")");
        FileItem f = new FileItem("Lab/" + p.name, p.path, p.size, p.mtime);
        f.area = p.area;
        Img m = load(pf, p.path, Sorter.EDGE, o);
        if (m == null) return;
        ImageSignals.S s = ImageSignals.measure(m.px, m.w, m.h);
        readAndJudge(pf, f, s, m.ow / (double) Math.max(1, m.oh), now, o);
        int[] d = new int[4];
        int[] small = pf.argb(p.path, Sorter.GREETING_EDGE, d);
        if (small != null) {
            TextRules.Words w2 = pf.readText(p.path, small, d[2], d[3]);
            if (w2 != null) o.line("  small copy (" + d[2] + " x " + d[3] + ", the greeting check): " + TextRules.clean(w2));
        }
    }

    // ------------------------------------------------------------------ helpers
    static float[] flat(List<float[]> l) {
        float[] o = new float[l.size() * PhotoQuality.FACE_FIELDS];
        for (int i = 0; i < l.size(); i++) System.arraycopy(l.get(i), 0, o, i * PhotoQuality.FACE_FIELDS, PhotoQuality.FACE_FIELDS);
        return o;
    }

    static String cut(String s, int n) { return s.length() <= n ? s : s.substring(0, n - 1) + "…"; }

    static String dur(long ms) {
        if (ms < 60000) return (ms / 1000) + " s";
        if (ms < 3600000) return (ms / 60000) + " min";
        if (ms < 172800000) return (ms / 3600000) + " h";
        return (ms / 86400000) + " days";
    }
}
