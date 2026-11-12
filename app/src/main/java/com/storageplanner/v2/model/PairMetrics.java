package com.storageplanner.v2.model;

/** Stage-4 metrics of a pair (PHASE_3 2.1). NaN = not computed. */
public final class PairMetrics extends Sub {
    public double ssim64Mean = Double.NaN, ssim64Min = Double.NaN, diff64Max = Double.NaN;
    public double ssimFineMin = Double.NaN, diffFineMax = Double.NaN;
    public double chroma16 = Double.NaN, fracBlocksSame = Double.NaN;
    public int gridW, gridH, fineN, band;
}
