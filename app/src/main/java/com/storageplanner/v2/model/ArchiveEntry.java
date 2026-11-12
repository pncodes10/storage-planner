package com.storageplanner.v2.model;

public final class ArchiveEntry extends Sub {
    public String path, rawName, container;
    public int depth, method = -1;
    public long size = -1, compressedSize = -1, crc32 = -1, mtime = Unknown.TIME, localOffset = -1;
    public boolean dir;
    public Tri encrypted = Tri.UNKNOWN;
}
