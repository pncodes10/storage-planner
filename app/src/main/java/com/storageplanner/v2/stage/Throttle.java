package com.storageplanner.v2.stage;

import com.storageplanner.v2.device.Clock;
import com.storageplanner.v2.device.ResourceMonitor;
import com.storageplanner.v2.device.ResourceState;
import com.storageplanner.v2.device.Thermal;
import com.storageplanner.v2.model.Tri;

/** Throttle policy of ARCHITECTURE 3.4. Samples the monitor at most every 2 s; unknown values have no effect. */
public final class Throttle {
    public static final long SAMPLE_EVERY_NANOS = 2000L * 1000000L;
    public static final String PAUSE_REASON = "PL_PAUSED_DEVICE";
    private final ResourceMonitor monitor;
    private final Clock clock;
    private long lastSample = Long.MIN_VALUE;
    private ResourceState last;
    /** Reason code of the last positive shouldPause(), else null. */
    public String pauseReason;

    public Throttle(ResourceMonitor monitor, Clock clock) { this.monitor = monitor; this.clock = clock; }

    private ResourceState state() {
        if (monitor == null) return null;
        long now = clock.monotonicNanos();
        if (last == null || lastSample == Long.MIN_VALUE || now - lastSample >= SAMPLE_EVERY_NANOS) { last = monitor.sample(); lastSample = now; }
        return last;
    }

    public boolean shouldPause() {
        ResourceState s = state();
        if (s == null) return false;
        boolean hot = s.thermal == Thermal.SEVERE || s.thermal == Thermal.CRITICAL;
        boolean lowBattery = s.batteryPercent >= 0 && s.batteryPercent <= 14 && s.charging != Tri.TRUE;
        if (hot || lowBattery) { pauseReason = PAUSE_REASON; return true; }
        return false;
    }

    /** thermal MODERATE or heap free below 64 MiB: run with concurrency 1. */
    public boolean reduceConcurrency() {
        ResourceState s = state();
        return s != null && (s.thermal == Thermal.MODERATE || (s.heapFreeBytes >= 0 && s.heapFreeBytes < 64L * 1024 * 1024));
    }

    /** storage free known and below 256 MiB. */
    public boolean lowStorage() {
        ResourceState s = state();
        return s != null && s.storageFreeBytes >= 0 && s.storageFreeBytes < 256L * 1024 * 1024;
    }
}
