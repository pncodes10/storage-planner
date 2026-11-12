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
        List<String> a = new ArrayList<String>(Arrays.asList("scan", tree.toString(), "--out", out.toString(), "--now", NOW,
                "--target-bytes", "max", "--snapshot-every-ms", "0", "--store", store));
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

            Random r = new Random(41);
            int ok = 0, killed = 0, power = 0, notKilled = 0, afterCompletion = 0;
            for (int i = 0; i < trials; i++) {
                PowerLossBlobStore inner = new PowerLossBlobStore();
                KillingBlobStore kb = new KillingBlobStore(inner);
                int type = i % 4;
                if (type == 0) kb.killAfterReplaceCommits(1 + r.nextInt(replaces));
                else if (type == 1) kb.killBeforeReplaceCommit(1 + r.nextInt(replaces));
                else if (type == 2) kb.killAfterAppendedBytes(r.nextInt((int) appended));
                else kb.killBeforeTruncate(1);                // a fresh run starts one segment
                Path st = w.dir("k" + i);
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
            System.out.println("  disk kills: " + trials + " trials, " + killed + " killed (" + afterCompletion + " after the scan completed), "
                    + notKilled + " not killed, " + power + " with power loss");
            T.eq("killed-and-resumed runs equal to the clean run", killed, ok);
            T.check("most trials killed", killed >= trials * 3 / 4);
        } finally { t.close(); w.close(); }
        return T.finish("DiskKillTest");
    }

    public static void main(String[] args) throws Exception { System.exit(run(args) == 0 ? 0 : 1); }
}
