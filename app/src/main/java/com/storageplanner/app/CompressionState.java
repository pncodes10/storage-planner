package com.storageplanner.app;

import android.os.SystemClock;

import com.storageplanner.core.FileItem;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Live state of a compression run, shared by CompressionService (worker thread) and the visible
 * screens. Like AnalysisState: the worker writes, the activity listens while it is in the foreground.
 */
final class CompressionState {
    private CompressionState() {}

    static volatile boolean running, cancelled;
    /** Recommendation being (or last) carried out; null before the first run. */
    static volatile String technique;
    /** Files to compress, handed from the screen to the service (main thread writes before starting). */
    static List<FileItem> queue = new ArrayList<FileItem>();
    static volatile int done, total, compressed;
    static volatile long before, after;
    /** Reason -> how many files were skipped for it (worker thread writes; read on the main thread after it ends). */
    static final Map<String, Integer> skipped = new LinkedHashMap<String, Integer>();
    /** Main thread only: refreshes the visible screen. */
    static Runnable listener;

    private static long lastPost;

    /** Main thread. False if a run is already going. */
    static boolean begin(String tech, List<FileItem> files) {
        if (running) return false;
        running = true; cancelled = false; technique = tech;
        queue = new ArrayList<FileItem>(files);
        done = 0; total = files.size(); compressed = 0; before = 0; after = 0; lastPost = 0;
        synchronized (skipped) { skipped.clear(); }
        return true;
    }

    static long saved() { return Math.max(0, before - after); }

    static void skip(String why) {
        synchronized (skipped) { Integer n = skipped.get(why); skipped.put(why, n == null ? 1 : n + 1); }
    }

    static int skippedCount() {
        int n = 0;
        synchronized (skipped) { for (int v : skipped.values()) n += v; }
        return n;
    }

    static Map<String, Integer> skippedCopy() {
        synchronized (skipped) { return new LinkedHashMap<String, Integer>(skipped); }
    }

    /** Worker thread: one file finished; tell the visible screen (throttled). */
    static void progress() {
        long now = SystemClock.uptimeMillis();
        if (now - lastPost < 300 && done != total) return;
        lastPost = now;
        post();
    }

    /** Any thread: the run ended. */
    static void finished() {
        AnalysisState.UI.post(new Runnable() {
            public void run() { running = false; queue = new ArrayList<FileItem>(); if (listener != null) listener.run(); }
        });
    }

    private static void post() {
        AnalysisState.UI.post(new Runnable() { public void run() { if (listener != null) listener.run(); } });
    }
}
