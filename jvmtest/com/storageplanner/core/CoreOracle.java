package com.storageplanner.core;

/** Test-only bridge to package-private classic helpers (no change to core/). */
public final class CoreOracle {
    private CoreOracle() {}
    public static String quickHash(String path, long size) { return Scanner.quickHash(path, size); }
    public static String fullHash(String path) { return Scanner.fullHash(path); }
    public static String magic(String path, long size) { return Classifier.magic(path, size); }
}
