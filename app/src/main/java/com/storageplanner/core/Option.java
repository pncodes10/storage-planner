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

    // ---- set by the Assessor (see Assessor.java); an option that was not assessed keeps the defaults
    /** Shown for the user to decide, never put into a plan automatically. Only the user's own approval selects it. */
    public final boolean review;
    /** Why it is review-only, in words for the user; null when it is not. */
    public final String note;
    /** The file that stays when this one goes (duplicates, bursts, Sent copies, older backups); null if none. */
    public final String keeps;
    /** Each 0..1: value of keeping the file, another file holds the same content, it can be had again,
     *  expected harm if it goes, strength of the evidence. -1 = not assessed. */
    public final double retention, redundancy, regenerable, harm, confidence;

    public Option(String action, String technique, long saved, int risk, boolean exact, String why) {
        this(action, technique, saved, risk, exact, why, false, null, null, -1, -1, -1, -1, -1);
    }

    Option(String action, String technique, long saved, int risk, boolean exact, String why, boolean review, String note,
           String keeps, double retention, double redundancy, double regenerable, double harm, double confidence) {
        this.action = action; this.technique = technique; this.saved = Math.max(0, saved);
        this.risk = risk; this.exact = exact; this.why = why;
        this.review = review; this.note = note; this.keeps = keeps;
        this.retention = retention; this.redundancy = redundancy; this.regenerable = regenerable;
        this.harm = harm; this.confidence = confidence;
    }

    public double cost() { return COST[risk]; }
}
