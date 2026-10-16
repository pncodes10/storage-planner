package com.storageplanner.core;

/** One installed app, from PackageManager + StorageStatsManager + UsageStatsManager. */
public final class AppItem {
    public final String pkg;
    public final String label;
    public long codeBytes;        // APK + native libs (StorageStats.getAppBytes)
    public long dataBytes;        // data excluding cache
    public long cacheBytes;       // StorageStats.getCacheBytes
    public long lastUsed = -1;    // epoch ms, -1 = no usage record
    public long firstInstall;     // epoch ms
    public long versionCode;
    public boolean isGame;
    public boolean isSystem;      // preinstalled and not removable
    public boolean hasLauncher;   // appears in the app drawer
    public boolean isEssential;   // current keyboard, launcher, this app ...

    public AppItem(String pkg, String label) { this.pkg = pkg; this.label = label; }

    /** Days since last use; a recently installed app is never "idle". */
    public double idleDays(long now) {
        long ref = Math.max(lastUsed, firstInstall);
        if (ref <= 0) return 0;
        return (now - ref) / 86400000.0;
    }
}
