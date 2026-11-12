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
import com.storageplanner.v2.model.ActionPlan;
import com.storageplanner.v2.model.FileRecord;
import com.storageplanner.v2.model.Recommendation;
import com.storageplanner.v2.model.ScanSession;
import com.storageplanner.v2.model.ScanState;
import com.storageplanner.v2.model.Verdict;
import com.storageplanner.v2.pipeline.Pipeline;
import com.storageplanner.v2.pipeline.PipelineResult;
import com.storageplanner.v2.plan.AppSubjects;
import com.storageplanner.v2.plan.HashOnDemand;
import com.storageplanner.v2.plan.Plans;
import com.storageplanner.v2.plan.PresentationPolicy;
import com.storageplanner.v2.plan.ProvisionalRecommendations;
import com.storageplanner.v2.plan.UnitBuilder2;
import com.storageplanner.v2.risk.Assessor;
import com.storageplanner.v2.risk.RiskConfig;
import com.storageplanner.v2.stage.PipelineConfig;
import com.storageplanner.v2.stage.Stage;
import com.storageplanner.v2.stage.StageContext;
import com.storageplanner.v2.stage.StageOutcome;
import com.storageplanner.v2.store.Stores;
import com.storageplanner.v2.store.StoreException;
import com.storageplanner.v2.store.V2Store;
import com.storageplanner.v2.util.Hex;
import com.storageplanner.v2adapter.V2Adapter;
import com.storageplanner.v2adapter.V2Capacity;

import java.io.FileInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    /** The app's store (PHASE_S_STORE 5.1): MEMORY until slice S6 switches to DISK; both stay selectable. */
    static final Stores.Kind STORE_KIND = Stores.Kind.MEMORY;

    static AnalysisRunner.Outcome run(final Context app, double targetGb, boolean measure, final Scanner.Progress pr) {
        AnalysisRunner.Outcome o = new AnalysisRunner.Outcome();
        ResultFiles.setRunning(app, true);
        try {
            try {
                runInner(app, targetGb, pr, o, false);   // its frame (store, stages, records) is gone once it returns or throws
            } catch (Exception e) {
                // PHASE_S_STORE 3.11: damaged saved data ⇒ one automatic retry on a fresh store (event D_STORE_RESET)
                if (Stores.corruption(e) == null) throw e;
                o.ok = false; o.cancelled = false; o.message = null;
                try { runInner(app, targetGb, pr, o, true); }
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
            o.message = "Analysis failed (v2): " + t;
        } finally {
            ResultFiles.setRunning(app, false);
            AnalysisState.finished(o.message, o.ok);
        }
        return o;
    }

    static final String STORE_RESET_MESSAGE = "v2's saved data was damaged and has been reset; please run again.";

    private static void runInner(final Context app, double targetGb, final Scanner.Progress pr, AnalysisRunner.Outcome o, boolean fresh) throws Exception {
        // the v2 state on disk is about to change: until this run completes, a restore must not show it
        if (AnalysisRunner.ALGO_V2.equals(AnalysisRunner.resultAlgorithm(app))) AnalysisRunner.setResultAlgorithm(app, null);
        pr.update("Reading installed apps", 0, 0);
        Runtime rt = Runtime.getRuntime();
        rt.gc();
        final long usedBefore = rt.totalMemory() - rt.freeMemory();   // heap in use before any v2 record is loaded (e.g. a result on screen)
        AndroidPlatform pf = new AndroidPlatform(app);
        AndroidV2Device dev;
        try { dev = device(app, pf); } finally { pf.close(); }
        PipelineConfig cfg = new PipelineConfig();
        cfg.maxTier = 3;
        V2Store store;
        if (fresh) { store = Stores.create(STORE_KIND, dev.blobs(), SNAPSHOT_EVERY_MS, dev.clock()); cfg.storeReset = true; }
        else try { store = Stores.open(STORE_KIND, dev.blobs(), SNAPSHOT_EVERY_MS, dev.clock()); }
        catch (StoreException e) { store = Stores.create(STORE_KIND, dev.blobs(), SNAPSHOT_EVERY_MS, dev.clock()); cfg.storeReset = true; }
        List<Stage> stages = Pipeline.defaultStages(cfg, dev);
        stages.add(new com.storageplanner.v2.graph.GraphStage());
        // memory guard (REVIEW_A M-1): right after discovery, before any extraction, the record count must fit the heap
        final long[] guard = {-1, -1};          // {files found, limit} when the guard stopped the run
        for (int i = 0; i < stages.size(); i++) {
            if ("T1_METADATA".equals(stages.get(i).id())) { stages.set(i, new CapacityGuard(stages.get(i), usedBefore, guard)); break; }
        }
        ScanControl control = new ScanControl() {
            public boolean cancelRequested() { return AnalysisState.cancelled; }
            public boolean pauseRequested() { return false; }
            public void progress(String stage, long done, long total) {
                pr.update(stageLabel(stage), (int) Math.min(Integer.MAX_VALUE, Math.max(0, done)), (int) Math.min(Integer.MAX_VALUE, Math.max(0, total)));
            }
        };
        PipelineResult res = new Pipeline(dev, store, cfg, stages).run(control);
        stages.clear();                         // GRAPH keeps its signature cache (~50 MB) and node copies: let them go now
        if (guard[0] >= 0) {
            o.message = V2Capacity.tooManyMessage(guard[0], guard[1]);
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
        Built b = analyse(app, dev, store, scanId, pr);
        store = null; dev = null;               // only the adapted result is kept from here on
        if (b == null) { o.cancelled = true; o.message = "Cancelled."; return; }
        prefs(app).edit().putLong("v2_target", target).putInt("v2_mode", Session.mode).commit();
        AnalysisRunner.setResultAlgorithm(app, AnalysisRunner.ALGO_V2);
        final long[] saved = {0};
        if (!publish(app, b, target, Session.mode, saved)) {
            o.message = "Analysis finished (v2), but the result could not be shown. Open the app again to load it.";
            return;
        }
        o.ok = true;
        o.saved = saved[0];
        o.message = "Done (v2): " + b.adapted.result.files.size() + " files and " + b.adapted.result.apps.size() + " apps analysed.";
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
            long limit = V2Capacity.maxFiles(Runtime.getRuntime().maxMemory(), usedBefore);
            if (files > limit) { out[0] = files; out[1] = limit; return StageOutcome.CANCELLED; }
            return inner.run(ctx);
        }
    }

    /**
     * After process death: reload the v2 store (no rescan), assess and plan again, adapt and put into Session.
     * Worker thread. Returns false if there is no complete v2 result (then nothing is shown, never a classic result instead).
     */
    static boolean restore(Context app) {
        try {
            AndroidPlatform pf = new AndroidPlatform(app);
            AndroidV2Device dev;
            try { dev = device(app, pf); } finally { pf.close(); }
            V2Store store = Stores.open(STORE_KIND, dev.blobs(), SNAPSHOT_EVERY_MS, dev.clock());
            ScanSession last = null;
            for (ScanSession s : store.scanSessions().all()) if (last == null || s.scanId > last.scanId) last = s;
            if (last == null || last.state != ScanState.COMPLETED) return false;
            Built b = analyse(app, dev, store, last.scanId, null);
            if (b == null) return false;
            SharedPreferences p = prefs(app);
            int mode = p.getInt("v2_mode", Session.MODE_TARGET) == Session.MODE_SAFE ? Session.MODE_SAFE : Session.MODE_TARGET;
            return publish(app, b, p.getLong("v2_target", 0), mode, new long[1]);
        } catch (Throwable t) {
            return false;
        }
    }

    private static final class Built { V2Adapter.Adapted adapted; int sdk; boolean appStats; }

    private static AndroidV2Device device(Context app, AndroidPlatform pf) {
        List<AppItem> classic = pf.apps();
        boolean usage = AndroidPlatform.hasUsageAccess(app);
        List<InstalledApp> apps = AndroidV2Device.fromClassic(classic, pf.appStatsAvailable, usage);
        Availability av = AndroidV2Device.appAvailability(app);
        AndroidV2Device d = new AndroidV2Device(app, pf.storageRoot(), apps, av);
        lastStats = pf.appStatsAvailable; lastSdk = pf.sdkInt(); lastTotal = pf.totalBytes(); lastFree = pf.freeBytes(); lastRoot = pf.storageRoot();
        return d;
    }
    private static volatile boolean lastStats; private static volatile int lastSdk; private static volatile long lastTotal, lastFree;
    private static volatile String lastRoot;

    /** ASSESS + PLAN (Plans.make: SAFE + NO_LOSS only, A29) + adapter. null if cancelled. */
    private static Built analyse(Context app, final AndroidV2Device dev, V2Store store, long scanId, Scanner.Progress pr) throws StoreException {
        if (pr != null) pr.update("Assessing", 0, 0);
        long now = dev.clock().nowMillis();
        List<Verdict> vs = Assessor.run(store, dev.apps(), now, PresentationPolicy.STANDARD, new RiskConfig());
        if (pr != null && AnalysisState.cancelled) return null;
        Map<String, FileRecord> fm = new HashMap<String, FileRecord>();
        for (FileRecord f : store.files().all()) fm.put(f.fileId, f);
        List<Recommendation> recs = ProvisionalRecommendations.of(vs, fm, scanId);
        vs = null;                              // the verdicts (about 2.5 KB per file) are not needed after this: free them before hashing
        if (pr != null) pr.update("Confirming identical copies", 0, 0);
        HashOnDemand.run(recs, fm, new HashOnDemand.Hasher() {
            public String sha256(FileRecord f) { return AnalysisState.cancelled ? null : sha256Of(dev.storage().localPath(f.volumeId, f.path)); }
        }, HASH_BUDGET);
        if (pr != null && AnalysisState.cancelled) return null;
        recs.addAll(AppSubjects.recs(dev.apps(), now, lastSdk, scanId, ProvisionalRecommendations.PLAN_VERSION));
        if (pr != null) pr.update("Planning", 0, 0);
        UnitBuilder2.Input in = new UnitBuilder2.Input();
        in.recs = recs; in.files = fm; in.keepers = Plans.keepers(store);
        Plans.Made m = Plans.make(in, ActionPlan.MAX_SAFE, null, false);
        Plans.version(store, m.plan, now, scanId);
        store.flush();
        Built b = new Built();
        b.sdk = lastSdk; b.appStats = lastStats;
        b.adapted = V2Adapter.adapt(in, m.built, dev.apps().list(), V2Adapter.duplicateOf(store.duplicateGroups().all()), lastRoot,
                lastSdk, now, lastTotal, lastFree, false);
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
