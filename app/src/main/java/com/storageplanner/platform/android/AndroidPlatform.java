package com.storageplanner.platform.android;

import android.app.AppOpsManager;
import android.app.usage.StorageStats;
import android.app.usage.StorageStatsManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.os.Build;
import android.os.Environment;
import android.os.Process;
import android.os.StatFs;
import android.os.storage.StorageManager;
import android.provider.Settings;
import android.provider.Telephony;
import android.telecom.TelecomManager;

import com.storageplanner.core.AppItem;
import com.storageplanner.core.FileItem;
import com.storageplanner.core.Platform;
import com.storageplanner.tools.faces.FaceNet;
import com.storageplanner.tools.faces.Faces;
import com.storageplanner.tools.ocr.TextRules;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Android implementation of the core's Platform. Read-only: it never modifies anything. */
public final class AndroidPlatform implements Platform {
    private final Context ctx;
    public boolean appStatsAvailable = true;   // false when usage access is not granted

    public AndroidPlatform(Context c) { this.ctx = c.getApplicationContext(); }

    public String storageRoot() { return Environment.getExternalStorageDirectory().getAbsolutePath(); }
    public int sdkInt() { return Build.VERSION.SDK_INT; }
    public long now() { return System.currentTimeMillis(); }

    public long totalBytes() {
        try {
            StorageStatsManager ssm = (StorageStatsManager) ctx.getSystemService(Context.STORAGE_STATS_SERVICE);
            return ssm.getTotalBytes(StorageManager.UUID_DEFAULT);
        } catch (Throwable t) {
            StatFs s = new StatFs(Environment.getDataDirectory().getAbsolutePath());
            return s.getTotalBytes();
        }
    }

    public long freeBytes() {
        try {
            StorageStatsManager ssm = (StorageStatsManager) ctx.getSystemService(Context.STORAGE_STATS_SERVICE);
            return ssm.getFreeBytes(StorageManager.UUID_DEFAULT);
        } catch (Throwable t) {
            StatFs s = new StatFs(Environment.getDataDirectory().getAbsolutePath());
            return s.getAvailableBytes();
        }
    }

    /** Usage access (PACKAGE_USAGE_STATS) is granted from Settings, not by a runtime dialog. */
    public static boolean hasUsageAccess(Context c) {
        try {
            AppOpsManager ops = (AppOpsManager) c.getSystemService(Context.APP_OPS_SERVICE);
            int mode;
            if (Build.VERSION.SDK_INT >= 29)
                mode = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.getPackageName());
            else
                mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.getPackageName());
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Throwable t) { return false; }
    }

    public static boolean hasFilesAccess(Context c) {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        return c.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    @SuppressWarnings("deprecation")
    public List<AppItem> apps() {
        List<AppItem> out = new ArrayList<AppItem>();
        PackageManager pm = ctx.getPackageManager();
        long now = System.currentTimeMillis();
        Map<String, UsageStats> usage = null;
        try {
            UsageStatsManager usm = (UsageStatsManager) ctx.getSystemService(Context.USAGE_STATS_SERVICE);
            usage = usm.queryAndAggregateUsageStats(now - 2L * 365 * 86400000L, now);
        } catch (Throwable ignored) {}
        StorageStatsManager ssm = (StorageStatsManager) ctx.getSystemService(Context.STORAGE_STATS_SERVICE);
        Set<String> essential = essentialPackages();

        List<ApplicationInfo> infos = pm.getInstalledApplications(0);
        for (ApplicationInfo ai : infos) {
            try {
                AppItem a = new AppItem(ai.packageName, String.valueOf(ai.loadLabel(pm)));
                a.isSystem = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                a.isGame = ai.category == ApplicationInfo.CATEGORY_GAME || (ai.flags & ApplicationInfo.FLAG_IS_GAME) != 0;
                a.hasLauncher = pm.getLaunchIntentForPackage(ai.packageName) != null;
                a.isEssential = essential.contains(ai.packageName);
                try {
                    PackageInfo pi = pm.getPackageInfo(ai.packageName, 0);
                    a.firstInstall = pi.firstInstallTime;
                    a.versionCode = Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
                } catch (Throwable ignored) {}
                if (usage != null) {
                    UsageStats us = usage.get(ai.packageName);
                    if (us != null) {
                        long last = us.getLastTimeUsed();
                        if (Build.VERSION.SDK_INT >= 29) last = Math.max(last, us.getLastTimeVisible());
                        if (last > 0) a.lastUsed = last;
                    }
                }
                try {
                    StorageStats st = ssm.queryStatsForPackage(ai.storageUuid, ai.packageName, Process.myUserHandle());
                    a.codeBytes = st.getAppBytes();
                    a.cacheBytes = st.getCacheBytes();
                    a.dataBytes = Math.max(0, st.getDataBytes() - st.getCacheBytes());
                } catch (SecurityException se) {
                    appStatsAvailable = false;
                }
                // without usage access we cannot tell idle apps from active ones: never suggest removing
                if (usage == null || usage.isEmpty()) a.isEssential = true;
                out.add(a);
            } catch (Throwable ignored) {}
        }
        return out;
    }

    /** Apps the planner must never suggest removing: this app, keyboard, home screen, SMS, dialer. */
    private Set<String> essentialPackages() {
        Set<String> s = new HashSet<String>();
        s.add(ctx.getPackageName());
        try {
            String ime = Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
            if (ime != null && ime.contains("/")) s.add(ime.substring(0, ime.indexOf('/')));
        } catch (Throwable ignored) {}
        try {
            Intent home = new Intent(Intent.ACTION_MAIN); home.addCategory(Intent.CATEGORY_HOME);
            ResolveInfo ri = ctx.getPackageManager().resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
            if (ri != null && ri.activityInfo != null) s.add(ri.activityInfo.packageName);
        } catch (Throwable ignored) {}
        try { String sms = Telephony.Sms.getDefaultSmsPackage(ctx); if (sms != null) s.add(sms); } catch (Throwable ignored) {}
        try {
            TelecomManager tm = (TelecomManager) ctx.getSystemService(Context.TELECOM_SERVICE);
            if (tm != null && tm.getDefaultDialerPackage() != null) s.add(tm.getDefaultDialerPackage());
        } catch (Throwable ignored) {}
        return s;
    }

    public boolean imageSize(String path, int[] dims) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, o);
        if (o.outWidth <= 0 || o.outHeight <= 0) return false;
        dims[0] = o.outWidth; dims[1] = o.outHeight;
        return true;
    }

    /** Decode at ~1024 px long edge (power-of-two subsampling, then exact scale), honour EXIF rotation. */
    public int[] luma(String path, int[] dims) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, o);
        int W = o.outWidth, H = o.outHeight;
        if (W <= 0 || H <= 0) return null;
        int sample = 1;
        while (Math.max(W, H) / (sample * 2) >= 1024) sample *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        o.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bm = BitmapFactory.decodeFile(path, o);
        if (bm == null) return null;
        try {
            int rot = 0;
            try {
                int ori = new ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
                if (ori == ExifInterface.ORIENTATION_ROTATE_90) rot = 90;
                else if (ori == ExifInterface.ORIENTATION_ROTATE_180) rot = 180;
                else if (ori == ExifInterface.ORIENTATION_ROTATE_270) rot = 270;
            } catch (Throwable ignored) {}
            double s = 1024.0 / Math.max(bm.getWidth(), bm.getHeight());
            Matrix m = new Matrix();
            m.postScale((float) s, (float) s);
            if (rot != 0) m.postRotate(rot);
            Bitmap sc = Bitmap.createBitmap(bm, 0, 0, bm.getWidth(), bm.getHeight(), m, true);
            int w = sc.getWidth(), h = sc.getHeight();
            int[] px = new int[w * h];
            sc.getPixels(px, 0, w, 0, 0, w, h);
            if (sc != bm) sc.recycle();
            int[] l = new int[w * h];
            for (int i = 0; i < px.length; i++) {
                int p = px[i];
                l[i] = (299 * ((p >> 16) & 255) + 587 * ((p >> 8) & 255) + 114 * (p & 255)) / 1000;
            }
            boolean swap = rot == 90 || rot == 270;
            dims[0] = swap ? H : W; dims[1] = swap ? W : H; dims[2] = w; dims[3] = h;
            return l;
        } finally {
            bm.recycle();
        }
    }

    /** Upright ARGB at long edge ~longEdge (never enlarged): power-of-two subsampling, then exact scale. */
    public int[] argb(String path, int longEdge, int[] dims) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, o);
        int W = o.outWidth, H = o.outHeight;
        if (W <= 0 || H <= 0) return null;
        int sample = 1;
        while (Math.max(W, H) / (sample * 2) >= longEdge) sample *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        o.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bm = BitmapFactory.decodeFile(path, o);
        if (bm == null) return null;
        try {
            int rot = rotation(path);
            double s = Math.min(1.0, (double) longEdge / Math.max(bm.getWidth(), bm.getHeight()));
            Matrix m = new Matrix();
            m.postScale((float) s, (float) s);
            if (rot != 0) m.postRotate(rot);
            Bitmap sc = Bitmap.createBitmap(bm, 0, 0, bm.getWidth(), bm.getHeight(), m, true);
            int w = sc.getWidth(), h = sc.getHeight();
            int[] px = new int[w * h];
            sc.getPixels(px, 0, w, 0, 0, w, h);
            if (sc != bm) sc.recycle();
            boolean swap = rot == 90 || rot == 270;
            dims[0] = swap ? H : W; dims[1] = swap ? W : H; dims[2] = w; dims[3] = h;
            return px;
        } finally {
            bm.recycle();
        }
    }

    private static int rotation(String path) {
        try {
            int ori = new ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            if (ori == ExifInterface.ORIENTATION_ROTATE_90) return 90;
            if (ori == ExifInterface.ORIENTATION_ROTATE_180) return 180;
            if (ori == ExifInterface.ORIENTATION_ROTATE_270) return 270;
        } catch (Throwable ignored) {}
        return 0;
    }

    // ---- face detector: YuNet in plain Java (assets/faces/yunet.bin), loaded on first use
    private FaceNet faceNet;
    private boolean faceNetFailed;

    private synchronized FaceNet faceNet() {
        if (faceNet != null || faceNetFailed) return faceNet;
        java.io.InputStream in = null;
        try {
            in = ctx.getAssets().open("faces/yunet.bin");
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream(1 << 18);
            byte[] buf = new byte[65536]; int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            faceNet = FaceNet.load(bo.toByteArray());
        } catch (Throwable t) {
            faceNetFailed = true;
            android.util.Log.w("StoragePlanner", "face model unavailable: " + t);
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignored) {}
        }
        return faceNet;
    }

    /** Faces with the bundled YuNet detector (on the phone; nothing leaves it). Falls back to Android's own detector. */
    public float[] faces(String path, int[] argb, int w, int h) {
        FaceNet net = faceNet();
        if (net == null) return otherFaces(path, argb, w, h);
        try { return Faces.detect(net, argb, w, h); } catch (Throwable t) { return otherFaces(path, argb, w, h); }
    }

    public String otherFacesName() { return "android.media.FaceDetector (built into Android)"; }

    /**
     * Android's built-in detector (android.media.FaceDetector), the one used up to version 1.7. It needs an RGB_565
     * bitmap of even width, reports only the point between the eyes and the eye distance, and its confidence is
     * nearly always 0.51 - hence the switch to YuNet.
     */
    public float[] otherFaces(String path, int[] argb, int w, int h) {
        int ew = w & ~1;
        if (ew < 32 || h < 32) return new float[0];
        Bitmap b = null;
        try {
            b = Bitmap.createBitmap(ew, h, Bitmap.Config.RGB_565);
            b.setPixels(argb, 0, w, 0, 0, ew, h);
            android.media.FaceDetector fd = new android.media.FaceDetector(ew, h, MAX_FACES);
            android.media.FaceDetector.Face[] fs = new android.media.FaceDetector.Face[MAX_FACES];
            int n = fd.findFaces(b, fs);
            float[] out = new float[n * 4];
            android.graphics.PointF p = new android.graphics.PointF();
            for (int i = 0; i < n; i++) {
                fs[i].getMidPoint(p);
                out[i * 4] = p.x; out[i * 4 + 1] = p.y; out[i * 4 + 2] = fs[i].eyesDistance(); out[i * 4 + 3] = fs[i].confidence();
            }
            return Faces.fromMidpoints(out);
        } catch (Throwable t) {
            return null;
        } finally {
            if (b != null) b.recycle();
        }
    }

    private static final int MAX_FACES = 10;

    // ---- text reader (started on first use, closed by close())
    private OcrEngine ocr;
    private boolean ocrFailed;

    /** Reads the words in a picture with the bundled Tesseract (offline). */
    public com.storageplanner.tools.ocr.TextRules.Words readText(String path, int[] argb, int w, int h) {
        if (ocrFailed) return null;
        if (ocr == null) {
            ocr = new OcrEngine(ctx);
            if (!ocr.start(90000)) { ocrFailed = true; android.util.Log.w("StoragePlanner", "text reader unavailable: " + ocr.error()); ocr.close(); return null; }
        }
        Bitmap b = null;
        try {
            b = Bitmap.createBitmap(argb, w, h, Bitmap.Config.ARGB_8888);
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream(w * h / 4);
            b.compress(Bitmap.CompressFormat.JPEG, 92, bo);
            return ocr.read(bo.toByteArray(), 60000);
        } catch (Throwable t) {
            return null;
        } finally {
            if (b != null) b.recycle();
        }
    }

    /** Stops the text reader, if it was started. */
    public void close() { if (ocr != null) { ocr.close(); ocr = null; } }

    public boolean video(FileItem f) {
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(f.abs);
            f.bitrate = parse(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE));
            f.width = (int) parse(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            f.height = (int) parse(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            f.durationMs = parse(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
        } catch (Throwable t) {
            return false;
        } finally {
            try { mmr.release(); } catch (Throwable ignored) {}
        }
        MediaExtractor ex = new MediaExtractor();
        try {
            ex.setDataSource(f.abs);
            for (int i = 0; i < ex.getTrackCount(); i++) {
                String mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) { f.vcodec = mime; break; }
            }
        } catch (Throwable ignored) {
        } finally {
            try { ex.release(); } catch (Throwable ignored) {}
        }
        return f.vcodec != null;
    }

    public long audioBitrate(String path) {
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(path);
            return parse(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE));
        } catch (Throwable t) {
            return 0;
        } finally {
            try { mmr.release(); } catch (Throwable ignored) {}
        }
    }

    @SuppressWarnings("deprecation")
    public String[] apkInfo(String path) {
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageArchiveInfo(path, 0);
            if (pi == null) return null;
            long v = Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
            return new String[]{pi.packageName, Long.toString(v)};
        } catch (Throwable t) { return null; }
    }

    private static long parse(String s) {
        if (s == null) return 0;
        try { return Long.parseLong(s.trim()); } catch (NumberFormatException e) { return 0; }
    }
}
