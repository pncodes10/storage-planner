package com.storageplanner.v2.device;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Persistence of the store, app-private (ARCHITECTURE 3.5). v2 never renames or deletes. */
public interface BlobStore {
    /** null if absent. */
    InputStream read(String name) throws IOException;
    OutputStream append(String name) throws IOException;
    void sync(String name) throws IOException;
    /** Old content stays until commit(). */
    AtomicWrite replace(String name) throws IOException;
    void truncate(String name) throws IOException;
    long length(String name);
    /** Positional reads of an appended blob (PHASE_S_STORE 3.14); null if absent. Each handle must be closed. */
    RandomRead openRead(String name) throws IOException;
    interface RandomRead extends java.io.Closeable {
        /** Reads up to len bytes at pos; -1 at end. */
        int read(long pos, byte[] b, int off, int len) throws IOException;
        long length() throws IOException;
    }
    interface AtomicWrite { OutputStream stream(); void commit() throws IOException; void abort(); }
}
