package com.storageplanner.v2.model;

/** Placeholder of a later phase: key field(s) only, so the store schema is complete from Phase 1. */
public final class BackupRecord implements Record {
    public String backupId;
    public String key() { return backupId; }
    public BackupRecord copy() { BackupRecord r = new BackupRecord(); r.backupId = backupId; return r; }
}
