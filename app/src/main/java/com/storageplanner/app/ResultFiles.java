package com.storageplanner.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.AtomicFile;

import com.storageplanner.core.ResultStore;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

/**
 * App-private persistence of the last results (getFilesDir()/last_results.bin, written through
 * AtomicFile so a crash mid-write leaves the previous file intact) and of the "analysis running" flag
 * used to detect a scan interrupted by process death. The format itself lives in core.ResultStore.
 */
final class ResultFiles {
    private ResultFiles() {}

    private static final String NAME = "last_results.bin";
    private static final Object LOCK = new Object();
    /** Main thread: results are being loaded from disk. */
    static boolean loading;

    private static AtomicFile file(Context c) { return new AtomicFile(new File(c.getFilesDir(), NAME)); }

    static boolean exists(Context c) {
        return new File(c.getFilesDir(), NAME).exists() || new File(c.getFilesDir(), NAME + ".bak").exists();
    }

    /** The scan result the file on disk belongs to (guarded by LOCK). */
    private static Object current;

    /** Blocking write of a NEW analysis (call off the main thread). Never throws; returns false on failure. */
    static boolean save(Context c, ResultStore.Saved s) {
        synchronized (LOCK) {
            current = s.result;      // any pending re-save of older results is now stale
            return write(c, s);
        }
    }

    private static boolean write(Context c, ResultStore.Saved s) {
        synchronized (LOCK) {
            AtomicFile af = file(c);
            FileOutputStream fos = null;
            try {
                fos = af.startWrite();
                BufferedOutputStream out = new BufferedOutputStream(fos, 1 << 16);
                ResultStore.write(out, s);
                out.flush();
                af.finishWrite(fos);
                return true;
            } catch (Throwable t) {
                if (fos != null) af.failWrite(fos);
                return false;
            }
        }
    }

    /** Blocking read (call off the main thread). Null if missing, unreadable or another format version. */
    static ResultStore.Saved load(Context c) {
        synchronized (LOCK) {
            FileInputStream in = null;
            try {
                in = file(c).openRead();
                ResultStore.Saved s = ResultStore.tryRead(in);
                if (s != null) current = s.result;
                return s;
            } catch (Throwable t) {
                return null;
            } finally {
                if (in != null) try { in.close(); } catch (Throwable ignored) {}
            }
        }
    }

    /** Main thread: snapshot Session and write it in the background (e.g. after the plan mode changed). */
    static void saveSessionAsync(Context c) {
        if (!Session.hasResults()) return;
        final ResultStore.Saved s = snapshot();
        final Context app = c.getApplicationContext();
        new Thread(new Runnable() {
            public void run() { synchronized (LOCK) { if (s.result == current) write(app, s); } }
        }, "save-results").start();
    }

    static ResultStore.Saved snapshot() {
        ResultStore.Saved s = new ResultStore.Saved();
        s.result = Session.result; s.est = Session.est; s.targetUsed = Session.targetUsed;
        s.appStats = Session.appStats; s.sdk = Session.sdk; s.mode = Session.mode;
        return s;
    }

    // ---------------- "running" flag (survives process death)
    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("analysis", Context.MODE_PRIVATE); }

    static void setRunning(Context c, boolean running) { prefs(c).edit().putBoolean("running", running).commit(); }

    /** True once if the flag says an analysis was running but none is (the process died mid-scan); clears it. */
    static boolean consumeInterrupted(Context c) {
        if (AnalysisState.running || !prefs(c).getBoolean("running", false)) return false;
        setRunning(c, false);
        return true;
    }
}
