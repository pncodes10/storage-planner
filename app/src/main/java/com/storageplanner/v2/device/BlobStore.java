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
    interface AtomicWrite { OutputStream stream(); void commit() throws IOException; void abort(); }
}
