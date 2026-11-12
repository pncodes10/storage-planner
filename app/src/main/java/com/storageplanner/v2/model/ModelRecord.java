package com.storageplanner.v2.model;

/** Placeholder of a later phase: key field(s) only, so the store schema is complete from Phase 1. */
public final class ModelRecord implements Record {
    public String modelId;
    public String key() { return modelId; }
    public ModelRecord copy() { ModelRecord r = new ModelRecord(); r.modelId = modelId; return r; }
}
