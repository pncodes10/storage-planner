package com.storageplanner.v2test;

import com.storageplanner.v2.device.Clock;

/** Wall clock (jvmtest only; v2 itself never reads the system time). nowMillis can be pinned for deterministic output. */
public final class SystemClock implements Clock {
    private final long fixedNow;
    public SystemClock() { this(Long.MIN_VALUE); }
    public SystemClock(long fixedNow) { this.fixedNow = fixedNow; }
    public long nowMillis() { return fixedNow != Long.MIN_VALUE ? fixedNow : System.currentTimeMillis(); }
    public long monotonicNanos() { return System.nanoTime(); }
}
