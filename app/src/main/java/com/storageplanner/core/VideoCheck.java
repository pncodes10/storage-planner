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
    public static final int VERSION = 3;            // 2: a dark frame must also be nearly featureless (night videos are not dark); 3: the index first, junk score
    /** Frames looked at, at these points of the clip (a little in from both ends: the first frames are often black). */
    public static final double[] AT = {0.08, 0.3, 0.5, 0.7, 0.92};
    /** Long edge of a decoded frame: the same as pictures get for their sharpness (ImageFeatures). */
    public static final int EDGE = 1024;
    /** A frame is dark below this mean brightness (0..255) when it also shows almost nothing (spread under DARK_SPREAD). */
    public static final double DARK_MEAN = 35;
    /**
     * Brightness spread under which a dark frame shows nothing: a pocket or a covered lens. Night scenes with lights in
     * them (a lit dashboard, a street at night, a concert) are dark on average but spread 40 and more, and are kept.
     */
    public static final double DARK_SPREAD = 20;
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
            if (darkFrame(ms)) r.dark++;
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

    /** {mean, spread} of a frame that shows nothing: dark and almost featureless, or flat (a covered lens, a wall). */
    static boolean darkFrame(double[] ms) { return ms[0] < DARK_MEAN && ms[1] < DARK_SPREAD || ms[1] < FLAT_SPREAD; }

    /** Writes the result of decoded frames onto the file (keeps what the index said). */
    public static void record(FileItem f, Result r) {
        f.videoVersion = VERSION;
        f.vFrames = r.frames; f.vDark = r.dark; f.vMotion = r.motion; f.vSharpMax = r.sharpMax;
        f.vPeople = r.people; f.vWords = r.words;
        if (r.frames > 0) { f.vHash = r.hash; f.vHashed = true; }
    }

    /** Writes what the index said onto the file. */
    public static void record(FileItem f, VideoIndex.Stats s) {
        f.videoVersion = VERSION;
        f.vIndexed = s.ok; f.vKeyframes = s.keyframes; f.vLowDetail = s.lowDetail; f.vStill = s.still;
        f.vAudio = s.audio; f.vQuiet = s.quiet;
    }

    static void copy(FileItem from, FileItem to) {
        to.videoVersion = from.videoVersion; to.vFrames = from.vFrames; to.vDark = from.vDark; to.vMotion = from.vMotion;
        to.vSharpMax = from.vSharpMax; to.vPeople = from.vPeople; to.vWords = from.vWords; to.vHash = from.vHash; to.vHashed = from.vHashed;
        to.vIndexed = from.vIndexed; to.vKeyframes = from.vKeyframes; to.vLowDetail = from.vLowDetail; to.vStill = from.vStill;
        to.vAudio = from.vAudio; to.vQuiet = from.vQuiet;
    }

    // ------------------------------------------------------------------ what the measurements mean (the tags)

    /** Checked: by its index, by decoded frames, or both. */
    public static boolean looked(FileItem f) { return f.videoVersion > 0 && (f.vFrames > 0 || f.vIndexed); }

    /** Its frames were decoded (only for videos the index found suspicious, or without an index). */
    public static boolean decoded(FileItem f) { return f.videoVersion > 0 && f.vFrames > 0; }

    /** The index said it looks fine, so no frame was decoded. */
    public static boolean indexClean(FileItem f) { return f.videoVersion > 0 && f.vIndexed && f.vFrames == 0; }

    public static Boolean dark(FileItem f) {
        if (decoded(f)) return f.vDark >= DARK_FRAMES * f.vFrames;
        return indexClean(f) ? Boolean.FALSE : null;
    }

    /** Nothing moves: from the index (frames between keyframes change next to nothing), else from decoded frames. */
    public static Boolean still(FileItem f) {
        if (f.videoVersion > 0 && f.vIndexed && f.vStill >= 0) return f.vStill >= STILL_SHARE;
        return decoded(f) && f.vMotion >= 0 ? f.vMotion < STILL_BITS : null;
    }

    /** Even its sharpest decoded frame is much softer than your camera photos (cameraSharp: their sharpness, ascending). */
    public static Boolean blurry(FileItem f, double[] cameraSharp) {
        if (indexClean(f)) return false;
        if (!decoded(f) || f.vSharpMax < 0 || cameraSharp == null || cameraSharp.length < 5) return null;
        return f.vSharpMax < UnitBuilder.BLUR_REL * cameraSharp[cameraSharp.length / 2];
    }

    /** No sound track, or quiet nearly all through; null when unknown. */
    public static Boolean silent(FileItem f) {
        if (f.videoVersion <= 0 || !f.vIndexed || f.vAudio < 0) return null;
        return f.vAudio == 0 || f.vQuiet >= SILENT_SHARE;
    }

    public static double seconds(FileItem f) { return f.durationMs > 0 ? f.durationMs / 1000.0 : Tags.UNKNOWN; }

    /** Taller than wide (as shown, the rotation applied); null when the size is unknown. */
    public static Boolean vertical(FileItem f) { return f.width > 0 && f.height > 0 ? f.height > f.width : null; }

    // ---- the junk score: each sign adds points, a face takes them all away
    /** Points for a clip under SHORT_SECONDS (almost always a mistaken tap), and for one under 5 s. */
    public static final int P_SHORT = 60, P_SHORTISH = 15;
    /** Points for dark or covered frames, times the share of decoded frames that are. */
    public static final int P_DARK = 60;
    public static final int P_BLURRY = 30, P_STILL = 20, P_SILENT = 10;
    /** From this score a video counts as junk (taken by mistake). */
    public static final int JUNK = 60;
    /** Share of frames between keyframes that change next to nothing for the clip to count as still. */
    public static final double STILL_SHARE = 0.8;
    /** Share of quiet seconds for the clip to count as silent. */
    public static final double SILENT_SHARE = 0.9;

    /** The junk score, 0..100; -1 when the video was not checked. A face in any decoded frame makes it 0. */
    public static int junkScore(FileItem f, double[] cameraSharp) { return junk(f, cameraSharp, null); }

    /** The score and, in why, each sign that added to it ("dark frames 3 of 3 +60"). */
    public static int junk(FileItem f, double[] cameraSharp, StringBuilder why) {
        if (!looked(f)) return -1;
        if (decoded(f) && f.vPeople > 0) { if (why != null) why.append("a face in a frame: kept"); return 0; }
        int p = 0;
        double s = seconds(f);
        if (s >= 0 && s < SHORT_SECONDS) { p += P_SHORT; add(why, String.format(java.util.Locale.US, "%.1f s long +%d", s, P_SHORT)); }
        else if (s >= 0 && s < 5) { p += P_SHORTISH; add(why, String.format(java.util.Locale.US, "%.1f s long +%d", s, P_SHORTISH)); }
        if (decoded(f) && f.vDark > 0) {
            int d = (int) Math.round(P_DARK * f.vDark / (double) f.vFrames);
            p += d; add(why, "dark or covered frames " + f.vDark + " of " + f.vFrames + " +" + d);
        }
        // blur only counts when some frame shows something: a black frame is not "blurred", it is already counted as dark
        if (Boolean.TRUE.equals(blurry(f, cameraSharp)) && f.vDark < f.vFrames) { p += P_BLURRY; add(why, "blurred all through +" + P_BLURRY); }
        if (Boolean.TRUE.equals(still(f))) { p += P_STILL; add(why, "nothing moves +" + P_STILL); }
        if (Boolean.TRUE.equals(silent(f))) { p += P_SILENT; add(why, (f.vAudio == 0 ? "no sound" : "quiet all through") + " +" + P_SILENT); }
        if (why != null && why.length() == 0) why.append("no sign of junk");
        return Math.min(100, p);
    }

    private static void add(StringBuilder b, String s) { if (b != null) b.append(b.length() == 0 ? "" : ", ").append(s); }

    /**
     * A clip taken by mistake: a junk score of JUNK or more and nobody in it. null until it was checked, and when frames
     * were decoded but faces could not be checked.
     */
    public static Boolean accidental(FileItem f, double[] cameraSharp) {
        if (!looked(f)) return null;
        if (indexClean(f)) return junkScore(f, cameraSharp) >= JUNK ? Boolean.TRUE : Boolean.FALSE;
        if (decoded(f) && f.vPeople < 0) return null;
        return junkScore(f, cameraSharp) >= JUNK;
    }

    /**
     * Looks like something passed around in chats: short (up to FORWARD_MAX_SECONDS), and upright like a reel or with a
     * caption burned into the picture. null until it was checked. (No built-in rule uses it; it is there for your own.)
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
     * after it was filmed, and a middle frame that looks the same. Only videos whose middle frame was fingerprinted.
     */
    public static void sentCopies(List<FileItem> files, Map<String, String> out) {
        List<FileItem> cam = new ArrayList<FileItem>(), sent = new ArrayList<FileItem>();
        for (FileItem f : files) {
            if (f.pending || f.videoVersion <= 0 || !f.vHashed || f.durationMs <= 0 || !Tags.VIDEO.equals(Tags.kind(f))) continue;
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
     * Checks the videos (chat videos first, camera ones last; newest first within each), within opt.videoBudgetMs;
     * unchanged videos keep last time's results (prev). Then fingerprints the middle frame of WhatsApp sent videos
     * and of camera videos of the same length, so sent copies can be matched.
     */
    public static void run(Platform pf, Scanner.Progress pr, List<FileItem> vids, Scanner.Options opt, Map<String, FileItem> prev) {
        List<FileItem> todo = new ArrayList<FileItem>(), all = new ArrayList<FileItem>();
        for (FileItem f : vids) {
            if (!worthy(f)) continue;
            all.add(f);
            FileItem p = Scanner.same(f, prev);
            if (p != null && p.videoVersion == VERSION) { copy(p, f); continue; }
            todo.add(f);
        }
        if (opt.videoBudgetMs <= 0) return;
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
            pr.update("Looking at videos", k + 1, todo.size());
            try { look(pf, todo.get(k), null); } catch (Throwable ignored) {}
        }
        // fingerprints for sent copies: only where a sent video and a camera video have about the same length
        List<FileItem> sent = new ArrayList<FileItem>(), cam = new ArrayList<FileItem>();
        for (FileItem f : all) { if ("wa_sent_video".equals(f.area)) sent.add(f); else if ("camera".equals(f.area)) cam.add(f); }
        List<FileItem> need = new ArrayList<FileItem>();
        for (FileItem s : sent)
            for (FileItem c : cam)
                if (Math.abs(c.durationMs - s.durationMs) <= 1500) { if (!need.contains(s)) need.add(s); if (!need.contains(c)) need.add(c); }
        for (FileItem f : need) {
            if (pr.cancelled() || System.currentTimeMillis() > end) return;
            if (f.videoVersion == VERSION && f.vHashed) continue;
            try { fingerprint(pf, f); } catch (Throwable ignored) {}
        }
    }

    /** Decodes the middle frame only, for its fingerprint. */
    static void fingerprint(Platform pf, FileItem f) {
        int[] dims = new int[2];
        int[][] fr = pf.videoFrames(f.abs, new long[]{f.durationMs * 500L}, EDGE, dims);
        if (fr == null || fr.length == 0 || fr[0] == null || dims[0] <= 0) return;
        if (f.videoVersion != VERSION) f.videoVersion = VERSION;
        f.vHash = ImageFeatures.phash(luma(fr[0]), dims[0], dims[1]);
        f.vHashed = true;
    }

    /** Checks one video; false when nothing could be read. */
    public static boolean look(Platform pf, FileItem f) { return look(pf, f, null) != null; }

    /**
     * Checks one video, writing what it does to o (the Algorithm Lab; may be null). Returns the times (microseconds) of
     * the frames it decoded (empty when the index was enough), or null when nothing could be read.
     *
     * 1. The index (VideoIndex): milliseconds, no decoding. A video with normal detail, movement and length is done.
     * 2. Otherwise the three keyframes with the most detail are decoded (plus the middle frame for its fingerprint): if
     *    even the richest frames are dark, covered or blurred, the rest of the video is worse; one good frame or a face
     *    keeps it. Without an index (not MP4, damaged) the five frames at AT are decoded instead.
     */
    public static long[] look(Platform pf, FileItem f, Lab.Out o) {
        long t0 = System.nanoTime();
        VideoIndex.Stats ix = VideoIndex.stats(f.abs, f.width, f.height);
        record(f, ix);
        f.vFrames = 0; f.vDark = 0; f.vMotion = -1; f.vSharpMax = -1; f.vPeople = -1; f.vWords = -1;
        long[] at;
        if (ix.ok) {
            String why = suspicion(f, ix);
            if (o != null) o.line(String.format(java.util.Locale.US,
                    "  index (%d ms): %d frames, %d keyframes, %.1f s · keyframe detail median %.3f, max %.3f bits per pixel · "
                            + "low-detail keyframes %.0f%% · still %s · sound %s",
                    (System.nanoTime() - t0) / 1000000, ix.frames, ix.keyframes, ix.seconds, ix.medianKeyBpp, ix.maxKeyBpp, 100 * ix.lowDetail,
                    ix.still < 0 ? "?" : String.format(java.util.Locale.US, "%.0f%%", 100 * ix.still),
                    ix.audio == 0 ? "none" : ix.audio < 0 ? "?" : String.format(java.util.Locale.US, "quiet %.0f%% of seconds", 100 * ix.quiet)));
            if (why == null) {
                if (o != null) o.line("  → looks fine from the index: no frame decoded");
                return new long[0];
            }
            if (o != null) o.line("  → worth a closer look (" + why + "): decoding the richest keyframes");
            at = new long[ix.richestUs.length + 1];
            System.arraycopy(ix.richestUs, 0, at, 0, ix.richestUs.length);
            at[at.length - 1] = f.durationMs * 500L;                      // the middle frame, for the fingerprint
        } else {
            if (o != null) o.line("  no usable index (not MP4, or damaged): decoding five frames spread over the video");
            at = new long[AT.length];
            for (int i = 0; i < at.length; i++) at[i] = (long) (f.durationMs * 1000L * AT[i]);
        }
        int[] dims = new int[2];
        long t1 = System.nanoTime();
        int[][] fr = pf.videoFrames(f.abs, at, EDGE, dims);
        if (fr == null || dims[0] <= 0 || dims[1] <= 0) { if (o != null) o.line("  ! no frame could be decoded from this video"); return ix.ok ? new long[0] : null; }
        if (o != null) o.line(String.format(java.util.Locale.US, "  %d frames decoded at %d x %d in %d ms", fr.length, dims[0], dims[1], (System.nanoTime() - t1) / 1000000));
        List<int[]> frames = new ArrayList<int[]>();
        int[] faces = new int[fr.length];
        int judged = ix.ok ? fr.length - 1 : fr.length;                  // with an index the last one is only for the fingerprint
        for (int i = 0; i < fr.length; i++) {
            faces[i] = -1;
            if (i >= judged) continue;
            frames.add(fr[i]);
            if (fr[i] == null) continue;
            float[] fc;
            try { fc = pf.faces(f.abs + "#" + i, fr[i], dims[0], dims[1]); } catch (Throwable t) { fc = null; }
            faces[i] = fc == null ? -1 : Faces.count(fc);
            if (o != null) {
                int[] l = luma(fr[i]);
                double[] ms = meanSpread(l);
                o.line(String.format(java.util.Locale.US, "  frame at %5.1f s: brightness %3.0f, spread %4.1f%s, sharpness %6.1f, faces %s",
                        at[i] / 1e6, ms[0], ms[1], ms[1] < FLAT_SPREAD ? " FLAT" : darkFrame(ms) ? " DARK" : ms[0] < DARK_MEAN ? " (night: dark but shows things)" : "",
                        ImageFeatures.sharpness(l, dims[0], dims[1]), faces[i] < 0 ? "?" : String.valueOf(faces[i])));
            }
        }
        Result r = measure(frames, dims[0], dims[1], java.util.Arrays.copyOf(faces, judged));
        if (ix.ok) r.motion = -1;                                       // richest keyframes are not neighbours: motion comes from the index
        if (ix.ok && fr[fr.length - 1] != null) r.hash = ImageFeatures.phash(luma(fr[fr.length - 1]), dims[0], dims[1]);
        if (r.frames == 0 && !ix.ok) return null;
        record(f, r);
        if (ix.ok && fr[fr.length - 1] != null) f.vHashed = true;
        long[] used = java.util.Arrays.copyOf(at, judged);
        return used;
    }

    /** Why the index alone is not enough for this video (null when it looks fine). */
    static String suspicion(FileItem f, VideoIndex.Stats ix) {
        double s = seconds(f);
        if (s >= 0 && s < SHORT_SECONDS) return String.format(java.util.Locale.US, "only %.1f s long", s);
        if (ix.flat) return "keyframes all the same size, so their sizes tell nothing";
        if (ix.lowDetail >= LOW_DETAIL_SHARE) return String.format(java.util.Locale.US, "%.0f%% of keyframes show next to nothing", 100 * ix.lowDetail);
        if (ix.still >= STILL_SHARE) return String.format(java.util.Locale.US, "nothing moves in %.0f%% of it", 100 * ix.still);
        return null;
    }

    /** Share of low-detail keyframes from which a video gets a closer look. */
    public static final double LOW_DETAIL_SHARE = 0.5;
}
