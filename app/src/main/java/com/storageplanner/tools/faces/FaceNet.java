package com.storageplanner.tools.faces;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Face detector: YuNet (OpenCV Zoo, face_detection_yunet_2023mar, MIT licence, 53 k weights), run in plain Java.
 * The network (depthwise and pointwise convolutions, max-pooling, a small feature pyramid) is read from the
 * binary written by scripts/facemodel/export_yunet.py; decoding and non-maximum suppression follow OpenCV's
 * FaceDetectorYN line by line. Finds faces down to about 10 px and gives both eye positions.
 */
public final class FaceNet {
    private static final int CONV = 1, RELU = 2, POOL = 3, UP = 4, ADD = 5;
    private static final int[] STRIDES = {8, 16, 32};

    private final int nTensors;
    private final int[] heads;                          // cls 8/16/32, obj 8/16/32, bbox 8/16/32, kps 8/16/32
    private final List<Op> ops = new ArrayList<Op>();

    private static final class Op {
        int type, out; int[] in, free;
        int cout, cin, k, stride, pad, group; float[] w, b;
    }

    /** A tensor: channels x height x width. */
    static final class T {
        final int c, h, w; final float[] v;
        T(int c, int h, int w) { this.c = c; this.h = h; this.w = w; v = new float[c * h * w]; }
    }

    private FaceNet(ByteBuffer b) {
        byte[] magic = new byte[4]; b.get(magic);
        if (magic[0] != 'Y' || magic[1] != 'U' || magic[2] != 'N' || magic[3] != 'T') throw new IllegalArgumentException("not a YuNet model");
        nTensors = b.getInt();
        int nOps = b.getInt(), nHeads = b.getInt();
        heads = new int[nHeads];
        for (int i = 0; i < nHeads; i++) heads[i] = b.getInt();
        for (int i = 0; i < nOps; i++) {
            Op o = new Op();
            o.type = b.getInt();
            int nin = b.getInt();
            o.out = b.getInt();
            o.in = new int[nin];
            for (int k = 0; k < nin; k++) o.in[k] = b.getInt();
            o.free = new int[b.getInt()];
            for (int k = 0; k < o.free.length; k++) o.free[k] = b.getInt();
            if (o.type == CONV) {
                o.cout = b.getInt(); o.cin = b.getInt(); o.k = b.getInt(); o.stride = b.getInt(); o.pad = b.getInt(); o.group = b.getInt();
                o.w = new float[o.cout * (o.cin / o.group) * o.k * o.k];
                b.asFloatBuffer().get(o.w); b.position(b.position() + 4 * o.w.length);
                o.b = new float[o.cout];
                b.asFloatBuffer().get(o.b); b.position(b.position() + 4 * o.b.length);
            }
            ops.add(o);
        }
    }

    public static FaceNet load(byte[] model) { return new FaceNet(ByteBuffer.wrap(model).order(ByteOrder.LITTLE_ENDIAN)); }

    /**
     * Faces in an ARGB picture, each as OpenCV's 15 numbers: box x, y, w, h; right eye x, y; left eye x, y; nose x, y;
     * mouth corners (right x, y, left x, y); score 0..1. ("Right eye" is the subject's right, on the image's left.)
     */
    public List<float[]> detect(int[] argb, int w, int h, float scoreThreshold, float nmsThreshold) {
        int pw = ((w - 1) / 32 + 1) * 32, ph = ((h - 1) / 32 + 1) * 32;
        T x = new T(3, ph, pw);                           // BGR, 0..255, zero padding at the right and bottom
        for (int y = 0; y < h; y++)
            for (int i = 0; i < w; i++) {
                int p = argb[y * w + i];
                x.v[y * pw + i] = p & 255; x.v[ph * pw + y * pw + i] = (p >> 8) & 255; x.v[2 * ph * pw + y * pw + i] = (p >> 16) & 255;
            }
        T[] t = new T[nTensors];
        t[0] = x;
        for (Op o : ops) {
            switch (o.type) {
                case CONV: t[o.out] = conv(t[o.in[0]], o); break;
                case RELU: { T a = t[o.in[0]], r = freed(o, o.in[0]) ? a : new T(a.c, a.h, a.w); float[] av = a.v, rv = r.v; for (int i = 0; i < av.length; i++) rv[i] = av[i] > 0 ? av[i] : 0; t[o.out] = r; break; }
                case POOL: t[o.out] = pool(t[o.in[0]]); break;
                case UP: t[o.out] = up(t[o.in[0]]); break;
                case ADD: { T a = t[o.in[0]], c = t[o.in[1]], r = freed(o, o.in[0]) ? a : new T(a.c, a.h, a.w); float[] av = a.v, cv = c.v, rv = r.v; for (int i = 0; i < av.length; i++) rv[i] = av[i] + cv[i]; t[o.out] = r; break; }
                default: throw new IllegalStateException("op " + o.type);
            }
            for (int f : o.free) t[f] = null;
        }
        return decode(t, pw, ph, scoreThreshold, nmsThreshold);
    }

    /** True if this op is the last user of tensor id (its result may then reuse the tensor's memory). */
    private static boolean freed(Op o, int id) { for (int f : o.free) if (f == id) return true; return false; }

    static T conv(T a, Op o) {
        int oh = (a.h + 2 * o.pad - o.k) / o.stride + 1, ow = (a.w + 2 * o.pad - o.k) / o.stride + 1;
        T r = new T(o.cout, oh, ow);
        int n = oh * ow;
        if (o.k == 1 && o.group == 1 && o.stride == 1) {                 // pointwise, 4 output channels per pass
            float[] v = r.v, av = a.v, wt = o.w; int cin = o.cin, co = 0;
            for (; co + 3 < o.cout; co += 4) {
                int r0 = co * n, r1 = r0 + n, r2 = r1 + n, r3 = r2 + n;
                Arrays.fill(v, r0, r0 + n, o.b[co]); Arrays.fill(v, r1, r1 + n, o.b[co + 1]); Arrays.fill(v, r2, r2 + n, o.b[co + 2]); Arrays.fill(v, r3, r3 + n, o.b[co + 3]);
                for (int ci = 0; ci < cin; ci++) {
                    float w0 = wt[co * cin + ci], w1 = wt[(co + 1) * cin + ci], w2 = wt[(co + 2) * cin + ci], w3 = wt[(co + 3) * cin + ci];
                    int ao = ci * n;
                    for (int i = 0; i < n; i++) { float x = av[ao + i]; v[r0 + i] += w0 * x; v[r1 + i] += w1 * x; v[r2 + i] += w2 * x; v[r3 + i] += w3 * x; }
                }
            }
            for (; co < o.cout; co++) {
                int ro = co * n;
                Arrays.fill(v, ro, ro + n, o.b[co]);
                for (int ci = 0; ci < cin; ci++) { float w0 = wt[co * cin + ci]; int ao = ci * n; for (int i = 0; i < n; i++) v[ro + i] += w0 * av[ao + i]; }
            }
            return r;
        }
        int cpg = o.cin / o.group, opg = o.cout / o.group, k = o.k;
        for (int co = 0; co < o.cout; co++) {
            int g = co / opg;
            for (int ci = 0; ci < cpg; ci++) {
                int src = (g * cpg + ci) * a.h * a.w;
                int wo = (co * cpg + ci) * k * k;
                for (int ky = 0; ky < k; ky++)
                    for (int kx = 0; kx < k; kx++) {
                        float wt = o.w[wo + ky * k + kx];
                        for (int y = 0; y < oh; y++) {
                            int sy = y * o.stride + ky - o.pad;
                            if (sy < 0 || sy >= a.h) continue;
                            int rb = co * n + y * ow, sb = src + sy * a.w + kx - o.pad, st = o.stride;
                            int x0 = Math.max(0, (o.pad - kx + st - 1) / st), x1 = Math.min(ow, (a.w - 1 - kx + o.pad) / st + 1);
                            float[] rv = r.v, av = a.v;
                            if (st == 1) for (int xx = x0; xx < x1; xx++) rv[rb + xx] += wt * av[sb + xx];
                            else for (int xx = x0; xx < x1; xx++) rv[rb + xx] += wt * av[sb + xx * st];
                        }
                    }
            }
            float bias = o.b[co];
            for (int i = co * n; i < (co + 1) * n; i++) r.v[i] += bias;
        }
        return r;
    }

    static T pool(T a) {
        int oh = a.h / 2, ow = a.w / 2;
        T r = new T(a.c, oh, ow);
        for (int c = 0; c < a.c; c++)
            for (int y = 0; y < oh; y++)
                for (int x = 0; x < ow; x++) {
                    int s = c * a.h * a.w + 2 * y * a.w + 2 * x;
                    r.v[c * oh * ow + y * ow + x] = Math.max(Math.max(a.v[s], a.v[s + 1]), Math.max(a.v[s + a.w], a.v[s + a.w + 1]));
                }
        return r;
    }

    static T up(T a) {
        T r = new T(a.c, a.h * 2, a.w * 2);
        for (int c = 0; c < a.c; c++)
            for (int y = 0; y < r.h; y++)
                for (int x = 0; x < r.w; x++) r.v[c * r.h * r.w + y * r.w + x] = a.v[c * a.h * a.w + (y / 2) * a.w + x / 2];
        return r;
    }

    private List<float[]> decode(T[] t, int pw, int ph, float thr, float nms) {
        List<float[]> faces = new ArrayList<float[]>();
        for (int s = 0; s < 3; s++) {
            int st = STRIDES[s], cols = pw / st, rows = ph / st;
            T cls = t[heads[s]], obj = t[heads[3 + s]], box = t[heads[6 + s]], kps = t[heads[9 + s]];
            int n = rows * cols;
            for (int r = 0; r < rows; r++)
                for (int c = 0; c < cols; c++) {
                    int idx = r * cols + c;
                    float cs = Math.min(1f, Math.max(0f, sigmoid(cls.v[idx]))), os = Math.min(1f, Math.max(0f, sigmoid(obj.v[idx])));
                    float score = (float) Math.sqrt(cs * os);
                    if (score < thr) continue;
                    // the heads are channels-first here (OpenCV transposes them to channels-last first)
                    float cx = (c + box.v[idx]) * st, cy = (r + box.v[n + idx]) * st;
                    float bw = (float) Math.exp(box.v[2 * n + idx]) * st, bh = (float) Math.exp(box.v[3 * n + idx]) * st;
                    float[] f = new float[15];
                    f[0] = cx - bw / 2f; f[1] = cy - bh / 2f; f[2] = bw; f[3] = bh;
                    for (int k = 0; k < 5; k++) {
                        f[4 + 2 * k] = (kps.v[(2 * k) * n + idx] + c) * st;
                        f[5 + 2 * k] = (kps.v[(2 * k + 1) * n + idx] + r) * st;
                    }
                    f[14] = score;
                    faces.add(f);
                }
        }
        return nmsBoxes(faces, nms);
    }

    static float sigmoid(float x) { return (float) (1.0 / (1.0 + Math.exp(-x))); }

    /** OpenCV's NMSBoxes on integer boxes: best score first, drop boxes overlapping a kept one by more than thr. */
    static List<float[]> nmsBoxes(List<float[]> faces, float thr) {
        List<float[]> sorted = new ArrayList<float[]>(faces);
        java.util.Collections.sort(sorted, new Comparator<float[]>() { public int compare(float[] a, float[] b) { return Float.compare(b[14], a[14]); } });
        List<float[]> keep = new ArrayList<float[]>();
        for (float[] f : sorted) {
            boolean ok = true;
            for (float[] k : keep) if (iou(f, k) > thr) { ok = false; break; }
            if (ok) keep.add(f);
        }
        return keep;
    }

    static double iou(float[] a, float[] b) {
        int ax = (int) a[0], ay = (int) a[1], aw = (int) a[2], ah = (int) a[3], bx = (int) b[0], by = (int) b[1], bw = (int) b[2], bh = (int) b[3];
        int x0 = Math.max(ax, bx), y0 = Math.max(ay, by), x1 = Math.min(ax + aw, bx + bw), y1 = Math.min(ay + ah, by + bh);
        double inter = Math.max(0, x1 - x0) * (double) Math.max(0, y1 - y0);
        double u = (double) aw * ah + (double) bw * bh - inter;
        return u <= 0 ? 0 : inter / u;
    }
}
