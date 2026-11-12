package com.storageplanner.v2.model;

/** Placeholder of a later phase: key field(s) only, so the store schema is complete from Phase 1. */
public final class DuplicateGroup implements Record {
    public String groupId;
    public String key() { return groupId; }
    public DuplicateGroup copy() { DuplicateGroup r = new DuplicateGroup(); r.groupId = groupId; return r; }
}
