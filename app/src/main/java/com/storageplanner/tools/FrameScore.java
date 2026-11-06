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
     * Whether the copy sits off the original (a picture moved or stretched on the way through the decoder and OpenGL,
     * which no bitrate fixes): for the top, middle and bottom third, the shift of the copy (-2..2 px each way, in
     * half pixels) that matches best, and how many dB of brightness PSNR it gains over no shift. All "(0,0)" = aligned.
     * A stretch shows as opposite shifts at the top and bottom.
     */
    public static String alignment(int[] a, int[] b, int w, int h) {
        double[] ya = luma(a), yb = luma(b);
        StringBuilder s = new StringBuilder("best shift");
        String[] names = {"top", "middle", "bottom"};
        for (int band = 0; band < 3; band++) {
            int y0 = Math.max(3, band * h / 3), y1 = Math.min(h - 3, (band + 1) * h / 3);
            double base = 0, best = Double.MAX_VALUE;
            double bx = 0, by = 0;
            for (int iy = -4; iy <= 4; iy++)
                for (int ix = -4; ix <= 4; ix++) {
                    double dx = ix / 2.0, dy = iy / 2.0, se = 0;
                    long n = 0;
                    for (int y = y0; y < y1; y += 2)              // every other row: enough to rank the shifts
                        for (int x = 3; x < w - 3; x++) {
                            double d = ya[y * w + x] - sample(yb, w, x + dx, y + dy);
                            se += d * d; n++;
                        }
                    double mse = se / Math.max(1, n);
                    if (ix == 0 && iy == 0) base = mse;
                    if (mse < best - 1e-9) { best = mse; bx = dx; by = dy; }
                }
            s.append(String.format(Locale.US, "%s %s (%s,%s) %+.1f dB", band == 0 ? ":" : ",", names[band], num(bx), num(by), psnr(best) - psnr(base)));
        }
        return s.toString();
    }

    /** Bilinear sample at a half-pixel position. */
    private static double sample(double[] y, int w, double x, double yy) {
        int x0 = (int) Math.floor(x), y0 = (int) Math.floor(yy);
        double fx = x - x0, fy = yy - y0;
        double a = y[y0 * w + x0], b = fx > 0 ? y[y0 * w + x0 + 1] : a;
        double c = fy > 0 ? y[(y0 + 1) * w + x0] : a, d = fx > 0 && fy > 0 ? y[(y0 + 1) * w + x0 + 1] : fy > 0 ? c : b;
        return (a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy;
    }

    private static String num(double v) { return v == Math.rint(v) ? String.valueOf((int) v) : String.valueOf(v); }

    private static double[] luma(int[] px) {
        double[] y = new double[px.length];
        for (int i = 0; i < px.length; i++) { int p = px[i]; y[i] = 0.299 * ((p >> 16) & 255) + 0.587 * ((p >> 8) & 255) + 0.114 * (p & 255); }
        return y;
    }

}
