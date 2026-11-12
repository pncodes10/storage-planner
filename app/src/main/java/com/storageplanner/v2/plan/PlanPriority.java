package com.storageplanner.v2.plan;

import com.storageplanner.v2.model.Action;

/** PHASE_5 4.2: spec 10.3 priority classes, applied within a tier (REVIEW_2 F-11). */
public final class PlanPriority {
    private PlanPriority() {}
    public static int of(Action a, String code) {
        if (a == Action.CLEAR_APP_CACHE) return 1;
        if (a == Action.ARCHIVE_APP) return 3;
        if (a == Action.UNINSTALL_APP) return 6;
        if (code == null) return 6;
        if (code.equals("N_ARCHIVE_FOLDER")) return 7;
        if (a.transform()) return 5;
        if (code.startsWith("S_")) return 1;
        if (code.equals("N_EXACT_DUPLICATE")) return 2;
        if (code.equals("N_INSTALLER_INSTALLED") || code.equals("N_APP_ARCHIVE")) return 3;
        if (code.equals("N_OLDER_BACKUP")) return 4;
        if (code.equals("N_LOSSLESS_SMALLER")) return 5;
        if (code.startsWith("N_")) return 2;
        return 6;
    }
}
