package com.storageplanner.v2.device;

/** Installed applications (ARCHITECTURE 3.3). */
public interface InstalledApps {
    Availability availability();
    /** Empty if UNAVAILABLE. */
    java.util.List<InstalledApp> list();
    /** null = not visible (NOT "not installed" unless availability == FULL). */
    InstalledApp get(String packageName);
    /** Platform parse of an APK file (Android PackageManager.getPackageArchiveInfo); null if unsupported. */
    InstalledApp inspectArchive(String localPath);
}
