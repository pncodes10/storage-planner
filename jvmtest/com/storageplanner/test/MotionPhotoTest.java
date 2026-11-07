package com.storageplanner.test;

import com.storageplanner.tools.MotionPhoto;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Random;

import javax.imageio.ImageIO;

/**
 * Desktop tests for tools/MotionPhoto: Google (JPEG, HEIC, MicroVideo), Samsung and Ultra HDR motion photos lose
 * their video and nothing else; ordinary photos, damaged files and anything unclear are left alone.
 */
public final class MotionPhotoTest {
    static int failed = 0, passed = 0;

    static void check(String what, boolean ok) {
        if (ok) passed++; else { failed++; System.out.println("FAIL " + what); }
    }

    public static void main(String[] a) throws Exception {
        File dir = Files.createTempDirectory("motiontest").toFile();
        try {
            google(dir);
            ultraHdr(dir);
            microVideo(dir);
            samsung(dir, false);
            samsung(dir, true);
            samsungWithXmp(dir);
            heic(dir);
            notMotion(dir);
            wrongLength(dir);
            refusals(dir);
            String[] left = dir.list();
            boolean noWork = true;
            for (String n : left) if (n.startsWith(".sp-")) noWork = false;
            check("no work files are left behind", noWork);
        } finally {
            for (File f : dir.listFiles()) f.delete();
            dir.delete();
        }
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    // ------------------------------------------------------------------ building test files
    static final byte[] MP4 = mp4(30000, 1);

    static byte[] mp4(int n, long seed) {
        byte[] data = new byte[n];
        new Random(seed).nextBytes(data);
        return cat(box("ftyp", ascii("isom\0\0\2\0isomiso2mp41")), box("moov", box("mvhd", new byte[100])), box("mdat", data));
    }

    static byte[] box(String type, byte[] payload) {
        byte[] b = new byte[8 + payload.length];
        int n = b.length;
        b[0] = (byte) (n >>> 24); b[1] = (byte) (n >>> 16); b[2] = (byte) (n >>> 8); b[3] = (byte) n;
        System.arraycopy(ascii(type), 0, b, 4, 4);
        System.arraycopy(payload, 0, b, 8, payload.length);
        return b;
    }

    /** A real JPEG (ImageIO), with {@code segments} (whole APPn segments) inserted after its start marker. */
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

    static byte[] xmpSegment(String xmp) { return app(0xE1, cat(ascii("http://ns.adobe.com/xap/1.0/\0"), ascii(xmp))); }

    static String xmp(String attrs, String items) {
        return "<?xpacket begin=\"\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?><x:xmpmeta xmlns:x=\"adobe:ns:meta/\">"
                + "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">"
                + "<rdf:Description rdf:about=\"\" xmlns:GCamera=\"http://ns.google.com/photos/1.0/camera/\""
                + " xmlns:Container=\"http://ns.google.com/photos/1.0/container/\" xmlns:Item=\"http://ns.google.com/photos/1.0/container/item/\" "
                + attrs + ">"
                + (items == null ? "" : "<Container:Directory><rdf:Seq>" + items + "</rdf:Seq></Container:Directory>")
                + "</rdf:Description></rdf:RDF></x:xmpmeta>" + spaces(200) + "<?xpacket end=\"w\"?>";
    }

    static String item(String mime, String semantic, long length, long padding) {
        return "<rdf:li rdf:parseType=\"Resource\"><Container:Item Item:Mime=\"" + mime + "\" Item:Semantic=\"" + semantic + "\""
                + " Item:Length=\"" + length + "\" Item:Padding=\"" + padding + "\"/></rdf:li>";
    }

    static String googleXmp(long videoLength) {
        return xmp("GCamera:MotionPhoto=\"1\" GCamera:MotionPhotoVersion=\"1\" GCamera:MotionPhotoPresentationTimestampUs=\"733000\"",
                item("image/jpeg", "Primary", 0, 0) + item("video/mp4", "MotionPhoto", videoLength, 0));
    }

    // ------------------------------------------------------------------ helpers
    static byte[] ascii(String s) { return s.getBytes(StandardCharsets.ISO_8859_1); }

    static String spaces(int n) { char[] c = new char[n]; Arrays.fill(c, ' '); return new String(c); }

    static byte[] cat(byte[]... parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] p : parts) o.write(p, 0, p.length);
        return o.toByteArray();
    }

    static File write(File f, byte[] b) throws IOException { Files.write(f.toPath(), b); return f; }

    static byte[] bytes(File f) throws IOException { return Files.readAllBytes(f.toPath()); }

    static byte[] bytesOrEmpty(String path) throws IOException { return path == null ? new byte[0] : bytes(new File(path)); }

    static String str(byte[] b) { return new String(b, StandardCharsets.ISO_8859_1); }

    static int indexOf(byte[] b, byte[] k) { return str(b).indexOf(str(k)); }

    /** Same pixels when decoded. */
    static boolean samePixels(byte[] x, byte[] y) throws IOException {
        BufferedImage a = ImageIO.read(new ByteArrayInputStream(x)), b = ImageIO.read(new ByteArrayInputStream(y));
        if (a == null || b == null || a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) return false;
        for (int yy = 0; yy < a.getHeight(); yy++) for (int xx = 0; xx < a.getWidth(); xx++) if (a.getRGB(xx, yy) != b.getRGB(xx, yy)) return false;
        return true;
    }

    /** Equal except inside [from, to), where they must be the same length. */
    static boolean sameOutside(byte[] x, byte[] y, int from, int to) {
        if (x.length != y.length) return false;
        for (int i = 0; i < x.length; i++) if ((i < from || i >= to) && x[i] != y[i]) return false;
        return true;
    }

    static MotionPhoto.Outcome strip(File dir, String name, byte[] data, boolean video) throws IOException {
        File in = write(new File(dir, name), data);
        in.setLastModified(1500000000000L);
        File out = new File(dir, "out-" + name), v = new File(dir, "out-" + name + ".mp4");
        out.delete(); v.delete();
        MotionPhoto.Outcome o = MotionPhoto.strip(in, out, video ? v : null);
        check(name + ": the original is not changed", Arrays.equals(bytes(in), data));
        return o;
    }

    // ------------------------------------------------------------------ cases
    static void google(File dir) throws Exception {
        String x = googleXmp(MP4.length);
        byte[] photo = jpeg(1, xmpSegment(x));
        byte[] file = cat(photo, MP4);
        MotionPhoto.Found f = MotionPhoto.find(write(new File(dir, "g-find.jpg"), file));
        check("google: found", f.motion && f.flagged && "JPEG".equals(f.format) && "Google Motion Photo".equals(f.kind));
        check("google: picture end", f.imageEnd == photo.length);
        check("google: video bytes", f.videoBytes() == MP4.length && f.newSize() == photo.length);

        MotionPhoto.Outcome o = strip(dir, "g.jpg", file, true);
        check("google: ok " + o.skipped, o.ok);
        byte[] out = bytes(new File(o.output));
        check("google: copy is the photo's size", out.length == photo.length && o.saved() == MP4.length);
        int xs = indexOf(photo, ascii("<x:xmpmeta")), xe = indexOf(photo, ascii("</x:xmpmeta>"));
        check("google: only the XMP changed", sameOutside(out, photo, xs, xe));
        String s = str(out);
        check("google: flag cleared", s.contains("GCamera:MotionPhoto=\"0\"") && !s.contains("GCamera:MotionPhoto=\"1\""));
        check("google: version and timestamp kept", s.contains("MotionPhotoVersion=\"1\"") && s.contains("PresentationTimestampUs=\"733000\""));
        check("google: video item gone, primary kept", !s.contains("Semantic=\"MotionPhoto\"") && s.contains("Semantic=\"Primary\""));
        check("google: XMP still well formed", s.contains("<rdf:Seq>") && s.contains("</rdf:Seq></Container:Directory>"));
        check("google: same pixels", samePixels(out, photo));
        check("google: video saved whole", Arrays.equals(bytes(new File(o.video)), MP4));
        check("google: dates kept", new File(o.output).lastModified() == 1500000000000L);
        MotionPhoto.Found g = MotionPhoto.find(new File(o.output));
        check("google: copy is an ordinary photo", !g.motion && !g.flagged && g.why == null);
        MotionPhoto.Outcome again = strip(dir, "g2.jpg", out, false);
        check("google: stripping the copy does nothing", !again.ok && again.skipped.startsWith("not a Motion Photo"));

        // the video is optional
        MotionPhoto.Outcome nv = strip(dir, "g3.jpg", file, false);
        check("google: without saving the video", nv.ok && nv.video == null && !new File(dir, "out-g3.jpg.mp4").exists());

        // element-style XMP
        String ex = googleXmp(MP4.length).replace("GCamera:MotionPhoto=\"1\" ", "").replace("</rdf:Description>",
                "<GCamera:MotionPhoto>1</GCamera:MotionPhoto></rdf:Description>")
                .replace("<Container:Item Item:Mime=\"video/mp4\" Item:Semantic=\"MotionPhoto\" Item:Length=\"" + MP4.length + "\" Item:Padding=\"0\"/>",
                        "<Container:Item><Item:Mime>video/mp4</Item:Mime><Item:Semantic>MotionPhoto</Item:Semantic><Item:Length>" + MP4.length + "</Item:Length></Container:Item>");
        MotionPhoto.Outcome eo = strip(dir, "g-elem.jpg", cat(jpeg(1, xmpSegment(ex)), MP4), false);
        String es = eo.ok ? str(bytes(new File(eo.output))) : "";
        check("google elements: ok " + eo.skipped, eo.ok && "Google Motion Photo".equals(eo.found.kind));
        check("google elements: flag cleared", es.contains("<GCamera:MotionPhoto>0</GCamera:MotionPhoto>") && !es.contains(">MotionPhoto<"));
    }

    static void ultraHdr(File dir) throws Exception {
        byte[] gain = jpeg(7);
        // layout: SOI, XMP, MPF (APP2), rest of primary; gain map; video
        String x = xmp("GCamera:MotionPhoto=\"1\"",
                item("image/jpeg", "Primary", 0, 0) + item("image/jpeg", "GainMap", gain.length, 0) + item("video/mp4", "MotionPhoto", MP4.length, 0));
        byte[] xs = xmpSegment(x);
        byte[] mpfProbe = mpf(0, 0, 0);
        int primaryLen = jpeg(1, xs, mpfProbe).length;
        long tiff = 2 + xs.length + 4 + 4;
        byte[] photo = jpeg(1, xs, mpf(primaryLen, gain.length, primaryLen - tiff));
        check("uhdr: layout", photo.length == primaryLen);
        byte[] file = cat(photo, gain, MP4);
        MotionPhoto.Outcome o = strip(dir, "u.jpg", file, true);
        check("uhdr: ok " + o.skipped, o.ok);
        byte[] out = bytes(new File(o.output));
        check("uhdr: gain map kept in place", out.length == photo.length + gain.length
                && Arrays.equals(Arrays.copyOfRange(out, photo.length, out.length), gain));
        String s = str(out);
        check("uhdr: gain map item kept", s.contains("Semantic=\"GainMap\"") && !s.contains("Semantic=\"MotionPhoto\""));
        check("uhdr: MPF unchanged", indexOf(out, mpf(primaryLen, gain.length, primaryLen - tiff)) > 0);
        check("uhdr: video saved", Arrays.equals(bytes(new File(o.video)), MP4));

        // a picture after the video would move: refuse
        byte[] tiffAfter = jpeg(1, xs, mpf(primaryLen, gain.length, primaryLen + MP4.length - tiff));
        MotionPhoto.Outcome bad = strip(dir, "u-bad.jpg", cat(tiffAfter, MP4, gain), false);
        check("uhdr: picture after the video refused", !bad.ok && !new File(dir, "out-u-bad.jpg").exists());
    }

    /** MPF APP2 listing the primary picture and one more at {@code off} from the TIFF header. */
    static byte[] mpf(long primary, long size2, long off) {
        byte[] t = new byte[8 + 2 + 12 + 4 + 32];
        t[0] = 'I'; t[1] = 'I'; t[2] = 42; le32(t, 4, 8);
        t[8] = 1;                                   // one entry
        t[10] = 0x02; t[11] = (byte) 0xB0;          // 0xB002 MPEntry
        t[12] = 7; le32(t, 14, 32); le32(t, 18, 26);
        le32(t, 26 + 4, primary);
        le32(t, 26 + 16 + 4, size2); le32(t, 26 + 16 + 8, off);
        return app(0xE2, cat(ascii("MPF\0"), t));
    }

    static void le32(byte[] b, int p, long v) { for (int i = 0; i < 4; i++) b[p + i] = (byte) (v >> (8 * i)); }

    static void microVideo(File dir) throws Exception {
        String x = xmp("GCamera:MicroVideo=\"1\" GCamera:MicroVideoVersion=\"1\" GCamera:MicroVideoOffset=\"" + MP4.length
                + "\" GCamera:MicroVideoPresentationTimestampUs=\"1000\"", null);
        byte[] photo = jpeg(2, xmpSegment(x));
        MotionPhoto.Outcome o = strip(dir, "m.jpg", cat(photo, MP4), true);
        check("micro: ok " + o.skipped, o.ok && o.found.kind.contains("MicroVideo"));
        byte[] out = bytes(new File(o.output));
        check("micro: photo size", out.length == photo.length);
        check("micro: flag cleared", str(out).contains("MicroVideo=\"0\"") && str(out).contains("MicroVideoVersion=\"1\""));
        check("micro: same pixels", samePixels(out, photo));
    }

    /** Samsung record: pad16 type16 nameLen32 name data. */
    static byte[] record(int type, String name, byte[] data) {
        byte[] h = new byte[8];
        h[2] = (byte) type; h[3] = (byte) (type >> 8);
        le32(h, 4, name.length());
        return cat(h, ascii(name), data);
    }

    /** Records laid out in order, then SEFH index (listing them in {@code order}), then the footer. */
    static byte[] sef(byte[][] recs, int[] types, int[] order) {
        int total = 0;
        for (byte[] r : recs) total += r.length;
        byte[] idx = new byte[12 + 12 * recs.length];
        System.arraycopy(ascii("SEFH"), 0, idx, 0, 4);
        le32(idx, 4, 106); le32(idx, 8, recs.length);
        int[] start = new int[recs.length];
        for (int i = 0, p = 0; i < recs.length; p += recs[i].length, i++) start[i] = p;
        for (int k = 0; k < order.length; k++) {
            int i = order[k], e = 12 + 12 * k;
            idx[e + 2] = (byte) types[i]; idx[e + 3] = (byte) (types[i] >> 8);
            le32(idx, e + 4, total - start[i]);
            le32(idx, e + 8, recs[i].length);
        }
        byte[] foot = new byte[8];
        le32(foot, 0, idx.length);
        System.arraycopy(ascii("SEFT"), 0, foot, 4, 4);
        return cat(cat(recs), idx, foot);
    }

    static void samsung(File dir, boolean videoFirst) throws Exception {
        byte[] photo = jpeg(3);
        byte[] utc = record(0x0001, "Image_UTC_Data", ascii("1699999999999"));
        byte[] info = record(0x0A01, "MCC_Data", ascii("234"));
        byte[] vid = record(0x0A30, "MotionPhoto_Data", MP4);
        byte[][] recs = videoFirst ? new byte[][]{vid, utc, info} : new byte[][]{utc, info, vid};
        int[] types = videoFirst ? new int[]{0x0A30, 0x0001, 0x0A01} : new int[]{0x0001, 0x0A01, 0x0A30};
        String n = videoFirst ? "s1.jpg" : "s2.jpg";
        MotionPhoto.Outcome o = strip(dir, n, cat(photo, sef(recs, types, new int[]{2, 0, 1})), true);
        check(n + ": ok " + o.skipped, o.ok && "Samsung Motion Photo".equals(o.found.kind));
        byte[] out = bytes(new File(o.output));
        check(n + ": photo kept byte for byte", Arrays.equals(Arrays.copyOf(out, photo.length), photo));
        check(n + ": video saved", Arrays.equals(bytes(new File(o.video)), MP4));
        check(n + ": saved about the video", o.saved() == vid.length + 12);
        check(n + ": no MotionPhoto_Data left", indexOf(out, ascii("MotionPhoto_Data")) < 0);
        // read the rewritten trailer independently
        int len = (int) le32(out, out.length - 8), sefh = out.length - 8 - len;
        boolean ok = str(out).endsWith("SEFT") && str(Arrays.copyOfRange(out, sefh, sefh + 4)).equals("SEFH") && le32(out, sefh + 8) == 2;
        java.util.Set<String> names = new java.util.HashSet<String>();
        for (int i = 0; ok && i < 2; i++) {
            int e = sefh + 12 + 12 * i;
            int start = sefh - (int) le32(out, e + 4), size = (int) le32(out, e + 8);
            int nl = (int) le32(out, start + 4);
            ok = start >= photo.length && size == 8 + nl + (nl == 14 ? 13 : 3) && out[start + 2] == out[e + 2] && out[start + 3] == out[e + 3];
            names.add(str(Arrays.copyOfRange(out, start + 8, start + 8 + nl)));
        }
        check(n + ": index lists the kept records at their new places", ok && names.contains("Image_UTC_Data") && names.contains("MCC_Data"));
        check(n + ": same pixels", samePixels(out, photo));
    }

    static long le32(byte[] b, int p) { long v = 0; for (int i = 0; i < 4; i++) v |= (long) (b[p + i] & 0xFF) << (8 * i); return v; }

    static void samsungWithXmp(File dir) throws Exception {
        byte[] vid = record(0x0A30, "MotionPhoto_Data", MP4);
        byte[] utc = record(0x0001, "Image_UTC_Data", ascii("1699999999999"));
        // newer Samsungs also write Google's XMP; its Length counts from the video to the end of the file
        byte[] photo = jpeg(4, xmpSegment(googleXmp(MP4.length + utc.length + 12 * 2 + 12 + 8)));
        MotionPhoto.Outcome o = strip(dir, "s3.jpg", cat(photo, sef(new byte[][]{vid, utc}, new int[]{0x0A30, 1}, new int[]{0, 1})), false);
        check("samsung+xmp: ok " + o.skipped, o.ok && "Samsung Motion Photo".equals(o.found.kind));
        String s = o.ok ? str(bytes(new File(o.output))) : "";
        check("samsung+xmp: flag cleared", s.contains("MotionPhoto=\"0\"") && !s.contains("Semantic=\"MotionPhoto\""));
    }

    static void heic(File dir) throws Exception {
        String x = xmp("GCamera:MotionPhoto=\"1\" GCamera:MotionPhotoVersion=\"1\"",
                item("image/heic", "Primary", 0, 8) + item("video/mp4", "MotionPhoto", MP4.length, 0));
        byte[] ftyp = box("ftyp", ascii("heic\0\0\0\0mif1heic"));
        byte[] meta = box("meta", new byte[300]);
        byte[] mdat = box("mdat", cat(new byte[500], ascii(x), new byte[2000]));
        byte[] image = cat(ftyp, meta, mdat);
        byte[] file = cat(image, box("mpvd", MP4));
        MotionPhoto.Outcome o = strip(dir, "h.heic", file, true);
        check("heic: ok " + o.skipped, o.ok && "HEIC".equals(o.found.format));
        byte[] out = bytes(new File(o.output));
        int xs = indexOf(image, ascii("<x:xmpmeta")), xe = indexOf(image, ascii("</x:xmpmeta>"));
        check("heic: the boxes before the video are kept", sameOutside(out, image, xs, xe));
        check("heic: flag cleared", str(out).contains("MotionPhoto=\"0\"") && !str(out).contains("Semantic=\"MotionPhoto\""));
        check("heic: video saved", Arrays.equals(bytes(new File(o.video)), MP4));

        // a box after mpvd would move (its data offsets would point at the wrong place): refuse
        MotionPhoto.Outcome bad = strip(dir, "h2.heic", cat(file, box("free", new byte[10])), false);
        check("heic: box after the video refused", !bad.ok && bad.skipped.contains("boxes follow"));
        // a plain MP4 video is not a photo
        MotionPhoto.Outcome v = strip(dir, "v.mp4", MP4, false);
        check("mp4 video: left alone", !v.ok && v.skipped.startsWith("not a Motion Photo"));
    }

    static void notMotion(File dir) throws Exception {
        byte[] plain = jpeg(5);
        MotionPhoto.Outcome o = strip(dir, "p.jpg", plain, false);
        check("plain: left alone", !o.ok && o.skipped.startsWith("not a Motion Photo") && !new File(dir, "out-p.jpg").exists());

        byte[] flagged = jpeg(5, xmpSegment(googleXmp(MP4.length)));
        MotionPhoto.Outcome f = strip(dir, "f.jpg", flagged, false);
        check("flag without a video: left alone", !f.ok && f.skipped.contains("no video"));

        MotionPhoto.Outcome junk = strip(dir, "j.jpg", cat(flagged, MP4, ascii("unknown data")), false);
        check("data after the video: refused", !junk.ok && junk.skipped.contains("follow the video"));

        // box-shaped data after the video is not video: Item:Length says where the video ends
        MotionPhoto.Outcome boxy = strip(dir, "b.jpg", cat(flagged, MP4, box("abcd", new byte[50])), false);
        check("box-shaped data after the video: refused, not removed", !boxy.ok && boxy.skipped.contains("follow the video"));
        MotionPhoto.Outcome boxy2 = strip(dir, "b2.jpg", cat(jpeg(5, xmpSegment(googleXmp(MP4.length + 7))), MP4, box("abcd", new byte[50])), false);
        check("box-shaped data after the video, wrong Length: refused", !boxy2.ok && boxy2.skipped.contains("follow the video"));
        MotionPhoto.Outcome boxy3 = strip(dir, "b3.jpg", cat(flagged, MP4, box("free", new byte[50])), false);
        check("an MP4 'free' (padding) box after the video goes with it", boxy3.ok && boxy3.after == flagged.length);

        MotionPhoto.Outcome pad = strip(dir, "z.jpg", cat(flagged, MP4, new byte[64]), false);
        check("zero padding after the video: removed too", pad.ok && pad.after == flagged.length);

        byte[] cut = Arrays.copyOf(flagged, flagged.length - 10);
        MotionPhoto.Outcome c = strip(dir, "c.jpg", cut, false);
        check("cut-off photo: refused", !c.ok && c.skipped.contains("cut off"));

        MotionPhoto.Outcome notVideo = strip(dir, "n.jpg", cat(jpeg(5, xmpSegment(googleXmp(5000))), new byte[]{1, 2, 3}, new byte[4997]), false);
        check("Length pointing at something that is not a video: left alone", !notVideo.ok && notVideo.skipped.contains("no video"));

        MotionPhoto.Outcome tiny = strip(dir, "t.jpg", new byte[]{1, 2, 3}, false);
        check("not an image: left alone", !tiny.ok);
    }

    static void wrongLength(File dir) throws Exception {
        byte[] photo = jpeg(6, xmpSegment(googleXmp(MP4.length + 100)));
        MotionPhoto.Outcome o = strip(dir, "w.jpg", cat(photo, MP4), true);
        check("Item:Length off: video found by its boxes " + o.skipped, o.ok && o.after == photo.length && o.found.kind.contains("found after"));
        String x = xmp("GCamera:MotionPhoto=\"1\"", item("image/jpeg", "Primary", 0, 50) + item("video/mp4", "MotionPhoto", MP4.length + 100, 0));
        byte[] photo2 = jpeg(6, xmpSegment(x));
        MotionPhoto.Outcome o2 = strip(dir, "w2.jpg", cat(photo2, MP4), true);
        check("Length and Padding off: video found by its boxes " + o2.skipped, o2.ok && o2.after == photo2.length && o2.found.kind.contains("found after")
                && Arrays.equals(bytesOrEmpty(o2.video), MP4));
    }

    static void refusals(File dir) throws Exception {
        byte[] file = cat(jpeg(1, xmpSegment(googleXmp(MP4.length))), MP4);
        File in = write(new File(dir, "r.jpg"), file);
        File exists = write(new File(dir, "exists.jpg"), new byte[]{1});
        MotionPhoto.Outcome a = MotionPhoto.strip(in, exists, null);
        check("refuse: copy exists", !a.ok && a.skipped.contains("already exists") && bytes(exists).length == 1);
        MotionPhoto.Outcome b = MotionPhoto.strip(in, in, null);
        check("refuse: copy over the original", !b.ok && Arrays.equals(bytes(in), file));
        MotionPhoto.Outcome c = MotionPhoto.strip(in, new File(dir, "missing/out.jpg"), null);
        check("refuse: missing folder", !c.ok && c.skipped.contains("does not exist"));
        MotionPhoto.Outcome d = MotionPhoto.strip(in, new File(dir, "r-out.jpg"), exists);
        check("refuse: video file exists", !d.ok && !new File(dir, "r-out.jpg").exists());
        MotionPhoto.Outcome e = MotionPhoto.strip(in, new File(dir, "r-out.jpg"), new File(dir, "r-out.jpg"));
        check("refuse: copy and video the same file", !e.ok && !new File(dir, "r-out.jpg").exists());
        MotionPhoto.Outcome f = MotionPhoto.strip(new File(dir, "nothing.jpg"), new File(dir, "r-out.jpg"), null);
        check("refuse: missing original", !f.ok && f.skipped.contains("cannot read"));
    }
}
