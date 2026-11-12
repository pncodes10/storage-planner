package com.storageplanner.v2.model;

/** Image facts (PHASE_2 2). Tier 1: container fields; tier 3: pixel fields (NaN / -1 / null = unknown). */
public final class ImageInfo extends Sub {
    public String format;
    public int width = -1, height = -1, orientation = 1, uprightWidth = -1, uprightHeight = -1;
    public double aspect = Double.NaN, megapixels = Double.NaN;
    public Tri animated = Tri.UNKNOWN, hasAlpha = Tri.UNKNOWN, progressive = Tri.UNKNOWN, motionPhoto = Tri.UNKNOWN;
    public int frames = -1, bitDepth = -1, jpegQuality = -1;
    public long motionVideoBytes = -1;
    // tier 3
    public DecodeStatus decodeStatus;
    public int analysedWidth = -1, analysedHeight = -1;
    public double sharpness = Double.NaN, brightness = Double.NaN, contrast = Double.NaN, clipLow = Double.NaN, clipHigh = Double.NaN,
            exposure = Double.NaN, colorfulness = Double.NaN, saturation = Double.NaN;
    public int[] lumaHist16;

    /** Fills upright dims, aspect and megapixels from width/height/orientation. */
    public void derive() {
        if (width > 0 && height > 0) {
            boolean swap = orientation >= 5 && orientation <= 8;
            uprightWidth = swap ? height : width; uprightHeight = swap ? width : height;
            aspect = (double) uprightWidth / uprightHeight;
            megapixels = width * (double) height / 1e6;
        }
    }
}
