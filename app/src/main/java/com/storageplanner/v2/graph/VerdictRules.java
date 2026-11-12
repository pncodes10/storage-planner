package com.storageplanner.v2.graph;

import com.storageplanner.v2.model.*;

import static com.storageplanner.v2.graph.GraphConfig.*;

/** Stage-4 verdict (PHASE_3 4.4); first matching row wins. V6 is evaluated before V4/V5 (PHASE_3_NOTES D-3). */
public final class VerdictRules {
    private VerdictRules() {}

    /** smallerInThumbLocation: the image with fewer pixels sits in a thumbnail location (band-B SAME_CONTENT condition). */
    public static PairVerdict verdict(PairMetrics m, int hamming, String transform, int sources, boolean smallerInThumbLocation) {
        return verdict(m, hamming, transform, sources, smallerInThumbLocation, false);
    }

    /**
     * distinctCapture: both capture times known (EXIF or name, second precision) and in different seconds. Such a pair is never
     * SAME_CONTENT or AMBIGUOUS, and a time-window pair is NEAR_SCENE before any edit verdict (PHASE_3_NOTES D-6).
     */
    public static PairVerdict verdict(PairMetrics m, int hamming, String transform, int sources, boolean smallerInThumbLocation, boolean distinctCapture) {
        if (m == null || m.gridW < 8 || m.gridH < 8 || Double.isNaN(m.ssim64Mean)) return PairVerdict.NOT_COMPARABLE;
        boolean id = transform == null;
        boolean timeWin = (sources & CandidateSource.TIME_WINDOW.bit()) != 0;
        boolean bandA = m.band == 1;
        boolean coarse = hamming >= 0 && hamming <= T_SAME_HAM && m.ssim64Mean >= V2_SSIM_MEAN && m.ssim64Min >= V2_SSIM_MIN
                && m.diff64Max <= V2_DIFF_MAX && m.chroma16 <= V2_CHROMA;
        boolean fine = bandA ? (m.ssimFineMin >= V2_FINE_SSIM_MIN && m.diffFineMax <= V2_FINE_DIFF_MAX) : smallerInThumbLocation;
        if (distinctCapture) {
            if (!id && m.ssim64Mean >= GEO_SSIM_MEAN && m.ssim64Min >= GEO_SSIM_MIN) return PairVerdict.GEOMETRIC_EDIT;
            if (m.ssim64Mean >= SCENE_SSIM_MEAN && timeWin) return PairVerdict.NEAR_SCENE;
        }
        if (id && coarse && fine && !distinctCapture) return PairVerdict.SAME_CONTENT;
        if (!id && m.ssim64Mean >= GEO_SSIM_MEAN && m.ssim64Min >= GEO_SSIM_MIN) return PairVerdict.GEOMETRIC_EDIT;
        if (id && !distinctCapture && ambiguous(m, hamming, coarse)) return PairVerdict.AMBIGUOUS;
        if (id && m.ssim64Mean >= COLOR_SSIM_MEAN && m.chroma16 > V2_CHROMA && m.fracBlocksSame >= COLOR_FRAC) return PairVerdict.COLOR_EDIT;
        if (id && m.ssim64Mean >= LOCAL_SSIM_MEAN && m.fracBlocksSame >= LOCAL_FRAC) return PairVerdict.LOCAL_EDIT;
        if (m.ssim64Mean >= SCENE_SSIM_MEAN && timeWin) return PairVerdict.NEAR_SCENE;
        return PairVerdict.DIFFERENT;
    }

    static boolean ambiguous(PairMetrics m, int hamming, boolean coarse) {
        if (hamming < 0 || hamming > T_CAND) return false;
        if (m.band != 1) return coarse;   // band B outside thumbnail locations, all band-A-free V2 conditions met
        return m.ssim64Mean >= AMB_SSIM_MEAN && m.ssim64Min >= AMB_SSIM_MIN && m.diff64Max <= AMB_DIFF_MAX && m.chroma16 <= AMB_CHROMA
                && m.ssimFineMin >= AMB_FINE_SSIM_MIN && m.diffFineMax <= AMB_FINE_DIFF_MAX;
    }

    /** PHASE_3 4.5: 0.5 at threshold, about 0.98 for identical pixels. */
    public static double sameConfidence(PairMetrics m) {
        double x = Math.min(Math.min(n(m.ssim64Mean, V2_SSIM_MEAN, 1), n(m.ssim64Min, V2_SSIM_MIN, 1)),
                Math.min(n(m.diff64Max, V2_DIFF_MAX, 0), n(m.chroma16, V2_CHROMA, 0)));
        if (m.band == 1) x = Math.min(x, Math.min(n(m.ssimFineMin, V2_FINE_SSIM_MIN, 1), n(m.diffFineMax, V2_FINE_DIFF_MAX, 0)));
        return Math.min(0.98, 0.5 + 0.5 * x);
    }
    static double n(double v, double thr, double ideal) {
        if (Double.isNaN(v)) return 0;
        double r = (v - thr) / (ideal - thr);
        return Math.max(0, Math.min(1, r));
    }
}
