package com.storageplanner.v2.model;

/** Table backup_records (ARCHITECTURE 2.8). Key = edgeId of the BACKED_UP_BY edge. */
public final class BackupRecord implements Record {
    public String backupId, subjectId, copyRef, note;
    public BackupKind kind = BackupKind.ARCHIVE_ENTRY;
    public BackupLevel level = BackupLevel.NONE;
    public BackupState state = BackupState.UNKNOWN;
    public long verifiedAt = Unknown.TIME;
    public Fingerprint subjectFp, copyFp;
    public String key() { return backupId; }
    public BackupRecord copy() {
        BackupRecord r = new BackupRecord(); r.backupId = backupId; r.subjectId = subjectId; r.copyRef = copyRef; r.note = note;
        r.kind = kind; r.level = level; r.state = state; r.verifiedAt = verifiedAt; r.subjectFp = subjectFp; r.copyFp = copyFp;
        return r;
    }
}
