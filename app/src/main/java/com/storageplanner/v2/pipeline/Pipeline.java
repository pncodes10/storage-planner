package com.storageplanner.v2.pipeline;

import com.storageplanner.v2.V2;
import com.storageplanner.v2.device.Clock;
import com.storageplanner.v2.device.ScanControl;
import com.storageplanner.v2.device.V2Device;
import com.storageplanner.v2.discovery.CoverageBuilder;
import com.storageplanner.v2.discovery.DiscoveryEngine;
import com.storageplanner.v2.model.ScanSession;
import com.storageplanner.v2.model.ScanState;
import com.storageplanner.v2.model.Unknown;
import com.storageplanner.v2.stage.*;
import com.storageplanner.v2.store.StoreException;
import com.storageplanner.v2.store.V2Store;
import com.storageplanner.v2.util.Text;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Orchestrator (ARCHITECTURE 5, PHASE_1 7). Errors (OutOfMemoryError, test kills) are never caught: they act as process death. */
public final class Pipeline {
    private final V2Device device;
    private final V2Store store;
    private final PipelineConfig config;
    private final List<Stage> stages;

    public Pipeline(V2Device device, V2Store store, PipelineConfig config, List<Stage> stages) {
        this.device = device; this.store = store; this.config = config == null ? new PipelineConfig() : config;
        this.stages = stages == null ? defaultStages(this.config, device) : new ArrayList<Stage>(stages);
    }

    /** Phase-1 default stage list (discovery only). */
    public static List<Stage> defaultStages() { return new ArrayList<Stage>(Collections.<Stage>singletonList(new DiscoveryEngine())); }

    /** [DISCOVER, T1, T2, T3] filtered by config.maxTier; the installed set goes to discovery only when availability is FULL. */
    public static List<Stage> defaultStages(PipelineConfig config, V2Device device) {
        List<Stage> l = new ArrayList<Stage>();
        java.util.Set<String> installed = null;
        if (device != null && device.apps() != null && device.apps().availability() == com.storageplanner.v2.device.Availability.FULL) {
            installed = new java.util.TreeSet<String>();
            for (com.storageplanner.v2.device.InstalledApp a : device.apps().list()) installed.add(a.packageName);
        }
        l.add(new DiscoveryEngine(new com.storageplanner.v2.discovery.WalkLimits(), installed));
        if (config.maxTier >= 1) l.add(new com.storageplanner.v2.features.T1MetadataStage());
        if (config.maxTier >= 2) l.add(new com.storageplanner.v2.features.T2HashStructStage());
        if (config.maxTier >= 3) l.add(new com.storageplanner.v2.features.T3ImageStage());
        return l;
    }

    static boolean open(ScanState s) { return s == ScanState.RUNNING || s == ScanState.PAUSED || s == ScanState.INTERRUPTED; }

    public PipelineResult run(ScanControl control) throws StoreException {
        Clock clock = device.clock();
        ScanSession session = null;
        ScanSession latest = null;
        long maxId = 0;
        for (ScanSession s : store.scanSessions().all()) { latest = s; maxId = Math.max(maxId, s.scanId); }
        long now = clock.nowMillis();
        if (config.resume && latest != null && open(latest.state)) {
            session = latest;
            if (session.state == ScanState.RUNNING) { session.state = ScanState.INTERRUPTED; session.event(now, "D_INTERRUPTED", null, null, null); }
            session.state = ScanState.RUNNING;
            session.resumedCount++;
            session.pauseReason = null;
            session.event(now, "D_RESUMED", null, null, null);
            store.scanSessions().put(session);
            store.commit();
        }
        if (session == null) {
            for (ScanSession s : store.scanSessions().all()) {
                if (!open(s.state)) continue;
                s.state = ScanState.CANCELLED;
                if (!Unknown.knownTime(s.endedAt)) s.endedAt = now;
                s.event(now, "D_SUPERSEDED", null, null, null);
                store.scanSessions().put(s);
            }
            session = new ScanSession();
            session.scanId = maxId + 1;
            session.kind = config.kind;
            session.state = ScanState.RUNNING;
            session.startedAt = now;
            session.algorithmVersion = V2.ALGORITHM_VERSION;
            session.scanVersion = V2.SCAN_VERSION;
            if (config.storeReset) session.event(now, "D_STORE_RESET", null, null, null);
            store.scanSessions().put(session);
            store.commit();
        }
        StageContext ctx = new StageContext();
        ctx.store = store; ctx.device = device; ctx.config = config; ctx.session = session; ctx.control = control;
        ctx.budget = config.budget == null ? new Budget() : config.budget;
        ctx.budget.start(clock);
        ctx.throttle = new Throttle(device.resources(), clock);
        PipelineResult result = new PipelineResult();
        result.session = session;
        for (Stage stage : stages) {
            if (session.stagesDone.contains(stage.id())) continue;
            session.stage = stage.id();
            store.scanSessions().put(session);
            store.commit();
            StageOutcome o;
            try {
                o = stage.run(ctx);
            } catch (StoreException e) {
                fail(session.scanId, e);
                throw e;
            } catch (RuntimeException e) {
                fail(session.scanId, e);
                throw new StoreException("stage " + stage.id() + " failed: " + e, e);
            }
            if (o == StageOutcome.PAUSED || o == StageOutcome.BUDGET_EXHAUSTED || o == StageOutcome.CANCELLED) {
                session.state = o == StageOutcome.CANCELLED ? ScanState.CANCELLED : ScanState.PAUSED;
                if (o == StageOutcome.BUDGET_EXHAUSTED && session.pauseReason == null) session.pauseReason = "PL_BUDGET";
                if (o == StageOutcome.CANCELLED) session.endedAt = clock.nowMillis();
                store.scanSessions().put(session);
                store.flush();
                result.outcome = o;
                result.coverage = CoverageBuilder.build(store, session.scanId, 1000);
                return result;
            }
            if (o == StageOutcome.FAILED) {
                StoreException e = new StoreException("stage " + stage.id() + " reported FAILED");
                fail(session.scanId, e);
                throw e;
            }
            if (!session.stagesDone.contains(stage.id())) session.stagesDone.add(stage.id());
        }
        session.state = ScanState.COMPLETED;
        session.endedAt = clock.nowMillis();
        session.stage = null;
        store.scanSessions().put(session);
        store.flush();
        result.outcome = StageOutcome.DONE;
        result.coverage = CoverageBuilder.build(store, session.scanId, 1000);
        return result;
    }

    /** Uncommitted work is dropped; the committed session is marked FAILED and made durable. */
    private void fail(long scanId, Exception e) throws StoreException {
        store.rollback();
        ScanSession s = store.scanSessions().get(ScanSession.keyOf(scanId));
        if (s == null) return;
        s.state = ScanState.FAILED;
        s.lastError = Text.truncate(e.getClass().getSimpleName() + ": " + e.getMessage(), 200);
        s.endedAt = device.clock().nowMillis();
        store.scanSessions().put(s);
        store.flush();
    }
}
