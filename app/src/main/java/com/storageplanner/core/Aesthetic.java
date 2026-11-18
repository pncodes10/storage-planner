package com.storageplanner.core;

/**
 * A learned "is this a nice photo" score, 1 to 10, from SceneNet's 1024 features (one linear layer, 1025 weights).
 *
 * It copies NIMA (Talebi & Milanfar 2018; the MobileNet aesthetic model by idealo, Apache 2.0), which learned from the
 * AVA set: 255,000 photos each rated 1-10 by about 200 photographers. NIMA's average photo scores about 5; blurred,
 * dark, washed-out or badly compressed photos score lower, and well composed, well lit ones higher. On held-out
 * pictures this score follows NIMA's closely (Spearman 0.80; see tools/scenemodel/README.md); NIMA itself agrees with
 * the photographers' ratings only roughly (Spearman about 0.6), so read it as a hint, not a verdict.
 */
public final class Aesthetic {
    private Aesthetic() {}

    private static float[] w;

    private static synchronized float[] weights() {
        if (w == null) {
            byte[] raw = java.util.Base64.getDecoder().decode(DATA);
            java.nio.FloatBuffer fb = java.nio.ByteBuffer.wrap(raw).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
            float[] v = new float[fb.remaining()];
            fb.get(v);
            if (v.length != SceneNet.FEATURES + 1) throw new IllegalStateException("aesthetic model size " + v.length);
            w = v;
        }
        return w;
    }

    /** The score, 1..10, from SceneNet's features. */
    public static double score(float[] features) {
        float[] W = weights();
        double s = W[SceneNet.FEATURES];
        for (int i = 0; i < SceneNet.FEATURES; i++) s += W[i] * features[i];
        return Math.max(1, Math.min(10, s));
    }

    /**
     * A word for a score. On NIMA's scale an average AVA photo scores about 5; ordinary phone and web pictures score
     * around 4.6, and spoiled copies of them (blurred, dark, crushed) 3.2-3.9.
     */
    public static String words(double s) {
        if (s < 0) return "not known";
        if (s < 3.5) return "poor";
        if (s < 3.9) return "below average";
        if (s < 5) return "average";
        if (s < 5.8) return "good";
        return "very good";
    }

    static final String DATA =
        "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";
}
