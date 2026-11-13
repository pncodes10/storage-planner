package com.storageplanner.app;

import android.content.Context;
import android.content.SharedPreferences;

import com.storageplanner.core.Estimates;
import com.storageplanner.core.ResultStore;
import com.storageplanner.core.Scanner;
import com.storageplanner.core.UnitBuilder;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The analysis pipeline (scan -> optional calibration -> units -> save -> Session), run on a worker
 * thread by AnalysisService (or, if the service cannot be started, by a plain thread).
 * Progress and the outcome go to AnalysisState; the cancel flag is AnalysisState.cancelled.
 */
final class AnalysisRunner {
    private AnalysisRunner() {}

    static final class Outcome {
        boolean ok, cancelled;
        String message;
        long saved;          // bytes the plan frees (for the notification)
    }

    static Outcome run(final Context app, double targetGb, boolean measure, Scanner.Progress pr) {
        if (ALGO_V2.equals(algorithm(app))) return V2Runner.run(app, targetGb, measure, pr);
        Outcome o = new Outcome();
        ResultFiles.setRunning(app, true);
        try {
            AndroidPlatform pf = new AndroidPlatform(app);
            Scanner.Options opt = new Scanner.Options();
            opt.sortPictures = sortPictures(app);
            ResultStore.Saved prev = ResultFiles.load(app);               // unchanged pictures are not measured or read again
            if (prev != null) {
                opt.previous = new java.util.HashMap<String, com.storageplanner.core.FileItem>();
                for (com.storageplanner.core.FileItem f : prev.result.files) opt.previous.put(f.rel, f);
            }
            prev = null;
            Scanner.Result r;
            try { r = Scanner.scan(pf, pr, opt); } finally { pf.close(); }
            Estimates est = new Estimates();
            if (!AnalysisState.cancelled && measure) Calibrator.run(r, est, pr);
            if (AnalysisState.cancelled) { o.cancelled = true; o.message = "Cancelled."; return o; }
            pr.update("Planning", 0, 0);
            final UnitBuilder ub = new UnitBuilder(r, est, pf.sdkInt());

            final ResultStore.Saved s = new ResultStore.Saved();
            s.result = r; s.est = est; s.targetUsed = (long) (targetGb * 1e9);
            s.appStats = pf.appStatsAvailable; s.sdk = pf.sdkInt(); s.mode = Session.mode;
            pr.update("Saving results", 0, 0);
            ResultFiles.save(app, s);                 // a failed save only costs a rescan after process death
            setResultAlgorithm(app, ALGO_CLASSIC);    // restore after process death uses classic for this result
            V2Runner.resetStoreIfPresent(app);        // REVIEW_S6 R-03: the v2 result is gone, give its working data back (truncation)

            final CountDownLatch set = new CountDownLatch(1);
            final long[] saved = {0};
            AnalysisState.UI.post(new Runnable() {
                public void run() {
                    try {
                        Session.loadKept(app);
                        Session.set(s.result, ub, s.est, s.targetUsed, s.appStats, s.sdk);
                        saved[0] = Session.plan.saved();
                    } finally { set.countDown(); }
                }
            });
            set.await(60, TimeUnit.SECONDS);
            o.ok = true;
            o.saved = saved[0];
            o.message = "Done: " + r.files.size() + " files and " + r.apps.size() + " apps analysed.";
            return o;
        } catch (Throwable t) {
            o.message = "Analysis failed: " + t;
            return o;
        } finally {
            ResultFiles.setRunning(app, false);
            AnalysisState.finished(o.message, o.ok);
        }
    }

    static boolean sortPictures(Context c) { return c.getSharedPreferences("analysis", Context.MODE_PRIVATE).getBoolean("sort_pictures", true); }
    static void setSortPictures(Context c, boolean on) { c.getSharedPreferences("analysis", Context.MODE_PRIVATE).edit().putBoolean("sort_pictures", on).apply(); }

    // ---- algorithm switch (Phase A): which algorithm runs next, and which one produced the saved result
    static final String ALGO_CLASSIC = "classic", ALGO_V2 = "v2";
    static String algorithm(Context c) {
        return ALGO_V2.equals(c.getSharedPreferences("analysis", Context.MODE_PRIVATE).getString("algorithm", ALGO_CLASSIC)) ? ALGO_V2 : ALGO_CLASSIC;
    }
    static void setAlgorithm(Context c, String a) { c.getSharedPreferences("analysis", Context.MODE_PRIVATE).edit().putString("algorithm", ALGO_V2.equals(a) ? ALGO_V2 : ALGO_CLASSIC).apply(); }
    /** Algorithm of the saved result; a missing marker (results saved before Phase A) means classic. null = none usable. */
    static String resultAlgorithm(Context c) {
        SharedPreferences p = c.getSharedPreferences("analysis", Context.MODE_PRIVATE);
        if (!p.contains("result_algorithm")) return ALGO_CLASSIC;
        String v = p.getString("result_algorithm", "");
        return ALGO_V2.equals(v) ? ALGO_V2 : ALGO_CLASSIC.equals(v) ? ALGO_CLASSIC : null;
    }
    /** null = the saved v2 state is incomplete (a v2 run is under way): restore nothing. */
    static void setResultAlgorithm(Context c, String a) {
        c.getSharedPreferences("analysis", Context.MODE_PRIVATE).edit().putString("result_algorithm", a == null ? "" : a).commit();
    }

    /** Progress callback that feeds AnalysisState (the activity) plus an optional extra sink (the notification). */
    static Scanner.Progress progress(final Scanner.Progress extra) {
        return new Scanner.Progress() {
            public void update(String stage, int done, int total) {
                AnalysisState.progress(stage, done, total);
                if (extra != null) extra.update(stage, done, total);
            }
            public boolean cancelled() { return AnalysisState.cancelled; }
        };
    }
}
