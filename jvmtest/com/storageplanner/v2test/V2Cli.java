package com.storageplanner.v2test;

import com.storageplanner.v2.device.*;
import com.storageplanner.v2.features.*;
import com.storageplanner.v2.discovery.DiscoveryEngine;
import com.storageplanner.v2.discovery.PathNormalizer;
import com.storageplanner.v2.discovery.PosixStorageAccess;
import com.storageplanner.v2.discovery.WalkLimits;
import com.storageplanner.v2.model.*;
import com.storageplanner.v2.pipeline.Pipeline;
import com.storageplanner.v2.pipeline.PipelineResult;
import com.storageplanner.v2.stage.PipelineConfig;
import com.storageplanner.v2.stage.Stage;
import com.storageplanner.v2.store.MemoryStore;
import com.storageplanner.v2.store.StoreException;
import com.storageplanner.v2.store.V2Store;
import com.storageplanner.v2.util.JsonWriter;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

/**
 * Runs v2 on a directory treated as a phone's shared-storage root and writes the result JSON (ARCHITECTURE 9).
 * Phase 1: discovery only.
 *
 *   V2Cli scan <root> --out <result.json> [options]      or      V2Cli <root> <result.json> [options]
 */
public final class V2Cli {
    private V2Cli() {}

    static final String USAGE = "usage: V2Cli scan <root> --out <result.json> [--state-dir D] [--volume-id primary] [--kind full|incremental]"
            + " [--graph] [--assess] [--mode standard|aggressive] [--pairs-out F --manifest M [--random N]] [--resume] [--restricted P]... [--exclude P]... [--now MS] [--snapshot-every-ms N] [--halt-after-commits N] [--store memory|disk] [--graph-decode-budget N] [--sample IMAGES VIDEOS] [--pretty]";

    /** In-process tests (DiskKillTest): replaces the state dir's BlobStore (e.g. by a killing or power-loss fake). */
    static java.util.function.Function<BlobStore, BlobStore> blobsHook;
    /** In-process tests (DiskCorruptionTest): called after each stage returns, with its id and the store (F-07 injection). */
    static java.util.function.BiConsumer<String, V2Store> afterStage;
    /** In-process tests: false ⇒ a StoreCorruption propagates out of {@link #run} instead of the automatic retry. */
    static boolean retryOnCorruption = true;
    /** In-process tests: DiskStore tunables (e.g. an index file at every checkpoint); null = the defaults. */
    static com.storageplanner.v2.store.DiskStore.Options diskOptions;

    private static V2Store openStore(com.storageplanner.v2.store.Stores.Kind k, BlobStore b, long every, SystemClock c, boolean create) throws StoreException {
        if (k == com.storageplanner.v2.store.Stores.Kind.DISK && diskOptions != null)
            return create ? com.storageplanner.v2.store.DiskStore.create(b, every, c, diskOptions) : com.storageplanner.v2.store.DiskStore.open(b, every, c, diskOptions);
        return create ? com.storageplanner.v2.store.Stores.create(k, b, every, c) : com.storageplanner.v2.store.Stores.open(k, b, every, c);
    }

    public static void main(String[] args) {
        int rc = run(args, System.out, System.err);
        System.out.flush();
        System.exit(rc);
    }

    static long usedHeapAfterGc() {
        Runtime rt = Runtime.getRuntime();
        for (int i = 0; i < 4; i++) { System.gc(); try { Thread.sleep(50); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); } }
        return rt.totalMemory() - rt.freeMemory();
    }

    /**
     * PHASE_S_STORE 3.11: a StoreCorruption (damaged record of a fail-closed table, F-04 mismatch, store I/O failure) anywhere
     * in the run ⇒ one automatic retry on a fresh store (session event D_STORE_RESET); a second one ⇒ exit 3 with the reset
     * message. No result JSON is written by a run that hit corruption.
     */
    public static int run(String[] args, PrintStream out, PrintStream err) {
        if (!retryOnCorruption) return runOnce(args, out, err, false);
        try { return runOnce(args, out, err, false); }
        catch (com.storageplanner.v2.store.StoreCorruption e) {
            err.println("store damaged, retrying once with a fresh store: " + e.getMessage());
        }
        try { return runOnce(args, out, err, true); }
        catch (com.storageplanner.v2.store.StoreCorruption e) {
            err.println("v2's saved data was damaged and has been reset; please run again (" + e.getMessage() + ")");
            return 3;
        }
    }

    private static int runOnce(String[] args, PrintStream out, PrintStream err, boolean fresh) {
        // results of an earlier in-process run (DiskKillTest runs the CLI many times in one JVM)
        V2Json.graphCoverage = null; AssessJson.verdicts = null; AssessJson.recs = null; PlanJson.plan = null; PlanJson.staleness = null; PlanJson.sample = false;
        String enc = System.getProperty("sun.jnu.encoding");
        if (enc == null || !(enc.equalsIgnoreCase("UTF-8") || enc.equalsIgnoreCase("UTF8"))) {
            err.println("V2Cli: file-name encoding is " + enc + ", not UTF-8; run with LC_ALL=C.UTF-8");
            return 4;
        }
        List<String> a = new ArrayList<String>(Arrays.asList(args));
        if (!a.isEmpty() && a.get(0).equals("scan")) a.remove(0);
        String root = null, outFile = null, stateDir = null, volumeId = "primary", kind = "full";
        boolean resume = false, pretty = false;
        List<String> restricted = null, exclude = new ArrayList<String>();
        Long now = null; long snapEvery = -1; int halt = -1; com.storageplanner.v2.store.Stores.Kind storeKind = com.storageplanner.v2.store.Stores.Kind.MEMORY;
        String installedApps = null; long budgetMs = -1, budgetBytes = -1; boolean measureMemory = false;
        V2Json.Options opt = new V2Json.Options();
        boolean graph = false, assess = false; String pairsOut = null, manifest = null; int randomPairs = 600;
        int decodeBudget = com.storageplanner.v2.graph.GraphConfig.MAX_DECODES_PER_RUN;
        com.storageplanner.v2.sample.SampleSpec sample = null;      // --sample: the app's "Quick v2 test" selection
        try {
            for (int i = 0; i < a.size(); i++) {
                String s = a.get(i);
                if (s.equals("--out")) outFile = a.get(++i);
                else if (s.equals("--state-dir")) stateDir = a.get(++i);
                else if (s.equals("--volume-id")) volumeId = a.get(++i);
                else if (s.equals("--kind")) kind = a.get(++i);
                else if (s.equals("--resume")) resume = true;
                else if (s.equals("--pretty")) pretty = true;
                else if (s.equals("--restricted")) { if (restricted == null) restricted = new ArrayList<String>(); restricted.add(PathNormalizer.normalizeRelative(a.get(++i))); }
                else if (s.equals("--exclude")) exclude.add(PathNormalizer.normalizeRelative(a.get(++i)));
                else if (s.equals("--now")) now = Long.parseLong(a.get(++i));
                else if (s.equals("--snapshot-every-ms")) snapEvery = Long.parseLong(a.get(++i));
                else if (s.equals("--halt-after-commits")) halt = Integer.parseInt(a.get(++i));
                else if (s.equals("--store")) storeKind = com.storageplanner.v2.store.Stores.Kind.valueOf(a.get(++i).toUpperCase(Locale.ROOT));
                else if (s.equals("--max-tier")) opt.maxTier = Integer.parseInt(a.get(++i));
                else if (s.equals("--hash")) opt.hash = a.get(++i);
                else if (s.equals("--mode")) opt.mode = a.get(++i);
                else if (s.equals("--target-bytes")) { opt.targetBytes = PlanJson.parseTarget(a.get(++i)); assess = true; graph = true; }
                else if (s.equals("--sdk")) PlanJson.sdk = Integer.parseInt(a.get(++i));
                else if (s.equals("--installed-apps-ext")) PlanJson.extApps = a.get(++i);
                else if (s.equals("--check-plan")) { PlanJson.checkPlan = a.get(++i); assess = true; graph = true; }
                else if (s.equals("--max-plan-tier")) PlanJson.maxTier = com.storageplanner.v2.model.Tier.valueOf(a.get(++i));
                else if (s.equals("--allow-your-call")) PlanJson.allowYourCall = true;
                else if (s.equals("--hash-budget-bytes")) PlanJson.hashBudget = Long.parseLong(a.get(++i));
                else if (s.equals("--installed-apps")) installedApps = a.get(++i);
                else if (s.equals("--workers")) { if (Integer.parseInt(a.get(++i)) != 0) err.println("note: --workers ignored, single-threaded until Phase 10 (A11)"); }
                else if (s.equals("--budget-ms")) budgetMs = Long.parseLong(a.get(++i));
                else if (s.equals("--budget-bytes")) budgetBytes = Long.parseLong(a.get(++i));
                else if (s.equals("--measure-memory")) measureMemory = true;
                else if (s.equals("--graph")) graph = true;
                else if (s.equals("--assess")) { assess = true; graph = true; }
                else if (s.equals("--pairs-out")) pairsOut = a.get(++i);
                else if (s.equals("--manifest")) manifest = a.get(++i);
                else if (s.equals("--random")) randomPairs = Integer.parseInt(a.get(++i));
                else if (s.equals("--sample")) { int im = Integer.parseInt(a.get(++i)); int vi = Integer.parseInt(a.get(++i)); sample = new com.storageplanner.v2.sample.SampleSpec(im, vi); }
                else if (s.equals("--graph-decode-budget")) decodeBudget = Integer.parseInt(a.get(++i));   // PHASE_S_STORE S7
                else if (s.startsWith("--")) { err.println("unknown option " + s); err.println(USAGE); return 2; }
                else if (root == null) root = s;
                else if (outFile == null) outFile = s;
                else { err.println(USAGE); return 2; }
            }
        } catch (RuntimeException e) {
            err.println("bad arguments: " + e.getMessage()); err.println(USAGE); return 2;
        }
        if (root == null || outFile == null || !(kind.equals("full") || kind.equals("incremental"))) { err.println(USAGE); return 2; }
        if (!Files.isDirectory(Paths.get(root))) { err.println("not a directory: " + root); return 2; }
        opt.kind = kind;

        SystemClock clock = new SystemClock(now == null ? Long.MIN_VALUE : now);
        long t0 = System.nanoTime();
        PosixStorageAccess.Root r = new PosixStorageAccess.Root();
        r.volumeId = volumeId; r.kind = VolumeKind.DIRECTORY; r.label = Paths.get(root).getFileName() == null ? root : Paths.get(root).getFileName().toString();
        r.path = Paths.get(root).toAbsolutePath().normalize().toString(); r.fileKeyStable = Tri.TRUE;
        r.restrictedPrefixes = restricted != null ? restricted : Arrays.asList("Android/data", "Android/obb");
        PosixStorageAccess sa = new PosixStorageAccess(Collections.singletonList(r),
                Collections.singletonList(AccessGrant.readAll(volumeId, "", AccessMode.B_ALL_FILES)));

        PipelineConfig cfg = new PipelineConfig();
        cfg.kind = kind.equals("full") ? ScanKind.FULL : ScanKind.INCREMENTAL;
        cfg.resume = resume;
        cfg.maxTier = opt.maxTier == null ? 3 : opt.maxTier;
        cfg.budget.maxMs = budgetMs; cfg.budget.maxBytesRead = budgetBytes;
        if (!opt.hash.equals("collisions") && !opt.hash.equals("all")) { err.println("--hash collisions|all"); return 2; }
        V2Store store;
        BlobStore blobs = null;
        try {
            if (stateDir != null) {
                blobs = new DirBlobStore(Paths.get(stateDir));
                if (blobsHook != null) blobs = blobsHook.apply(blobs);
                if (halt > 0) blobs = new KillingBlobStore(blobs).killAfterReplaceCommits(halt).haltProcess(true);
                if (fresh) {
                    store = openStore(storeKind, blobs, snapEvery, clock, true);
                    cfg.storeReset = true;
                } else try { store = openStore(storeKind, blobs, snapEvery, clock, false); }
                catch (StoreException e) {
                    err.println("store unreadable, starting fresh: " + e.getMessage());
                    store = openStore(storeKind, blobs, snapEvery, clock, true);
                    cfg.storeReset = true;
                }
            } else if (storeKind == com.storageplanner.v2.store.Stores.Kind.DISK) {
                store = com.storageplanner.v2.store.DiskStore.create(new PowerLossBlobStore(), snapEvery, clock);   // no state dir: in memory
            } else store = MemoryStore.volatileStore();
        } catch (StoreException e) {
            err.println("store: " + e.getMessage()); return 3;
        } catch (IOException e) {
            err.println("state dir: " + e); return 3;
        }
        WalkLimits lim = new WalkLimits();
        lim.excluded.addAll(exclude);
        JvmInstalledApps apps;
        try { apps = new JvmInstalledApps(installedApps); } catch (IllegalArgumentException e) { err.println(e.getMessage()); return 2; }
        JvmDevice dev = new JvmDevice(sa, clock, new JvmResourceMonitor(), blobs, new JvmMediaDecoder(sa), apps);
        Set<String> installed = null;
        if (apps.availability() == Availability.FULL) { installed = new TreeSet<String>(); for (InstalledApp x : apps.list()) installed.add(x.packageName); }
        List<Stage> stages = new ArrayList<Stage>();
        stages.add(StageTimer.wrap(new DiscoveryEngine(lim, installed)));
        if (sample != null) stages.add(StageTimer.wrap(new com.storageplanner.v2.sample.SampleStage(sample)));
        if (cfg.maxTier >= 1) stages.add(StageTimer.wrap(new T1MetadataStage()));
        if (cfg.maxTier >= 2) stages.add(StageTimer.wrap(new T2HashStructStage(opt.hash.equals("all"))));
        if (cfg.maxTier >= 3) stages.add(StageTimer.wrap(new T3ImageStage()));
        com.storageplanner.v2.graph.GraphStage gs = new com.storageplanner.v2.graph.GraphStage();
        gs.decodeBudget = decodeBudget;
        if (graph || pairsOut != null) stages.add(StageTimer.wrap(gs));
        long heapBefore = measureMemory ? usedHeapAfterGc() : 0;
        PipelineResult res;
        try {
            res = new Pipeline(dev, store, cfg, stages).run(null);
        } catch (com.storageplanner.v2.store.StoreLowSpace e) {
            err.println("store: " + e.getMessage()); return 3;
        } catch (StoreException e) {
            com.storageplanner.v2.store.StoreCorruption c = com.storageplanner.v2.store.Stores.corruption(e);
            if (c != null) throw c;
            err.println("store error: " + e.getMessage()); return 3;
        }
        long totalMs = (System.nanoTime() - t0) / 1000000L;
        if (graph || pairsOut != null) V2Json.graphCoverage = gs.coverage;
        AssessJson.verdicts = null; AssessJson.recs = null;
        if (assess) {
            long ta = System.nanoTime();
            try {
                // the app's streaming path (PHASE_S_STORE 4 items 4-5): recommendations per verdict from the post-ASSESS record,
                // PLAN reads files through StoreFileMap; the verdict map is kept only for the CLI JSON
                final Map<String, com.storageplanner.v2.model.Verdict> vm = new HashMap<String, com.storageplanner.v2.model.Verdict>();
                final List<com.storageplanner.v2.model.Recommendation> recs = new ArrayList<com.storageplanner.v2.model.Recommendation>();
                final long scanId = res.session.scanId;
                com.storageplanner.v2.risk.Assessor.run(store, apps, clock.nowMillis(), com.storageplanner.v2.plan.PresentationPolicy.of(opt.mode),
                        new com.storageplanner.v2.risk.RiskConfig(), new com.storageplanner.v2.risk.Assessor.VerdictSink() {
                            public void accept(com.storageplanner.v2.model.Verdict v, FileRecord f) {
                                vm.put(v.fileId, v);
                                com.storageplanner.v2.plan.ProvisionalRecommendations.ofOne(v, f, scanId, recs);
                            }
                        });
                Map<String, FileRecord> fm = new com.storageplanner.v2.plan.StoreFileMap(store);
                AssessJson.verdicts = vm;
                AssessJson.recs = recs;
                PlanJson.plan = null; PlanJson.staleness = null;
                if (opt.targetBytes != null || PlanJson.checkPlan != null) {
                    long tp = System.nanoTime();
                    AssessJson.mode = opt.mode;
                    PlanJson.sample = sample != null;
                    String perr = PlanJson.make(store, apps, installedApps, r.path, fm, opt.targetBytes, clock.nowMillis(), res.session.scanId, err);
                    if (perr != null) { err.println(perr); return 3; }
                    StageTimer.ms.put("PLAN", (System.nanoTime() - tp) / 1000000L);
                }
            } catch (StoreException e) {
                com.storageplanner.v2.store.StoreCorruption c = com.storageplanner.v2.store.Stores.corruption(e);
                if (c != null) throw c;
                err.println("assess: " + e.getMessage()); return 3;
            }
            StageTimer.ms.put("ASSESS", (System.nanoTime() - ta) / 1000000L);
        }
        // F-07: derived records found damaged by ASSESS/PLAN are counted in the session too (never for MemoryStore)
        if (Pipeline.noteDamaged(store, res.session, clock.nowMillis())) {
            try { store.scanSessions().put(res.session); store.flush(); }
            catch (StoreException e) { err.println("store error: " + e.getMessage()); return 3; }
            res.coverage.events = res.session.events; res.coverage.eventsDropped = res.session.eventsDropped;
        }
        if (pairsOut != null) {
            try { PairsDump.write(store, gs, manifest, randomPairs, pairsOut, err); }
            catch (IOException e) { err.println("pairs: " + e); return 3; }
        }
        V2Json.Timings t = new V2Json.Timings();
        t.totalMs = totalMs;
        for (Map.Entry<String, Long> e : StageTimer.ms.entrySet()) t.stages.put(e.getKey(), e.getValue());
        if (t.stages.isEmpty()) t.stages.put("DISCOVER", totalMs);
        long present = 0, found = 0;
        for (FileRecord f : store.files().all()) { found++; if (f.presence == Presence.PRESENT) present++; }
        t.filesPerSecond = totalMs > 0 ? present * 1000.0 / totalMs : 0;
        Runtime rt = Runtime.getRuntime();
        t.peakHeapBytes = rt.totalMemory() - rt.freeMemory();
        t.bytesRead = cfg.budget.bytesRead;
        if (measureMemory) {
            long heapAfter = usedHeapAfterGc();
            int nf = store.files().size();
            long snap = -1;
            try {
                snap = MemoryStoreSize.snapshotBytes(store);
            } catch (Exception e) { err.println("snapshot size: " + e); }
            err.println(String.format(Locale.ROOT, "memory: files=%d features=%d hashes=%d heapBefore=%d heapAfter=%d retainedPerFile=%.0f B snapshotBytes=%d snapshotPerFile=%.0f B",
                    nf, store.features().size(), store.hashes().size(), heapBefore, heapAfter, nf == 0 ? 0.0 : (heapAfter - heapBefore) / (double) nf, snap, nf == 0 ? 0.0 : snap / (double) nf));
        }
        // built in memory first: a StoreCorruption while reading the store for the JSON leaves no partial result file
        StringWriter sw = new StringWriter();
        JsonWriter w = new JsonWriter(sw, pretty);
        V2Json.writeResult(w, store, r.path, volumeId, opt, res.session, res.coverage, clock.nowMillis(), t);
        sw.write('\n');
        try (Writer wr = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(outFile), StandardCharsets.UTF_8))) {
            wr.write(sw.toString());
        } catch (IOException e) {
            err.println("cannot write " + outFile + ": " + e); return 3;
        }
        CoverageReport c = res.coverage;
        if (sample != null) {
            int nImg = 0, nVid = 0;
            for (FileRecord f : store.files().all()) { int kd = com.storageplanner.v2.sample.SampleSelector.kindOf(f.extension); if (kd == 1) nImg++; else if (kd == 2) nVid++; }
            long discovered = found + res.session.counter("sampleDropped");
            out.println("sample: " + discovered + " files discovered, " + nImg + " images and " + nVid + " videos selected, "
                    + (found - nImg - nVid) + " other records; " + sample.notice());
        }
        out.println("scan " + res.session.scanId + " " + res.session.state + ": " + present + " files present (" + found + " records), "
                + c.inaccessibleTotal + " inaccessible locations, complete=" + c.complete + ", " + totalMs + " ms");
        return 0;
    }
}
