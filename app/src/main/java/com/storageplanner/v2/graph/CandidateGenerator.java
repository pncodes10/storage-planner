package com.storageplanner.v2.graph;

import com.storageplanner.v2.model.CandidateSource;
import com.storageplanner.v2.model.Unknown;

import java.util.*;

import static com.storageplanner.v2.graph.GraphConfig.*;

/**
 * Stage 2 candidates (PHASE_3 4.2): multi-index pHash (4 bands, radius 1), capture-time window, name stems.
 * Inputs are parallel arrays over eligible images in fileId order; output pairs (i < j) with a source bitmask.
 * REVIEW_2 F-01: an image whose bucket was truncated or whose per-source cap was hit is marked truncated.
 */
public final class CandidateGenerator {
    public final long[] hash; public final long[] time; public final String[] stem;
    public final int n;
    /**
     * Candidate pairs after {@link #run}: ascending keys (i << 32 | j, i < j) and their source bitmasks, {@link #pairCount}
     * entries (PHASE_S_STORE 4 item 3: primitive arrays instead of a TreeMap; same keys, same ascending order).
     */
    public long[] keys = new long[0];
    public int[] sources = new int[0];
    private int npairs;
    private long[] acc = new long[64];     // (key, source bit) appended by add(), merged by finish()
    private int nacc;
    public final boolean[] truncated;
    public long bucketOverflow, work;

    public CandidateGenerator(long[] hash, long[] time, String[] stem) {
        this.hash = hash; this.time = time; this.stem = stem; this.n = hash.length; this.truncated = new boolean[n];
    }

    public static int ham(long a, long b) { return Long.bitCount((a ^ b) & 0x7fffffffffffffffL); }
    static final int[] BAND_SHIFT = {0, 16, 32, 48}, BAND_BITS = {16, 16, 16, 15};
    static int band(long h, int k) { return (int) ((h >>> BAND_SHIFT[k]) & ((1L << BAND_BITS[k]) - 1)); }

    public CandidateGenerator run() {
        mih(); timeWindow(); stems(); finish();
        return this;
    }

    public int pairCount() { return npairs; }

    /** Sources of the pair with this key, or -1 when it is not a candidate. */
    public int sourcesOf(long key) {
        int i = Arrays.binarySearch(keys, 0, npairs, key);
        return i < 0 ? -1 : sources[i];
    }

    /** The pairs as a map (tests and calibration only). */
    public TreeMap<Long, Integer> pairMap() {
        TreeMap<Long, Integer> m = new TreeMap<Long, Integer>();
        for (int i = 0; i < npairs; i++) m.put(keys[i], sources[i]);
        return m;
    }

    void add(int i, int j, CandidateSource s) {
        long k = i < j ? ((long) i << 32) | j : ((long) j << 32) | i;
        if (2 * nacc + 2 > acc.length) acc = Arrays.copyOf(acc, acc.length * 2);
        acc[2 * nacc] = k; acc[2 * nacc + 1] = s.bit(); nacc++;
    }

    private void finish() {
        com.storageplanner.v2.util.PairSort.pairs(acc, nacc);
        int distinct = 0;
        for (int q = 0; q < nacc; q++) if (q == 0 || acc[2 * q] != acc[2 * q - 2]) distinct++;
        keys = new long[distinct]; sources = new int[distinct];
        int w = -1;
        for (int q = 0; q < nacc; q++) {
            if (q == 0 || acc[2 * q] != acc[2 * q - 2]) { w++; keys[w] = acc[2 * q]; }
            sources[w] |= (int) acc[2 * q + 1];
        }
        npairs = distinct;
        acc = null; nacc = 0;
    }

    /** Keeps the cap nearest of cand (by key, then index) and marks truncation. */
    void capped(int i, List<long[]> cand, int cap, CandidateSource s) {
        Collections.sort(cand, new Comparator<long[]>() { public int compare(long[] a, long[] b) { return a[0] != b[0] ? Long.compare(a[0], b[0]) : Long.compare(a[1], b[1]); } });
        if (cand.size() > cap) truncated[i] = true;
        for (int k = 0; k < cand.size() && k < cap; k++) add(i, (int) cand.get(k)[1], s);
    }

    void mih() {
        // per band one sorted long[] of (band value << 32 | index): a bucket is the run with one value, in ascending index
        long[][] idx = new long[4][];
        for (int k = 0; k < 4; k++) {
            int m = 0;
            for (int i = 0; i < n; i++) if (hash[i] != Long.MIN_VALUE) m++;
            long[] a = new long[m];
            m = 0;
            for (int i = 0; i < n; i++) if (hash[i] != Long.MIN_VALUE) a[m++] = ((long) band(hash[i], k) << 32) | i;
            Arrays.sort(a);
            idx[k] = a;
        }
        for (int i = 0; i < n; i++) {
            if (hash[i] == Long.MIN_VALUE) continue;
            Set<Integer> seen = new HashSet<Integer>();
            List<long[]> cand = new ArrayList<long[]>();
            for (int k = 0; k < 4; k++) {
                int v = band(hash[i], k);
                for (int f = -1; f < BAND_BITS[k]; f++) {
                    long[] a = idx[k];
                    long bv = (long) (f < 0 ? v : v ^ (1 << f));
                    int from = lowerBound(a, bv << 32), to = lowerBound(a, (bv + 1) << 32);
                    if (from == to) continue;
                    int lim = to - from;
                    if (lim > MIH_BUCKET_CAP) { lim = MIH_BUCKET_CAP; truncated[i] = true; bucketOverflow++; }
                    for (int x = 0; x < lim; x++) {
                        int j = (int) a[from + x];
                        work++;
                        if (j == i || !seen.add(j)) continue;
                        int d = ham(hash[i], hash[j]);
                        if (d <= T_CAND) cand.add(new long[]{d, j});
                    }
                }
            }
            capped(i, cand, CAP_PHASH, CandidateSource.PHASH_MIH);
        }
    }

    static int lowerBound(long[] a, long key) {
        int lo = 0, hi = a.length;
        while (lo < hi) { int mid = (lo + hi) >>> 1; if (a[mid] < key) lo = mid + 1; else hi = mid; }
        return lo;
    }

    void timeWindow() {
        int m = 0;
        for (int i = 0; i < n; i++) if (Unknown.knownTime(time[i]) && hash[i] != Long.MIN_VALUE) m++;
        final int[] o = new int[m];
        m = 0;
        for (int i = 0; i < n; i++) if (Unknown.knownTime(time[i]) && hash[i] != Long.MIN_VALUE) o[m++] = i;
        com.storageplanner.v2.util.PairSort.ints(o, m, new com.storageplanner.v2.util.PairSort.IntOrder() {
            public int compare(int a, int b) { return time[a] != time[b] ? Long.compare(time[a], time[b]) : a - b; }
        });
        for (int p = 0; p < m; p++) {
            int i = o[p];
            List<long[]> cand = new ArrayList<long[]>();
            for (int dir = -1; dir <= 1; dir += 2)
                for (int q = p + dir; q >= 0 && q < m && Math.abs(time[o[q]] - time[i]) <= TIME_WINDOW_MS; q += dir) {
                    int j = o[q]; work++;
                    if (ham(hash[i], hash[j]) <= T_BURST) cand.add(new long[]{Math.abs(time[j] - time[i]), j});
                }
            capped(i, cand, CAP_TIME, CandidateSource.TIME_WINDOW);
        }
    }

    void stems() {
        TreeMap<String, List<Integer>> g = new TreeMap<String, List<Integer>>();
        for (int i = 0; i < n; i++) {
            if (stem[i] == null || stem[i].length() < STEM_MIN_LEN) continue;
            List<Integer> l = g.get(stem[i]);
            if (l == null) g.put(stem[i], l = new ArrayList<Integer>());
            l.add(i);
        }
        for (List<Integer> l : g.values()) {
            if (l.size() < 2) continue;
            if (l.size() > STEM_GROUP_MAX) { for (int i : l) truncated[i] = true; continue; }
            for (int x = 0; x < l.size(); x++) for (int y = x + 1; y < l.size(); y++) add(l.get(x), l.get(y), CandidateSource.NAME_STEM);
        }
    }
}
