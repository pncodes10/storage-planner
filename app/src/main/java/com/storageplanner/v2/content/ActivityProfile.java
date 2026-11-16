package com.storageplanner.v2.content;

import com.storageplanner.v2.model.ActivityInfo;
import com.storageplanner.v2.model.ActivityStatus;

import java.util.Arrays;

/**
 * PHASE_6 8.1 steps 1-7: the bitstream activity profile of a video track from its sample-size table (protect only, A41).
 * Every status other than OK/SHORT means "unknown"; nothing reads unknown as "static". The thresholds are fixed constants.
 */
public final class ActivityProfile {
    private ActivityProfile() {}

    public static final long WINDOW_MS = 1000;
    public static final double PEAK_FACTOR = 4.0;
    public static final double PEAK_MARGIN_BYTES = 2048;
    public static final int MIN_WINDOWS = 10;
    public static final int MAX_INTERVALS = 32;
    public static final int MERGE_GAP_WINDOWS = 2;
    public static final double EARLY_SYNC = 0.8;

    /** Fills a new ActivityInfo from the tables (never null, never throws). */
    public static ActivityInfo video(Mp4Tables.Tables t) {
        ActivityInfo a = new ActivityInfo();
        try {
            fill(t, a);
        } catch (RuntimeException e) {
            return status(a, ActivityStatus.CORRUPT);
        }
        return a;
    }

    private static ActivityInfo status(ActivityInfo keep, ActivityStatus s) {
        ActivityInfo a = new ActivityInfo(); a.videoStatus = s;
        if (keep != null) { a.durationMs = keep.durationMs; a.videoSamples = keep.videoSamples; }
        return a;
    }

    private static void fill(Mp4Tables.Tables t, ActivityInfo a) {
        if (t == null) { a.videoStatus = ActivityStatus.CORRUPT; return; }
        if (t.status != null) { a.videoStatus = t.status; return; }
        Mp4Tables.Track v = t.video;
        if (v == null) return;                                             // step 1: no video track -> NOT_RUN
        if (!v.hasStsz || v.sampleCount <= 0 || !v.hasStts) { a.videoStatus = ActivityStatus.NO_SAMPLE_TABLE; return; }
        if (v.timescale <= 0) { a.videoStatus = ActivityStatus.CORRUPT; return; }
        int ns = v.sampleCount;
        // step 2: sample times in ms (decode order), without overflow
        long[] tms = new long[ns];
        long ticks = 0; int i = 0;
        for (int e = 0; e < v.sttsCount.length && i < ns; e++) {
            long c = v.sttsCount[e], d = v.sttsDelta[e];
            for (long k = 0; k < c && i < ns; k++) { tms[i++] = ms(ticks, v.timescale); ticks += d; }
        }
        if (i != ns) { a.videoStatus = ActivityStatus.CORRUPT; return; }   // stts does not cover the samples: not reliable
        long endTicks = ticks;
        // the stts entries must describe exactly the samples (a table that over- or under-states them is not reliable)
        long total = 0; for (long c : v.sttsCount) total += c;
        if (total != ns) { a.videoStatus = ActivityStatus.CORRUPT; return; }
        a.videoSamples = ns;
        a.durationMs = v.duration > 0 ? ms(v.duration, v.timescale) : ms(endTicks, v.timescale);
        // sync samples
        int[] ss = v.stss;
        if (ss != null) {
            int prev = 0;
            for (int s : ss) { if (s < 1 || s > ns || s <= prev) { a.videoStatus = ActivityStatus.CORRUPT; a.videoSamples = ns; return; } prev = s; }
        }
        if (ss == null || ss.length >= ns) { a.videoStatus = ActivityStatus.ALL_KEYFRAMES; return; }   // step 3 (unknown)
        boolean[] key = new boolean[ns];
        for (int s : ss) key[s - 1] = true;
        // step 4: windows of 1 s over the non-key samples (times are non-decreasing, so equal windows are consecutive)
        long[] wIdx = new long[16], wBytes = new long[16]; int n = 0;
        for (int k = 0; k < ns; k++) {
            if (key[k]) continue;
            long w = tms[k] / WINDOW_MS, sz = v.sizeOf(k);
            if (n == 0 || wIdx[n - 1] != w) {
                if (n == wIdx.length) { wIdx = Arrays.copyOf(wIdx, n * 2); wBytes = Arrays.copyOf(wBytes, n * 2); }
                wIdx[n] = w; wBytes[n] = 0; n++;
            }
            wBytes[n - 1] += sz;
        }
        // step 4b: keyframes inserted early (distance to the previous regular keyframe < 0.8 g) mark their window as peak
        int irregular = 0;
        long[] extra = new long[0]; int ne = 0;
        if (ss.length >= 3) {
            int[] dist = new int[ss.length - 1];
            for (int k = 1; k < ss.length; k++) dist[k - 1] = ss[k] - ss[k - 1];
            Arrays.sort(dist);
            double g = dist.length % 2 == 1 ? dist[dist.length / 2] : (dist[dist.length / 2 - 1] + dist[dist.length / 2]) / 2.0;
            extra = new long[ss.length];
            int lastRegular = ss[0];
            for (int k = 1; k < ss.length; k++) {
                if (ss[k] - lastRegular < EARLY_SYNC * g) { irregular++; extra[ne++] = tms[ss[k] - 1] / WINDOW_MS; }
                else lastRegular = ss[k];
            }
        }
        a.irregularSync = irregular;
        if (n == 0) { a.videoStatus = ActivityStatus.SHORT; return; }
        // step 6
        long[] sorted = Arrays.copyOf(wBytes, n); Arrays.sort(sorted);
        double m = n % 2 == 1 ? sorted[n / 2] : (sorted[n / 2 - 1] + (double) sorted[n / 2]) / 2.0;
        double pk = Math.max(PEAK_FACTOR * m, m + PEAK_MARGIN_BYTES);
        a.medianWindowBytes = round6(m); a.peakThreshold = round6(pk);
        long[] pw = new long[n + ne]; int np = 0;
        for (int k = 0; k < n; k++) if (wBytes[k] > pk) pw[np++] = wIdx[k];
        for (int k = 0; k < ne; k++) pw[np++] = extra[k];
        Arrays.sort(pw, 0, np);
        int uniq = 0;
        for (int k = 0; k < np; k++) if (uniq == 0 || pw[uniq - 1] != pw[k]) pw[uniq++] = pw[k];
        a.peakWindows = uniq;
        long[] iv = new long[2 * MAX_INTERVALS]; int cnt = 0;
        int k = 0;
        while (k < uniq) {
            int j = k;
            while (j + 1 < uniq && pw[j + 1] - pw[j] <= MERGE_GAP_WINDOWS) j++;
            if (cnt < MAX_INTERVALS) { iv[2 * cnt] = pw[k] * WINDOW_MS; iv[2 * cnt + 1] = (pw[j] + 1) * WINDOW_MS; cnt++; }
            k = j + 1;
        }
        a.peaks = Arrays.copyOf(iv, 2 * cnt);
        a.videoStatus = n < MIN_WINDOWS ? ActivityStatus.SHORT : ActivityStatus.OK;   // step 5/7 (peaks computed for SHORT too)
    }

    static long ms(long ticks, long timescale) { return (ticks / timescale) * 1000L + (ticks % timescale) * 1000L / timescale; }

    static double round6(double d) { return Math.round(d * 1e6) / 1e6; }
}
