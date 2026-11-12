package com.storageplanner.v2.risk;

import com.storageplanner.v2.model.*;

/**
 * L: loss severity (PHASE_4 4). Factors are raised, never lowered, by signals; unknown content raises every importance high to 0.9.
 * Copies elsewhere are NOT in L (only "origin"); they live in R (double-count guard, P4-4).
 */
public final class LossEstimator {
    private LossEstimator() {}
    static final double N = Double.NaN;
    /** personal, financial, legal, sentimental, origin, replacement: {mid, high} (NaN high = min(1, mid + 0.2)). */
    static double[][] table(UsageClass c, FileContext ctx) {
        String p = ctx.f.path;
        switch (c) {
            case CAMERA_PHOTO: case CAMERA_VIDEO: return new double[][]{{.7, N}, {.1, .6}, {.1, .6}, {.6, N}, {1, N}, {1, N}};
            case SCREENSHOT: return new double[][]{{.4, N}, {.3, .8}, {.2, .7}, {.2, N}, {1, N}, {.8, N}};
            case CHAT_MEDIA: return new double[][]{{.4, N}, {.1, .6}, {.1, .6}, {.4, N}, {.5, 1}, {.6, N}};
            case VOICE: return new double[][]{{.6, N}, {.1, N}, {.1, N}, {.6, N}, {1, N}, {1, N}};
            case DOCUMENT_RECORD: return new double[][]{{.5, N}, Keywords.financial(p) ? new double[]{.9, N} : new double[]{.3, .8},
                    Keywords.legal(p) || Keywords.medical(p) ? new double[]{.9, N} : new double[]{.3, .8}, {.3, N}, {.8, N}, {.9, N}};
            case DOCUMENT: return new double[][]{{.4, N}, {.3, .8}, {.3, .8}, {.2, N}, {.7, N}, {.7, N}};
            case INSTALLER: return new double[][]{{.1, N}, {0, N}, {0, N}, {.1, N}, {.3, .9}, {.5, .9}};
            case ARCHIVE: return new double[][]{{.4, .9}, {.3, .9}, {.3, .9}, {.3, N}, {.7, N}, {.8, N}};
            case CHAT_BACKUP: return new double[][]{{.8, N}, {.2, N}, {.2, N}, {.8, N}, {1, N}, {olderBackup(ctx) ? .6 : 1, N}};
            case UNKNOWN: return new double[][]{{.5, .9}, {.5, .9}, {.5, .9}, {.5, .9}, {1, N}, {1, N}};
            case TEMP: case TRASH: case THUMBNAIL: case CACHE: case APP_LOG: case STATUS: case EMPTY:
                return new double[][]{{.05, N}, {0, N}, {0, N}, {0, N}, {.1, N}, {.1, N}};
            default: return new double[][]{{.4, N}, {.2, .7}, {.2, .7}, {.3, N}, {.6, N}, {.6, N}};
        }
    }
    static boolean olderBackup(FileContext ctx) { return ctx.f.name != null && ctx.f.name.toLowerCase(java.util.Locale.ROOT).matches("msgstore-\\d.*"); }
    static Interval iv(double[] mh) { double hi = Double.isNaN(mh[1]) ? Math.min(1, mh[0] + 0.2) : mh[1]; return Interval.of(0.5 * mh[0], mh[0], hi); }
    static final String[] NAMES = {"personal", "financial", "legal", "sentimental", "origin", "replacement"};

    public static void estimate(FileContext ctx, Assessment a) {
        double[][] t = table(ctx.usage, ctx);
        Interval[] f = new Interval[6];
        for (int i = 0; i < 6; i++) f[i] = iv(t[i]);
        String p = ctx.f.path;
        if (Keywords.financial(p)) f[1] = f[1].max(Interval.of(.9, .9, 1));
        if (Keywords.legal(p) || Keywords.medical(p)) f[2] = f[2].max(Interval.of(.9, .9, 1));
        if (Keywords.sentimental(p)) f[3] = f[3].max(Interval.of(.7, .8, 1));
        if (SignalExtractor.strongest(ctx.signals, EvidenceFamily.CAPTURE, +1) != null)
            f[0] = Interval.of(Math.min(1, f[0].low + .1), Math.min(1, f[0].mid + .1), Math.min(1, f[0].high + .1));
        if (a.anyUnknown && !ctx.usage.junk()) for (int i = 0; i < 4; i++) f[i] = f[i].max(Interval.of(f[i].low, f[i].mid, .9));
        double dens = ctx.f.sizeBytes == 0 ? 0 : 1;
        double[] lb = new double[3];
        for (int b = 0; b < 3; b++) {
            double imp = 0, repl = 0;
            for (int i = 0; i < 4; i++) imp = Math.max(imp, pick(f[i], b));
            for (int i = 4; i < 6; i++) repl = Math.max(repl, pick(f[i], b));
            lb[b] = imp * (0.4 + 0.6 * repl) * dens;
        }
        a.lossRange = Interval.of(lb[0], lb[1], lb[2]).clamp01();
        a.loss = a.lossRange.mid;
        for (int i = 0; i < 6; i++) a.lossFactors.put(NAMES[i], f[i].mid);
        a.lossFactors.put("density", dens);
    }
    static double pick(Interval i, int b) { return b == 0 ? i.low : b == 1 ? i.mid : i.high; }
}
