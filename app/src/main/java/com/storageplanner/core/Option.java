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

    // ---- set by the Assessor (see Assessor.java) right after it makes the option; defaults = not assessed
    /** Shown for the user to decide, never put into a plan automatically. Only the user's own approval selects it. */
    public boolean review;
    /** Why it is review-only, in words for the user; null when it is not. */
    public String note;
    /** The file that stays when this one goes (duplicates, bursts, Sent copies, older backups); null if none. */
    public String keeps;
    /** Each 0..1: value of keeping the file, another file holds the same content, it can be had again,
     *  expected harm if it goes, strength of the evidence. -1 = not assessed. */
    public double retention = -1, redundancy = -1, regenerable = -1, harm = -1, confidence = -1;
    /** For suggestions decided by Need: how likely the user needs the file, 0..100; -1 otherwise. */
    public int need = -1;
    /** What spoke for and against needing it, in words. */
    public String evidence;
    /** Need is so low, on several kinds of evidence, that the default plan includes it (Thorough setting only). */
    public boolean sure;

    public Option(String action, String technique, long saved, int risk, boolean exact, String why) {
        this.action = action; this.technique = technique; this.saved = Math.max(0, saved);
        this.risk = risk; this.exact = exact; this.why = why;
    }

    /** May the default plan (every safe step) take this by itself? */
    public boolean safeStep() { return !review && (risk <= R2 || sure); }

    public double cost() { return COST[risk]; }
}
