package com.storageplanner.core;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Set;
import java.util.regex.Pattern;

/** Maps a shared-storage path to an area using Android / app folder conventions. */
public final class Classifier {
    private Classifier() {}

    private static final Pattern WA_OLD_BACKUP =
        Pattern.compile("msgstore-\\d{4}-\\d{2}-\\d{2}\\.\\d+\\.db\\.crypt\\d+");

    /** @return {area, pkg-or-null} */
    public static String[] classify(String rel, Set<String> installed) {
        String[] p = rel.split("/");
        String name = p[p.length - 1];
        String lower = name.toLowerCase();

        if (name.startsWith(".trashed-")) return a("trash", null);
        if (name.startsWith(".pending-") || lower.endsWith(".tmp") || lower.endsWith(".part")
                || lower.endsWith(".crdownload")) return a("temp", null);

        // WhatsApp / WhatsApp Business (Android 11+ location and the legacy one)
        int wa = -1;
        for (int i = 0; i < p.length - 1; i++)
            if (p[i].equals("WhatsApp") || p[i].equals("WhatsApp Business")) { wa = i; break; }
        if (wa >= 0) {
            String waPkg = p[wa].equals("WhatsApp") ? "com.whatsapp" : "com.whatsapp.w4b";
            if (wa + 1 < p.length && p[wa + 1].equals("Databases") && WA_OLD_BACKUP.matcher(name).matches())
                return a("wa_old_backup", waPkg);
            if (wa + 2 < p.length && p[wa + 1].equals("Media")) {
                String sub = p[wa + 2];
                boolean sent = false;
                for (int i = wa + 3; i < p.length - 1; i++) if (p[i].equals("Sent")) sent = true;
                if (sub.endsWith("Images")) return a(sent ? "wa_sent" : "wa_image", waPkg);
                if (sub.endsWith("Video")) return a(sent ? "wa_sent_video" : "wa_video", waPkg);
                if (sub.endsWith("Voice Notes") || sub.endsWith("Audio")) return a("wa_voice", waPkg);
                if (sub.endsWith("Documents")) return a("wa_doc", waPkg);
                if (sub.equals(".Statuses")) return a("wa_status", waPkg);
                if (sub.endsWith("Stickers")) return a("wa_sticker", waPkg);
                if (sub.endsWith("Animated Gifs")) return a("wa_gif", waPkg);
            }
            return a("wa_other", waPkg);
        }

        if (p.length >= 3 && p[0].equals("Android")) {
            String pkg = p[2];
            if (p[1].equals("data")) {
                if (!installed.contains(pkg)) return a("residue", pkg);
                if (p.length > 3 && p[3].equals("cache")) return a("ext_cache", pkg);
                return a("ext_data", pkg);
            }
            if (p[1].equals("obb")) return a(installed.contains(pkg) ? "app_obb" : "residue", pkg);
            if (p[1].equals("media")) {
                if (!installed.contains(pkg)) return a("residue", pkg);
                if (pkg.equals("org.telegram.messenger") || pkg.equals("org.telegram.plus")) return a("telegram_media", pkg);
                return a("app_media", pkg);
            }
        }

        for (String s : p) if (s.equals(".thumbnails") || s.equals(".Thumbnails")) return a("thumbnails", null);

        String top = p[0];
        if ((top.equals("DCIM") || top.equals("Pictures")) && p.length > 2 && p[1].equals("Screenshots"))
            return a("screenshot", null);
        if (top.equals("DCIM")) return a("camera", null);
        if (top.equals("Pictures")) return a("pictures", null);
        if (top.equals("Download")) return a("download", null);
        if (top.equals("Music")) return a("music", null);
        if (top.equals("Documents")) return a("document", null);
        if (top.equals("Movies")) return a("movies", null);
        if (top.equals("Telegram")) return a("telegram_media", "org.telegram.messenger");
        return a("user_other", null);
    }

    private static String[] a(String area, String pkg) { return new String[]{area, pkg}; }

    /** File type from magic bytes, never from the extension. */
    public static String magic(String path, long size) {
        if (size == 0) return "empty";
        byte[] h = new byte[32];
        int n;
        InputStream in = null;
        try {
            in = new FileInputStream(path);
            n = in.read(h);
        } catch (IOException e) {
            return "unreadable";
        } finally {
            if (in != null) try { in.close(); } catch (IOException ignored) {}
        }
        if (n < 4) return "bin";
        if ((h[0] & 0xff) == 0xFF && (h[1] & 0xff) == 0xD8 && (h[2] & 0xff) == 0xFF) return "jpeg";
        if ((h[0] & 0xff) == 0x89 && h[1] == 'P' && h[2] == 'N' && h[3] == 'G') return "png";
        if (n >= 12 && h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F' && h[8] == 'W' && h[9] == 'E' && h[10] == 'B' && h[11] == 'P') return "webp";
        if (n >= 12 && h[4] == 'f' && h[5] == 't' && h[6] == 'y' && h[7] == 'p') {
            String brand = new String(h, 8, 4);
            if (brand.equals("heic") || brand.equals("heix") || brand.equals("mif1") || brand.equals("msf1")) return "heic";
            if (brand.equals("avif") || brand.equals("avis")) return "avif";
            if (brand.startsWith("M4A")) return "m4a";
            return "mp4";
        }
        if (h[0] == 0x1A && (h[1] & 0xff) == 0x45 && (h[2] & 0xff) == 0xDF && (h[3] & 0xff) == 0xA3) return "mkv";
        if (h[0] == '%' && h[1] == 'P' && h[2] == 'D' && h[3] == 'F') return "pdf";
        if (h[0] == 'O' && h[1] == 'g' && h[2] == 'g' && h[3] == 'S') return "ogg";
        if (h[0] == 'f' && h[1] == 'L' && h[2] == 'a' && h[3] == 'C') return "flac";
        if ((h[0] == 'I' && h[1] == 'D' && h[2] == '3') || ((h[0] & 0xff) == 0xFF && ((h[1] & 0xff) & 0xE0) == 0xE0)) return "mp3";
        if (h[0] == 'P' && h[1] == 'K') return "zip";
        if (n >= 16 && new String(h, 0, 15).equals("SQLite format 3")) return "sqlite";
        return "bin";
    }
}
