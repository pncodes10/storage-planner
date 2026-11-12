package com.storageplanner.v2.store;

/** Size figures of a store (PHASE_S_STORE 3.8). MemoryStore gives estimates; disk figures are 0 for it. */
public final class StoreStats {
    /** Records over all tables. */
    public final long records;
    /** Estimated heap bytes of the in-memory indexes (DiskStore) or of the records and indexes (MemoryStore). */
    public final long indexBytesEstimate;
    public final long liveBytes, garbageBytes, diskBytes;
    /** Keys of derived tables whose record was found damaged (3.11); 0 for MemoryStore. */
    public final long damaged;

    public StoreStats(long records, long indexBytesEstimate, long liveBytes, long garbageBytes, long diskBytes, long damaged) {
        this.records = records; this.indexBytesEstimate = indexBytesEstimate; this.liveBytes = liveBytes;
        this.garbageBytes = garbageBytes; this.diskBytes = diskBytes; this.damaged = damaged;
    }

    @Override public String toString() {
        return "records=" + records + " indexBytes=" + indexBytesEstimate + " live=" + liveBytes + " garbage=" + garbageBytes
                + " disk=" + diskBytes + " damaged=" + damaged;
    }
}
