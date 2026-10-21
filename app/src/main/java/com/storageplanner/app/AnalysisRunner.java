package com.storageplanner.app;

import android.content.Context;

import com.storageplanner.core.Estimates;
import com.storageplanner.core.FileItem;
import com.storageplanner.core.ResultStore;
import com.storageplanner.core.Scanner;
import com.storageplanner.core.UnitBuilder;
import com.storageplanner.platform.android.AndroidPlatform;
import com.storageplanner.platform.android.Calibrator;

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
