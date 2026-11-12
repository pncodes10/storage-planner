package com.storageplanner.v2.model;

public final class AudioInfo extends Sub {
    public String container, codec;
    public long durationMs = -1, bitrate = -1;
    public double durationConfidence = Double.NaN;
    public int sampleRate = -1, channels = -1;
    public Tri vbr = Tri.UNKNOWN;
}
