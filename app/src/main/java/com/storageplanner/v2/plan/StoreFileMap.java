package com.storageplanner.v2.plan;

import com.storageplanner.v2.model.FileRecord;
import com.storageplanner.v2.store.V2Store;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Read-only map view of the store's {@code files} table (PHASE_S_STORE 4 item 5, REVIEW_S F-10): replaces the PLAN files map
 * that used to copy every FileRecord. {@code get}/{@code containsKey} read {@code files().get} (each call a fresh copy; PLAN
 * only reads), {@code size} is {@code files().size()}, {@code keySet()/entrySet()} iterate {@code files().all()} in ascending
 * fileId. Every other method, and every mutator, throws ({@code AbstractMap}'s defaults would decode the whole table).
 */
public final class StoreFileMap extends AbstractMap<String, FileRecord> {
    private final V2Store store;

    public StoreFileMap(V2Store store) { this.store = store; }

    @Override public FileRecord get(Object key) { return key instanceof String ? store.files().get((String) key) : null; }
    @Override public boolean containsKey(Object key) { return get(key) != null; }
    @Override public int size() { return store.files().size(); }
    @Override public boolean isEmpty() { return size() == 0; }

    @Override public Set<Map.Entry<String, FileRecord>> entrySet() {
        return new AbstractSet<Map.Entry<String, FileRecord>>() {
            public int size() { return StoreFileMap.this.size(); }
            public Iterator<Map.Entry<String, FileRecord>> iterator() {
                final Iterator<FileRecord> it = store.files().all().iterator();
                return new Iterator<Map.Entry<String, FileRecord>>() {
                    public boolean hasNext() { return it.hasNext(); }
                    public Map.Entry<String, FileRecord> next() { FileRecord f = it.next(); return new SimpleImmutableEntry<String, FileRecord>(f.fileId, f); }
                    public void remove() { throw new UnsupportedOperationException(); }
                };
            }
            @Override public boolean contains(Object o) { throw unsupported(); }
            @Override public boolean remove(Object o) { throw unsupported(); }
            @Override public void clear() { throw unsupported(); }
        };
    }

    @Override public Set<String> keySet() {
        return new AbstractSet<String>() {
            public int size() { return StoreFileMap.this.size(); }
            public Iterator<String> iterator() {
                final Iterator<String> it = store.files().keys().iterator();
                return new Iterator<String>() {
                    public boolean hasNext() { return it.hasNext(); }
                    public String next() { return it.next(); }
                    public void remove() { throw new UnsupportedOperationException(); }
                };
            }
            @Override public boolean contains(Object o) { return containsKey(o); }
            @Override public boolean remove(Object o) { throw unsupported(); }
            @Override public void clear() { throw unsupported(); }
        };
    }

    @Override public Collection<FileRecord> values() { throw unsupported(); }
    @Override public boolean containsValue(Object value) { throw unsupported(); }
    @Override public FileRecord put(String key, FileRecord value) { throw unsupported(); }
    @Override public FileRecord remove(Object key) { throw unsupported(); }
    @Override public void putAll(Map<? extends String, ? extends FileRecord> m) { throw unsupported(); }
    @Override public void clear() { throw unsupported(); }
    @Override public boolean equals(Object o) { return o == this; }
    @Override public int hashCode() { return System.identityHashCode(this); }
    @Override public String toString() { return "StoreFileMap(" + size() + " files)"; }

    private static UnsupportedOperationException unsupported() { return new UnsupportedOperationException("StoreFileMap is a read-only view"); }
}
