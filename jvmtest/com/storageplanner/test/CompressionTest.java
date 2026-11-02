package com.storageplanner.test;

import com.storageplanner.core.Compression;
import com.storageplanner.core.Exif;
import com.storageplanner.core.FileItem;
import com.storageplanner.core.Option;
import com.storageplanner.core.Techniques;
import com.storageplanner.core.Unit;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Image compression rules: output names, which files a recommendation compresses, EXIF carried into the copy. */
public final class CompressionTest {
    static int checks, fails;

    static void check(String what, boolean ok) {
        checks++;
        if (!ok) { fails++; System.out.println("FAIL " + what); }
    }

    public static void main(String[] a) {
        names();
        targets();
        exif();
        webp();
        System.out.println("CompressionTest: " + (checks - fails) + " of " + checks + " checks passed");
        if (fails > 0) System.exit(1);
    }

    static void names() {
        check("photo -> heic", Compression.outputPath("/s/DCIM/Camera/IMG_1.jpg", "heic").equals("/s/DCIM/Camera/IMG_1.heic"));
        check("chat image -> webp", Compression.outputPath("/s/WhatsApp/IMG-2024-WA0001.jpeg", "webp").equals("/s/WhatsApp/IMG-2024-WA0001.webp"));
        check("dot in folder, none in name", Compression.base("/s/my.photos/IMG").equals("/s/my.photos/IMG"));
        check("hidden file keeps its name", Compression.base("/s/x/.jpg").equals("/s/x/.jpg"));
        check("photos may become HEIC or WebP", Arrays.equals(Compression.extensions(Techniques.PHOTOS), new String[]{"heic", "webp"}));
        check("screenshots only lossless WebP", Arrays.equals(Compression.extensions(Techniques.SCREENSHOTS), new String[]{"webp"}));
        check("temp is hidden, same folder", Compression.tempPath("/s/DCIM/Camera/IMG_1.heic").equals("/s/DCIM/Camera/.sp-IMG_1.heic.tmp"));
        check("only image recommendations", Compression.isCompression(Techniques.PHOTOS) && Compression.isCompression(Techniques.CHAT_IMAGES)
                && Compression.isCompression(Techniques.SCREENSHOTS) && !Compression.isCompression(Techniques.VIDEOS)
                && !Compression.isCompression(Techniques.DUPLICATES));
        check("nouns", Compression.noun(Techniques.PHOTOS, 1).equals("photo") && Compression.noun(Techniques.SCREENSHOTS, 3).equals("screenshots"));
    }

    static Unit photo(String name) {
        Unit u = Unit.forFile(new FileItem("DCIM/Camera/" + name, "/s/DCIM/Camera/" + name, 4_000_000, 0));
        u.add(new Option("reencode_photo", Techniques.PHOTOS, 1_800_000, Option.R2, false, "test"));
        return u;
    }

    static void targets() {
        Unit plain = photo("a.jpg"), kept = photo("b.jpg"), dup = photo("c.jpg"), inPlan = photo("d.jpg");
        Option del = new Option("delete", Techniques.DUPLICATES, 4_000_000, Option.R1, true, "Identical to x");
        dup.add(del);
        Unit app = Unit.forCache(new com.storageplanner.core.AppItem("p", "P"));
        List<Unit> cands = new ArrayList<Unit>(Arrays.asList(plain, kept, dup, inPlan, app));
        Set<String> keptIds = new HashSet<String>(); keptIds.add(kept.id);
        Map<Unit, Option> chosen = new HashMap<Unit, Option>();
        chosen.put(dup, del);
        chosen.put(inPlan, inPlan.options.get(0));
        List<Unit> t = Compression.targets(cands, Techniques.PHOTOS, keptIds, chosen);
        check("not planned: compressed", t.contains(plain));
        check("in plan: compressed", t.contains(inPlan));
        check("kept: left out", !t.contains(kept));
        check("deleted as duplicate: left out", !t.contains(dup));
        check("apps: left out", !t.contains(app));
        check("other recommendation: nothing", Compression.targets(cands, Techniques.SCREENSHOTS, keptIds, chosen).isEmpty());
        check("no candidates", Compression.targets(null, Techniques.PHOTOS, keptIds, chosen).isEmpty());
    }

    /** A JPEG head: SOI, APP0 (JFIF), APP1 (EXIF with orientation and one more tag), then start of scan. */
    static byte[] jpeg(boolean littleEndian, int orientation) {
        ByteArrayOutputStream tiff = new ByteArrayOutputStream();
        w(tiff, littleEndian ? new byte[]{'I', 'I'} : new byte[]{'M', 'M'});
        u16(tiff, 42, littleEndian); u32(tiff, 8, littleEndian);
        u16(tiff, 2, littleEndian);                                       // two entries
        u16(tiff, 0x010F, littleEndian); u16(tiff, 2, littleEndian); u32(tiff, 4, littleEndian); w(tiff, new byte[]{'A', 'c', 'm', 0});
        u16(tiff, 0x0112, littleEndian); u16(tiff, 3, littleEndian); u32(tiff, 1, littleEndian);
        u16(tiff, orientation, littleEndian); u16(tiff, 0, littleEndian);
        u32(tiff, 0, littleEndian);                                       // no next IFD
        byte[] t = tiff.toByteArray();
        ByteArrayOutputStream j = new ByteArrayOutputStream();
        w(j, new byte[]{(byte) 0xFF, (byte) 0xD8});
        w(j, new byte[]{(byte) 0xFF, (byte) 0xE0, 0, 16, 'J', 'F', 'I', 'F', 0, 1, 1, 0, 0, 1, 0, 1, 0, 0});
        int len = 2 + 6 + t.length;
        w(j, new byte[]{(byte) 0xFF, (byte) 0xE1, (byte) (len >> 8), (byte) len, 'E', 'x', 'i', 'f', 0, 0});
        w(j, t);
        w(j, new byte[]{(byte) 0xFF, (byte) 0xDA, 0, 2});
        return j.toByteArray();
    }

    static void exif() {
        for (boolean le : new boolean[]{true, false}) {
            String bo = le ? "little-endian" : "big-endian";
            byte[] e = Exif.fromJpeg(jpeg(le, 6));
            check(bo + ": block found", e != null && e[0] == 'E' && e[4] == 0 && e[5] == 0);
            check(bo + ": orientation read", Exif.orientation(e) == 6);
            byte[] up = Exif.withOrientation(e, 1);
            check(bo + ": orientation reset", Exif.orientation(up) == 1);
            check(bo + ": original block unchanged", Exif.orientation(e) == 6);
            int diff = 0;
            for (int i = 0; i < e.length; i++) if (e[i] != up[i]) diff++;
            check(bo + ": only the orientation changed", diff == 1 && up.length == e.length);
        }
        check("no EXIF in plain JPEG", Exif.fromJpeg(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xDA, 0, 2}) == null);
        check("not a JPEG", Exif.fromJpeg("PNG data".getBytes()) == null);
        byte[] cut = Arrays.copyOf(jpeg(true, 3), 30);
        check("cut-off segment", Exif.fromJpeg(cut) == null);
        check("missing block: upright", Exif.orientation(null) == 1);
        check("odd value: upright", Exif.orientation(Exif.fromJpeg(jpeg(true, 42))) == 1);
        byte[] mini = Exif.minimal("StoragePlanner");
        check("minimal block: valid, upright", mini[0] == 'E' && Exif.orientation(mini) == 1);
        check("minimal block: orientation settable", Exif.orientation(Exif.withOrientation(mini, 8)) == 8);
        String tail = new String(mini, mini.length - 15, 14, java.nio.charset.StandardCharsets.US_ASCII);
        check("minimal block: maker name at the end", tail.equals("StoragePlanner") && mini[mini.length - 1] == 0);
        byte[] sized = Exif.withSize(mini, 1100, 700);
        check("minimal block: size settable", Exif.number(sized, false, 0x0100) == 1100 && Exif.number(sized, false, 0x0101) == 700
                && Exif.orientation(sized) == 1);
        // a camera block: IFD0 says 4000 x 1848 (SHORT), orientation 6, and an Exif IFD with PixelX/Y 4000 x 1848 (LONG)
        for (boolean le : new boolean[]{true, false}) {
            byte[] cam = camera(le);
            check("camera block " + (le ? "LE" : "BE") + ": sizes read", Exif.number(cam, false, 0x0100) == 4000 && Exif.number(cam, false, 0x0101) == 1848
                    && Exif.number(cam, true, 0xA002) == 4000 && Exif.number(cam, true, 0xA003) == 1848 && Exif.orientation(cam) == 6);
            byte[] up = Exif.withSize(Exif.withOrientation(cam, 1), 1848, 4000);
            check("camera block " + (le ? "LE" : "BE") + ": upright copy says 1848 x 4000", Exif.number(up, false, 0x0100) == 1848
                    && Exif.number(up, false, 0x0101) == 4000 && Exif.number(up, true, 0xA002) == 1848 && Exif.number(up, true, 0xA003) == 4000
                    && Exif.orientation(up) == 1 && up.length == cam.length);
        }
        check("size on a block without size tags: unchanged", Arrays.equals(Exif.withSize(Exif.fromJpeg(jpeg(true, 6)), 10, 20), Exif.fromJpeg(jpeg(true, 6))));
    }

    static int le32(byte[] b, int p) { return (b[p] & 255) | (b[p + 1] & 255) << 8 | (b[p + 2] & 255) << 16 | (b[p + 3] & 255) << 24; }

    /** Simple WebP (RIFF, WEBP, one odd-sized "VP8 " chunk) gains VP8X + EXIF; an extended one keeps its chunks. */
    static void webp() {
        byte[] img = {1, 2, 3, 4, 5};                                    // odd size: padded
        ByteArrayOutputStream s = new ByteArrayOutputStream();
        w(s, "RIFF".getBytes()); u32(s, 4 + 8 + 6, true); w(s, "WEBP".getBytes());
        w(s, "VP8 ".getBytes()); u32(s, 5, true); w(s, img); s.write(0);
        byte[] simple = s.toByteArray();
        byte[] exif = Exif.minimal("Cam");
        byte[] out = com.storageplanner.core.WebpMeta.withExif(simple, exif, 1848, 4000);
        check("webp: RIFF size", le32(out, 4) == out.length - 8);
        check("webp: VP8X first, EXIF flag", new String(out, 12, 4).equals("VP8X") && le32(out, 16) == 10 && (out[20] & 0x08) != 0);
        int cw = (out[24] & 255) | (out[25] & 255) << 8 | (out[26] & 255) << 16, ch = (out[27] & 255) | (out[28] & 255) << 8 | (out[29] & 255) << 16;
        check("webp: canvas size", cw == 1847 && ch == 3999);
        check("webp: image chunk kept, padded", new String(out, 30, 4).equals("VP8 ") && le32(out, 34) == 5 && out[38] == 1 && out[42] == 5 && out[43] == 0);
        int e = 44;
        check("webp: EXIF chunk holds the TIFF part", new String(out, e, 4).equals("EXIF") && le32(out, e + 4) == exif.length - 6
                && out[e + 8] == 'I' && out[e + 9] == 'I');
        check("webp: total length", out.length == e + 8 + (exif.length - 6) + ((exif.length - 6) & 1));
        byte[] again = com.storageplanner.core.WebpMeta.withExif(out, Exif.minimal("Other"), 1848, 4000);
        check("webp: second EXIF replaces the first", le32(again, 4) == again.length - 8
                && new String(again).indexOf("Cam") < 0 && new String(again).indexOf("Other") > 0
                && new String(again).indexOf("EXIF") == new String(again).lastIndexOf("EXIF"));
        boolean threw = false;
        try { com.storageplanner.core.WebpMeta.withExif("not webp at all".getBytes(), exif, 1, 1); } catch (IllegalArgumentException x) { threw = true; }
        check("webp: rejects other files", threw);
    }

    /** "Exif\0\0" + TIFF: IFD0 {ImageWidth SHORT, ImageLength SHORT, Orientation, ExifIFD pointer}, Exif IFD {PixelX LONG, PixelY LONG}. */
    static byte[] camera(boolean le) {
        ByteArrayOutputStream t = new ByteArrayOutputStream();
        w(t, le ? new byte[]{'I', 'I'} : new byte[]{'M', 'M'}); u16(t, 42, le); u32(t, 8, le);
        u16(t, 4, le);
        u16(t, 0x0100, le); u16(t, 3, le); u32(t, 1, le); u16(t, 4000, le); u16(t, 0, le);
        u16(t, 0x0101, le); u16(t, 3, le); u32(t, 1, le); u16(t, 1848, le); u16(t, 0, le);
        u16(t, 0x0112, le); u16(t, 3, le); u32(t, 1, le); u16(t, 6, le); u16(t, 0, le);
        int exifAt = 8 + 2 + 4 * 12 + 4;
        u16(t, 0x8769, le); u16(t, 4, le); u32(t, 1, le); u32(t, exifAt, le);
        u32(t, 0, le);
        u16(t, 2, le);
        u16(t, 0xA002, le); u16(t, 4, le); u32(t, 1, le); u32(t, 4000, le);
        u16(t, 0xA003, le); u16(t, 4, le); u32(t, 1, le); u32(t, 1848, le);
        u32(t, 0, le);
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        w(o, new byte[]{'E', 'x', 'i', 'f', 0, 0}); w(o, t.toByteArray());
        return o.toByteArray();
    }

    static void w(ByteArrayOutputStream o, byte[] b) { o.write(b, 0, b.length); }
    static void u16(ByteArrayOutputStream o, int v, boolean le) {
        if (le) { o.write(v & 255); o.write((v >> 8) & 255); } else { o.write((v >> 8) & 255); o.write(v & 255); }
    }
    static void u32(ByteArrayOutputStream o, long v, boolean le) {
        for (int i = 0; i < 4; i++) o.write((int) (v >> (8 * (le ? i : 3 - i))) & 255);
    }
}
