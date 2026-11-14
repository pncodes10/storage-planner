package com.storageplanner.app;

import android.content.Context;
import android.content.SharedPreferences;

import com.storageplanner.app.v2.AndroidV2Device;
import com.storageplanner.core.AppItem;
import com.storageplanner.core.Estimates;
import com.storageplanner.core.Scanner;
import com.storageplanner.v2.device.Availability;
import com.storageplanner.v2.device.InstalledApp;
import com.storageplanner.v2.device.ScanControl;
import com.storageplanner.v2.model.FileRecord;
import com.storageplanner.v2.model.ScanSession;
import com.storageplanner.v2.model.ScanState;
import com.storageplanner.v2.pipeline.Pipeline;
import com.storageplanner.v2.pipeline.PipelineResult;
import com.storageplanner.v2.plan.HashOnDemand;
import com.storageplanner.v2.sample.SampleSpec;
import com.storageplanner.v2.sample.SampleStage;
import com.storageplanner.v2.stage.PipelineConfig;
import com.storageplanner.v2.stage.Stage;
import com.storageplanner.v2.stage.StageContext;
import com.storageplanner.v2.stage.StageOutcome;
import com.storageplanner.v2.store.SpaceProbe;
import com.storageplanner.v2.store.Stores;
import com.storageplanner.v2.store.StoreException;
import com.storageplanner.v2.store.V2Store;
import com.storageplanner.v2.util.Hex;
import com.storageplanner.v2adapter.V2Adapter;
import com.storageplanner.v2adapter.V2Analysis;
import com.storageplanner.v2adapter.V2Capacity;

import java.io.FileInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The v2 analysis (Phase A): discovery -> extraction (T1-T3) -> graph -> assess -> plan, adapted to the classic Unit/Option types
 * so every existing screen works. Read-only like classic: nothing here deletes, moves or changes user files. The v2 store
 * lives in getFilesDir()/v2 (AtomicFile); the "analysis" preference result_algorithm says which algorithm produced the saved
 * result, so a restore after process death uses the same algorithm.
 */
final class V2Runner {
    private V2Runner() {}

    /** Bytes hashed on demand per analysis to confirm exact copies before they are offered (the rest stay "hash pending"). */
    static final long HASH_BUDGET = 2000000000L;
    /**
     * -1 = the store's adaptive interval (at least 60 s, at most about 5 % of the time spent writing). A run is never resumed
     * (cfg.resume is false), so snapshots during it only cost time and flash writes: at 2.7 KB per file a fixed 30 s interval
     * rewrote about 100 MB every 30 s on a 40,000-file phone. The final flush is unaffected.
     */
    static final long SNAPSHOT_EVERY_MS = -1;

    /**
     * The app's store (PHASE_S_STORE 5.1, slice S6): DISK — records in getFilesDir()/v2 (app-private), only indexes and the
     * stages' working sets in the heap. MEMORY stays selectable here (and its own capacity guard applies).
     */
    static final Stores.Kind STORE_KIND = Stores.Kind.DISK;

    /** Capacity of this heap for the selected store (V2Capacity). */
    static long capacity(long usedBefore) {
        long max = Runtime.getRuntime().maxMemory();
        return STORE_KIND == Stores.Kind.DISK ? V2Capacity.maxFiles(max, usedBefore) : V2Capacity.maxFilesMemoryStore(max, usedBefore);
    }

    static AnalysisRunner.Outcome run(final Context app, double targetGb, boolean measure, final Scanner.Progress pr, final boolean sample) {
        AnalysisRunner.Outcome o = new AnalysisRunner.Outcome();
        ResultFiles.setRunning(app, true);
        try {
            try {
                runInner(app, targetGb, pr, o, false, sample);   // its frame (store, stages, records) is gone once it returns or throws
            } catch (Exception e) {
                // PHASE_S_STORE 3.11: damaged saved data ⇒ one automatic retry on a fresh store (event D_STORE_RESET)
                if (Stores.corruption(e) == null) throw e;
                o.ok = false; o.cancelled = false; o.message = null;
                try { runInner(app, targetGb, pr, o, true, sample); }
                catch (Exception e2) {
                    if (Stores.corruption(e2) == null) throw e2;
                    o.ok = false; o.cancelled = false; o.message = STORE_RESET_MESSAGE;
                }
            }
        } catch (OutOfMemoryError e) {
            o.ok = false; o.cancelled = false;
            o.message = V2Capacity.OUT_OF_MEMORY;
        } catch (Throwable t) {
            o.ok = false;
            boolean low = com.storageplanner.v2.store.StoreLowSpace.in(t) != null;
            o.message = low ? V2Capacity.LOW_STORAGE : "Analysis failed (v2): " + t;
            if (low) resetStore(app, sample);           // REVIEW_S6 R-02: give back the space the store took before it stopped
        } finally {
            ResultFiles.setRunning(app, false);
            AnalysisState.finished(o.message, o.ok);
        }
        return o;
    }

    static final String STORE_RESET_MESSAGE = "v2's saved data was damaged and has been reset; please run again.";

    private static void runInner(final Context app, double targetGb, final Scanner.Progress pr, AnalysisRunner.Outcome o, boolean fresh, boolean sample) throws Exception {
        // the v2 state on disk is about to change: until this run completes, a restore must not show it
        // (a sample run changes only its own store, so a full v2 result stays restorable until the sample replaces it, and vice versa)
        if ((sample ? AnalysisRunner.ALGO_V2_SAMPLE : AnalysisRunner.ALGO_V2).equals(AnalysisRunner.resultAlgorithm(app))) AnalysisRunner.setResultAlgorithm(app, null);
        pr.update("Reading installed apps", 0, 0);
        Runtime rt = Runtime.getRuntime();
        rt.gc();
        final long usedBefore = rt.totalMemory() - rt.freeMemory();   // heap in use before any v2 record is loaded (e.g. a result on screen)
        AndroidPlatform pf = new AndroidPlatform(app);
        AndroidV2Device dev;
        try { dev = device(app, pf, sample); } finally { pf.close(); }
        PipelineConfig cfg = new PipelineConfig();
        cfg.maxTier = 3;
        V2Store store;
        SpaceProbe space = space(dev);
        if (sample) store = Stores.create(STORE_KIND, dev.blobs(), SNAPSHOT_EVERY_MS, dev.clock(), space);   // always empty: the sample store is never reused
        else if (fresh) { store = Stores.create(STORE_KIND, dev.blobs(), SNAPSHOT_EVERY_MS, dev.clock(), space); cfg.storeReset = true; }
        else try { store = Stores.open(STORE_KIND, dev.blobs(), SNAPSHOT_EVERY_MS, dev.clock(), space); }
        catch (StoreException e) { store = Stores.create(STORE_KIND, dev.blobs(), SNAPSHOT_EVERY_MS, dev.clock(), space); cfg.storeReset = true; }
        // REVIEW_S6 R-01: the store is closed exactly once per run, on every path (writes the index file, releases the file
        // handles). runOpen closes it early (before publish, so its indexes can go) and clears the holder; else the finally does.
        final V2Store[] held = {store};
        store = null;
        try {
            runOpen(app, targetGb, pr, o, dev, cfg, held, usedBefore, sample);
        } finally {
            if (held[0] != null) { closeQuietly(held[0]); held[0] = null; }
        }
    }

    /** Closes a store, ignoring every failure (the result that matters was flushed; the next open validates what is on disk). */
    static void closeQuietly(V2Store s) {
        if (s == null) return;
        try { s.close(); } catch (Throwable ignored) { /* handles are released by DiskStore.close in any case */ }
    }

    /**
     * Empties the v2 store by truncation (DiskStore.create: fresh manifest, every segment and index file and an old store.snap
     * truncated to 0 bytes; nothing deleted or renamed), only inside getFilesDir()/v2. Used when the run stopped for lack of
     * storage or memory and after a Classic result replaced the v2 one (REVIEW_S6 R-02/R-03). Never throws.
     */
    static void resetStore(Context app) { resetStore(app, false); }

    /** sample: empties the quick test's own store (getFilesDir()/v2sample) instead; the full scan's store is never touched by it. */
    static void resetStore(Context app, boolean sample) {
        try {
            closeQuietly(Stores.create(STORE_KIND, sample ? AndroidV2Device.sampleBlobs(app) : AndroidV2Device.privateBlobs(app), SNAPSHOT_EVERY_MS, RESET_CLOCK));
        } catch (Throwable ignored) { /* the next v2 run validates the store again */ }
    }

    /** resetStore of the sample store only if a sample run left data behind; no-op otherwise (no directory is created). */
    static void resetSampleStoreIfPresent(Context app) {
        java.io.File d = AndroidV2Device.sampleStoreDir(app);
        if (new java.io.File(d, com.storageplanner.v2.store.DiskStore.MANIFEST).exists()
                || new java.io.File(d, com.storageplanner.v2.store.MemoryStore.SNAPSHOT).length() > 0) resetStore(app, true);
    }

    /** resetStore only if v2 left data behind (a disk-store manifest or an old memory-store snapshot); no-op otherwise. */
    static void resetStoreIfPresent(Context app) {
        java.io.File d = AndroidV2Device.storeDir(app);
        if (new java.io.File(d, com.storageplanner.v2.store.DiskStore.MANIFEST).exists()
                || new java.io.File(d, com.storageplanner.v2.store.MemoryStore.SNAPSHOT).length() > 0) resetStore(app);
    }

    private static final com.storageplanner.v2.device.Clock RESET_CLOCK = new com.storageplanner.v2.device.Clock() {
        public long nowMillis() { return System.currentTimeMillis(); }
        public long monotonicNanos() { return System.nanoTime(); }
    };

    private static void runOpen(final Context app, double targetGb, final Scanner.Progress pr, AnalysisRunner.Outcome o,
                                AndroidV2Device dev, PipelineConfig cfg, final V2Store[] held, final long usedBefore, final boolean sample) throws Exception {
        V2Store store = held[0];
        List<Stage> stages = Pipeline.defaultStages(cfg, dev);
        if (sample) SampleStage.insertAfterDiscovery(stages, SampleSpec.DEFAULT);   // only the sample goes on to extraction, graph, assessment, plan
        stages.add(new com.storageplanner.v2.graph.GraphStage());
        // memory guard (REVIEW_A M-1): right after discovery, before any extraction, the record count must fit the heap
        final long[] guard = {-1, -1, 0};       // {files found, limit, 1 = disk} when the guard stopped the run
        for (int i = 0; i < stages.size(); i++) {
            if ("T1_METADATA".equals(stages.get(i).id())) { stages.set(i, new CapacityGuard(stages.get(i), usedBefore, guard)); break; }
        }
        // REVIEW_S F-17: the guard also watches the record count while DISCOVER runs (every 10,000 records), so a library far
        // above the limit stops before discovery itself fills the heap
        final V2Store guardStore = store;
        final long discoverLimit = capacity(usedBefore);
        final long[] nextCheck = {10000};
        ScanControl control = new ScanControl() {
            public boolean cancelRequested() { return AnalysisState.cancelled || guard[0] >= 0; }
            public boolean pauseRequested() { return false; }
            public void progress(String stage, long done, long total) {
                if ("DISCOVER".equals(stage) && guard[0] < 0) {
                    long n = guardStore.files().size();
                    if (n >= nextCheck[0]) {
                        if (sample) {
                            // keep only the newest sample candidates so far (what is dropped here cannot be in the final sample)
                            try { SampleStage.prune(guardStore, SampleSpec.DEFAULT); } catch (StoreException ignored) { /* the SAMPLE stage prunes again */ }
                            n = guardStore.files().size();
                            nextCheck[0] = n + 10000;
                        } else nextCheck[0] = (n / 10000 + 1) * 10000;
                        if (n > discoverLimit) { guard[0] = n; guard[1] = discoverLimit; }
                    }
                }
                pr.update(stageLabel(stage), (int) Math.min(Integer.MAX_VALUE, Math.max(0, done)), (int) Math.min(Integer.MAX_VALUE, Math.max(0, total)));
            }
        };
        PipelineResult res = new Pipeline(dev, store, cfg, stages).run(control);
        stages.clear();                         // GRAPH keeps its signature cache (~50 MB) and node copies: let them go now
        if (guard[0] >= 0) {
            o.message = guard[2] == 1 ? V2Capacity.notEnoughStorageMessage(guard[0]) : V2Capacity.tooManyMessage(guard[0], guard[1]);
            closeQuietly(store); held[0] = null;
            resetStore(app, sample);            // REVIEW_S6 R-02: the same library stops again; do not keep its records on disk
            return;
        }
        if (AnalysisState.cancelled || res.outcome != StageOutcome.DONE) {
            o.cancelled = AnalysisState.cancelled || res.outcome == StageOutcome.CANCELLED;
            o.message = o.cancelled ? "Cancelled." : "Analysis stopped early (" + res.outcome + ").";
            return;
        }
        long scanId = res.session.scanId;
        res = null;
        long target = (long) (targetGb * 1e9);
        Built b = analyse(app, dev, store, scanId, pr, sample);
        closeQuietly(store); held[0] = null;    // R-01: index file written, handles released, before the result is published
        store = null; dev = null;               // only the adapted result is kept from here on
        if (b == null) { o.cancelled = true; o.message = "Cancelled."; return; }
        prefs(app).edit().putLong("v2_target", target).putInt("v2_mode", Session.mode).commit();
        AnalysisRunner.setResultAlgorithm(app, sample ? AnalysisRunner.ALGO_V2_SAMPLE : AnalysisRunner.ALGO_V2);
        final long[] saved = {0};
        if (!publish(app, b, target, Session.mode, saved)) {
            o.message = "Analysis finished (v2), but the result could not be shown. Open the app again to load it.";
            return;
        }
        o.ok = true;
        o.saved = saved[0];
        o.sample = sample;
        o.message = sample ? "Done (v2 sample): " + b.adapted.result.files.size() + " files analysed. " + b.sampleNote
                : "Done (v2): " + b.adapted.result.files.size() + " files and " + b.adapted.result.apps.size() + " apps analysed.";
        if (!sample) resetSampleStoreIfPresent(app);     // the quick test's result is replaced by this full one: give its data back
        if (b.continueNote != null) o.message += " " + b.continueNote;    // PHASE_S_STORE S7: decode budget used up, run again
    }

    /**
     * Wraps T1 (the first extraction stage, same id so the stored session is unchanged): before it reads any file it compares the
     * number of file records in the store with V2Capacity and cancels the run when they would not fit the heap.
     */
    static final class CapacityGuard implements Stage {
        private final Stage inner; private final long usedBefore; private final long[] out;
        CapacityGuard(Stage inner, long usedBefore, long[] out) { this.inner = inner; this.usedBefore = usedBefore; this.out = out; }
        public String id() { return inner.id(); }
        public StageOutcome run(StageContext ctx) throws StoreException {
            long files = ctx.store.files().size();          // every record in memory, including ones kept from earlier scans
            long limit = capacity(usedBefore);
            if (files > limit) { out[0] = files; out[1] = limit; return StageOutcome.CANCELLED; }
            if (STORE_KIND == Stores.Kind.DISK) {        // PHASE_S_STORE 3.12: disk preflight with the heap guard
                long free = ctx.device.resources().sample().storageFreeBytes;
                if (!V2Capacity.diskFits(files, free)) { out[0] = files; out[1] = free; out[2] = 1; return StageOutcome.CANCELLED; }
            }
            return inner.run(ctx);
        }
    }

    /**
     * After process death: reload the v2 store (no rescan), assess and plan again, adapt and put into Session.
     * Worker thread. Returns false if there is no complete v2 result (then nothing is shown, never a classic result instead).
     */
    static boolean restore(Context app, boolean sample) {
        try {
            AndroidPlatform pf = new AndroidPlatform(app);
            AndroidV2Device dev;
            try { dev = device(app, pf, sample); } finally { pf.close(); }
            V2Store store = Stores.open(STORE_KIND, dev.blobs(), SNAPSHOT_EVERY_MS, dev.clock(), space(dev));
            Built b;
            try {
                ScanSession last = null;
                for (ScanSession s : store.scanSessions().all()) if (last == null || s.scanId > last.scanId) last = s;
                if (last == null || last.state != ScanState.COMPLETED) return false;
                b = analyse(app, dev, store, last.scanId, null, sample);
            } finally {
                closeQuietly(store);            // R-01: once, on every path
            }
            store = null;
            if (b == null) return false;
            SharedPreferences p = prefs(app);
            int mode = p.getInt("v2_mode", Session.MODE_TARGET) == Session.MODE_SAFE ? Session.MODE_SAFE : Session.MODE_TARGET;
            return publish(app, b, p.getLong("v2_target", 0), mode, new long[1]);
        } catch (Throwable t) {
            return false;
        }
    }

    private static final class Built { V2Adapter.Adapted adapted; int sdk; boolean appStats; String continueNote, sampleNote; }

    /** Free bytes of the shared root (same /data partition as getFilesDir()), for the DiskStore reserve checks (3.12). */
    private static SpaceProbe space(final AndroidV2Device dev) {
        return new SpaceProbe() {
            public long freeBytes() {
                com.storageplanner.v2.device.ResourceState s = dev.resources().sample();
                return s == null ? -1 : s.storageFreeBytes;
            }
        };
    }

    private static AndroidV2Device device(Context app, AndroidPlatform pf, boolean sample) {
        List<AppItem> classic = pf.apps();
        boolean usage = AndroidPlatform.hasUsageAccess(app);
        List<InstalledApp> apps = AndroidV2Device.fromClassic(classic, pf.appStatsAvailable, usage);
        Availability av = AndroidV2Device.appAvailability(app);
        AndroidV2Device d = new AndroidV2Device(app, pf.storageRoot(), apps, av, sample);
        lastStats = pf.appStatsAvailable; lastSdk = pf.sdkInt(); lastTotal = pf.totalBytes(); lastFree = pf.freeBytes(); lastRoot = pf.storageRoot();
        return d;
    }
    private static volatile boolean lastStats; private static volatile int lastSdk; private static volatile long lastTotal, lastFree;
    private static volatile String lastRoot;

    /** ASSESS + PLAN (Plans.make: SAFE + NO_LOSS only, A29) + adapter, via V2Analysis (PHASE_S_STORE 4 item 6). null if cancelled. */
    private static Built analyse(Context app, final AndroidV2Device dev, V2Store store, long scanId, Scanner.Progress pr, boolean sample) throws StoreException {
        V2Analysis.Params p = new V2Analysis.Params();
        p.apps = dev.apps(); p.now = dev.clock().nowMillis(); p.scanId = scanId;
        p.sdk = lastSdk; p.root = lastRoot; p.total = lastTotal; p.free = lastFree;
        p.hasher = new HashOnDemand.Hasher() {
            public String sha256(FileRecord f) { return AnalysisState.cancelled ? null : sha256Of(dev.storage().localPath(f.volumeId, f.path)); }
        };
        p.hashBudget = HASH_BUDGET;
        p.progress = pr;
        if (sample) p.sample = SampleSpec.DEFAULT;      // no app recommendations, a sample notice on the result
        p.cancel = new V2Analysis.Cancel() { public boolean cancelled() { return AnalysisState.cancelled; } };
        V2Analysis.Result r = V2Analysis.run(store, p);
        if (r == null) return null;
        Built b = new Built();
        b.sdk = lastSdk; b.appStats = lastStats;
        b.adapted = r.adapted;
        b.continueNote = r.continueNote();
        b.sampleNote = r.sampleNote;
        return b;
    }

    /** Puts the adapted result into Session on the main thread and waits (like AnalysisRunner). */
    private static boolean publish(final Context app, final Built b, final long target, final int mode, final long[] saved) throws InterruptedException {
        final CountDownLatch set = new CountDownLatch(1);
        final boolean[] ok = {false};
        AnalysisState.UI.post(new Runnable() {
            public void run() {
                try {
                    Session.loadKept(app);
                    Session.mode = mode;
                    Session.setV2(b.adapted.result, b.adapted.ub, new Estimates(), target, b.appStats, b.sdk, b.adapted.notPlannable);
                    Session.v2Note = b.continueNote;
                    Session.sampleNote = b.sampleNote;       // non-null = a sample result: every plan screen says so
                    saved[0] = Session.plan.saved();
                    ok[0] = true;
                } catch (RuntimeException e) {
                    ok[0] = false;
                } finally { set.countDown(); }
            }
        });
        set.await(60, TimeUnit.SECONDS);
        return ok[0];
    }

    static String sha256Of(String path) {
        if (path == null) return null;
        InputStream in = null;
        try {
            in = new FileInputStream(path);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[1 << 16]; int n;
            while ((n = in.read(buf)) > 0) { md.update(buf, 0, n); if (AnalysisState.cancelled) return null; }
            return Hex.of(md.digest());
        } catch (Exception e) {
            return null;
        } finally {
            if (in != null) try { in.close(); } catch (Exception ignored) { /* nothing */ }
        }
    }

    static String stageLabel(String stage) {
        if ("DISCOVER".equals(stage)) return "Finding files (v2)";
        if ("T1_METADATA".equals(stage)) return "Reading file details (v2)";
        if ("T2_HASH_STRUCT".equals(stage)) return "Comparing contents (v2)";
        if ("T3_IMAGE".equals(stage)) return "Looking at pictures (v2)";
        if ("GRAPH".equals(stage)) return "Finding copies (v2)";
        return "Analysing (v2)";
    }

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("analysis", Context.MODE_PRIVATE); }
}
