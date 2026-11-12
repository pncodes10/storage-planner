package com.storageplanner.v2.model;

import java.util.*;

/** ARCHITECTURE 2.11. Phase 4 fills the provisional fields; the store codec persists the key fields only until Phase 5. */
public final class Recommendation implements Record {
    public String recommendationId, fileId;
    public Action proposedAction; public Tier tier;
    public long expectedSavingBytes = Unknown.LONG;
    public double expectedLoss = Double.NaN, confidence = Double.NaN, uncertainty = Double.NaN;
    public List<String> reasonCodes = new ArrayList<String>();
    public Map<String, String> reasonParams = new TreeMap<String, String>();
    public List<String> evidenceRefs = new ArrayList<String>();
    public List<String> dependencyIds = new ArrayList<String>();
    public List<String> hardBlocks = new ArrayList<String>();
    public int planVersion;
    public Fingerprint fp;
    public long createdScanId = Unknown.LONG;
    public String key() { return recommendationId; }
    public Recommendation copy() {
        Recommendation r = new Recommendation(); r.recommendationId = recommendationId; r.fileId = fileId;
        r.proposedAction = proposedAction; r.tier = tier; r.expectedSavingBytes = expectedSavingBytes; r.expectedLoss = expectedLoss;
        r.confidence = confidence; r.uncertainty = uncertainty; r.reasonCodes = new ArrayList<String>(reasonCodes);
        r.reasonParams = new TreeMap<String, String>(reasonParams); r.evidenceRefs = new ArrayList<String>(evidenceRefs);
        r.dependencyIds = new ArrayList<String>(dependencyIds); r.hardBlocks = new ArrayList<String>(hardBlocks);
        r.planVersion = planVersion; r.fp = fp; r.createdScanId = createdScanId; return r;
    }
}
