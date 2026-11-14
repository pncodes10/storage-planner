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
import com.storageplanner.v2.sample.SampleSpec;
import com.storageplanner.v2.store.DiskStore;
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
        /**
         * Quick v2 test (sample run): the store holds only the sample. No app recommendations (idle apps, app caches) are made,
         * the adapted result lists no apps, and the result carries {@code Result.sampleNote}. null = a full run.
         */
        public SampleSpec sample;
        /**
         * REVIEW_S F-09: on a DiskStore, drop the store's in-memory indexes before {@code V2Adapter.adapt} (the store is then
         * only closable, and {@code Result.in.files} is null). Default true; tests that read the store afterwards set false.
         */
        public boolean releaseIndexes = true;
        public Marks marks;
    }

    public static final class Result {
        public UnitBuilder2.Input in;
        public Plans.Made made;
        public V2Adapter.Adapted adapted;     // null when Params.adapt is false
        /**
         * PHASE_S_STORE S7: photos whose comparison the scan left for a later run (GRAPH's decode budget ran out). The result is
         * complete and safe — those photos are "relationships unknown", never unique, never grouped or removed on their
         * comparisons — but another run continues the comparison from the saved checks. 0 = finished.
         */
        public long pendingPhotos;
        /** The banner line of a sample run ("Sample result: ... only."), else null. */
        public String sampleNote;
        /** The line the app shows when {@link #pendingPhotos} &gt; 0, else null. */
        public String continueNote() { return continueMessage(pendingPhotos); }
    }

    /** "N photos have not been compared yet; run the analysis again to continue." (null for 0). */
    public static String continueMessage(long pendingPhotos) {
        if (pendingPhotos <= 0) return null;
        return (pendingPhotos == 1 ? "1 photo has" : pendingPhotos + " photos have") + " not been compared with similar photos yet,"
                + " so v2 suggests nothing for them; run the analysis again to continue the comparison.";
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
        if (p.sample == null) recs.addAll(AppSubjects.recs(p.apps, p.now, p.sdk, scanId, ProvisionalRecommendations.PLAN_VERSION));
        if (pr != null) pr.update("Planning", 0, 0);
        Result r = new Result();
        UnitBuilder2.Input in = new UnitBuilder2.Input();
        in.recs = recs; in.files = fm; in.keepers = Plans.keepers(store);
        r.in = in;
        if (p.sample != null) r.sampleNote = p.sample.notice();
        final List<com.storageplanner.v2.device.InstalledApp> appList =
                p.sample != null ? new ArrayList<com.storageplanner.v2.device.InstalledApp>() : p.apps.list();
        r.made = Plans.make(in, ActionPlan.MAX_SAFE, null, false);
        Plans.version(store, r.made.plan, p.now, scanId);
        ScanSession ss = store.scanSessions().get(ScanSession.keyOf(scanId));   // F-07: damage found by ASSESS/PLAN is counted too
        r.pendingPhotos = com.storageplanner.v2.graph.GraphStage.pendingImages(ss);
        if (ss != null && Pipeline.noteDamaged(store, ss, p.now)) store.scanSessions().put(ss);
        store.flush();
        if (mk != null) mk.mark("plan_made");
        if (p.adapt) {
            java.util.Map<String, String> dupOf = V2Adapter.duplicateOf(store.duplicateGroups().all());
            if (store instanceof DiskStore && p.releaseIndexes) {
                // REVIEW_S F-09: the store's in-memory indexes go before the classic result grows; the adapter reads the files
                // through the 16 B/file projection. The store stays open only for that and is closed by the caller.
                Iterable<FileRecord> files = ((DiskStore) store).releaseIndexes();
                in.files = null;              // StoreFileMap cannot be read any more
                if (mk != null) mk.mark("indexes_released");
                r.adapted = V2Adapter.adapt(in, files, r.made.built, appList, dupOf, p.root, p.sdk, p.now, p.total, p.free, false);
            } else
                r.adapted = V2Adapter.adapt(in, r.made.built, appList, dupOf, p.root, p.sdk, p.now, p.total, p.free, false);
            if (mk != null) mk.mark("adapted");
        }
        return r;
    }
}
