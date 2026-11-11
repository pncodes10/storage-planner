package com.storageplanner.core;

/**
 * Every number the Assessor decides with, in one place.
 *
 * These are starting values chosen to be conservative. They are not fitted to data yet: when a labelled
 * set of files exists, tune them there (keep "never suggest a valuable file at a safe tier" fixed, then
 * recover as many bytes as that allows) and raise VERSION.
 */
public final class Policy {
    public Policy() {}

    public static final int VERSION = 2;

    // ================================================================== the user's settings
    /** The settings every UnitBuilder uses unless it is given others. The app loads the saved ones into this. */
    public static Policy active = new Policy();

    /** How readily a file is called "not needed". */
    public static final int CAREFUL = 0, BALANCED = 1, THOROUGH = 2;
    public int sensitivity = BALANCED;

    /** The ways of judging whether a file is needed (see Need). A way that is off adds nothing, for or against. */
    public boolean useSource = true;     // where it came from: camera, screenshot, chat, download
    public boolean useContent = true;    // what it shows: one-time code, greeting, ticket, receipt, nothing at all
    public boolean usePeople = true;     // faces in it
    public boolean useQuality = true;    // blur, tiny pictures, clips of a second or two
    public boolean useAge = true;        // how old it is, judged by kind (a code expires, a camera photo does not)
    public boolean useEvent = true;      // taken together with other photos (a trip, a party)
    public boolean useMotion = true;     // videos: whether anything happens in them
    public boolean useHistory = true;    // what the user decided before on the same kind of suggestion
    /** Look inside videos during the scan (a few frames each). Off: videos are judged by length and place only. */
    public boolean lookInsideVideos = true;
    /** Read Marathi and Hindi text (Devanagari script) on pictures, for greetings and forwards in those languages. */
    public boolean readIndic = true;

    /** Debug (Algorithm Lab): record every step of every decision in Assessment.trace. Off in a scan. */
    public boolean trace;

    public Policy copy() {
        Policy p = new Policy();
        p.sensitivity = sensitivity; p.useSource = useSource; p.useContent = useContent; p.usePeople = usePeople; p.useQuality = useQuality;
        p.useAge = useAge; p.useEvent = useEvent; p.useMotion = useMotion; p.useHistory = useHistory; p.lookInsideVideos = lookInsideVideos; p.readIndic = readIndic;
        p.kept = kept; p.accepted = accepted; p.trace = trace;
        return p;
    }

    /** "Balanced; off: age, event" */
    public String describe() {
        StringBuilder off = new StringBuilder();
        String[] n = {"where it came from", "what it shows", "people", "blur and length", "age", "taken with others", "video motion", "earlier choices"};
        boolean[] on = {useSource, useContent, usePeople, useQuality, useAge, useEvent, useMotion, useHistory};
        for (int i = 0; i < n.length; i++) if (!on[i]) off.append(off.length() == 0 ? "" : ", ").append(n[i]);
        return sensitivityName(sensitivity) + " (suggest at an estimate of " + suggestAtOrBelow() + " or less, keep without asking from "
                + keepAtOrAbove() + (planAtOrBelow() >= 0 ? ", add to the plan at " + planAtOrBelow() + " or less" : "") + "); "
                + (off.length() == 0 ? "every way of looking is on" : "switched off: " + off);
    }

    /** For useHistory: unit ids the user kept, and unit id -> technique the user approved. May be null. */
    public java.util.Set<String> kept;
    public java.util.Map<String, String> accepted;

    /** Need score (0..100) at or below which a file with two kinds of evidence against it is suggested for deletion. */
    public int suggestAtOrBelow() { return sensitivity == CAREFUL ? 10 : sensitivity == THOROUGH ? 35 : 25; }
    /** Need score at or above which the file is simply kept: no suggestion, nothing to review. */
    public int keepAtOrAbove() { return sensitivity == CAREFUL ? 45 : sensitivity == THOROUGH ? 70 : 60; }
    /** Need score at or below which the suggestion goes into the default plan; -1 = never (only Thorough does this). */
    public int planAtOrBelow() { return sensitivity == THOROUGH ? 10 : -1; }

    /** Kinds of evidence against a file needed before it is suggested. One reason is never enough, at any setting. */
    public static final int MIN_REASONS = 2;
    /** Decisions on one kind of suggestion before they count (useHistory). */
    public static final int HISTORY_MIN = 5;

    public static String sensitivityName(int s) { return s == CAREFUL ? "Careful" : s == THOROUGH ? "Thorough" : "Balanced"; }

    // ================================================================== fixed values

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
