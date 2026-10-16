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

    static final String INTERRUPTED = "The last analysis was interrupted. Tap Analyse to run it again.";

    static volatile boolean running, cancelled;
    static volatile String stage = "";
    static volatile int done, total;
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
        running = true; cancelled = false; pendingOverview = false; message = null;
        stage = "Starting"; done = 0; total = 0; lastPost = 0;
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
