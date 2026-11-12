package com.storageplanner.v2.risk;

import java.util.*;

/** Content families of Phase 2 type strings. */
public final class Types {
    private Types() {}
    static final Set<String> IMAGE = set("jpeg", "png", "webp", "heic", "heif", "gif", "bmp", "tiff", "avif");
    static final Set<String> VIDEO = set("mp4", "mkv", "webm", "3gp", "mov", "avi", "m4v");
    static final Set<String> AUDIO = set("opus", "mp3", "m4a", "ogg", "wav", "flac", "aac", "amr");
    static final Set<String> ARCHIVE = set("zip", "gzip", "tar", "7z", "rar", "bzip2", "xz");
    static final Set<String> APK = set("apk", "apks", "xapk");
    static final Set<String> DOC_EXT = set("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp", "txt", "csv", "rtf");
    static Set<String> set(String... s) { return new HashSet<String>(Arrays.asList(s)); }
    public static boolean image(String t) { return IMAGE.contains(t); }
    public static boolean video(String t) { return VIDEO.contains(t); }
    public static boolean audio(String t) { return AUDIO.contains(t); }
    public static boolean archive(String t) { return ARCHIVE.contains(t); }
    public static boolean apk(String t) { return APK.contains(t); }
    public static boolean document(String t) {
        return t != null && (t.equals("pdf") || t.startsWith("ooxml") || t.startsWith("odf") || t.equals("text") || t.equals("csv")
                || t.equals("rtf") || t.equals("ole") || t.startsWith("msoffice"));
    }
    /** In any content family (PHASE_4 3.1 row 3). */
    public static boolean content(String t) { return image(t) || video(t) || audio(t) || archive(t) || apk(t) || document(t); }
    public static boolean docName(String name) {
        int i = name == null ? -1 : name.lastIndexOf('.');
        return i > 0 && DOC_EXT.contains(name.substring(i + 1).toLowerCase(Locale.ROOT));
    }
}
