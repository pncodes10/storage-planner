package com.storageplanner.core;

/** One way to free space from a unit. Options of a unit are mutually exclusive. */
public final class Option {
    /** Risk tiers, fixed in advance (same as the research prototype). */
    public static final int R0 = 0, R1 = 1, R2 = 2, R3 = 3, R4 = 4;
    public static final double[] COST = {0.0, 0.5, 1.0, 3.0, 5.0};
    public static final String[] TIER_NAME = {
        "Safe: rebuildable or junk",
        "No loss: duplicates, archiving, lossless",
        "Looks identical: quality-checked re-encode",
        "Small quality trade-off",
        "Needs your confirmation: deletes unique items"
    };

    public final String action;     // delete, clear_cache, archive_app, uninstall_app, reencode_photo, ...
    public final String technique;  // grouping label shown to the user
    public final long saved;        // bytes
    public final int risk;
    public final boolean exact;     // true = measured size; false = estimate
    public final String why;

    public Option(String action, String technique, long saved, int risk, boolean exact, String why) {
        this.action = action; this.technique = technique; this.saved = Math.max(0, saved);
        this.risk = risk; this.exact = exact; this.why = why;
    }

    public double cost() { return COST[risk]; }
}
