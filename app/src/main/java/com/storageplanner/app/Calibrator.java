package com.storageplanner.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Rect;
import android.os.Build;

import com.storageplanner.core.Estimates;
import com.storageplanner.core.FileItem;
import com.storageplanner.core.Scanner;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Measures real re-encoding savings on a small sample of the user's own files, under the same
 * quality guard as the research prototype (SSIM(Y) >= 0.98 and PSNR(Y) >= 38 dB). Read-only:
 * encodes go to memory and are discarded.
 *
 * Photos are measured with WebP (the encoder every Android exposes to apps since API 14).
 * HEIC usually saves more, so these numbers are conservative.
 */
public final class Calibrator {
    static final double SSIM_MIN = 0.98, PSNR_MIN = 38.0;
    static final int CROP = 512;

    public static void run(Scanner.Result r, Estimates est, Scanner.Progress pr) {
        List<FileItem> cam = new ArrayList<FileItem>(), chat = new ArrayList<FileItem>(), shots = new ArrayList<FileItem>();
        for (FileItem f : r.files) {
            if (f.type == null) continue;
            if (f.type.equals("jpeg") && f.area.equals("camera") && Estimates.bpp(f) >= 1.0
                    && (long) f.width * f.height <= 25_000_000L) cam.add(f);
            else if (f.type.equals("jpeg") && f.area.equals("wa_image") && Estimates.bpp(f) >= 0.5) chat.add(f);
            else if (f.type.equals("png") && f.area.equals("screenshot")) shots.add(f);
        }
        Double p = measureLossy(sample(cam, 6), pr, "Measuring photo compression");
        if (p != null) { est.cameraPhoto = p; est.photoSource = String.format(Locale.US,
                "measured %.0f%% on %d of your photos (WebP at visually identical quality; HEIC usually saves more)", 100 * p, Math.min(6, cam.size())); }
        if (pr.cancelled()) return;
        Double c = measureLossy(sample(chat, 6), pr, "Measuring chat image compression");
        if (c != null) { est.chatImage = c; est.chatSource = String.format(Locale.US,
                "measured %.0f%% on %d of your chat images", 100 * c, Math.min(6, chat.size())); }
        if (pr.cancelled()) return;
        Double s = measureLossless(sample(shots, 5), pr);
        if (s != null) { est.screenshotSource = String.format(Locale.US,
                "measured %.0f%% on %d of your screenshots, pixel-identical", 100 * s, Math.min(5, shots.size())); est.screenshotLossless = s; }
    }

    /** Evenly spaced, deterministic sample. */
    static List<FileItem> sample(List<FileItem> l, int n) {
        Collections.sort(l, new Comparator<FileItem>() { public int compare(FileItem a, FileItem b) { return a.rel.compareTo(b.rel); } });
        if (l.size() <= n) return l;
        List<FileItem> out = new ArrayList<FileItem>();
        for (int i = 0; i < n; i++) out.add(l.get(i * l.size() / n));
        return out;
    }

    @SuppressWarnings("deprecation")
    static Bitmap.CompressFormat lossy() { return Build.VERSION.SDK_INT >= 30 ? Bitmap.CompressFormat.WEBP_LOSSY : Bitmap.CompressFormat.WEBP; }

    /** Median saving (0..1) over files that could be measured, or null. */
    static Double measureLossy(List<FileItem> files, Scanner.Progress pr, String stage) {
        List<Double> savings = new ArrayList<Double>();
        for (int i = 0; i < files.size(); i++) {
            if (pr.cancelled()) return null;
            pr.update(stage, i + 1, files.size());
            FileItem f = files.get(i);
            Bitmap full = null;
            try {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inPreferredConfig = Bitmap.Config.ARGB_8888;
                full = BitmapFactory.decodeFile(f.abs, o);
                if (full == null) continue;
                Rect[] search = crops(full.getWidth(), full.getHeight(), 0.25, 0.75);
                Rect[] verify = crops(full.getWidth(), full.getHeight(), 0.4, 0.6);
                Bitmap[] cs = new Bitmap[search.length];
                for (int k = 0; k < cs.length; k++) cs[k] = Bitmap.createBitmap(full, search[k].left, search[k].top, search[k].width(), search[k].height());
                int lo = 40, hi = 95, best = -1;
                while (lo <= hi) {                                   // lowest quality where every crop passes
                    int q = (lo + hi) / 2;
                    boolean ok = true;
                    for (Bitmap c : cs) if (!passes(c, encode(c, q))) { ok = false; break; }
                    if (ok) { best = q; hi = q - 1; } else lo = q + 1;
                }
                for (Bitmap c : cs) c.recycle();
                if (best < 0) { savings.add(0.0); continue; }
                // full encode, then verify different regions of the real output
                for (int q = best; q <= 95; q += 3) {
                    byte[] data = encode(full, q);
                    if (verifyRegions(full, data, verify)) {          // throws if the phone cannot decode: photo skipped
                        savings.add(Math.max(0.0, 1.0 - data.length / (double) f.size));
                        break;
                    }
                    if (q + 3 > 95) savings.add(0.0);                  // genuinely no quality passes: no saving
                }
            } catch (OutOfMemoryError oom) {
                // skip very large images on low-memory devices
            } catch (Throwable ignored) {
            } finally {
                if (full != null) full.recycle();
            }
        }
        return median(savings);
    }

    static Double measureLossless(List<FileItem> files, Scanner.Progress pr) {
        List<Double> savings = new ArrayList<Double>();
        for (int i = 0; i < files.size(); i++) {
            if (pr.cancelled()) return null;
            pr.update("Measuring screenshot compression", i + 1, files.size());
            FileItem f = files.get(i);
            Bitmap bm = null, back = null;
            try {
                bm = BitmapFactory.decodeFile(f.abs);
                if (bm == null) continue;
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                if (Build.VERSION.SDK_INT >= 30) bm.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, bos);
                else bm.compress(lossy(), 100, bos);                // quality 100 = lossless before API 30
                byte[] d = bos.toByteArray();
                back = BitmapFactory.decodeByteArray(d, 0, d.length);
                if (back == null || !back.sameAs(bm)) continue;     // must be pixel-identical
                savings.add(Math.max(0.0, 1.0 - d.length / (double) f.size));
            } catch (Throwable ignored) {
            } finally {
                if (bm != null) bm.recycle();
                if (back != null) back.recycle();
            }
        }
        return median(savings);
    }

    static byte[] encode(Bitmap b, int q) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        b.compress(lossy(), q, bos);
        return bos.toByteArray();
    }

    static boolean passes(Bitmap ref, byte[] enc) {
        Bitmap d = BitmapFactory.decodeByteArray(enc, 0, enc.length);
        if (d == null) return false;
        try { return guard(luma(ref), luma(d), ref.getWidth(), ref.getHeight()); } finally { d.recycle(); }
    }

    /** Check the guard on regions of the real full-size output. Region decoding where supported, else full decode. */
    @SuppressWarnings("deprecation")
    static boolean verifyRegions(Bitmap full, byte[] enc, Rect[] regions) {
        BitmapRegionDecoder dec = null;
        Bitmap whole = null;
        try {
            try { dec = BitmapRegionDecoder.newInstance(enc, 0, enc.length, false); } catch (Throwable t) { dec = null; }
            if (dec == null) {
                whole = BitmapFactory.decodeByteArray(enc, 0, enc.length);
                if (whole == null) throw new IllegalStateException("encoded image could not be decoded");
            }
            for (Rect r : regions) {
                Bitmap a = Bitmap.createBitmap(full, r.left, r.top, r.width(), r.height());
                Bitmap b = dec != null ? dec.decodeRegion(r, null) : Bitmap.createBitmap(whole, r.left, r.top, r.width(), r.height());
                if (b == null) throw new IllegalStateException("region could not be decoded");
                boolean ok = guard(luma(a), luma(b), r.width(), r.height());
                a.recycle(); b.recycle();
                if (!ok) return false;
            }
            return true;
        } finally {
            if (dec != null) dec.recycle();
            if (whole != null) whole.recycle();
        }
    }

    static Rect[] crops(int w, int h, double f1, double f2) {
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

    static double[] luma(Bitmap b) {
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

    static boolean guard(double[] a, double[] b, int w, int h) {
        double mse = 0;
        for (int i = 0; i < a.length; i++) { double d = a[i] - b[i]; mse += d * d; }
        mse /= a.length;
        double psnr = mse == 0 ? 99 : 10 * Math.log10(255.0 * 255.0 / mse);
        return psnr >= PSNR_MIN && Ssim.ssim(a, b, w, h) >= SSIM_MIN;
    }

    static Double median(List<Double> v) {
        if (v.isEmpty()) return null;
        Double[] a = v.toArray(new Double[0]); Arrays.sort(a);
        return a.length % 2 == 1 ? a[a.length / 2] : (a[a.length / 2 - 1] + a[a.length / 2]) / 2;
    }
}
