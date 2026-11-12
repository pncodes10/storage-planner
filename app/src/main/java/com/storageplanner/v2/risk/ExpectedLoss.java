package com.storageplanner.v2.risk;

import com.storageplanner.v2.model.*;

/**
 * Expected loss, uncertainty penalty and action utility (PHASE_4 7.3), all on one dimensionless [0, 1] scale:
 * EL_mid = U90.mid L.mid (1-rvMid) I_raw; EL_pess = U90.high L.high (1-rvPess) I_raw; UP = EL_pess - EL_mid;
 * utility = Bn - EL_mid - EC - QP - UP = Bn - EL_pess - EC - QP, Bn = B / (B + B0).
 */
public final class ExpectedLoss {
    private ExpectedLoss() {}
    public static double bn(long b) { return b < 0 ? 0 : b / (b + RiskConfig.B0); }
    public static double executionCost(Action x, long size) {
        switch (x) {
            case DELETE: return 0.001;
            case QUARANTINE: return 0.002;
            case COMPRESS: return 0.01 + 0.02 * Math.max(0, size) / (1024.0 * 1024 * 1024);
            case ARCHIVE: return 0.01;
            default: return 0.001;
        }
    }
    public static double elMid(Assessment a, Action x) { return a.u90.mid * a.lossRange.mid * (1 - a.rvMid) * IrreversibilityEstimator.raw(x); }
    public static double elPess(Assessment a, Action x) { return a.u90.high * a.lossRange.high * (1 - a.rvPess) * IrreversibilityEstimator.raw(x); }
    public static void estimate(FileContext ctx, Assessment a) {
        for (Action x : new Action[]{Action.DELETE, Action.QUARANTINE, Action.COMPRESS}) {
            Long b = a.benefit.get(x);
            if (b == null) continue;
            double m = elMid(a, x), p = elPess(a, x);
            a.elMid.put(x, m); a.elPess.put(x, p);
            double qp = 0;    // lossy transforms do not exist before Phase 6
            a.utility.put(x, bn(b) - m - executionCost(x, ctx.f.sizeBytes) - qp - (p - m));
        }
    }
}
