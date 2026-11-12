package com.storageplanner.v2.risk;

import com.storageplanner.v2.model.*;

/** I(action) = I_raw(action) x (1 - rvMid); unknown R gives rvMid 0, so I stays at its raw value (PHASE_4 7.1). */
public final class IrreversibilityEstimator {
    private IrreversibilityEstimator() {}
    public static double raw(Action x) {
        switch (x) {
            case DELETE: case UNINSTALL_APP: return 1.0;
            case QUARANTINE: return 0.25;
            case ARCHIVE: return 0.1;
            case ARCHIVE_APP: return 0.05;
            default: return 0.0;
        }
    }
    public static void estimate(Assessment a) {
        double rv = Double.isNaN(a.rvMid) ? 0 : a.rvMid;
        for (Action x : new Action[]{Action.DELETE, Action.QUARANTINE, Action.COMPRESS, Action.ARCHIVE}) a.irreversibility.put(x, raw(x) * (1 - rv));
    }
}
