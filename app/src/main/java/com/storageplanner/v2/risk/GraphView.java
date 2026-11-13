package com.storageplanner.v2.risk;

import com.storageplanner.v2.model.*;
import com.storageplanner.v2.store.V2Store;

import java.util.*;

/** Read-only index of the Phase 3 graph for one ASSESS run (PHASE_3 9; written here, PHASE_3_NOTES D-1). */
public final class GraphView {
    public final Map<String, FileRecord> files = new HashMap<String, FileRecord>();
    final Map<String, List<Relationship>> edges = new HashMap<String, List<Relationship>>();
    final Map<String, List<DuplicateGroup>> groups = new HashMap<String, List<DuplicateGroup>>();
    final Map<String, List<BackupRecord>> bySubject = new HashMap<String, List<BackupRecord>>();
    final Map<String, List<BackupRecord>> byArchive = new HashMap<String, List<BackupRecord>>();
    final Map<Long, Integer> sizeCount = new HashMap<Long, Integer>();
    final Map<String, String> canonical = new HashMap<String, String>();
    /** Install state of APK files (filled by ContextBuilder from current features). */
    public final Map<String, InstallState> apkStates = new HashMap<String, InstallState>();
    public InstallState apkState(String id) { InstallState s = apkStates.get(id); return s == null ? InstallState.UNKNOWN : s; }

    /**
     * Store-backed view (PHASE_S_STORE 4 item 4): files through a 4,096-entry cache of {@code files().get}, edges through
     * {@code relationships().of} (ascending relationship key, as the old per-node lists), groups and backups indexed in one pass
     * (bounded by duplicate members and archive matches), present sizes as a sorted {@code long[]}, and canonical ids for alias
     * sets only. Sees ASSESS's own U/L/R/C writes; no rule reads those fields of another file through the view (F-10,
     * {@code StreamingTest.viewScores}).
     */
    public static GraphView of(V2Store s) {
        GraphView g = new GraphView();
        g.store = s;
        long[] sizes = new long[64], pk = new long[64];
        int ns = 0, npk = 0;
        for (FileRecord f : s.files().all()) {
            if (f.presence != Presence.PRESENT) continue;
            if (f.sizeBytes >= 0) { if (ns == sizes.length) sizes = Arrays.copyOf(sizes, ns * 2); sizes[ns++] = f.sizeBytes; }
            if (f.physicalKey != null) { if (npk == pk.length) pk = Arrays.copyOf(pk, npk * 2); pk[npk++] = hash64(f.volumeId + "\u0000" + f.physicalKey); }
        }
        com.storageplanner.v2.util.PairSort.longs(sizes, ns);
        g.sizes = Arrays.copyOf(sizes, ns);
        sizes = null;
        com.storageplanner.v2.util.PairSort.longs(pk, npk);
        boolean dup = false;
        for (int i = 1; i < npk && !dup; i++) dup = pk[i] == pk[i - 1];
        if (dup) for (FileRecord f : s.files().all()) {                 // only keys whose hash occurs twice can be alias sets
            if (f.presence != Presence.PRESENT || f.physicalKey == null) continue;
            String k = f.volumeId + "\u0000" + f.physicalKey;
            if (count(pk, npk, hash64(k)) < 2) continue;
            String c = g.canonical.get(k);
            if (c == null || f.fileId.compareTo(c) < 0) g.canonical.put(k, f.fileId);
        }
        for (DuplicateGroup d : s.duplicateGroups().all()) g.add(d);
        for (BackupRecord b : s.backups().all()) g.add(b);
        return g;
    }

    private V2Store store;                 // null: the in-memory view built with add(...) (tests)
    private long[] sizes;                  // store-backed: sorted sizes of PRESENT files with size >= 0
    private final LinkedHashMap<String, FileRecord> cache = new LinkedHashMap<String, FileRecord>(256, 0.75f, true) {
        protected boolean removeEldestEntry(Map.Entry<String, FileRecord> e) { return size() > CACHE; }
    };
    static final int CACHE = 4096;
    /** Test hook (F-10): the store-backed view hands out records whose U/L/R/C are nonsense; verdicts must not change. */
    public static boolean debugScrambleScores;

    static long hash64(String s) {
        long x = 0xcbf29ce484222325L;
        for (int i = 0; i < s.length(); i++) { x ^= s.charAt(i); x *= 0x100000001b3L; }
        return x;
    }
    /** Occurrences of v in the sorted first n entries of a (counted up to 2). */
    static int count(long[] a, int n, long v) {
        int lo = 0, hi = n;
        while (lo < hi) { int m = (lo + hi) >>> 1; if (a[m] < v) lo = m + 1; else hi = m; }
        return lo >= n || a[lo] != v ? 0 : lo + 1 < n && a[lo + 1] == v ? 2 : 1;
    }
    static int lower(long[] a, long v) { int lo = 0, hi = a.length; while (lo < hi) { int m = (lo + hi) >>> 1; if (a[m] < v) lo = m + 1; else hi = m; } return lo; }

    public GraphView add(FileRecord f) {
        files.put(f.fileId, f);
        if (f.presence == Presence.PRESENT && f.sizeBytes >= 0) { Integer c = sizeCount.get(f.sizeBytes); sizeCount.put(f.sizeBytes, c == null ? 1 : c + 1); }
        if (f.presence == Presence.PRESENT && f.physicalKey != null) {
            String k = f.volumeId + "\u0000" + f.physicalKey, c = canonical.get(k);
            if (c == null || f.fileId.compareTo(c) < 0) canonical.put(k, f.fileId);
        }
        return this;
    }
    public GraphView add(Relationship r) { put(edges, r.src, r); put(edges, r.dst, r); return this; }
    public GraphView add(DuplicateGroup d) { for (String m : d.members) put(groups, m, d); return this; }
    public GraphView add(BackupRecord b) {
        put(bySubject, b.subjectId, b);
        if (b.copyRef != null && b.copyRef.indexOf('!') > 0) put(byArchive, b.copyRef.substring(0, b.copyRef.indexOf('!')), b);
        return this;
    }
    static <T> void put(Map<String, List<T>> m, String k, T v) { List<T> l = m.get(k); if (l == null) m.put(k, l = new ArrayList<T>()); l.add(v); }
    static <T> List<T> get(Map<String, List<T>> m, String k) { List<T> l = m.get(k); return l == null ? Collections.<T>emptyList() : l; }

    public FileRecord file(String id) {
        if (store == null) return files.get(id);
        FileRecord f = cache.get(id);
        if (f == null && !cache.containsKey(id)) {
            f = store.files().get(id);
            if (f != null && debugScrambleScores) { f.utility = -7.25; f.lossSeverity = 9.5; f.recoverability = Recoverability.REGENERABLE; f.confidence = -3; }
            cache.put(id, f);
        }
        return f;
    }
    public List<DuplicateGroup> groupsOf(String id) { return get(groups, id); }
    public List<BackupRecord> backupsOf(String id) { return get(bySubject, id); }
    public List<BackupRecord> backupsInArchive(String id) { return get(byArchive, id); }
    /** Current edges only (PHASE_3 edgeCurrent): every file end still has the fingerprint the edge was made with. */
    public List<Relationship> edgesOf(String id) {
        List<Relationship> out = new ArrayList<Relationship>();
        List<Relationship> all;
        if (store == null) all = get(edges, id);
        else {
            all = new ArrayList<Relationship>();
            for (Relationship r : store.relationships().of(id)) { all.add(r); if (r.src.equals(r.dst)) all.add(r); }   // as add(r): once per end
        }
        for (Relationship r : all) if (current(r)) out.add(r);
        return out;
    }
    public boolean current(Relationship r) { return endCurrent(r.src, r.srcFp) && endCurrent(r.dst, r.dstFp); }
    boolean endCurrent(String ref, Fingerprint fp) {
        FileRecord f = file(ref);
        if (f == null) return ref.indexOf('!') > 0;          // archive-entry node
        return f.presence == Presence.PRESENT && (fp == null || fp.sameAs(f.fingerprint()));
    }
    public Relationship edge(String a, String b, RelType t) {
        for (Relationship r : edgesOf(a)) if (r.type == t && ((r.src.equals(a) && r.dst.equals(b)) || (r.src.equals(b) && r.dst.equals(a)))) return r;
        return null;
    }
    public boolean sizeUnique(FileRecord f) {
        if (store != null) { int i = lower(sizes, f.sizeBytes); return f.sizeBytes >= 0 && i < sizes.length && sizes[i] == f.sizeBytes && (i + 1 == sizes.length || sizes[i + 1] != f.sizeBytes); }
        Integer c = sizeCount.get(f.sizeBytes); return f.sizeBytes >= 0 && c != null && c == 1;
    }
    /** Non-canonical hard link / alias (PHASE_3 2.3). */
    public boolean alias(FileRecord f) {
        if (f.physicalKey == null) return false;
        String c = canonical.get(f.volumeId + "\u0000" + f.physicalKey);
        return c != null && !c.equals(f.fileId);
    }
    /** A file another file may rely on as the kept copy (PRESENT, readable, not damaged, not trashed). */
    public boolean eligible(String id) {
        FileRecord f = file(id);
        return f != null && f.presence == Presence.PRESENT && f.readable != Tri.FALSE && f.integrity != Integrity.CORRUPT
                && f.integrity != Integrity.TRUNCATED && !UsageClassifier.trashed(f) && !alias(f);
    }
}
