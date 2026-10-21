package com.storageplanner.tools;

/** Pixel helpers shared by the tools: down-scaling and luma. */
public final class Pixels {
    private Pixels() {}

    /** Box-filter down-scaling of ARGB pixels. */
    public static int[] shrink(int[] px, int w, int h, int sw, int sh) {
        int[] o = new int[sw * sh];
        for (int y = 0; y < sh; y++) {
            int y0 = y * h / sh, y1 = Math.max(y0 + 1, (y + 1) * h / sh);
            for (int x = 0; x < sw; x++) {
                int x0 = x * w / sw, x1 = Math.max(x0 + 1, (x + 1) * w / sw);
                long rr = 0, gg = 0, bb = 0; int n = 0;
                for (int yy = y0; yy < y1; yy++) for (int xx = x0; xx < x1; xx++) {
                    int c = px[yy * w + xx]; rr += (c >> 16) & 255; gg += (c >> 8) & 255; bb += c & 255; n++;
                }
                o[y * sw + x] = 0xFF000000 | (int) (rr / n) << 16 | (int) (gg / n) << 8 | (int) (bb / n);
            }
        }
        return o;
    }

    public static int[] luma(int[] px) {
        int[] l = new int[px.length];
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            l[i] = (299 * ((p >> 16) & 255) + 587 * ((p >> 8) & 255) + 114 * (p & 255)) / 1000;
        }
        return l;
    }
}
