package com.storageplanner.core;

/**
 * Every number the Assessor decides with, in one place.
 *
 * These are starting values chosen to be conservative. They are not fitted to data yet: when a labelled
 * set of files exists, tune them there (keep "never suggest a valuable file at a safe tier" fixed, then
 * recover as many bytes as that allows) and raise VERSION.
 */
public final class Policy {
    private Policy() {}

    public static final int VERSION = 1;

    // ---- retention: how much keeping the file is worth (0..1). Unknown means valuable.
    public static final double KEEP_DEFAULT = 0.6;
    public static final double KEEP_SENSITIVE = 1.0, KEEP_PEOPLE = 0.9, KEEP_RECORD = 0.85, KEEP_CAMERA = 0.8;

    // ---- confidence
    /** One kind of evidence alone counts for at most this much, unless it is conclusive (a hash, a system fact). */
    public static final double ONE_KIND_CAP = 0.6;
    /** Evidence at or above this is conclusive by itself. */
    public static final double CONCLUSIVE = 0.95;

    // ---- tiers
    /** Evidence needed before anything is called "Safe" or "No loss". */
    public static final double SAFE_CONFIDENCE = 0.9;
    /** "Safe" (R0): rebuilt by itself and worth nothing kept. */
    public static final double SAFE_REGENERABLE = 0.95, SAFE_MAX_KEEP = 0.3;
    /** "No loss" (R1): the same content stays elsewhere, or it can be had again. */
    public static final double NO_LOSS_REDUNDANT = 0.98, NO_LOSS_REGENERABLE = 0.9;

    // ---- gates
    /** Expected harm above this: the deletion is shown for review only, never planned automatically. */
    public static final double REVIEW_HARM = 0.5;

    // ---- WhatsApp Sent copies: "the same photo" only when the hashes are this close and the original is not smaller
    public static final int SENT_SURE_BITS = 4;
}
