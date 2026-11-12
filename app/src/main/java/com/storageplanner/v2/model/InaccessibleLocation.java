package com.storageplanner.v2.model;

/** Derived by CoverageBuilder; not a table. */
public final class InaccessibleLocation {
    public String volumeId, path; public InaccessReason reason; public AccessMode mode;
    public long knownFiles = -1, knownBytes = -1;
    public String detail;
}
