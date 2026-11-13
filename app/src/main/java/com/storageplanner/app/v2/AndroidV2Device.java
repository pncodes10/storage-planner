package com.storageplanner.app.v2;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.os.BatteryManager;
import android.os.Build;
import android.os.PowerManager;
import android.os.StatFs;
import android.util.AtomicFile;

import com.storageplanner.v2.device.*;
import com.storageplanner.v2.discovery.PosixStorageAccess;
import com.storageplanner.v2.model.AccessGrant;
import com.storageplanner.v2.model.AccessMode;
import com.storageplanner.v2.model.DecodeStatus;
import com.storageplanner.v2.model.Tri;
import com.storageplanner.v2.model.VolumeKind;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.*;

/**
 * Android implementation of the v2 device interfaces (ARCHITECTURE 3, 13; Phase A). Access mode B only: the shared-storage root
 * the classic AndroidPlatform scans, read through the android-free PosixStorageAccess, with Android/data and Android/obb reported
 * as restricted. Anything the platform cannot tell is the v2 "unknown" value, never a guess. Never deletes, renames or writes
 * user files: the only writes are the store snapshot in getFilesDir()/v2 through android.util.AtomicFile.
 */
public final class AndroidV2Device implements V2Device {
    public static final String VOLUME_ID = "primary";
    private final Context ctx;
    private final StorageAccess storage;
    private final Clock clock;
    private final ResourceMonitor resources;
    private final BlobStore blobs;
    private final MediaDecoder media;
    private final InstalledApps apps;

    /** apps: the installed set the classic AndroidPlatform gathered (converted by the caller); availability as seen by it. */
    public AndroidV2Device(Context c, String storageRoot, List<InstalledApp> appList, Availability appAvailability) {
        ctx = c.getApplicationContext();
        PosixStorageAccess.Root r = new PosixStorageAccess.Root();
        r.volumeId = VOLUME_ID; r.kind = VolumeKind.PRIMARY_SHARED; r.label = "Internal storage"; r.path = storageRoot;
        r.fileKeyStable = Tri.FALSE;          // FUSE / sdcardfs inode stability not measured yet (ARCH C2): FALSE is the safe default
        r.caseInsensitive = Tri.UNKNOWN;
        r.restrictedPrefixes = Arrays.asList("Android/data", "Android/obb");
        storage = new PosixStorageAccess(Collections.singletonList(r),
                Collections.singletonList(AccessGrant.readAll(VOLUME_ID, "", AccessMode.B_ALL_FILES)));
        clock = new Clock() {
            public long nowMillis() { return System.currentTimeMillis(); }
            public long monotonicNanos() { return android.os.SystemClock.elapsedRealtimeNanos(); }
        };
        resources = new Resources(ctx, storageRoot);
        blobs = new AndroidBlobStore(storeDir(ctx));
        media = new BitmapDecoder(storage);
        apps = new Apps(ctx, appList, appAvailability);
    }

    /** The v2 store's directory getFilesDir()/v2 (app-private), the same blobs a device would use; for a store reset only. */
    public static File storeDir(Context c) { return new File(c.getFilesDir(), "v2"); }
    public static BlobStore privateBlobs(Context c) { return new AndroidBlobStore(storeDir(c)); }

    public StorageAccess storage() { return storage; }
    public Clock clock() { return clock; }
    public ResourceMonitor resources() { return resources; }
    public BlobStore blobs() { return blobs; }
    public MediaDecoder media() { return media; }
    public InstalledApps apps() { return apps; }

    // ------------------------------------------------------------------ resources
    static final class Resources implements ResourceMonitor {
        private final Context ctx; private final String root;
        Resources(Context c, String root) { ctx = c; this.root = root; }
        public ResourceState sample() {
            ResourceState s = new ResourceState();
            Runtime rt = Runtime.getRuntime();
            s.heapMaxBytes = rt.maxMemory();
            s.heapFreeBytes = rt.maxMemory() - (rt.totalMemory() - rt.freeMemory());
            s.cpuCount = rt.availableProcessors();
            try { s.storageFreeBytes = new StatFs(root).getAvailableBytes(); } catch (RuntimeException ignored) { /* unknown */ }
            try {
                BatteryManager bm = (BatteryManager) ctx.getSystemService(Context.BATTERY_SERVICE);
                if (bm != null) {
                    int p = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
                    if (p >= 0 && p <= 100) s.batteryPercent = p;
                    s.charging = bm.isCharging() ? Tri.TRUE : Tri.FALSE;
                }
            } catch (RuntimeException ignored) { /* unknown */ }
            if (Build.VERSION.SDK_INT >= 29) {
                try {
                    PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
                    if (pm != null) s.thermal = thermal(pm.getCurrentThermalStatus());
                } catch (RuntimeException ignored) { /* unknown */ }
            }
            return s;
        }
        static Thermal thermal(int t) {
            switch (t) {
                case 0: return Thermal.NONE;
                case 1: return Thermal.LIGHT;
                case 2: return Thermal.MODERATE;
                case 3: return Thermal.SEVERE;
                case 4: case 5: case 6: return Thermal.CRITICAL;
                default: return Thermal.UNKNOWN;
            }
        }
        public DeviceProfile profile() {
            try {
                ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
                if (am != null && am.isLowRamDevice()) return DeviceProfile.LOW;
            } catch (RuntimeException ignored) { /* default */ }
            return DeviceProfile.BALANCED;
        }
    }

    // ------------------------------------------------------------------ blobs (app-private; AtomicFile for atomic replace)
    static final class AndroidBlobStore implements BlobStore {
        private final File dir;
        AndroidBlobStore(File dir) { this.dir = dir; if (!dir.isDirectory()) dir.mkdirs(); }
        private File f(String name) { return new File(dir, name); }
        public InputStream read(String name) throws IOException {
            AtomicFile af = new AtomicFile(f(name));
            if (!f(name).exists() && !new File(dir, name + ".bak").exists()) return null;
            return new BufferedInputStream(af.openRead(), 1 << 16);
        }
        public OutputStream append(String name) throws IOException { return new FileOutputStream(f(name), true); }
        public void sync(String name) throws IOException {
            FileOutputStream o = new FileOutputStream(f(name), true);
            try { o.getFD().sync(); } finally { o.close(); }
        }
        public void truncate(String name) throws IOException {
            FileOutputStream o = new FileOutputStream(f(name), false);
            try { o.getFD().sync(); } finally { o.close(); }
        }
        public long length(String name) { File x = f(name); return x.exists() ? x.length() : -1; }
        /** Segments of the disk store are plain files (not AtomicFiles); read-only handle, no delete or rename. */
        public RandomRead openRead(String name) throws IOException {
            File x = f(name);
            if (!x.exists()) return null;
            final java.io.RandomAccessFile raf = new java.io.RandomAccessFile(x, "r");
            return new RandomRead() {
                public synchronized int read(long pos, byte[] b, int off, int len) throws IOException {
                    if (len == 0) return 0;
                    raf.seek(pos);
                    return raf.read(b, off, len);
                }
                public long length() throws IOException { return raf.length(); }
                public void close() throws IOException { raf.close(); }
            };
        }
        public AtomicWrite replace(String name) throws IOException {
            final AtomicFile af = new AtomicFile(f(name));
            final FileOutputStream fos = af.startWrite();
            final BufferedOutputStream out = new BufferedOutputStream(fos, 1 << 16);
            return new AtomicWrite() {
                boolean done;
                public OutputStream stream() { return out; }
                public void commit() throws IOException {
                    out.flush();
                    af.finishWrite(fos);     // fsync + atomic replace inside the framework
                    done = true;
                }
                public void abort() { if (!done) { done = true; af.failWrite(fos); } }
            };
        }
    }

    // ------------------------------------------------------------------ media (BitmapFactory with inSampleSize, decision A22)
    static final class BitmapDecoder implements MediaDecoder {
        private final StorageAccess storage;
        private final Set<String> types;
        BitmapDecoder(StorageAccess s) {
            storage = s;
            Set<String> t = new TreeSet<String>(Arrays.asList("jpeg", "png", "gif", "bmp", "webp"));
            if (Build.VERSION.SDK_INT >= 28) { t.add("heic"); t.add("heif"); }
            if (Build.VERSION.SDK_INT >= 31) t.add("avif");
            types = Collections.unmodifiableSet(t);
        }
        public Set<String> decodableTypes() { return types; }
        public DecodedImage videoFrame(String v, String p, long t, int e) { return DecodedImage.failed(DecodeStatus.UNSUPPORTED_FORMAT, "video frames: Phase 6"); }

        public DecodedImage decode(String volumeId, String path, int longEdge, long maxPixels, boolean wantArgb) {
            String local = storage.localPath(volumeId, path);
            if (local == null) return DecodedImage.failed(DecodeStatus.IO_ERROR, "not path-addressable");
            if (!new File(local).canRead()) return DecodedImage.failed(DecodeStatus.IO_ERROR, "not readable");
            Bitmap bm = null;
            try {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(local, o);
                int w = o.outWidth, h = o.outHeight;
                if (w <= 0 || h <= 0) return DecodedImage.failed(DecodeStatus.CORRUPT, "no bounds");
                if ((long) w * h > maxPixels) { DecodedImage d = DecodedImage.failed(DecodeStatus.TOO_LARGE, w + "x" + h); d.origW = w; d.origH = h; return d; }
                int sample = 1, edge = Math.max(1, longEdge);
                while (Math.max(w, h) / (sample * 2) >= edge) sample *= 2;    // decode near the size v2 needs, never below it
                o = new BitmapFactory.Options();
                o.inSampleSize = sample;
                o.inPreferredConfig = Bitmap.Config.ARGB_8888;
                bm = BitmapFactory.decodeFile(local, o);
                if (bm == null) return DecodedImage.failed(DecodeStatus.CORRUPT, "decode returned null");
                int orientation = 1;
                try { orientation = new ExifInterface(local).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1); } catch (IOException | RuntimeException ignored) { /* 1 */ }
                if (orientation < 1 || orientation > 8) orientation = 1;
                double s = Math.min(1.0, edge / (double) Math.max(bm.getWidth(), bm.getHeight()));
                Matrix m = new Matrix();
                m.postScale((float) s, (float) s);
                switch (orientation) {
                    case 2: m.postScale(-1, 1); break;
                    case 3: m.postRotate(180); break;
                    case 4: m.postScale(1, -1); break;
                    case 5: m.postRotate(90); m.postScale(-1, 1); break;
                    case 6: m.postRotate(90); break;
                    case 7: m.postRotate(270); m.postScale(-1, 1); break;
                    case 8: m.postRotate(270); break;
                    default: break;
                }
                Bitmap sc = Bitmap.createBitmap(bm, 0, 0, bm.getWidth(), bm.getHeight(), m, true);
                int ow = sc.getWidth(), oh = sc.getHeight();
                int[] px = new int[ow * oh];
                sc.getPixels(px, 0, ow, 0, 0, ow, oh);
                if (sc != bm) sc.recycle();
                DecodedImage d = new DecodedImage();
                d.status = DecodeStatus.OK; d.w = ow; d.h = oh; d.origW = w; d.origH = h; d.orientation = orientation;
                d.luma = new int[px.length];
                for (int i = 0; i < px.length; i++) {
                    int c = px[i];
                    int a = (c >>> 24) & 255, rr = (c >> 16) & 255, gg = (c >> 8) & 255, bb = c & 255;
                    if (a < 255) { rr = (rr * a + 255 * (255 - a)) / 255; gg = (gg * a + 255 * (255 - a)) / 255; bb = (bb * a + 255 * (255 - a)) / 255; }  // on white, like the JVM
                    d.luma[i] = (299 * rr + 587 * gg + 114 * bb) / 1000;
                }
                d.argb = wantArgb ? px : null;
                return d;
            } catch (OutOfMemoryError e) {
                return DecodedImage.failed(DecodeStatus.OUT_OF_MEMORY, "OOM");
            } catch (RuntimeException e) {
                return DecodedImage.failed(DecodeStatus.CORRUPT, String.valueOf(e.getClass().getSimpleName()));
            } finally {
                if (bm != null) bm.recycle();
            }
        }
    }

    // ------------------------------------------------------------------ installed apps
    static final class Apps implements InstalledApps {
        private final Context ctx; private final Availability availability;
        private final Map<String, InstalledApp> map = new TreeMap<String, InstalledApp>();
        Apps(Context c, List<InstalledApp> list, Availability a) {
            ctx = c; availability = list == null ? Availability.UNAVAILABLE : a;
            if (list != null) for (InstalledApp x : list) map.put(x.packageName, x);
        }
        public Availability availability() { return availability; }
        public List<InstalledApp> list() { return availability == Availability.UNAVAILABLE ? Collections.<InstalledApp>emptyList() : new ArrayList<InstalledApp>(map.values()); }
        public InstalledApp get(String pkg) { return pkg == null ? null : map.get(pkg); }
        public InstalledApp inspectArchive(String localPath) {
            try {
                PackageInfo pi = ctx.getPackageManager().getPackageArchiveInfo(localPath, 0);
                if (pi == null || pi.packageName == null) return null;
                InstalledApp a = new InstalledApp();
                a.packageName = pi.packageName; a.versionName = pi.versionName;
                a.versionCode = Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
                return a;
            } catch (RuntimeException e) { return null; }
        }
    }

    /** Converts the classic app list (AndroidPlatform.apps()); sizes/usage unknown when the platform could not read them. */
    public static List<InstalledApp> fromClassic(List<com.storageplanner.core.AppItem> classic, boolean statsAvailable, boolean usageAvailable) {
        List<InstalledApp> out = new ArrayList<InstalledApp>();
        for (com.storageplanner.core.AppItem c : classic) {
            InstalledApp a = new InstalledApp();
            a.packageName = c.pkg; a.label = c.label;
            a.versionCode = c.versionCode > 0 ? c.versionCode : -1;
            a.firstInstallTime = c.firstInstall > 0 ? c.firstInstall : Long.MIN_VALUE;
            a.system = c.isSystem ? Tri.TRUE : Tri.FALSE;
            a.hasLauncher = c.hasLauncher ? Tri.TRUE : Tri.FALSE;
            a.game = c.isGame ? Tri.TRUE : Tri.FALSE;
            // AndroidPlatform marks every app essential when usage access is missing: then "essential" is unknown, not true
            a.essential = usageAvailable ? (c.isEssential ? Tri.TRUE : Tri.FALSE) : Tri.UNKNOWN;
            if (statsAvailable) { a.codeBytes = c.codeBytes; a.dataBytes = c.dataBytes; a.cacheBytes = c.cacheBytes; }
            a.lastUsed = usageAvailable && c.lastUsed > 0 ? c.lastUsed : Long.MIN_VALUE;
            out.add(a);
        }
        return out;
    }

    /** True when the manifest's QUERY_ALL_PACKAGES makes the package list complete (API 30+ filters it otherwise). */
    public static Availability appAvailability(Context c) {
        if (Build.VERSION.SDK_INT < 30) return Availability.FULL;
        return c.checkSelfPermission("android.permission.QUERY_ALL_PACKAGES") == PackageManager.PERMISSION_GRANTED ? Availability.FULL : Availability.PARTIAL;
    }
}
