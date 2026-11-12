package com.storageplanner.v2test;

import com.storageplanner.v2.model.*;
import com.storageplanner.v2.pipeline.PipelineResult;
import com.storageplanner.v2.store.Codecs;

import java.util.*;

public final class IncrementalScanTest {
    static Set<String> paths(Fx fx, long scan, ChangeState c) {
        Set<String> s = new TreeSet<String>();
        for (FileRecord r : fx.store.files().all()) if (r.changeScanId == scan && r.change == c) s.add(r.path);
        return s;
    }
    static Set<String> set(String... p) { return new TreeSet<String>(Arrays.asList(p)); }
    static Map<String, String> byId(Fx fx) {
        Map<String, String> m = new TreeMap<String, String>();
        for (FileRecord r : fx.store.files().all()) m.put(r.fileId, Codecs.json(Codecs.FILE, r));
        return m;
    }

    public static int run(String[] args) throws Exception {
        T.section("Incremental");
        try (Fx fx = Fx.create()) {
            TempTree t = fx.tree;
            t.file("keep/k1.txt", 10, 1); t.file("keep/k2.txt", 10, 2);
            t.file("app.log", 10, 3); t.file("rep.jpg", 10, 4); t.file("del.txt", 10, 5); t.file("old name.txt", 10, 6);
            t.file("gone/g1", 1, 7); t.file("gone/g2", 1, 8); t.file("gone/g3", 1, 9); t.file("touch.txt", 10, 10);
            t.file("same.bin", 10, 11);
            fx.scan();
            // unchanged rescan: identical ids, no ADDED/CHANGED/REMOVED, no file record written
            Map<String, String> files1 = byId(fx);
            fx.scan(ScanKind.INCREMENTAL, false);
            T.eq("unchanged: same file records", files1, byId(fx));
            T.eq("unchanged: nothing changed", 0, fx.changed(2, ChangeState.ADDED) + fx.changed(2, ChangeState.CHANGED)
                    + fx.changed(2, ChangeState.REMOVED) + fx.changed(2, ChangeState.REPLACED));

            long mt = t.mtime("app.log");
            t.file("new.txt", 10, 20);
            t.append("app.log", new byte[]{1, 2, 3}); t.mtime("app.log", mt + 2000);
            String repOldId = fx.f("rep.jpg").fileId;
            t.replaceAtomically("rep.jpg", new byte[]{9, 9, 9});
            t.delete("del.txt");
            t.rename("old name.txt", "new name.txt");
            t.delete("gone");
            t.file("added/n1", 2, 21); t.file("added/n2", 2, 22);
            t.mtime("touch.txt", t.mtime("touch.txt") + 5000);
            long sm = t.mtime("same.bin");
            byte[] other = new byte[10]; Arrays.fill(other, (byte) 7);
            java.nio.file.Files.write(t.path("same.bin"), other);   // same size, same inode
            t.mtime("same.bin", sm);

            Map<String, String> beforeScan3 = byId(fx);
            long puts = fx.store.changedPutsSinceOpen();
            PipelineResult r = fx.scan(ScanKind.INCREMENTAL, false);
            long scan = r.session.scanId;
            T.eq("ADDED", set("new.txt", "new name.txt", "added/n1", "added/n2", "rep.jpg"), paths(fx, scan, ChangeState.ADDED));
            T.eq("CHANGED", set("app.log", "touch.txt"), paths(fx, scan, ChangeState.CHANGED));
            T.eq("REPLACED", set("rep.jpg"), paths(fx, scan, ChangeState.REPLACED));
            T.eq("REPLACED is the old id", repOldId, fx.store.files().get(repOldId).fileId);
            T.eq("old id presence REMOVED", Presence.REMOVED, fx.store.files().get(repOldId).presence);
            T.eq("REMOVED", set("del.txt", "old name.txt", "gone/g1", "gone/g2", "gone/g3"), paths(fx, scan, ChangeState.REMOVED));
            T.eq("same-size content change with mtime restored: not detected (documented limit)", 1L, fx.f("same.bin").changeScanId);
            T.eq("appended keeps id", true, beforeScan3.containsKey(fx.f("app.log").fileId));
            // records not changed in this scan were not rewritten
            Map<String, String> after = byId(fx);
            int rewrittenUnchanged = 0, changedRecords = 0;
            for (Map.Entry<String, String> e : after.entrySet()) {
                FileRecord rec = fx.store.files().get(e.getKey());
                if (rec.changeScanId == scan) { changedRecords++; continue; }
                if (!e.getValue().equals(beforeScan3.get(e.getKey()))) rewrittenUnchanged++;
            }
            T.eq("unchanged records not rewritten", 0, rewrittenUnchanged);
            int dirs = fx.store.directories().size();
            long delta = fx.store.changedPutsSinceOpen() - puts;
            T.check("puts ≤ changed records + dir records + session puts (" + delta + ")", delta <= changedRecords + dirs + 3L * dirs + 10);
            T.eq("counters derived", (long) paths(fx, scan, ChangeState.ADDED).size(), r.session.counter("added"));
        }
        // path-scheme volume: replacement keeps the id and is CHANGED
        try (Fx fx = Fx.create(Tri.FALSE)) {
            fx.tree.file("rep.jpg", 10, 4);
            fx.scan();
            String id = fx.f("rep.jpg").fileId;
            fx.tree.replaceAtomically("rep.jpg", new byte[]{1, 2, 3});
            fx.scan(ScanKind.INCREMENTAL, false);
            T.eq("path scheme: same id", id, fx.f("rep.jpg").fileId);
            T.eq("path scheme: CHANGED", ChangeState.CHANGED, fx.f("rep.jpg").change);
            T.eq("path scheme: one record", 1, fx.files().size());
        }
        return T.finish("IncrementalScanTest");
    }
}
