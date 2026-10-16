package com.storageplanner.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** "What's using space": maps every file to a user-facing storage category. */
public final class Categories {
    private Categories() {}

    public static final String PHOTOS = "Photos", VIDEOS = "Videos", SCREENSHOTS = "Screenshots", WHATSAPP = "WhatsApp",
            TELEGRAM = "Telegram", DOWNLOADS = "Downloads", MUSIC = "Music and audio", DOCUMENTS = "Documents",
            TRASH = "Trash", JUNK = "Junk and leftovers", OTHER = "Other files", APPS = "Apps and app data",
            CACHES = "App caches", SYSTEM = "System and other";

    /** Order shown in the UI (largest first is applied separately). */
    public static final String[] ALL = {PHOTOS, VIDEOS, SCREENSHOTS, WHATSAPP, TELEGRAM, DOWNLOADS, MUSIC, DOCUMENTS,
            TRASH, JUNK, OTHER, APPS, CACHES, SYSTEM};

    public static boolean isVideo(FileItem f) { return "mp4".equals(f.type) || "mkv".equals(f.type); }
    public static boolean isImage(FileItem f) {
        return "jpeg".equals(f.type) || "png".equals(f.type) || "webp".equals(f.type) || "heic".equals(f.type) || "avif".equals(f.type);
    }

    /** Category of a shared-storage file, or null if it is already counted in app data (Android/obb, Android/media). */
    public static String of(FileItem f) {
        String a = f.area;
        if (a.startsWith("wa_")) return WHATSAPP;
        if (a.equals("camera") || a.equals("pictures")) return isVideo(f) ? VIDEOS : PHOTOS;
        if (a.equals("movies")) return VIDEOS;
        if (a.equals("screenshot")) return SCREENSHOTS;
        if (a.equals("telegram_media")) return TELEGRAM;
        if (a.equals("download")) return DOWNLOADS;
        if (a.equals("music")) return MUSIC;
        if (a.equals("document")) return DOCUMENTS;
        if (a.equals("trash")) return TRASH;
        if (a.equals("thumbnails") || a.equals("temp") || a.equals("residue")) return JUNK;
        if (a.equals("app_obb") || a.equals("app_media") || a.startsWith("ext_")) return null;
        if ("music".equals(a) || "mp3".equals(f.type) || "flac".equals(f.type) || "ogg".equals(f.type) || "m4a".equals(f.type)) return MUSIC;
        return OTHER;
    }

    /** Bytes per category; app data and caches from system stats; the remainder of used space is "System and other". */
    public static Map<String, Long> breakdown(Scanner.Result r) {
        Map<String, Long> m = new LinkedHashMap<String, Long>();
        for (String c : ALL) m.put(c, 0L);
        for (FileItem f : r.files) {
            String c = of(f);
            if (c != null) m.put(c, m.get(c) + f.size);
        }
        long apps = 0, caches = 0;
        for (AppItem a : r.apps) { apps += a.codeBytes + a.dataBytes; caches += a.cacheBytes; }
        m.put(APPS, apps); m.put(CACHES, caches);
        long counted = 0;
        for (Long v : m.values()) counted += v;
        m.put(SYSTEM, Math.max(0, (r.total - r.free) - counted));
        return m;
    }

    public static List<FileItem> files(Scanner.Result r, String category) {
        List<FileItem> out = new ArrayList<FileItem>();
        for (FileItem f : r.files) if (category.equals(of(f))) out.add(f);
        return out;
    }
}
