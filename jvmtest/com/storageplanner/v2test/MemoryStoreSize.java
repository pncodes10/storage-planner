package com.storageplanner.v2test;

import com.storageplanner.v2.model.Record;
import com.storageplanner.v2.store.Codecs;
import com.storageplanner.v2.store.Table;
import com.storageplanner.v2.store.V2Store;

import java.nio.charset.StandardCharsets;

/** Approximate snapshot size: one JSON line per record of the tables Phase 1-2 fill (A12 measurement). */
final class MemoryStoreSize {
    private MemoryStoreSize() {}
    static <R extends Record> long bytes(Table<R> t, Codecs.Codec<R> c) {
        long n = 0;
        for (R r : t.all()) n += Codecs.json(c, r).getBytes(StandardCharsets.UTF_8).length + 1;
        return n;
    }
    static long snapshotBytes(V2Store s) {
        return bytes(s.files(), Codecs.FILE) + bytes(s.features(), Codecs.FEATURE) + bytes(s.hashes(), Codecs.HASH)
                + bytes(s.directories(), Codecs.DIR) + bytes(s.volumes(), Codecs.VOLUME) + bytes(s.scanSessions(), Codecs.SESSION);
    }
}
