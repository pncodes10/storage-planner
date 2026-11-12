package com.storageplanner.v2.stage;

import com.storageplanner.v2.device.Clock;

/** Work limits of one pipeline run; -1 = unlimited. */
public final class Budget {
    public long maxMs = -1, maxBytesRead = -1, maxDecodes = -1;
    public long bytesRead, decodes;
    private long startNanos = Long.MIN_VALUE;

    public void start(Clock clock) { startNanos = clock.monotonicNanos(); }
    public boolean exhausted(Clock clock) {
        if (maxBytesRead >= 0 && bytesRead >= maxBytesRead) return true;
        if (maxDecodes >= 0 && decodes >= maxDecodes) return true;
        return maxMs >= 0 && startNanos != Long.MIN_VALUE && (clock.monotonicNanos() - startNanos) / 1000000L >= maxMs;
    }
}
