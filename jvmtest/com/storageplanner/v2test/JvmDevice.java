package com.storageplanner.v2test;

import com.storageplanner.v2.device.*;

/** V2Device wiring for tests and V2Cli. */
public final class JvmDevice implements V2Device {
    private final StorageAccess storage; private final Clock clock; private final ResourceMonitor resources; private final BlobStore blobs;
    public JvmDevice(StorageAccess storage, Clock clock, ResourceMonitor resources, BlobStore blobs) {
        this.storage = storage; this.clock = clock; this.resources = resources; this.blobs = blobs;
    }
    public StorageAccess storage() { return storage; }
    public Clock clock() { return clock; }
    public ResourceMonitor resources() { return resources; }
    public BlobStore blobs() { return blobs; }
}
