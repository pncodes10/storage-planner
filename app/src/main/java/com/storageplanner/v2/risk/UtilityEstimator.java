package com.storageplanner.v2.risk;

import com.storageplanner.v2.model.*;

import java.util.EnumMap;

/** U: probability of need per horizon (PHASE_4 3.2). Age moves only low/mid, never high (H10); unknown widens high (H4). */
public final class UtilityEstimator {
    private UtilityEstimator() {}
    static final EnumMap<UsageClass, Double> LAMBDA = new EnumMap<UsageClass, Double>(UsageClass.class);
    static final java.util.EnumSet<UsageClass> DECAYS = java.util.EnumSet.of(UsageClass.INSTALLER, UsageClass.SCREENSHOT, UsageClass.CHAT_MEDIA, UsageClass.DOWNLOAD);
    static {
        Object[] t = {UsageClass.EMPTY, 0.0, UsageClass.TRASH, 0.05, UsageClass.TEMP, 0.05, UsageClass.THUMBNAIL, 0.0, UsageClass.CACHE, 0.0,
            UsageClass.APP_LOG, 0.05, UsageClass.STATUS, 0.05, UsageClass.CHAT_BACKUP, 0.5, UsageClass.INSTALLER, 0.3, UsageClass.ARCHIVE, 0.5,
            UsageClass.DOCUMENT_RECORD, 1.5, UsageClass.DOCUMENT, 1.0, UsageClass.CAMERA_PHOTO, 2.0, UsageClass.CAMERA_VIDEO, 1.0,
            UsageClass.SCREENSHOT, 0.5, UsageClass.CHAT_MEDIA, 0.3, UsageClass.VOICE, 0.5, UsageClass.MUSIC, 3.0, UsageClass.PICTURES, 1.0,
            UsageClass.DOWNLOAD, 0.3, UsageClass.APP_MEDIA, 0.5, UsageClass.OTHER, 1.0, UsageClass.UNKNOWN, 1.0};
        for (int i = 0; i < t.length; i += 2) LAMBDA.put((UsageClass) t[i], (Double) t[i + 1]);
    }
    public static double ageFactor(UsageClass u, FileContext ctx) {
        if (!DECAYS.contains(u) || !"CREATED".equals(ctx.ageSource) || ctx.ageMs < 0) return 1.0;
        long d = ctx.ageMs / RiskConfig.DAY;
        return d <= 30 ? 1.0 : d <= 180 ? 0.7 : d <= 365 ? 0.5 : 0.35;
    }
    /** Family multipliers: one per family (strongest signal), each in [0.5, 2], product clamped to [0.25, 4]. */
    static double multiplier(FileContext ctx) {
        double prod = 1;
        Signal up = SignalExtractor.strongest(ctx.signals, EvidenceFamily.PROV, +1), down = SignalExtractor.strongest(ctx.signals, EvidenceFamily.PROV, -1);
        Signal prov = up == null ? down : down == null ? up : (up.strength >= down.strength ? up : down);
        if (prov != null) prod *= prov.code.equals("WA_FORWARD_NAME") ? 0.7 : prov.code.equals("PATH_KEYWORD_RECORD") || prov.code.startsWith("RULES_") ? 1.5 : 1.0;
        if (SignalExtractor.strongest(ctx.signals, EvidenceFamily.CAPTURE, +1) != null) prod *= 1.2;
        if (SignalExtractor.strongest(ctx.signals, EvidenceFamily.APP, -1) != null && ctx.usage == UsageClass.INSTALLER) prod *= 0.5;   // APP ×0.3 clamped to the family range [0.5, 2]
        return Math.max(0.25, Math.min(4, prod));
    }
    static Interval u(double lo, double mid, double hi, int h) {
        return Interval.of(1 - Math.exp(-lo * h / 365.0), 1 - Math.exp(-mid * h / 365.0), 1 - Math.exp(-hi * h / 365.0));
    }
    public static void estimate(FileContext ctx, Assessment a) {
        UsageClass c = ctx.usage;
        double lam = LAMBDA.get(c), m = multiplier(ctx), af = ageFactor(c, ctx);
        double mid = lam * m * af, low = 0.5 * mid, high = (c == UsageClass.UNKNOWN ? 3 : 2) * lam * Math.max(1, m) * (a.anyUnknown ? 1.5 : 1);
        if (c == UsageClass.UNKNOWN) low = 0.1 * mid;
        a.u30 = u(low, mid, high, 30); a.u90 = u(low, mid, high, 90); a.u365 = u(low, mid, high, 365); a.u1095 = u(low, mid, high, 1095);
    }
}
