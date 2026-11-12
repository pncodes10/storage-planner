package com.storageplanner.v2.model;

/** Placeholder of a later phase: key field(s) only, so the store schema is complete from Phase 1. */
public final class EmbeddingRecord implements Record {
    public String fileId, modelId;
    public String key() { return fileId + ":" + modelId; }
    public EmbeddingRecord copy() { EmbeddingRecord r = new EmbeddingRecord(); r.fileId = fileId; r.modelId = modelId; return r; }
}
