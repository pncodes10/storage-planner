package com.storageplanner.v2.device;

import com.storageplanner.v2.model.AccessGrant;
import java.util.List;

/** File system and volume access (ARCHITECTURE 3.1). Never throws for I/O except open(). */
public interface StorageAccess {
    /** Volumes visible now. Never null. */
    List<VolumeInfo> volumes();
    /** Access grants in force now, re-read on every call. */
    List<AccessGrant> grants();
    DirListing list(String volumeId, String dirPath);
    /** Stat without following symlinks. */
    FileStat stat(String volumeId, String path);
    ReadableFile open(String volumeId, String path) throws java.io.IOException;
    /** "ino:<dev>:<ino>" or "path:<canonical>"; null if unavailable. Follows symlinks (identity of the target). */
    String dirIdentity(String volumeId, String dirPath);
    /** Absolute local path if path-addressable, else null. */
    String localPath(String volumeId, String path);
    /** Symlink target relative to the volume's canonical root ("" = root); null if outside or unresolvable. */
    String resolveLink(String volumeId, String path);
}
