package com.storageplanner.v2test;

import com.storageplanner.v2.features.*;
import com.storageplanner.v2.model.ExtMismatch;
import com.storageplanner.v2.model.NameDate;
import com.storageplanner.v2.model.TypeSource;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** TypeDetector rows (PHASE_2 1.2), ExtensionMap mismatch table (1.3), NameDates (4). */
public final class TypeDetectorTest {
    static byte[] b(int... v) { byte[] r = new byte[v.length]; for (int i = 0; i < v.length; i++) r[i] = (byte) v[i]; return r; }
    static byte[] a(String s) { return s.getBytes(StandardCharsets.ISO_8859_1); }
    static byte[] cat(byte[]... p) { int n = 0; for (byte[] x : p) n += x.length; byte[] r = new byte[n]; int o = 0; for (byte[] x : p) { System.arraycopy(x, 0, r, o, x.length); o += x.length; } return r; }
    static byte[] pad(byte[] x, int n) { return Arrays.copyOf(x, Math.max(n, x.length)); }
    static String t(byte[] h, String ext) { return TypeDetector.detect(h, h.length, h.length, ext).type; }
    static byte[] ftyp(String major, String... compat) {
        byte[] r = new byte[16 + 4 * compat.length];
        r[3] = (byte) r.length; System.arraycopy(a("ftyp" + major), 0, r, 4, 8);
        for (int i = 0; i < compat.length; i++) System.arraycopy(a(compat[i]), 0, r, 16 + 4 * i, 4);
        return pad(r, 64);
    }
    /** Two MPEG-1 layer III 128 kbps 44.1 kHz frames (417 bytes each). */
    static byte[] mp3Frames(int n) {
        byte[] r = new byte[417 * n];
        for (int i = 0; i < n; i++) { r[i * 417] = (byte) 0xFF; r[i * 417 + 1] = (byte) 0xFB; r[i * 417 + 2] = (byte) 0x90; r[i * 417 + 3] = 0x64; }
        return r;
    }

    public static int run(String[] args) {
        T.section("TypeDetector");
        T.eq("empty", "empty", TypeDetector.detect(new byte[0], 0, 0, "jpg").type);
        T.eq("jpeg", "jpeg", t(pad(b(0xFF, 0xD8, 0xFF, 0xE0), 32), "bin"));
        T.eq("png", "png", t(pad(b(0x89, 'P', 'N', 'G', 13, 10, 26, 10), 32), ""));
        T.eq("gif", "gif", t(pad(a("GIF89a"), 32), ""));
        T.eq("webp", "webp", t(pad(a("RIFF\0\0\0\0WEBPVP8 "), 32), ""));
        T.eq("avi", "avi", t(pad(a("RIFF\0\0\0\0AVI LIST"), 32), ""));
        T.eq("wav", "wav", t(pad(a("RIFF\0\0\0\0WAVEfmt "), 32), ""));
        T.eq("ftyp heic", "heic", t(ftyp("heic", "mif1", "heic"), ""));
        T.eq("ftyp mif1 only → heif", "heif", t(ftyp("mif1", "mif1"), ""));
        T.eq("ftyp avif", "avif", t(ftyp("avif", "mif1"), ""));
        T.eq("ftyp qt", "mov", t(ftyp("qt  "), ""));
        T.eq("ftyp 3gp4", "3gp", t(ftyp("3gp4", "isom"), ""));
        T.eq("ftyp M4A", "m4a", t(ftyp("M4A ", "isom"), ""));
        T.eq("ftyp isom", "mp4", t(ftyp("isom", "iso2", "avc1"), ""));
        T.eq("ftyp crx → unknown", "unknown", t(ftyp("crx "), ""));
        T.eq("webm doctype", "webm", t(pad(cat(b(0x1A, 0x45, 0xDF, 0xA3, 0x9F, 0x42, 0x82, 0x84), a("webm")), 64), ""));
        T.eq("mkv doctype", "mkv", t(pad(cat(b(0x1A, 0x45, 0xDF, 0xA3, 0x9F, 0x42, 0x82, 0x88), a("matroska")), 64), ""));
        T.eq("%PDF at 500", "pdf", t(cat(new byte[500], a("%PDF-1.7\n")), ""));
        T.eq("zip", "zip", t(pad(b('P', 'K', 3, 4), 32), ""));
        T.eq("rar", "rar", t(pad(a("Rar!\u001a\u0007"), 16), ""));
        T.eq("7z", "7z", t(pad(b(0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C), 16), ""));
        T.eq("gzip", "gzip", t(pad(b(0x1F, 0x8B, 8), 16), ""));
        T.eq("bzip2", "bzip2", t(pad(a("BZh9"), 16), ""));
        T.eq("xz", "xz", t(pad(b(0xFD, '7', 'z', 'X', 'Z', 0), 16), ""));
        T.eq("zstd", "zstd", t(pad(b(0x28, 0xB5, 0x2F, 0xFD), 16), ""));
        byte[] tar = new byte[1024]; System.arraycopy(a("ustar"), 0, tar, 257, 5); tar[0] = 1; T.eq("tar", "tar", t(tar, ""));
        T.eq("sqlite", "sqlite", t(pad(a("SQLite format 3\0"), 32), ""));
        T.eq("ole2", "ole2", t(pad(b(0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1), 32), "doc"));
        T.eq("elf", "elf", t(pad(b(0x7F, 'E', 'L', 'F'), 32), ""));
        T.eq("dex", "dex", t(pad(a("dex\n035\0"), 32), ""));
        T.eq("class", "class", t(pad(b(0xCA, 0xFE, 0xBA, 0xBE), 32), ""));
        T.eq("flac", "flac", t(pad(a("fLaC"), 32), ""));
        byte[] ogg = new byte[64]; System.arraycopy(a("OggS"), 0, ogg, 0, 4); ogg[26] = 1; ogg[27] = 19; System.arraycopy(a("OpusHead"), 0, ogg, 28, 8);
        T.eq("opus", "opus", t(ogg, ""));
        System.arraycopy(b(1, 'v', 'o', 'r', 'b', 'i', 's', 0), 0, ogg, 28, 8); T.eq("vorbis", "ogg", t(ogg, ""));
        T.eq("amr", "amr", t(pad(a("#!AMR\n"), 16), ""));
        T.eq("midi", "midi", t(pad(a("MThd"), 16), ""));
        T.eq("ID3 mp3", "mp3", t(pad(a("ID3\u0004\0\0\0\0\u0001\u0000"), 64), ""));
        Signature fp = TypeDetector.detect(mp3Frames(2), 834, 834, "");
        T.eq("mp3 frame pair", "mp3", fp.type); T.eq("frame pair STRUCTURE", TypeSource.STRUCTURE, fp.source);
        byte[] lone = pad(b(0xFF, 0xFB, 0x90, 0x64), 834); lone[417] = 0x55;
        T.check("FF FB without second frame → not mp3", !t(lone, "bin").equals("mp3"));
        T.eq("bmp", "bmp", t(pad(cat(a("BM"), new byte[12], b(40)), 64), ""));
        T.eq("tiff", "tiff", t(pad(a("II*\0"), 32), "tif"));
        T.eq("dng by ext", "dng", t(pad(a("MM\0*"), 32), "dng"));
        T.eq("rtf", "rtf", t(a("{\\rtf1 hello}"), ""));
        T.eq("utf-8 text", "text", t("héllo wörld\nline two\n".getBytes(StandardCharsets.UTF_8), "txt"));
        T.eq("binary with NUL: text only by extension", TypeSource.EXTENSION, TypeDetector.detect(b('a', 'b', 0, 'c', 'd'), 5, 5, "txt").source);
        T.eq("utf-16 BOM text", "text", t(cat(b(0xFF, 0xFE), "hi there".getBytes(StandardCharsets.UTF_16LE)), ""));
        T.eq("xml", "xml", t(a("<?xml version=\"1.0\"?><a/>"), ""));
        T.eq("svg", "svg", t(a("<?xml version=\"1.0\"?>\n<svg xmlns=\"x\"/>"), ""));
        T.eq("html", "html", t(a("  <!DOCTYPE HTML><html></html>"), ""));
        T.eq("vcf", "vcf", t(a("BEGIN:VCARD\nVERSION:3.0\n"), ""));
        T.eq("ics", "ics", t(a("BEGIN:VCALENDAR\n"), ""));
        T.eq("csv", "csv", t(a("a,b,c\n1,2,3\n4,5,6\n"), "csv"));
        T.eq("json", "json", t(a("{\"a\": 1}"), "json"));
        Signature wa = TypeDetector.detect(b(0x12, 0x00, 0x98, 0x01, 0x02), 5, 5, "crypt14");
        T.eq("crypt14 → wa-crypt", "wa-crypt", wa.type); T.eq("crypt14 EXTENSION", TypeSource.EXTENSION, wa.source);
        T.eq("random → unknown", "unknown", t(b(0x01, 0x80, 0x02, 0x90, 0x00, 0x05), "xyz"));
        T.eq("random .mp3 → mp3 by extension", TypeSource.EXTENSION, TypeDetector.detect(b(0x01, 0x80, 0, 1), 4, 4, "mp3").source);

        T.section("ExtensionMap");
        T.eq(".jpg+png", ExtMismatch.BENIGN, ExtensionMap.mismatch("jpg", "png", TypeSource.SIGNATURE));
        T.eq(".jpg+heic", ExtMismatch.BENIGN, ExtensionMap.mismatch("jpg", "heic", TypeSource.SIGNATURE));
        T.eq(".jpg+pdf", ExtMismatch.SUSPICIOUS, ExtensionMap.mismatch("jpg", "pdf", TypeSource.SIGNATURE));
        T.eq(".mp4+apk", ExtMismatch.SUSPICIOUS, ExtensionMap.mismatch("mp4", "apk", TypeSource.SIGNATURE));
        T.eq(".apk+zip", ExtMismatch.BENIGN, ExtensionMap.mismatch("apk", "zip", TypeSource.SIGNATURE));
        T.eq(".zip+apk", ExtMismatch.BENIGN, ExtensionMap.mismatch("zip", "apk", TypeSource.SIGNATURE));
        T.eq(".doc+docx", ExtMismatch.BENIGN, ExtensionMap.mismatch("doc", "ooxml-docx", TypeSource.SIGNATURE));
        T.eq(".pdf+jpeg", ExtMismatch.SUSPICIOUS, ExtensionMap.mismatch("pdf", "jpeg", TypeSource.SIGNATURE));
        T.eq(".xyz+jpeg", ExtMismatch.UNKNOWN, ExtensionMap.mismatch("xyz", "jpeg", TypeSource.SIGNATURE));
        T.eq(".jpg+jpeg", ExtMismatch.NONE, ExtensionMap.mismatch("JPG", "jpeg", TypeSource.SIGNATURE));
        T.eq("EXTENSION source", ExtMismatch.UNKNOWN, ExtensionMap.mismatch("mp3", "mp3", TypeSource.EXTENSION));

        T.section("NameDates");
        long now = Dates.utc(2026, 1, 1, 0, 0, 0, 0);
        nd("IMG_20240627_175114.jpg", now, Dates.utc(2024, 6, 27, 17, 51, 14, 0), "CAM_YMD_HMS");
        nd("IMG20240627_200846.jpg", now, Dates.utc(2024, 6, 27, 20, 8, 46, 0), "CAM_YMD_HMS");
        nd("PXL_20240627_175114123.jpg", now, Dates.utc(2024, 6, 27, 17, 51, 14, 123), "CAM_YMD_HMS");
        nd("VID_20240627_175114.mp4", now, Dates.utc(2024, 6, 27, 17, 51, 14, 0), "CAM_YMD_HMS");
        nd("Screenshot_20240627-175114_WhatsApp.png", now, Dates.utc(2024, 6, 27, 17, 51, 14, 0), "SCREENSHOT");
        nd("Screenshot 2024-06-27 17.51.14.png", now, Dates.utc(2024, 6, 27, 17, 51, 14, 0), "SCREENSHOT");
        nd("IMG-20240627-WA0027.jpg", now, Dates.utc(2024, 6, 27, 0, 0, 0, 0), "WA_DAY");
        nd("WhatsApp Image 2024-06-27 at 17.51.14 (2).jpeg", now, Dates.utc(2024, 6, 27, 17, 51, 14, 0), "WA_DESKTOP");
        nd("WhatsApp Image 2024-06-27 at 5.51.14 PM (2).jpeg", now, Dates.utc(2024, 6, 27, 17, 51, 14, 0), "WA_DESKTOP");
        nd("IMG20240628095906.jpg", now, Dates.utc(2024, 6, 28, 9, 59, 6, 0), "CAM_YMD_HMS");
        nd("20240627_175114.jpg", now, Dates.utc(2024, 6, 27, 17, 51, 14, 0), "CAM_YMD_HMS");
        nd("invoice_2023-04-01.pdf", now, Dates.utc(2023, 4, 1, 0, 0, 0, 0), "GENERIC_DAY");
        T.eq("invalid month", null, NameDates.parse("20241332_101010.jpg", now));
        T.eq("Feb 30", null, NameDates.parse("IMG_20240230_101010.jpg", now));
        T.eq("future", null, NameDates.parse("IMG_20270101_101010.jpg", now));
        T.eq("pre-2000", null, NameDates.parse("IMG_19990101_101010.jpg", now));
        T.eq("leap day ok", Dates.utc(2024, 2, 29, 0, 0, 0, 0), NameDates.parse("IMG-20240229-WA0001.jpg", now).at);
        T.eq("epoch check", 1719510674000L, Dates.utc(2024, 6, 27, 17, 51, 14, 0));
        java.nio.file.Path inv = java.nio.file.Paths.get("/home/user/sp-dataset-drive/inventory.jsonl");
        if (java.nio.file.Files.isRegularFile(inv)) {
            int seen = 0, bad = 0;
            try {
                for (String line : java.nio.file.Files.readAllLines(inv, StandardCharsets.UTF_8)) {
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"title\"\\s*:\\s*\"([^\"]+)\"").matcher(line);
                    if (!m.find()) continue;
                    String n = m.group(1);
                    if (!(n.startsWith("IMG_") || n.startsWith("IMG2") || n.matches("IMG-\\d{8}-WA.*") || n.startsWith("WhatsApp Image"))) continue;
                    if (!n.matches(".*20\\d{6}.*")) { System.out.println("  no date in name (not counted): " + n); continue; }   // e.g. IMG_1976.HEIC
                    seen++;
                    if (NameDates.parse(n, Long.MAX_VALUE / 2) == null) { bad++; System.out.println("  unmatched name: " + n); }
                }
            } catch (java.io.IOException e) { T.check("inventory readable", false); }
            System.out.println("  NameDates on Drive inventory: " + seen + " camera/WhatsApp names, " + bad + " unmatched");
            T.eq("Drive inventory names all parse", 0, bad);
        }
        return T.finish("TypeDetectorTest");
    }

    static void nd(String name, long now, long at, String pattern) {
        NameDate d = NameDates.parse(name, now);
        T.check(name + " parsed", d != null);
        if (d == null) return;
        T.eq(name + " at", at, d.at); T.eq(name + " pattern", pattern, d.pattern);
    }
}
