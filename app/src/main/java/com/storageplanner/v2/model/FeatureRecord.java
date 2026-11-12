package com.storageplanner.v2.model;

/** Placeholder of a later phase: key field(s) only, so the store schema is complete from Phase 1. */
public final class FeatureRecord implements Record {
    public String fileId;
    public String key() { return fileId; }
    public FeatureRecord copy() { FeatureRecord r = new FeatureRecord(); r.fileId = fileId; return r; }
}
