package com.storageplanner.v2test;

import com.storageplanner.v2.device.*;

/** V2Device wiring for tests and V2Cli. */
public final class JvmDevice implements V2Device {
    private final StorageAccess storage; private final Clock clock; private final ResourceMonitor resources; private final BlobStore blobs;
    private final MediaDecoder media; private final InstalledApps apps;
    public JvmDevice(StorageAccess storage, Clock clock, ResourceMonitor resources, BlobStore blobs) {
        this(storage, clock, resources, blobs, null, null);
    }
    public JvmDevice(StorageAccess storage, Clock clock, ResourceMonitor resources, BlobStore blobs, MediaDecoder media, InstalledApps apps) {
        this.storage = storage; this.clock = clock; this.resources = resources; this.blobs = blobs;
        this.media = media != null ? media : new JvmMediaDecoder(storage);
        this.apps = apps != null ? apps : new JvmInstalledApps(null);
    }
    public StorageAccess storage() { return storage; }
    public Clock clock() { return clock; }
    public ResourceMonitor resources() { return resources; }
    public BlobStore blobs() { return blobs; }
    public MediaDecoder media() { return media; }
    public InstalledApps apps() { return apps; }
}
