package com.storageplanner.v2.model;

public final class VideoInfo extends Sub {
    public String container, videoCodec, audioCodec, problem;
    public long durationMs = -1, bitrate = -1, createdAt = Unknown.TIME, trailerBytes = -1;
    public int width = -1, height = -1, rotation = -1, videoTracks = -1, audioTracks = -1;
    public double fps = Double.NaN;
    public Tri hasAudio = Tri.UNKNOWN, fragmented = Tri.UNKNOWN, moovAtEnd = Tri.UNKNOWN, samsungTrailer = Tri.UNKNOWN;
}
