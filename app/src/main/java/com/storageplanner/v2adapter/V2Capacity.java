package com.storageplanner.v2adapter;

import java.util.Locale;

/**
 * Phase A memory guard (REVIEW_A M-1). The v2 store lives in the heap, so before extraction starts V2Runner compares the number of
 * file records with what the heap can hold and stops cleanly with {@link #tooManyMessage} instead of running into an
 * OutOfMemoryError. Android-free so the arithmetic is tested on the JVM (V2AdapterTest).
 *
 * <p>Calibration (docs/v2/REVIEW_A.md, dataset of 2,414 files, JVM, after a full GC): store after T1-T3 + GRAPH about 3.1 KB per
 * file; the highest point is inside ASSESS, where every verdict and the store are held together, about 6.4 KB per file
 * (V2Runner frees the verdicts right after, so hash-on-demand and PLAN run at about 3.8 KB per file); while the adapted
 * result is shown about 0.5 KB per file. The GRAPH stage also holds a bounded picture-signature cache (about 50 MB at its cap)
 * plus decode buffers, which is the fixed reserve. {@link #BYTES_PER_FILE} adds 25 % to the measured peak for ART and for
 * libraries larger than the dataset (longer paths, more groups).</p>
 */
public final class V2Capacity {
    private V2Capacity() {}

    /** Peak heap per file record (measured 6.4 KB, plus 25 %). */
    public static final long BYTES_PER_FILE = 8L * 1024;
    /** Fixed heap the run needs besides the records: GRAPH signature cache (~50 MB at its cap) and image decode buffers. */
    public static final long FIXED_RESERVE = 64L * 1024 * 1024;
    /** Share of the app's heap limit v2 may plan to use; the rest is head room for the UI and the garbage collector. */
    public static final double HEAP_FRACTION = 0.80;

    /**
     * Largest number of file records v2 can analyse with this heap.
     * @param maxHeap  Runtime.maxMemory()
     * @param usedHeap heap in use before the v2 records are counted (other screens, a result still shown)
     */
    public static long maxFiles(long maxHeap, long usedHeap) {
        if (maxHeap <= 0) return 0;
        double budget = maxHeap * HEAP_FRACTION - Math.max(0, usedHeap) - FIXED_RESERVE;
        if (budget <= 0) return 0;
        return (long) (budget / BYTES_PER_FILE);
    }

    public static boolean fits(long files, long maxHeap, long usedHeap) { return files <= maxFiles(maxHeap, usedHeap); }

    /** Shown when the guard stops v2 before extraction. */
    public static String tooManyMessage(long files, long limit) {
        return String.format(Locale.ROOT, "v2 cannot handle this many files yet: it found %,d files, and this phone's memory allows about %,d for v2. "
                + "It stopped before reading them. Nothing was changed. Choose Classic (default) on the start screen and tap Analyse.", files, Math.max(0, limit));
    }

    // ---- disk (PHASE_S_STORE 3.12, REVIEW_S F-11): only when the run uses the disk store

    /** Worst case per file: live + garbage up to max(64 MiB, live) + a compaction copy of live (3 × 2.5 KiB), plus index files. */
    public static final long DISK_BYTES_PER_FILE = 8L * 1024;
    public static final long DISK_FIXED = 64L * 1024 * 1024;
    /** The store never writes the device below this (DiskStore.Options.reserveBytes). */
    public static final long LOW_SPACE_RESERVE = 256L * 1024 * 1024;

    /** Free storage the run needs for this many file records (preflight right after DISCOVER). */
    public static long diskNeeded(long files) { return Math.max(0, files) * DISK_BYTES_PER_FILE + DISK_FIXED + LOW_SPACE_RESERVE; }

    /** Whether the run may go on; an unknown free size (−1) does not stop it (the in-run reserve check still applies). */
    public static boolean diskFits(long files, long freeBytes) { return freeBytes < 0 || freeBytes >= diskNeeded(files); }

    /** Shown when the preflight stops v2 before extraction. */
    public static String notEnoughStorageMessage(long files) {
        return String.format(Locale.ROOT, "Not enough free storage for v2's working data (about %,d MB needed). It stopped before reading the files. "
                + "Nothing was changed.", (diskNeeded(files) + (1 << 20) - 1) >> 20);
    }

    /** Shown when the free storage fell below the reserve during the run (D_LOW_STORAGE). */
    public static final String LOW_STORAGE = "v2 stopped because the phone's free storage fell below 256 MB while it was working. Nothing was changed. "
            + "Free some space and run again.";

    /** Shown when the heap ran out anyway (last resort). */
    public static final String OUT_OF_MEMORY = "v2 ran out of memory: it cannot handle this many files yet. Nothing was changed. "
            + "Choose Classic (default) on the start screen and tap Analyse.";
}
