package com.storageplanner.v2.risk;

import com.storageplanner.v2.model.*;

/** B: usable bytes recovered per action (PHASE_4 7.2). Aliases and 0-byte files free nothing; unknown size = Unknown.LONG. */
public final class BenefitEstimator {
    private BenefitEstimator() {}
    /** CompressionEstimates: lossless output ratio by type (classic core.Estimates values re-declared); NaN = no lossless transform. */
    public static double losslessRatio(FileContext ctx) {
        String t = ctx.f.type == null ? "" : ctx.f.type;
        if (t.equals("wav")) return 0.55;
        if (t.equals("bmp") || t.equals("tiff")) return 0.45;
        if (t.equals("png") && "screenshot".equals(ctx.f.area)) return 0.80;
        return Double.NaN;
    }
    public static void estimate(FileContext ctx, Assessment a) {
        long size = ctx.f.sizeBytes;
        long del = size < 0 ? Unknown.LONG : ctx.g.alias(ctx.f) ? 0 : size;
        a.benefit.put(Action.DELETE, del); a.benefit.put(Action.QUARANTINE, del);
        double r = losslessRatio(ctx);
        if (size > 0 && !Double.isNaN(r)) a.benefit.put(Action.COMPRESS, Math.max(0L, size - (long) (size * r) - 4096));
    }
}
