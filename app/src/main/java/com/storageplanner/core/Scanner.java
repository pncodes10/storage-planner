package com.storageplanner.core;

import com.storageplanner.tools.Pixels;
import com.storageplanner.tools.category.PhotoCategory;
import com.storageplanner.tools.quality.PhotoQuality;
import com.storageplanner.tools.sharpness.Sharpness;
import com.storageplanner.tools.similarity.PerceptualHash;

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
                    || f.area.equals("download") || f.area.equals("wa_video"))) vids.add(f);
            else if ((f.type.equals("mp3") || f.type.equals("flac") || f.type.equals("m4a")) && f.area.equals("music")) auds.add(f);
            else if (f.rel.toLowerCase().endsWith(".apk") && f.type.equals("zip")) {
                String[] info = pf.apkInfo(f.abs);
                if (info != null) { f.apkPackage = info[0]; try { f.apkVersion = Long.parseLong(info[1]); } catch (Exception ignored) {} }
            }
        }
        int[] dims = new int[4];
        Map<FileItem, Sorter.Prep> measured = new HashMap<FileItem, Sorter.Prep>();
        for (int k = 0; k < imgs.size(); k++) {
            if (pr.cancelled()) return r;
            FileItem f = imgs.get(k);
            pr.update("Analysing photos", k + 1, imgs.size());
            if (reuse(f, opt.previous)) continue;           // unchanged since the last scan
            // full features (hash + sharpness) only where they are used; dimensions for the rest
            boolean needFeatures = f.area.equals("camera") || f.area.equals("wa_sent");
            if (!needFeatures) {                       // dimensions only (for bits-per-pixel)
                try { if (pf.imageSize(f.abs, dims)) { f.width = dims[0]; f.height = dims[1]; } } catch (Throwable ignored) {}
                continue;
            }
            int[] l;
            if (opt.sortPictures && PhotoCategory.sortable(f) && !sortedBefore(f, opt.previous)) {
                // one decode for both: photo features and picture sorting
                int[] px;
                try { px = pf.argb(f.abs, Sorter.EDGE, dims); } catch (Throwable t) { px = null; }
                if (px == null) continue;
                l = Pixels.luma(px);
                measured.put(f, Sorter.measure(pf, f, px, dims));
            } else {
                try { l = pf.luma(f.abs, dims); } catch (Throwable t) { l = null; }
                if (l == null) continue;
            }
            f.width = dims[0]; f.height = dims[1];
            f.phash = PerceptualHash.phash(l, dims[2], dims[3]); f.hasPhash = true;
            f.sharp = Sharpness.of(l, dims[2], dims[3]);
        }
        // ---- near-identical shots: measure which one is better (eyes open, faces, sharpness, light)
        List<FileItem> group = new ArrayList<FileItem>();
        for (List<FileItem> c : UnitBuilder.burstClusters(r.files)) group.addAll(c);
        for (int k = 0; k < group.size(); k++) {
            if (pr.cancelled()) return r;
            pr.update("Comparing similar photos", k + 1, group.size());
            FileItem f = group.get(k);
            if (f.quality != null) continue;                 // kept from the last scan
            try { f.quality = quality(pf, f.abs); } catch (Throwable t) { f.quality = null; }
        }
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
        return p != null && p.category != null && !p.textPending;
    }

    static FileItem same(FileItem f, Map<String, FileItem> prev) {
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
