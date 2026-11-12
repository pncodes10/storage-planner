package com.storageplanner.v2.store;

import com.storageplanner.v2.model.Record;

public interface Table<R extends Record> {
    /** A copy; null if absent. */
    R get(String key);
    /** Stores a copy (read-your-writes before commit). */
    void put(R record);
    boolean remove(String key);
    int size();
    /** Copies, ascending key order, iterating a key snapshot. */
    Iterable<R> all();
}
