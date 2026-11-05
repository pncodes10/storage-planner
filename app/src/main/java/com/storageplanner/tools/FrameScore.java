package com.storageplanner.tools;

import java.util.Locale;

/**
 * Compares one frame of a video copy with the same frame of the original (both as ARGB pixels of the same size):
 * SSIM and PSNR of brightness (Y), PSNR of colour (Cb, Cr) and the largest average shift of red, green or blue,
 * the same measures the photo tools use. Plain Java, so it is tested on a desktop.
 */
public final class FrameScore {
    /** Largest average shift of red, green or blue (0..255): the same limit as ImageCompressor.MAX_COLOR_SHIFT. */
    public static final double MAX_COLOR_SHIFT = 3.0;

    public double ssim = 1, psnr = 99, chromaPsnr = 99, shift = 0;
    /** Source time of the worst frame, microseconds (-1 = not set). */
    public long worstAtUs = -1;
    public int frames;

    public static FrameScore of(int[] a, int[] b, int w, int h) {
        int n = w * h;
        double[] ya = new double[n], yb = new double[n];
        double seY = 0, seC = 0, dr = 0, dg = 0, db = 0;
        for (int i = 0; i < n; i++) {
            int p = a[i], q = b[i];
            int r1 = (p >> 16) & 255, g1 = (p >> 8) & 255, b1 = p & 255, r2 = (q >> 16) & 255, g2 = (q >> 8) & 255, b2 = q & 255;
            ya[i] = 0.299 * r1 + 0.587 * g1 + 0.114 * b1;
            yb[i] = 0.299 * r2 + 0.587 * g2 + 0.114 * b2;
            double d = ya[i] - yb[i];
            seY += d * d;
            double cb = -0.168736 * (r1 - r2) - 0.331264 * (g1 - g2) + 0.5 * (b1 - b2);
            double cr = 0.5 * (r1 - r2) - 0.418688 * (g1 - g2) - 0.081312 * (b1 - b2);
            seC += cb * cb + cr * cr;
            dr += r2 - r1; dg += g2 - g1; db += b2 - b1;
        }
        FrameScore s = new FrameScore();
        s.ssim = Ssim.ssim(ya, yb, w, h);
        s.psnr = psnr(seY / n);
        s.chromaPsnr = psnr(seC / (2.0 * n));
        s.shift = Math.max(Math.abs(dr / n), Math.max(Math.abs(dg / n), Math.abs(db / n)));
        s.frames = 1;
        return s;
    }

    static double psnr(double mse) { return mse <= 1e-10 ? 99 : Math.min(99, 10 * Math.log10(255.0 * 255.0 / mse)); }

    /** Keeps the worse of each measure; the worst frame is the one with the lowest SSIM. */
    public void worst(FrameScore o, long atUs) {
        if (o.ssim < ssim || worstAtUs < 0) worstAtUs = atUs;
        ssim = Math.min(ssim, o.ssim); psnr = Math.min(psnr, o.psnr);
        chromaPsnr = Math.min(chromaPsnr, o.chromaPsnr); shift = Math.max(shift, o.shift);
        frames += o.frames;
    }

    public boolean passes(VideoLevel l) {
        return ssim >= l.ssimMin && psnr >= l.psnrMin && chromaPsnr >= l.chromaPsnrMin && shift <= MAX_COLOR_SHIFT;
    }

    @Override public String toString() {
        return String.format(Locale.US, "worst of %d frames: SSIM %.4f, PSNR %.1f dB, colour PSNR %.1f dB, colour shift %.1f", frames, ssim, psnr, chromaPsnr, shift);
    }

}
