package com.storageplanner.app;

import android.content.Context;
import android.os.Build;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * The Algorithm Lab's log: every line the Lab shows, with times, device and app details, in the app's private
 * storage (lab-log.txt). "Save log" copies it to Download/StoragePlanner so it can be shared for debugging.
 */
final class LabLog {
    private LabLog() {}

    static final String NAME = "lab-log.txt";
    private static final Object LOCK = new Object();

    static File file(Context c) { return new File(c.getFilesDir(), NAME); }

    static void line(Context c, String s) { write(c, s + "\n"); }

    static void error(Context c, String what, Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        write(c, "  ! " + what + " failed: " + t + "\n" + sw + "\n");
    }

    /** Session header: when, which app build, which phone, which WebView (the text reader runs in it). */
    static void header(Context c) {
        StringBuilder b = new StringBuilder("\n==== Algorithm Lab session " + now() + " ====\n");
        try {
            android.content.pm.PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            b.append("app ").append(pi.versionName).append(" (").append(pi.versionCode).append(")\n");
        } catch (Throwable ignored) {}
        b.append("phone ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append(" (").append(Build.DEVICE).append("), Android ")
         .append(Build.VERSION.RELEASE).append(" / API ").append(Build.VERSION.SDK_INT).append(", ").append(Runtime.getRuntime().availableProcessors())
         .append(" cores, max memory ").append(Runtime.getRuntime().maxMemory() / 1048576).append(" MB\n");
        try {
            android.content.pm.PackageInfo wv = android.webkit.WebView.getCurrentWebViewPackage();
            if (wv != null) b.append("webview ").append(wv.packageName).append(' ').append(wv.versionName).append('\n');
        } catch (Throwable ignored) {}
        write(c, b.toString());
    }

    static String now() { return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date()); }

    private static void write(Context c, String s) {
        synchronized (LOCK) {
            FileOutputStream out = null;
            try {
                out = new FileOutputStream(file(c), true);
                out.write(s.getBytes(StandardCharsets.UTF_8));
            } catch (Throwable ignored) {
            } finally {
                if (out != null) try { out.close(); } catch (Throwable ignored) {}
            }
        }
    }

    static long size(Context c) { return file(c).length(); }

    /** Starts the log afresh (overwrites; the app never deletes files). */
    static void clear(Context c) {
        synchronized (LOCK) {
            FileOutputStream out = null;
            try { out = new FileOutputStream(file(c), false); out.write(("log cleared " + now() + "\n").getBytes(StandardCharsets.UTF_8)); }
            catch (Throwable ignored) {}
            finally { if (out != null) try { out.close(); } catch (Throwable ignored) {} }
        }
    }

    /** Copies the log to Download/StoragePlanner/lab-log-<time>.txt and returns that file. */
    static File export(Context c) throws Exception {
        File dir = new File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "StoragePlanner");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new Exception("cannot create " + dir);
        File dst = new File(dir, "lab-log-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".txt");
        synchronized (LOCK) {
            java.io.FileInputStream in = new java.io.FileInputStream(file(c));
            FileOutputStream out = new FileOutputStream(dst);
            try {
                byte[] buf = new byte[65536]; int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            } finally { in.close(); out.close(); }
        }
        return dst;
    }
}
