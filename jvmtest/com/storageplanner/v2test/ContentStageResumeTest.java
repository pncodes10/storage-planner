package com.storageplanner.v2test;

import com.storageplanner.v2.content.*;
import com.storageplanner.v2.device.ScanControl;
import com.storageplanner.v2.model.*;
import com.storageplanner.v2.pipeline.PipelineResult;
import com.storageplanner.v2.stage.Budget;
import com.storageplanner.v2.stage.StageOutcome;
import com.storageplanner.v2.store.ContentCodec;
import com.storageplanner.v2.util.JsonWriter;

import java.util.Map;
import java.util.TreeMap;

/** Phase 6 S2 (PHASE_6 5.3): the content stage commits in steps, pauses/stops cleanly and a chain of runs equals one uninterrupted run. */
public final class ContentStageResumeTest {
    static final int N = 30;

    static void fill(Fx fx) throws Exception {
        for (int i = 0; i < N; i++) {
            final int ev = 100 + 10 * ((i * 3) % 40);          // event start (sample) differs per file
            byte[] f = ActivityProfileTest.clip(600, 100, 25 + (i % 3), new ActivityProfileTest.Hook() {
                public void apply(int[] s, boolean[] k) { for (int j = ev; j < ev + 50; j++) if (!k[j]) s[j] = 700; } });
            fx.tree.file(String.format("DCIM/Camera/VID_%02d.mp4", i), f);
        }
    }

    static Map<String, String> activities(Fx fx) {
        Map<String, String> m = new TreeMap<String, String>();
        for (FileRecord f : fx.files()) {
            FeatureRecord fr = fx.store.features().get(f.fileId);
            if (fr == null) continue;
            final ActivityInfo a = fr.activity;
            java.io.StringWriter sw = new java.io.StringWriter();
            if (a != null) ContentCodec.write(new JsonWriter(sw), a);
            m.put(f.path, a == null ? "null" : sw.toString() + "/v" + fr.versions[com.storageplanner.v2.features.Extractors.ACTIVITY]);
        }
        return m;
    }

    /** Pauses the stage as soon as it reports that 10 files are done (= right after its first commit with commitEvery = 10). */
    static final class PauseAfterFirstCommit implements ScanControl {
        boolean pause;
        public boolean cancelRequested() { return false; }
        public boolean pauseRequested() { return pause; }
        public void progress(String stage, long done, long total) { if (ContentStage.ID.equals(stage) && done >= 10) pause = true; }
    }

    static long count(Fx fx, long scanId) { Long v = fx.session(scanId).counters.get(ContentStage.COUNT_ACTIVITY); return v == null ? 0 : v; }

    public static int run(String[] args) throws Exception {
        T.section("ContentStageResume");
        Map<String, String> whole;
        try (Fx fx = Fx.create()) {
            fx.maxTier = 1;
            ContentConfig cfg = new ContentConfig(); cfg.commitEvery = 10;
            fx.extraStages.add(new ContentStage(new ContentProviders(null, null), cfg));
            fill(fx);
            fx.scan();
            whole = activities(fx);
            T.eq("uninterrupted: 30 files", 30L, count(fx, 1));
            T.check("uninterrupted: all have OK activity", whole.size() == N && !whole.values().contains("null"));
        }
        try (Fx fx = Fx.create()) {
            fx.maxTier = 1;
            ContentConfig cfg = new ContentConfig(); cfg.commitEvery = 10;
            ContentStage cs = new ContentStage(new ContentProviders(null, null), cfg);
            fx.extraStages.add(cs);
            fill(fx);
            fx.control = new TestControlAdapter(new PauseAfterFirstCommit());
            PipelineResult r1 = fx.scan();
            T.eq("run 1 paused", StageOutcome.PAUSED, r1.outcome);
            T.eq("run 1 processed the first 10", 10L, count(fx, 1));
            int have = 0; for (String v : activities(fx).values()) if (!v.equals("null")) have++;
            T.eq("10 files committed with their versions", 10, have);
            T.eq("pending counts in the session event", "ocr=0 visual=0 doc=0 activity=20", eventDetail(fx.session(1)));
            T.eq("stage pending field", 20L, cs.pending[1]);
            fx.control = null;
            PipelineResult r2 = fx.scan(ScanKind.FULL, true);
            T.eq("run 2 completed", StageOutcome.DONE, r2.outcome);
            T.eq("run 2 processed only the rest", 30L, count(fx, 1));
            T.check("event gone when nothing is pending", eventDetail(fx.session(1)) == null);
            T.check("chain == uninterrupted run", whole.equals(activities(fx)));
            fx.scan();
            T.eq("a later FULL run reads nothing", 0L, count(fx, 2));
            T.check("and changes nothing", whole.equals(activities(fx)));
        }
        // a byte budget stops the stage between files; further runs converge on the same result
        try (Fx fx = Fx.create()) {
            fx.maxTier = 1;
            ContentConfig cfg = new ContentConfig(); cfg.commitEvery = 7;
            fx.extraStages.add(new ContentStage(new ContentProviders(null, null), cfg));
            fill(fx);
            int runs = 0; long total = 0; boolean sawBudget = false;
            long scanBefore = 0;
            for (; runs < 40; runs++) {
                Budget b = new Budget(); b.maxBytesRead = runs == 0 ? 6000 : 4000;   // T1 reads heads too; later runs charge activity bytes mostly
                fx.budget = b;
                PipelineResult r = fx.scan(ScanKind.FULL, false);
                if (r.outcome == StageOutcome.BUDGET_EXHAUSTED) sawBudget = true;
                int have = 0; for (String v : activities(fx).values()) if (!v.equals("null")) have++;
                if (have == N && r.outcome == StageOutcome.DONE) break;
            }
            T.check("budget stopped at least one run", sawBudget);
            fx.budget = null; fx.scan();
            T.check("budgeted chain == uninterrupted run", whole.equals(activities(fx)));
        }
        return T.finish("ContentStageResumeTest");
    }

    static String eventDetail(ScanSession s) {
        for (ScanEvent e : s.events) if (ContentStage.INCOMPLETE.equals(e.code)) return e.detail;
        return null;
    }

    /** Fx.control is a TestControl; this adapter lets a custom ScanControl be used in its place. */
    static final class TestControlAdapter extends TestControl {
        final ScanControl c;
        TestControlAdapter(ScanControl c) { this.c = c; }
        public boolean cancelRequested() { return c.cancelRequested(); }
        public boolean pauseRequested() { return c.pauseRequested(); }
        public void progress(String stage, long done, long total) { c.progress(stage, done, total); }
    }
}
