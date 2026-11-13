package com.storageplanner.v2adapter;

import java.util.Locale;

/**
 * v2's memory guard (REVIEW_A M-1, recalculated in PHASE_S_STORE slice S6 for the disk store). Before extraction starts
 * V2Runner compares the number of file records with what the heap can hold and stops cleanly with {@link #tooManyMessage}
 * instead of running into an OutOfMemoryError. Android-free so the arithmetic is tested on the JVM (V2AdapterTest).
 *
 * <p>With the disk store (S6) the records live on disk; the heap holds the store's indexes and the stages' working sets
 * (PHASE_S_STORE S5). Two phases bound the run, measured with the scale harness on synthetic trees of 10,000 and 100,000
 * files (DiskStore, JVM, after full GCs; linear fit, docs/v2/PHASE_S_NOTES.md "S6"):</p>
 * <ul>
 * <li>GRAPH: store index + lite nodes, candidates and edges ≈ 514 B per file, plus the fixed signature cache and decode
 *     buffers ({@link #GRAPH_RESERVE});</li>
 * <li>the end of the run: store index + recommendations + plan + the adapted classic result ≈ 1,415 B per file after GC,
 *     but bounded by the F-08 cap test, see {@link #END_BYTES_PER_FILE}.</li>
 * </ul>
 * <p>Both are multiplied by {@link #SAFETY} (REVIEW_S F-08: a safety factor for ART's GC headroom, large-object fragmentation
 * and unmeasured transients, not an object-size correction). {@link #STORE_FIXED} covers the store's caches and buffers.
 * With the memory store ({@link #maxFilesMemoryStore}) the old single per-file figure applies.</p>
 */
public final class V2Capacity {
    private V2Capacity() {}

    /** REVIEW_S F-08 safety factor on every measured per-file figure. */
    public static final double SAFETY = 1.25;
    /** GRAPH phase, measured 514 B per file (disk store), times {@link #SAFETY}. */
    public static final long GRAPH_BYTES_PER_FILE = 643;
    /**
     * End of the run (store index + recommendations + plan + adapted result). Measured 1,415 B per file after a full GC; × 1.25
     * would be 1,769, but the F-08 cap test (100,000 files, full V2Analysis at -Xmx148m) ran out of memory: building the classic
     * result has transients (list and map growth) the after-GC marks do not see. Set instead from the cap test: 85,000 files
     * pass at -Xmx148m, so the 256 MB claim may not exceed that: (0.8 × 256 MiB − 20 MiB − 16 MiB) / 85,000 = 2,083 B.
     */
    public static final long END_BYTES_PER_FILE = 2083;
    /** The disk store's fixed heap: record cache (8 MiB), write buffers, delta overlays. */
    public static final long STORE_FIXED = 16L * 1024 * 1024;
    /** Fixed heap of GRAPH besides the records: signature cache (~50 MB at its cap) and image decode buffers. */
    public static final long GRAPH_RESERVE = 64L * 1024 * 1024;

    /** Memory store (selectable, not the default): peak heap per file record (measured 6.4 KB, plus 25 %). */
    public static final long BYTES_PER_FILE = 8L * 1024;
    /** Memory store: fixed heap the run needs besides the records. */
    public static final long FIXED_RESERVE = 64L * 1024 * 1024;
    /** Share of the app's heap limit v2 may plan to use; the rest is head room for the UI and the garbage collector. */
    public static final double HEAP_FRACTION = 0.80;

    /**
     * Largest number of file records v2 can analyse with this heap on the disk store:
     * min((0.8·max − used − STORE_FIXED − GRAPH_RESERVE) / GRAPH_BYTES, (0.8·max − used − STORE_FIXED) / END_BYTES).
     * @param maxHeap  Runtime.maxMemory()
     * @param usedHeap heap in use before the v2 records are counted (other screens, a result still shown)
     */
    public static long maxFiles(long maxHeap, long usedHeap) {
        if (maxHeap <= 0) return 0;
        double base = maxHeap * HEAP_FRACTION - Math.max(0, usedHeap) - STORE_FIXED;
        double graph = (base - GRAPH_RESERVE) / GRAPH_BYTES_PER_FILE, end = base / END_BYTES_PER_FILE;
        double m = Math.min(graph, end);
        return m <= 0 ? 0 : (long) m;
    }

    /** The same guard for the memory store (every record in the heap). */
    public static long maxFilesMemoryStore(long maxHeap, long usedHeap) {
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
