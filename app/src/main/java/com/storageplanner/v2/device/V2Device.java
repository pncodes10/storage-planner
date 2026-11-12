package com.storageplanner.v2.device;

/** Aggregate of device services. Phase 6 adds text(); Phase 9 fileOps(). */
public interface V2Device {
    StorageAccess storage();
    Clock clock();
    ResourceMonitor resources();
    BlobStore blobs();
    /** Pixel decoder; never null (a device without one returns NO_DECODER for everything). */
    MediaDecoder media();
    /** Installed applications; never null (availability UNAVAILABLE when unknown). */
    InstalledApps apps();
}
