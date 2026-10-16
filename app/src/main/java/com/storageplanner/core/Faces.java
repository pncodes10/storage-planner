package com.storageplanner.core;

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

    /** YuNet faces in an ARGB picture, in that picture's pixels (all with score >= 0.3; callers apply their own bar). */
    public static float[] detect(FaceNet net, int[] argb, int w, int h) { return detect(net, argb, w, h, DETECT_EDGE); }

    /** Same, on a copy whose long edge is at most edge. */
    public static float[] detect(FaceNet net, int[] argb, int w, int h, int edge) {
        double s = Math.min(1.0, edge / (double) Math.max(w, h));
        int sw = Math.max(1, (int) Math.round(w * s)), sh = Math.max(1, (int) Math.round(h * s));
        int[] px = s >= 1.0 ? argb : Sorter.shrink(argb, w, h, sw, sh);
        double fx = w / (double) sw, fy = h / (double) sh;
        List<float[]> d = net.detect(px, sw, sh, 0.3f, 0.3f);
        float[] o = new float[d.size() * F];
        for (int i = 0; i < d.size(); i++) {
            float[] f = d.get(i);
            // OpenCV's "right eye" (subject's right) is the eye on the image's left
            float x1 = (float) (f[4] * fx), y1 = (float) (f[5] * fy), x2 = (float) (f[6] * fx), y2 = (float) (f[7] * fy);
            if (x1 > x2) { float t = x1; x1 = x2; x2 = t; t = y1; y1 = y2; y2 = t; }
            o[i * F] = (x1 + x2) / 2; o[i * F + 1] = (y1 + y2) / 2; o[i * F + 2] = (float) Math.hypot(x2 - x1, y2 - y1); o[i * F + 3] = f[14];
            o[i * F + 4] = x1; o[i * F + 5] = y1; o[i * F + 6] = x2; o[i * F + 7] = y2;
        }
        return o;
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
