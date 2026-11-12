package com.storageplanner.v2test;

import com.storageplanner.v2.device.BlobStore;
import com.storageplanner.v2.util.Json;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * PHASE_S_STORE S3 (first part): the whole pipeline (DISCOVER … PLAN, {@code --target-bytes max}) on a DiskStore, killed at
 * one of the four KillingBlobStore points (after/before replace commit k, after b appended bytes, before truncate k), a
 * third of the runs also losing power (PowerLossBlobStore), then resumed: the result must equal the clean MemoryStore run
 * (evaluate.py --compare semantics: timings, resumedCount and events ignored). Also the clean DiskStore run must equal the
 * clean MemoryStore run, and a second run over the same state (the app's multi-run path) must equal MemoryStore's.
 * Trials: -Dv2.diskKillTrials (default 16).
 */
public final class DiskKillTest {
    static final String NOW = "1792000000000";

    static void tree(TempTree t) throws Exception {
        for (int i = 0; i < 40; i++) {
            java.awt.image.BufferedImage img = FxImg.pattern(64, 48, 1000 + i);
            byte[] png = FxImg.encode(img, "png");
            t.file(String.format(Locale.ROOT, "DCIM/Camera/IMG_2024%04d_%06d.png", i, i * 7), png);
            if (i < 15) t.file(String.format(Locale.ROOT, "WhatsApp/Media/WhatsApp Images/IMG-20240%03d-WA%04d.jpg", i, i), FxImg.jpeg(img, 0.6f));
            if (i % 4 == 0) t.file(String.format(Locale.ROOT, "Download/copy é %d.png", i), png);
        }
        Random r = new Random(3);
        for (int i = 0; i < 225; i++) {
            String dir = i % 3 == 0 ? "Documents/a" : i % 3 == 1 ? "Documents/b/😀" : "Download/sub";
            byte[] b = new byte[16 + (i % 23) * 5];
            if (i % 9 != 0) r.nextBytes(b);            // every 9th file has zero content of a repeating size: exact duplicates
            t.file(dir + "/doc" + i + ".bin", b);
        }
    }

    static String run(Path tree, Path out, Path state, String store, boolean resume, BlobStore hookStore) throws Exception {
        return run(tree, out, state, store, resume, hookStore, "0");
    }

    static String run(Path tree, Path out, Path state, String store, boolean resume, BlobStore hookStore, String snapEvery) throws Exception {
        List<String> a = new ArrayList<String>(Arrays.asList("scan", tree.toString(), "--out", out.toString(), "--now", NOW,
                "--target-bytes", "max", "--snapshot-every-ms", snapEvery, "--store", store));
        if (state != null) { a.add("--state-dir"); a.add(state.toString()); }
        if (resume) a.add("--resume");
        final BlobStore hs = hookStore;
        V2Cli.blobsHook = hs == null ? null : new java.util.function.Function<BlobStore, BlobStore>() { public BlobStore apply(BlobStore b) { return hs; } };
        PrintStream sink = new PrintStream(new ByteArrayOutputStream(), true, "UTF-8");
        try {
            int rc = V2Cli.run(a.toArray(new String[0]), sink, sink);
            if (rc != 0) throw new IllegalStateException("V2Cli exit " + rc);
        } finally { V2Cli.blobsHook = null; }
        return new String(Files.readAllBytes(out), StandardCharsets.UTF_8);
    }

    /** evaluate.py's IGNORED, plus the per-run work counters: a resumed run legitimately re-uses or re-does work. */
    static final Set<String> IGNORED = new HashSet<String>(Arrays.asList("timings", "scan.resumedCount", "coverage.resumedCount",
            "coverage.events", "coverage.eventsDropped", "coverage.extraction", "graphCoverage.decodes"));

    static final Set<String> PLAN_ID = new HashSet<String>(Arrays.asList("plan.planId", "plan.planVersion"));

    static Object strip(Object o, String path) { return strip(o, path, IGNORED); }

    @SuppressWarnings("unchecked")
    static Object strip(Object o, String path, Set<String> ignored) {
        if (o instanceof Map) {
            Map<String, Object> m = new TreeMap<String, Object>();
            for (Map.Entry<String, Object> e : ((Map<String, Object>) o).entrySet()) {
                String p = path.isEmpty() ? e.getKey() : path + "." + e.getKey();
                if (!ignored.contains(p)) m.put(e.getKey(), strip(e.getValue(), p, ignored));
            }
            return m;
        }
        if (o instanceof List) { List<Object> l = new ArrayList<Object>(); for (Object x : (List<Object>) o) l.add(strip(x, path, ignored)); return l; }
        return o;
    }

    @SuppressWarnings("unchecked")
    static String firstDiff(String p, Object a, Object b) {
        if (a instanceof Map && b instanceof Map) {
            TreeSet<String> ks = new TreeSet<String>(((Map<String, Object>) a).keySet()); ks.addAll(((Map<String, Object>) b).keySet());
            for (String k : ks) { String d = firstDiff(p + "." + k, ((Map<String, Object>) a).get(k), ((Map<String, Object>) b).get(k)); if (d != null) return d; }
            return null;
        }
        if (a instanceof List && b instanceof List && ((List<Object>) a).size() == ((List<Object>) b).size()) {
            for (int i = 0; i < ((List<Object>) a).size(); i++) { String d = firstDiff(p + "[" + i + "]", ((List<Object>) a).get(i), ((List<Object>) b).get(i)); if (d != null) return d; }
            return null;
        }
        if (a == null ? b == null : a.equals(b)) return null;
        String x = String.valueOf(a), y = String.valueOf(b);
        return p + ": " + (x.length() > 300 ? x.substring(0, 300) : x) + " != " + (y.length() > 300 ? y.substring(0, 300) : y);
    }

    static Object norm(String json) throws Exception { return strip(Json.parse(json), ""); }

    public static int run(String[] args) throws Exception {
        T.section("DiskKill");
        int trials = Integer.getInteger("v2.diskKillTrials", 16);
        String store = System.getProperty("v2.diskKillStore", "disk");
        TempTree t = TempTree.create();
        TempTree w = TempTree.create();
        try {
            tree(t);
            Path tr = t.root();
            Object clean = norm(run(tr, w.path("clean.json"), null, "memory", false, null));
            // clean disk run, learning the ranges of the kill points
            PowerLossBlobStore pb = new PowerLossBlobStore();
            KillingBlobStore probe = new KillingBlobStore(pb);
            Object diskClean = norm(run(tr, w.path("disk.json"), w.dir("probe"), "disk", false, probe));
            T.check("clean DiskStore run equals clean MemoryStore run", clean.equals(diskClean));
            int replaces = probe.replaceCommits();
            long appended = probe.appendedBytes();
            T.check("pipeline writes manifests (" + replaces + ") and log bytes (" + appended + ")", replaces > 5 && appended > 10000);
            // second run over the same state (cfg.resume false, the app's path): equal to MemoryStore's second run
            Path ms = w.dir("mstate");
            run(tr, w.path("m1.json"), ms, "memory", false, null);
            Object m2 = norm(run(tr, w.path("m2.json"), ms, "memory", false, null));
            Object d2 = norm(run(tr, w.path("d2.json"), w.dir("probe2"), "disk", false, pb));
            Object m2NoPlanId = strip(m2, "", PLAN_ID);
            T.check("second run over a persistent DiskStore equals MemoryStore's second run", m2.equals(d2));

            loop(tr, w, store, trials, replaces, appended, 1, clean, m2NoPlanId, new Random(41), "disk kills");
            if ("disk".equals(store)) {
                indexKills(tr, w, clean, m2NoPlanId);
                phantomSegment(tr, w, m2);
                compactionKills(tr, w, clean, m2NoPlanId, trials);
            }
        } finally { t.close(); w.close(); V2Cli.diskOptions = null; }
        evolving();
        return T.finish("DiskKillTest");
    }

    /**
     * The kill/resume loop: trial i kills after/before a random replace commit, after random appended bytes, or before a
     * random truncate (1..truncs); every third trial also loses power; the resumed run must equal its reference.
     */
    static void loop(Path tr, TempTree w, String store, int trials, int replaces, long appended, int truncs, Object clean,
                     Object m2NoPlanId, Random r, String label) throws Exception {
        int ok = 0, killed = 0, power = 0, notKilled = 0, afterCompletion = 0;
        for (int i = 0; i < trials; i++) {
            PowerLossBlobStore inner = new PowerLossBlobStore();
            KillingBlobStore kb = new KillingBlobStore(inner);
            int type = i % 4;
            if (type == 0) kb.killAfterReplaceCommits(1 + r.nextInt(replaces));
            else if (type == 1) kb.killBeforeReplaceCommit(1 + r.nextInt(replaces));
            else if (type == 2) kb.killAfterAppendedBytes(r.nextInt((int) appended));
            else kb.killBeforeTruncate(1 + r.nextInt(truncs));      // a fresh run truncates once (its first segment); compactions more
            Path st = w.dir(label.replace(' ', '-') + i);
            boolean k = false;
            try { run(tr, w.path("k" + i + ".json"), st, store, false, kb); }
            catch (SimulatedKill e) { killed++; k = true; }
            if (!k) { notKilled++; continue; }
            if (i % 3 == 2) { inner.powerLoss(r, r.nextBoolean(), r.nextBoolean()); power++; }
            Object res = norm(run(tr, w.path("r" + i + ".json"), st, store, true, inner));
            // the kill may land after the scan's COMPLETED state was durable (CLI writes after the pipeline): then the
            // "resume" is a second scan over a completed store, and the reference is MemoryStore's second run
            boolean second = res instanceof Map && "2".equals(String.valueOf(((Map<?, ?>) ((Map<?, ?>) res).get("coverage")).get("scanId")));
            if (second) afterCompletion++;
            // and the first run's plan may not have been durable, so its plan numbering may not have advanced
            if (second) res = strip(res, "", PLAN_ID);
            Object ref = second ? m2NoPlanId : clean;
            if (res.equals(ref)) ok++;
            else System.out.println("  trial " + i + " (kill type " + type + ", store " + store + "): resumed result differs from its reference" + (second ? " (second run)" : "") + ": " + firstDiff("", ref, res));
        }
        System.out.println("  " + label + ": " + trials + " trials, " + killed + " killed (" + afterCompletion + " after the scan completed), "
                + notKilled + " not killed, " + power + " with power loss");
        T.eq(label + ": killed-and-resumed runs equal to the clean run", killed, ok);
        T.check(label + ": most trials killed", killed >= trials * 3 / 4);
    }

    /**
     * S4: the same kill/resume trials with compaction at every checkpoint where garbage ≥ live (compactMinGarbage 0) and
     * small segments, so kills land inside compactions (replace commits of hw raises, index and manifest; truncates of both
     * sets). The resumed result must equal the clean run.
     */
    static void compactionKills(Path tr, TempTree w, Object clean, Object m2NoPlanId, int trials) throws Exception {
        T.section("S4 compaction kills");
        com.storageplanner.v2.store.DiskStore.Options o = new com.storageplanner.v2.store.DiskStore.Options();
        o.compactMinGarbage = 0;
        o.maxSegmentBytes = 64 << 10;
        V2Cli.diskOptions = o;
        try {
            PowerLossBlobStore pb = new PowerLossBlobStore();
            KillingBlobStore probe = new KillingBlobStore(pb);
            T.check("clean run with forced compaction equals clean", clean.equals(norm(run(tr, w.path("cp.json"), w.dir("cp"), "disk", false, probe))));
            int truncs = probe.truncates();
            T.check("the run compacted (" + truncs + " truncates, " + probe.replaceCommits() + " replace commits)", truncs > 10);
            com.storageplanner.v2.store.DiskStore d = com.storageplanner.v2.store.DiskStore.open(DiskStoreTest.copy(pb), 0, new FixedClock(0));
            com.storageplanner.v2.store.StoreStats st = d.stats();
            T.check("disk after the run ≤ 2 × live + 64 KiB (" + st + ")", st.diskBytes <= 2 * st.liveBytes + (64 << 10));
            loop(tr, w, "disk", Math.max(8, trials), probe.replaceCommits(), probe.appendedBytes(), truncs, clean, m2NoPlanId, new Random(43), "compaction kills");
        } finally { V2Cli.diskOptions = null; }
    }

    /** Kills right after the n-th index file {@code replace} commits, before the manifest that would name it (F-01, F-12). */
    static final class IndexKill implements BlobStore {
        final BlobStore in; final int n; int idx;
        IndexKill(BlobStore in, int n) { this.in = in; this.n = n; }
        public java.io.InputStream read(String name) throws java.io.IOException { return in.read(name); }
        public java.io.OutputStream append(String name) throws java.io.IOException { return in.append(name); }
        public void sync(String name) throws java.io.IOException { in.sync(name); }
        public void truncate(String name) throws java.io.IOException { in.truncate(name); }
        public long length(String name) { return in.length(name); }
        public RandomRead openRead(String name) throws java.io.IOException { return in.openRead(name); }
        public AtomicWrite replace(final String name) throws java.io.IOException {
            final AtomicWrite w = in.replace(name);
            return new AtomicWrite() {
                public java.io.OutputStream stream() { return w.stream(); }
                public void commit() throws java.io.IOException {
                    w.commit();
                    if (name.endsWith(".idx") && ++idx == n) throw new SimulatedKill("after index file " + n);
                }
                public void abort() { w.abort(); }
            };
        }
    }

    /**
     * F-12: a pipeline run whose store writes the index file at every checkpoint (indexTailBytes 0), killed between an index
     * replace and the manifest replace: the reopened store must reject that index (rebuild by scan) and the resumed run
     * must equal the clean run.
     */
    static void indexKills(Path tr, TempTree w, Object clean, Object m2NoPlanId) throws Exception {
        T.section("F-12 index kill");
        com.storageplanner.v2.store.DiskStore.Options o = new com.storageplanner.v2.store.DiskStore.Options();
        o.indexTailBytes = 0;
        V2Cli.diskOptions = o;
        try {
            IndexKill probe = new IndexKill(new PowerLossBlobStore(), Integer.MAX_VALUE);
            T.check("index-every-checkpoint disk run equals clean", clean.equals(norm(run(tr, w.path("ip.json"), w.dir("ip"), "disk", false, probe))));
            int writes = probe.idx;
            T.check("pipeline wrote index files (" + writes + ")", writes > 5);
            int trials = Math.min(writes, 8), ok = 0, rejected = 0;
            for (int i = 0; i < trials; i++) {
                int n = 1 + (int) ((long) i * (writes - 1) / Math.max(1, trials - 1));
                PowerLossBlobStore inner = new PowerLossBlobStore();
                boolean k = false;
                try { run(tr, w.path("ik.json"), w.dir("ik" + i), "disk", false, new IndexKill(inner, n)); } catch (SimulatedKill e) { k = true; }
                com.storageplanner.v2.store.DiskStore re = com.storageplanner.v2.store.DiskStore.open(DiskStoreTest.copy(inner), 0, new FixedClock(0), o);
                if (k && !re.loadedFromIndex()) rejected++;
                Object res = norm(run(tr, w.path("ir.json"), w.dir("ik" + i), "disk", true, inner));
                boolean second = "2".equals(String.valueOf(((Map<?, ?>) ((Map<?, ?>) res).get("coverage")).get("scanId")));
                // the last index writes come after the scan's COMPLETED state was durable: then the reference is a second run
                Object ref = second ? m2NoPlanId : clean;
                if (second) res = strip(res, "", PLAN_ID);
                if (k && res.equals(ref)) ok++;
                else System.out.println("  index kill " + n + ": killed=" + k + " second=" + second + " " + firstDiff("", ref, res));
            }
            T.eq("killed after index write " + trials + " times: newer index rejected at reopen", trials, rejected);
            T.eq("killed after index write: resumed result equals clean", trials, ok);
        } finally { V2Cli.diskOptions = null; }
    }

    /**
     * F-12 / F-02: the second run over a persistent store, checkpointing only at stage ends, is killed after part of its log
     * was appended, leaving complete frames (with COMMITs) beyond the manifest's durable state; there is no index file, so
     * the reopen rebuilds by scan. Those frames must never be replayed: the resumed run equals MemoryStore's second run.
     */
    static void phantomSegment(Path tr, TempTree w, Object m2) throws Exception {
        T.section("F-12 stale segment");
        PowerLossBlobStore b1 = new PowerLossBlobStore();
        run(tr, w.path("p1.json"), w.dir("p1"), "disk", false, b1, "100000000");
        KillingBlobStore probe = new KillingBlobStore(DiskStoreTest.copy(b1));
        run(tr, w.path("p2.json"), w.dir("p2"), "disk", false, probe, "100000000");
        long total = probe.appendedBytes();
        int ok = 0, stale = 0, n = 6;
        for (int i = 1; i <= n; i++) {
            PowerLossBlobStore c = DiskStoreTest.copy(b1);
            boolean k = false;
            try { run(tr, w.path("pk.json"), w.dir("pk" + i), "disk", false, new KillingBlobStore(c).killAfterAppendedBytes(total * i / (n + 1)), "100000000"); }
            catch (SimulatedKill e) { k = true; }
            com.storageplanner.v2.store.DiskStore d = com.storageplanner.v2.store.DiskStore.open(DiskStoreTest.copy(c), 0, new FixedClock(0));
            long segBytes = 0;
            for (String nm : c.names()) if (nm.endsWith(".seg")) segBytes += c.bytes(nm).length;
            if (k && !d.loadedFromIndex() && segBytes > d.stats().diskBytes) stale++;
            Object res = norm(run(tr, w.path("pr.json"), w.dir("pk" + i), "disk", true, c, "100000000"));
            if (k && res.equals(m2)) ok++;
            else System.out.println("  stale-segment trial " + i + ": killed=" + k + " " + firstDiff("", m2, res));
        }
        T.eq("killed second runs left log bytes beyond durable, reopened by scan", n, stale);
        T.eq("resumed second runs equal MemoryStore's second run (stale frames never replayed)", n, ok);
    }

    /**
     * F-12: three runs over one persistent store (cfg.resume false, the app's path) with files added, removed, moved and
     * modified between runs: DiskStore equals MemoryStore on every run (timings removed, nothing else ignored).
     */
    static void evolving() throws Exception {
        T.section("F-12 evolving tree");
        TempTree t = TempTree.create();
        TempTree w = TempTree.create();
        try {
            tree(t);
            Path tr = t.root();
            Set<String> timings = Collections.singleton("timings");
            Path ms = w.dir("m"), ds = w.dir("d");
            PowerLossBlobStore pb = new PowerLossBlobStore();
            for (int round = 0; round < 3; round++) {
                if (round == 1) {
                    t.file("Documents/a/new 1.bin", new byte[]{1, 2, 3});
                    t.file("DCIM/Camera/IMG_new.png", FxImg.encode(FxImg.pattern(64, 48, 1003), "png"));   // copy of image 3's pixels, new file
                    Files.delete(tr.resolve("Download/copy é 4.png"));
                    Files.move(tr.resolve("Documents/a/doc3.bin"), tr.resolve("Documents/b/doc3 moved.bin"));
                    Files.write(tr.resolve("Download/sub/doc2.bin"), new byte[]{9, 9, 9, 9, 9});
                    Files.setLastModifiedTime(tr.resolve("Download/sub/doc2.bin"), java.nio.file.attribute.FileTime.fromMillis(1700000000000L));
                } else if (round == 2) {
                    Files.delete(tr.resolve("DCIM/Camera/IMG_20240001_000007.png"));
                    Files.move(tr.resolve("Download/copy é 8.png"), tr.resolve("Documents/copy 8 moved.png"));
                    t.file("Documents/b/😀/late.bin", new byte[40]);
                    Files.write(tr.resolve("Documents/a/new 1.bin"), new byte[]{7, 7, 7, 7});
                    Files.setLastModifiedTime(tr.resolve("Documents/a/new 1.bin"), java.nio.file.attribute.FileTime.fromMillis(1700000500000L));
                }
                Object m = strip(Json.parse(run(tr, w.path("m" + round + ".json"), ms, "memory", false, null)), "", timings);
                Object d = strip(Json.parse(run(tr, w.path("d" + round + ".json"), ds, "disk", false, pb)), "", timings);
                if (!m.equals(d)) System.out.println("  evolving run " + (round + 1) + ": " + firstDiff("", m, d));
                T.check("evolving tree, run " + (round + 1) + ": DiskStore equals MemoryStore", m.equals(d));
            }
        } finally { t.close(); w.close(); }
    }

    public static void main(String[] args) throws Exception { System.exit(run(args) == 0 ? 0 : 1); }
}
