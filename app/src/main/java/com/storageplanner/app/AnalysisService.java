package com.storageplanner.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;

import com.storageplanner.core.Report;
import com.storageplanner.core.Scanner;

/**
 * Foreground service (type dataSync: local file processing) that runs the analysis, so it keeps going
 * while the user is in other apps or the screen is off. Started by MainActivity while it is in the
 * foreground; shows an ongoing progress notification with Cancel, then a "plan is ready" notification.
 * START_NOT_STICKY: if the process is killed anyway, MainActivity reports the interrupted scan.
 */
public final class AnalysisService extends Service {
    static final String ACTION_START = "com.storageplanner.app.action.START";
    static final String ACTION_CANCEL = "com.storageplanner.app.action.CANCEL";
    static final String EXTRA_TARGET_GB = "target_gb", EXTRA_MEASURE = "measure";

    private static final String CH_PROGRESS = "analysis", CH_RESULT = "results";
    static final int ID_PROGRESS = 1, ID_RESULT = 2;
    private static final long WAKE_TIMEOUT_MS = 60 * 60 * 1000L;

    private static boolean workerActive;             // main thread only
    private long lastNotify;
    private int lastStartId;

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        lastStartId = startId;
        if (ACTION_CANCEL.equals(action)) {
            AnalysisState.cancelled = true;
            if (!workerActive) stopSelf(startId);
            else notifyProgress("Cancelling", 0, 0, true);
            return START_NOT_STICKY;
        }
        // started with startForegroundService: must go foreground right away, even if already running
        goForeground(progressNotification(AnalysisState.stage, AnalysisState.done, AnalysisState.total, false));
        if (workerActive || intent == null) {
            if (!workerActive) stopSelf(startId);
            return START_NOT_STICKY;
        }
        workerActive = true;
        if (!AnalysisState.running) AnalysisState.begin();
        final double gb = intent.getDoubleExtra(EXTRA_TARGET_GB, 0);
        final boolean measure = intent.getBooleanExtra(EXTRA_MEASURE, true);
        new Thread(new Runnable() { public void run() { work(gb, measure); } }, "analysis").start();
        return START_NOT_STICKY;
    }

    private void work(double gb, boolean measure) {
        PowerManager.WakeLock wl = null;
        AnalysisRunner.Outcome o = null;
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "StoragePlanner:analysis");
                wl.setReferenceCounted(false);
                wl.acquire(WAKE_TIMEOUT_MS);
            }
            o = AnalysisRunner.run(getApplicationContext(), gb, measure, AnalysisRunner.progress(new Scanner.Progress() {
                public void update(String stage, int done, int total) { notifyProgress(stage, done, total, false); }
                public boolean cancelled() { return AnalysisState.cancelled; }
            }));
        } catch (Throwable t) {
            o = new AnalysisRunner.Outcome();
            o.message = "Analysis failed: " + t;
            AnalysisState.finished(o.message, false);      // the runner did not get to report it
        } finally {
            try { if (wl != null && wl.isHeld()) wl.release(); } catch (Throwable ignored) {}
            final AnalysisRunner.Outcome fo = o;
            AnalysisState.UI.post(new Runnable() { public void run() { done(fo); } });
        }
    }

    /** Main thread: replace the ongoing notification with the outcome and stop. */
    private void done(AnalysisRunner.Outcome o) {
        workerActive = false;
        stopForeground(STOP_FOREGROUND_REMOVE);
        // the visible app already shows the outcome; otherwise leave a notification
        if (AnalysisState.listener == null) try {
            NotificationManager nm = nm();
            if (nm != null) {
                Notification.Builder b = builder(CH_RESULT)
                        .setAutoCancel(true)
                        .setOnlyAlertOnce(true)
                        .setContentIntent(openApp());
                if (o != null && o.ok) {
                    b.setSmallIcon(android.R.drawable.stat_notify_sdcard)
                     .setContentTitle("Your storage plan is ready: frees " + Report.gb(o.saved))
                     .setContentText("Tap to review it. Nothing has been deleted or changed.");
                } else if (o != null && o.cancelled) {
                    b.setSmallIcon(android.R.drawable.stat_notify_sdcard)
                     .setContentTitle("Analysis cancelled")
                     .setContentText("Tap to open Storage Planner.");
                } else {
                    b.setSmallIcon(android.R.drawable.stat_notify_error)
                     .setContentTitle("Analysis failed")
                     .setContentText(o != null && o.message != null ? o.message : "Tap to open Storage Planner and try again.");
                }
                nm.notify(ID_RESULT, b.build());
            }
        } catch (Throwable ignored) {}
        stopSelf(lastStartId);        // a start that arrived meanwhile keeps the service alive
    }

    @Override public void onDestroy() {
        if (workerActive) AnalysisState.cancelled = true;     // stopped by the system mid-scan: end the worker cleanly
        super.onDestroy();
    }

    // ------------------------------------------------------------------ notifications
    private void goForeground(Notification n) {
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID_PROGRESS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else startForeground(ID_PROGRESS, n);
    }

    /** Worker thread; at most every 500 ms. */
    private void notifyProgress(String stage, int done, int total, boolean force) {
        long now = SystemClock.uptimeMillis();
        if (!force && now - lastNotify < 500) return;
        lastNotify = now;
        try {
            NotificationManager nm = nm();
            if (nm != null) nm.notify(ID_PROGRESS, progressNotification(stage, done, total, AnalysisState.cancelled));
        } catch (Throwable ignored) {}
    }

    private Notification progressNotification(String stage, int done, int total, boolean cancelling) {
        String text = cancelling ? "Cancelling…"
                : total > 0 ? stage + "  " + done + " of " + total : (done > 0 ? stage + "  " + done : (stage == null || stage.isEmpty() ? "Starting" : stage) + "…");
        Notification.Builder b = builder(CH_PROGRESS)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("Analysing your storage")
                .setContentText(text)
                .setOngoing(true)
                .setShowWhen(false)
                .setOnlyAlertOnce(true)
                .setContentIntent(openApp())
                .setProgress(total > 0 ? total : 0, total > 0 ? Math.min(done, total) : 0, total <= 0);
        if (!cancelling) {
            Intent ci = new Intent(this, AnalysisService.class).setAction(ACTION_CANCEL);
            PendingIntent cp = PendingIntent.getService(this, 1, ci, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            b.addAction(new Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                    "Cancel", cp).build());
        }
        if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        return b.build();
    }

    private Notification.Builder builder(String channel) {
        ensureChannels(this);
        return new Notification.Builder(this, channel)
                .setCategory(CH_PROGRESS.equals(channel) ? Notification.CATEGORY_PROGRESS : Notification.CATEGORY_STATUS);
    }

    private PendingIntent openApp() {
        Intent i = new Intent(this, MainActivity.class)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        return PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private NotificationManager nm() { return (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE); }

    static void ensureChannels(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null || nm.getNotificationChannel(CH_PROGRESS) != null) return;
        NotificationChannel p = new NotificationChannel(CH_PROGRESS, "Analysis progress", NotificationManager.IMPORTANCE_LOW);
        p.setDescription("Shown while Storage Planner analyses your storage in the background");
        p.setShowBadge(false);
        NotificationChannel r = new NotificationChannel(CH_RESULT, "Plan ready", NotificationManager.IMPORTANCE_DEFAULT);
        r.setDescription("Tells you when your storage plan is ready");
        nm.createNotificationChannel(p);
        nm.createNotificationChannel(r);
    }

    /** Remove a finished "plan ready" notification (the user is looking at the app). */
    static void clearResultNotification(Context c) {
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(ID_RESULT);
        } catch (Throwable ignored) {}
    }
}
