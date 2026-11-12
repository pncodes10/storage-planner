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

    public static GraphView of(V2Store s) {
        GraphView g = new GraphView();
        for (FileRecord f : s.files().all()) g.add(f);
        for (Relationship r : s.relationships().all()) g.add(r);
        for (DuplicateGroup d : s.duplicateGroups().all()) g.add(d);
        for (BackupRecord b : s.backups().all()) g.add(b);
        return g;
    }
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

    public FileRecord file(String id) { return files.get(id); }
    public List<DuplicateGroup> groupsOf(String id) { return get(groups, id); }
    public List<BackupRecord> backupsOf(String id) { return get(bySubject, id); }
    public List<BackupRecord> backupsInArchive(String id) { return get(byArchive, id); }
    /** Current edges only (PHASE_3 edgeCurrent): every file end still has the fingerprint the edge was made with. */
    public List<Relationship> edgesOf(String id) {
        List<Relationship> out = new ArrayList<Relationship>();
        for (Relationship r : get(edges, id)) if (current(r)) out.add(r);
        return out;
    }
    public boolean current(Relationship r) { return endCurrent(r.src, r.srcFp) && endCurrent(r.dst, r.dstFp); }
    boolean endCurrent(String ref, Fingerprint fp) {
        FileRecord f = files.get(ref);
        if (f == null) return ref.indexOf('!') > 0;          // archive-entry node
        return f.presence == Presence.PRESENT && (fp == null || fp.sameAs(f.fingerprint()));
    }
    public Relationship edge(String a, String b, RelType t) {
        for (Relationship r : edgesOf(a)) if (r.type == t && ((r.src.equals(a) && r.dst.equals(b)) || (r.src.equals(b) && r.dst.equals(a)))) return r;
        return null;
    }
    public boolean sizeUnique(FileRecord f) { Integer c = sizeCount.get(f.sizeBytes); return f.sizeBytes >= 0 && c != null && c == 1; }
    /** Non-canonical hard link / alias (PHASE_3 2.3). */
    public boolean alias(FileRecord f) {
        if (f.physicalKey == null) return false;
        String c = canonical.get(f.volumeId + "\u0000" + f.physicalKey);
        return c != null && !c.equals(f.fileId);
    }
    /** A file another file may rely on as the kept copy (PRESENT, readable, not damaged, not trashed). */
    public boolean eligible(String id) {
        FileRecord f = files.get(id);
        return f != null && f.presence == Presence.PRESENT && f.readable != Tri.FALSE && f.integrity != Integrity.CORRUPT
                && f.integrity != Integrity.TRUNCATED && !UsageClassifier.trashed(f) && !alias(f);
    }
}
