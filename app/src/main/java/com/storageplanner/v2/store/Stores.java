package com.storageplanner.v2.store;

import com.storageplanner.v2.device.BlobStore;
import com.storageplanner.v2.device.Clock;

/** Store selection (PHASE_S_STORE 5.1). MEMORY stays the default of every caller until slice S6. */
public final class Stores {
    private Stores() {}
    public enum Kind { MEMORY, DISK }

    /** Opens the persistent store of this kind; StoreException → the caller starts with {@link #create}. */
    public static V2Store open(Kind kind, BlobStore blobs, long checkpointEveryMs, Clock clock) throws StoreException {
        return kind == Kind.DISK ? DiskStore.open(blobs, checkpointEveryMs, clock) : MemoryStore.open(blobs, checkpointEveryMs, clock);
    }

    /**
     * The StoreCorruption behind a failure, or null (3.11): Pipeline wraps a stage's RuntimeException in a StoreException,
     * ASSESS/PLAN throw it directly. A caller that gets one retries once with {@link #create} (event D_STORE_RESET).
     */
    public static StoreCorruption corruption(Throwable t) {
        for (int i = 0; t != null && i < 16; i++, t = t.getCause()) if (t instanceof StoreCorruption) return (StoreCorruption) t;
        return null;
    }

    /** An empty persistent store of this kind (after a reset). */
    public static V2Store create(Kind kind, BlobStore blobs, long checkpointEveryMs, Clock clock) throws StoreException {
        return kind == Kind.DISK ? DiskStore.create(blobs, checkpointEveryMs, clock) : MemoryStore.create(blobs, checkpointEveryMs, clock);
    }
}
