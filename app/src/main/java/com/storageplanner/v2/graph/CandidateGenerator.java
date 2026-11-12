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
    public final TreeMap<Long, Integer> pairs = new TreeMap<Long, Integer>();   // (i << 32 | j) -> sources
    public final boolean[] truncated;
    public long bucketOverflow, work;

    public CandidateGenerator(long[] hash, long[] time, String[] stem) {
        this.hash = hash; this.time = time; this.stem = stem; this.n = hash.length; this.truncated = new boolean[n];
    }

    public static int ham(long a, long b) { return Long.bitCount((a ^ b) & 0x7fffffffffffffffL); }
    static final int[] BAND_SHIFT = {0, 16, 32, 48}, BAND_BITS = {16, 16, 16, 15};
    static int band(long h, int k) { return (int) ((h >>> BAND_SHIFT[k]) & ((1L << BAND_BITS[k]) - 1)); }

    public CandidateGenerator run() {
        mih(); timeWindow(); stems();
        return this;
    }

    void add(int i, int j, CandidateSource s) {
        long k = i < j ? ((long) i << 32) | j : ((long) j << 32) | i;
        Integer o = pairs.get(k);
        pairs.put(k, (o == null ? 0 : o) | s.bit());
    }

    /** Keeps the cap nearest of cand (by key, then index) and marks truncation. */
    void capped(int i, List<long[]> cand, int cap, CandidateSource s) {
        Collections.sort(cand, new Comparator<long[]>() { public int compare(long[] a, long[] b) { return a[0] != b[0] ? Long.compare(a[0], b[0]) : Long.compare(a[1], b[1]); } });
        if (cand.size() > cap) truncated[i] = true;
        for (int k = 0; k < cand.size() && k < cap; k++) add(i, (int) cand.get(k)[1], s);
    }

    void mih() {
        List<Map<Integer, int[]>> idx = new ArrayList<Map<Integer, int[]>>();
        for (int k = 0; k < 4; k++) {
            Map<Integer, List<Integer>> m = new HashMap<Integer, List<Integer>>();
            for (int i = 0; i < n; i++) {
                if (hash[i] == Long.MIN_VALUE) continue;
                Integer b = band(hash[i], k);
                List<Integer> l = m.get(b);
                if (l == null) m.put(b, l = new ArrayList<Integer>());
                l.add(i);
            }
            Map<Integer, int[]> mm = new HashMap<Integer, int[]>();
            for (Map.Entry<Integer, List<Integer>> e : m.entrySet()) {
                int[] a = new int[e.getValue().size()];
                for (int x = 0; x < a.length; x++) a[x] = e.getValue().get(x);
                mm.put(e.getKey(), a);
            }
            idx.add(mm);
        }
        for (int i = 0; i < n; i++) {
            if (hash[i] == Long.MIN_VALUE) continue;
            Set<Integer> seen = new HashSet<Integer>();
            List<long[]> cand = new ArrayList<long[]>();
            for (int k = 0; k < 4; k++) {
                int v = band(hash[i], k);
                for (int f = -1; f < BAND_BITS[k]; f++) {
                    int[] bucket = idx.get(k).get(f < 0 ? v : v ^ (1 << f));
                    if (bucket == null) continue;
                    int lim = bucket.length;
                    if (lim > MIH_BUCKET_CAP) { lim = MIH_BUCKET_CAP; truncated[i] = true; bucketOverflow++; }
                    for (int x = 0; x < lim; x++) {
                        int j = bucket[x];
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

    void timeWindow() {
        Integer[] ord = new Integer[n];
        int m = 0;
        for (int i = 0; i < n; i++) if (Unknown.knownTime(time[i]) && hash[i] != Long.MIN_VALUE) ord[m++] = i;
        final Integer[] o = Arrays.copyOf(ord, m);
        Arrays.sort(o, new Comparator<Integer>() { public int compare(Integer a, Integer b) { return time[a] != time[b] ? Long.compare(time[a], time[b]) : a - b; } });
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
