package com.storageplanner.v2.model;

import java.util.*;

/**
 * ARCHITECTURE 2.12 + PHASE_5 12 C-5. Phase 5 writes DRAFT and STALE only (PHASE_5 7). The store codec persists planId only
 * (planVersion is recovered from it, "p" + version); a plan's items travel in the CLI JSON.
 */
public final class ActionPlan implements Record {
    public static final long MAX_SAFE = Long.MAX_VALUE;
    public String planId;
    public int planVersion;
    public long createdAt = Unknown.TIME, scanId = Unknown.LONG;
    public String state = "DRAFT";
    public String mode = "standard";
    public long targetBytes;
    /** Highest tier the request allows (YOUR_CALL for a numeric target, NO_LOSS for MAX_SAFE unless the caller says otherwise). */
    public Tier maxTier = Tier.YOUR_CALL;
    public List<PlanItem> items = new ArrayList<PlanItem>();
    /** SAFE + NO_LOSS item bytes; exactOnly excludes estimated (transform) bytes. */
    public long achievableBytes, achievableExactBytes, withYourCallBytes;
    public boolean reachesTarget;
    public Tier highestTier;
    public List<String> reasonCodes = new ArrayList<String>();
    public Map<String, String> reasonParams = new TreeMap<String, String>();
    public int hashPending, validatorDropped;
    public List<String> staleReasons = new ArrayList<String>();

    public static final class PlanItem {
        public String unitId, subject, recId, code;
        public List<String> fileIds = new ArrayList<String>();
        public Action action; public Tier tier;
        public long bytes; public boolean exact = true;
        public int priorityClass;
        public double cost, utility;
        /** Files this item depends on (keepers, primaries): must survive. */
        public List<String> requires = new ArrayList<String>();
        /** Fingerprints at plan time for fileIds and requires (staleness, PHASE_5 7); app subjects: "v=<versionCode>,c=<cache>,d=<data>". */
        public Map<String, Fingerprint> fps = new TreeMap<String, Fingerprint>();
        public String appStats;
    }

    public static int versionOf(String planId) {
        if (planId == null || !planId.startsWith("p")) return 0;
        try { return Integer.parseInt(planId.substring(1)); } catch (NumberFormatException e) { return 0; }
    }
    public String key() { return planId; }
    public ActionPlan copy() {
        ActionPlan r = new ActionPlan(); r.planId = planId; r.planVersion = planVersion; r.createdAt = createdAt; r.scanId = scanId; r.state = state;
        r.mode = mode; r.targetBytes = targetBytes; r.maxTier = maxTier; r.items = new ArrayList<PlanItem>(items);
        r.achievableBytes = achievableBytes; r.achievableExactBytes = achievableExactBytes; r.withYourCallBytes = withYourCallBytes;
        r.reachesTarget = reachesTarget; r.highestTier = highestTier; r.reasonCodes = new ArrayList<String>(reasonCodes);
        r.reasonParams = new TreeMap<String, String>(reasonParams); r.hashPending = hashPending; r.validatorDropped = validatorDropped;
        r.staleReasons = new ArrayList<String>(staleReasons);
        return r;
    }
}
