package com.storageplanner.v2.model;

/** Placeholder of a later phase: key field(s) only, so the store schema is complete from Phase 1. */
public final class HashRecord implements Record {
    public String fileId;
    public String key() { return fileId; }
    public HashRecord copy() { HashRecord r = new HashRecord(); r.fileId = fileId; return r; }
}
