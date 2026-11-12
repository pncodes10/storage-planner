package com.storageplanner.v2.model;

/** Table directories (ARCHITECTURE 2.4). Key = volumeId + NUL + path. */
public final class DirRecord implements Record, Cloneable {
    public String volumeId, path;
    public String rawPath;
    public DirStatus status;
    public long lastVisitedScanId = Unknown.LONG;
    public long lastListedOkScanId = Unknown.LONG;
    public long mtime = Unknown.TIME, inode = Unknown.LONG;
    public String identity;
    public int entries = Unknown.INT, entriesSkipped = Unknown.INT;
    public String error;

    public static String keyOf(String volumeId, String path) { return volumeId + "\u0000" + path; }
    public String key() { return keyOf(volumeId, path); }
    public DirRecord copy() {
        try { return (DirRecord) super.clone(); } catch (CloneNotSupportedException e) { throw new IllegalStateException(e); }
    }
}
