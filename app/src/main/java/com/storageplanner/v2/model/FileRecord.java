package com.storageplanner.v2.model;

/** Table files (ARCHITECTURE 2.3). Key = fileId. All fields are immutable values, so copy() is a shallow clone. */
public final class FileRecord implements Record, Cloneable {
    // spec 13.1
    public String fileId, volumeId, path, uri, name, extension = "", mimeType;
    public long sizeBytes = Unknown.LONG, createdAt = Unknown.TIME, modifiedAt = Unknown.TIME, lastAccessedAt = Unknown.TIME;
    public String sha256;
    public long phash; public boolean hasPhash;
    public String embeddingRef;
    public String category = "UNKNOWN";
    public ExtractionStatus extractionStatus = ExtractionStatus.NOT_STARTED;
    public double utility = Double.NaN, lossSeverity = Double.NaN;
    public Recoverability recoverability = Recoverability.UNKNOWN;
    public double confidence = Double.NaN;
    public Protection protection = Protection.NONE;
    public int scanVersion = Unknown.INT;
    // extras
    public long inode = Unknown.LONG;
    public String physicalKey;
    public Presence presence = Presence.PRESENT;
    public String presenceReason;
    public ChangeState change = ChangeState.ADDED;
    public long firstSeenScanId = Unknown.LONG, changeScanId = Unknown.LONG, removedScanId = Unknown.LONG;
    public String rawPath;
    public Tri readable = Tri.UNKNOWN, writable = Tri.UNKNOWN;
    public AccessMode accessMode;
    public String area, ownerPackage;
    public boolean hidden;
    public String type = "unknown";
    public TypeSource typeSource = TypeSource.NONE;
    public ExtMismatch extMismatch = ExtMismatch.UNKNOWN;
    public Integrity integrity = Integrity.UNKNOWN;
    public TimeSource createdAtSource = TimeSource.NONE;
    public double extractionConfidence = Double.NaN;
    public int tiersDone, tiersFailed, tiersPending;
    public String duplicateGroupId;
    public String lastError;

    public String key() { return fileId; }
    public Fingerprint fingerprint() { return new Fingerprint(sizeBytes, modifiedAt, inode); }
    /** The path to use for StorageAccess calls. */
    public String accessPath() { return rawPath != null ? rawPath : path; }

    public FileRecord copy() {
        try { return (FileRecord) super.clone(); } catch (CloneNotSupportedException e) { throw new IllegalStateException(e); }
    }
}
