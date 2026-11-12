package com.storageplanner.v2test;

import com.storageplanner.v2.model.*;
import com.storageplanner.v2.store.MemoryStore;
import com.storageplanner.v2.store.StoreException;
import com.storageplanner.v2.store.V2Store;

import java.util.ArrayList;
import java.util.List;

/** Store contract; runs against any V2Store (Phase 10 reuses it for SQLite). */
public final class StoreContractTest {
    static FileRecord file(String id, String path, long size, Presence p, long changeScan) {
        FileRecord r = new FileRecord();
        r.fileId = id; r.volumeId = "v"; r.path = path; r.name = path.substring(path.lastIndexOf('/') + 1); r.sizeBytes = size;
        r.presence = p; r.changeScanId = changeScan;
        return r;
    }
    static List<String> paths(Iterable<FileRecord> it) { List<String> l = new ArrayList<String>(); for (FileRecord r : it) l.add(r.path); return l; }

    public static int run(String[] args) throws StoreException {
        return run(MemoryStore.volatileStore());
    }

    public static int run(V2Store s) throws StoreException {
        T.section("StoreContract");
        FileRecord a = file("id-a", "a/x.jpg", 10, Presence.PRESENT, 1);
        s.files().put(a);
        a.sizeBytes = 99;
        T.eq("put stores a copy", 10L, s.files().get("id-a").sizeBytes);
        FileRecord g = s.files().get("id-a"); g.sizeBytes = 77;
        T.eq("get returns a copy", 10L, s.files().get("id-a").sizeBytes);
        // byPath preference
        s.files().put(file("id-old", "p/q.txt", 5, Presence.REMOVED, 3));
        s.files().put(file("id-new", "p/q.txt", 6, Presence.PRESENT, 2));
        T.eq("byPath prefers present", "id-new", s.files().byPath("v", "p/q.txt").fileId);
        FileRecord n = s.files().get("id-new"); n.presence = Presence.REMOVED; n.changeScanId = 4; s.files().put(n);
        T.eq("byPath else greatest changeScanId", "id-new", s.files().byPath("v", "p/q.txt").fileId);
        // bySize / bySha256
        T.eq("bySize", 1, s.files().bySize(10).size());
        FileRecord c = s.files().get("id-a"); c.sizeBytes = 11; c.sha256 = "ab"; s.files().put(c);
        T.eq("bySize old empty", 0, s.files().bySize(10).size());
        T.eq("bySize new", 1, s.files().bySize(11).size());
        T.eq("bySha256", 1, s.files().bySha256("ab").size());
        s.files().remove("id-a");
        T.eq("bySha256 after remove", 0, s.files().bySha256("ab").size());
        T.eq("bySize after remove", 0, s.files().bySize(11).size());
        // prefix boundaries
        s.files().put(file("1", "a/f1", 1, Presence.PRESENT, 1));
        s.files().put(file("2", "a/b/f2", 1, Presence.PRESENT, 1));
        s.files().put(file("3", "ab/f3", 1, Presence.PRESENT, 1));
        s.files().put(file("4", "a", 1, Presence.PRESENT, 1));
        s.files().put(file("5", "a/0", 1, Presence.PRESENT, 1));
        T.eq("underPrefix a", "[a/0, a/b/f2, a/f1]", paths(s.files().underPrefix("v", "a")).toString());
        T.eq("childrenOf a", "[a/0, a/f1]", paths(s.files().childrenOf("v", "a")).toString());
        T.eq("childrenOf root", "[a]", paths(s.files().childrenOf("v", "")).toString());
        T.eq("underPrefix root", 7, paths(s.files().underPrefix("v", "")).size());
        T.eq("underPrefix other volume", 0, paths(s.files().underPrefix("w", "")).size());
        // all() key order
        List<String> keys = new ArrayList<String>(); for (FileRecord r : s.files().all()) keys.add(r.fileId);
        List<String> sorted = new ArrayList<String>(keys); java.util.Collections.sort(sorted);
        T.eq("all() key order", sorted, keys);
        // directories
        DirRecord d1 = new DirRecord(); d1.volumeId = "v"; d1.path = "a"; s.directories().put(d1);
        DirRecord d2 = new DirRecord(); d2.volumeId = "v"; d2.path = "a/b"; s.directories().put(d2);
        DirRecord d3 = new DirRecord(); d3.volumeId = "v"; d3.path = "ab"; s.directories().put(d3);
        DirRecord d0 = new DirRecord(); d0.volumeId = "v"; d0.path = ""; s.directories().put(d0);
        T.eq("dir childrenOf root", 2, s.directories().childrenOf("v", "").size());
        int under = 0; for (DirRecord d : s.directories().underPrefix("v", "a")) { under++; T.eq("dir under a", "a/b", d.path); }
        T.eq("dir underPrefix a count", 1, under);
        T.check("dir get", s.directories().get("v", "ab") != null);
        // journal
        OperationEntry op = new OperationEntry(); op.opId = "op1"; op.seq = 0; op.phase = OpPhase.INTENT;
        s.operations().put(op);
        boolean threw = false;
        try { s.operations().put(op); } catch (IllegalStateException e) { threw = true; }
        T.check("journal duplicate key throws", threw);
        T.eq("journal open ops", 1, s.operations().openOps().size());
        // rollback
        s.commit();
        FileRecord r1 = s.files().get("1"); r1.sizeBytes = 500; s.files().put(r1);
        s.files().remove("2");
        s.files().put(file("9", "new/f9", 1, Presence.PRESENT, 1));
        s.rollback();
        T.eq("rollback restores value", 1L, s.files().get("1").sizeBytes);
        T.check("rollback restores removed", s.files().get("2") != null);
        T.check("rollback drops new", s.files().get("9") == null);
        T.eq("rollback restores index", 3, paths(s.files().underPrefix("v", "a")).size());
        return T.finish("StoreContractTest");
    }
}
