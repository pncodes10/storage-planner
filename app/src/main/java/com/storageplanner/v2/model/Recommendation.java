package com.storageplanner.v2.model;

/** Placeholder of a later phase: key field(s) only, so the store schema is complete from Phase 1. */
public final class Recommendation implements Record {
    public String recommendationId, fileId;
    public String key() { return recommendationId; }
    public Recommendation copy() { Recommendation r = new Recommendation(); r.recommendationId = recommendationId; r.fileId = fileId; return r; }
}
