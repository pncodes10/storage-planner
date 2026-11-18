package com.storageplanner.core;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * MobileNetV3-Small (Howard et al. 2019; Keras Applications ImageNet weights, Apache 2.0), run in plain Java on a
 * 224 x 224 copy of a picture. About 56 million multiply-adds, 2.5 million weights (stored as 16-bit floats, 5 MB,
 * from tools/scenemodel/export_mnv3.py). Gives the 1000 ImageNet class scores (what is in the picture, used for
 * the scene) and the 1024 numbers just before them (used by the aesthetic score, see Aesthetic).
 */
public final class SceneNet {
    public static final int SIZE = 224, FEATURES = 1024, CLASSES = 1000;

    /** One convolution with batch normalisation folded in: weights [cout][cin/groups][k][k]. */
    static final class Conv {
        boolean dw; int k, stride, cin, cout; float[] w, b;
    }

    /**
     * The blocks: {kernel, stride, squeeze-excite (1/0), hard-swish (1) or ReLU (0), expand conv (1/0)}, as in
     * Keras's MobileNetV3Small.
     */
    static final int[][] BLOCKS = {
        {3, 2, 1, 0, 0}, {3, 2, 0, 0, 1}, {3, 1, 0, 0, 1},
        {5, 2, 1, 1, 1}, {5, 1, 1, 1, 1}, {5, 1, 1, 1, 1}, {5, 1, 1, 1, 1}, {5, 1, 1, 1, 1},
        {5, 2, 1, 1, 1}, {5, 1, 1, 1, 1}, {5, 1, 1, 1, 1},
    };

    private final Conv[] convs;

    private SceneNet(ByteBuffer b) {
        byte[] magic = new byte[4]; b.get(magic);
        if (magic[0] != 'M' || magic[1] != 'N' || magic[2] != 'V' || magic[3] != '3') throw new IllegalArgumentException("not a MobileNetV3 model");
        if (b.getInt() != 1) throw new IllegalArgumentException("model version");
        int n = b.getInt();
        convs = new Conv[n];
        for (int i = 0; i < n; i++) {
            Conv c = new Conv();
            c.dw = b.getInt() == 1; c.k = b.getInt(); c.stride = b.getInt(); c.cin = b.getInt(); c.cout = b.getInt();
            c.w = half(b, c.cout * (c.dw ? 1 : c.cin) * c.k * c.k);
            c.b = half(b, c.cout);
            convs[i] = c;
        }
        int expect = 4;                                            // stem, the last 1x1, the 1024 features, the logits
        for (int[] bl : BLOCKS) expect += 2 + bl[4] + 2 * bl[2];
        if (n != expect || convs[n - 1].cout != CLASSES || convs[n - 2].cout != FEATURES)
            throw new IllegalArgumentException("unexpected network: " + n + " convolutions");
    }

    private static float[] half(ByteBuffer b, int n) {
        float[] o = new float[n];
        for (int i = 0; i < n; i++) o[i] = halfToFloat(b.getShort());
        return o;
    }

    /** IEEE 754 half precision to float (Float.float16ToFloat is not on Android). */
    static float halfToFloat(short hv) {
        int h = hv & 0xFFFF, sign = (h >>> 15) << 31, e = (h >>> 10) & 0x1F, m = h & 0x3FF;
        if (e == 0) {
            if (m == 0) return Float.intBitsToFloat(sign);
            float v = m / 1024f / 16384f;                          // subnormal: m * 2^-24
            return sign == 0 ? v : -v;
        }
        if (e == 31) return Float.intBitsToFloat(sign | 0x7F800000 | m << 13);
        return Float.intBitsToFloat(sign | (e - 15 + 127) << 23 | m << 13);
    }

    public static SceneNet load(byte[] model) { return new SceneNet(ByteBuffer.wrap(model).order(ByteOrder.LITTLE_ENDIAN)); }

    /** What the network says about one picture. */
    public static final class Out {
        public final float[] features = new float[FEATURES];
        public final float[] logits = new float[CLASSES];
    }

    /** The picture (ARGB, any size) squeezed to 224 x 224, as the network's input: RGB scaled to -1..1. */
    public static float[] input(int[] argb, int w, int h) {
        int[] px = resize(argb, w, h, SIZE, SIZE);
        float[] x = new float[3 * SIZE * SIZE];
        int n = SIZE * SIZE;
        for (int i = 0; i < n; i++) {
            int p = px[i];
            x[i] = ((p >> 16) & 255) / 127.5f - 1; x[n + i] = ((p >> 8) & 255) / 127.5f - 1; x[2 * n + i] = (p & 255) / 127.5f - 1;
        }
        return x;
    }

    /** Box-filter shrink, or bilinear when the picture is smaller than the target. */
    static int[] resize(int[] px, int w, int h, int tw, int th) {
        if (w >= tw && h >= th) return Sorter.shrink(px, w, h, tw, th);
        int[] o = new int[tw * th];
        for (int y = 0; y < th; y++)
            for (int x = 0; x < tw; x++) {
                double sx = Math.max(0, Math.min(w - 1, (x + 0.5) * w / tw - 0.5)), sy = Math.max(0, Math.min(h - 1, (y + 0.5) * h / th - 0.5));
                int x0 = (int) sx, y0 = (int) sy, x1 = Math.min(x0 + 1, w - 1), y1 = Math.min(y0 + 1, h - 1);
                double fx = sx - x0, fy = sy - y0;
                int c = 0;
                for (int sh = 0; sh <= 16; sh += 8) {
                    double v = (((px[y0 * w + x0] >> sh) & 255) * (1 - fx) + ((px[y0 * w + x1] >> sh) & 255) * fx) * (1 - fy)
                             + (((px[y1 * w + x0] >> sh) & 255) * (1 - fx) + ((px[y1 * w + x1] >> sh) & 255) * fx) * fy;
                    c |= ((int) Math.round(v) & 255) << sh;
                }
                o[y * tw + x] = 0xFF000000 | c;
            }
        return o;
    }

    public Out run(int[] argb, int w, int h) { return runInput(input(argb, w, h)); }

    /** Runs the network on an input made by input() (3 x 224 x 224). */
    public Out runInput(float[] in) {
        int ci = 0, hh = SIZE, ww = SIZE, c = 3;
        float[] x = in;
        // stem
        Conv st = convs[ci++];
        int[] hw = new int[2];
        x = conv(x, c, hh, ww, st, hw); c = st.cout; hh = hw[0]; ww = hw[1];
        hswish(x);
        for (int[] bl : BLOCKS) {
            float[] in0 = x; int c0 = c;
            if (bl[4] == 1) {
                Conv e = convs[ci++];
                x = conv(x, c, hh, ww, e, hw); c = e.cout;
                act(x, bl[3]);
            }
            Conv d = convs[ci++];
            int h0 = hh, w0 = ww;
            x = conv(x, c, hh, ww, d, hw); hh = hw[0]; ww = hw[1];
            act(x, bl[3]);
            if (bl[2] == 1) {
                Conv s1 = convs[ci++], s2 = convs[ci++];
                float[] m = pool(x, c, hh * ww);
                float[] r = conv(m, c, 1, 1, s1, hw);
                for (int i = 0; i < r.length; i++) r[i] = Math.max(0, r[i]);
                float[] g = conv(r, s1.cout, 1, 1, s2, hw);
                int n = hh * ww;
                for (int k = 0; k < c; k++) {
                    float s = hsigmoid(g[k]);
                    for (int i = k * n; i < (k + 1) * n; i++) x[i] *= s;
                }
            }
            Conv p = convs[ci++];
            x = conv(x, c, hh, ww, p, hw); c = p.cout;
            if (bl[1] == 1 && c0 == c && h0 == hh && w0 == ww) for (int i = 0; i < x.length; i++) x[i] += in0[i];
        }
        Conv last = convs[ci++];
        x = conv(x, c, hh, ww, last, hw); c = last.cout;
        hswish(x);
        float[] m = pool(x, c, hh * ww);
        Conv f = convs[ci++];
        float[] feat = conv(m, c, 1, 1, f, hw);
        hswish(feat);
        Conv lg = convs[ci];
        float[] logits = conv(feat, f.cout, 1, 1, lg, hw);
        Out o = new Out();
        System.arraycopy(feat, 0, o.features, 0, FEATURES);
        System.arraycopy(logits, 0, o.logits, 0, CLASSES);
        return o;
    }

    static void act(float[] x, int hs) { if (hs == 1) hswish(x); else for (int i = 0; i < x.length; i++) if (x[i] < 0) x[i] = 0; }

    static float hsigmoid(float v) { return Math.min(6f, Math.max(0f, v + 3f)) / 6f; }

    static void hswish(float[] x) { for (int i = 0; i < x.length; i++) x[i] *= hsigmoid(x[i]); }

    static float[] pool(float[] x, int c, int n) {
        float[] m = new float[c];
        for (int k = 0; k < c; k++) {
            double s = 0;
            for (int i = k * n; i < (k + 1) * n; i++) s += x[i];
            m[k] = (float) (s / n);
        }
        return m;
    }

    /** A convolution with TensorFlow's "same" padding (the extra row and column go at the bottom and right). */
    static float[] conv(float[] x, int cin, int h, int w, Conv c, int[] hw) {
        if (cin != c.cin) throw new IllegalStateException("channels " + cin + " != " + c.cin);
        int s = c.stride, k = c.k;
        int oh = (h + s - 1) / s, ow = (w + s - 1) / s;
        int pt = Math.max((oh - 1) * s + k - h, 0) / 2, pl = Math.max((ow - 1) * s + k - w, 0) / 2;
        hw[0] = oh; hw[1] = ow;
        int on = oh * ow, n = h * w;
        float[] o = new float[c.cout * on];
        if (k == 1 && s == 1 && !c.dw) {                           // pointwise: a matrix product
            for (int co = 0; co < c.cout; co++) {
                int ob = co * on;
                java.util.Arrays.fill(o, ob, ob + on, c.b[co]);
                int wb = co * cin;
                for (int q = 0; q < cin; q++) {
                    float wv = c.w[wb + q];
                    if (wv == 0) continue;
                    int ib = q * n;
                    for (int i = 0; i < n; i++) o[ob + i] += wv * x[ib + i];
                }
            }
            return o;
        }
        int groupsIn = c.dw ? 1 : cin;
        for (int co = 0; co < c.cout; co++) {
            int ob = co * on;
            java.util.Arrays.fill(o, ob, ob + on, c.b[co]);
            for (int q = 0; q < groupsIn; q++) {
                int ib = (c.dw ? co : q) * n;
                int wb = (co * groupsIn + q) * k * k;
                for (int ky = 0; ky < k; ky++)
                    for (int kx = 0; kx < k; kx++) {
                        float wv = c.w[wb + ky * k + kx];
                        for (int oy = 0; oy < oh; oy++) {
                            int iy = oy * s + ky - pt;
                            if (iy < 0 || iy >= h) continue;
                            int orow = ob + oy * ow, irow = ib + iy * w;
                            int ox0 = 0, ox1 = ow;
                            while (ox0 < ow && ox0 * s + kx - pl < 0) ox0++;
                            while (ox1 > ox0 && (ox1 - 1) * s + kx - pl >= w) ox1--;
                            for (int ox = ox0, ix = ox0 * s + kx - pl; ox < ox1; ox++, ix += s) o[orow + ox] += wv * x[irow + ix];
                        }
                    }
            }
        }
        return o;
    }

    /** Softmax of the logits. */
    public static float[] probabilities(float[] logits) {
        float max = Float.NEGATIVE_INFINITY;
        for (float v : logits) max = Math.max(max, v);
        double sum = 0;
        float[] p = new float[logits.length];
        for (int i = 0; i < p.length; i++) { p[i] = (float) Math.exp(logits[i] - max); sum += p[i]; }
        for (int i = 0; i < p.length; i++) p[i] /= sum;
        return p;
    }
}
