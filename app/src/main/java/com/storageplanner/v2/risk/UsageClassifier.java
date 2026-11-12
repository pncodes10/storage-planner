package com.storageplanner.v2.risk;

import com.storageplanner.v2.model.*;

import java.util.*;
import java.util.regex.Pattern;

/** PHASE_4 3.1, first match. */
public final class UsageClassifier {
    private UsageClassifier() {}
    static final Set<String> CHAT = Types.set("wa_image", "wa_video", "wa_sent", "wa_sent_video", "wa_gif", "wa_sticker", "telegram_media");
    static final Pattern APPDIR = Pattern.compile("^android/(data|media)/[^/]+/(.+/)?");

    public static boolean trashed(FileRecord f) {
        return "trash".equals(f.area) || (f.name != null && f.name.startsWith(".trashed-"));
    }
    /** F-05: the content contradicts a temp name (complete file of a content family). */
    public static boolean tempContradicted(FileRecord f) {
        return f.typeSource == TypeSource.SIGNATURE && Types.content(f.type) && f.integrity != Integrity.TRUNCATED;
    }
    static boolean underAppDir(String low) { return low.startsWith("android/data/") || low.startsWith("android/media/"); }
    static boolean hasComp(String low, String... names) {
        String[] c = low.split("/");
        for (int i = 3; i < c.length - 1; i++) for (String n : names) if (c[i].equals(n)) return true;
        return false;
    }

    public static UsageClass of(FileContext ctx) {
        FileRecord f = ctx.f; String a = f.area == null ? "" : f.area, t = f.type == null ? "unknown" : f.type, low = ctx.lowerPath();
        String ext = f.extension == null ? "" : f.extension.toLowerCase(Locale.ROOT);
        ctx.nameContentMismatch = false;
        if (f.sizeBytes == 0) return UsageClass.EMPTY;
        if (trashed(f)) return UsageClass.TRASH;
        if (a.equals("temp")) {
            if (!tempContradicted(f)) return UsageClass.TEMP;
            ctx.nameContentMismatch = true;
        }
        if (a.equals("thumbnails")) return UsageClass.THUMBNAIL;
        if (a.equals("ext_cache") || (underAppDir(low) && hasComp(low, "cache", ".cache"))) return UsageClass.CACHE;
        if (underAppDir(low) && hasComp(low, "logs", "log") && (ext.equals("log") || ext.equals("txt") || ext.equals("trace"))) return UsageClass.APP_LOG;
        if (a.equals("wa_status")) return UsageClass.STATUS;
        String nl = f.name == null ? "" : f.name.toLowerCase(Locale.ROOT);
        if (a.equals("wa_old_backup") || (nl.startsWith("msgstore") && nl.contains(".db.crypt"))) return UsageClass.CHAT_BACKUP;
        if (Types.apk(t) || ext.equals("exe") || ext.equals("msi") || ext.equals("dmg")) return UsageClass.INSTALLER;
        boolean doc = Types.document(t) || (Types.image(t) && (low.startsWith("documents/") || low.contains("/documents/")));
        if (doc && Keywords.record(f.path)) return UsageClass.DOCUMENT_RECORD;
        if (Types.archive(t) && !doc) return UsageClass.ARCHIVE;
        if (Types.document(t)) return UsageClass.DOCUMENT;
        if (Types.image(t) && a.equals("camera")) return UsageClass.CAMERA_PHOTO;
        if (Types.video(t) && (a.equals("camera") || a.equals("movies"))) return UsageClass.CAMERA_VIDEO;
        if (a.equals("screenshot")) return UsageClass.SCREENSHOT;
        if (CHAT.contains(a)) return UsageClass.CHAT_MEDIA;
        if (a.equals("wa_voice") || low.startsWith("recordings/") || (Types.audio(t) && (nl.contains("voice") || nl.contains("call")))) return UsageClass.VOICE;
        if (a.equals("music") && Types.audio(t)) return UsageClass.MUSIC;
        if (a.equals("pictures")) return UsageClass.PICTURES;
        if (a.equals("download")) return UsageClass.DOWNLOAD;
        if (a.equals("app_media") || a.equals("app_obb") || a.equals("ext_data") || a.equals("residue")) return UsageClass.APP_MEDIA;
        if (t.equals("unknown")) return UsageClass.UNKNOWN;
        return UsageClass.OTHER;
    }
}
