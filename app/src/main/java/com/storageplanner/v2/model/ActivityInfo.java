package com.storageplanner.v2.model;

/** Phase 6 video activity profile (PHASE_6 4.2; video only). */
public final class ActivityInfo extends Sub {
    public ActivityStatus videoStatus = ActivityStatus.NOT_RUN;
    public long durationMs = -1; public int videoSamples = -1;
    public double medianWindowBytes = Double.NaN, peakThreshold = Double.NaN;
    public int peakWindows = -1, irregularSync = -1;
    public long[] peaks;                                       // [start0,end0,start1,end1,...] ms, at most 32 intervals; null = unknown
}
