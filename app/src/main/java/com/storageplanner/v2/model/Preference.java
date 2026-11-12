package com.storageplanner.v2.model;

/** Placeholder of a later phase: key field(s) only, so the store schema is complete from Phase 1. */
public final class Preference implements Record {
    public String name, value; public long updatedAt = Unknown.TIME;
    public String key() { return name; }
    public Preference copy() { Preference r = new Preference(); r.name = name; r.value = value; r.updatedAt = updatedAt; return r; }
}
