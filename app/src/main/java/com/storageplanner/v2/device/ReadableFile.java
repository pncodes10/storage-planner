package com.storageplanner.v2.device;

public interface ReadableFile extends java.io.Closeable {
    long size() throws java.io.IOException;
    /** Reads up to len bytes at position; -1 at EOF. Never reads past size(). */
    int read(long position, byte[] buf, int off, int len) throws java.io.IOException;
}
