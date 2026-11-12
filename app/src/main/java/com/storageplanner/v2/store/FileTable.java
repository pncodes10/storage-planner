package com.storageplanner.v2.store;

import com.storageplanner.v2.model.FileRecord;
import java.util.List;

public interface FileTable extends Table<FileRecord> {
    /** The record at this path with presence != REMOVED; else the one with the greatest changeScanId (ties: smallest fileId); null if none. */
    FileRecord byPath(String volumeId, String path);
    List<FileRecord> bySize(long size);
    List<FileRecord> bySha256(String sha256);
    Iterable<FileRecord> byVolume(String volumeId);
    Iterable<FileRecord> underPrefix(String volumeId, String dirPath);
    List<FileRecord> childrenOf(String volumeId, String dirPath);
}
