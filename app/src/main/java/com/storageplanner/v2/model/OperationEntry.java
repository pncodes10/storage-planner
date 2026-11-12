package com.storageplanner.v2.model;

/** Placeholder of a later phase: key field(s) only, so the store schema is complete from Phase 1. */
public final class OperationEntry implements Record {
    public String opId; public int seq; public OpPhase phase;
    public String key() { return opId + "#" + seq; }
    public OperationEntry copy() { OperationEntry r = new OperationEntry(); r.opId = opId; r.seq = seq; r.phase = phase; return r; }
}
