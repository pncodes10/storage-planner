package com.storageplanner.core;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Reads a video's index (the MP4 / 3GP "moov" box) without decoding anything: how big every compressed frame is, which
 * frames are keyframes and when each one is shown, and how big each chunk of sound is. That is enough to tell, for the
 * whole video and in milliseconds, how much detail it has (a black, covered or blurred keyframe compresses to almost
 * nothing), how much moves (frames between keyframes are tiny when nothing changes) and how much of it is quiet.
 *
 * VideoCheck uses it to decide whether a video needs any frames decoded at all, and which ones. Plain Java, read-only.
 */
public final class VideoIndex {
    private VideoIndex() {}

    /** Larger indexes are not read (a 3-hour 60 fps video has one of about 10 MB). */
    static final long MAX_MOOV = 64L << 20;

    /** One track of the file. */
    public static final class Track {
        public String handler = "?";       // "vide", "soun", ...
        public long timescale;              // units per second of the times below
        public int[] sizes = new int[0];    // bytes of each sample (frame, or chunk of sound)
        public long[] times = new long[0];  // when each sample starts, in timescale units
        public boolean[] sync;              // keyframes; null = every sample is one
        public double seconds() { return timescale <= 0 || times.length == 0 ? 0 : (times[times.length - 1] + lastDelta) / (double) timescale; }
        long lastDelta;
    }

    /** The tracks of an MP4 / MOV / 3GP file; empty when it has no readable index (not MP4, fragmented, damaged). */
    public static List<Track> read(File f) throws IOException {
        List<Track> out = new ArrayList<Track>();
        RandomAccessFile in = new RandomAccessFile(f, "r");
        try {
            long len = in.length(), pos = 0;
            while (pos + 8 <= len) {
                in.seek(pos);
                long size = in.readInt() & 0xFFFFFFFFL;
                String type = type(in.readInt());
                int header = 8;
                if (size == 1) { if (pos + 16 > len) break; size = in.readLong(); header = 16; }
                else if (size == 0) size = len - pos;
                if (size < header || pos + size > len) break;
                if (type.equals("moov")) {
                    if (size - header > MAX_MOOV) break;
                    byte[] b = new byte[(int) (size - header)];
                    in.readFully(b);
                    for (int p = 0; p + 8 <= b.length; ) {
                        int s = box(b, p);
                        if (s <= 0 || p + s > b.length) break;
                        if (type(b, p + 4).equals("trak")) { Track t = trak(b, p + 8, p + s); if (t != null) out.add(t); }
                        p += s;
                    }
                    break;
                }
                pos += size;
            }
        } finally {
            in.close();
        }
        return out;
    }

    static Track trak(byte[] b, int from, int to) {
        Track t = new Track();
        int[] mdia = find(b, from, to, "mdia");
        if (mdia == null) return null;
        int[] hdlr = find(b, mdia[0], mdia[1], "hdlr");
        if (hdlr != null && hdlr[0] + 12 <= b.length) t.handler = type(b, hdlr[0] + 8);
        int[] mdhd = find(b, mdia[0], mdia[1], "mdhd");
        if (mdhd != null) t.timescale = (b[mdhd[0]] & 255) == 1 ? u32(b, mdhd[0] + 20) : u32(b, mdhd[0] + 12);
        int[] minf = find(b, mdia[0], mdia[1], "minf");
        int[] stbl = minf == null ? null : find(b, minf[0], minf[1], "stbl");
        if (stbl == null) return t;
        int[] stsz = find(b, stbl[0], stbl[1], "stsz");
        if (stsz != null) {
            long fixed = u32(b, stsz[0] + 4), n = u32(b, stsz[0] + 8);
            if (n > 5000000) return t;
            int[] s = new int[(int) n];
            for (int i = 0; i < n; i++) {
                int at = stsz[0] + 12 + 4 * i;
                if (fixed == 0 && at + 4 > stsz[1]) return t;
                s[i] = (int) Math.min(Integer.MAX_VALUE, fixed != 0 ? fixed : u32(b, at));
            }
            t.sizes = s;
        }
        int n = t.sizes.length;
        int[] stts = find(b, stbl[0], stbl[1], "stts");
        long[] times = new long[n];
        if (stts != null) {
            long entries = u32(b, stts[0] + 4), at = 0; int k = 0;
            for (int e = 0; e < entries && k < n; e++) {
                int q = stts[0] + 8 + 8 * e;
                if (q + 8 > stts[1]) break;
                long count = u32(b, q), delta = u32(b, q + 4);
                for (long c = 0; c < count && k < n; c++) { times[k++] = at; at += delta; }
                t.lastDelta = delta;
            }
        }
        t.times = times;
        int[] stss = find(b, stbl[0], stbl[1], "stss");
        if (stss != null) {
            boolean[] sync = new boolean[n];
            long entries = u32(b, stss[0] + 4);
            for (int e = 0; e < entries; e++) {
                int q = stss[0] + 8 + 4 * e;
                if (q + 4 > stss[1]) break;
                long i = u32(b, q) - 1;
                if (i >= 0 && i < n) sync[(int) i] = true;
            }
            t.sync = sync;
        }
        return t;
    }

    // ------------------------------------------------------------------ what the index says about a video

    /** Keyframe detail (bits per pixel) below which a keyframe shows next to nothing: black, covered, badly blurred. */
    public static final double LOW_KEY_BPP = 0.04;
    /** Frames between keyframes below this many bits per pixel change next to nothing (a phone lying still). */
    public static final double STILL_BPP = 0.002;
    /** A second of sound is quiet when it takes less than this share of the video's loud seconds (the 90th percentile). */
    public static final double QUIET_SHARE = 0.25;
    /** A second of compressed sound smaller than this is quiet whatever the rest does (AAC silence: a few hundred bytes). */
    public static final int QUIET_BYTES = 1500;

    /** What the index says. ok is false when there is no usable index (then frames are decoded the old way). */
    public static final class Stats {
        public boolean ok;
        public int frames, keyframes;
        public double seconds;
        public double lowDetail = -1;       // share of keyframes that show next to nothing
        public double still = -1;           // share of the other frames that change next to nothing; -1 without such frames
        public int audio = -1;              // 1 = has a sound track, 0 = none, -1 = unknown
        public double quiet = -1;           // share of seconds that are quiet; -1 without sound
        public boolean flat;                // keyframes all about the same size: the encoder pads them, the sizes tell nothing
        public double medianKeyBpp, maxKeyBpp;
        public long[] richestUs = new long[0];   // the keyframes with the most detail, richest first, at least 1 s apart (microseconds)
    }

    /** Reads the index of a video shown at w × h pixels. Never throws; ok = false when it cannot. */
    public static Stats stats(String path, int w, int h) {
        Stats s = new Stats();
        List<Track> tracks;
        try { tracks = read(new File(path)); } catch (Throwable t) { return s; }
        Track v = null, a = null;
        for (Track t : tracks) {
            if (v == null && "vide".equals(t.handler) && t.sizes.length > 0) v = t;
            if (a == null && "soun".equals(t.handler)) a = t;
        }
        if (v == null || v.timescale <= 0 || w <= 0 || h <= 0) return s;
        double px = (double) w * h;
        s.frames = v.sizes.length;
        s.seconds = v.seconds();
        List<Integer> keys = new ArrayList<Integer>();
        List<Double> other = new ArrayList<Double>();
        for (int i = 0; i < v.sizes.length; i++) {
            if (v.sync == null || v.sync[i]) keys.add(i);
            else other.add(v.sizes[i] * 8 / px);
        }
        s.keyframes = keys.size();
        if (keys.isEmpty()) return s;
        double[] kb = new double[keys.size()];
        int low = 0;
        for (int i = 0; i < kb.length; i++) { kb[i] = v.sizes[keys.get(i)] * 8 / px; if (kb[i] < LOW_KEY_BPP) low++; }
        s.lowDetail = low / (double) kb.length;
        double[] sorted = kb.clone(); Arrays.sort(sorted);
        s.medianKeyBpp = sorted[sorted.length / 2]; s.maxKeyBpp = sorted[sorted.length - 1];
        if (kb.length >= 3) {
            double mean = 0; for (double x : kb) mean += x; mean /= kb.length;
            double var = 0; for (double x : kb) var += (x - mean) * (x - mean);
            s.flat = mean > 0 && Math.sqrt(var / kb.length) / mean < 0.03;
        }
        if (!other.isEmpty()) { int st = 0; for (double x : other) if (x < STILL_BPP) st++; s.still = st / (double) other.size(); }
        // the richest keyframes, at least a second apart
        Integer[] order = keys.toArray(new Integer[0]);
        final Track fv = v;
        Arrays.sort(order, new java.util.Comparator<Integer>() { public int compare(Integer x, Integer y) { return Integer.compare(fv.sizes[y], fv.sizes[x]); } });
        List<Long> rich = new ArrayList<Long>();
        for (Integer i : order) {
            long us = v.times[i] * 1000000L / v.timescale;
            boolean near = false;
            for (long r : rich) if (Math.abs(r - us) < 1000000L) near = true;
            if (!near) rich.add(us);
            if (rich.size() == 3) break;
        }
        s.richestUs = new long[rich.size()];
        for (int i = 0; i < rich.size(); i++) s.richestUs[i] = rich.get(i);
        // sound: bytes per second, quiet seconds against the loud ones
        if (a == null) s.audio = 0;
        else if (a.timescale > 0 && a.sizes.length > 0) {
            s.audio = 1;
            int secs = (int) Math.max(1, Math.ceil(a.seconds()));
            long[] per = new long[secs];
            for (int i = 0; i < a.sizes.length; i++) { int sec = (int) Math.min(secs - 1, a.times[i] / a.timescale); per[sec] += a.sizes[i]; }
            int full = Math.max(1, (int) Math.floor(a.seconds()));          // the last, partial second is left out
            long[] ps = Arrays.copyOf(per, full); Arrays.sort(ps);
            double loud = ps[(int) Math.min(full - 1, Math.floor(0.9 * (full - 1)))];
            int q = 0;
            for (int i = 0; i < full; i++) if (loud <= 0 || per[i] < Math.max(QUIET_SHARE * loud, QUIET_BYTES)) q++;
            s.quiet = loud <= 0 ? 1 : q / (double) full;
        }
        s.ok = true;
        return s;
    }

    // ------------------------------------------------------------------ box helpers

    /** {content start, end} of the first child box of this type between from and to; null if none. */
    static int[] find(byte[] b, int from, int to, String type) {
        for (int p = from; p + 8 <= to; ) {
            int s = box(b, p);
            if (s <= 0 || p + s > to) return null;
            if (type(b, p + 4).equals(type)) {
                int start = p + 8;
                if (type.equals("stsz") || type.equals("stts") || type.equals("stss") || type.equals("hdlr") || type.equals("mdhd")) return new int[]{start, p + s};
                return new int[]{start, p + s};
            }
            p += s;
        }
        return null;
    }

    static int box(byte[] b, int p) {
        if (p + 8 > b.length) return -1;
        long s = u32(b, p);
        return s < 8 || s > Integer.MAX_VALUE ? -1 : (int) s;
    }

    static String type(int v) { return new String(new byte[]{(byte) (v >> 24), (byte) (v >> 16), (byte) (v >> 8), (byte) v}, StandardCharsets.ISO_8859_1); }

    static String type(byte[] b, int p) { return p + 4 <= b.length ? new String(b, p, 4, StandardCharsets.ISO_8859_1) : "????"; }

    static long u32(byte[] b, int p) {
        if (p < 0 || p + 4 > b.length) return 0;
        return ((b[p] & 255L) << 24) | ((b[p + 1] & 255L) << 16) | ((b[p + 2] & 255L) << 8) | (b[p + 3] & 255L);
    }
}
