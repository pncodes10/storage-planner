package com.storageplanner.v2.model;

/** Table file_hashes (ARCHITECTURE 2.5). */
public final class HashRecord implements Record {
    public String fileId; public Fingerprint fp;
    public String quickHash, sha256, reason;
    public long crc32 = -1, hashedAt = Unknown.TIME;
    public String key() { return fileId; }
    public HashRecord copy() {
        HashRecord r = new HashRecord(); r.fileId = fileId; r.fp = fp; r.quickHash = quickHash; r.sha256 = sha256; r.reason = reason;
        r.crc32 = crc32; r.hashedAt = hashedAt; return r;
    }
}
