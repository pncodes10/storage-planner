package com.storageplanner.v2.features;

import com.storageplanner.v2.model.ExtMismatch;
import com.storageplanner.v2.model.TypeSource;

import java.util.*;

/** Extension → expected type ids, and the extension/content mismatch rule (PHASE_2 1.3). */
public final class ExtensionMap {
    private ExtensionMap() {}
    private static final Map<String, Set<String>> MAP = new HashMap<String, Set<String>>();
    private static void put(String exts, String types) {
        Set<String> t = new LinkedHashSet<String>(Arrays.asList(types.split(" ")));
        for (String e : exts.split(" ")) MAP.put(e, t);
    }
    static {
        put("jpg jpeg jpe jfif", "jpeg"); put("png", "png"); put("gif", "gif"); put("webp", "webp"); put("bmp dib", "bmp");
        put("tif tiff", "tiff"); put("dng", "dng tiff"); put("heic heif", "heic heif"); put("avif", "avif"); put("ico", "ico"); put("svg", "svg");
        put("mp4 m4v", "mp4"); put("mov qt", "mov mp4"); put("3gp 3g2 3gpp", "3gp"); put("mkv", "mkv"); put("webm", "webm"); put("avi", "avi");
        put("m4a m4b aac", "m4a aac"); put("mp3", "mp3"); put("flac", "flac"); put("ogg oga", "ogg opus"); put("opus", "opus");
        put("wav", "wav"); put("amr", "amr"); put("mid midi", "midi");
        put("pdf", "pdf"); put("docx docm dotx", "ooxml-docx"); put("xlsx xlsm xltx", "ooxml-xlsx"); put("pptx pptm potx", "ooxml-pptx");
        put("odt ods odp odg", "odf"); put("doc xls ppt msg", "ole2"); put("rtf", "rtf"); put("epub", "epub");
        put("txt log md srt ini cfg conf", "text"); put("csv tsv", "csv"); put("html htm xhtml", "html"); put("xml", "xml");
        put("json", "json"); put("vcf", "vcf"); put("ics", "ics");
        put("zip", "zip"); put("tar", "tar"); put("gz tgz", "gzip"); put("bz2", "bzip2"); put("xz", "xz"); put("zst", "zstd");
        put("7z", "7z"); put("rar", "rar");
        put("apk", "apk"); put("apks", "apks"); put("xapk", "xapk"); put("jar", "jar");
        put("db sqlite sqlite3", "sqlite"); put("crypt12 crypt14 crypt15", "wa-crypt");
        put("so", "elf"); put("dex", "dex"); put("class", "class");
    }

    /** First expected type of an extension, null if unmapped. */
    public static String typeFor(String ext) {
        Set<String> s = MAP.get(ext == null ? "" : ext.toLowerCase(Locale.ROOT));
        return s == null ? null : s.iterator().next();
    }
    public static Set<String> expected(String ext) { return MAP.get(ext == null ? "" : ext.toLowerCase(Locale.ROOT)); }

    public static ExtMismatch mismatch(String ext, String type, TypeSource source) {
        Set<String> exp = expected(ext);
        if (exp == null || type == null || type.equals("unknown") || type.equals("empty") || source == TypeSource.EXTENSION || source == TypeSource.NONE)
            return ExtMismatch.UNKNOWN;
        if (exp.contains(type)) return ExtMismatch.NONE;
        String fe = FileType.family(exp.iterator().next()), ft = FileType.family(type);
        if (fe.equals(ft)) return ExtMismatch.BENIGN;
        // zip-family refinements: .zip holding apk/jar/ooxml, .apk holding a plain zip, .doc holding ooxml, .txt holding structured text
        boolean zipE = false; for (String e : exp) if (FileType.isZipFamily(e)) zipE = true;
        if (zipE && FileType.isZipFamily(type)) return ExtMismatch.BENIGN;
        if (exp.contains("ole2") && type.startsWith("ooxml-")) return ExtMismatch.BENIGN;
        return ExtMismatch.SUSPICIOUS;
    }
}
