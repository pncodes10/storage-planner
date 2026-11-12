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
            + " [--resume] [--restricted P]... [--exclude P]... [--now MS] [--snapshot-every-ms N] [--halt-after-commits N] [--pretty]";

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

    public static int run(String[] args, PrintStream out, PrintStream err) {
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
        Long now = null; long snapEvery = -1; int halt = -1;
        String installedApps = null; long budgetMs = -1, budgetBytes = -1; boolean measureMemory = false;
        V2Json.Options opt = new V2Json.Options();
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
                else if (s.equals("--max-tier")) opt.maxTier = Integer.parseInt(a.get(++i));
                else if (s.equals("--hash")) opt.hash = a.get(++i);
                else if (s.equals("--mode")) opt.mode = a.get(++i);
                else if (s.equals("--target-bytes")) { String v = a.get(++i); opt.targetBytes = v.equals("max") ? Long.MAX_VALUE : Long.parseLong(v); }
                else if (s.equals("--installed-apps")) installedApps = a.get(++i);
                else if (s.equals("--workers")) { if (Integer.parseInt(a.get(++i)) != 0) err.println("note: --workers ignored, single-threaded until Phase 10 (A11)"); }
                else if (s.equals("--budget-ms")) budgetMs = Long.parseLong(a.get(++i));
                else if (s.equals("--budget-bytes")) budgetBytes = Long.parseLong(a.get(++i));
                else if (s.equals("--measure-memory")) measureMemory = true;
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
                if (halt > 0) blobs = new KillingBlobStore(blobs).killAfterReplaceCommits(halt).haltProcess(true);
                try { store = MemoryStore.open(blobs, snapEvery, clock); }
                catch (StoreException e) {
                    err.println("store unreadable, starting fresh: " + e.getMessage());
                    store = MemoryStore.create(blobs, snapEvery, clock);
                    cfg.storeReset = true;
                }
            } else store = MemoryStore.volatileStore();
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
        if (cfg.maxTier >= 1) stages.add(StageTimer.wrap(new T1MetadataStage()));
        if (cfg.maxTier >= 2) stages.add(StageTimer.wrap(new T2HashStructStage(opt.hash.equals("all"))));
        if (cfg.maxTier >= 3) stages.add(StageTimer.wrap(new T3ImageStage()));
        long heapBefore = measureMemory ? usedHeapAfterGc() : 0;
        PipelineResult res;
        try {
            res = new Pipeline(dev, store, cfg, stages).run(null);
        } catch (StoreException e) {
            err.println("store error: " + e.getMessage()); return 3;
        }
        long totalMs = (System.nanoTime() - t0) / 1000000L;
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
        try (Writer wr = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(outFile), StandardCharsets.UTF_8))) {
            JsonWriter w = new JsonWriter(wr, pretty);
            V2Json.writeResult(w, store, r.path, volumeId, opt, res.session, res.coverage, clock.nowMillis(), t);
            wr.write('\n');
        } catch (IOException e) {
            err.println("cannot write " + outFile + ": " + e); return 3;
        }
        CoverageReport c = res.coverage;
        out.println("scan " + res.session.scanId + " " + res.session.state + ": " + present + " files present (" + found + " records), "
                + c.inaccessibleTotal + " inaccessible locations, complete=" + c.complete + ", " + totalMs + " ms");
        return 0;
    }
}
