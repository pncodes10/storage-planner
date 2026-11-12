package com.storageplanner.v2test;

import com.storageplanner.v2.device.Clock;

/** nowMillis() is settable; monotonicNanos() advances 1 ms per call; advance(ms) moves both. */
public final class FixedClock implements Clock {
    public static final long DEFAULT_NOW = 1792000000000L;
    private long now = DEFAULT_NOW;
    private long mono = 1000000000000L;
    public FixedClock() {}
    public FixedClock(long now) { this.now = now; }
    public synchronized long nowMillis() { return now; }
    public synchronized long monotonicNanos() { long m = mono; mono += 1000000L; return m; }
    public synchronized void set(long ms) { now = ms; }
    public synchronized void advance(long ms) { now += ms; mono += ms * 1000000L; }
}
