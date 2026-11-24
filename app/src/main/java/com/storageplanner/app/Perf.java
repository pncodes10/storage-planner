package com.storageplanner.app;

import android.content.Context;
import android.content.SharedPreferences;

import com.storageplanner.core.Scanner;

/**
 * Speed settings of the analysis, and the stage timer behind the "Timing" line.
 *
 * Why the analysis used to slow down in the background: the text reader runs in a WebView that is never on screen, and Android
 * lowers the priority of that WebView's renderer process (and of the analysis threads) as soon as the app is not in front.
 * The loop also sent one picture at a time to the reader, so any slowness showed up in full. With the boost on, the renderer
 * is kept at "important", the worker threads run at the highest priority, and several pictures are in the reader at once.
 */
final class Perf {
    private Perf() {}

    private static final String PREFS = "perf";

    /** The speed-ups on (default) or off (the old behaviour, for comparing). */
    static boolean boost(Context c) { return c.getSharedPreferences(PREFS, 0).getBoolean("boost", true); }
    static void setBoost(Context c, boolean on) { c.getSharedPreferences(PREFS, 0).edit().putBoolean("boost", on).apply(); }

    /** The last "Timing" line (kept so the Lab screen can show it again). */
    static String lastTiming(Context c) { return c.getSharedPreferences(PREFS, 0).getString("last_timing", ""); }

    static void apply(Context c, Scanner.Options opt) {
        boolean on = boost(c);
        opt.highPriority = on;
        opt.textThreads = on ? 6 : 1;
        opt.workers = on ? Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors())) : 1;
    }

    /** Tesseract workers in the text reader: one per two cores, 1 to 3 (the old value is one per three cores, 1 to 3). */
    static int ocrWorkers(Context c) {
        int cores = Runtime.getRuntime().availableProcessors();
        return boost(c) ? Math.max(1, Math.min(3, cores / 2)) : Math.max(1, Math.min(3, cores / 3));
    }

    // ---- battery optimisation: while the app is in the background Samsung and Android give a restricted app little CPU
    static boolean batteryExempt(Context c) {
        try { return ((android.os.PowerManager) c.getSystemService(Context.POWER_SERVICE)).isIgnoringBatteryOptimizations(c.getPackageName()); }
        catch (Throwable t) { return true; }
    }

    /** Opens Android's own "Allow Storage Planner to run in the background without limits?" dialog (the user decides). */
    static void askBatteryExemption(android.app.Activity a) {
        try {
            a.startActivity(new android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    android.net.Uri.parse("package:" + a.getPackageName())));
        } catch (Throwable t) {
            try { a.startActivity(new android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); } catch (Throwable ignored) {}
        }
    }

    /** Raises the calling thread's priority when the boost is on (worker threads started from it inherit it). */
    static void raiseThread(Context c) {
        if (boost(c)) try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_FOREGROUND); } catch (Throwable ignored) {}
    }

    /** Times each stage and how long the app was out of sight while it ran. Worker thread only. */
    static final class Timer {
        private final long t0 = System.currentTimeMillis();
        private final java.util.LinkedHashMap<String, long[]> stages = new java.util.LinkedHashMap<String, long[]>();   // ms, pictures
        private String cur;
        private long curStart, lastTick, bgMs;
        private int curMax;

        void tick(String stage, int done, int total) {
            long now = System.currentTimeMillis();
            if (lastTick != 0 && AnalysisState.listener == null) bgMs += now - lastTick;
            lastTick = now;
            if (stage == null) return;
            if (!stage.equals(cur)) { close(now); cur = stage; curStart = now; curMax = 0; }
            curMax = Math.max(curMax, total > 0 ? total : done);
        }

        private void close(long now) {
            if (cur == null) return;
            long[] a = stages.get(cur);
            if (a == null) { a = new long[2]; stages.put(cur, a); }
            a[0] += now - curStart; a[1] = Math.max(a[1], curMax);
        }

        String finish(boolean boosted) {
            long now = System.currentTimeMillis();
            tick(null, 0, 0);
            close(now); cur = null;
            long total = now - t0;
            StringBuilder b = new StringBuilder();
            b.append(String.format(java.util.Locale.US, "Timing: total %.1f s, %.0f%% of it with the app out of sight, speed-ups %s.",
                    total / 1000.0, total > 0 ? 100.0 * bgMs / total : 0, boosted ? "ON" : "OFF"));
            for (java.util.Map.Entry<String, long[]> e : stages.entrySet()) {
                if (e.getValue()[0] < 200) continue;
                b.append(String.format(java.util.Locale.US, "\n  %s: %.1f s", e.getKey(), e.getValue()[0] / 1000.0));
                if (e.getValue()[1] > 0) b.append(String.format(java.util.Locale.US, " (%d, %.0f ms each)", e.getValue()[1], e.getValue()[0] / (double) e.getValue()[1]));
            }
            return b.toString();
        }
    }
}
