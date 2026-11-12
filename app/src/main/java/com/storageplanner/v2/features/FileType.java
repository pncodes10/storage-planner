package com.storageplanner.v2.features;

import java.util.HashMap;
import java.util.Map;

/** Type ids and families (PHASE_2 1.1). */
public final class FileType {
    private FileType() {}
    private static final Map<String, String> FAMILY = new HashMap<String, String>();
    private static void fam(String family, String ids) { for (String t : ids.split(" ")) FAMILY.put(t, family); }
    static {
        fam("image", "jpeg png gif webp bmp tiff dng heic heif avif ico svg");
        fam("video", "mp4 mov 3gp mkv webm avi");
        fam("audio", "m4a mp3 aac flac ogg opus wav amr midi");
        fam("document", "pdf ooxml-docx ooxml-xlsx ooxml-pptx odf ole2 rtf epub text csv html xml json vcf ics");
        fam("archive", "zip tar gzip bzip2 xz zstd 7z rar");
        fam("installer", "apk apks xapk");
        fam("data", "sqlite wa-crypt");
        fam("executable", "elf dex class jar");
        fam("special", "empty unknown");
    }
    /** Family of a type id; "special" for unknown ids. */
    public static String family(String type) { String f = FAMILY.get(type); return f == null ? "special" : f; }
    public static boolean isImage(String t) { return "image".equals(family(t)); }
    /** zip family: zip and everything ArchiveSubtype refines a zip into. */
    public static boolean isZipFamily(String t) {
        return t.equals("zip") || t.equals("apk") || t.equals("apks") || t.equals("xapk") || t.equals("jar") || t.startsWith("ooxml-")
                || t.equals("odf") || t.equals("epub");
    }
}
