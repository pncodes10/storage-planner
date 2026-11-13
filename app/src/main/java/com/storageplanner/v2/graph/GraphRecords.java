package com.storageplanner.v2.graph;

import com.storageplanner.v2.model.*;
import com.storageplanner.v2.store.StoreCorruption;
import com.storageplanner.v2.store.V2Store;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bounded cache of the records behind GRAPH's lite nodes (PHASE_S_STORE 4 item 3; 2,048 nodes). A node's records are loaded
 * together and must agree with what the snapshot pass saw: a FeatureRecord or HashRecord that was there and now reads as
 * absent (damage met between two reads of one run) or a missing FileRecord is a {@link StoreCorruption}, never a silent change
 * of the graph.
 */
final class GraphRecords {
    static final int SIZE = 2048;

    static final class Entry {
        final FileRecord f; final FeatureRecord fr; final HashRecord hr; final Fingerprint fp; final NameStem.Parsed name;
        Entry(FileRecord f, FeatureRecord fr, HashRecord hr) {
            this.f = f; this.fr = fr; this.hr = hr; this.fp = f.fingerprint(); this.name = NameStem.parse(f.name);
        }
    }

    private final V2Store store;
    private final LinkedHashMap<String, Entry> cache = new LinkedHashMap<String, Entry>(256, 0.75f, true) {
        protected boolean removeEldestEntry(Map.Entry<String, GraphRecords.Entry> e) { return size() > SIZE; }
    };
    long loads;

    GraphRecords(V2Store store) { this.store = store; }

    /** Seeds the cache with records just read by the snapshot pass. */
    void offer(Node n, FileRecord f, FeatureRecord fr, HashRecord hr) { cache.put(n.id, new Entry(f, fr, hr)); }

    Entry get(Node n) {
        Entry e = cache.get(n.id);
        if (e != null) return e;
        loads++;
        FileRecord f = store.files().get(n.id);
        FeatureRecord fr = n.is(Node.HAS_FR) ? store.features().get(n.id) : null;
        HashRecord hr = n.is(Node.HAS_HR) ? store.hashes().get(n.id) : null;
        if (f == null || (fr == null) == n.is(Node.HAS_FR) || (hr == null) == n.is(Node.HAS_HR))
            throw new StoreCorruption("GRAPH: records of " + n.id + " changed during the stage");
        e = new Entry(f, fr, hr);
        cache.put(n.id, e);
        return e;
    }
}
