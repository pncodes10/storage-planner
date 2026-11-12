package com.storageplanner.v2.model;

/** Date parsed from a file name (PHASE_2 4). Local time stored as UTC. */
public final class NameDate extends Sub {
    public long at = Unknown.TIME;
    public String precision, pattern, source;
}
