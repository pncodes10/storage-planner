package com.storageplanner.v2.device;

public final class DirEntry {
    public String name; public EntryKind kind = EntryKind.UNKNOWN;
    public long size = -1, mtime = Long.MIN_VALUE, inode = -1;
    public String uri, physicalKey;
    public DirEntry copy() {
        DirEntry e = new DirEntry();
        e.name = name; e.kind = kind; e.size = size; e.mtime = mtime; e.inode = inode; e.uri = uri; e.physicalKey = physicalKey;
        return e;
    }
}
