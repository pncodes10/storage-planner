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
    /** Lab: what the worst frame's alignment check found (see {@link #alignment}), or null. */
    public String note;

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
        return String.format(Locale.US, "worst of %d frames: SSIM %.4f, PSNR %.1f dB, colour PSNR %.1f dB, colour shift %.1f", frames, ssim, psnr, chromaPsnr, shift)
                + (note != null ? " [" + note + "]" : "");
    }

    /**
     * Whether the copy sits a pixel or two off the original (a picture moved or stretched by the decoder -> OpenGL path,
     * which no bitrate fixes): for the top, middle and bottom third, the shift of the copy (-2..2 px each way) that
     * matches best, and how many dB of brightness PSNR that shift gains over no shift. All "(0,0)" = aligned.
     */
    public static String alignment(int[] a, int[] b, int w, int h) {
        double[] ya = luma(a), yb = luma(b);
        StringBuilder s = new StringBuilder("best shift");
        String[] names = {"top", "middle", "bottom"};
        for (int band = 0; band < 3; band++) {
            int y0 = Math.max(2, band * h / 3), y1 = Math.min(h - 2, (band + 1) * h / 3);
            double base = 0, best = Double.MAX_VALUE;
            int bx = 0, by = 0;
            for (int dy = -2; dy <= 2; dy++)
                for (int dx = -2; dx <= 2; dx++) {
                    double se = 0; long n = 0;
                    for (int y = y0; y < y1; y++)
                        for (int x = 2; x < w - 2; x++) {
                            double d = ya[y * w + x] - yb[(y + dy) * w + x + dx];
                            se += d * d; n++;
                        }
                    double mse = se / Math.max(1, n);
                    if (dx == 0 && dy == 0) base = mse;
                    if (mse < best - 1e-9) { best = mse; bx = dx; by = dy; }
                }
            s.append(String.format(Locale.US, "%s %s (%d,%d) %+.1f dB", band == 0 ? ":" : ",", names[band], bx, by, psnr(best) - psnr(base)));
        }
        return s.toString();
    }

    private static double[] luma(int[] px) {
        double[] y = new double[px.length];
        for (int i = 0; i < px.length; i++) { int p = px[i]; y[i] = 0.299 * ((p >> 16) & 255) + 0.587 * ((p >> 8) & 255) + 0.114 * (p & 255); }
        return y;
    }

}
