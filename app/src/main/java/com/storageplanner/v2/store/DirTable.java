package com.storageplanner.v2.store;

import com.storageplanner.v2.model.DirRecord;

public interface DirTable extends Table<DirRecord> {
    DirRecord get(String volumeId, String path);
    /** Directories whose parent is dirPath, ascending key; a key snapshot (PHASE_S_STORE 3.8). */
    Iterable<DirRecord> childrenOf(String volumeId, String dirPath);
    /** All descendants (not dirPath itself), ascending path. */
    Iterable<DirRecord> underPrefix(String volumeId, String dirPath);
}
