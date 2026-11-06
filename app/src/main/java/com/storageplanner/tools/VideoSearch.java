package com.storageplanner.tools;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * The part of the video tool that decides: whether a video may be compressed, at what size and frame rate, and
 * which bitrate reaches a level's quality target. Phone encoders offer only bitrate control (constant-quality mode
 * is missing or ignored on many chips), and a fixed share of the original bitrate starves busy or grainy videos and
 * wastes space on calm ones (docs/video-compression-research.md). So the bitrate is searched: short samples are
 * encoded at candidate bitrates and scored against the original, and the lowest bitrate whose worst sample passes is
 * used for the whole video, which is then checked again on other frames.
 *
 * Plain Java: the encoding and the frame comparison are behind {@link Engine}, which the Android tool implements with
 * MediaCodec, and the desktop test with ffmpeg.
 */
public final class VideoSearch {
    private VideoSearch() {}

    /** A copy that saves less than this is not worth replacing the original with (as for photos). */
    public static final double MIN_SAVING = 0.10;
    /** Videos up to this long are searched on the whole video rather than on samples. */
    static final long WHOLE_UP_TO_US = 20_000_000L;
    static final long SAMPLE_US = 2_000_000L;
    static final int ROUNDS = 4;
    /** The full encode uses the searched bitrate plus this margin (samples are not the whole video). */
    static final double MARGIN = 1.10;
    /** Frame rates above this are slow motion or high-speed capture: the playback speed would be lost. */
    static final double MAX_SOURCE_FPS = 61;

    /** What is known about the original before any encoding. */
    public static final class Facts {
        public long fileBytes, durationUs;
        /** Size of the decoded frames (before rotation) and the rotation to show them with. */
        public int width, height, rotation;
        public String videoMime, audioMime;
        /** Average bitrates in bits per second, from the sample sizes. */
        public long videoBitrate, audioBitrate;
        public int channels, sampleRate;
        /** Presentation times (sorted) and sizes of every video frame. */
        public long[] frameUs = new long[0];
        public int[] frameBytes = new int[0];
        /** Why the picture is HDR (PQ / HLG transfer, 10-bit BT.2020), or null for SDR. */
        public String hdr;
        public Mp4.Info mp4;

        public double fps() {
            int n = frameUs.length;
            if (n < 2) return 30;
            double span = (frameUs[n - 1] - frameUs[0]) / 1e6;
            return span > 0 ? (n - 1) / span : 30;
        }
    }

    /** Why a video is not compressed. {@code hard}: not even the Lab can make a meaningful copy. */
    public static final class Refusal {
        public final String why;
        public final boolean hard;
        Refusal(String why, boolean hard) { this.why = why; this.hard = hard; }
    }

    /** Checks done before any encoding, in the order a user would care about them. */
    public static Refusal refuse(Facts f) {
        Mp4.Info m = f.mp4;
        if (m != null && m.problem != null) return new Refusal("it could not be read as an MP4 video (" + m.problem + ")", true);
        if (f.videoMime == null || f.frameUs.length == 0) return new Refusal("it has no video track", true);
        if (f.hdr != null) return new Refusal("it is an HDR video (" + f.hdr + "); a copy would lose the HDR look", true);
        if (m != null && m.count("vide") > 1) return new Refusal("it has " + m.count("vide") + " video tracks", true);
        if (f.durationUs < 1_000_000L) return new Refusal("it is shorter than a second", true);
        if (m != null && m.captureFps) {
            // Samsung writes the capture speed in every video; only a speed well above the playback rate is slow motion
            double cap = m.captureFpsValue;
            if (Double.isNaN(cap)) return new Refusal("it may be a slow-motion video (its capture speed could not be read)", false);
            if (cap > f.fps() * 1.5)
                return new Refusal(String.format(Locale.US, "it is a slow-motion video (captured at %.0f fps, played at %.0f), and a copy would lose that", cap, f.fps()), false);
        }
        if (f.fps() > MAX_SOURCE_FPS) return new Refusal(String.format(Locale.US, "it has %.0f frames a second (slow motion or high-speed video)", f.fps()), false);
        if (m != null) {
            if (m.count("soun") > 1) return new Refusal("it has " + m.count("soun") + " audio tracks, and a copy keeps one", false);
            for (Mp4.Track t : m.tracks)
                if (!t.handler.equals("vide") && !t.handler.equals("soun"))
                    return new Refusal("it has a '" + t.handler.trim() + "' track (subtitles, sensor or other data) a copy would lose", false);
            if (m.trailerBytes >= Mp4.SMALL_TRAILER)
                return new Refusal("it holds " + (m.trailerBytes >> 10) + " KB of data after the video" + (m.samsungTrailer ? " (Samsung's trailer)" : "") + ", which a copy would lose", false);
        }
        return null;
    }

    /** What the copy will be. */
    public static final class Plan {
        public VideoLevel level;
        public int width, height;
        /** Frame rate of the copy, and which source frames it keeps (null = all). */
        public double fps;
        public long[] keptUs;
        /** Size the copy and the original are compared at. */
        public int compareWidth, compareHeight;
        /** H.264 instead of HEVC (no HEVC encoder on this phone). */
        public boolean avc;
        public int startBps, loBps, hiBps;
        /** The audio is re-encoded at this bitrate (0 = copied as it is). */
        public int audioBps;
        public final List<long[]> samples = new ArrayList<long[]>();   // {from, to} source microseconds
        public final List<long[]> sampleFrames = new ArrayList<long[]>();
        public long[] verifyUs;

        /** Whether the copy keeps the source frame shown at this time. */
        public boolean keeps(long us) { return keptUs == null || Arrays.binarySearch(keptUs, us) >= 0; }

        @Override public String toString() {
            return String.format(Locale.US, "%dx%d at %.2f fps, %s, bitrate search %d-%d kbps from %d, compared at %dx%d, audio %s",
                    width, height, fps, avc ? "H.264" : "HEVC", loBps / 1000, hiBps / 1000, startBps / 1000, compareWidth, compareHeight,
                    audioBps > 0 ? "AAC " + audioBps / 1000 + " kbps" : "copied");
        }
    }

    public static Plan plan(Facts f, VideoLevel l, boolean avc) {
        Plan p = new Plan();
        p.level = l; p.avc = avc;
        int w = f.width, h = f.height, shortSide = Math.min(w, h);
        if (l.maxShortSide > 0 && shortSide > l.maxShortSide) {
            double s = (double) l.maxShortSide / shortSide;
            w = even(w * s); h = even(h * s);
        }
        p.width = even(w); p.height = even(h);
        // Compare at the copy's size, halved when that is above 720p: sensor grain, which no encoder keeps,
        // averages out at half size (measured: full-size scores of a grainy video stay low at any bitrate).
        {
            double s = Math.min(p.width, p.height) > 720 ? 0.5 : 1.0;
            p.compareWidth = even(p.width * s); p.compareHeight = even(p.height * s);
        }

        double fps = f.fps();
        p.fps = fps;
        if (l.maxFps > 0 && fps > l.maxFps * 1.15) {
            p.fps = l.maxFps;
            long[] t = f.frameUs;
            List<Long> kept = new ArrayList<Long>();
            long last = Long.MIN_VALUE;
            // a tenth of a source frame of tolerance: frame times are rounded to microseconds
            double tol = 0.1 * 1e6 / fps;
            for (long us : t) {
                long bucket = (long) Math.floor((us - t[0] + tol) * l.maxFps / 1e6);
                if (bucket != last) { kept.add(us); last = bucket; }
            }
            p.keptUs = new long[kept.size()];
            for (int i = 0; i < p.keptUs.length; i++) p.keptUs[i] = kept.get(i);
        }

        double perSecond = (double) p.width * p.height * p.fps;
        p.startBps = (int) (l.startBpp * perSecond * (avc ? 1.4 : 1.0));
        p.loBps = Math.max(VideoLevel.FLOOR_BPS, p.startBps / 4);
        p.hiBps = (int) Math.min(f.videoBitrate * 0.85, p.startBps * 4.0);
        p.startBps = Math.max(p.loBps, Math.min(p.hiBps, p.startBps));
        if (l.audioKbps > 0 && f.audioMime != null && f.audioBitrate > l.audioKbps * 1000 * 1.2) p.audioBps = l.audioKbps * 1000;

        long[] kept = p.keptUs != null ? p.keptUs : f.frameUs;
        if (f.durationUs <= WHOLE_UP_TO_US) {
            p.samples.add(new long[]{0, Long.MAX_VALUE});
            p.sampleFrames.add(spread(kept, kept[0], kept[kept.length - 1] + 1, 8, 0.5));
        } else {
            long busiest = busiest(f);
            long[] starts = {busiest, (long) (f.durationUs * 0.10), (long) (f.durationUs * 0.35), (long) (f.durationUs * 0.60), (long) (f.durationUs * 0.85)};
            for (long s : starts) {
                s = Math.max(0, Math.min(f.durationUs - SAMPLE_US, s));
                boolean overlaps = false;
                for (long[] w2 : p.samples) if (s < w2[1] && s + SAMPLE_US > w2[0]) overlaps = true;
                if (overlaps) continue;
                p.samples.add(new long[]{s, s + SAMPLE_US});
                // skip the first half second of a sample: the encoder has just started there
                p.sampleFrames.add(spread(kept, s + 500_000L, s + SAMPLE_US, 3, 0.5));
            }
        }
        int n = (int) Math.max(6, Math.min(24, f.durationUs / 1_000_000L));
        p.verifyUs = spread(kept, kept[0], kept[kept.length - 1] + 1, n, 0.25);
        // never verify on a frame the search was tuned on: move to the next kept frame
        java.util.Set<Long> used = new java.util.HashSet<Long>();
        for (long[] sf : p.sampleFrames) for (long u : sf) used.add(u);
        for (int i = 0; i < p.verifyUs.length; i++) {
            int k = Arrays.binarySearch(kept, p.verifyUs[i]);
            while (k >= 0 && k < kept.length - 1 && used.contains(kept[k])) k++;
            if (k >= 0) p.verifyUs[i] = kept[k];
        }
        return p;
    }

    /** Start of the 2-second stretch with the most video data (the hardest part to compress). */
    static long busiest(Facts f) {
        long[] t = f.frameUs;
        int[] b = f.frameBytes;
        long best = 0, sum = 0;
        long bestAt = 0;
        int j = 0;
        for (int i = 0; i < t.length; i++) {
            sum += b[i];
            while (t[i] - t[j] >= SAMPLE_US) sum -= b[j++];
            if (sum > best) { best = sum; bestAt = t[j]; }
        }
        return bestAt;
    }

    /** n frame times evenly spread over [from, to), each snapped to a kept frame, at the given phase of its slot. */
    static long[] spread(long[] kept, long from, long to, int n, double phase) {
        List<Long> out = new ArrayList<Long>();
        for (int i = 0; i < n; i++) {
            long want = from + (long) ((to - from) * (i + phase) / n);
            int k = Arrays.binarySearch(kept, want);
            if (k < 0) k = Math.min(kept.length - 1, -k - 1);
            long us = kept[k];
            if (us < from || us >= to) continue;
            if (!out.contains(us)) out.add(us);
        }
        long[] r = new long[out.size()];
        for (int i = 0; i < r.length; i++) r[i] = out.get(i);
        return r;
    }

    static int even(double v) { int i = (int) Math.round(v); return Math.max(2, i - (i & 1)); }

    // ------------------------------------------------------------------ the search
    /** One encoded file (a sample or the whole video). */
    public static final class Copy {
        public String path;
        public long bytes, ms;
    }

    public interface Engine {
        /**
         * Encodes the source from {@code fromUs} up to (not including) {@code toUs} (0 and Long.MAX_VALUE = the whole
         * video, with audio) at this video bitrate, following the plan's size, frame rate and audio.
         */
        Copy encode(Plan p, long fromUs, long toUs, int bitrate) throws Exception;

        /** Worst score of the copy against the original over these source frame times (copy made from fromUs). */
        FrameScore score(Plan p, Copy c, long fromUs, long[] sourceUs) throws Exception;

        void discard(Copy c);
    }

    public static final class Outcome {
        public boolean ok;
        public String skipped;
        public int bitrate;
        public Copy copy;
        public FrameScore score;
        public long before, after, ms;
        public final List<String> trace = new ArrayList<String>();

        Outcome fail(String why) { ok = false; skipped = why; return this; }
    }

    /** Searches the bitrate, encodes the whole video and checks it. The caller removes the copy when it is not kept. */
    public static Outcome run(Facts f, Plan p, Engine e) throws Exception {
        long t0 = System.currentTimeMillis();
        Outcome o = new Outcome();
        o.before = f.fileBytes;
        o.trace.add("plan: " + p);
        try {
            if (p.hiBps < p.loBps)
                return o.fail(String.format(Locale.US, "it is already small for this level (video %d kbps; this level would use at least %d kbps)",
                        f.videoBitrate / 1000, p.loBps / 1000));
            int best = -1, maxFail = -1, b = p.startBps;
            for (int round = 0; round < ROUNDS; round++) {
                boolean pass = probe(f, p, e, b, o);
                if (pass) best = b; else maxFail = b;
                int next;
                if (pass) next = (int) Math.sqrt((double) b * Math.max(p.loBps, maxFail));
                else if (best > 0) next = (int) Math.sqrt((double) b * best);
                else { if (b >= p.hiBps) break; next = Math.min(p.hiBps, b * 2); }
                if (best > 0 && maxFail > 0 && (double) best / maxFail < 1.15) break;
                next = Math.max(p.loBps, Math.min(p.hiBps, next));
                if (Math.abs(next - b) < b * 0.07) break;     // e.g. a pass right above the lowest bitrate allowed
                b = next;
            }
            if (best < 0)
                return o.fail(String.format(Locale.US, "no bitrate up to %d kbps reached the %s target (%s)", p.hiBps / 1000, p.level.label, lastScore(o)));

            int full = (int) Math.min(p.hiBps, best * MARGIN);
            for (int attempt = 0; attempt < 2; attempt++) {
                Copy c = e.encode(p, 0, Long.MAX_VALUE, full);
                FrameScore s = e.score(p, c, 0, p.verifyUs);
                boolean pass = s.passes(p.level);
                o.trace.add(String.format(Locale.US, "whole video at %d kbps: %d KB in %.1f s, %s -> %s", full / 1000, c.bytes >> 10, c.ms / 1000.0, s, pass ? "pass" : "FAIL"));
                if (pass) {
                    o.copy = c; o.score = s; o.bitrate = full; o.after = c.bytes;
                    if (c.bytes > o.before * (1 - MIN_SAVING)) {
                        o.ok = false;
                        o.skipped = String.format(Locale.US, "the copy saves only %.0f%% (at least %.0f%% needed)", 100.0 * (1 - (double) c.bytes / o.before), MIN_SAVING * 100);
                        return o;
                    }
                    o.ok = true;
                    return o;
                }
                e.discard(c);
                if (full >= p.hiBps) break;
                full = (int) Math.min(p.hiBps, full * 1.3);
            }
            return o.fail("the whole video did not reach the target at the searched bitrate (" + lastScore(o) + ")");
        } finally {
            o.ms = System.currentTimeMillis() - t0;
        }
    }

    private static String lastScore(Outcome o) {
        return o.trace.isEmpty() ? "" : o.trace.get(o.trace.size() - 1).replaceFirst("^[^:]*: ", "");
    }

    /** Encodes every sample at bitrate b; passes when the worst frame of every sample passes. Busiest sample first. */
    private static boolean probe(Facts f, Plan p, Engine e, int b, Outcome o) throws Exception {
        FrameScore all = new FrameScore();
        long bytes = 0, ms = 0;
        boolean pass = true;
        for (int i = 0; i < p.samples.size(); i++) {
            long[] w = p.samples.get(i);
            Copy c = e.encode(p, w[0], w[1], b);
            try {
                FrameScore s = e.score(p, c, w[0], p.sampleFrames.get(i));
                all.worst(s, s.worstAtUs);
                bytes += c.bytes; ms += c.ms;
                if (!s.passes(p.level)) { pass = false; break; }
            } finally {
                e.discard(c);
            }
        }
        o.trace.add(String.format(Locale.US, "samples at %d kbps (%d KB, %.1f s): %s, worst at %.2f s -> %s", b / 1000, bytes >> 10, ms / 1000.0, all,
                all.worstAtUs / 1e6, pass ? "pass" : "FAIL"));
        return pass;
    }
}
