package com.storageplanner.tools;

import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Build;

/**
 * The "looks identical" guard the compression tools hold every lossy copy to, and where it is checked.
 * The thresholds are the research prototype's (the same as the app's Calibrator, which measures expected
 * savings): SSIM of brightness (Y) >= 0.98 and PSNR(Y) >= 38 dB on 512 px regions of the picture.
 */
public final class Guard {
    private Guard() {}

    public static final double SSIM_MIN = 0.98, PSNR_MIN = 38.0;
    public static final int CROP = 512;

    /**
     * Four regions of {@code CROP} px (smaller pictures: their short side) centred at the given fractions of the
     * width and height: (f1, f1), (f2, f1), (f1, f2), (f2, f2). Compression searches at 0.25 / 0.75 and verifies
     * the full copy at 0.4 / 0.6, so a copy is always checked on regions the quality was not tuned on.
     */
    public static Rect[] crops(int w, int h, double f1, double f2) {
        int c = Math.min(CROP, Math.min(w, h));
        double[] fx = {f1, f2, f1, f2}, fy = {f1, f1, f2, f2};
        Rect[] out = new Rect[4];
        for (int i = 0; i < 4; i++) {
            int x = (int) Math.max(0, Math.min(w - c, fx[i] * w - c / 2.0));
            int y = (int) Math.max(0, Math.min(h - c, fy[i] * h - c / 2.0));
            out[i] = new Rect(x, y, x + c, y + c);
        }
        return out;
    }

    /** Brightness of every pixel: 0.299 R + 0.587 G + 0.114 B. */
    public static double[] luma(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        double[] y = new double[w * h];
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            y[i] = 0.299 * ((p >> 16) & 255) + 0.587 * ((p >> 8) & 255) + 0.114 * (p & 255);
        }
        return y;
    }

    /** Android's lossy WebP format (named WEBP before Android 11, where quality 100 is also lossless). */
    @SuppressWarnings("deprecation")
    static Bitmap.CompressFormat webpLossy() { return Build.VERSION.SDK_INT >= 30 ? Bitmap.CompressFormat.WEBP_LOSSY : Bitmap.CompressFormat.WEBP; }
}
