package com.storageplanner.v2.store;

import com.storageplanner.v2.V2;
import com.storageplanner.v2.device.BlobStore;
import com.storageplanner.v2.device.Clock;
import com.storageplanner.v2.model.*;
import com.storageplanner.v2.model.Record;   // single-type import: java.lang.Record exists in android-34.jar

import java.io.IOException;
import java.io.InputStream;
import java.util.*;

/**
 * In-memory store persisted as one atomic snapshot blob (ARCHITECTURE 4.2, PHASE_1 8). Single writer; every
 * public method synchronizes on the store.
 */
public final class MemoryStore implements V2Store {
    public static final String SNAPSHOT = "store.snap";

    final Object lock = this;
    private final BlobStore blobs;          // null = volatile
    private final long snapshotEveryMs;     // -1 adaptive, 0 every commit
    private final Clock clock;
    private long commitSeq;
    private boolean dirty;
    private long lastSnapNanos;
    private long lastSnapDurationMs;
    private long changedPuts;
    private int snapshotsWritten;
    private final List<Op> pending = new ArrayList<Op>();

    private static final class Op { final MemTable<?> t; final String key; final Record prev; Op(MemTable<?> t, String k, Record p) { this.t = t; key = k; prev = p; } }

    // ---------------------------------------------------------------- tables
    private final FileMem files = new FileMem();
    private final MemTable<FeatureRecord> features = new MemTable<FeatureRecord>(Codecs.FEATURE);
    private final MemTable<HashRecord> hashes = new MemTable<HashRecord>(Codecs.HASH);
    private final MemTable<EmbeddingRecord> embeddings = new MemTable<EmbeddingRecord>(Codecs.EMBEDDING);
    private final RelMem relationships = new RelMem();
    private final MemTable<DuplicateGroup> duplicateGroups = new MemTable<DuplicateGroup>(Codecs.DUPLICATE_GROUP);
    private final MemTable<BackupRecord> backups = new MemTable<BackupRecord>(Codecs.BACKUP);
    private final MemTable<PairCheck> pairChecks = new MemTable<PairCheck>(Codecs.PAIR_CHECK);
    private final RecMem recommendations = new RecMem();
    private final MemTable<ActionPlan> actionPlans = new MemTable<ActionPlan>(Codecs.ACTION_PLAN);
    private final JournalMem operations = new JournalMem();
    private final MemTable<Feedback> feedback = new MemTable<Feedback>(Codecs.FEEDBACK);
    private final MemTable<Preference> preferences = new MemTable<Preference>(Codecs.PREFERENCE);
    private final MemTable<ModelRecord> models = new MemTable<ModelRecord>(Codecs.MODEL);
    private final MemTable<ScanSession> scanSessions = new MemTable<ScanSession>(Codecs.SESSION);
    private final MemTable<Volume> volumes = new MemTable<Volume>(Codecs.VOLUME);
    private final DirMem directories = new DirMem();

    /** All tables in ARCHITECTURE 4.1 order (the snapshot order). */
    final List<MemTable<?>> tables = Arrays.<MemTable<?>>asList(files, features, hashes, embeddings, relationships, duplicateGroups,
            backups, pairChecks, recommendations, actionPlans, operations, feedback, preferences, models, scanSessions, volumes, directories);

    private MemoryStore(BlobStore blobs, long snapshotEveryMs, Clock clock) {
        this.blobs = blobs; this.snapshotEveryMs = snapshotEveryMs; this.clock = clock;
        this.lastSnapNanos = clock == null ? 0 : clock.monotonicNanos();
    }

    /** Loads store.snap if present; StoreException if it is invalid (the caller then starts with create()). */
    public static MemoryStore open(BlobStore blobs, long snapshotEveryMs, Clock clock) throws StoreException {
        MemoryStore s = new MemoryStore(blobs, snapshotEveryMs, clock);
        InputStream in;
        try { in = blobs.read(SNAPSHOT); } catch (IOException e) { throw new StoreException("cannot read snapshot: " + e, e); }
        if (in != null) {
            try { s.commitSeq = Snapshot.read(in, s); }
            finally { try { in.close(); } catch (IOException ignored) { /* nothing to do */ } }
            s.pending.clear();
            s.changedPuts = 0;
            s.dirty = false;
        }
        return s;
    }

    /** An empty persistent store over blobs (after a reset); the next snapshot replaces whatever is there. */
    public static MemoryStore create(BlobStore blobs, long snapshotEveryMs, Clock clock) {
        MemoryStore s = new MemoryStore(blobs, snapshotEveryMs, clock);
        s.dirty = true;
        return s;
    }

    /** No persistence (unit tests); commit() only clears pending ops. */
    public static MemoryStore volatileStore() { return new MemoryStore(null, 0, null); }

    public synchronized long changedPutsSinceOpen() { return changedPuts; }
    public synchronized int snapshotsWritten() { return snapshotsWritten; }
    public synchronized long commitSeq() { return commitSeq; }

    public int schemaVersion() { return V2.STORE_SCHEMA; }
    public FileTable files() { return files; }
    public Table<FeatureRecord> features() { return features; }
    public Table<HashRecord> hashes() { return hashes; }
    public Table<EmbeddingRecord> embeddings() { return embeddings; }
    public RelationshipTable relationships() { return relationships; }
    public Table<DuplicateGroup> duplicateGroups() { return duplicateGroups; }
    public Table<BackupRecord> backups() { return backups; }
    public Table<PairCheck> pairChecks() { return pairChecks; }
    public RecommendationTable recommendations() { return recommendations; }
    public Table<ActionPlan> actionPlans() { return actionPlans; }
    public JournalTable operations() { return operations; }
    public Table<Feedback> feedback() { return feedback; }
    public Table<Preference> preferences() { return preferences; }
    public Table<ModelRecord> models() { return models; }
    public Table<ScanSession> scanSessions() { return scanSessions; }
    public Table<Volume> volumes() { return volumes; }
    public DirTable directories() { return directories; }

    public synchronized void commit() throws StoreException {
        pending.clear();
        commitSeq++;
        if (blobs == null || !dirty) return;
        long interval = snapshotEveryMs >= 0 ? snapshotEveryMs : Math.max(60000L, 20 * lastSnapDurationMs);
        long elapsedMs = (clock.monotonicNanos() - lastSnapNanos) / 1000000L;
        if (elapsedMs >= interval) snapshot();
    }

    public synchronized void flush() throws StoreException {
        commit();
        if (blobs != null && dirty) snapshot();
    }

    public synchronized void rollback() {
        for (int i = pending.size() - 1; i >= 0; i--) pending.get(i).t.restore(pending.get(i).key, pending.get(i).prev);
        pending.clear();
    }

    public void close() throws IOException {
        try { flush(); } catch (StoreException e) { throw new IOException(e.getMessage(), e); }
    }

    private void snapshot() throws StoreException {
        long t0 = clock.monotonicNanos();
        Snapshot.write(this, blobs, commitSeq, clock.nowMillis());
        long t1 = clock.monotonicNanos();
        lastSnapDurationMs = (t1 - t0) / 1000000L;
        lastSnapNanos = t1;
        dirty = false;
        snapshotsWritten++;
    }

    // ================================================================ table implementations

    class MemTable<R extends Record> implements Table<R> {
        final Codecs.Codec<R> codec;
        final TreeMap<String, R> map = new TreeMap<String, R>();
        MemTable(Codecs.Codec<R> codec) { this.codec = codec; }

        void index(R r) {}
        void unindex(R r) {}

        @SuppressWarnings("unchecked")
        R cp(R r) { return r == null ? null : (R) r.copy(); }

        public R get(String key) { synchronized (lock) { return cp(map.get(key)); } }

        public void put(R record) {
            synchronized (lock) {
                String k = record.key();
                R old = map.get(k);
                R c = cp(record);
                if (old != null && Codecs.json(codec, old).equals(Codecs.json(codec, c))) return;   // no change
                pending.add(new Op(this, k, old));
                if (old != null) unindex(old);
                map.put(k, c);
                index(c);
                dirty = true;
                changedPuts++;
            }
        }

        public boolean remove(String key) {
            synchronized (lock) {
                R old = map.remove(key);
                if (old == null) return false;
                unindex(old);
                pending.add(new Op(this, key, old));
                dirty = true;
                return true;
            }
        }

        @SuppressWarnings("unchecked")
        void restore(String key, Record prev) {
            R cur = map.remove(key);
            if (cur != null) unindex(cur);
            if (prev != null) { map.put(key, (R) prev); index((R) prev); }
        }

        /** Used by the snapshot loader: no pending op, no dirty flag. */
        @SuppressWarnings("unchecked")
        void load(Record r) { R x = (R) r; R old = map.put(x.key(), x); if (old != null) unindex(old); index(x); }

        public int size() { synchronized (lock) { return map.size(); } }

        public Iterable<R> all() {
            synchronized (lock) { return copies(new ArrayList<String>(map.keySet())); }
        }

        /** Copies of the records under the given keys (skipping keys removed meanwhile), lazily. */
        Iterable<R> copies(final List<String> keys) {
            return new Iterable<R>() {
                public Iterator<R> iterator() {
                    return new Iterator<R>() {
                        int i = 0; R next = advance();
                        R advance() { while (i < keys.size()) { R r = get(keys.get(i++)); if (r != null) return r; } return null; }
                        public boolean hasNext() { return next != null; }
                        public R next() { if (next == null) throw new NoSuchElementException(); R r = next; next = advance(); return r; }
                        public void remove() { throw new UnsupportedOperationException(); }
                    };
                }
            };
        }

        List<R> list(Collection<String> keys) {
            List<R> r = new ArrayList<R>(keys.size());
            for (String k : keys) { R x = map.get(k); if (x != null) r.add(cp(x)); }
            return r;
        }

        /** Raw access for the snapshot writer (caller holds the lock). */
        Collection<R> raw() { return map.values(); }
    }

    static <K> void add(Map<K, TreeSet<String>> m, K k, String v) { TreeSet<String> s = m.get(k); if (s == null) m.put(k, s = new TreeSet<String>()); s.add(v); }
    static <K> void del(Map<K, TreeSet<String>> m, K k, String v) { TreeSet<String> s = m.get(k); if (s != null) { s.remove(v); if (s.isEmpty()) m.remove(k); } }
    static String parentOf(String path) { int i = path.lastIndexOf('/'); return i < 0 ? "" : path.substring(0, i); }

    final class FileMem extends MemTable<FileRecord> implements FileTable {
        final HashMap<String, TreeSet<String>> byPathKey = new HashMap<String, TreeSet<String>>();
        final HashMap<Long, TreeSet<String>> bySize = new HashMap<Long, TreeSet<String>>();
        final HashMap<String, TreeSet<String>> bySha = new HashMap<String, TreeSet<String>>();
        final TreeMap<String, String> ordered = new TreeMap<String, String>();
        final HashMap<String, TreeSet<String>> children = new HashMap<String, TreeSet<String>>();   // vol\0parent -> ordered keys
        FileMem() { super(Codecs.FILE); }

        String ok(FileRecord r) { return r.volumeId + "\u0000" + r.path + "\u0000" + r.fileId; }

        @Override void index(FileRecord r) {
            add(byPathKey, r.volumeId + "\u0000" + r.path, r.fileId);
            add(bySize, r.sizeBytes, r.fileId);
            if (r.sha256 != null) add(bySha, r.sha256, r.fileId);
            ordered.put(ok(r), r.fileId);
            if (!r.path.isEmpty()) add(children, r.volumeId + "\u0000" + parentOf(r.path), ok(r));
        }
        @Override void unindex(FileRecord r) {
            del(byPathKey, r.volumeId + "\u0000" + r.path, r.fileId);
            del(bySize, r.sizeBytes, r.fileId);
            if (r.sha256 != null) del(bySha, r.sha256, r.fileId);
            ordered.remove(ok(r));
            if (!r.path.isEmpty()) del(children, r.volumeId + "\u0000" + parentOf(r.path), ok(r));
        }

        public FileRecord byPath(String volumeId, String path) {
            synchronized (lock) {
                TreeSet<String> ids = byPathKey.get(volumeId + "\u0000" + path);
                if (ids == null) return null;
                FileRecord best = null;
                for (String id : ids) {
                    FileRecord r = map.get(id);
                    if (r.presence != Presence.REMOVED) return cp(r);
                    if (best == null || r.changeScanId > best.changeScanId) best = r;
                }
                return cp(best);
            }
        }
        public List<FileRecord> bySize(long size) { synchronized (lock) { TreeSet<String> s = bySize.get(size); return s == null ? new ArrayList<FileRecord>() : list(s); } }
        public List<FileRecord> bySha256(String sha) { synchronized (lock) { TreeSet<String> s = bySha.get(sha); return s == null ? new ArrayList<FileRecord>() : list(s); } }
        public Iterable<FileRecord> byVolume(String volumeId) { return underPrefix(volumeId, ""); }
        public Iterable<FileRecord> underPrefix(String volumeId, String dirPath) {
            synchronized (lock) {
                SortedMap<String, String> sub = dirPath.isEmpty()
                        ? ordered.subMap(volumeId + "\u0000", volumeId + "\u0001")
                        : ordered.subMap(volumeId + "\u0000" + dirPath + "/", volumeId + "\u0000" + dirPath + "0");
                return copies(new ArrayList<String>(sub.values()));
            }
        }
        public List<FileRecord> childrenOf(String volumeId, String dirPath) {
            synchronized (lock) {
                TreeSet<String> s = children.get(volumeId + "\u0000" + dirPath);
                if (s == null) return new ArrayList<FileRecord>();
                List<String> ids = new ArrayList<String>(s.size());
                for (String k : s) ids.add(ordered.get(k));
                return list(ids);
            }
        }
    }

    final class DirMem extends MemTable<DirRecord> implements DirTable {
        final HashMap<String, TreeSet<String>> children = new HashMap<String, TreeSet<String>>();
        DirMem() { super(Codecs.DIR); }
        @Override void index(DirRecord r) { if (!r.path.isEmpty()) add(children, r.volumeId + "\u0000" + parentOf(r.path), r.key()); }
        @Override void unindex(DirRecord r) { if (!r.path.isEmpty()) del(children, r.volumeId + "\u0000" + parentOf(r.path), r.key()); }
        public DirRecord get(String volumeId, String path) { return get(DirRecord.keyOf(volumeId, path)); }
        public List<DirRecord> childrenOf(String volumeId, String dirPath) {
            synchronized (lock) { TreeSet<String> s = children.get(volumeId + "\u0000" + dirPath); return s == null ? new ArrayList<DirRecord>() : list(s); }
        }
        public Iterable<DirRecord> underPrefix(String volumeId, String dirPath) {
            synchronized (lock) {
                SortedMap<String, DirRecord> sub = dirPath.isEmpty()
                        ? map.subMap(volumeId + "\u0000", volumeId + "\u0001")
                        : map.subMap(volumeId + "\u0000" + dirPath + "/", volumeId + "\u0000" + dirPath + "0");
                List<String> keys = new ArrayList<String>(sub.keySet());
                if (dirPath.isEmpty()) keys.remove(DirRecord.keyOf(volumeId, ""));
                return copies(keys);
            }
        }
    }

    final class RelMem extends MemTable<Relationship> implements RelationshipTable {
        final HashMap<String, TreeSet<String>> byNode = new HashMap<String, TreeSet<String>>();
        RelMem() { super(Codecs.RELATIONSHIP); }
        @Override void index(Relationship r) { if (r.src != null) add(byNode, r.src, r.key()); if (r.dst != null) add(byNode, r.dst, r.key()); }
        @Override void unindex(Relationship r) { if (r.src != null) del(byNode, r.src, r.key()); if (r.dst != null) del(byNode, r.dst, r.key()); }
        public List<Relationship> of(String nodeRef) { synchronized (lock) { TreeSet<String> s = byNode.get(nodeRef); return s == null ? new ArrayList<Relationship>() : list(s); } }
    }

    final class RecMem extends MemTable<Recommendation> implements RecommendationTable {
        final HashMap<String, TreeSet<String>> byFile = new HashMap<String, TreeSet<String>>();
        RecMem() { super(Codecs.RECOMMENDATION); }
        @Override void index(Recommendation r) { if (r.fileId != null) add(byFile, r.fileId, r.key()); }
        @Override void unindex(Recommendation r) { if (r.fileId != null) del(byFile, r.fileId, r.key()); }
        public List<Recommendation> forFile(String fileId) { synchronized (lock) { TreeSet<String> s = byFile.get(fileId); return s == null ? new ArrayList<Recommendation>() : list(s); } }
    }

    final class JournalMem extends MemTable<OperationEntry> implements JournalTable {
        final HashMap<String, TreeSet<String>> byOp = new HashMap<String, TreeSet<String>>();
        JournalMem() { super(Codecs.OPERATION); }
        @Override void index(OperationEntry r) { add(byOp, r.opId, r.key()); }
        @Override void unindex(OperationEntry r) { del(byOp, r.opId, r.key()); }
        @Override public void put(OperationEntry r) {
            synchronized (lock) {
                if (map.containsKey(r.key())) throw new IllegalStateException("journal entry exists: " + r.key());
                super.put(r);
            }
        }
        public List<OperationEntry> forOp(String opId) {
            synchronized (lock) {
                TreeSet<String> s = byOp.get(opId);
                List<OperationEntry> l = s == null ? new ArrayList<OperationEntry>() : list(s);
                Collections.sort(l, new Comparator<OperationEntry>() { public int compare(OperationEntry a, OperationEntry b) { return a.seq < b.seq ? -1 : a.seq == b.seq ? 0 : 1; } });
                return l;
            }
        }
        public List<String> openOps() {
            synchronized (lock) {
                List<String> r = new ArrayList<String>();
                for (String op : new TreeSet<String>(byOp.keySet())) {
                    List<OperationEntry> l = forOp(op);
                    OpPhase p = l.get(l.size() - 1).phase;
                    if (p != OpPhase.COMMITTED && p != OpPhase.ROLLED_BACK && p != OpPhase.FAILED && p != OpPhase.PRECHECK_FAILED && p != OpPhase.RESTORED) r.add(op);
                }
                return r;
            }
        }
    }
}
