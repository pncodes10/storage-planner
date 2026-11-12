package com.storageplanner.v2.discovery;

import java.util.HashSet;
import java.util.Set;

/** Walker limits (PHASE_1 4.1). */
public final class WalkLimits {
    public int maxDepth = 64;
    public int maxEntriesPerDir = 100000;
    public long maxTotalEntries = 5000000L;
    public int maxPathChars = 4096;
    public boolean followSymlinks = false;
    public int commitEvery = 500;
    public long commitEveryMs = 3000;
    /** Normalized paths not walked. */
    public Set<String> excluded = new HashSet<String>();
}
