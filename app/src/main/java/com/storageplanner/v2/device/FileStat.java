package com.storageplanner.v2.device;

import com.storageplanner.v2.model.Tri;

public final class FileStat {
    public IoStatus status; public EntryKind kind = EntryKind.UNKNOWN;
    public long size = -1, mtime = Long.MIN_VALUE, birthTime = Long.MIN_VALUE, atime = Long.MIN_VALUE, inode = -1;
    public Tri readable = Tri.UNKNOWN, writable = Tri.UNKNOWN;
    public String linkTarget, physicalKey, error;
    public static FileStat failed(IoStatus st, String error) { FileStat s = new FileStat(); s.status = st; s.error = error; return s; }
}
