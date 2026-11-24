package com.storageplanner.app;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import com.storageplanner.core.Version;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * The only code in the app that uses the network. It asks GitHub for the latest release of this project
 * (one GET, nothing about the phone or its files is sent), downloads the release's APK only from this
 * project's release address, checks its SHA-256 against the one published with the release, and hands it to
 * Android's installer. Android itself then checks that the APK is signed with the same key as the installed
 * app and asks the user to confirm. Everything runs on a background thread; results are posted to the UI thread.
 */
final class Updater {
    static final String REPO = "pncodes10/storage-planner";
    private static final String API = "https://api.github.com/repos/" + REPO + "/releases/latest";
    private static final String DOWNLOADS = "https://github.com/" + REPO + "/releases/download/";
    private static final long MAX_APK = 300L << 20;
    private static final String PREFS = "update";

    /** A newer release. */
    static final class Release {
        String version, notes, apkUrl, apkName, sha256;
        long size;
    }

    interface CheckListener { void done(Release newer, String error); }          // both null = up to date
    interface DownloadListener { void progress(long done, long total); void done(File apk, String error); }

    private static volatile boolean busy;

    static boolean busy() { return busy; }

    static boolean autoCheck(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("auto_check", true); }
    static void setAutoCheck(Context c, boolean on) { c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("auto_check", on).apply(); }

    static String installedVersion(Context c) {
        try { return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName; }
        catch (Throwable t) { return "0"; }
    }

    private static long codeOf(PackageInfo p) { return Build.VERSION.SDK_INT >= 28 ? p.getLongVersionCode() : p.versionCode; }

    static File dir(Context c) { return new File(c.getCacheDir(), "updates"); }

    /** Removes downloaded installers left from earlier updates. */
    static void cleanUp(Context c) {
        File[] fs = dir(c).listFiles();
        if (fs != null) for (File f : fs) f.delete();
    }

    // ------------------------------------------------------------------ check
    static void check(final Context ctx, final CheckListener l) {
        if (busy) return;
        busy = true;
        final Context app = ctx.getApplicationContext();
        new Thread(new Runnable() {
            public void run() {
                Release r = null; String err = null;
                try { r = fetchLatest(app); }
                catch (Throwable t) { err = "Could not check for updates (" + (t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName()) + ")."; }
                final Release fr = r; final String fe = err;
                AnalysisState.UI.post(new Runnable() { public void run() { busy = false; l.done(fr, fe); } });
            }
        }, "update-check").start();
    }

    private static Release fetchLatest(Context app) throws Exception {
        JSONObject j = new JSONObject(new String(get(API, 1 << 20), StandardCharsets.UTF_8));
        String tag = j.optString("tag_name", "");
        if (j.optBoolean("draft") || j.optBoolean("prerelease") || !Version.isNewer(tag, installedVersion(app))) return null;
        JSONArray assets = j.optJSONArray("assets");
        if (assets == null) throw new Exception("the release has no files");
        Release r = new Release();
        r.version = tag.startsWith("v") ? tag.substring(1) : tag;
        r.notes = j.optString("body", "").trim();
        String shaUrl = null;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject a = assets.getJSONObject(i);
            String name = a.optString("name"), url = a.optString("browser_download_url");
            if (!url.startsWith(DOWNLOADS + tag + "/") || name.contains("/")) continue;
            if (name.endsWith(".apk") && r.apkUrl == null) { r.apkUrl = url; r.apkName = name; r.size = a.optLong("size"); }
            else if (name.endsWith(".apk.sha256")) shaUrl = url;
        }
        if (r.apkUrl == null || shaUrl == null) throw new Exception("the release has no installer or checksum");
        String line = new String(get(shaUrl, 4096), StandardCharsets.UTF_8).trim().split("\\s+")[0].toLowerCase(Locale.US);
        if (!line.matches("[0-9a-f]{64}")) throw new Exception("the checksum file is unreadable");
        r.sha256 = line;
        return r;
    }

    private static byte[] get(String url, int max) throws Exception {
        HttpURLConnection c = open(url);
        try {
            if (c.getResponseCode() != 200) throw new Exception("GitHub answered " + c.getResponseCode());
            InputStream in = c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) > 0) { out.write(buf, 0, n); if (out.size() > max) throw new Exception("answer too large"); }
            return out.toByteArray();
        } finally { c.disconnect(); }
    }

    private static HttpURLConnection open(String url) throws Exception {
        if (!url.startsWith("https://")) throw new Exception("not https");
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setRequestProperty("User-Agent", "StoragePlanner-updater");
        return c;
    }

    // ------------------------------------------------------------------ download
    static void download(final Context ctx, final Release r, final DownloadListener l) {
        if (busy) return;
        busy = true;
        final Context app = ctx.getApplicationContext();
        new Thread(new Runnable() {
            public void run() {
                File out = null; String err = null;
                try { out = fetchApk(app, r, l); }
                catch (Throwable t) { err = "Download failed (" + (t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName()) + ")."; }
                final File fo = out; final String fe = err;
                AnalysisState.UI.post(new Runnable() { public void run() { busy = false; l.done(fo, fe); } });
            }
        }, "update-download").start();
    }

    private static File fetchApk(Context app, Release r, final DownloadListener l) throws Exception {
        File d = dir(app); cleanUp(app); d.mkdirs();
        File part = new File(d, "update.part"), apk = new File(d, "update.apk");
        HttpURLConnection c = open(r.apkUrl);
        try {
            if (c.getResponseCode() != 200) throw new Exception("GitHub answered " + c.getResponseCode());
            final long total = c.getContentLengthLong() > 0 ? c.getContentLengthLong() : r.size;
            if (total > MAX_APK) throw new Exception("file too large");
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            InputStream in = c.getInputStream();
            FileOutputStream fo = new FileOutputStream(part);
            try {
                byte[] buf = new byte[1 << 16]; int n; long done = 0, lastPost = 0;
                while ((n = in.read(buf)) > 0) {
                    fo.write(buf, 0, n); md.update(buf, 0, n); done += n;
                    if (done > MAX_APK) throw new Exception("file too large");
                    long now = System.currentTimeMillis();
                    if (now - lastPost > 300) {
                        lastPost = now; final long dn = done;
                        AnalysisState.UI.post(new Runnable() { public void run() { l.progress(dn, total); } });
                    }
                }
            } finally { fo.close(); }
            StringBuilder hex = new StringBuilder();
            for (byte b : md.digest()) hex.append(String.format(Locale.US, "%02x", b));
            if (!hex.toString().equals(r.sha256)) throw new Exception("checksum does not match, the file was not kept");
        } finally { c.disconnect(); }
        if (!part.renameTo(apk)) throw new Exception("could not save the file");
        // It must be this app, and newer than the installed one (Android also checks the signing key on install)
        PackageManager pm = app.getPackageManager();
        PackageInfo info = pm.getPackageArchiveInfo(apk.getPath(), 0);
        PackageInfo mine = pm.getPackageInfo(app.getPackageName(), 0);
        if (info == null || !app.getPackageName().equals(info.packageName) || codeOf(info) <= codeOf(mine)) {
            apk.delete();
            throw new Exception("the file is not a newer version of this app");
        }
        return apk;
    }

    // ------------------------------------------------------------------ install
    /** Opens Android's installer for the verified file. Returns a message when the user must first allow installs. */
    static String install(MainActivity a, File apk) {
        if (Build.VERSION.SDK_INT >= 26 && !a.getPackageManager().canRequestPackageInstalls()) {
            try {
                a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.getPackageName())));
            } catch (Throwable t) { /* the message below tells the user where to go */ }
            return "Allow Storage Planner to install updates in the screen that opened, then tap Check for updates again.";
        }
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(FileAccessProvider.updateUri(apk.getName()), "application/vnd.android.package-archive");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        try { a.startActivity(i); return null; }
        catch (Throwable t) { return "Could not open the installer."; }
    }
}
