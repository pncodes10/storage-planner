package com.storageplanner.v2adapter;

import com.storageplanner.core.Scanner;
import com.storageplanner.v2.device.InstalledApps;
import com.storageplanner.v2.model.ActionPlan;
import com.storageplanner.v2.model.FileRecord;
import com.storageplanner.v2.model.Recommendation;
import com.storageplanner.v2.model.ScanSession;
import com.storageplanner.v2.model.Verdict;
import com.storageplanner.v2.pipeline.Pipeline;
import com.storageplanner.v2.plan.AppSubjects;
import com.storageplanner.v2.plan.HashOnDemand;
import com.storageplanner.v2.plan.Plans;
import com.storageplanner.v2.plan.PresentationPolicy;
import com.storageplanner.v2.plan.ProvisionalRecommendations;
import com.storageplanner.v2.plan.StoreFileMap;
import com.storageplanner.v2.plan.UnitBuilder2;
import com.storageplanner.v2.risk.Assessor;
import com.storageplanner.v2.risk.RiskConfig;
import com.storageplanner.v2.store.StoreException;
import com.storageplanner.v2.store.V2Store;

import java.util.ArrayList;
import java.util.List;

/**
 * The app's analysis after the pipeline (PHASE_S_STORE 4 item 6): ASSESS (streamed: each verdict becomes its recommendations at
 * once and is dropped) → hash on demand → app subjects → PLAN ({@code Plans.make}: SAFE + NO_LOSS only, A29) → version the plan,
 * count damage (F-07), flush → adapt to the classic result. PLAN reads files through {@link StoreFileMap}. Android-free, so the
 * JVM scale harness runs exactly the app's code path; {@code V2Runner} keeps only the Android wiring.
 */
public final class V2Analysis {
    private V2Analysis() {}

    /** Optional hooks: heap marks (scale harness) after each phase. */
    public interface Marks { void mark(String phase); }
    /** Cancellation as V2Runner checks it (AnalysisState.cancelled). */
    public interface Cancel { boolean cancelled(); }

    public static final class Params {
        public InstalledApps apps;
        public long now, scanId;
        public int sdk;
        public String root;
        public long total, free;
        public HashOnDemand.Hasher hasher;
        public long hashBudget;
        /** null: no progress and no cancellation check (restore after process death), as V2Runner did. */
        public Scanner.Progress progress;
        public Cancel cancel;
        public boolean adapt = true;
        public Marks marks;
    }

    public static final class Result {
        public UnitBuilder2.Input in;
        public Plans.Made made;
        public V2Adapter.Adapted adapted;     // null when Params.adapt is false
    }

    /** null if cancelled (only when a progress callback is given). */
    public static Result run(V2Store store, Params p) throws StoreException {
        final Scanner.Progress pr = p.progress;
        if (pr != null) pr.update("Assessing", 0, 0);
        final List<Recommendation> recs = new ArrayList<Recommendation>();
        final long scanId = p.scanId;
        final Marks mk = p.marks;
        final boolean[] first = {true};
        Assessor.run(store, p.apps, p.now, PresentationPolicy.STANDARD, new RiskConfig(), new Assessor.VerdictSink() {
            public void accept(Verdict v, FileRecord f) {
                if (first[0] && mk != null) { first[0] = false; mk.mark("in_ASSESS"); }
                ProvisionalRecommendations.ofOne(v, f, scanId, recs);
            }
        });
        if (pr != null && p.cancel != null && p.cancel.cancelled()) return null;
        if (mk != null) mk.mark("assess_recs");
        StoreFileMap fm = new StoreFileMap(store);
        if (pr != null) pr.update("Confirming identical copies", 0, 0);
        HashOnDemand.run(recs, fm, p.hasher, p.hashBudget);
        if (pr != null && p.cancel != null && p.cancel.cancelled()) return null;
        recs.addAll(AppSubjects.recs(p.apps, p.now, p.sdk, scanId, ProvisionalRecommendations.PLAN_VERSION));
        if (pr != null) pr.update("Planning", 0, 0);
        Result r = new Result();
        UnitBuilder2.Input in = new UnitBuilder2.Input();
        in.recs = recs; in.files = fm; in.keepers = Plans.keepers(store);
        r.in = in;
        r.made = Plans.make(in, ActionPlan.MAX_SAFE, null, false);
        Plans.version(store, r.made.plan, p.now, scanId);
        ScanSession ss = store.scanSessions().get(ScanSession.keyOf(scanId));   // F-07: damage found by ASSESS/PLAN is counted too
        if (ss != null && Pipeline.noteDamaged(store, ss, p.now)) store.scanSessions().put(ss);
        store.flush();
        if (mk != null) mk.mark("plan_made");
        if (p.adapt) {
            r.adapted = V2Adapter.adapt(in, r.made.built, p.apps.list(), V2Adapter.duplicateOf(store.duplicateGroups().all()), p.root,
                    p.sdk, p.now, p.total, p.free, false);
            if (mk != null) mk.mark("adapted");
        }
        return r;
    }
}
