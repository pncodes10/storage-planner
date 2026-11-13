package com.storageplanner.v2test;

import com.storageplanner.v2.util.FileIdList;
import com.storageplanner.v2.util.PairSort;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/** PHASE_S_STORE S5: the compact working-set structures of the streaming callers. */
public final class StreamingTest {
    public static int run(String[] args) throws Exception {
        fileIdList();
        pairSort();
        viewScores();
        storeFileMap();
        releaseIndexes();
        analysisPath();
        return T.finish("StreamingTest");
    }

    static void fileIdList() {
        T.section("FileIdList");
        Random r = new Random(5);
        FileIdList l = new FileIdList();
        List<String> ref = new ArrayList<String>();
        String hex = "0123456789abcdef";
        for (int i = 0; i < 5000; i++) {
            String s;
            int k = r.nextInt(10);
            if (k < 7) { StringBuilder b = new StringBuilder(); for (int j = 0; j < 32; j++) b.append(hex.charAt(r.nextInt(16))); s = b.toString(); }
            else if (k == 7) s = "0123456789ABCDEF0123456789abcdef";                     // upper case: not packed
            else if (k == 8) s = "id-" + i + "-é😀";
            else s = i % 2 == 0 ? "" : "ffffffffffffffffffffffffffffffff0";                // empty, 33 chars
            l.add(s); ref.add(s);
        }
        l.add("00000000000000000000000000000000"); ref.add("00000000000000000000000000000000");
        l.add("ffffffffffffffffffffffffffffffff"); ref.add("ffffffffffffffffffffffffffffffff");
        T.eq("size", ref.size(), l.size());
        boolean all = true;
        for (int i = 0; i < ref.size(); i++) all &= ref.get(i).equals(l.get(i));
        T.check("every id round-trips", all);
        boolean threw = false;
        try { l.get(l.size()); } catch (IndexOutOfBoundsException e) { threw = true; }
        T.check("out of range throws", threw);
    }

    static void pairSort() {
        T.section("PairSort");
        Random r = new Random(11);
        boolean pairsOk = true, intsOk = true, longsOk = true;
        for (int trial = 0; trial < 300; trial++) {
            int n = r.nextInt(trial < 10 ? 3 : 400);
            long[] a = new long[2 * n + 4];
            List<long[]> ref = new ArrayList<long[]>();
            for (int i = 0; i < n; i++) {
                long k = r.nextInt(4) == 0 ? Long.MAX_VALUE - r.nextInt(3) : r.nextInt(20) - 3;
                a[2 * i] = k; a[2 * i + 1] = i; ref.add(new long[]{k, i});
            }
            PairSort.pairs(a, n);
            Collections.sort(ref, new Comparator<long[]>() { public int compare(long[] x, long[] y) { return x[0] != y[0] ? Long.compare(x[0], y[0]) : Long.compare(x[1], y[1]); } });
            for (int i = 0; i < n; i++) pairsOk &= a[2 * i] == ref.get(i)[0] && a[2 * i + 1] == ref.get(i)[1];
            final long[] key = new long[n];
            for (int i = 0; i < n; i++) key[i] = r.nextInt(30);
            int[] idx = new int[n + 2];
            List<Integer> ir = new ArrayList<Integer>();
            for (int i = 0; i < n; i++) { idx[i] = i; ir.add(i); }
            PairSort.ints(idx, n, new PairSort.IntOrder() { public int compare(int x, int y) { return key[x] != key[y] ? Long.compare(key[y], key[x]) : Integer.compare(x, y); } });
            Collections.sort(ir, new Comparator<Integer>() { public int compare(Integer x, Integer y) { return key[x] != key[y] ? Long.compare(key[y], key[x]) : Integer.compare(x, y); } });
            for (int i = 0; i < n; i++) intsOk &= idx[i] == ir.get(i);
            long[] l = new long[n + 1];
            for (int i = 0; i < n; i++) l[i] = r.nextLong() >> r.nextInt(64);
            l[n] = Long.MIN_VALUE;
            long[] lr = Arrays.copyOf(l, n);
            Arrays.sort(lr);
            PairSort.longs(l, n);
            longsOk &= Arrays.equals(Arrays.copyOf(l, n), lr) && l[n] == Long.MIN_VALUE;
        }
        T.check("pairs equal a sort by (key, value)", pairsOk);
        T.check("ints equal Collections.sort with the same order", intsOk);
        T.check("longs equal Arrays.sort, tail untouched", longsOk);
    }

    /**
     * F-10: the store-backed GraphView sees ASSESS's own U/L/R/C writes of other files mid-loop (the old snapshot did not).
     * Safe only while no rule reads those four fields of a file obtained through the view: with every view record's U/L/R/C
     * scrambled the CLI result must not change (DiskKillTest tree: images, near and exact duplicates).
     */
    static void viewScores() throws Exception {
        T.section("GraphView scores (F-10)");
        TempTree t = TempTree.create(), w = TempTree.create();
        try {
            DiskKillTest.tree(t);
            for (String store : new String[]{"memory", "disk"}) {
                Object plain = DiskKillTest.norm(DiskKillTest.run(t.root(), w.path("p-" + store + ".json"), null, store, false, null));
                com.storageplanner.v2.risk.GraphView.debugScrambleScores = true;
                Object scr;
                try { scr = DiskKillTest.norm(DiskKillTest.run(t.root(), w.path("s-" + store + ".json"), null, store, false, null)); }
                finally { com.storageplanner.v2.risk.GraphView.debugScrambleScores = false; }
                String d = DiskKillTest.firstDiff("", plain, scr);
                T.check(store + ": result unchanged with scrambled U/L/R/C in the view" + (d == null ? "" : " (" + d + ")"), d == null);
            }
        } finally { t.close(); w.close(); }
    }

    /** F-10: StoreFileMap answers get/containsKey/size/keySet/entrySet from the store in ascending id; everything else throws. */
    /**
     * REVIEW_S F-09: DiskStore.releaseIndexes — the projection yields exactly files().all() (also after puts, removes and
     * overwrites spread over several segments and an index file), every table operation and commit refuse afterwards, close
     * works, and the next open uses the index file written by the release and equals the store before it. A frame moved under
     * another file's locator is refused by the projection (fail closed).
     */
    static void releaseIndexes() throws Exception {
        T.section("F-09 releaseIndexes");
        com.storageplanner.v2.store.DiskStore.Options o = new com.storageplanner.v2.store.DiskStore.Options();
        o.maxSegmentBytes = 4096;
        PowerLossBlobStore b = new PowerLossBlobStore();
        com.storageplanner.v2.store.DiskStore st = com.storageplanner.v2.store.DiskStore.create(b, 0, new FixedClock(NOW), o);
        java.util.Random r = new java.util.Random(9);
        java.util.TreeMap<String, String> ref = new java.util.TreeMap<String, String>();
        for (int i = 0; i < 600; i++) {
            String id = i % 7 == 0 ? "id-é" + i : i % 11 == 0 ? "a😀" + i : String.format(java.util.Locale.ROOT, "%032x", r.nextLong() & 0xffffffffffffL);
            com.storageplanner.v2.model.FileRecord f = StoreContractTest.file(id, "d/f" + i, 10 + i,
                    i % 13 == 0 ? com.storageplanner.v2.model.Presence.REMOVED : com.storageplanner.v2.model.Presence.PRESENT, 1);
            st.files().put(f);
            if (i % 50 == 49) st.commit();
        }
        st.commit();
        int k = 0;
        for (String id : st.files().keys()) { if (k++ % 9 == 0) st.files().remove(id); }
        st.commit();
        for (com.storageplanner.v2.model.FileRecord f : st.files().all()) if (f.sizeBytes % 5 == 0) { f.sizeBytes += 1000; st.files().put(f); }
        st.commit();
        for (com.storageplanner.v2.model.FileRecord f : st.files().all())
            ref.put(f.fileId, com.storageplanner.v2.store.Codecs.json(com.storageplanner.v2.store.Codecs.FILE, f));
        Iterable<com.storageplanner.v2.model.FileRecord> proj = st.releaseIndexes();
        java.util.TreeMap<String, String> got = new java.util.TreeMap<String, String>();
        List<String> order = new ArrayList<String>();
        for (com.storageplanner.v2.model.FileRecord f : proj) { got.put(f.fileId, com.storageplanner.v2.store.Codecs.json(com.storageplanner.v2.store.Codecs.FILE, f)); order.add(f.fileId); }
        T.check("projection = files().all() (" + ref.size() + " records, ascending id)", got.equals(ref) && order.equals(new ArrayList<String>(ref.keySet())));
        int refused = 0;
        try { st.files().get(order.get(0)); } catch (IllegalStateException e) { refused++; }
        try { st.features().size(); } catch (IllegalStateException e) { refused++; }
        try { st.files().all(); } catch (IllegalStateException e) { refused++; }
        try { st.files().put(StoreContractTest.file("x", "x", 1, com.storageplanner.v2.model.Presence.PRESENT, 1)); } catch (IllegalStateException e) { refused++; }
        try { st.commit(); } catch (com.storageplanner.v2.store.StoreException e) { refused++; }
        try { st.releaseIndexes(); } catch (com.storageplanner.v2.store.StoreException e) { refused++; }
        T.eq("after release: get/size/all/put/commit/release refused", 6, refused);
        T.check("stats after release", st.stats().liveBytes > 0);
        st.close();
        com.storageplanner.v2.store.DiskStore re = com.storageplanner.v2.store.DiskStore.open(b, 0, new FixedClock(NOW), o);
        java.util.TreeMap<String, String> again = new java.util.TreeMap<String, String>();
        for (com.storageplanner.v2.model.FileRecord f : re.files().all()) again.put(f.fileId, com.storageplanner.v2.store.Codecs.json(com.storageplanner.v2.store.Codecs.FILE, f));
        T.check("reopen uses the index file written by the release and equals the store", re.loadedFromIndex() && again.equals(ref));
        // fail closed: a locator pointing at another file's frame (hash and length copied) is refused by the projection
        String a0 = order.get(1), a1 = order.get(2);
        long[] la = re.debugLocator(1, a0), lb = re.debugLocator(1, a1);
        re.debugSetLocator(1, a1, la[0], la[1], (int) la[2]);
        boolean caught = false;
        try { for (com.storageplanner.v2.model.FileRecord f : re.releaseIndexes()) { if (f == null) break; } }
        catch (com.storageplanner.v2.store.StoreCorruption e) { caught = true; }
        T.check("projection refuses a locator that reaches another file's frame", caught);
        re.close();
    }

    static void storeFileMap() throws Exception {
        T.section("StoreFileMap");
        for (int kind = 0; kind < 2; kind++) {
            com.storageplanner.v2.store.V2Store st = kind == 0 ? com.storageplanner.v2.store.MemoryStore.volatileStore()
                    : com.storageplanner.v2.store.DiskStore.create(new PowerLossBlobStore(), 0, new FixedClock(1792000000000L));
            String k = kind == 0 ? "memory: " : "disk: ";
            java.util.TreeMap<String, com.storageplanner.v2.model.FileRecord> ref = new java.util.TreeMap<String, com.storageplanner.v2.model.FileRecord>();
            String[] ids = {"ffff0000ffff0000ffff0000ffff0000", "0000aaaa0000aaaa0000aaaa0000aaaa", "id-é", "Zed", "a😀"};
            for (int i = 0; i < ids.length; i++) {
                com.storageplanner.v2.model.FileRecord f = StoreContractTest.file(ids[i], "d/f" + i, 10 + i,
                        i == 3 ? com.storageplanner.v2.model.Presence.REMOVED : com.storageplanner.v2.model.Presence.PRESENT, 1);
                st.files().put(f); ref.put(ids[i], f);
            }
            st.commit();
            com.storageplanner.v2.plan.StoreFileMap m = new com.storageplanner.v2.plan.StoreFileMap(st);
            T.eq(k + "size", ref.size(), m.size());
            T.check(k + "get", m.get("Zed") != null && "d/f3".equals(m.get("Zed").path) && m.get("nope") == null && m.get(7) == null);
            T.check(k + "containsKey", m.containsKey("id-é") && !m.containsKey("nope"));
            T.eq(k + "keySet order", new ArrayList<String>(ref.keySet()), new ArrayList<String>(m.keySet()));
            List<String> ek = new ArrayList<String>(); List<String> ep = new ArrayList<String>();
            for (java.util.Map.Entry<String, com.storageplanner.v2.model.FileRecord> e : m.entrySet()) { ek.add(e.getKey()); ep.add(e.getValue().path); }
            List<String> rp = new ArrayList<String>(); for (com.storageplanner.v2.model.FileRecord f : ref.values()) rp.add(f.path);
            T.check(k + "entrySet order and values", ek.equals(new ArrayList<String>(ref.keySet())) && ep.equals(rp));
            int threw = 0;
            try { m.values(); } catch (UnsupportedOperationException e) { threw++; }
            try { m.put("x", null); } catch (UnsupportedOperationException e) { threw++; }
            try { m.remove("Zed"); } catch (UnsupportedOperationException e) { threw++; }
            try { m.clear(); } catch (UnsupportedOperationException e) { threw++; }
            try { m.containsValue(null); } catch (UnsupportedOperationException e) { threw++; }
            try { m.putAll(ref); } catch (UnsupportedOperationException e) { threw++; }
            try { m.entrySet().contains(null); } catch (UnsupportedOperationException e) { threw++; }
            T.eq(k + "other methods throw", 7, threw);
            T.eq(k + "store unchanged", ref.size(), st.files().size());
            // the adapter iterates a StoreFileMap or a natural-order SortedMap directly, anything else sorted: same order
            List<String> a1 = new ArrayList<String>(), a2 = new ArrayList<String>();
            for (java.util.Map.Entry<String, com.storageplanner.v2.model.FileRecord> e : com.storageplanner.v2adapter.V2Adapter.sortedFiles(m)) a1.add(e.getKey());
            for (java.util.Map.Entry<String, com.storageplanner.v2.model.FileRecord> e : com.storageplanner.v2adapter.V2Adapter.sortedFiles(new java.util.HashMap<String, com.storageplanner.v2.model.FileRecord>(ref))) a2.add(e.getKey());
            T.check(k + "adapter order", a1.equals(new ArrayList<String>(ref.keySet())) && a2.equals(a1));
        }
    }

    /**
     * Item 6 (and items 4-5 on the app path): V2Analysis — streamed ASSESS, recommendations per verdict, StoreFileMap, adapter
     * iterating the store — gives exactly what the old V2Runner.analyse body gave (verdict list, then a HashMap of every
     * FileRecord): recommendations, plan and the adapted classic result, on both stores.
     */
    static void analysisPath() throws Exception {
        T.section("V2Analysis = old analyse");
        TempTree t = TempTree.create(), w = TempTree.create();
        try {
            DiskKillTest.tree(t);
            for (String kind : new String[]{"memory", "disk"}) {
                com.storageplanner.v2.store.Stores.Kind k = kind.equals("memory") ? com.storageplanner.v2.store.Stores.Kind.MEMORY : com.storageplanner.v2.store.Stores.Kind.DISK;
                String[] dumps = new String[2];
                for (int way = 0; way < 2; way++) {
                    java.nio.file.Path sd = w.dir(kind + "-" + way);
                    DiskKillTest.run(t.root(), w.path(kind + "-" + way + ".json"), sd, kind, false, null);
                    com.storageplanner.v2.store.V2Store st = com.storageplanner.v2.store.Stores.open(k, new DirBlobStore(sd), 0, new FixedClock(1792000000000L));
                    long scanId = -1;
                    for (com.storageplanner.v2.model.ScanSession s : st.scanSessions().all()) scanId = Math.max(scanId, s.scanId);
                    dumps[way] = way == 0 ? oldAnalyse(st, t.root().toString(), scanId) : newAnalyse(st, t.root().toString(), scanId);
                }
                T.check(kind + ": recommendations, plan and adapted result equal (" + dumps[0].length() + " chars)", dumps[0].equals(dumps[1]));
                T.check(kind + ": the comparison covers removals, plan items and units", dumps[0].contains("\"DELETE\"") && dumps[0].contains("\"items\":[{") && dumps[0].contains("\nU "));
                if (!dumps[0].equals(dumps[1])) {
                    int i = 0; while (i < Math.min(dumps[0].length(), dumps[1].length()) && dumps[0].charAt(i) == dumps[1].charAt(i)) i++;
                    System.out.println("  first difference at " + i + ": " + dumps[0].substring(Math.max(0, i - 80), Math.min(dumps[0].length(), i + 80))
                            + "\n  vs " + dumps[1].substring(Math.max(0, i - 80), Math.min(dumps[1].length(), i + 80)));
                }
            }
        } finally { t.close(); w.close(); }
    }

    static final long NOW = 1792000000000L;
    static com.storageplanner.v2.plan.HashOnDemand.Hasher hasher(final String root) {
        return new com.storageplanner.v2.plan.HashOnDemand.Hasher() {
            public String sha256(com.storageplanner.v2.model.FileRecord f) { return ScaleRun.sha256Of(root + "/" + f.path); }
        };
    }

    /** The V2Runner.analyse body as it was before S5 (HEAD c7a31ae), minus the Android wiring. */
    static String oldAnalyse(com.storageplanner.v2.store.V2Store store, String root, long scanId) throws Exception {
        JvmInstalledApps apps = new JvmInstalledApps((String) null);
        List<com.storageplanner.v2.model.Verdict> vs = com.storageplanner.v2.risk.Assessor.run(store, apps, NOW,
                com.storageplanner.v2.plan.PresentationPolicy.STANDARD, new com.storageplanner.v2.risk.RiskConfig());
        java.util.Map<String, com.storageplanner.v2.model.FileRecord> fm = new java.util.HashMap<String, com.storageplanner.v2.model.FileRecord>();
        for (com.storageplanner.v2.model.FileRecord f : store.files().all()) fm.put(f.fileId, f);
        List<com.storageplanner.v2.model.Recommendation> recs = com.storageplanner.v2.plan.ProvisionalRecommendations.of(vs, fm, scanId);
        com.storageplanner.v2.plan.HashOnDemand.run(recs, fm, hasher(root), 2000000000L);
        recs.addAll(com.storageplanner.v2.plan.AppSubjects.recs(apps, NOW, 34, scanId, com.storageplanner.v2.plan.ProvisionalRecommendations.PLAN_VERSION));
        com.storageplanner.v2.plan.UnitBuilder2.Input in = new com.storageplanner.v2.plan.UnitBuilder2.Input();
        in.recs = recs; in.files = fm; in.keepers = com.storageplanner.v2.plan.Plans.keepers(store);
        com.storageplanner.v2.plan.Plans.Made m = com.storageplanner.v2.plan.Plans.make(in, com.storageplanner.v2.model.ActionPlan.MAX_SAFE, null, false);
        com.storageplanner.v2.plan.Plans.version(store, m.plan, NOW, scanId);
        store.flush();
        com.storageplanner.v2adapter.V2Adapter.Adapted a = com.storageplanner.v2adapter.V2Adapter.adapt(in, m.built, apps.list(),
                com.storageplanner.v2adapter.V2Adapter.duplicateOf(store.duplicateGroups().all()), root, 34, NOW, 0L, 0L, false);
        return dump(recs, m, a);
    }

    static String newAnalyse(com.storageplanner.v2.store.V2Store store, String root, long scanId) throws Exception {
        com.storageplanner.v2adapter.V2Analysis.Params p = new com.storageplanner.v2adapter.V2Analysis.Params();
        p.apps = new JvmInstalledApps((String) null); p.now = NOW; p.scanId = scanId; p.sdk = 34; p.root = root;
        p.hasher = hasher(root); p.hashBudget = 2000000000L;
        com.storageplanner.v2adapter.V2Analysis.Result r = com.storageplanner.v2adapter.V2Analysis.run(store, p);
        if (store instanceof com.storageplanner.v2.store.DiskStore) {     // F-09: indexes released, adapter read the projection
            boolean released = ((com.storageplanner.v2.store.DiskStore) store).indexesReleased();
            boolean refused = false;
            try { store.files().size(); } catch (IllegalStateException e) { refused = true; }
            T.check("disk: indexes released before the adapter, files map dropped, tables refuse use", released && r.in.files == null && refused);
        } else T.check("files map is a StoreFileMap", r.in.files instanceof com.storageplanner.v2.plan.StoreFileMap);
        return dump(r.in.recs, r.made, r.adapted);
    }

    static String dump(List<com.storageplanner.v2.model.Recommendation> recs, com.storageplanner.v2.plan.Plans.Made m,
                       com.storageplanner.v2adapter.V2Adapter.Adapted a) {
        StringBuilder b = new StringBuilder();
        java.io.StringWriter sw = new java.io.StringWriter();
        com.storageplanner.v2.util.JsonWriter w = new com.storageplanner.v2.util.JsonWriter(sw);
        List<com.storageplanner.v2.model.Recommendation> keepRecs = AssessJson.recs;
        com.storageplanner.v2.model.ActionPlan keepPlan = PlanJson.plan;
        com.storageplanner.v2.plan.HashOnDemand.Result keepHash = PlanJson.hashRes;
        try {
            AssessJson.recs = recs; PlanJson.plan = m.plan; PlanJson.hashRes = null;
            w.beginArray(); AssessJson.recommendations(w); PlanJson.write(w); w.endArray();
        } finally { AssessJson.recs = keepRecs; PlanJson.plan = keepPlan; PlanJson.hashRes = keepHash; }
        b.append(sw).append('\n');
        for (com.storageplanner.v2.model.Recommendation r : recs) b.append(r.fp == null ? "-" : r.fp.size + "/" + r.fp.mtime + "/" + r.fp.inode).append(' ');
        b.append('\n');
        for (com.storageplanner.core.FileItem f : a.result.files)
            b.append(f.rel).append('|').append(f.size).append('|').append(f.area).append('|').append(f.pkg).append('|').append(f.type).append('|')
             .append(f.sha256).append('|').append(f.phash).append('\n');
        for (com.storageplanner.core.Unit u : a.ub.units) {
            b.append("U ").append(u.id).append('|').append(u.title).append('|').append(u.kind);
            for (com.storageplanner.core.Option o : u.options) b.append(" [").append(o.action).append('|').append(o.technique).append('|').append(o.saved)
                    .append('|').append(o.risk).append('|').append(o.exact).append('|').append(o.why).append(']');
            b.append('\n');
        }
        b.append(new java.util.TreeMap<String, String>(a.ub.det.duplicateOf)).append('\n');
        b.append(new java.util.TreeSet<String>(a.notPlannable)).append(' ').append(a.plannableBytes).append(' ').append(a.keeperUnitsSkipped).append(' ')
         .append(a.conflictOptionsDropped).append(' ').append(a.includedInDropped).append(' ').append(a.displayOnly).append('\n');
        return b.toString();
    }
}
