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

    /** An empty persistent store of this kind (after a reset). */
    public static V2Store create(Kind kind, BlobStore blobs, long checkpointEveryMs, Clock clock) throws StoreException {
        return kind == Kind.DISK ? DiskStore.create(blobs, checkpointEveryMs, clock) : MemoryStore.create(blobs, checkpointEveryMs, clock);
    }
}
