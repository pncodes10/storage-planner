package com.storageplanner.v2.util;

/**
 * In-place sorts of primitive arrays without extra memory (PHASE_S_STORE section 4: the streaming callers' compact working
 * sets). Heapsort, so the result depends only on the order relation; callers make keys unique (an ordinal as the last key) so
 * the order is total and equal to what a stable sort of the old object lists produced.
 */
public final class PairSort {
    private PairSort() {}

    /** Sorts the first {@code n} pairs of {@code a} = [k0, v0, k1, v1, …] ascending by (k, v). */
    public static void pairs(long[] a, int n) {
        for (int i = n / 2 - 1; i >= 0; i--) siftPairs(a, i, n);
        for (int end = n - 1; end > 0; end--) { swapPairs(a, 0, end); siftPairs(a, 0, end); }
    }

    private static boolean lessPair(long[] a, int i, int j) {
        long ki = a[2 * i], kj = a[2 * j];
        return ki < kj || ki == kj && a[2 * i + 1] < a[2 * j + 1];
    }
    private static void swapPairs(long[] a, int i, int j) {
        long k = a[2 * i], v = a[2 * i + 1];
        a[2 * i] = a[2 * j]; a[2 * i + 1] = a[2 * j + 1]; a[2 * j] = k; a[2 * j + 1] = v;
    }
    private static void siftPairs(long[] a, int i, int n) {
        while (true) {
            int c = 2 * i + 1;
            if (c >= n) return;
            if (c + 1 < n && lessPair(a, c, c + 1)) c++;
            if (!lessPair(a, i, c)) return;
            swapPairs(a, i, c); i = c;
        }
    }

    /** Order on int elements for {@link #ints}. Must be a total order on the elements being sorted. */
    public interface IntOrder { int compare(int x, int y); }

    /** Sorts the first {@code n} elements of {@code a} ascending by {@code ord}. */
    public static void ints(int[] a, int n, IntOrder ord) {
        for (int i = n / 2 - 1; i >= 0; i--) siftInts(a, i, n, ord);
        for (int end = n - 1; end > 0; end--) { int t = a[0]; a[0] = a[end]; a[end] = t; siftInts(a, 0, end, ord); }
    }
    private static void siftInts(int[] a, int i, int n, IntOrder ord) {
        while (true) {
            int c = 2 * i + 1;
            if (c >= n) return;
            if (c + 1 < n && ord.compare(a[c], a[c + 1]) < 0) c++;
            if (ord.compare(a[i], a[c]) >= 0) return;
            int t = a[i]; a[i] = a[c]; a[c] = t; i = c;
        }
    }

    /** Sorts the first {@code n} longs ascending (no allocation). */
    public static void longs(long[] a, int n) {
        for (int i = n / 2 - 1; i >= 0; i--) siftLongs(a, i, n);
        for (int end = n - 1; end > 0; end--) { long t = a[0]; a[0] = a[end]; a[end] = t; siftLongs(a, 0, end); }
    }
    private static void siftLongs(long[] a, int i, int n) {
        while (true) {
            int c = 2 * i + 1;
            if (c >= n) return;
            if (c + 1 < n && a[c] < a[c + 1]) c++;
            if (a[i] >= a[c]) return;
            long t = a[i]; a[i] = a[c]; a[c] = t; i = c;
        }
    }
}
