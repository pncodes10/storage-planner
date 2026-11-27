package com.storageplanner.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Stage 1: walk shared storage, classify, find exact duplicates, extract media features. Read-only. */
public final class Scanner {

    public interface Progress {
        void update(String stage, int done, int total);
        boolean cancelled();
    }

    public static final class Result {
        public final List<FileItem> files = new ArrayList<FileItem>();
        public List<AppItem> apps = new ArrayList<AppItem>();
        public long total, free, now;
        public int unreadableDirs;
        public long scannedBytes;
        /** Set when only some pictures were analysed (Try on my pictures): what was picked, in words. Not saved with the results. */
        public String sample;
        /** Set when Mirage could not look at pictures in this scan: why, in words for the user. Not saved with the results. */
        public String mirageNote;
        /** The file list is complete and every file has its type; false when paused before that (nothing usable then). */
        public boolean prepared;
        /** Files analysed last time that were taken over as they were, and files of last time that are gone. Not saved. */
        public int resumed, gone;
    }

    private static final Set<String> MEDIA_AREAS = new HashSet<String>();
    static {
        String[] a = {"camera", "screenshot", "wa_image", "wa_sent", "wa_video", "movies", "download",
                      "music", "pictures"};
        for (String s : a) MEDIA_AREAS.add(s);
    }
    private static final Set<String> NO_DEDUP = new HashSet<String>();
    static {
        String[] a = {"trash", "thumbnails", "temp", "wa_status", "ext_cache", "ext_data", "residue",
                      "app_obb", "wa_old_backup", "telegram_media"};
        for (String s : a) NO_DEDUP.add(s);
    }

    /** Choices for one scan. */
    public static final class Options {
        /** Files from the last finished scan (by path): unchanged pictures keep their measurements and category. */
        public Map<String, FileItem> previous;
        /** Sort pictures into categories (QR, receipts, screenshots, people, ...). */
        public boolean sortPictures = true;
        /** Time allowed for reading text in pictures in this scan; the rest is read next time. */
        public long textBudgetMs = 20 * 60 * 1000L;
        /** Time allowed for looking at what photos show (scene, aesthetic score) in this scan; the rest is done next time. */
        public long sceneBudgetMs = 15 * 60 * 1000L;
        /** Time allowed for Mirage (v3: the mirage_* tags and v3_category) in this scan; the rest is done next time. 0 = off. */
        public long mirageBudgetMs = 20 * 60 * 1000L;
        /** Analyse only some pictures (Try on my pictures); null = everything. */
        public Sample.Limit limit;
        /** Reading text in pictures: how many pictures are sent to the reader at once (1 = one after the other). */
        public int textThreads = 1;
        /** Run the worker threads at the highest priority (the analysis then keeps its speed when the app is in the background). */
        public boolean highPriority;
        /** Threads for the per-picture stages (measuring, comparing similar photos, sorting); 1 = one after the other. */
        public int workers = 1;
        /** Analyse at most this many pictures, the newest (Settings, to try the flow quickly); 0 = all. Other files stay. */
        public int maxPictures;
        /** Time allowed for looking inside videos (VideoCheck) in this scan; the rest is done next time. 0 = off. */
        public long videoBudgetMs = 10 * 60 * 1000L;
        /** Leave out pictures smaller than this many bytes (Settings: too small to be worth the time); 0 = none. */
        public long minPictureBytes;
        /** Told what is analysed and, every checkpointMs at least, what is done so far; may be null. */
        public Live live;
        public long checkpointMs = 4000;
        /** Files analysed together; a pause takes effect between batches (a file cut short is done again next time). */
        public int batch = 12;
        /** Reading text: the reader worked once in this scan, and failed this many times in a row (no reader on the phone). */
        boolean readerWorked;
        int readerFails;
    }

    /** Watches an analysis as it goes (the app's progress screen). Called on the analysis thread, between files. */
    public interface Live {
        /** The biggest file of the next batch. */
        void looking(FileItem f);
        /** Between batches: r holds the files done so far (the rest are pending); nothing changes it during the call. */
        void checkpoint(Result r, int filesDone, int filesTotal, long bytesDone, long bytesTotal);
    }

    /**
     * The Settings limits on pictures: pictures under minBytes are left out, then only the newest max are kept (0 = no
     * limit). Only pictures in photo folders count; thumbnails, temp files, trash and other files are never touched. Returns how many pictures were left out by each limit:
     * {too small, over the count}.
     */
    public static int[] trimPictures(List<FileItem> files, int max, long minBytes) {
        int small = 0, over = 0;
        List<FileItem> pics = new ArrayList<FileItem>();
        for (java.util.Iterator<FileItem> it = files.iterator(); it.hasNext(); ) {
            FileItem f = it.next();
            if (!photo(f)) continue;
            if (minBytes > 0 && f.size < minBytes) { it.remove(); small++; continue; }
            pics.add(f);
        }
        if (max > 0 && pics.size() > max) {
            Set<FileItem> keep = new HashSet<FileItem>(Sample.newest(pics, max));
            for (java.util.Iterator<FileItem> it = files.iterator(); it.hasNext(); ) {
                FileItem f = it.next();
                if (photo(f) && !keep.contains(f)) { it.remove(); over++; }
            }
        }
        return new int[]{small, over};
    }

    /** A picture in a photo folder (camera, screenshots, chats, downloads ...): not a thumbnail, temp file or trash. */
    static boolean photo(FileItem f) {
        return f.area != null && (MEDIA_AREAS.contains(f.area) || f.area.startsWith("wa_")) && Sample.pictureName(f.name());
    }

    /** "newest 500 pictures, pictures under 100 KB left out" for the limits that left something out; null if none did. */
    public static String trimmed(int[] n, int max, long minBytes) {
        String s = null;
        if (n[1] > 0) s = "newest " + max + " pictures";
        if (n[0] > 0) s = (s == null ? "" : s + ", ") + String.format(java.util.Locale.US, "%,d", n[0]) + " pictures under " + Report.size(minBytes) + " left out";
        return s;
    }

    public static Result scan(Platform pf, Progress pr) { return scan(pf, pr, new Options()); }

    public static Result scan(Platform pf, Progress pr, Options opt) {
        Result r = new Result();
        r.now = pf.now(); r.total = pf.totalBytes(); r.free = pf.freeBytes();
        pr.update("Reading installed apps", 0, 0);
        r.apps = pf.apps();
        Set<String> installed = new HashSet<String>();
        for (AppItem a : r.apps) installed.add(a.pkg);

        // ---- walk (iterative, never follows symlinks out of the tree)
        File root = new File(pf.storageRoot());
        String rootPath = root.getAbsolutePath();
        ArrayDeque<File> stack = new ArrayDeque<File>();
        stack.push(root);
        int seen = 0;
        while (!stack.isEmpty()) {
            if (pr.cancelled()) return r;
            File d = stack.pop();
            File[] kids = d.listFiles();
            if (kids == null) { r.unreadableDirs++; continue; }
            for (File k : kids) {
                String abs = k.getAbsolutePath();
                if (!abs.startsWith(rootPath + "/")) continue;
                String rel = abs.substring(rootPath.length() + 1);
                if (k.isDirectory()) {
                    if (isSymlink(k)) continue;
                    // per-app private dirs: unreadable on Android 11+, and already counted by StorageStats
                    if (rel.equals("Android/data")) continue;
                    stack.push(k);
                    continue;
                }
                long size = k.length();
                FileItem f = new FileItem(rel, abs, size, k.lastModified());
                String[] c = Classifier.classify(rel, installed);
                f.area = c[0]; f.pkg = c[1];
                r.files.add(f); r.scannedBytes += size;
                if (++seen % 500 == 0) pr.update("Listing files", seen, 0);
            }
        }
        // ---- a limited analysis: only the picked pictures, nothing else (no other files, no apps)
        if (opt.limit != null) {
            pr.update("Picking pictures", 0, 0);
            List<FileItem> keep = Sample.restrict(r.files, opt.limit);
            r.files.clear(); r.files.addAll(keep);
            r.scannedBytes = 0;
            for (FileItem f : r.files) r.scannedBytes += f.size;
            r.apps = new ArrayList<AppItem>();
            r.sample = opt.limit.describe();
        }
        // ---- the Settings limits: skip small pictures, analyse only the newest so many
        if (opt.limit == null && (opt.maxPictures > 0 || opt.minPictureBytes > 0)) {
            int[] n = trimPictures(r.files, opt.maxPictures, opt.minPictureBytes);
            r.scannedBytes = 0;
            for (FileItem f : r.files) r.scannedBytes += f.size;
            r.sample = trimmed(n, opt.maxPictures, opt.minPictureBytes);
        }
        // ---- types from magic bytes (only where it matters)
        int i = 0;
        for (FileItem f : r.files) {
            if (pr.cancelled()) return r;
            if (MEDIA_AREAS.contains(f.area) || f.area.startsWith("wa_") || f.rel.toLowerCase().endsWith(".apk"))
                f.type = Classifier.magic(f.abs, f.size);
            else f.type = "other";
            if (++i % 500 == 0) pr.update("Reading file types", i, r.files.size());
        }
        // ---- exact duplicates: size -> quick hash (first+last 64 KiB) -> full SHA-256
        Map<Long, List<FileItem>> bySize = new HashMap<Long, List<FileItem>>();
        for (FileItem f : r.files) {
            if (f.size < 4096 || NO_DEDUP.contains(f.area)) continue;
            List<FileItem> l = bySize.get(f.size);
            if (l == null) { l = new ArrayList<FileItem>(); bySize.put(f.size, l); }
            l.add(f);
        }
        List<List<FileItem>> groups = new ArrayList<List<FileItem>>();
        for (List<FileItem> l : bySize.values()) if (l.size() > 1) groups.add(l);
        int g = 0;
        for (List<FileItem> l : groups) {
            if (pr.cancelled()) return r;
            pr.update("Checking for duplicates", ++g, groups.size());
            Map<String, List<FileItem>> byQuick = new HashMap<String, List<FileItem>>();
            for (FileItem f : l) {
                String q = quickHash(f.abs, f.size);
                if (q == null) continue;
                List<FileItem> m = byQuick.get(q);
                if (m == null) { m = new ArrayList<FileItem>(); byQuick.put(q, m); }
                m.add(f);
            }
            for (List<FileItem> m : byQuick.values())
                if (m.size() > 1) for (FileItem f : m) f.sha256 = fullHash(f.abs);
        }
        // ---- installers: which app is inside (from the file list, no looking inside pictures)
        for (FileItem f : r.files) {
            if (pr.cancelled()) return r;
            if (f.rel.toLowerCase().endsWith(".apk") && "zip".equals(f.type)) {
                String[] info = pf.apkInfo(f.abs);
                if (info != null) { f.apkPackage = info[0]; try { f.apkVersion = Long.parseLong(info[1]); } catch (Exception ignored) {} }
            }
        }
        // ---- favourites in the gallery
        java.util.Set<String> fav = null;
        try { fav = pf.favourites(); } catch (Throwable ignored) {}
        for (FileItem f : r.files) {
            String k = Tags.kind(f);
            if (fav != null && (Tags.IMAGE.equals(k) || Tags.VIDEO.equals(k))) f.favourite = fav.contains(f.abs);
        }
        // ---- the files to look inside: pictures, videos, music. Everything else is decided from the list above.
        List<FileItem> work = new ArrayList<FileItem>();
        for (FileItem f : r.files) if (looked(f)) { work.add(f); f.pending = true; }
        if (opt.previous != null) {
            Set<String> now = new HashSet<String>();
            for (FileItem f : r.files) now.add(f.rel);
            for (String rel : opt.previous.keySet()) if (!now.contains(rel)) r.gone++;
        }
        order(work, opt.previous);
        for (FileItem f : work) { FileItem p = same(f, opt.previous); if (p != null && !p.pending) r.resumed++; }
        r.prepared = true;
        analyse(pf, pr, r, opt, work);
        return r;
    }

    /** Whether a file is looked inside (its pixels, frames or sound), one at a time in analyse(); the rest are done at once. */
    static boolean looked(FileItem f) { return Tags.IMAGE.equals(Tags.kind(f)) || video(f) || music(f); }

    /** Photos measured for their fingerprint and sharpness (or, for the others among them, their size). */
    static boolean featureImage(FileItem f) {
        boolean img = f.type.equals("jpeg") || f.type.equals("heic") || f.type.equals("png") || f.type.equals("webp");
        return img && (f.area.equals("camera") || f.area.equals("wa_sent") || f.area.equals("screenshot")
                || f.area.equals("wa_image") || f.area.equals("pictures"));
    }

    static boolean video(FileItem f) {
        return (f.type.equals("mp4") || f.type.equals("mkv")) && (f.area.equals("camera") || f.area.equals("movies")
                || f.area.equals("download") || f.area.equals("wa_video") || f.area.equals("wa_sent_video"));
    }

    static boolean music(FileItem f) { return (f.type.equals("mp3") || f.type.equals("flac") || f.type.equals("m4a")) && f.area.equals("music"); }

    /**
     * The order files are analysed in: those already analysed last time first (they only copy what was measured, so the
     * plan so far is back in seconds), then the biggest first, where most space is to be won.
     */
    static void order(List<FileItem> work, final Map<String, FileItem> prev) {
        final Map<FileItem, Boolean> done = new HashMap<FileItem, Boolean>();
        for (FileItem f : work) { FileItem p = same(f, prev); done.put(f, p != null && !p.pending); }
        Collections.sort(work, new java.util.Comparator<FileItem>() {
            public int compare(FileItem a, FileItem b) {
                boolean da = done.get(a), db = done.get(b);
                if (da != db) return da ? -1 : 1;
                if (a.size != b.size) return a.size > b.size ? -1 : 1;
                return a.rel.compareTo(b.rel);
            }
        });
    }


    /** The state of one analysis: what is queued, done and counted. */
    static final class Run {
        final List<FileItem> work;
        int next, size;                                              // next file of work to take; files per batch
        final java.util.ArrayDeque<FileItem> front = new java.util.ArrayDeque<FileItem>();   // pulled forward: taken first
        final Set<FileItem> taken = new HashSet<FileItem>(), pulled = new HashSet<FileItem>(), tried = new HashSet<FileItem>();
        final List<FileItem> camByTime = new ArrayList<FileItem>();  // camera photos, by when they were taken
        final List<FileItem> camVideos = new ArrayList<FileItem>(), sentVideos = new ArrayList<FileItem>();   // checked
        int filesDone, filesTotal;
        long bytesDone, bytesTotal;
        Run(List<FileItem> work, int size) { this.work = work; this.size = Math.max(1, size); }

        List<FileItem> batch() {
            List<FileItem> b = new ArrayList<FileItem>();
            while (b.size() < size && !front.isEmpty()) b.add(front.poll());
            while (b.size() < size && next < work.size()) {
                FileItem f = work.get(next++);
                if (taken.add(f)) b.add(f);
            }
            return b;
        }
    }

    /**
     * Looks inside each file of work, a batch at a time, in that order: every check of a file (its date, fingerprint and
     * sharpness, faces, text, what it shows, Mirage; for a video its index and frames) before the next ones. A file is
     * pending until its batch is done. Similar shots need every photo of their moment: when a camera photo is done, the
     * photos taken within minutes of it come next, so their group is decided soon. Stops when pr is cancelled (paused):
     * whatever was done stays done.
     */
    static void analyse(final Platform pf, final Progress pr, Result r, Options opt, List<FileItem> work) {
        final Run run = new Run(work, opt.batch);
        run.filesTotal = r.files.size();
        for (FileItem f : r.files) run.bytesTotal += f.size;
        run.filesDone = r.files.size() - work.size();
        run.bytesDone = run.bytesTotal;
        for (FileItem f : work) run.bytesDone -= f.size;
        for (FileItem f : work) if ("camera".equals(f.area) && Tags.IMAGE.equals(Tags.kind(f))) run.camByTime.add(f);
        Collections.sort(run.camByTime, new java.util.Comparator<FileItem>() {
            public int compare(FileItem a, FileItem b) { return Long.compare(a.mtime, b.mtime); }
        });
        // each stage reports its own name; the counts are the whole analysis's
        Progress inner = new Progress() {
            public void update(String stage, int d, int t) { pr.update(stage, run.filesDone, run.filesTotal); }
            public boolean cancelled() { return pr.cancelled(); }
        };
        long lastCheck = System.currentTimeMillis(), every = opt.checkpointMs;
        if (opt.live != null) opt.live.checkpoint(r, run.filesDone, run.filesTotal, run.bytesDone, run.bytesTotal);
        while (!pr.cancelled()) {
            List<FileItem> b = run.batch();
            if (b.isEmpty()) break;
            if (opt.live != null) opt.live.looking(b.get(0));
            if (!batch(pf, inner, r, opt, b, run)) break;
            for (FileItem f : b) { f.pending = false; run.filesDone++; run.bytesDone += f.size; }
            pullNeighbours(b, run);
            long now = System.currentTimeMillis();
            if (opt.live != null && now - lastCheck >= every) {
                if (!qualities(pf, inner, r, opt, run)) break;
                long t0 = System.currentTimeMillis();
                opt.live.checkpoint(r, run.filesDone, run.filesTotal, run.bytesDone, run.bytesTotal);
                lastCheck = System.currentTimeMillis();
                every = Math.max(opt.checkpointMs, 5 * (lastCheck - t0));          // a slow plan is not rebuilt too often
            }
        }
        if (pr.cancelled() || !qualities(pf, inner, r, opt, run)) holdUnjudged(r, run);
    }

    /** One batch: every check of each file. False when paused meanwhile (the batch then stays pending). */
    static boolean batch(final Platform pf, Progress pr, Result r, final Options opt, List<FileItem> b, Run run) {
        // date taken and Motion Photos (from the file, not the pixels)
        java.util.TimeZone tz = java.util.TimeZone.getDefault();
        for (FileItem f : b) {
            if (pr.cancelled()) return false;
            if (!Tags.IMAGE.equals(Tags.kind(f))) continue;
            FileItem p = same(f, opt.previous);
            if (p != null && p.taken >= 0) { f.taken = p.taken; f.motionBytes = p.motionBytes; continue; }   // read last time
            pr.update("Reading photo details", 0, 0);
            f.taken = "jpeg".equals(f.type) ? PhotoMeta.taken(f.abs, tz) : 0;
            if (f.taken < 0) continue;                                        // unreadable: try again next time
            // the short video of a Motion Photo: only camera photos have one (read-only; the Motion Photo tool's finder)
            if (f.area.equals("camera") && ("jpeg".equals(f.type) || "heic".equals(f.type)))
                try { f.motionBytes = com.storageplanner.tools.MotionPhoto.find(new File(f.abs)).videoBytes(); }
                catch (Throwable t) { f.motionBytes = -1; }
        }
        // fingerprint and sharpness of photos; one decode for these and the sorting
        final List<FileItem> imgList = new ArrayList<FileItem>();
        for (FileItem f : b) if (featureImage(f)) imgList.add(f);
        final Map<FileItem, Sorter.Prep> measured = java.util.Collections.synchronizedMap(new HashMap<FileItem, Sorter.Prep>());
        if (!Par.forEach(imgList.size(), opt.workers, opt.highPriority, pr, "Analysing photos", new Par.Body() {
            public void run(int k, int[] dims) {
                FileItem f = imgList.get(k);
                if (reuse(f, opt.previous)) return;              // unchanged since the last scan
                // full features (hash + sharpness) only where they are used; dimensions for the rest
                boolean needFeatures = f.area.equals("camera") || f.area.equals("wa_sent");
                if (!needFeatures) {                       // dimensions only (for bits-per-pixel)
                    try { if (pf.imageSize(f.abs, dims)) { f.width = dims[0]; f.height = dims[1]; } } catch (Throwable ignored) {}
                    return;
                }
                int[] l;
                if (opt.sortPictures && PhotoCategory.sortable(f) && !sortedBefore(f, opt.previous)) {
                    int[] px;
                    try { px = pf.argb(f.abs, Sorter.EDGE, dims); } catch (Throwable t) { px = null; }
                    if (px == null) return;
                    l = Sorter.luma(px);
                    measured.put(f, Sorter.measure(pf, f, px, dims));
                } else {
                    try { l = pf.luma(f.abs, dims); } catch (Throwable t) { l = null; }
                    if (l == null) return;
                }
                f.width = dims[0]; f.height = dims[1];
                f.phash = ImageFeatures.phash(l, dims[2], dims[3]); f.hasPhash = true;
                f.sharp = ImageFeatures.sharpness(l, dims[2], dims[3]);
            }
        })) return false;
        // videos and music: what the file says (length, size on screen, bit rate)
        for (FileItem f : b) {
            if (pr.cancelled()) return false;
            if (video(f)) { pr.update("Reading videos", 0, 0); try { pf.video(f); } catch (Throwable ignored) {} }
            else if (music(f)) { pr.update("Reading music", 0, 0); try { f.bitrate = pf.audioBitrate(f.abs); } catch (Throwable ignored) {} }
        }
        if (!opt.sortPictures) return !pr.cancelled();
        // what pictures are: faces, text, codes, what a photo shows, Mirage
        List<FileItem> pics = new ArrayList<FileItem>();
        for (FileItem f : b) if (PhotoCategory.sortable(f)) pics.add(f);
        if (!pics.isEmpty()) {
            String note = Sorter.sort(pf, pr, pics, r.now, opt, measured);
            if (note != null) { r.mirageNote = note; opt.mirageBudgetMs = 0; }     // Mirage cannot run: not tried again in this scan
        }
        if (pr.cancelled()) return false;
        // inside videos: the index, and frames only where it finds something
        for (FileItem f : b) {
            if (!video(f) || !VideoCheck.worthy(f)) continue;
            if (pr.cancelled()) return false;
            pr.update("Looking at videos", 0, 0);
            FileItem p = same(f, opt.previous);
            if (p != null && p.videoVersion == VideoCheck.VERSION) VideoCheck.copy(p, f);
            else try { VideoCheck.look(pf, f, null); } catch (Throwable ignored) {}
            // a camera video and its WhatsApp sent copy: fingerprints only where both are there with about the same length
            boolean cam = "camera".equals(f.area), sent = "wa_sent_video".equals(f.area);
            if (!cam && !sent) continue;
            for (FileItem o : cam ? run.sentVideos : run.camVideos) {
                if (Math.abs(o.durationMs - f.durationMs) > 1500) continue;
                if (!(f.videoVersion == VideoCheck.VERSION && f.vHashed)) try { VideoCheck.fingerprint(pf, f); } catch (Throwable ignored) {}
                if (!(o.videoVersion == VideoCheck.VERSION && o.vHashed)) try { VideoCheck.fingerprint(pf, o); } catch (Throwable ignored) {}
            }
            (cam ? run.camVideos : run.sentVideos).add(f);
        }
        return !pr.cancelled();
    }

    /**
     * After a batch: the camera photos taken within minutes of its camera photos come next, so a group of similar shots
     * is complete soon. A photo taken in its turn pulls its neighbours; a pulled one pulls further only when it turns out
     * to be one of a group (it looks like a neighbour already done), so a day of photos is not pulled in one go.
     */
    static void pullNeighbours(List<FileItem> b, Run run) {
        for (FileItem f : b) {
            if (!"camera".equals(f.area) || !Tags.IMAGE.equals(Tags.kind(f))) continue;
            int[] span = window(run.camByTime, f.mtime);
            boolean pull = !run.pulled.contains(f);
            if (!pull && f.hasPhash)
                for (int i = span[0]; i < span[1] && !pull; i++) {
                    FileItem g = run.camByTime.get(i);
                    if (g != f && !g.pending && g.hasPhash && g.width == f.width && g.height == f.height
                            && ImageFeatures.hamming(g.phash, f.phash) <= UnitBuilder.NEAR_DUP_BITS) pull = true;
                }
            if (!pull) continue;
            for (int i = span[0]; i < span[1]; i++) {
                FileItem g = run.camByTime.get(i);
                if (g.pending && run.taken.add(g)) { run.front.add(g); run.pulled.add(g); }
            }
        }
    }

    /** {from, to}: the camera photos (by time) taken within BURST_WINDOW_MS of t. */
    static int[] window(List<FileItem> byTime, long t) {
        int lo = 0, hi = byTime.size();
        while (lo < hi) { int m = (lo + hi) >>> 1; if (byTime.get(m).mtime < t - UnitBuilder.BURST_WINDOW_MS) lo = m + 1; else hi = m; }
        int to = lo;
        while (to < byTime.size() && byTime.get(to).mtime <= t + UnitBuilder.BURST_WINDOW_MS) to++;
        return new int[]{lo, to};
    }

    /**
     * Groups of similar shots whose every photo is analysed: measures which one is better (eyes open, faces, sharpness,
     * light). False when paused meanwhile.
     */
    static boolean qualities(final Platform pf, Progress pr, Result r, Options opt, Run run) {
        long[] pending = UnitBuilder.pendingCameraTimes(r.files);
        final List<FileItem> need = new ArrayList<FileItem>();
        for (List<FileItem> c : UnitBuilder.burstClusters(r.files)) {
            if (UnitBuilder.pendingNear(c, pending) > 0) continue;
            for (FileItem f : c) if (f.quality == null && run.tried.add(f)) need.add(f);
        }
        return Par.forEach(need.size(), opt.workers, opt.highPriority, pr, "Comparing similar photos", new Par.Body() {
            public void run(int k, int[] dims) {
                FileItem f = need.get(k);
                try { f.quality = quality(pf, f.abs); } catch (Throwable t) { f.quality = null; }
            }
        });
    }

    /** Paused before a complete group of similar shots was compared: the group waits, like one still missing a photo. */
    static void holdUnjudged(Result r, Run run) {
        long[] pending = UnitBuilder.pendingCameraTimes(r.files);
        for (List<FileItem> c : UnitBuilder.burstClusters(r.files)) {
            if (UnitBuilder.pendingNear(c, pending) > 0) continue;
            boolean judged = true;
            for (FileItem f : c) if (f.quality == null && !run.tried.contains(f)) judged = false;
            if (!judged) for (FileItem f : c) f.pending = true;
        }
    }

    /** Copies what was measured last time if the file is unchanged (same size, time and type). */
    static boolean reuse(FileItem f, Map<String, FileItem> prev) {
        FileItem p = same(f, prev);
        if (p == null || !p.hasPhash && p.width == 0) return false;
        f.width = p.width; f.height = p.height; f.hasPhash = p.hasPhash; f.phash = p.phash; f.sharp = p.sharp; f.quality = p.quality;
        return true;
    }

    static boolean sortedBefore(FileItem f, Map<String, FileItem> prev) {
        FileItem p = same(f, prev);
        return Sorter.current(p);
    }

    public static FileItem same(FileItem f, Map<String, FileItem> prev) {
        if (prev == null) return null;
        FileItem p = prev.get(f.rel);
        return p != null && p.size == f.size && p.mtime == f.mtime && f.type != null && f.type.equals(p.type) ? p : null;
    }

    /** Long edge used to judge photos: enough pixels for faces in group shots, still fast to decode. */
    public static final int QUALITY_EDGE = 1280;

    public static PhotoQuality.Q quality(Platform pf, String path) {
        int[] dims = new int[4];
        int[] px = pf.argb(path, QUALITY_EDGE, dims);
        if (px == null) return null;
        int w = dims[2], h = dims[3];
        int[] l = new int[px.length];
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            l[i] = (299 * ((p >> 16) & 255) + 587 * ((p >> 8) & 255) + 114 * (p & 255)) / 1000;
        }
        float[] faces = pf.faces(path, px, w, h);
        return PhotoQuality.analyse(l, w, h, faces);
    }

    private static boolean isSymlink(File f) {
        try { return java.nio.file.Files.isSymbolicLink(f.toPath()); } catch (Throwable e) { return true; }
    }

    static String quickHash(String path, long size) {
        RandomAccessFile raf = null;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            raf = new RandomAccessFile(path, "r");
            byte[] buf = new byte[65536];
            int n = raf.read(buf); if (n > 0) md.update(buf, 0, n);
            if (size > 131072) { raf.seek(size - 65536); n = raf.read(buf); if (n > 0) md.update(buf, 0, n); }
            md.update(Long.toString(size).getBytes());
            return hex(md.digest());
        } catch (Exception e) {
            return null;
        } finally {
            if (raf != null) try { raf.close(); } catch (IOException ignored) {}
        }
    }

    static String fullHash(String path) {
        FileInputStream in = null;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            in = new FileInputStream(path);
            byte[] buf = new byte[1 << 20]; int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            return hex(md.digest());
        } catch (Exception e) {
            return null;
        } finally {
            if (in != null) try { in.close(); } catch (IOException ignored) {}
        }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x & 0xff));
        return sb.toString();
    }
}
