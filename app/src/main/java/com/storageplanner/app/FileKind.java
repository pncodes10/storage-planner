package com.storageplanner.app;

import android.webkit.MimeTypeMap;

import com.storageplanner.core.FileItem;

import java.util.Locale;

/** What a file can be shown as inside the app, and its MIME type for other apps. */
public final class FileKind {
    private FileKind() {}

    public static final int NONE = 0, IMAGE = 1, VIDEO = 2, AUDIO = 3, PDF = 4, TEXT = 5;

    public static String ext(String name) {
        int i = name.lastIndexOf('.');
        return i < 0 ? "" : name.substring(i + 1).toLowerCase(Locale.US);
    }

    private static boolean in(String e, String... list) { for (String x : list) if (x.equals(e)) return true; return false; }

    public static int of(FileItem f) {
        String t = f.type == null ? "" : f.type, e = ext(f.name());
        if (in(t, "jpeg", "png", "webp", "heic", "avif") || in(e, "jpg", "jpeg", "png", "webp", "heic", "heif", "gif", "bmp", "avif")) return IMAGE;
        if (in(t, "mp4", "mkv") && !in(e, "m4a", "aac", "3ga")) return VIDEO;
        if (in(e, "mp4", "mkv", "3gp", "webm", "mov", "m4v")) return VIDEO;
        if (in(t, "mp3", "ogg", "flac", "m4a") || in(e, "mp3", "ogg", "opus", "flac", "m4a", "aac", "wav", "amr", "3ga", "mid", "midi")) return AUDIO;
        if ("pdf".equals(t) || "pdf".equals(e)) return PDF;
        if (in(e, "txt", "log", "csv", "json", "xml", "md", "ini", "cfg", "conf", "srt", "vcf", "html", "htm", "yml", "yaml", "tsv")) return TEXT;
        return NONE;
    }

    public static String label(int kind) {
        switch (kind) {
            case IMAGE: return "View photo";
            case VIDEO: return "Play video";
            case AUDIO: return "Play audio";
            case PDF: return "View document";
            case TEXT: return "View text";
            default: return "View";
        }
    }

    public static String mime(FileItem f) {
        String e = ext(f.name());
        if ("apk".equals(e)) return "application/vnd.android.package-archive";
        if ("opus".equals(e)) return "audio/ogg";
        String m = e.isEmpty() ? null : MimeTypeMap.getSingleton().getMimeTypeFromExtension(e);
        if (m != null) return m;
        String t = f.type == null ? "" : f.type;
        if (t.equals("jpeg")) return "image/jpeg";
        if (t.equals("png")) return "image/png";
        if (t.equals("webp")) return "image/webp";
        if (t.equals("heic")) return "image/heic";
        if (t.equals("mp4")) return "video/mp4";
        if (t.equals("mkv")) return "video/x-matroska";
        if (t.equals("mp3")) return "audio/mpeg";
        if (t.equals("ogg")) return "audio/ogg";
        if (t.equals("flac")) return "audio/flac";
        if (t.equals("m4a")) return "audio/mp4";
        if (t.equals("pdf")) return "application/pdf";
        if (t.equals("zip")) return "application/zip";
        return "*/*";
    }
}
