package com.storageplanner.v2.model;

/** Unknown-value sentinels (ARCHITECTURE 2.1): unknown is never zero. */
public final class Unknown {
    public static final long TIME = Long.MIN_VALUE;
    public static final long LONG = -1L;
    public static final int INT = -1;
    public static boolean knownTime(long t) { return t != TIME; }
    public static boolean knownCount(long v) { return v >= 0; }
    public static boolean knownCount(int v) { return v >= 0; }
    private Unknown() {}
}
