package com.storageplanner.tools.sharpness;

import java.util.Arrays;

/** How sharp the best-focused parts of a picture are, from 8-bit luma at about 1024 px on the long edge. */
public final class Sharpness {
    private Sharpness() {}

    /** 90th percentile of per-tile (64x64) Laplacian variance: high when the best-focused parts are sharp. */
    public static double of(int[] luma, int w, int h) {
        int tw = w / 64, th = h / 64;
        if (tw == 0 || th == 0) return -1;
        double[] tiles = new double[tw * th];
        for (int ty = 0; ty < th; ty++)
            for (int tx = 0; tx < tw; tx++) {
                double sum = 0, sq = 0; int n = 0;
                for (int y = ty * 64; y < ty * 64 + 64; y++) {
                    if (y == 0 || y >= h - 1) continue;
                    int row = y * w;
                    for (int x = tx * 64; x < tx * 64 + 64; x++) {
                        if (x == 0 || x >= w - 1) continue;
                        int i = row + x;
                        double l = luma[i - 1] + luma[i + 1] + luma[i - w] + luma[i + w] - 4.0 * luma[i];
                        sum += l; sq += l * l; n++;
                    }
                }
                double m = n > 0 ? sum / n : 0;
                tiles[ty * tw + tx] = n > 0 ? sq / n - m * m : 0;
            }
        Arrays.sort(tiles);
        return tiles[(int) Math.min(tiles.length - 1, Math.floor(0.9 * (tiles.length - 1) + 0.5))];
    }
}
