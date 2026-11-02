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
import android.media.MediaScannerConnection;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;

import com.storageplanner.core.Compression;
import com.storageplanner.core.FileItem;
import com.storageplanner.core.Report;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Foreground service (dataSync: local file processing) that compresses the queued images one by one,
 * so it keeps going while the user is in other apps or the screen is off. Originals are never touched
 * here: each checked copy is recorded in {@link Originals}, and the user moves originals to the trash
 * from the app afterwards. START_NOT_STICKY: if the process is killed, finished copies stay valid.
 */
public final class CompressionService extends Service {
    static final String ACTION_START = "com.storageplanner.app.action.COMPRESS";
    static final String ACTION_CANCEL = "com.storageplanner.app.action.COMPRESS_CANCEL";

    private static final String CH_PROGRESS = "compress", CH_RESULT = "compress_done";
    private static final int ID_PROGRESS = 11, ID_RESULT = 12;
    private static final long WAKE_MS = 30 * 60 * 1000L;      // renewed every file

    private static boolean workerActive;                      // main thread only
    private long lastNotify;
    private int lastStartId;

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        lastStartId = startId;
        if (ACTION_CANCEL.equals(action)) {
            CompressionState.cancelled = true;
            if (!workerActive) stopSelf(startId);
            else notifyProgress(true);
            return START_NOT_STICKY;
        }
        goForeground(progressNotification());
        if (workerActive || intent == null || !CompressionState.running) {
            if (!workerActive) stopSelf(startId);
            return START_NOT_STICKY;
        }
        workerActive = true;
        final String technique = CompressionState.technique;
        final List<FileItem> files = new ArrayList<FileItem>(CompressionState.queue);
        new Thread(new Runnable() { public void run() { work(technique, files); } }, "compress").start();
        return START_NOT_STICKY;
    }

    /** Also used directly (in this process) if the system refuses the foreground service. */
    static void run(Context c, String technique, List<FileItem> files, Runnable onFile) {
        File work = new File(c.getCacheDir(), "compress");
        work.mkdirs();
        int hint = -1;
        List<String> scan = new ArrayList<String>();
        try {
            for (FileItem f : files) {
                if (CompressionState.cancelled) break;
                ImageCompressor.Outcome o = ImageCompressor.compress(f, technique, hint, work);
                if (o.ok) {
                    hint = o.quality >= 0 ? o.quality : hint;
                    CompressionState.compressed++;
                    CompressionState.before += o.before;
                    CompressionState.after += o.after;
                    Originals.add(c, f.abs, o.output);
                    scan.add(o.output);
                    LabLog.line(c, "Compressed " + f.rel + ": " + Report.size(o.before) + " -> " + Report.size(o.after)
                            + (o.quality >= 0 ? " at quality " + o.quality : " (lossless)"));
                } else {
                    CompressionState.skip(o.skipped);
                    LabLog.line(c, "Skipped " + f.rel + ": " + o.skipped);
                }
                CompressionState.done++;
                if (scan.size() >= 25) { scan(c, scan); scan = new ArrayList<String>(); }
                CompressionState.progress();
                if (onFile != null) onFile.run();
            }
        } finally {
            scan(c, scan);
            CompressionState.finished();
        }
    }

    /** Lets galleries see the new copies. */
    private static void scan(Context c, List<String> paths) {
        if (paths.isEmpty()) return;
        try { MediaScannerConnection.scanFile(c, paths.toArray(new String[0]), null, null); } catch (Throwable ignored) {}
    }

    private void work(String technique, List<FileItem> files) {
        PowerManager.WakeLock wl = null;
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "StoragePlanner:compress");
                wl.setReferenceCounted(false);
                wl.acquire(WAKE_MS);
            }
            final PowerManager.WakeLock fwl = wl;
            LabLog.line(getApplicationContext(), "Compression started: " + technique + ", " + files.size() + " files, encoder " + HeifEncoder.describe());
            run(getApplicationContext(), technique, files, new Runnable() {
                public void run() {
                    if (fwl != null) fwl.acquire(WAKE_MS);
                    notifyProgress(false);
                }
            });
        } catch (Throwable t) {
            CompressionState.skip("stopped by an error (" + t.getClass().getSimpleName() + ")");
            CompressionState.finished();
        } finally {
            try { if (wl != null && wl.isHeld()) wl.release(); } catch (Throwable ignored) {}
            AnalysisState.UI.post(new Runnable() { public void run() { done(); } });
        }
    }

    private void done() {
        workerActive = false;
        stopForeground(STOP_FOREGROUND_REMOVE);
        if (CompressionState.listener == null) try {         // the visible app already shows the outcome
            NotificationManager nm = nm();
            if (nm != null) {
                String t = CompressionState.technique;
                Notification.Builder b = builder(CH_RESULT).setAutoCancel(true).setContentIntent(openApp())
                        .setSmallIcon(android.R.drawable.stat_notify_sdcard)
                        .setContentTitle((CompressionState.cancelled ? "Compression stopped: " : "Compression finished: ")
                                + CompressionState.compressed + " " + Compression.noun(t, CompressionState.compressed)
                                + ", " + Report.size(CompressionState.saved()) + " smaller")
                        .setContentText("Tap to move the originals to the trash. Nothing has been deleted yet.");
                nm.notify(ID_RESULT, b.build());
            }
        } catch (Throwable ignored) {}
        stopSelf(lastStartId);
    }

    @Override public void onDestroy() {
        if (workerActive) CompressionState.cancelled = true;
        super.onDestroy();
    }

    // ------------------------------------------------------------------ notifications
    private void goForeground(Notification n) {
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID_PROGRESS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else startForeground(ID_PROGRESS, n);
    }

    private void notifyProgress(boolean force) {
        long now = SystemClock.uptimeMillis();
        if (!force && now - lastNotify < 500) return;
        lastNotify = now;
        try { NotificationManager nm = nm(); if (nm != null) nm.notify(ID_PROGRESS, progressNotification()); } catch (Throwable ignored) {}
    }

    private Notification progressNotification() {
        boolean cancelling = CompressionState.cancelled;
        int d = CompressionState.done, t = CompressionState.total;
        String text = cancelling ? "Stopping after this file…"
                : d + " of " + t + " · " + Report.size(CompressionState.saved()) + " smaller so far";
        Notification.Builder b = builder(CH_PROGRESS)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("Compressing " + Compression.noun(CompressionState.technique, 2))
                .setContentText(text)
                .setOngoing(true).setShowWhen(false).setOnlyAlertOnce(true)
                .setContentIntent(openApp())
                .setProgress(Math.max(t, 1), Math.min(d, t), t <= 0);
        if (!cancelling) {
            Intent ci = new Intent(this, CompressionService.class).setAction(ACTION_CANCEL);
            PendingIntent cp = PendingIntent.getService(this, 2, ci, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            b.addAction(new Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                    "Stop", cp).build());
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
        Intent i = new Intent(this, MainActivity.class).setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        return PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private NotificationManager nm() { return (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE); }

    private static void ensureChannels(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null || nm.getNotificationChannel(CH_PROGRESS) != null) return;
        NotificationChannel p = new NotificationChannel(CH_PROGRESS, "Compression progress", NotificationManager.IMPORTANCE_LOW);
        p.setDescription("Shown while Storage Planner compresses images in the background");
        p.setShowBadge(false);
        NotificationChannel r = new NotificationChannel(CH_RESULT, "Compression finished", NotificationManager.IMPORTANCE_DEFAULT);
        r.setDescription("Tells you when compression is done and originals can be moved to the trash");
        nm.createNotificationChannel(p);
        nm.createNotificationChannel(r);
    }

    static void clearResultNotification(Context c) {
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(ID_RESULT);
        } catch (Throwable ignored) {}
    }
}
