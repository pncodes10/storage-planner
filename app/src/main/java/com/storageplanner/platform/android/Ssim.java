package com.storageplanner.platform.android;

/**
 * SSIM matching scikit-image's structural_similarity defaults (7x7 uniform window, sample
 * covariance, K1=0.01, K2=0.03, data_range=255, mean over the region not touched by padding),
 * which is the guard the research prototype used. Integral images keep it O(pixels).
 */
public final class Ssim {
    private Ssim() {}

    public static double ssim(double[] x, double[] y, int w, int h) {
        final int win = 7, r = 3;
        if (w < win || h < win) return 1.0;
        final double np = win * win, cov = np / (np - 1);
        final double C1 = Math.pow(0.01 * 255, 2), C2 = Math.pow(0.03 * 255, 2);
        int W = w + 1;
        double[] sx = new double[W * (h + 1)], sy = new double[W * (h + 1)], sxx = new double[W * (h + 1)],
                 syy = new double[W * (h + 1)], sxy = new double[W * (h + 1)];
        for (int j = 0; j < h; j++) {
            double ax = 0, ay = 0, axx = 0, ayy = 0, axy = 0;
            for (int i = 0; i < w; i++) {
                double a = x[j * w + i], b = y[j * w + i];
                ax += a; ay += b; axx += a * a; ayy += b * b; axy += a * b;
                int k = (j + 1) * W + (i + 1), u = j * W + (i + 1);
                sx[k] = sx[u] + ax; sy[k] = sy[u] + ay; sxx[k] = sxx[u] + axx; syy[k] = syy[u] + ayy; sxy[k] = sxy[u] + axy;
            }
        }
        double total = 0; long n = 0;
        for (int j = r; j < h - r; j++)
            for (int i = r; i < w - r; i++) {
                int a = (j - r) * W + (i - r), b = (j - r) * W + (i + r + 1), c = (j + r + 1) * W + (i - r), d = (j + r + 1) * W + (i + r + 1);
                double mx = (sx[d] - sx[b] - sx[c] + sx[a]) / np, my = (sy[d] - sy[b] - sy[c] + sy[a]) / np;
                double vx = cov * ((sxx[d] - sxx[b] - sxx[c] + sxx[a]) / np - mx * mx);
                double vy = cov * ((syy[d] - syy[b] - syy[c] + syy[a]) / np - my * my);
                double vxy = cov * ((sxy[d] - sxy[b] - sxy[c] + sxy[a]) / np - mx * my);
                total += ((2 * mx * my + C1) * (2 * vxy + C2)) / ((mx * mx + my * my + C1) * (vx + vy + C2));
                n++;
            }
        return total / n;
    }
}
