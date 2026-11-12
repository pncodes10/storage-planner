package com.storageplanner.v2.device;

import com.storageplanner.v2.model.Tri;

/** ARCHITECTURE 3.3, with the app-size and usage fields of REVIEW_1 F-M11. */
public final class InstalledApp {
    public String packageName, versionName, label, installerPackage;
    public long versionCode = -1, firstInstallTime = Long.MIN_VALUE, lastUpdateTime = Long.MIN_VALUE;
    /** lower-hex SHA-256 of signing certificates; null unknown. */
    public java.util.List<String> signingCertSha256;
    public Tri system = Tri.UNKNOWN, hasLauncher = Tri.UNKNOWN;
    public long codeBytes = -1, dataBytes = -1 /* excl. cache */, cacheBytes = -1, lastUsed = Long.MIN_VALUE;
    public Tri game = Tri.UNKNOWN, essential = Tri.UNKNOWN;
}
