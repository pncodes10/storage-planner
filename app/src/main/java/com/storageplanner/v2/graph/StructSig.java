package com.storageplanner.v2.graph;

import com.storageplanner.v2.device.DecodedImage;
import com.storageplanner.v2.model.DecodeStatus;
import com.storageplanner.v2.model.PairMetrics;

/** Per-image structural signature and the stage-4 comparator (PHASE_3 4.4). */
public final class StructSig {
    public final int w, h;          // upright luma size (long edge <= 256)
    public final int[] luma;        // w*h
    public final int[][] rgb16;     // [3][256] box-averaged R, G, B on 16x16
    public final DecodeStatus status;

    StructSig(DecodeStatus s) { status = s; w = h = 0; luma = null; rgb16 = null; }
    public StructSig(int w, int h, int[] luma, int[][] rgb16) { this.w = w; this.h = h; this.luma = luma; this.rgb16 = rgb16; status = DecodeStatus.OK; }

    public static StructSig of(DecodedImage d) {
        if (d == null || d.status != DecodeStatus.OK || d.luma == null || d.w < 8 || d.h < 8) return new StructSig(d == null ? DecodeStatus.IO_ERROR : d.status == DecodeStatus.OK ? DecodeStatus.CORRUPT : d.status);
        int[][] rgb = new int[3][];
        if (d.argb != null) {
            for (int c = 0; c < 3; c++) {
                int[] ch = new int[d.w * d.h];
                int sh = 16 - 8 * c;
                for (int i = 0; i < ch.length; i++) ch[i] = (d.argb[i] >> sh) & 255;
                rgb[c] = resample(ch, d.w, d.h, 16, 16);
            }
        } else { int[] l = resample(d.luma, d.w, d.h, 16, 16); rgb[0] = l; rgb[1] = l; rgb[2] = l; }
        int lw = d.w, lh = d.h; int[] l = d.luma;
        int le = Math.max(lw, lh);
        if (le > GraphConfig.SIG_LONG_EDGE) {   // box-average down: the decoder's subsample + bilinear path aliases (PHASE_3_NOTES D-4)
            double k = GraphConfig.SIG_LONG_EDGE / (double) le;
            int nw = Math.max(1, (int) Math.round(lw * k)), nh = Math.max(1, (int) Math.round(lh * k));
            l = resample(l, lw, lh, nw, nh); lw = nw; lh = nh;
        }
        return new StructSig(lw, lh, l, rgb);
    }

    /** Area-average resample (box filter; integer source bounds, at least one pixel per cell). */
    static int[] resample(int[] src, int w, int h, int W, int H) {
        double[] tmp = resampleD(src, w, h, W, H);
        int[] o = new int[tmp.length];
        for (int i = 0; i < o.length; i++) o[i] = (int) Math.round(tmp[i]);
        return o;
    }
    static double[] resampleD(int[] src, int w, int h, int W, int H) {
        double[] o = new double[W * H];
        for (int y = 0; y < H; y++) {
            int y0 = (int) ((long) y * h / H), y1 = Math.max(y0 + 1, (int) ((long) (y + 1) * h / H));
            for (int x = 0; x < W; x++) {
                int x0 = (int) ((long) x * w / W), x1 = Math.max(x0 + 1, (int) ((long) (x + 1) * w / W));
                long s = 0; int n = 0;
                for (int yy = y0; yy < y1 && yy < h; yy++) for (int xx = x0; xx < x1 && xx < w; xx++) { s += src[yy * w + xx]; n++; }
                o[y * W + x] = n == 0 ? 0 : (double) s / n;
            }
        }
        return o;
    }

    static final String[] TRANSFORMS = {null, "rot90", "rot180", "rot270", "mirror", "mirror+rot90", "mirror+rot180", "mirror+rot270"};

    /** Dihedral transform t (0..7) of a w*h raster; dims[0..1] receive the new size. */
    static int[] transform(int[] src, int w, int h, int t, int[] dims) {
        if (t == 0) { dims[0] = w; dims[1] = h; return src; }
        int[] m = src; int mw = w;
        if (t >= 4) { m = new int[w * h]; for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) m[y * w + (w - 1 - x)] = src[y * w + x]; }
        int r = t & 3;
        if (r == 0) { dims[0] = w; dims[1] = h; return m; }
        if (r == 2) { int[] o = new int[w * h]; for (int i = 0; i < o.length; i++) o[o.length - 1 - i] = m[i]; dims[0] = w; dims[1] = h; return o; }
        int[] o = new int[w * h]; int nw = h, nh = mw;
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int nx, ny;
            if (r == 1) { nx = h - 1 - y; ny = x; } else { nx = y; ny = w - 1 - x; }   // rot90 clockwise / rot270
            o[ny * nw + nx] = m[y * w + x];
        }
        dims[0] = nw; dims[1] = nh; return o;
    }

    static double r6(double v) { return Double.isNaN(v) ? v : Math.round(v * 1e6) / 1e6; }

    /**
     * Metrics of a (reference) vs b under transform t; null when the transformed aspect differs from a's by more than tol.
     * bandA: both original upright long edges >= 512.
     */
    static PairMetrics compare(StructSig a, StructSig b, int t, boolean bandA, double tol) {
        int[] d = new int[2];
        int[] bl = transform(b.luma, b.w, b.h, t, d);
        int bw = d[0], bh = d[1];
        double ar = ((double) a.w / a.h) / ((double) bw / bh);
        if (Math.abs(ar - 1) > tol) return null;
        PairMetrics m = new PairMetrics();
        boolean land = a.w >= a.h;
        int gw = land ? 64 : (int) Math.round(64.0 * a.w / a.h), gh = land ? (int) Math.round(64.0 * a.h / a.w) : 64;
        m.gridW = gw; m.gridH = gh; m.band = bandA ? 1 : 2;
        if (gw < 8 || gh < 8) return m;
        double[] ga = resampleD(a.luma, a.w, a.h, gw, gh), gb = resampleD(bl, bw, bh, gw, gh);
        double[] coarse = blocks(ga, gb, gw, gh, 8);
        m.ssim64Mean = r6(coarse[0]); m.ssim64Min = r6(coarse[1]); m.diff64Max = r6(coarse[2]); m.fracBlocksSame = r6(coarse[3]);
        if (bandA) {
            int fn = Math.min(256, Math.min(Math.max(a.w, a.h), Math.max(bw, bh)));
            int fw = land ? fn : (int) Math.round((double) fn * a.w / a.h), fh = land ? (int) Math.round((double) fn * a.h / a.w) : fn;
            m.fineN = fn;
            if (fw >= 16 && fh >= 16) {
                double[] fine = blocks(resampleD(a.luma, a.w, a.h, fw, fh), resampleD(bl, bw, bh, fw, fh), fw, fh, 16);
                m.ssimFineMin = r6(fine[1]); m.diffFineMax = r6(fine[2]);
            }
        }
        double cs = 0;
        int[][] brgb = new int[3][];
        for (int c = 0; c < 3; c++) brgb[c] = transform(b.rgb16[c], 16, 16, t, d);
        for (int i = 0; i < 256; i++) {
            int rgA = a.rgb16[0][i] - a.rgb16[1][i], bgA = a.rgb16[2][i] - a.rgb16[1][i];
            int rgB = brgb[0][i] - brgb[1][i], bgB = brgb[2][i] - brgb[1][i];
            cs += (Math.abs(rgA - rgB) + Math.abs(bgA - bgB)) / 2.0;
        }
        m.chroma16 = r6(cs / 256);
        return m;
    }

    /** [mean SSIM, min SSIM, max mean |diff|, fraction of blocks with SSIM >= BLOCK_SAME_SSIM] over non-overlapping n*n blocks. */
    static double[] blocks(double[] a, double[] b, int w, int h, int n) {
        final double c1 = Math.pow(0.01 * 255, 2), c2 = Math.pow(0.03 * 255, 2);
        double sum = 0, min = Double.MAX_VALUE, dmax = 0; int cnt = 0, same = 0;
        for (int by = 0; by + n <= h; by += n) for (int bx = 0; bx + n <= w; bx += n) {
            double sa = 0, sb = 0, saa = 0, sbb = 0, sab = 0, sd = 0; int k = n * n;
            for (int y = by; y < by + n; y++) for (int x = bx; x < bx + n; x++) {
                double p = a[y * w + x], q = b[y * w + x];
                sa += p; sb += q; saa += p * p; sbb += q * q; sab += p * q; sd += Math.abs(p - q);
            }
            double ma = sa / k, mb = sb / k, va = saa / k - ma * ma, vb = sbb / k - mb * mb, cov = sab / k - ma * mb;
            double ssim = ((2 * ma * mb + c1) * (2 * cov + c2)) / ((ma * ma + mb * mb + c1) * (va + vb + c2));
            sum += ssim; min = Math.min(min, ssim); dmax = Math.max(dmax, sd / k); cnt++;
            if (ssim >= GraphConfig.BLOCK_SAME_SSIM) same++;
        }
        if (cnt == 0) return new double[]{Double.NaN, Double.NaN, Double.NaN, Double.NaN};
        return new double[]{sum / cnt, min, dmax, (double) same / cnt};
    }
}
