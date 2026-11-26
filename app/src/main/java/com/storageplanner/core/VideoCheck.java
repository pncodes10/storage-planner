package com.storageplanner.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Looks inside videos: FRAMES frames spread over the clip, each judged with the checks pictures already have
 * (brightness, covered or flat, sharpness, faces) plus how much changes from one frame to the next, the text in
 * the middle frame and a fingerprint of the middle frame (to find the WhatsApp copy of a camera video). Read-only.
 *
 * What it finds becomes the video tags (Tags.VIDEO_*) and three rules: accidental clips, old forwarded videos and
 * sent copies of camera videos (BuiltInRules). A clip is never called accidental or a forward when it has a face in it.
 */
public final class VideoCheck {
    private VideoCheck() {}

    /** Bump when what is measured changes: older measurements are then redone. */
    public static final int VERSION = 1;
    /** Frames looked at, at these points of the clip (a little in from both ends: the first frames are often black). */
    public static final double[] AT = {0.08, 0.3, 0.5, 0.7, 0.92};
    /** Long edge of a decoded frame: the same as pictures get for their sharpness (ImageFeatures). */
    public static final int EDGE = 1024;
    /** A frame is dark below this mean brightness (0..255). */
    public static final double DARK_MEAN = 35;
    /** A frame is flat (lens covered, a wall, the inside of a pocket) below this brightness spread. */
    public static final double FLAT_SPREAD = 10;
    /** Mean bits that change between the fingerprints of neighbouring frames, below which nothing happens in the clip. */
    public static final double STILL_BITS = 3;
    /** Share of frames that must be dark or flat for the whole clip to count as dark. */
    public static final double DARK_FRAMES = 0.8;
    /** A clip this short (seconds) from the camera is usually a mistaken tap. */
    public static final double SHORT_SECONDS = 2.5;
    /** Forwards are short: longer clips are not called a forward. */
    public static final double FORWARD_MAX_SECONDS = 180;
    /** Words in the middle frame from which it counts as a captioned clip (memes, news clips, greetings). */
    public static final int CAPTION_WORDS = 4;
    /** Fingerprint bits that may differ between a camera video and its WhatsApp copy. */
    public static final int SENT_MATCH_BITS = 12;

    /** What the frames showed. */
    public static final class Result {
        public int frames;                 // frames decoded
        public int dark;                   // dark or flat frames
        public double motion = -1;         // mean fingerprint bits changing between neighbouring frames; -1 with fewer than 2 frames
        public double sharpMax = -1;       // sharpness of the sharpest frame (ImageFeatures scale); -1 = none measured
        public int people = -1;            // most faces in one frame; -1 = faces could not be checked
        public int words = -1;             // words read in the middle frame; -1 = not read
        public long hash;                  // fingerprint of the middle frame (ImageFeatures.phash)
    }

    /** Brightness (0..255) of an ARGB pixel. */
    static int luma(int p) { return (((p >> 16) & 255) * 77 + ((p >> 8) & 255) * 150 + (p & 255) * 29) >> 8; }

    public static int[] luma(int[] argb) {
        int[] l = new int[argb.length];
        for (int i = 0; i < argb.length; i++) l[i] = luma(argb[i]);
        return l;
    }

    /** {mean, spread (standard deviation)} of a luma frame. */
    static double[] meanSpread(int[] l) {
        double s = 0, q = 0;
        for (int v : l) { s += v; q += (double) v * v; }
        double m = s / Math.max(1, l.length);
        return new double[]{m, Math.sqrt(Math.max(0, q / Math.max(1, l.length) - m * m))};
    }

    /**
     * Measures decoded frames (ARGB, all w × h, in clip order; null entries are frames that could not be decoded).
     * faces[i] is the number of faces in frame i, or -1 when it could not be checked.
     */
    public static Result measure(List<int[]> argb, int w, int h, int[] faces) {
        Result r = new Result();
        List<Long> hashes = new ArrayList<Long>();
        int mid = argb.size() / 2;
        boolean facesKnown = faces != null;
        int people = 0;
        for (int i = 0; i < argb.size(); i++) {
            int[] px = argb.get(i);
            if (px == null) continue;
            r.frames++;
            int[] l = luma(px);
            double[] ms = meanSpread(l);
            if (ms[0] < DARK_MEAN || ms[1] < FLAT_SPREAD) r.dark++;
            double sh = ImageFeatures.sharpness(l, w, h);
            if (sh > r.sharpMax) r.sharpMax = sh;
            long hs = ImageFeatures.phash(l, w, h);
            hashes.add(hs);
            if (i == mid || r.frames == 1) r.hash = hs;
            if (faces == null || i >= faces.length || faces[i] < 0) facesKnown = false;
            else people = Math.max(people, faces[i]);
        }
        if (hashes.size() >= 2) {
            double d = 0;
            for (int i = 1; i < hashes.size(); i++) d += ImageFeatures.hamming(hashes.get(i - 1), hashes.get(i));
            r.motion = d / (hashes.size() - 1);
        }
        r.people = r.frames > 0 && facesKnown ? people : -1;
        return r;
    }

    /** Writes the result onto the file. */
    public static void record(FileItem f, Result r) {
        f.videoVersion = VERSION;
        f.vFrames = r.frames; f.vDark = r.dark; f.vMotion = r.motion; f.vSharpMax = r.sharpMax;
        f.vPeople = r.people; f.vWords = r.words; f.vHash = r.hash;
    }

    static void copy(FileItem from, FileItem to) {
        to.videoVersion = from.videoVersion; to.vFrames = from.vFrames; to.vDark = from.vDark; to.vMotion = from.vMotion;
        to.vSharpMax = from.vSharpMax; to.vPeople = from.vPeople; to.vWords = from.vWords; to.vHash = from.vHash;
    }

    // ------------------------------------------------------------------ what the measurements mean (the tags)

    public static boolean looked(FileItem f) { return f.videoVersion > 0 && f.vFrames > 0; }

    public static Boolean dark(FileItem f) { return looked(f) ? f.vDark >= DARK_FRAMES * f.vFrames : null; }

    public static Boolean still(FileItem f) { return looked(f) && f.vMotion >= 0 ? f.vMotion < STILL_BITS : null; }

    /** Even its sharpest frame is much softer than your camera photos (cameraSharp: their sharpness, ascending). */
    public static Boolean blurry(FileItem f, double[] cameraSharp) {
        if (!looked(f) || f.vSharpMax < 0 || cameraSharp == null || cameraSharp.length < 5) return null;
        return f.vSharpMax < UnitBuilder.BLUR_REL * cameraSharp[cameraSharp.length / 2];
    }

    public static double seconds(FileItem f) { return f.durationMs > 0 ? f.durationMs / 1000.0 : Tags.UNKNOWN; }

    /** Taller than wide (as shown, the rotation applied); null when the size is unknown. */
    public static Boolean vertical(FileItem f) { return f.width > 0 && f.height > 0 ? f.height > f.width : null; }

    /**
     * A clip taken by mistake: nobody in it, and short, dark or covered, blurred all through, or with nothing moving.
     * null until the frames were looked at and faces could be checked.
     */
    public static Boolean accidental(FileItem f, double[] cameraSharp) {
        if (!looked(f) || f.vPeople < 0) return null;
        if (f.vPeople > 0) return false;
        double s = seconds(f);
        return s >= 0 && s < SHORT_SECONDS || Boolean.TRUE.equals(dark(f)) || Boolean.TRUE.equals(blurry(f, cameraSharp))
                || Boolean.TRUE.equals(still(f));
    }

    /**
     * Looks like something passed around in chats: short (up to FORWARD_MAX_SECONDS), and upright like a reel or with a
     * caption burned into the picture. null until the frames were looked at.
     */
    public static Boolean forwardLike(FileItem f) {
        if (!looked(f)) return null;
        double s = seconds(f);
        if (s < 0 || s > FORWARD_MAX_SECONDS) return false;
        return Boolean.TRUE.equals(vertical(f)) || f.vWords >= CAPTION_WORDS;
    }

    // ------------------------------------------------------------------ the WhatsApp copy of a camera video

    /**
     * The camera video that a sent WhatsApp video is a copy of: same length (within 1.5 s), same orientation, sent
     * after it was filmed, and a middle frame that looks the same. Empty when nothing matches; looked-at videos only.
     */
    public static void sentCopies(List<FileItem> files, Map<String, String> out) {
        List<FileItem> cam = new ArrayList<FileItem>(), sent = new ArrayList<FileItem>();
        for (FileItem f : files) {
            if (!looked(f) || f.durationMs <= 0 || !Tags.VIDEO.equals(Tags.kind(f))) continue;
            if ("camera".equals(f.area)) cam.add(f);
            else if ("wa_sent_video".equals(f.area)) sent.add(f);
        }
        for (FileItem s : sent) {
            FileItem best = null; int bestBits = SENT_MATCH_BITS + 1;
            for (FileItem c : cam) {
                if (Math.abs(c.durationMs - s.durationMs) > 1500) continue;
                if (!same(vertical(c), vertical(s))) continue;
                if (s.mtime < c.mtime - 60000) continue;
                int bits = ImageFeatures.hamming(c.vHash, s.vHash);
                if (bits < bestBits) { bestBits = bits; best = c; }
            }
            if (best != null) out.put(s.rel, best.rel);
        }
    }

    private static boolean same(Boolean a, Boolean b) { return a == null || b == null || a.equals(b); }

    // ------------------------------------------------------------------ the pass over the phone's videos

    /** Videos this pass looks at. */
    public static boolean worthy(FileItem f) {
        return Tags.VIDEO.equals(Tags.kind(f)) && f.durationMs > 0 && f.area != null
                && (f.area.equals("camera") || f.area.equals("wa_video") || f.area.equals("wa_sent_video") || f.area.equals("download") || f.area.equals("movies"));
    }

    /**
     * Looks at the videos (camera ones last so chat videos, where most of the clean-up is, come first; newest first
     * within each), within opt.videoBudgetMs; unchanged videos keep last time's measurements (prev).
     */
    public static void run(Platform pf, Scanner.Progress pr, List<FileItem> vids, Scanner.Options opt, Map<String, FileItem> prev) {
        List<FileItem> todo = new ArrayList<FileItem>();
        for (FileItem f : vids) {
            if (!worthy(f)) continue;
            FileItem p = Scanner.same(f, prev);
            if (p != null && p.videoVersion == VERSION) { copy(p, f); continue; }
            todo.add(f);
        }
        if (todo.isEmpty() || opt.videoBudgetMs <= 0) return;
        Collections.sort(todo, new Comparator<FileItem>() {
            public int compare(FileItem a, FileItem b) {
                boolean ca = "camera".equals(a.area), cb = "camera".equals(b.area);
                if (ca != cb) return ca ? 1 : -1;
                return Long.compare(b.mtime, a.mtime);
            }
        });
        long end = System.currentTimeMillis() + opt.videoBudgetMs;
        for (int k = 0; k < todo.size(); k++) {
            if (pr.cancelled() || System.currentTimeMillis() > end) return;
            pr.update("Looking inside videos", k + 1, todo.size());
            FileItem f = todo.get(k);
            try { look(pf, f); } catch (Throwable ignored) {}
        }
    }

    /** Decodes the frames of one video and records what they show; false when no frame could be decoded. */
    public static boolean look(Platform pf, FileItem f) { return look(pf, f, null); }

    /** The same, writing each frame's measurements to o (the Algorithm Lab); o may be null. */
    public static boolean look(Platform pf, FileItem f, Lab.Out o) {
        long[] at = new long[AT.length];
        for (int i = 0; i < at.length; i++) at[i] = (long) (f.durationMs * 1000L * AT[i]);
        int[] dims = new int[2];
        long t0 = System.nanoTime();
        int[][] fr = pf.videoFrames(f.abs, at, EDGE, dims);
        if (fr == null || dims[0] <= 0 || dims[1] <= 0) { if (o != null) o.line("  ! no frame could be decoded from this video"); return false; }
        if (o != null) o.line(String.format(java.util.Locale.US, "  %d frames decoded at %d x %d in %d ms", fr.length, dims[0], dims[1], (System.nanoTime() - t0) / 1000000));
        long prevHash = 0; boolean havePrev = false;
        List<int[]> frames = new ArrayList<int[]>();
        int[] faces = new int[fr.length];
        for (int i = 0; i < fr.length; i++) {
            frames.add(fr[i]);
            faces[i] = -1;
            if (fr[i] == null) continue;
            float[] fc;
            try { fc = pf.faces(f.abs + "#" + i, fr[i], dims[0], dims[1]); } catch (Throwable t) { fc = null; }
            faces[i] = fc == null ? -1 : Faces.count(fc);
            if (o != null) {
                int[] l = luma(fr[i]);
                double[] ms = meanSpread(l);
                long hs = ImageFeatures.phash(l, dims[0], dims[1]);
                o.line(String.format(java.util.Locale.US, "  frame %d at %4.1f s: brightness %3.0f, spread %4.1f%s, sharpness %6.1f, faces %s%s",
                        i + 1, at[i] / 1e6, ms[0], ms[1], ms[0] < DARK_MEAN ? " DARK" : ms[1] < FLAT_SPREAD ? " FLAT" : "",
                        ImageFeatures.sharpness(l, dims[0], dims[1]), faces[i] < 0 ? "?" : String.valueOf(faces[i]),
                        havePrev ? ", changed " + ImageFeatures.hamming(prevHash, hs) + " of 64 bits" : ""));
                prevHash = hs; havePrev = true;
            }
        }
        if (o != null) for (int i = 0; i < fr.length; i++) if (fr[i] == null) o.line("  frame " + (i + 1) + ": could not be decoded");
        Result r = measure(frames, dims[0], dims[1], faces);
        if (r.frames == 0) return false;
        int mid = fr.length / 2;
        if (fr[mid] != null) {
            TextRules.Words w;
            try { w = pf.readText(f.abs + "#" + mid, fr[mid], dims[0], dims[1]); } catch (Throwable t) { w = null; }
            if (w != null) {
                int n = 0;
                for (int i = 0; i < w.text.size(); i++) if (w.conf.get(i) >= 60 && w.text.get(i).trim().length() >= 2) n++;
                r.words = n;
                if (o != null) {
                    StringBuilder b = new StringBuilder();
                    for (int i = 0; i < w.text.size() && b.length() < 160; i++) if (w.conf.get(i) >= 60) b.append(w.text.get(i)).append(' ');
                    o.line("  middle frame text: " + n + " words read confidently" + (b.length() > 0 ? ": " + b.toString().trim() : ""));
                }
            } else if (o != null) o.line("  middle frame text: the text reader is not available");
        }
        record(f, r);
        return true;
    }
}
