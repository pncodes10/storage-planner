package com.storageplanner.app;

import android.content.Context;

import com.storageplanner.core.Estimates;
import com.storageplanner.core.FileItem;
import com.storageplanner.core.FinalPlan;
import com.storageplanner.core.Piles;
import com.storageplanner.core.Report;
import com.storageplanner.core.Unit;
import com.storageplanner.core.ResultStore;
import com.storageplanner.core.Scanner;
import com.storageplanner.core.UnitBuilder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
        boolean ok, cancelled, paused;
        String message;
        long saved;          // bytes the plan frees (for the notification)
    }

    static Outcome run(final Context app, double targetGb, boolean measure, Scanner.Progress pr) { return run(app, targetGb, measure, false, pr); }

    /** sample: analyse only the pictures picked on Try on my pictures (SampleScreen.limit), not the whole phone. */
    static Outcome run(final Context app, double targetGb, boolean measure, boolean sample, Scanner.Progress pr0) {
        Scanner.Progress pr = pr0;
        Outcome o = new Outcome();
        ResultFiles.setRunning(app, true);
        try {
            AndroidPlatform pf = new AndroidPlatform(app);
            Scanner.Options opt = new Scanner.Options();
            opt.sortPictures = sortPictures(app);
            Perf.apply(app, opt);
            final Perf.Timer timer = new Perf.Timer();
            final Scanner.Progress inner = pr;
            pr = new Scanner.Progress() {
                public void update(String stage, int done, int total) { timer.tick(stage, done, total); inner.update(stage, done, total); }
                public boolean cancelled() { return inner.cancelled(); }
            };
            if (sample) { opt.limit = SampleScreen.limit(app); opt.sortPictures = true; }
            else {                                                                       // the Settings limits on pictures
                opt.maxPictures = PlanStore.maxPictures(app);
                opt.minPictureBytes = PlanStore.minPictureKb(app) * 1000L;
            }
            // a limited run on Try on my pictures starts from scratch: timing it against saved results would measure nothing
            ResultStore.Saved prev = sample ? null : ResultFiles.load(app);               // unchanged pictures are not measured or read again
            if (prev != null) {
                opt.previous = new java.util.HashMap<String, com.storageplanner.core.FileItem>();
                for (com.storageplanner.core.FileItem f : prev.result.files) opt.previous.put(f.rel, f);
            }
            prev = null;
            final int sdk = pf.sdkInt();
            final boolean partSaves = !sample;
            opt.live = new Scanner.Live() {
                long lastSave = System.currentTimeMillis();
                public void looking(FileItem f) { AnalysisState.looking = describe(f); }
                public void checkpoint(Scanner.Result r, int fd, int ft, long bd, long bt) {
                    AnalysisState.filesDone = fd; AnalysisState.filesTotal = ft; AnalysisState.bytesDone = bd; AnalysisState.bytesTotal = bt;
                    if (r.resumed > 0 && AnalysisState.resumeNote == null)
                        AnalysisState.resumeNote = "Carrying on where you paused: " + Piles.count(r.resumed, "file") + " already done "
                                + (r.resumed == 1 ? "is" : "are") + " taken over" + (r.gone > 0 ? ", and " + Piles.count(r.gone, "file")
                                + " you removed " + (r.gone == 1 ? "is" : "are") + " gone from the list." : ".");
                    try { snapshot(app, r, sdk); } catch (Throwable ignored) {}
                    AnalysisState.live();
                    // what is done is saved now and then, so a crash or a killed app loses little: it opens as a paused plan
                    if (partSaves && System.currentTimeMillis() - lastSave > SAVE_EVERY_MS) {
                        lastSave = System.currentTimeMillis();
                        saveAs(app, r, new Estimates(), targetGb, pf, sdk);
                        setPaused(app, ft - fd, ft);
                    }
                }
            };
            Scanner.Result r;
            try { r = Scanner.scan(pf, pr, opt); } finally { pf.close(); }
            if (AnalysisState.cancelled && AnalysisState.discard) {          // thrown away: the next analysis starts from the beginning
                ResultFiles.delete(app);
                setPaused(app, 0, 0);
                final CountDownLatch cleared = new CountDownLatch(1);
                AnalysisState.UI.post(new Runnable() { public void run() {
                    try { Session.clear(); PlanStore.invalidate(); } finally { cleared.countDown(); }
                } });
                cleared.await(10, TimeUnit.SECONDS);
                o.cancelled = true; o.message = "Stopped. This analysis was thrown away: the next one starts from the beginning.";
                return o;
            }
            if (!r.prepared) {                                                // paused while listing: nothing new to show
                o.cancelled = true; o.message = "Paused before any file was looked at. Your last plan stays as it was.";
                return o;
            }
            final boolean paused = AnalysisState.cancelled;
            int pending = 0;
            for (FileItem f : r.files) if (f.pending) pending++;
            Estimates est = new Estimates();
            if (!paused && measure) Calibrator.run(r, est, pr);
            // stopped while measuring the savings: the files are all done, so the plan is saved as finished all the same
            pr.update("Planning", 0, 0);
            final int rulesVersion = RulesStore.version();
            final UnitBuilder ub = new UnitBuilder(r, est, pf.sdkInt(), RulesStore.get(app));

            final ResultStore.Saved s = new ResultStore.Saved();
            s.result = r; s.est = est; s.targetUsed = (long) (targetGb * 1e9);
            s.appStats = pf.appStatsAvailable; s.sdk = pf.sdkInt(); s.mode = Session.mode;
            pr.update("Saving results", 0, 0);
            ResultFiles.save(app, s);                 // a failed save only costs a rescan after process death
            setLastSample(app, r.sample);             // the overview says when the plan covers only some pictures
            setPaused(app, sample ? 0 : pending, r.files.size());

            final CountDownLatch set = new CountDownLatch(1);
            final long[] saved = {0};
            AnalysisState.UI.post(new Runnable() {
                public void run() {
                    try {
                        Session.loadKept(app);
                        Session.set(s.result, ub, s.est, s.targetUsed, s.appStats, s.sdk);
                        if (RulesStore.version() != rulesVersion) Session.rebuild(app, null);       // the rules were saved while the scan ran
                        saved[0] = Session.plan.saved();
                    } finally { set.countDown(); }
                }
            });
            set.await(60, TimeUnit.SECONDS);
            o.ok = true;
            o.paused = paused;
            o.saved = saved[0];
            o.message = paused ? "Paused: " + Piles.count(r.files.size() - pending, "file") + " of " + String.format(java.util.Locale.US, "%,d", r.files.size())
                    + " analysed. The plan covers those; carry on any time to analyse the rest."
                    : r.sample != null ? "Done: " + r.files.size() + " pictures analysed (" + r.sample + ")."
                    : "Done: " + r.files.size() + " files and " + r.apps.size() + " apps analysed.";
            if (!paused && r.mirageNote != null && !"cancelled".equals(r.mirageNote))      // the plan is less precise without Mirage: say so
                o.message += "\nMirage could not look at your pictures this time (" + r.mirageNote + "), so a few suggestions that need it "
                        + "(accidental shots, photos taken to remember something) were held back. The next analysis tries again.";
            String timing = timer.finish(opt.highPriority);
            o.message += "\n" + timing;
            app.getSharedPreferences("perf", Context.MODE_PRIVATE).edit().putString("last_timing", timing).apply();
            LabLog.line(app, "\n--- " + LabLog.now() + "  Analysis timing (" + r.files.size() + " pictures)\n" + timing);
            return o;
        } catch (Throwable t) {
            o.message = "Analysis failed: " + t;
            return o;
        } finally {
            ResultFiles.setRunning(app, false);
            AnalysisState.finished(o.message, o.ok);
        }
    }

    /** A checkpoint is saved this often (the plan so far survives a crash or the app being killed). */
    static final long SAVE_EVERY_MS = 60000;

    /** "Video · 412 MB" */
    static String describe(FileItem f) {
        int k = FileKind.of(f);
        String what = k == FileKind.IMAGE ? "Photo" : k == FileKind.VIDEO ? "Video" : "Music";
        long sec = f.durationMs / 1000;
        String len = k == FileKind.VIDEO && sec > 0 ? (sec >= 60 ? " · " + (sec / 60) + " min" : " · " + sec + " s") : "";
        return what + " · " + Report.size(f.size) + len;
    }

    /** What the files done so far would free, and the piles they make (for the progress screen). Analysis thread. */
    static void snapshot(Context app, Scanner.Result r, int sdk) {
        UnitBuilder ub = new UnitBuilder(r, new Estimates(), sdk, RulesStore.get(app));
        Map<String, Unit> units = new HashMap<String, Unit>();
        for (Unit u : ub.units) units.put(u.id, u);
        Set<String> kept; Map<String, String> accepted;
        try { kept = new HashSet<String>(Session.kept); accepted = new HashMap<String, String>(Session.accepted); }
        catch (RuntimeException e) { kept = new HashSet<String>(); accepted = new HashMap<String, String>(); }   // changed meanwhile
        List<FinalPlan.Entry> entries = FinalPlan.build(r.files, units, PlanStore.choices(app), kept, accepted);
        List<Object[]> ready = new ArrayList<Object[]>();
        for (Piles.Pile p : Piles.build(entries)) if (p.freed() > 0) ready.add(new Object[]{p.title(), p.freed()});
        AnalysisState.ready = ready;
        AnalysisState.freed = FinalPlan.totals(entries, null).freed();
    }

    /** Saves the files done so far (the rest pending) as the last results. */
    static void saveAs(Context app, Scanner.Result r, Estimates est, double targetGb, AndroidPlatform pf, int sdk) {
        ResultStore.Saved s = new ResultStore.Saved();
        s.result = r; s.est = est; s.targetUsed = (long) (targetGb * 1e9);
        s.appStats = pf.appStatsAvailable; s.sdk = sdk; s.mode = Session.mode;
        ResultFiles.save(app, s);
    }

    /** After a paused analysis: {files not analysed yet, files in all}; null when the last analysis finished. */
    static int[] paused(Context c) {
        android.content.SharedPreferences p = c.getSharedPreferences("analysis", Context.MODE_PRIVATE);
        int n = p.getInt("paused_pending", 0), t = p.getInt("paused_total", 0);
        return n > 0 && t > 0 ? new int[]{n, t} : null;
    }

    static void setPaused(Context c, int pending, int total) {
        c.getSharedPreferences("analysis", Context.MODE_PRIVATE).edit().putInt("paused_pending", pending).putInt("paused_total", total).apply();
    }

    /** What the last finished analysis covered when it was limited to some pictures; null after a full one. */
    static String lastSample(Context c) { return c.getSharedPreferences("analysis", Context.MODE_PRIVATE).getString("last_sample", null); }
    static void setLastSample(Context c, String s) {
        android.content.SharedPreferences.Editor e = c.getSharedPreferences("analysis", Context.MODE_PRIVATE).edit();
        if (s == null) e.remove("last_sample"); else e.putString("last_sample", s);
        e.apply();
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
