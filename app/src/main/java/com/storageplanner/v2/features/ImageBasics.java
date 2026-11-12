package com.storageplanner.v2.features;

import com.storageplanner.core.ImageFeatures;
import com.storageplanner.v2.device.DecodedImage;
import com.storageplanner.v2.model.ImageInfo;

/** Tier-3 pixel features (PHASE_2 3.5) from an upright decoded image; pHash and sharpness from the classic ImageFeatures. */
public final class ImageBasics {
    private ImageBasics() {}

    public static int[] luma(DecodedImage d) {
        if (d.luma != null) return d.luma;
        int[] l = new int[d.w * d.h];
        for (int i = 0; i < l.length; i++) { int c = d.argb[i]; l[i] = (299 * ((c >> 16) & 255) + 587 * ((c >> 8) & 255) + 114 * (c & 255)) / 1000; }
        return l;
    }

    /** Fills the tier-3 fields of img; returns the pHash. */
    public static long measure(DecodedImage d, ImageInfo img) {
        int[] L = luma(d);
        int n = L.length;
        long sum = 0; double sq = 0; long lo = 0, hi = 0;
        int[] hist = new int[16];
        for (int v : L) { sum += v; sq += (double) v * v; if (v <= 5) lo++; if (v >= 250) hi++; hist[v >> 4]++; }
        double mean = sum / (double) n;
        img.brightness = mean;
        img.contrast = Math.sqrt(Math.max(0, sq / n - mean * mean));
        img.clipLow = lo / (double) n; img.clipHigh = hi / (double) n;
        img.exposure = (mean - 128) / 128;
        double sh = ImageFeatures.sharpness(L, d.w, d.h);
        img.sharpness = sh < 0 ? Double.NaN : sh;
        // histogram scaled to 10000, remainder to the largest bin
        int[] h16 = new int[16]; int tot = 0, big = 0;
        for (int i = 0; i < 16; i++) { h16[i] = (int) (hist[i] * 10000L / n); tot += h16[i]; if (hist[i] > hist[big]) big = i; }
        h16[big] += 10000 - tot;
        img.lumaHist16 = h16;
        if (d.argb != null) {
            int sx = Math.max(1, d.w / 256), sy = Math.max(1, d.h / 256);
            double srg = 0, syb = 0, srg2 = 0, syb2 = 0, sat = 0; int m = 0;
            for (int y = 0; y < d.h; y += sy) for (int x = 0; x < d.w; x += sx) {
                int c = d.argb[y * d.w + x];
                int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
                double rg = r - g, yb = (r + g) / 2.0 - b;
                srg += rg; syb += yb; srg2 += rg * rg; syb2 += yb * yb;
                int mx = Math.max(r, Math.max(g, b)), mn = Math.min(r, Math.min(g, b));
                sat += mx == 0 ? 0 : (mx - mn) / (double) mx;
                m++;
            }
            double mrg = srg / m, myb = syb / m;
            double vrg = Math.max(0, srg2 / m - mrg * mrg), vyb = Math.max(0, syb2 / m - myb * myb);
            img.colorfulness = Math.sqrt(vrg + vyb) + 0.3 * Math.sqrt(mrg * mrg + myb * myb);
            img.saturation = sat / m;
        }
        img.analysedWidth = d.w; img.analysedHeight = d.h;
        if (d.orientation > 0) img.orientation = d.orientation;
        return ImageFeatures.phash(L, d.w, d.h);
    }
}
