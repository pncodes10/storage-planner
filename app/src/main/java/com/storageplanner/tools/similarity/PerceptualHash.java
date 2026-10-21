package com.storageplanner.tools.similarity;

import java.util.Arrays;

/**
 * Perceptual hash of an 8-bit luma image whose long edge is about 1024 px (same scale as the research
 * prototype, so thresholds carry over). Near-identical pictures differ in few bits.
 */
public final class PerceptualHash {
    private PerceptualHash() {}

    /** 63-bit DCT perceptual hash: 32x32 box downsample, 2-D DCT, top-left 8x8 minus DC vs median. */
    public static long phash(int[] luma, int w, int h) {
        double[][] s = new double[32][32];
        for (int y = 0; y < 32; y++) {
            int y0 = y * h / 32, y1 = Math.max(y0 + 1, (y + 1) * h / 32);
            for (int x = 0; x < 32; x++) {
                int x0 = x * w / 32, x1 = Math.max(x0 + 1, (x + 1) * w / 32);
                long sum = 0; int n = 0;
                for (int yy = y0; yy < y1; yy++) {
                    int row = yy * w;
                    for (int xx = x0; xx < x1; xx++) { sum += luma[row + xx]; n++; }
                }
                s[y][x] = (double) sum / n;
            }
        }
        double[][] c = new double[32][32];
        for (int u = 0; u < 32; u++)
            for (int x = 0; x < 32; x++) c[u][x] = Math.cos((2 * x + 1) * u * Math.PI / 64.0);
        double[][] tmp = new double[8][32];
        for (int u = 0; u < 8; u++)                       // DCT along rows (only first 8 freqs)
            for (int y = 0; y < 32; y++) {
                double acc = 0; for (int x = 0; x < 32; x++) acc += s[y][x] * c[u][x];
                tmp[u][y] = acc;
            }
        double[] coef = new double[63]; int k = 0;
        for (int v = 0; v < 8; v++)
            for (int u = 0; u < 8; u++) {
                double acc = 0; for (int y = 0; y < 32; y++) acc += tmp[u][y] * c[v][y];
                if (u == 0 && v == 0) continue;           // skip DC
                coef[k++] = acc;
            }
        double[] sorted = coef.clone(); Arrays.sort(sorted);
        double med = (sorted[30] + sorted[31] + sorted[32]) / 3.0;
        long bits = 0;
        for (int i = 0; i < 63; i++) if (coef[i] > med) bits |= 1L << i;
        return bits;
    }

    public static int hamming(long a, long b) { return Long.bitCount(a ^ b); }
}
