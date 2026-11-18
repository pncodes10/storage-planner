package com.storageplanner.core;

/**
 * Smiling or not, and looking at the camera or not, for one face, from the five points the face detector gives
 * (eyes, nose, mouth corners).
 *
 * Smile: a small neural network (NF features, NH hidden units) on a patch around the mouth, lined up with the eyes so
 * a tilted head looks the same. Trained on the SMILEs set (13,166 faces from Labeled Faces in the Wild, marked
 * smiling or not), with the faces shrunk to phone-photo sizes; see tools/smilemodel/README.md for how, and for the
 * accuracy on faces it never saw. The features are computed by this class for training too, so the phone and the
 * training see exactly the same numbers.
 *
 * Looking at the camera: the head is turned towards the camera when the nose sits between the eyes (not off to one
 * side) and at a normal height between the eyes and the mouth (not tipped far up or down), and the eyes are open
 * (EyeState). Where the eyes themselves look (a sideways glance with the head straight) is not judged.
 */
public final class Smile {
    private Smile() {}

    public static final int PW = 32, PH = 20;
    static final int CELL = 4, BINS = 8, POOL = 4;
    static final int NF = (PW / CELL) * (PH / CELL) * BINS + (PW / POOL) * (PH / POOL) + 3, NH = 16;
    /** The patch spans this many eye distances across, and this many down; its centre is DOWN eye distances below the mouth corners. */
    static final double SPAN_W = 1.4, SPAN_H = 0.875, DOWN = 0.04;
    /** A face is called smiling at this probability or more. */
    public static final double SMILING = 0.5;

    /** Turned towards the camera: the nose is at most this many eye distances to the side of the point between the eyes. */
    public static final double MAX_TURN = 0.2;
    /** ... and this far below the eyes, as a share of the eyes-to-mouth height (tipped up: lower; tipped down: higher). */
    public static final double MIN_NOSE = 0.3, MAX_NOSE = 0.75;

    private static float[] w;

    private static synchronized float[] weights() {
        if (w == null) {
            byte[] raw = java.util.Base64.getDecoder().decode(SmileModel.DATA);
            java.nio.FloatBuffer fb = java.nio.ByteBuffer.wrap(raw).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
            float[] v = new float[fb.remaining()];
            fb.get(v);
            if (v.length != 2 * NF + NH * NF + NH + NH + 1) throw new IllegalStateException("smile model size " + v.length);
            w = v;
        }
        return w;
    }

    /** {dx, dy (eye distances, along and across the eye line), scale}: small shifts the answer is averaged over. */
    static final double[][] SHIFTS = {{0, 0, 1}, {0, -0.05, 1}, {0, 0.05, 1}, {0, 0, 0.92}, {0, 0, 1.08}};

    /** Probability that face i (offset into a Faces list, in the pixels of luma) is smiling; NaN without mouth positions. */
    public static double probability(int[] luma, int w, int h, float[] faces, int i) {
        if (!Faces.hasMouth(faces, i)) return Double.NaN;
        double sum = 0;
        for (double[] s : SHIFTS) sum += predict(features(luma, w, h, faces, i, s[0], s[1], s[2], false));
        return sum / SHIFTS.length;
    }

    /** Whether face i is turned towards the camera (nose between the eyes, head not tipped far up or down). */
    public static boolean facing(float[] faces, int i) {
        double[] t = turn(faces, i);
        return t != null && Math.abs(t[0]) <= MAX_TURN && t[1] >= MIN_NOSE && t[1] <= MAX_NOSE;
    }

    /**
     * {sideways (nose offset from the point between the eyes, along the eye line, in eye distances),
     *  height (nose below the eyes as a share of the eyes-to-mouth height)}; null without nose and mouth positions.
     */
    public static double[] turn(float[] f, int i) {
        if (!Faces.hasMouth(f, i)) return null;
        double ex = f[i + 6] - f[i + 4], ey = f[i + 7] - f[i + 5], d = Math.hypot(ex, ey);
        if (d < 1) return null;
        double ux = ex / d, uy = ey / d, vx = -uy, vy = ux;
        double mx = (f[i + 4] + f[i + 6]) / 2, my = (f[i + 5] + f[i + 7]) / 2;
        double nx = f[i + 8] - mx, ny = f[i + 9] - my;
        double mouth = ((f[i + 10] + f[i + 12]) / 2 - mx) * vx + ((f[i + 11] + f[i + 13]) / 2 - my) * vy;
        if (mouth < 0.2 * d) return null;                                   // the points make no sense as a face
        return new double[]{(nx * ux + ny * uy) / d, (nx * vx + ny * vy) / mouth};
    }

    /**
     * The model's input for face i: an aligned PW x PH patch around the mouth (gradient histograms and coarse
     * brightness) and three measurements (mouth width, how high the mouth corners sit, nose to mouth), all in eye
     * distances. dx, dy, sc shift and scale the patch (in eye distances); mirror flips it left to right.
     */
    public static double[] features(int[] l, int w, int h, float[] f, int i, double dx, double dy, double sc, boolean mirror) {
        double ex = f[i + 6] - f[i + 4], ey = f[i + 7] - f[i + 5], d = Math.max(1, Math.hypot(ex, ey));
        double ux = ex / d, uy = ey / d, vx = -uy, vy = ux;
        double ax = f[i + 10], ay = f[i + 11], bx = f[i + 12], by = f[i + 13];
        double cx = (ax + bx) / 2 + (DOWN + dy) * d * vx + dx * d * ux, cy = (ay + by) / 2 + (DOWN + dy) * d * vy + dx * d * uy;
        double sw = SPAN_W * d * sc / PW, sh = SPAN_H * d * sc / PH;
        double[] p = new double[PW * PH];
        for (int j = 0; j < PH; j++)
            for (int k = 0; k < PW; k++) {
                int kk = mirror ? PW - 1 - k : k;
                double acc = 0;
                for (int sy = 0; sy < 2; sy++)
                    for (int sx = 0; sx < 2; sx++) {
                        double a = (kk + (sx + 0.5) / 2.0 - PW / 2.0) * sw, b = (j + (sy + 0.5) / 2.0 - PH / 2.0) * sh;
                        acc += EyeState.sample(l, w, h, cx + a * ux + b * vx, cy + a * uy + b * vy);
                    }
                p[j * PW + k] = acc / 4.0;
            }
        double[] out = new double[NF];
        int k = hog(p, out);
        // geometry, in eye distances, centred on typical values
        double mw = Math.hypot(bx - ax, by - ay) / d;
        double ox = (f[i + 4] + f[i + 6]) / 2, oy = (f[i + 5] + f[i + 7]) / 2;
        double corners = (((ax + bx) / 2 - ox) * vx + ((ay + by) / 2 - oy) * vy) / d;
        double nose = (((ax + bx) / 2 - f[i + 8]) * vx + ((ay + by) / 2 - f[i + 9]) * vy) / d;
        out[k++] = (mw - 0.85) * 4;
        out[k++] = (corners - 1.05) * 4;
        out[k] = (nose - 0.4) * 4;
        return out;
    }

    /** Gradient histograms (CELL x CELL cells, BINS directions, normalised over the patch) and POOL x POOL mean brightness. */
    static int hog(double[] p, double[] f) {
        int n = p.length;
        double m = 0; for (double v : p) m += v; m /= n;
        double var = 0; for (double v : p) var += (v - m) * (v - m);
        double s = Math.sqrt(var / n);
        double[] nn = new double[n];
        for (int i = 0; i < n; i++) nn[i] = (p[i] - m) / (s + 4.0);
        int k = 0;
        for (int cy = 0; cy < PH / CELL; cy++)
            for (int cx = 0; cx < PW / CELL; cx++) {
                for (int y = cy * CELL; y < cy * CELL + CELL; y++)
                    for (int x = cx * CELL; x < cx * CELL + CELL; x++) {
                        double gx = nn[y * PW + Math.min(x + 1, PW - 1)] - nn[y * PW + Math.max(x - 1, 0)];
                        double gy = nn[Math.min(y + 1, PH - 1) * PW + x] - nn[Math.max(y - 1, 0) * PW + x];
                        double mag = Math.sqrt(gx * gx + gy * gy);
                        double ang = Math.atan2(gy, gx);
                        if (ang < 0) ang += Math.PI;
                        if (ang >= Math.PI) ang -= Math.PI;
                        double pos = ang / Math.PI * BINS - 0.5;
                        int b0 = (int) Math.floor(pos); double fr = pos - b0;
                        f[k + Math.floorMod(b0, BINS)] += mag * (1 - fr);
                        f[k + Math.floorMod(b0 + 1, BINS)] += mag * fr;
                    }
                k += BINS;
            }
        double ss = 0; for (int i = 0; i < k; i++) ss += f[i] * f[i];
        double norm = Math.sqrt(ss) + 1e-3;
        for (int i = 0; i < k; i++) f[i] /= norm;
        for (int y = 0; y < PH; y += POOL)
            for (int x = 0; x < PW; x += POOL) {
                double a = 0;
                for (int yy = y; yy < y + POOL; yy++) for (int xx = x; xx < x + POOL; xx++) a += nn[yy * PW + xx];
                f[k++] = a / (POOL * POOL);
            }
        return k;
    }

    /** The network: standardise, 16 ReLU units, logistic output. */
    static double predict(double[] f) {
        float[] W = weights();
        int mean = 0, std = NF, w1 = 2 * NF, b1 = w1 + NH * NF, w2 = b1 + NH, b2 = w2 + NH;
        double[] x = new double[NF];
        for (int i = 0; i < NF; i++) x[i] = (f[i] - W[mean + i]) / W[std + i];
        double z = W[b2];
        for (int j = 0; j < NH; j++) {
            double a = W[b1 + j];
            int o = w1 + j * NF;
            for (int i = 0; i < NF; i++) a += W[o + i] * x[i];
            if (a > 0) z += a * W[w2 + j];
        }
        return 1.0 / (1.0 + Math.exp(-z));
    }
}
