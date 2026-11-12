package com.storageplanner.v2.features;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Type id → MIME type; extension → MIME type (fallback). */
public final class MimeMap {
    private MimeMap() {}
    private static final Map<String, String> M = new HashMap<String, String>();
    private static void m(String t, String mime) { M.put(t, mime); }
    static {
        m("jpeg", "image/jpeg"); m("png", "image/png"); m("gif", "image/gif"); m("webp", "image/webp"); m("bmp", "image/bmp");
        m("tiff", "image/tiff"); m("dng", "image/x-adobe-dng"); m("heic", "image/heic"); m("heif", "image/heif"); m("avif", "image/avif");
        m("ico", "image/x-icon"); m("svg", "image/svg+xml");
        m("mp4", "video/mp4"); m("mov", "video/quicktime"); m("3gp", "video/3gpp"); m("mkv", "video/x-matroska"); m("webm", "video/webm");
        m("avi", "video/x-msvideo");
        m("m4a", "audio/mp4"); m("mp3", "audio/mpeg"); m("aac", "audio/aac"); m("flac", "audio/flac"); m("ogg", "audio/ogg"); m("opus", "audio/opus");
        m("wav", "audio/wav"); m("amr", "audio/amr"); m("midi", "audio/midi");
        m("pdf", "application/pdf"); m("ooxml-docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        m("ooxml-xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        m("ooxml-pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation");
        m("odf", "application/vnd.oasis.opendocument"); m("ole2", "application/x-ole-storage"); m("rtf", "application/rtf");
        m("epub", "application/epub+zip"); m("text", "text/plain"); m("csv", "text/csv"); m("html", "text/html"); m("xml", "application/xml");
        m("json", "application/json"); m("vcf", "text/vcard"); m("ics", "text/calendar");
        m("zip", "application/zip"); m("tar", "application/x-tar"); m("gzip", "application/gzip"); m("bzip2", "application/x-bzip2");
        m("xz", "application/x-xz"); m("zstd", "application/zstd"); m("7z", "application/x-7z-compressed"); m("rar", "application/vnd.rar");
        m("apk", "application/vnd.android.package-archive"); m("apks", "application/octet-stream"); m("xapk", "application/octet-stream");
        m("jar", "application/java-archive"); m("sqlite", "application/vnd.sqlite3"); m("elf", "application/x-elf");
        m("dex", "application/octet-stream"); m("class", "application/java-vm");
    }
    /** MIME of a content-detected type; null for unknown/empty/wa-crypt. */
    public static String of(String type) { return M.get(type); }
    public static String ofExtension(String ext) { String t = ExtensionMap.typeFor(ext); return t == null ? null : M.get(t); }
    static String lower(String s) { return s.toLowerCase(Locale.ROOT); }
}
