package com.storageplanner.v2.model;

/** Placeholder of a later phase: key field(s) only, so the store schema is complete from Phase 1. */
public final class Relationship implements Record {
    public String edgeId, src, dst;
    public String key() { return edgeId; }
    public Relationship copy() { Relationship r = new Relationship(); r.edgeId = edgeId; r.src = src; r.dst = dst; return r; }
}
