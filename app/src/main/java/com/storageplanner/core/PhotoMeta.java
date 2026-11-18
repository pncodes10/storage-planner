package com.storageplanner.core;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.Calendar;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Facts about a picture that come from its file or its name, not from its pixels: the date it was taken (EXIF), the
 * app a screenshot shows (from the file name), the kind of QR code or barcode in it. Read-only.
 */
public final class PhotoMeta {
    private PhotoMeta() {}

    static final int HEAD = 72 * 1024;       // EXIF is one APP1 segment (at most 64 KB), right after the start of the file

    // ------------------------------------------------------------------ date taken
    /**
     * When a JPEG was taken, from EXIF DateTimeOriginal (with OffsetTimeOriginal when the camera wrote it, else the
     * phone's time zone), in ms; 0 when the file has no such date (downloads, many edits, screenshots), -1 when it could
     * not be read. Unlike the file's date, this survives copying, forwarding and restoring a backup.
     */
    public static long taken(String path, TimeZone tz) {
        byte[] d;
        try { d = head(path); } catch (Throwable t) { return -1; }
        return d == null ? -1 : taken(d, tz);
    }

    static byte[] head(String path) throws java.io.IOException {
        RandomAccessFile f = new RandomAccessFile(new File(path), "r");
        try {
            int n = (int) Math.min(HEAD, f.length());
            byte[] b = new byte[n];
            f.readFully(b);
            return b;
        } finally { f.close(); }
    }

    /** taken() on the first bytes of a JPEG; 0 when they hold no readable date (also when they are cut or damaged). */
    public static long taken(byte[] d, TimeZone tz) {
        try { long t = takenOrThrow(d, tz); return t > 0 ? t : 0; } catch (RuntimeException e) { return 0; }
    }

    private static long takenOrThrow(byte[] d, TimeZone tz) {
        if (d.length < 4 || (d[0] & 0xFF) != 0xFF || (d[1] & 0xFF) != 0xD8) return 0;
        int p = 2;
        while (p + 4 <= d.length) {
            if ((d[p] & 0xFF) != 0xFF) return 0;
            int m = d[p + 1] & 0xFF;
            if (m == 0xD8 || m >= 0xD0 && m <= 0xD7 || m == 0x01) { p += 2; continue; }
            if (m == 0xDA || m == 0xD9) return 0;                           // image data: no EXIF before it
            int len = ((d[p + 2] & 0xFF) << 8) | (d[p + 3] & 0xFF);
            if (len < 2) return 0;
            if (m == 0xE1 && p + 10 <= d.length && d[p + 4] == 'E' && d[p + 5] == 'x' && d[p + 6] == 'i' && d[p + 7] == 'f') {
                long t = tiff(d, p + 10, Math.min(d.length, p + 2 + len), tz);
                if (t != 0) return t;
            }
            p += 2 + len;
        }
        return 0;
    }

    private static long tiff(byte[] d, int t, int end, TimeZone tz) {
        if (t + 8 > end) return 0;
        boolean le = d[t] == 'I';
        if (!le && d[t] != 'M') return 0;
        long ifd0 = u32(d, t + 4, le);
        int exif = (int) tagValue(d, t, end, ifd0, 0x8769, le);           // the Exif IFD
        if (exif <= 0) return 0;
        String when = ascii(d, t, end, exif, 0x9003, le);                  // DateTimeOriginal "YYYY:MM:DD HH:MM:SS"
        if (when == null) return 0;
        String off = ascii(d, t, end, exif, 0x9011, le);                   // OffsetTimeOriginal "+05:30"
        return parse(when, off, tz);
    }

    static long parse(String when, String off, TimeZone tz) {
        Matcher m = Pattern.compile("(\\d{4}):(\\d{2}):(\\d{2})[ T](\\d{2}):(\\d{2}):(\\d{2})").matcher(when);
        if (!m.find()) return 0;
        int y = Integer.parseInt(m.group(1)), mo = Integer.parseInt(m.group(2)), da = Integer.parseInt(m.group(3));
        int h = Integer.parseInt(m.group(4)), mi = Integer.parseInt(m.group(5)), s = Integer.parseInt(m.group(6));
        if (y < 1990 || y > 2100 || mo < 1 || mo > 12 || da < 1 || da > 31 || h > 23 || mi > 59 || s > 60) return 0;   // "0000:00:00" and other blanks
        TimeZone zone = tz;
        if (off != null) {
            Matcher o = Pattern.compile("([+-])(\\d{2}):(\\d{2})").matcher(off);
            if (o.find()) zone = TimeZone.getTimeZone("GMT" + o.group(1) + o.group(2) + ":" + o.group(3));
        }
        Calendar c = Calendar.getInstance(zone, Locale.ROOT);
        c.clear();
        c.set(y, mo - 1, da, h, mi, s);
        return c.getTimeInMillis();
    }

    /** Value (or offset) of a tag in the IFD at tiff+ifd; -1 if missing. */
    private static long tagValue(byte[] d, int t, int end, long ifd, int tag, boolean le) {
        int e = entry(d, t, end, ifd, tag, le);
        return e < 0 ? -1 : u32(d, e + 8, le);
    }

    private static String ascii(byte[] d, int t, int end, long ifd, int tag, boolean le) {
        int e = entry(d, t, end, ifd, tag, le);
        if (e < 0 || u16(d, e + 2, le) != 2) return null;
        long n = u32(d, e + 4, le);
        if (n <= 0 || n > 64) return null;
        int at = n <= 4 ? e + 8 : (int) (t + u32(d, e + 8, le));
        if (at < 0 || at + n > end) return null;
        int k = 0;
        while (k < n && d[at + k] != 0) k++;
        return new String(d, at, k, StandardCharsets.US_ASCII);
    }

    private static int entry(byte[] d, int t, int end, long ifd, int tag, boolean le) {
        if (ifd < 8) return -1;
        int p = (int) (t + ifd);
        if (p + 2 > end) return -1;
        int n = u16(d, p, le);
        for (int i = 0; i < n; i++) {
            int e = p + 2 + 12 * i;
            if (e + 12 > end) return -1;
            if (u16(d, e, le) == tag) return e;
        }
        return -1;
    }

    private static int u16(byte[] d, int p, boolean le) {
        return le ? (d[p] & 0xFF) | (d[p + 1] & 0xFF) << 8 : (d[p] & 0xFF) << 8 | (d[p + 1] & 0xFF);
    }

    private static long u32(byte[] d, int p, boolean le) {
        if (p < 0 || p + 4 > d.length) return -1;
        return le ? (d[p] & 0xFFL) | (d[p + 1] & 0xFFL) << 8 | (d[p + 2] & 0xFFL) << 16 | (d[p + 3] & 0xFFL) << 24
                  : (d[p] & 0xFFL) << 24 | (d[p + 1] & 0xFFL) << 16 | (d[p + 2] & 0xFFL) << 8 | (d[p + 3] & 0xFFL);
    }

    // ------------------------------------------------------------------ screenshot of which app
    private static final Pattern HEX = Pattern.compile("[0-9a-f]{16,}");
    private static final Pattern PACKAGE = Pattern.compile("[a-z][a-z0-9_]*(\\.[a-z0-9_]+)+");

    /**
     * The app a screenshot shows, from its file name, or null when the name does not say. Phones write it there:
     * Samsung "Screenshot_20240115_123456_WhatsApp.jpg" (the app's name), Xiaomi and others
     * "Screenshot_2024-01-15-12-34-56-789_com.whatsapp.jpg" (the package, turned into the app's name when the app is
     * installed). Pixel's "Screenshot_20240115-123456.png" names no app; OnePlus and Oppo write a code that cannot be
     * turned back into a name.
     */
    public static String screenshotApp(String fileName, Map<String, AppItem> apps) {
        if (fileName == null) return null;
        String n = fileName;
        int dot = n.lastIndexOf('.');
        if (dot > 0) n = n.substring(0, dot);
        n = n.replaceAll("\\s*\\(\\d+\\)$", "").replaceAll("~\\d+$", "");          // "… (1)", "…~2": copies
        if (!n.toLowerCase(Locale.ROOT).startsWith("screenshot")) return null;
        String[] parts = n.split("_");
        String app = null;
        for (int i = 1; i < parts.length; i++) {
            String p = parts[i].trim();
            if (p.isEmpty() || p.matches("[0-9.:\\-]+")) continue;                   // date and time
            app = app == null ? p : app + "_" + p;                                  // an app name with "_" in it
        }
        if (app == null || HEX.matcher(app.toLowerCase(Locale.ROOT)).matches()) return null;
        if (PACKAGE.matcher(app).matches()) {
            AppItem a = apps == null ? null : apps.get(app);
            return a != null && a.label != null && !a.label.isEmpty() ? a.label : app;
        }
        return app;
    }

    // ------------------------------------------------------------------ the kind of code
    public static final String CODE_NONE = "none", CODE_UPI = "UPI payment", CODE_LINK = "link", CODE_WIFI = "Wi-Fi",
            CODE_CONTACT = "contact", CODE_EMAIL = "e-mail", CODE_PHONE = "phone number", CODE_BARCODE = "barcode", CODE_TEXT = "text";
    public static final String[] CODE_TYPES = {CODE_NONE, CODE_UPI, CODE_LINK, CODE_WIFI, CODE_CONTACT, CODE_EMAIL, CODE_PHONE, CODE_BARCODE, CODE_TEXT};

    /** The kind of code from what the sorter wrote about it (Codes.describe), CODE_NONE for no code. */
    public static String codeType(String code) {
        if (code == null) return CODE_NONE;
        if (code.startsWith("UPI payment")) return CODE_UPI;
        if (code.startsWith("Link to")) return CODE_LINK;
        if (code.startsWith("Wi-Fi network")) return CODE_WIFI;
        if (code.startsWith("Contact card")) return CODE_CONTACT;
        if (code.startsWith("E-mail address")) return CODE_EMAIL;
        if (code.startsWith("Phone number")) return CODE_PHONE;
        if (code.startsWith("Barcode")) return CODE_BARCODE;
        return CODE_TEXT;
    }
}
