package com.storageplanner.app;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

/**
 * Live state of the analysis, shared by AnalysisService (worker thread) and the visible MainActivity.
 * The worker writes stage/done/total; the activity registers a listener in onResume and removes it in
 * onPause, and on (re)creation reads the fields directly, so a recreated activity shows live progress.
 */
final class AnalysisState {
    private AnalysisState() {}

    interface Listener {
        void onProgress(String stage, int done, int total);
        void onFinished(String message, boolean ok);
    }

    static final String INTERRUPTED = "The last analysis was interrupted. What it finished is kept: tap Carry on analysing to go on from there.";

    /** cancelled: stop the analysis (a pause: what is done is kept); discard: and throw this run away. */
    static volatile boolean running, cancelled, discard;
    static volatile String stage = "";
    static volatile int done, total;
    // ---- what the progress screen shows, from the analysis's checkpoints (worker thread writes, main thread reads)
    /** What can be freed with the files done so far; -1 = not counted yet. */
    static volatile long freed = -1, bytesDone, bytesTotal;
    static volatile int filesDone, filesTotal;
    /** The file being looked at ("Video · 412 MB"), or null. */
    static volatile String looking;
    /** The piles ready so far: {title, bytes}. */
    static volatile java.util.List<Object[]> ready = new java.util.ArrayList<Object[]>();
    /** Carrying on from a pause: what was taken over ("1,026 files already done are skipped ..."), or null. */
    static volatile String resumeNote;
    /** Last outcome / notice shown on the setup screen when idle (main thread). */
    static String message;
    /** Finished successfully while no activity was listening: the next onResume opens the Overview (main thread). */
    static boolean pendingOverview;
    /** Main thread only. */
    static Listener listener;

    static final Handler UI = new Handler(Looper.getMainLooper());
    private static long lastPost;

    /** Main thread. Returns false if an analysis is already running. */
    static boolean begin() {
        if (running) return false;
        running = true; cancelled = false; discard = false; pendingOverview = false; message = null;
        stage = "Starting"; done = 0; total = 0; lastPost = 0;
        freed = -1; bytesDone = 0; bytesTotal = 0; filesDone = 0; filesTotal = 0; looking = null; resumeNote = null;
        ready = new java.util.ArrayList<Object[]>();
        return true;
    }

    /** Worker thread: record progress and forward it to the visible activity (throttled). */
    static void progress(final String s, final int d, final int t) {
        stage = s; done = d; total = t;
        long now = SystemClock.uptimeMillis();
        if (now - lastPost < 150 && d != t) return;
        lastPost = now;
        UI.post(new Runnable() {
            public void run() { if (running && listener != null) listener.onProgress(s, d, t); }
        });
    }

    /** Worker thread: the progress screen's numbers changed (a checkpoint): redraw it. */
    static void live() {
        UI.post(new Runnable() {
            public void run() { if (running && listener != null) listener.onProgress(stage, done, total); }
        });
    }

    /** Any thread: the analysis ended (results, if any, are already in Session). */
    static void finished(final String msg, final boolean ok) {
        UI.post(new Runnable() {
            public void run() {
                running = false;
                message = msg;
                if (listener != null) { pendingOverview = false; listener.onFinished(msg, ok); }
                else pendingOverview = ok;
            }
        });
    }
}
