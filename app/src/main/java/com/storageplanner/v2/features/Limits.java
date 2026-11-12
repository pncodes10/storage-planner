package com.storageplanner.v2.features;

/** Read and parse caps (PHASE_2 3.2, 7, 8). */
public final class Limits {
    public int headBytes = 64 * 1024, tailBytes = 64 * 1024;
    public int imageProbeBytes = 4 << 20, jpegMarkerBytes = 1 << 20, mp4Bytes = 1 << 20, audioBytes = 2 << 20;
    public int pdfWholeMax = 8 << 20, pdfRegion = 4 << 20;
    public int archiveMaxEntries = 10000;
    public long zipMaxCdBytes = 16L << 20;
    public int zipEocdSearch = 65557;
    public double bombEntryRatio = 100;
    public long bombEntryMinSize = 1L << 20, bombTotalAbs = 8L << 30, bombTotalFactor = 1000;
    public int maxNestedDepth = 2;
    public int inflateManifest = 4 << 20, inflateSignature = 1 << 20;
    public long bundleBaseMaxDeflated = 32L << 20;
    public long maxPixels = 100000000L;
    public long undecodedMaxBytes = 64L << 20;
    public int decodeLongEdge = 1024;
    public int maxBoxes = 10000, maxMarkers = 10000, maxChunks = 100000;
    public static final Limits DEFAULT = new Limits();
}
