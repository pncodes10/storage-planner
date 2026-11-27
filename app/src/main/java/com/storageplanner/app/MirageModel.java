package com.storageplanner.app;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * Where Mirage's model file lives and how it gets there. The 22 MB model is not packed in the APK (so an app update
 * is only a few MB); it is downloaded once, on request, from Hugging Face at a pinned revision, checked against the
 * pinned SHA-256 (tools/mirage/files.sha256) and kept in the app's private files folder (getFilesDir()/mirage).
 * That folder survives app updates, so a new version finds the model already there; only a release that pins a
 * different model (MODEL_SHA256 changes) downloads it again, and the old copy is then removed.
 * The first analysis downloads it by itself (Wi-Fi or mobile data), and the Lab has a button for it.
 * Nothing about the phone or its files is sent; the request is one plain GET for a fixed address.
 */
final class MirageModel {
    static final String FILE = "mirage.cact";
    static final long SIZE = 21_600_000L;     // about (decimal MB, as the app shows it); only for progress and the free-space check
    private static final String MODEL_REV = "76c1e9e53381aca8fc3843c636216c96ff220122";
    static final String MODEL_SHA256 = "4249a0f7515272ff76b9414bec17d7e7daf84b15b66c7fb4f17fce1e66f9f237";
    static final String URL_MODEL = "https://huggingface.co/Cactus-Compute/mirage/resolve/" + MODEL_REV + "/" + FILE;
    private static final long MAX = 40L << 20;

    interface Listener { void progress(long done, long total); void done(String error); }   // error null = installed

    private static volatile boolean busy;

    private MirageModel() {}

    static boolean busy() { return busy; }

    static File dir(Context c) { return new File(c.getFilesDir(), "mirage"); }

    static File file(Context c) { return new File(dir(c), FILE); }

    /** True when a model file is saved. Its hash is checked when it is loaded (MirageEngine). */
    static boolean installed(Context c) { File f = file(c); return f.isFile() && f.length() > 0; }

    static String sha256(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        InputStream in = new FileInputStream(f);
        try {
            byte[] buf = new byte[1 << 16]; int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        } finally { in.close(); }
        return hex(md.digest());
    }

    private static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format(Locale.US, "%02x", x));
        return s.toString();
    }

    /** Removes the saved model (to free the space, or after a bad file). */
    static void remove(Context c) {
        File[] fs = dir(c).listFiles();
        if (fs != null) for (File f : fs) f.delete();
    }

    /** Progress of a download, called on the downloading thread. */
    interface Sink { void progress(long done, long total); boolean cancelled(); }

    private static final Object LOCK = new Object();

    /** The Lab's Download button: in the background, progress and the result on the UI thread. */
    static void download(final Context ctx, final Listener l) {     // while the analysis is downloading, this waits for it
        final Context app = ctx.getApplicationContext();
        new Thread(new Runnable() {
            public void run() {
                final long[] last = {0};
                final String err = downloadNow(app, new Sink() {
                    public void progress(final long done, final long total) {
                        long now = System.currentTimeMillis();
                        if (now - last[0] < 300) return;
                        last[0] = now;
                        AnalysisState.UI.post(new Runnable() { public void run() { l.progress(done, total); } });
                    }
                    public boolean cancelled() { return false; }
                });
                AnalysisState.UI.post(new Runnable() { public void run() { l.done(err); } });
            }
        }, "mirage-download").start();
    }

    /**
     * Downloads the model on this thread unless it is already saved (the analysis calls this before its Mirage pass, so
     * the first analysis fetches it; on Wi-Fi or mobile data). One download at a time: a second caller waits for the
     * first and then finds the model there. Returns null when the model is saved, else why not, in words.
     */
    static String downloadNow(Context ctx, Sink s) {
        Context app = ctx.getApplicationContext();
        synchronized (LOCK) {
            if (installed(app)) return null;
            busy = true;
            try { fetch(app, s); MirageEngine.reset(); return null; }
            catch (Throwable t) { return "download failed (" + (t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName()) + ")"; }
            finally { busy = false; }
        }
    }

    private static void fetch(Context app, Sink l) throws Exception {
        File d = dir(app); d.mkdirs();
        if (d.getUsableSpace() < SIZE + (8L << 20)) throw new Exception("not enough free space for the 22 MB model");
        File part = new File(d, FILE + ".part"), out = file(app);
        HttpURLConnection c = (HttpURLConnection) new URL(URL_MODEL).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", "StoragePlanner-model");
        try {
            if (c.getResponseCode() != 200) throw new Exception("the server answered " + c.getResponseCode());
            if (!c.getURL().getProtocol().equals("https")) throw new Exception("not https");
            long len = c.getContentLengthLong();
            final long total = len > 0 ? len : SIZE;
            if (total > MAX) throw new Exception("file too large");
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            InputStream in = c.getInputStream();
            FileOutputStream fo = new FileOutputStream(part);
            try {
                byte[] buf = new byte[1 << 16]; int n; long done = 0;
                while ((n = in.read(buf)) > 0) {
                    if (l.cancelled()) throw new Exception("cancelled");
                    fo.write(buf, 0, n); md.update(buf, 0, n); done += n;
                    if (done > MAX) throw new Exception("file too large");
                    l.progress(done, total);
                }
            } finally { fo.close(); }
            if (!hex(md.digest()).equals(MODEL_SHA256)) { part.delete(); throw new Exception("checksum does not match, the file was not kept"); }
        } catch (Exception e) {
            part.delete();
            throw e;
        } finally { c.disconnect(); }
        out.delete();
        if (!part.renameTo(out)) throw new Exception("could not save the file");
    }
}
