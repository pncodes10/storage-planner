package com.storageplanner.test;

import com.storageplanner.core.*;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.TimeZone;

import javax.imageio.ImageIO;

/**
 * The five tags from the file and the gallery: favourite, date taken (EXIF), screenshot of which app, Motion Photo and
 * its video size, and the kind of code; read in a scan, saved with the results, and the built-in keep rule for favourites.
 */
public final class PhotoMetaTest {
    static int checks, fails;

    static void check(String what, boolean ok) {
        checks++;
        if (!ok) { fails++; System.out.println("FAIL " + what); }
    }

    // ------------------------------------------------------------------ building test files
    static byte[] ascii(String s) { return s.getBytes(StandardCharsets.ISO_8859_1); }

    static byte[] cat(byte[]... parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] p : parts) o.write(p, 0, p.length);
        return o.toByteArray();
    }

    static byte[] jpeg(int seed, byte[]... segments) throws IOException {
        BufferedImage img = new BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB);
        Random r = new Random(seed);
        for (int y = 0; y < 48; y++) for (int x = 0; x < 64; x++) img.setRGB(x, y, (x * 4) << 16 | (y * 5) << 8 | r.nextInt(64));
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", o);
        byte[] j = o.toByteArray();
        ByteArrayOutputStream w = new ByteArrayOutputStream();
        w.write(j, 0, 2);
        for (byte[] s : segments) w.write(s);
        w.write(j, 2, j.length - 2);
        return w.toByteArray();
    }

    static byte[] app(int marker, byte[] payload) {
        byte[] b = new byte[4 + payload.length];
        b[0] = (byte) 0xFF; b[1] = (byte) marker;
        b[2] = (byte) ((payload.length + 2) >> 8); b[3] = (byte) (payload.length + 2);
        System.arraycopy(payload, 0, b, 4, payload.length);
        return b;
    }

    /** An EXIF APP1 segment with DateTimeOriginal (and OffsetTimeOriginal when off is not null), in either byte order. */
    static byte[] exif(String when, String off, boolean le) {
        // TIFF: header(8) IFD0 at 8: 1 entry (ExifIFD pointer) + next(4) = 2+12+4 = 18 -> Exif IFD at 26
        int exifIfd = 26, n = off == null ? 1 : 2, data = exifIfd + 2 + 12 * n + 4;
        byte[] w = ascii(when + "\0"), o = off == null ? new byte[0] : ascii(off + "\0");
        byte[] t = new byte[data + w.length + o.length];
        t[0] = t[1] = (byte) (le ? 'I' : 'M');
        u16(t, 2, 42, le); u32(t, 4, 8, le);
        u16(t, 8, 1, le); entry(t, 10, 0x8769, 4, 1, exifIfd, le); u32(t, 22, 0, le);
        u16(t, exifIfd, n, le);
        entry(t, exifIfd + 2, 0x9003, 2, w.length, data, le);
        if (off != null) entry(t, exifIfd + 14, 0x9011, 2, o.length, data + w.length, le);
        System.arraycopy(w, 0, t, data, w.length);
        System.arraycopy(o, 0, t, data + w.length, o.length);
        return app(0xE1, cat(ascii("Exif\0\0"), t));
    }

    static void entry(byte[] t, int p, int tag, int type, long count, long value, boolean le) {
        u16(t, p, tag, le); u16(t, p + 2, type, le); u32(t, p + 4, count, le); u32(t, p + 8, value, le);
    }
    static void u16(byte[] b, int p, int v, boolean le) { if (le) { b[p] = (byte) v; b[p + 1] = (byte) (v >> 8); } else { b[p] = (byte) (v >> 8); b[p + 1] = (byte) v; } }
    static void u32(byte[] b, int p, long v, boolean le) { for (int i = 0; i < 4; i++) b[p + i] = (byte) (le ? v >> (8 * i) : v >> (8 * (3 - i))); }

    static byte[] box(String type, byte[] payload) {
        byte[] b = new byte[8 + payload.length];
        int n = b.length;
        b[0] = (byte) (n >>> 24); b[1] = (byte) (n >>> 16); b[2] = (byte) (n >>> 8); b[3] = (byte) n;
        System.arraycopy(ascii(type), 0, b, 4, 4);
        System.arraycopy(payload, 0, b, 8, payload.length);
        return b;
    }

    /** A Google Motion Photo: a JPEG with the Motion Photo XMP and a short MP4 appended. */
    static byte[] motion(int videoBytes) throws IOException {
        byte[] data = new byte[videoBytes];
        new Random(5).nextBytes(data);
        byte[] mp4 = cat(box("ftyp", ascii("isom\0\0\2\0isomiso2mp41")), box("moov", box("mvhd", new byte[100])), box("mdat", data));
        String xmp = "<?xpacket begin=\"\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?><x:xmpmeta xmlns:x=\"adobe:ns:meta/\">"
                + "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">"
                + "<rdf:Description rdf:about=\"\" xmlns:GCamera=\"http://ns.google.com/photos/1.0/camera/\""
                + " xmlns:Container=\"http://ns.google.com/photos/1.0/container/\" xmlns:Item=\"http://ns.google.com/photos/1.0/container/item/\""
                + " GCamera:MotionPhoto=\"1\" GCamera:MotionPhotoVersion=\"1\" GCamera:MotionPhotoPresentationTimestampUs=\"733000\">"
                + "<Container:Directory><rdf:Seq>"
                + "<rdf:li rdf:parseType=\"Resource\"><Container:Item Item:Mime=\"image/jpeg\" Item:Semantic=\"Primary\" Item:Length=\"0\" Item:Padding=\"0\"/></rdf:li>"
                + "<rdf:li rdf:parseType=\"Resource\"><Container:Item Item:Mime=\"video/mp4\" Item:Semantic=\"MotionPhoto\" Item:Length=\"" + mp4.length + "\" Item:Padding=\"0\"/></rdf:li>"
                + "</rdf:Seq></Container:Directory></rdf:Description></rdf:RDF></x:xmpmeta>" + new String(new char[200]).replace('\0', ' ') + "<?xpacket end=\"w\"?>";
        return cat(jpeg(3, app(0xE1, cat(ascii("http://ns.adobe.com/xap/1.0/\0"), ascii(xmp)))), mp4);
    }

    static void write(File f, byte[] b) throws IOException { f.getParentFile().mkdirs(); Files.write(f.toPath(), b); }

    public static void main(String[] args) throws Exception {
        TimeZone ist = TimeZone.getTimeZone("Asia/Kolkata"), utc = TimeZone.getTimeZone("UTC");
        // ---------------------------------------------------------------- date taken
        long want = 1700000000000L;                                        // 2023-11-14 22:13:20 UTC
        check("date taken: little-endian EXIF, phone time zone", PhotoMeta.taken(jpeg(1, exif("2023:11:14 22:13:20", null, true)), utc) == want);
        check("date taken: big-endian EXIF", PhotoMeta.taken(jpeg(1, exif("2023:11:14 22:13:20", null, false)), utc) == want);
        check("date taken: the phone's time zone is used without an offset", PhotoMeta.taken(jpeg(1, exif("2023:11:15 03:43:20", null, true)), ist) == want);
        check("date taken: the offset the camera wrote wins over the phone's zone", PhotoMeta.taken(jpeg(1, exif("2023:11:15 03:43:20", "+05:30", true)), utc) == want);
        check("date taken: a blank date (0000:00:00) is no date", PhotoMeta.taken(jpeg(1, exif("0000:00:00 00:00:00", null, true)), utc) == 0);
        check("date taken: a JPEG without EXIF has none", PhotoMeta.taken(jpeg(1), utc) == 0);
        check("date taken: not a JPEG has none", PhotoMeta.taken(ascii("\u0089PNG\r\n\u001a\n...."), utc) == 0);
        check("date taken: a missing file cannot be read", PhotoMeta.taken("/no/such/file.jpg", utc) == -1);
        boolean safe = true;
        Random rnd = new Random(1);
        for (int i = 0; i < 300; i++) {                                    // cut or damaged EXIF never throws
            byte[] j = jpeg(2, exif("2023:11:14 22:13:20", "+01:00", rnd.nextBoolean()));
            byte[] cut = java.util.Arrays.copyOf(j, 4 + rnd.nextInt(120));
            for (int k = 0; k < 3; k++) cut[4 + rnd.nextInt(cut.length - 4)] = (byte) rnd.nextInt(256);
            try { long t = PhotoMeta.taken(cut, utc); safe &= t >= 0; } catch (Throwable e) { safe = false; }
        }
        check("date taken: cut or damaged EXIF never throws", safe);

        // ---------------------------------------------------------------- screenshot of which app
        Map<String, AppItem> apps = new HashMap<String, AppItem>();
        apps.put("com.whatsapp", new AppItem("com.whatsapp", "WhatsApp"));
        check("screenshot app: Samsung writes the app's name", "WhatsApp".equals(PhotoMeta.screenshotApp("Screenshot_20240115_123456_WhatsApp.jpg", apps)));
        check("screenshot app: older Samsung", "Chrome".equals(PhotoMeta.screenshotApp("Screenshot_20190101-120000_Chrome.jpg", apps)));
        check("screenshot app: Xiaomi writes the package, shown as the installed app's name",
                "WhatsApp".equals(PhotoMeta.screenshotApp("Screenshot_2024-01-15-12-34-56-789_com.whatsapp.jpg", apps)));
        check("screenshot app: a package that is not installed stays a package",
                "in.swiggy.android".equals(PhotoMeta.screenshotApp("Screenshot_2024-01-15-12-34-56-789_in.swiggy.android.jpg", apps)));
        check("screenshot app: Pixel names none", PhotoMeta.screenshotApp("Screenshot_20240115-123456.png", apps) == null);
        check("screenshot app: OnePlus's code is not a name",
                PhotoMeta.screenshotApp("Screenshot_2024-01-15-12-34-56-78_965bbf4d18d205f782c6b8409c5773a4.jpg", apps) == null);
        check("screenshot app: a copy's (1) is dropped", "Paytm".equals(PhotoMeta.screenshotApp("Screenshot_20240115_123456_Paytm (1).jpg", apps)));
        check("screenshot app: app names with spaces or _ stay whole", "Google Pay".equals(PhotoMeta.screenshotApp("Screenshot_20240115_123456_Google Pay.jpg", apps))
                && "My_App".equals(PhotoMeta.screenshotApp("Screenshot_20240115_123456_My_App.jpg", apps)));
        check("screenshot app: not a screenshot's name", PhotoMeta.screenshotApp("IMG_20240115_123456.jpg", apps) == null);

        // ---------------------------------------------------------------- the kind of code
        check("code type: from what the sorter wrote", PhotoMeta.codeType(Codes.describe("QR_CODE", "upi://pay?pa=shop@okaxis&pn=Shop")).equals(PhotoMeta.CODE_UPI)
                && PhotoMeta.codeType(Codes.describe("QR_CODE", "https://example.com/x")).equals(PhotoMeta.CODE_LINK)
                && PhotoMeta.codeType(Codes.describe("QR_CODE", "WIFI:S:Home;T:WPA;P:x;;")).equals(PhotoMeta.CODE_WIFI)
                && PhotoMeta.codeType(Codes.describe("QR_CODE", "BEGIN:VCARD\nFN:A\nEND:VCARD")).equals(PhotoMeta.CODE_CONTACT)
                && PhotoMeta.codeType(Codes.describe("QR_CODE", "mailto:a@b.c")).equals(PhotoMeta.CODE_EMAIL)
                && PhotoMeta.codeType(Codes.describe("QR_CODE", "tel:+911234")).equals(PhotoMeta.CODE_PHONE)
                && PhotoMeta.codeType(Codes.describe("EAN_13", "8901234567890")).equals(PhotoMeta.CODE_BARCODE)
                && PhotoMeta.codeType(Codes.describe("QR_CODE", "hello")).equals(PhotoMeta.CODE_TEXT)
                && PhotoMeta.codeType(null).equals(PhotoMeta.CODE_NONE));

        // ---------------------------------------------------------------- a scan of a small phone
        File dev = Files.createTempDirectory("meta").toFile(), sd = new File(dev, "sdcard");
        write(new File(dev, "meta/usagestats.json"), ascii("{}"));
        write(new File(sd, "DCIM/Camera/IMG_1.jpg"), jpeg(1, exif("2023:11:14 22:13:20", "+00:00", true)));
        write(new File(sd, "DCIM/Camera/IMG_2.jpg"), jpeg(2));
        byte[] mp = motion(40000);
        write(new File(sd, "DCIM/Camera/MOTION_3.jpg"), mp);
        write(new File(sd, "DCIM/Screenshots/Screenshot_20240115_123456_WhatsApp.jpg"), jpeg(4));
        write(new File(sd, "Download/poster.jpg"), jpeg(5, exif("2022:01:01 10:00:00", null, true)));
        write(new File(dev, "meta/favourites.txt"), ascii("DCIM/Camera/IMG_2.jpg\n"));
        JvmPlatform pf = new JvmPlatform(dev.getPath());
        Scanner.Progress quiet = new Scanner.Progress() { public void update(String s, int d, int t) {} public boolean cancelled() { return false; } };
        Scanner.Result r = Scanner.scan(pf, quiet, new Scanner.Options());
        Map<String, FileItem> by = new HashMap<String, FileItem>();
        for (FileItem f : r.files) by.put(f.rel, f);
        FileItem a = by.get("DCIM/Camera/IMG_1.jpg"), b = by.get("DCIM/Camera/IMG_2.jpg"), m = by.get("DCIM/Camera/MOTION_3.jpg"),
                s = by.get("DCIM/Screenshots/Screenshot_20240115_123456_WhatsApp.jpg"), d = by.get("Download/poster.jpg");
        check("scan: date taken read", a.taken == want && b.taken == 0);
        check("scan: favourites from the gallery", Boolean.TRUE.equals(b.favourite) && Boolean.FALSE.equals(a.favourite));
        check("scan: Motion Photo and the size of its video", m.motionBytes > 40000 && m.motionBytes < 41000 && a.motionBytes == 0);
        check("scan: only camera photos are checked for a video", d.motionBytes == -1 && s.motionBytes == -1);
        check("scan: downloads get a date taken too (when they have one)", d.taken > 0);

        UnitBuilder ub = new UnitBuilder(r, new Estimates(), 34);
        Tags.Context ctx = Tags.context(r, ub.det);
        long now = r.now;
        Tags.T ta = Tags.of(a, ctx, now), tb = Tags.of(b, ctx, now), tm = Tags.of(m, ctx, now), ts = Tags.of(s, ctx, now), td = Tags.of(d, ctx, now);
        check("tags: favourite yes / no", Boolean.TRUE.equals(tb.flagTag(Tags.FAVOURITE)) && Boolean.FALSE.equals(ta.flagTag(Tags.FAVOURITE)));
        check("tags: age since taken", Math.abs(ta.numTag(Tags.TAKEN_DAYS) - (now - want) / 86400000.0) < 1e-6 && tb.numTag(Tags.TAKEN_DAYS) == Tags.UNKNOWN);
        check("tags: motion photo and its video in MB", Boolean.TRUE.equals(tm.flagTag(Tags.MOTION_PHOTO)) && tm.numTag(Tags.MOTION_VIDEO_MB) > 0.04
                && Boolean.FALSE.equals(ta.flagTag(Tags.MOTION_PHOTO)) && ta.numTag(Tags.MOTION_VIDEO_MB) == 0 && td.flagTag(Tags.MOTION_PHOTO) == null);
        check("tags: screenshot of app", "WhatsApp".equals(ts.textTag(Tags.SCREENSHOT_APP)) && ta.textTag(Tags.SCREENSHOT_APP) == null);
        check("tags: code type for a sorted picture without a code is none", a.category == null || PhotoMeta.CODE_NONE.equals(ta.textTag(Tags.CODE_TYPE)));
        check("tags: all five can be used in rules", Tags.typeOf(Tags.FAVOURITE) == Tags.Type.FLAG && Tags.typeOf(Tags.TAKEN_DAYS) == Tags.Type.NUMBER
                && Tags.typeOf(Tags.SCREENSHOT_APP) == Tags.Type.TEXT && Tags.typeOf(Tags.MOTION_PHOTO) == Tags.Type.FLAG
                && Tags.typeOf(Tags.MOTION_VIDEO_MB) == Tags.Type.NUMBER && Tags.typeOf(Tags.CODE_TYPE) == Tags.Type.TEXT
                && Rule.Condition.parse("screenshot_app = whatsapp").matches(ts) && Rule.Condition.parse("taken_days > 100").matches(ta)
                && Rule.Condition.parse("motion_video_mb >= 0.04").matches(tm) && !Rule.Condition.parse("taken_days > 0").matches(tb));

        // ---------------------------------------------------------------- the favourites keep rule
        boolean favKept = true;
        for (Unit u : ub.units) if (u.id.equals(b.rel)) favKept = u.options.isEmpty();
        Option aOpt = null;
        for (Unit u : ub.units) if (u.id.equals(a.rel) && !u.options.isEmpty()) aOpt = u.options.get(0);
        check("a favourite gets no suggestion (built-in keep rule)", favKept);
        RuleSet off = RuleSet.defaults();
        off.find("keep_favourites").enabled = false;
        UnitBuilder ub2 = new UnitBuilder(r, new Estimates(), 34, off);
        boolean back = false;
        for (Unit u : ub2.units) if (u.id.equals(b.rel)) back = !u.options.isEmpty();
        check("switched off, the favourite gets its suggestion back (" + (aOpt == null ? "none" : aOpt.technique) + ")", back == (aOpt != null));

        // ---------------------------------------------------------------- saved with the results
        ResultStore.Saved sv = new ResultStore.Saved();
        sv.result = r; sv.est = new Estimates();
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        ResultStore.write(o, sv, ResultStore.VERSION);
        Map<String, FileItem> l = new HashMap<String, FileItem>();
        for (FileItem f : ResultStore.read(new ByteArrayInputStream(o.toByteArray())).result.files) l.put(f.rel, f);
        check("saved: favourite, date taken and Motion Photo video come back", Boolean.TRUE.equals(l.get(b.rel).favourite) && Boolean.FALSE.equals(l.get(a.rel).favourite)
                && l.get(a.rel).taken == want && l.get(m.rel).motionBytes == m.motionBytes && l.get(d.rel).motionBytes == -1);
        ByteArrayOutputStream o8 = new ByteArrayOutputStream();
        ResultStore.write(o8, sv, 8);
        Map<String, FileItem> l8 = new HashMap<String, FileItem>();
        for (FileItem f : ResultStore.read(new ByteArrayInputStream(o8.toByteArray())).result.files) l8.put(f.rel, f);
        check("a format 8 file loads with these unknown (read again at the next scan)", l8.get(b.rel).favourite == null && l8.get(a.rel).taken == -1 && l8.get(m.rel).motionBytes == -1);

        Scanner.Options again = new Scanner.Options();
        again.previous = by;
        Scanner.Result r2 = Scanner.scan(pf, quiet, again);
        boolean kept = true;
        for (FileItem f : r2.files) { FileItem p = by.get(f.rel); kept &= f.taken == p.taken && f.motionBytes == p.motionBytes; }
        check("unchanged pictures keep their date and video size from the last scan", kept);

        System.out.println((checks - fails) + "/" + checks + " photo detail checks pass");
        if (fails > 0) System.exit(1);
    }
}
