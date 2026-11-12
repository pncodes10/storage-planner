package com.storageplanner.v2.store;

import com.storageplanner.v2.model.DirRecord;
import java.util.List;

public interface DirTable extends Table<DirRecord> {
    DirRecord get(String volumeId, String path);
    List<DirRecord> childrenOf(String volumeId, String dirPath);
    /** All descendants (not dirPath itself), ascending path. */
    Iterable<DirRecord> underPrefix(String volumeId, String dirPath);
}
