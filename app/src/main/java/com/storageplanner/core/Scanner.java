package com.storageplanner.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
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
        // ---- media features
        List<FileItem> imgs = new ArrayList<FileItem>(), vids = new ArrayList<FileItem>(), auds = new ArrayList<FileItem>();
        for (FileItem f : r.files) {
            boolean img = f.type.equals("jpeg") || f.type.equals("heic") || f.type.equals("png") || f.type.equals("webp");
            if (img && (f.area.equals("camera") || f.area.equals("wa_sent") || f.area.equals("screenshot")
                    || f.area.equals("wa_image") || f.area.equals("pictures"))) imgs.add(f);
            else if ((f.type.equals("mp4") || f.type.equals("mkv")) && (f.area.equals("camera") || f.area.equals("movies")
                    || f.area.equals("download") || f.area.equals("wa_video") || f.area.equals("wa_sent_video"))) vids.add(f);
            else if ((f.type.equals("mp3") || f.type.equals("flac") || f.type.equals("m4a")) && f.area.equals("music")) auds.add(f);
            else if (f.rel.toLowerCase().endsWith(".apk") && f.type.equals("zip")) {
                String[] info = pf.apkInfo(f.abs);
                if (info != null) { f.apkPackage = info[0]; try { f.apkVersion = Long.parseLong(info[1]); } catch (Exception ignored) {} }
            }
        }
        // ---- favourites in the gallery, date taken and Motion Photos (from the file, not the pixels)
        java.util.Set<String> fav = null;
        try { fav = pf.favourites(); } catch (Throwable ignored) {}
        for (FileItem f : r.files) {
            String k = Tags.kind(f);
            if (fav != null && (Tags.IMAGE.equals(k) || Tags.VIDEO.equals(k))) f.favourite = fav.contains(f.abs);
        }
        java.util.TimeZone tz = java.util.TimeZone.getDefault();
        List<FileItem> pics = new ArrayList<FileItem>();
        for (FileItem f : r.files) if (Tags.IMAGE.equals(Tags.kind(f))) pics.add(f);
        for (int k = 0; k < pics.size(); k++) {
            if (pr.cancelled()) return r;
            FileItem f = pics.get(k);
            FileItem p = same(f, opt.previous);
            if (p != null && p.taken >= 0) { f.taken = p.taken; f.motionBytes = p.motionBytes; continue; }   // read last time
            if (k % 50 == 0) pr.update("Reading photo details", k + 1, pics.size());
            f.taken = "jpeg".equals(f.type) ? PhotoMeta.taken(f.abs, tz) : 0;
            if (f.taken < 0) continue;                                        // unreadable: try again next time
            // the short video of a Motion Photo: only camera photos have one (read-only; the Motion Photo tool's finder)
            if (f.area.equals("camera") && ("jpeg".equals(f.type) || "heic".equals(f.type)))
                try { f.motionBytes = com.storageplanner.tools.MotionPhoto.find(new File(f.abs)).videoBytes(); }
                catch (Throwable t) { f.motionBytes = -1; }
        }
        final Map<FileItem, Sorter.Prep> measured = java.util.Collections.synchronizedMap(new HashMap<FileItem, Sorter.Prep>());
        final List<FileItem> imgList = imgs;
        final Options fo = opt;
        final Platform fpf = pf;
        if (!Par.forEach(imgList.size(), opt.workers, opt.highPriority, pr, "Analysing photos", new Par.Body() {
            public void run(int k, int[] dims) {
                FileItem f = imgList.get(k);
                if (reuse(f, fo.previous)) return;              // unchanged since the last scan
                // full features (hash + sharpness) only where they are used; dimensions for the rest
                boolean needFeatures = f.area.equals("camera") || f.area.equals("wa_sent");
                if (!needFeatures) {                       // dimensions only (for bits-per-pixel)
                    try { if (fpf.imageSize(f.abs, dims)) { f.width = dims[0]; f.height = dims[1]; } } catch (Throwable ignored) {}
                    return;
                }
                int[] l;
                if (fo.sortPictures && PhotoCategory.sortable(f) && !sortedBefore(f, fo.previous)) {
                    // one decode for both: photo features and picture sorting
                    int[] px;
                    try { px = fpf.argb(f.abs, Sorter.EDGE, dims); } catch (Throwable t) { px = null; }
                    if (px == null) return;
                    l = Sorter.luma(px);
                    measured.put(f, Sorter.measure(fpf, f, px, dims));
                } else {
                    try { l = fpf.luma(f.abs, dims); } catch (Throwable t) { l = null; }
                    if (l == null) return;
                }
                f.width = dims[0]; f.height = dims[1];
                f.phash = ImageFeatures.phash(l, dims[2], dims[3]); f.hasPhash = true;
                f.sharp = ImageFeatures.sharpness(l, dims[2], dims[3]);
            }
        })) return r;
        // ---- near-identical shots: measure which one is better (eyes open, faces, sharpness, light)
        List<FileItem> group = new ArrayList<FileItem>();
        for (List<FileItem> c : UnitBuilder.burstClusters(r.files)) group.addAll(c);
        final List<FileItem> groupList = group;
        if (!Par.forEach(groupList.size(), opt.workers, opt.highPriority, pr, "Comparing similar photos", new Par.Body() {
            public void run(int k, int[] dims) {
                FileItem f = groupList.get(k);
                if (f.quality != null) return;                 // kept from the last scan
                try { f.quality = quality(fpf, f.abs); } catch (Throwable t) { f.quality = null; }
            }
        })) return r;
        for (int k = 0; k < vids.size(); k++) {
            if (pr.cancelled()) return r;
            pr.update("Reading videos", k + 1, vids.size());
            try { pf.video(vids.get(k)); } catch (Throwable ignored) {}
        }
        for (int k = 0; k < auds.size(); k++) {
            if (pr.cancelled()) return r;
            pr.update("Reading music", k + 1, auds.size());
            try { auds.get(k).bitrate = pf.audioBitrate(auds.get(k).abs); } catch (Throwable ignored) {}
        }
        if (opt.sortPictures) Sorter.sort(pf, pr, r, opt, measured);
        if (opt.sortPictures && !pr.cancelled()) VideoCheck.run(pf, pr, vids, opt, opt.previous);
        return r;
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
