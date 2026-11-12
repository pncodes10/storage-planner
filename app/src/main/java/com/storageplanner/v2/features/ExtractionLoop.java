package com.storageplanner.v2.features;

import com.storageplanner.v2.device.Clock;
import com.storageplanner.v2.model.FileRecord;
import com.storageplanner.v2.model.Presence;
import com.storageplanner.v2.stage.StageContext;
import com.storageplanner.v2.stage.StageOutcome;
import com.storageplanner.v2.stage.Throttle;
import com.storageplanner.v2.store.StoreException;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Single-threaded job loop of the extraction stages (A11: workers = 0 until Phase 10). Commits every 500 jobs or 3 s
 * (CommitPolicy); checks cancel, pause, throttle and budget before each job. Unprocessed jobs keep a pending bit.
 */
public final class ExtractionLoop {
    private ExtractionLoop() {}
    public static final int COMMIT_EVERY = 500;
    public static final long COMMIT_EVERY_MS = 3000;

    public interface Job { void run(FileRecord f) throws StoreException; }

    /** PRESENT files of the scanned volumes, ascending fileId. */
    public static List<FileRecord> present(StageContext ctx) {
        Set<String> vols = ctx.config.volumeIds;
        List<FileRecord> out = new ArrayList<FileRecord>();
        for (FileRecord f : ctx.store.files().all())
            if (f.presence == Presence.PRESENT && (vols == null || vols.contains(f.volumeId))) out.add(f);
        return out;
    }

    public static StageOutcome run(StageContext ctx, String stageId, List<FileRecord> jobs, int pendingBit, Job job) throws StoreException {
        Clock clock = ctx.device.clock();
        long lastCommit = clock.monotonicNanos();
        int sinceCommit = 0;
        for (int i = 0; i < jobs.size(); i++) {
            StageOutcome stop = stopReason(ctx);
            if (stop != null) {
                for (int k = i; k < jobs.size(); k++) {
                    FileRecord f = ctx.store.files().get(jobs.get(k).fileId);
                    if (f != null && pendingBit != 0 && (f.tiersPending & pendingBit) == 0) { Tiers.pending(f, pendingBit); Confidence.apply(f, ctx.store.features().get(f.fileId)); ctx.store.files().put(f); }
                }
                if (stop == StageOutcome.BUDGET_EXHAUSTED) ctx.session.count("budget.pendingFiles", jobs.size() - i);
                ctx.store.scanSessions().put(ctx.session);
                ctx.store.commit();
                return stop;
            }
            job.run(jobs.get(i));
            sinceCommit++;
            if (ctx.control != null && (i & 63) == 0) ctx.control.progress(stageId, i, jobs.size());
            if (sinceCommit >= COMMIT_EVERY || (clock.monotonicNanos() - lastCommit) / 1000000L >= COMMIT_EVERY_MS) {
                ctx.store.scanSessions().put(ctx.session);
                ctx.store.commit();
                sinceCommit = 0; lastCommit = clock.monotonicNanos();
            }
        }
        ctx.store.scanSessions().put(ctx.session);
        ctx.store.commit();
        return StageOutcome.DONE;
    }

    static StageOutcome stopReason(StageContext ctx) {
        if (ctx.control != null && ctx.control.cancelRequested()) return StageOutcome.CANCELLED;
        if (ctx.control != null && ctx.control.pauseRequested()) return StageOutcome.PAUSED;
        if (ctx.throttle != null && ctx.throttle.shouldPause()) { ctx.session.pauseReason = Throttle.PAUSE_REASON; return StageOutcome.PAUSED; }
        if (ctx.budget != null && ctx.budget.exhausted(ctx.device.clock())) {
            ctx.session.pauseReason = "PL_BUDGET";
            ctx.session.counters.put("budget.stoppedBy." + ctx.budget.stoppedBy(ctx.device.clock()), 1L);
            return StageOutcome.BUDGET_EXHAUSTED;
        }
        return null;
    }
}
