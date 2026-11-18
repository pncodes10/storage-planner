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
        public String rel, type;              // a picture on the phone itself (Try on my pictures): its real path and file type; null for picked copies
        public Pic(String path, String name, long size, long mtime) { this.path = path; this.name = name; this.size = size; this.mtime = mtime; }
    }

    /** What a run found that the screen can draw: faces (PhotoQuality.Q.face layout) and eye patches. */
    public static final class Shown {
        public float[] faces;                                  // x, y, eye distance (fractions), eyes-open p, confidence
        public final List<int[]> patches = new ArrayList<int[]>();   // ARGB, EyeState.PW x EyeState.PH each
        public final List<String> patchLabels = new ArrayList<String>();
        public FileItem file;                                  // "Picture category": the picture as the sorter left it
        public final List<Option> options = new ArrayList<Option>();   // "Rules": what the plan would suggest for it
        public final List<String> matched = new ArrayList<String>();   // "Rules": names of the rules that match
    }

    // ------------------------------------------------------------------ files the Lab makes of picked pictures
    // A picked picture is copied into the app's own storage as p<slot>.<ext>; the slots (0..max-1) are reused. These small
    // rules are here, not in the screen, so they are tested on the desktop.

    /** The slot in a copy's file name (p<slot>.<ext>); -1 if the path is not one of those copies. */
    public static int slotOf(String path) {
        String n = path.substring(path.lastIndexOf('/') + 1);
        int dot = n.indexOf('.');
        if (n.length() < 2 || n.charAt(0) != 'p' || dot < 2) return -1;
        try { return Integer.parseInt(n.substring(1, dot)); } catch (NumberFormatException e) { return -1; }
    }

    /** The lowest slot below max that no picked picture uses and nobody has reserved; -1 if all are taken. */
    public static int freeSlot(java.util.Collection<String> usedPaths, java.util.Collection<Integer> reserved, int max) {
        java.util.Set<Integer> taken = new java.util.HashSet<Integer>(reserved);
        for (String p : usedPaths) taken.add(slotOf(p));
        for (int i = 0; i < max; i++) if (!taken.contains(i)) return i;
        return -1;
    }

    /** File name of a slot's copy; the extension is kept (the decoder and the log show it) but only if it is plain letters and digits. */
    public static String slotName(int slot, String ext) {
        String e = ext == null ? "" : ext.toLowerCase(Locale.ROOT);
        if (!e.matches("[a-z0-9]{1,5}")) e = "jpg";
        return "p" + slot + "." + e;
    }

    static final class Img { int[] px, l; int w, h, ow, oh; long ms; }

    static Img load(Platform pf, Pic p, int edge, Out o) {
        long t0 = System.nanoTime();
        int[] d = new int[4];
        int[] px = decode(pf, p.path, p.size, edge, d, "the picture", o);
        if (px == null) return null;
        Img m = new Img();
        m.px = px; m.l = Sorter.luma(px); m.w = d[2]; m.h = d[3]; m.ow = d[0]; m.oh = d[1];
        m.ms = (System.nanoTime() - t0) / 1000000;
        o.line(f("  decoded %d x %d (original %d x %d, upright) in %d ms", m.w, m.h, m.ow, m.oh, m.ms));
        return m;
    }

    /**
     * The one place where the Lab decodes a picture, for every tool. Returns the ARGB pixels, or null after writing why
     * not: file facts, the first bytes, and what the phone's decoder said. A decode that only worked through a fallback
     * says so. pickedSize is the size the picker reported (-1 = unknown); what names the picture in the message.
     */
    static int[] decode(Platform pf, String path, long pickedSize, int edge, int[] dims, String what, Out o) {
        int[] px = null; Throwable err = null;
        try { px = pf.argb(path, edge, dims); } catch (Throwable t) { err = t; }
        if (px != null) {
            String note = pf.decodeNote();
            if (note != null) o.line("  note: " + note);
            return px;
        }
        o.line("  ! could not decode " + what + " at " + edge + " px");
        for (String l : whyNot(pf, path, pickedSize, err)) o.line("    " + l);
        return null;
    }

    /** Why a file did not decode, in plain lines for the log. */
    static List<String> whyNot(Platform pf, String path, long pickedSize, Throwable err) {
        List<String> r = new ArrayList<String>();
        java.io.File file = new java.io.File(path);
        r.add("file: " + path);
        if (!file.exists()) {
            r.add("the file is not there (missing or deleted)");
            if (path.contains("/cache/") || slotOf(path) >= 0) r.add("this was a copy the Lab made in the app's storage (Android deletes app cache files when storage runs low). Pick the picture again");
        } else if (file.isDirectory()) {
            r.add("this is a folder, not a file");
        } else if (!file.canRead()) {
            r.add("the file exists (" + file.length() + " bytes) but this app may not read it");
        } else {
            long len = file.length();
            r.add("the file exists, " + len + " bytes, readable");
            if (len == 0) r.add("it is empty: the copy or the download did not finish");
            else {
                if (pickedSize >= 0 && pickedSize != len) r.add("the picker reported " + pickedSize + " bytes but " + len + " are here: the copy is incomplete or the file changed");
                String type = Classifier.magic(path, len);
                r.add("first bytes: " + firstBytes(path) + " -> looks like " + type);
                String ext = ext(path);
                if (!type.equals("jpeg") && !type.equals("png") && !type.equals("webp") && !type.equals("heic") && !type.equals("avif"))
                    r.add("this is not a picture format the app reads (" + type + ")");
                else if (!ext.isEmpty() && !sameKind(ext, type)) r.add("the name ends in ." + ext + " but the bytes say " + type);
                if (type.equals("heic") || type.equals("avif")) r.add(type.toUpperCase(Locale.ROOT) + " needs a decoder for it on this phone");
            }
        }
        String note = pf == null ? null : pf.decodeNote();
        if (note != null) r.add("decoder: " + note);
        if (err != null) r.add("error: " + err);
        return r;
    }

    private static String ext(String path) {
        int i = path.lastIndexOf('.');
        return i < 0 || path.indexOf('/', i) >= 0 ? "" : path.substring(i + 1).toLowerCase(Locale.ROOT);
    }

    private static boolean sameKind(String ext, String type) {
        if (ext.equals("jpg") || ext.equals("jpeg") || ext.equals("jpe") || ext.equals("jfif")) return type.equals("jpeg");
        if (ext.equals("heif")) return type.equals("heic");
        return ext.equals(type);
    }

    static String firstBytes(String path) {
        java.io.InputStream in = null;
        try {
            in = new java.io.FileInputStream(path);
            byte[] b = new byte[16]; int n = in.read(b);
            StringBuilder s = new StringBuilder();
            for (int i = 0; i < Math.max(0, n); i++) s.append(i == 0 ? "" : " ").append(String.format(Locale.US, "%02x", b[i] & 0xff));
            return n <= 0 ? "(none)" : s.toString();
        } catch (java.io.IOException e) { return "(unreadable: " + e.getMessage() + ")"; }
        finally { if (in != null) try { in.close(); } catch (java.io.IOException ignored) {} }
    }

    static String f(String fmt, Object... a) { return String.format(Locale.US, fmt, a); }

    // ------------------------------------------------------------------ faces
    /** Why a detected face is or is not counted (same rules as PhotoQuality.analyse). */
    static String verdict(float[] raw, int i, int w, int h, double biggest) {
        double mx = raw[i], my = raw[i + 1], d = raw[i + 2], c = raw[i + 3];
        if (c < PhotoQuality.MIN_FACE_CONFIDENCE) return "ignored (score below " + PhotoQuality.MIN_FACE_CONFIDENCE + ")";
        if (d < 6 || mx < 0 || my < 0 || mx >= w || my >= h) return "ignored (too small or outside)";
        if (d < PhotoQuality.BACKGROUND * biggest) return "ignored (far smaller than the main face: a bystander)";
        return null;
    }

    static double biggest(float[] raw) {
        double b = 0;
        for (int i = 0; i + Faces.F - 1 < raw.length; i += Faces.F) if (raw[i + 3] >= PhotoQuality.MIN_FACE_CONFIDENCE) b = Math.max(b, raw[i + 2]);
        return b;
    }

    static float[] shown(Img m, float[] raw, int i, double pOpen) {
        return new float[]{raw[i] / m.w, raw[i + 1] / m.h, raw[i + 2] / m.w, (float) pOpen, raw[i + 3]};
    }

    static double open(Img m, float[] raw, int i) { return EyeState.openProbability(m.l, m.w, m.h, raw[i + 4], raw[i + 5], raw[i + 6], raw[i + 7]); }

    /** Face detection exactly as the photo-quality check runs it (1280 px picture, YuNet, score >= MIN_FACE_CONFIDENCE). */
    public static Shown faces(Platform pf, Pic p, Out o) {
        o.line("FACE DETECTION (as used to pick the best shot) — " + p.name);
        Shown sh = new Shown();
        Img m = load(pf, p, Scanner.QUALITY_EDGE, o);
        if (m == null) return sh;
        long t0 = System.nanoTime();
        float[] raw = pf.faces(p.path, m.px, m.w, m.h);
        long ms = (System.nanoTime() - t0) / 1000000;
        if (raw == null) { o.line("  ! the face detector is not available on this phone"); return sh; }
        o.line(f("  detector (YuNet, on a copy at most %d px; candidates scoring %.2f-%.2f looked at again on a full-resolution crop) returned %d face(s) in %d ms",
                Faces.DETECT_EDGE, Faces.LOOK_AGAIN, PhotoQuality.MIN_FACE_CONFIDENCE, raw.length / Faces.F, ms));
        double big = biggest(raw);
        List<float[]> kept = new ArrayList<float[]>();
        for (int i = 0; i + Faces.F - 1 < raw.length; i += Faces.F) {
            String why = verdict(raw, i, m.w, m.h, big);
            o.line(f("  face %d: eyes at (%.0f, %.0f) and (%.0f, %.0f), between them (%.1f%%, %.1f%%), %.1f px apart (%.1f%% of width), score %.2f -> %s",
                    i / Faces.F + 1, raw[i + 4], raw[i + 5], raw[i + 6], raw[i + 7], 100 * raw[i] / m.w, 100 * raw[i + 1] / m.h, raw[i + 2], 100 * raw[i + 2] / m.w, raw[i + 3],
                    why == null ? "counted" : why));
            if (why == null) kept.add(shown(m, raw, i, open(m, raw, i)));
        }
        if (kept.isEmpty()) o.line("  => no face counted: this picture is judged on sharpness and light only");
        float[] old = null;
        try { old = pf.otherFaces(p.path, m.px, m.w, m.h); } catch (Throwable ignored) {}
        if (old != null) {
            StringBuilder b = new StringBuilder();
            for (int i = 0; i + Faces.F - 1 < old.length; i += Faces.F)
                b.append(f(" [(%.1f%%, %.1f%%), eyes %.0f px apart, conf %.2f]", 100 * old[i] / m.w, 100 * old[i + 1] / m.h, old[i + 2], old[i + 3]));
            o.line(f("  for comparison, %s finds %d:%s", pf.otherFacesName(), old.length / Faces.F, b));
        }
        sh.faces = flat(kept);
        return sh;
    }

    /** The detector on other sizes and on the picture turned a little, and the old detector: shows what changes the answer. */
    public static Shown facesDetailed(Platform pf, Pic p, Out o) {
        o.line("FACE DETECTION, SIZES AND TILTS — " + p.name);
        Shown best = new Shown();
        int bestN = -1;
        for (int edge : new int[]{640, 1280, 2048}) {
            Img m = load(pf, p, edge, o);
            if (m == null) continue;
            for (int deg : edge == 1280 ? new int[]{0, -20, -10, 10, 20} : new int[]{0}) {
                int[] px = deg == 0 ? m.px : rotate(m.px, m.w, m.h, deg);
                long t0 = System.nanoTime();
                float[] raw = pf.faces(p.path, px, m.w, m.h);
                long ms = (System.nanoTime() - t0) / 1000000;
                if (raw == null) { o.line("  ! the face detector is not available on this phone"); return best; }
                StringBuilder b = new StringBuilder();
                int n = Faces.count(raw);
                for (int i = 0; i + Faces.F - 1 < raw.length; i += Faces.F) b.append(f(" [eyes %.0f px apart, score %.2f]", raw[i + 2], raw[i + 3]));
                o.line(f("  up to %4d px, turned %+3d°: %d face(s) with score >= %.1f, %d ms%s%s", edge, deg, n, PhotoQuality.MIN_FACE_CONFIDENCE, ms, b,
                        edge == 1280 && deg == 0 ? "   <- what the app uses" : ""));
                if (deg == 0 && n > bestN) {                        // overlay: faces found without turning, at the size that found most
                    bestN = n;
                    List<float[]> kept = new ArrayList<float[]>();
                    for (int i = 0; i + Faces.F - 1 < raw.length; i += Faces.F) if (raw[i + 3] >= PhotoQuality.MIN_FACE_CONFIDENCE) kept.add(shown(m, raw, i, open(m, raw, i)));
                    best.faces = flat(kept);
                }
                if (edge == 1280 && deg == 0) {
                    float[] old = null;
                    try { old = pf.otherFaces(p.path, px, m.w, m.h); } catch (Throwable ignored) {}
                    if (old != null) {
                        StringBuilder ob = new StringBuilder();
                        for (int i = 0; i + Faces.F - 1 < old.length; i += Faces.F) ob.append(f(" [(%.1f%%, %.1f%%), conf %.2f]", 100 * old[i] / m.w, 100 * old[i + 1] / m.h, old[i + 3]));
                        o.line(f("  up to 1280 px with %s: %d face(s)%s", pf.otherFacesName(), old.length / Faces.F, ob));
                    }
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
        Img m = load(pf, p, Scanner.QUALITY_EDGE, o);
        if (m == null) return sh;
        float[] raw = pf.faces(p.path, m.px, m.w, m.h);
        if (raw == null) { o.line("  ! the face detector is not available on this phone"); return sh; }
        double big = biggest(raw);
        List<float[]> kept = new ArrayList<float[]>();
        int k = 0;
        for (int i = 0; i + Faces.F - 1 < raw.length; i += Faces.F) {
            if (verdict(raw, i, m.w, m.h, big) != null) continue;
            k++;
            double x1 = raw[i + 4], y1 = raw[i + 5], x2 = raw[i + 6], y2 = raw[i + 7], d = raw[i + 2], mx = raw[i], my = raw[i + 1];
            StringBuilder shifts = new StringBuilder();
            for (double[] s : EyeState.SHIFTS) {
                double dx = s[0] * d, dy = s[1] * d, sc = s[2];
                double l = EyeState.eye(m.l, m.w, m.h, mx + (x1 - mx) * sc + dx, my + (y1 - my) * sc + dy, d * sc, false),
                       r = EyeState.eye(m.l, m.w, m.h, mx + (x2 - mx) * sc + dx, my + (y2 - my) * sc + dy, d * sc, true);
                shifts.append(f(" %.2f/%.2f", l, r));
            }
            double pOpen = open(m, raw, i);
            o.line(f("  face %d (eyes at (%.0f, %.0f) and (%.0f, %.0f), %.0f px apart, score %.2f): eyes-open probability %.2f -> %s", k, x1, y1, x2, y2, d, raw[i + 3], pOpen,
                    pOpen >= PhotoQuality.OPEN ? "EYES OPEN" : "EYES CLOSED"));
            o.line("    left/right eye at the 7 positions tried (centre, up, down, left, right, smaller, larger):" + shifts);
            o.line(f("    counts towards the score as %.2f of a face (0 below p=0.2, full above p=0.8)", PhotoQuality.openness(pOpen)));
            for (int e = 0; e < 2; e++) {
                double ex = e == 0 ? x1 : x2, ey = e == 0 ? y1 : y2;
                sh.patches.add(gray(EyeState.patch(m.l, m.w, m.h, ex, ey, d, e == 1)));
                sh.patchLabels.add("face " + k + (e == 0 ? " left eye " : " right eye ") + f("%.2f", EyeState.eye(m.l, m.w, m.h, ex, ey, d, e == 1)));
            }
            kept.add(shown(m, raw, i, pOpen));
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
        Img m = load(pf, p, Scanner.QUALITY_EDGE, o);
        if (m == null) return sh;
        o.line(f("  focus (used to compare near-identical shots): %.3f   [90th percentile of Laplacian variance / variance, 64 px tiles, 1280 px]", PhotoQuality.sharpness(m.l, m.w, m.h)));
        Img k = load(pf, p, Sorter.EDGE, o);
        if (k != null) {
            o.line(f("  blur score (used for 'possibly blurry' and when shots were not measured): %.1f   [90th percentile Laplacian variance, 1024 px]",
                    ImageFeatures.sharpness(k.l, k.w, k.h)));
            o.line("  'possibly blurry' needs a blur score below " + UnitBuilder.BLUR_REL + " x the median of all your camera photos");
        }
        float[] raw = pf.faces(p.path, m.px, m.w, m.h);
        List<float[]> kept = new ArrayList<float[]>();
        double big = raw == null ? 0 : biggest(raw);
        if (raw != null) for (int i = 0; i + Faces.F - 1 < raw.length; i += Faces.F) if (verdict(raw, i, m.w, m.h, big) == null) {
            double[] st = PhotoQuality.faceStats(m.l, m.w, m.h, raw[i], raw[i + 1], raw[i + 2]);
            o.line(f("  face %d focus: %.3f (brightness %.0f)", kept.size() + 1, st[0], st[1]));
            kept.add(shown(m, raw, i, open(m, raw, i)));
        }
        if (kept.isEmpty()) o.line("  no face counted: only the whole picture's focus is used");
        sh.faces = flat(kept);
        return sh;
    }

    public static Shown lighting(Platform pf, Pic p, Out o) {
        o.line("LIGHTING — " + p.name);
        Img m = load(pf, p, Scanner.QUALITY_EDGE, o);
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
        Img m = load(pf, p, Scanner.QUALITY_EDGE, o);
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
        List<String> failed = new ArrayList<String>();
        for (Pic p : pics) {
            o.line(" " + p.name);
            PhotoQuality.Q q = quality(pf, p, o);
            if (q == null) failed.add(p.name); else qs.add(q);        // go on: every picture that cannot be measured gets its reason in the log
        }
        if (!failed.isEmpty()) {
            o.line("  ! cannot rank: " + failed.size() + " of " + pics.size() + " pictures could not be measured (" + String.join(", ", failed)
                    + "); the app would then keep the one with the highest blur score");
            return sh;
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
        int failed = 0;
        for (int i = 0; i < pics.size(); i++) {
            o.line(" " + pics.get(i).name);
            Img m = load(pf, pics.get(i), Sorter.EDGE, o);
            if (m == null) { failed++; continue; }
            h[i] = ImageFeatures.phash(m.l, m.w, m.h);
            dims[i] = new int[]{m.ow, m.oh};
            o.line(f("  perceptual hash %016x", h[i]));
        }
        if (failed > 0) { o.line("  ! cannot compare: " + failed + " of " + pics.size() + " pictures could not be decoded (reasons above)"); return; }
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
            if (hs[i] == null) {
                o.line("  ! " + pics.get(i).name + ": could not read the file");
                for (String l : whyNot(null, pics.get(i).path, pics.get(i).size, null)) o.line("    " + l);
                continue;
            }
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
        FileItem f = new FileItem(p.rel != null ? p.rel : p.area.equals("screenshot") ? "Pictures/Screenshots/" + p.name : "Lab/" + p.name, p.path, p.size, p.mtime);
        f.area = p.area; f.type = p.type != null ? p.type : p.name.toLowerCase(Locale.ROOT).endsWith(".png") ? "png" : "jpeg";
        f.taken = "jpeg".equals(f.type) ? PhotoMeta.taken(p.path, java.util.TimeZone.getDefault()) : 0;    // date taken, Motion Photo: as in a scan
        if ("camera".equals(f.area)) try { f.motionBytes = com.storageplanner.tools.MotionPhoto.find(new java.io.File(p.path)).videoBytes(); } catch (Throwable t) { f.motionBytes = -1; }
        Img m = load(pf, p, Sorter.EDGE, o);
        if (m == null) return sh;
        int[] d = {m.ow, m.oh, m.w, m.h};
        Sorter.Prep prep = Sorter.measure(pf, f, m.px, d);
        ImageSignals.S s = prep.s;
        o.line(f("  brightness %.0f, contrast %.1f, colourful %.1f, dark %.1f%%, glare %.1f%%, paper-like %.1f%%, flat (screen) %.1f%%, focus %.3f (sharpest parts %.3f)",
                s.meanLuma, s.contrast, s.colorful, 100 * s.darkFrac, 100 * s.glare, 100 * s.paper, 100 * s.flat, s.sharp, s.peakSharp));
        o.line(f("  text-like marks %d in %d lines covering %.2f%% (letter height %.1f%% of the picture, big lettering %.0f%% of the width)",
                s.textBlobs, s.textLines, 100 * s.textArea, 100 * s.textHeight, 100 * s.bigText));
        o.line(pageLine(s));
        o.line("  looks like a page of paper? " + (s.page() ? "yes (handwritten or printed page, no text reading needed)" : "no: " + s.pageFail()));
        o.line("  code: " + (f.code == null ? "none" : f.code) + "; faces counted: " + (f.faces < 0 ? "not checked" : String.valueOf(f.faces))
                + (f.eyeFaces > 0 ? "; eyes open on " + f.eyesOpen + " of " + f.eyeFaces + " faces checked" : f.faces > 0 ? "; eyes not checked (faces too small or far in the background)" : ""));
        o.line("  card or paper held up? " + (PhotoCategory.cardLike(f, s) ? "yes" + (f.faces > 0 ? " (with a face: only an ID document verdict from the text counts)" : " (three or more lines of print)")
                : "no" + (PhotoCategory.cardCandidate(s) ? " (paper and print, but no face and fewer than 3 lines)" : f(" (needs paper-like 20%%+, 2+ lines, 8+ marks)"))));
        boolean read = PhotoCategory.worthReading(f, s);
        o.line("  accidental shot? " + PhotoCategory.accidental(s) + shakenNote(s) + "; worth reading the text? " + read);
        TextRules.Verdict v = null;
        if (read) v = readAndJudge(pf, f, s, prep.aspect, now, o);
        PhotoCategory.Result r = PhotoCategory.classify(f, s, f.code, v, f.faces);
        f.category = r.category; f.faces = f.faces;
        o.line("  => " + PhotoCategory.label(r.category) + (r.why.isEmpty() ? "" : "  (" + r.why + ")") + (r.date > 0 ? "; date " + Report.day(r.date) : ""));
        f.category = r.category; f.catDate = r.date; f.textPending = read && v == null;
        tagLines(f, now, o);
        sh.file = f;
        return sh;
    }

    // ------------------------------------------------------------------ rules
    /**
     * Sorts the picture as "Picture category" does, then runs the rules on it, with the same engine and rails as the plan:
     * which rules match, why each other one does not (the first condition that fails, with this picture's value), and the
     * options the plan would get from it. Tags that need the whole phone (duplicates, bursts, blur against your other
     * photos, sent copies) stay unknown here, and a rule that tests them does not match.
     */
    public static Shown rules(Platform pf, Pic p, RuleSet rules, long now, Out o) {
        Shown sh = category(pf, p, now, o);
        FileItem f = sh.file;
        if (f == null) return sh;
        o.line("RULES — " + p.name + " (" + rules.rules.size() + " rules; duplicates, bursts, blur and sent copies need your whole phone, so rules testing them do not match here)");
        Scanner.Result one = new Scanner.Result();
        one.files.add(f);
        Tags.Context ctx = Tags.context(one, new UnitBuilder.Detections());
        Tags.T t = Tags.of(f, ctx, now);
        Estimates est = new Estimates();
        int off = 0, hits = 0;
        StringBuilder notBuilt = new StringBuilder();
        for (Rule r : rules.rules) {
            String who = (r.builtIn ? "built-in" : "your rule") + " \"" + r.name + "\"";
            if (!r.enabled) { if (!r.builtIn) o.line("  - " + who + ": switched off"); else off++; continue; }
            if (r.problem() != null) { o.line("  - " + who + ": not used, " + r.problem()); continue; }
            if (r.matches(t)) {
                hits++;
                sh.matched.add(r.name);
                String rail = r.action == Rule.Action.DELETE && t.idDocument ? "  (but an ID document is never deleted: only an identical extra copy may go)" : "";
                o.line("  ✔ " + who + " MATCHES -> " + r.action.text + (r.onlyIfNoOtherOption ? " (only if no other rule gave an option)" : "") + rail);
                continue;
            }
            List<String> why = new ArrayList<String>();
            if (!r.fileTypeMatches(t))
                why.add("f" + RuleText.appliesTo(r).substring(1) + " — here: " + (t.fileType == null ? "file type not known" : RuleText.fileType(t.fileType)));
            for (Rule.Condition c : r.when) {
                if (r.builtIn && !why.isEmpty()) break;
                if (c.matches(t)) continue;
                Tags.Entry e = t.get(c.tag);
                why.add(RuleText.condition(c) + " — here: " + (e == null ? "no such tag" : e.known ? e.value : "not known"));
                if (r.builtIn) break;                          // built-in rules: the first failing condition is enough
            }
            if (r.builtIn) notBuilt.append("\n    ").append(r.name).append(": ").append(why.isEmpty() ? "no conditions" : why.get(0));
            else {
                o.line("  ✘ " + who + " does not match:");
                for (String w : why) o.line("      " + w);
            }
        }
        if (notBuilt.length() > 0) o.line("  built-in rules that do not match (first failing condition):" + notBuilt);
        if (off > 0) o.line("  " + off + " built-in rule" + (off == 1 ? " is" : "s are") + " switched off");
        Unit u = Unit.forFile(f);
        rules.compile().apply(u, f, t, ctx, est, now);
        sh.options.addAll(u.options);
        o.line("  => " + hits + " rule" + (hits == 1 ? "" : "s") + " match; the plan would get " + (u.options.isEmpty() ? "no option for it (kept)" : u.options.size() + " option" + (u.options.size() == 1 ? "" : "s") + ":"));
        for (Option op : u.options)
            o.line(f("      %s by \"%s\", frees about %.1f MB%s", op.action, op.technique, op.saved / 1e6, op.why == null || op.why.isEmpty() ? "" : " — " + op.why));
        return sh;
    }

    /** Why a soft picture is or is not called shaken (both its focus and its sharpest parts must be soft). */
    static String shakenNote(ImageSignals.S s) {
        if (s.sharp < 0 || s.sharp >= PhotoCategory.SHAKEN) return "";
        if (s.peakSharp >= PhotoCategory.SHAKEN_PEAK)
            return f(" (soft overall, focus %.3f < %.2f, but its sharpest parts are sharp, %.3f >= %.2f: a sharp subject on a blurred background, not shaken)",
                    s.sharp, PhotoCategory.SHAKEN, s.peakSharp, PhotoCategory.SHAKEN_PEAK);
        return f(" (focus %.3f < %.2f and even its sharpest parts are soft, %.3f < %.2f)", s.sharp, PhotoCategory.SHAKEN, s.peakSharp, PhotoCategory.SHAKEN_PEAK);
    }

    /** The page-of-paper measurements on one line (the later ones are not measured when an earlier check already failed). */
    static String pageLine(ImageSignals.S s) {
        if (s.pagePaper < 0) return "  page check: not measured";
        String rest = s.smooth < 0 ? "smooth, ink: not measured (an earlier check failed)"
                : f("smooth %.0f%% of that paper, ink %.1f%% of the picture in %d of 8 bands", 100 * s.smooth, 100 * s.ink, s.inkBands);
        return f("  page check: white level %.0f, plain paper near it %.1f%%, %s", s.whiteLevel, 100 * s.pagePaper, rest);
    }

    /** The tags of this one picture (the blur score and the other whole-scan tags need your whole phone, so they stay unknown here). */
    static void tagLines(FileItem f, long now, Out o) {
        Scanner.Result one = new Scanner.Result();
        one.files.add(f);
        Tags.Context ctx = Tags.context(one, new UnitBuilder.Detections());
        StringBuilder b = new StringBuilder();
        for (Tags.Entry e : Tags.of(f, ctx, now).shown()) b.append(b.length() == 0 ? "" : "; ").append(e.label).append(' ').append(e.value);
        o.line("  tags: " + b);
    }

    static TextRules.Verdict readAndJudge(Platform pf, FileItem f, ImageSignals.S s, double aspect, long now, Out o) {
        int[] d = new int[4];
        int[] px = decode(pf, f.abs, f.size, Sorter.TEXT_EDGE, d, "the picture for reading", o);
        if (px == null) return null;
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
            Img m = load(pf, p, edge, o);
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
        Img m = load(pf, p, Sorter.EDGE, o);
        if (m == null) return;
        ImageSignals.S s = ImageSignals.measure(m.px, m.w, m.h);
        readAndJudge(pf, f, s, m.ow / (double) Math.max(1, m.oh), now, o);
        int[] d = new int[4];
        int[] small = decode(pf, p.path, p.size, Sorter.GREETING_EDGE, d, "the small copy for the greeting check", o);
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
