package com.storageplanner.v2.features;

import com.storageplanner.v2.model.TypeSource;
import com.storageplanner.v2.util.Bytes;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Content-based type detection (PHASE_2 1.2); rules in table order, first match wins. Never throws. */
public final class TypeDetector {
    private TypeDetector() {}

    static Signature sig(String t) { return new Signature(t, TypeSource.SIGNATURE, 1.0, null); }

    public static Signature detect(byte[] h, int n, long size, String ext) {
        try { return detect0(h, Math.min(n, h.length), size, ext == null ? "" : ext.toLowerCase(Locale.ROOT)); }
        catch (RuntimeException e) { return byExtension(ext == null ? "" : ext.toLowerCase(Locale.ROOT)); }
    }

    private static Signature detect0(byte[] h, int n, long size, String ext) {
        if (size == 0) return sig("empty");
        if (Bytes.at(h, n, 0, 0xFF, 0xD8, 0xFF)) return sig("jpeg");
        if (Bytes.at(h, n, 0, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)) return sig("png");
        if (Bytes.at(h, n, 0, "GIF87a") || Bytes.at(h, n, 0, "GIF89a")) return sig("gif");
        if (Bytes.at(h, n, 0, "RIFF")) {
            if (Bytes.at(h, n, 8, "WEBP")) return sig("webp");
            if (Bytes.at(h, n, 8, "AVI ")) return sig("avi");
            if (Bytes.at(h, n, 8, "WAVE")) return sig("wav");
        }
        if (Bytes.at(h, n, 4, "ftyp")) { Signature s = ftyp(h, n); if (s != null) return s; }
        if (Bytes.at(h, n, 0, 0x1A, 0x45, 0xDF, 0xA3)) {
            int d = Bytes.indexOf(h, 4, Math.min(n, 64), "B\u0082");
            boolean webm = d >= 0 && d + 3 + 4 <= n && Bytes.at(h, n, d + 3, "webm");
            return sig(webm ? "webm" : "mkv");
        }
        if (Bytes.indexOf(h, 0, Math.min(n, 1024), "%PDF-") >= 0) return sig("pdf");
        if (Bytes.at(h, n, 0, 'P', 'K', 3, 4) || Bytes.at(h, n, 0, 'P', 'K', 5, 6) || Bytes.at(h, n, 0, 'P', 'K', 7, 8)) return sig("zip");
        if (Bytes.at(h, n, 0, 'R', 'a', 'r', '!', 0x1A, 0x07)) return sig("rar");
        if (Bytes.at(h, n, 0, 0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C)) return sig("7z");
        if (Bytes.at(h, n, 0, 0x1F, 0x8B)) return sig("gzip");
        if (Bytes.at(h, n, 0, "BZh") && n > 3 && h[3] >= '1' && h[3] <= '9') return sig("bzip2");
        if (Bytes.at(h, n, 0, 0xFD, '7', 'z', 'X', 'Z', 0)) return sig("xz");
        if (Bytes.at(h, n, 0, 0x28, 0xB5, 0x2F, 0xFD)) return sig("zstd");
        if (Bytes.at(h, n, 257, "ustar")) return sig("tar");
        if (Bytes.at(h, n, 0, "SQLite format 3\u0000")) return sig("sqlite");
        if (Bytes.at(h, n, 0, 0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1)) {
            boolean known = ext.equals("doc") || ext.equals("xls") || ext.equals("ppt") || ext.equals("msg");
            return new Signature("ole2", TypeSource.SIGNATURE, 1.0, known ? ext : null);
        }
        if (Bytes.at(h, n, 0, 0x7F, 'E', 'L', 'F')) return sig("elf");
        if (Bytes.at(h, n, 0, "dex\n")) return sig("dex");
        if (Bytes.at(h, n, 0, 0xCA, 0xFE, 0xBA, 0xBE)) return sig("class");
        if (Bytes.at(h, n, 0, "fLaC")) return sig("flac");
        if (Bytes.at(h, n, 0, "OggS")) {
            int seg = n > 26 ? h[26] & 0xff : 0, p = 27 + seg;
            if (Bytes.at(h, n, p, "OpusHead")) return sig("opus");
            if (Bytes.at(h, n, p, 0x01, 'v', 'o', 'r', 'b', 'i', 's')) return sig("ogg");
            return sig("ogg");
        }
        if (Bytes.at(h, n, 0, "#!AMR")) return sig("amr");
        if (Bytes.at(h, n, 0, "MThd")) return sig("midi");
        if (Bytes.at(h, n, 0, "ID3") && n >= 10 && (h[6] & 0x80) == 0 && (h[7] & 0x80) == 0 && (h[8] & 0x80) == 0 && (h[9] & 0x80) == 0) return sig("mp3");
        if (Mpeg.framePair(h, n, 0)) return new Signature("mp3", TypeSource.STRUCTURE, 0.9, "frame-pair");
        if (Mpeg.adtsPair(h, n, 0)) return new Signature("aac", TypeSource.STRUCTURE, 0.9, null);
        if (n >= 6 && Bytes.at(h, n, 0, 0, 0, 1, 0)) {
            int c = Bytes.u16le(h, 4);
            if (c >= 1 && c <= 64 && n >= 6 + 16 && h[6 + 3] == 0 && (Bytes.u16le(h, 6 + 4) <= 1)) return new Signature("ico", TypeSource.STRUCTURE, 0.8, null);
        }
        if (Bytes.at(h, n, 0, "BM") && n >= 18) {
            long hs = Bytes.u32le(h, 14);
            if (hs == 12 || hs == 40 || hs == 52 || hs == 56 || hs == 108 || hs == 124) return new Signature("bmp", TypeSource.STRUCTURE, 0.9, null);
        }
        if (Bytes.at(h, n, 0, 'I', 'I', '*', 0) || Bytes.at(h, n, 0, 'M', 'M', 0, '*')) return sig(ext.equals("dng") ? "dng" : "tiff");
        if (Bytes.at(h, n, 0, "{\\rtf")) return sig("rtf");
        String text = textType(h, n, ext);
        if (text != null) return new Signature(text, TypeSource.STRUCTURE, 0.7, null);
        return byExtension(ext);
    }

    static Signature byExtension(String ext) {
        if (ext.equals("crypt12") || ext.equals("crypt14") || ext.equals("crypt15")) return new Signature("wa-crypt", TypeSource.EXTENSION, 0.5, null);
        String t = ExtensionMap.typeFor(ext);
        if (t != null) return new Signature(t, TypeSource.EXTENSION, 0.5, null);
        return new Signature("unknown", TypeSource.NONE, 0.3, null);
    }

    private static Signature ftyp(byte[] h, int n) {
        long box = Bytes.u32be(h, 0);
        if (box < 16 || box > 4096) box = Math.min(n, 16);
        int end = (int) Math.min(box, n);
        String major = Bytes.ascii(h, 8, 4);
        boolean avif = false, heic = false, mif1 = false;
        String[] brands = new String[Math.max(1, (end - 16) / 4 + 1)];
        brands[0] = major;
        int bn = 1;
        for (int p = 16; p + 4 <= end && bn < brands.length; p += 4) brands[bn++] = Bytes.ascii(h, p, 4);
        for (int k = 0; k < bn; k++) {
            String b = brands[k];
            if (b.equals("avif") || b.equals("avis")) avif = true;
            if (b.equals("heic") || b.equals("heix") || b.equals("hevc") || b.equals("hevx") || b.equals("heim") || b.equals("heis")) heic = true;
            if (b.equals("mif1") || b.equals("msf1")) mif1 = true;
        }
        // major brand decides first, then compatible brands
        if (major.equals("avif") || major.equals("avis")) return sig("avif");
        if (heic && (major.startsWith("hei") || major.startsWith("hev") || major.equals("mif1") || major.equals("msf1"))) return sig("heic");
        if (major.equals("qt  ")) return sig("mov");
        if (major.startsWith("3gp") || major.startsWith("3g2")) return sig("3gp");
        if (major.equals("M4A ") || major.equals("M4B ") || major.equals("M4P ")) return sig("m4a");
        if (major.equals("crx ")) return new Signature("unknown", TypeSource.SIGNATURE, 1.0, "crx");
        if (avif) return sig("avif");
        if (heic) return sig("heic");
        if (mif1) return sig("heif");
        return sig("mp4");
    }

    /** Rule 21: text test on the first 4 KiB, then content-specific text types. */
    static String textType(byte[] h, int n, String ext) {
        int len = Math.min(n, 4096);
        if (len == 0) return null;
        int start = 0;
        String s;
        if (Bytes.at(h, n, 0, 0xFF, 0xFE) || Bytes.at(h, n, 0, 0xFE, 0xFF)) {
            s = new String(h, 2, len - 2 - ((len - 2) & 1), Bytes.at(h, n, 0, 0xFF, 0xFE) ? StandardCharsets.UTF_16LE : StandardCharsets.UTF_16BE);
        } else {
            if (Bytes.at(h, n, 0, 0xEF, 0xBB, 0xBF)) start = 3;
            int ctl = 0;
            for (int i = start; i < len; i++) {
                int c = h[i] & 0xff;
                if (c == 0) return null;
                if (c < 0x20 && c != '\t' && c != '\r' && c != '\n' && c != '\f') ctl++;
            }
            if (ctl * 50 > len) return null;
            // valid UTF-8, allowing a sequence cut at the 4 KiB boundary
            int cut = len;
            if (len == 4096) { int k = len - 1; while (k > len - 4 && (h[k] & 0xC0) == 0x80) k--; if ((h[k] & 0x80) != 0) cut = k; }
            try {
                s = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(h, start, cut - start)).toString();
            } catch (CharacterCodingException e) { return null; }
        }
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == 0) return null;
        String t = s.replaceFirst("^[\\s\\uFEFF]+", "");
        String low = t.length() > 1024 ? t.substring(0, 1024).toLowerCase(Locale.ROOT) : t.toLowerCase(Locale.ROOT);
        if (t.startsWith("<?xml")) return low.contains("<svg") ? "svg" : "xml";
        if (low.startsWith("<svg")) return "svg";
        if (low.startsWith("<!doctype html") || low.startsWith("<html")) return "html";
        if (t.startsWith("BEGIN:VCARD")) return "vcf";
        if (t.startsWith("BEGIN:VCALENDAR")) return "ics";
        if (ext.equals("csv") && csvLike(t)) return "csv";
        if (ext.equals("json") && (t.startsWith("{") || t.startsWith("["))) return "json";
        return "text";
    }

    static boolean csvLike(String t) {
        String[] lines = t.split("\r?\n", 20);
        java.util.Map<String, Integer> seen = new java.util.HashMap<String, Integer>();
        for (int i = 0; i < lines.length && i < 19; i++) {
            for (char d : new char[]{',', ';'}) {
                int c = 0; for (int k = 0; k < lines[i].length(); k++) if (lines[i].charAt(k) == d) c++;
                if (c >= 2) { String key = d + ":" + c; Integer v = seen.get(key); v = v == null ? 1 : v + 1; seen.put(key, v); if (v >= 2) return true; }
            }
        }
        return false;
    }
}
