package com.storageplanner.v2.features;

import com.storageplanner.v2.model.FileRecord;

/** tiersDone / tiersFailed / tiersPending bits (bit k = 1 << k). */
public final class Tiers {
    private Tiers() {}
    public static final int T1 = 1 << 1, T2 = 1 << 2, T3 = 1 << 3, ALL = T1 | T2 | T3;
    public static void done(FileRecord f, int bit) { f.tiersDone |= bit; f.tiersFailed &= ~bit; f.tiersPending &= ~bit; }
    public static void failed(FileRecord f, int bit) { f.tiersFailed |= bit; f.tiersDone &= ~bit; f.tiersPending &= ~bit; }
    public static void pending(FileRecord f, int bit) { f.tiersPending |= bit; f.tiersDone &= ~bit; f.tiersFailed &= ~bit; }
    public static void na(FileRecord f, int bit) { f.tiersPending &= ~bit; f.tiersDone &= ~bit; f.tiersFailed &= ~bit; }
    public static boolean t2Applicable(FileRecord f) { return f.sizeBytes > 0; }
    public static boolean t3Applicable(FileRecord f) { return FileType.isImage(f.type); }
}
