package com.storageplanner.tools.faces;

import com.storageplanner.tools.Pixels;
import com.storageplanner.tools.quality.PhotoQuality;

import java.util.List;

/**
 * Face lists as the Platform hands them over: F floats per face
 * {x between the eyes, y, eye distance, score 0..1, x1, y1, x2, y2} where (x1, y1) is the eye on the image's left.
 */
public final class Faces {
    private Faces() {}

    public static final int F = 8;
    /** The detector works on a copy whose long edge is at most this (YuNet's training size). */
    public static final int DETECT_EDGE = 640;

    /** Candidates scoring at least this (but below PhotoQuality.MIN_FACE_CONFIDENCE) get a second, closer look. */
    public static final float LOOK_AGAIN = 0.45f;
    /** Long edge of the crop used for the second look; the crop is taken at full resolution when it fits. */
    static final int CROP_EDGE = 320;
    /** The crop is this many face-box sizes wide. */
    static double CROP_BOXES = 2;
    /** Set to false to see the first pass only (the Lab and tests compare both). */
    public static boolean secondLook = true;

    /** YuNet faces in an ARGB picture, in that picture's pixels (all with score >= 0.3; callers apply their own bar). */
    public static float[] detect(FaceNet net, int[] argb, int w, int h) { return detect(net, argb, w, h, DETECT_EDGE); }

    /**
     * Same, on a copy whose long edge is at most edge. A face that is small in that copy can score just under the bar
     * (a 24 px face in a dark photo: 0.69). So each such candidate is looked at again on a crop around it, taken from
     * the full picture, where it is bigger; the closer look's score replaces the first one.
     */
    public static float[] detect(FaceNet net, int[] argb, int w, int h, int edge) {
        double s = Math.min(1.0, edge / (double) Math.max(w, h));
        int sw = Math.max(1, (int) Math.round(w * s)), sh = Math.max(1, (int) Math.round(h * s));
        int[] px = s >= 1.0 ? argb : Pixels.shrink(argb, w, h, sw, sh);
        List<float[]> d = net.detect(px, sw, sh, 0.3f, 0.3f);
        double fx = w / (double) sw, fy = h / (double) sh;
        List<float[]> out = new java.util.ArrayList<float[]>();
        for (float[] f : d) out.add(map(f, fx, fy, 0, 0));
        if (secondLook && s < 1.0)
            for (int i = 0; i < out.size(); i++) {
                float[] f = out.get(i);
                if (f[14] < LOOK_AGAIN || f[14] >= PhotoQuality.MIN_FACE_CONFIDENCE) continue;
                float[] g = closer(net, argb, w, h, f);
                if (g != null && g[14] > f[14]) out.set(i, g);
            }
        // two candidates that the second look put on the same face: keep the better one
        for (int i = 0; i < out.size(); i++)
            for (int j = out.size() - 1; j > i; j--)
                if (FaceNet.iou(out.get(i), out.get(j)) > 0.3) { if (out.get(j)[14] > out.get(i)[14]) out.set(i, out.get(j)); out.remove(j); }
        float[] o = new float[out.size() * F];
        for (int i = 0; i < out.size(); i++) {
            float[] f = out.get(i);
            // OpenCV's "right eye" (subject's right) is the eye on the image's left
            float x1 = f[4], y1 = f[5], x2 = f[6], y2 = f[7];
            if (x1 > x2) { float t = x1; x1 = x2; x2 = t; t = y1; y1 = y2; y2 = t; }
            o[i * F] = (x1 + x2) / 2; o[i * F + 1] = (y1 + y2) / 2; o[i * F + 2] = (float) Math.hypot(x2 - x1, y2 - y1); o[i * F + 3] = f[14];
            o[i * F + 4] = x1; o[i * F + 5] = y1; o[i * F + 6] = x2; o[i * F + 7] = y2;
        }
        return o;
    }

    /** OpenCV's 15 numbers scaled by (fx, fy) and moved by (ox, oy). */
    static float[] map(float[] f, double fx, double fy, double ox, double oy) {
        float[] g = f.clone();
        for (int k = 0; k < 14; k += 2) { g[k] = (float) (f[k] * fx + (k == 2 ? 0 : ox)); g[k + 1] = (float) (f[k + 1] * fy + (k == 2 ? 0 : oy)); }
        return g;
    }

    /** The face at f, looked at on a crop two box-sizes wide around it; null if the crop shows no face there. */
    static float[] closer(FaceNet net, int[] argb, int w, int h, float[] f) {
        double cx = f[0] + f[2] / 2, cy = f[1] + f[3] / 2, side = CROP_BOXES * Math.max(f[2], f[3]);
        int x0 = (int) Math.max(0, Math.round(cx - side / 2)), y0 = (int) Math.max(0, Math.round(cy - side / 2));
        int x1 = (int) Math.min(w, Math.round(cx + side / 2)), y1 = (int) Math.min(h, Math.round(cy + side / 2));
        int cw = x1 - x0, ch = y1 - y0;
        if (cw < 32 || ch < 32) return null;
        int[] crop = new int[cw * ch];
        for (int y = 0; y < ch; y++) System.arraycopy(argb, (y0 + y) * w + x0, crop, y * cw, cw);
        double s = Math.min(1.0, CROP_EDGE / (double) Math.max(cw, ch));
        int sw = Math.max(1, (int) Math.round(cw * s)), sh = Math.max(1, (int) Math.round(ch * s));
        int[] px = s >= 1.0 ? crop : Pixels.shrink(crop, cw, ch, sw, sh);
        float[] best = null;
        for (float[] g : net.detect(px, sw, sh, 0.3f, 0.3f)) {
            float[] m = map(g, cw / (double) sw, ch / (double) sh, x0, y0);
            double gx = m[0] + m[2] / 2, gy = m[1] + m[3] / 2;
            if (Math.abs(gx - cx) > f[2] / 2 || Math.abs(gy - cy) > f[3] / 2) continue;      // a different face in the crop
            if (best == null || m[14] > best[14]) best = m;
        }
        return best;
    }

    /** From a detector that only gives the point between the eyes and their distance (eyes assumed level). */
    public static float[] fromMidpoints(float[] four) {
        int n = four.length / 4;
        float[] o = new float[n * F];
        for (int i = 0; i < n; i++) {
            float mx = four[i * 4], my = four[i * 4 + 1], d = four[i * 4 + 2];
            o[i * F] = mx; o[i * F + 1] = my; o[i * F + 2] = d; o[i * F + 3] = four[i * 4 + 3];
            o[i * F + 4] = mx - d / 2; o[i * F + 5] = my; o[i * F + 6] = mx + d / 2; o[i * F + 7] = my;
        }
        return o;
    }

    /** Faces that count: score >= PhotoQuality.MIN_FACE_CONFIDENCE and eyes at least 6 px apart. */
    public static int count(float[] f) {
        if (f == null) return -1;
        int n = 0;
        for (int i = 0; i + F - 1 < f.length; i += F) if (f[i + 3] >= PhotoQuality.MIN_FACE_CONFIDENCE && f[i + 2] >= 6) n++;
        return n;
    }
}
