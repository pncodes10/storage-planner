package com.storageplanner.v2.device;

/** Aggregate of device services. Phase 2 adds media() and apps(); Phase 6 text(); Phase 9 fileOps(). */
public interface V2Device {
    StorageAccess storage();
    Clock clock();
    ResourceMonitor resources();
    BlobStore blobs();
}
