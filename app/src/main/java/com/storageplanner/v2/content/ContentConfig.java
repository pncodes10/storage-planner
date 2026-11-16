package com.storageplanner.v2.content;

import com.storageplanner.v2.device.DeviceProfile;

/** PHASE_6 5.2 budgets of the content stage (BALANCED defaults). */
public final class ContentConfig {
    public int visualPerRun = 2000, ocrPerRun = 300;
    public long docMaxBytes = 16L << 20;
    public long ocrTimeoutMs = 20000;
    public int ocrLongEdge = 1600; public long ocrMaxPixels = 4000000L;
    public int visualLongEdge = 1024; public long visualMaxPixels = 100000000L;
    /** moov boxes above this are not read (ActivityStatus.TOO_LARGE), PHASE_6 8.1. */
    public long mp4MaxMoovBytes = Mp4Tables.DEFAULT_MAX_MOOV;
    /** Commit every this many files or {@link #commitEveryMs} (PHASE_6 5.3); a field so a test can use a small value. */
    public int commitEvery = 200;
    public long commitEveryMs = 3000;

    public static ContentConfig forProfile(DeviceProfile p) {
        ContentConfig c = new ContentConfig();
        if (p == DeviceProfile.LOW) { c.visualPerRun = 500; c.ocrPerRun = 100; }
        else if (p == DeviceProfile.HIGH) { c.visualPerRun = 5000; c.ocrPerRun = 600; }
        return c;
    }
}
