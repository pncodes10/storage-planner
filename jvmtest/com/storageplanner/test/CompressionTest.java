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
        System.out.println("CompressionTest: " + (checks - fails) + " of " + checks + " checks passed");
        if (fails > 0) System.exit(1);
    }

    static void names() {
        check("photo -> heic", Compression.outputPath("/s/DCIM/Camera/IMG_1.jpg", Techniques.PHOTOS).equals("/s/DCIM/Camera/IMG_1.heic"));
        check("chat image -> heic", Compression.outputPath("/s/WhatsApp/IMG-2024-WA0001.jpeg", Techniques.CHAT_IMAGES).equals("/s/WhatsApp/IMG-2024-WA0001.heic"));
        check("screenshot -> webp", Compression.outputPath("/s/Pictures/Screenshots/Screenshot_1.png", Techniques.SCREENSHOTS).equals("/s/Pictures/Screenshots/Screenshot_1.webp"));
        check("dot in folder, none in name", Compression.outputPath("/s/my.photos/IMG", Techniques.PHOTOS).equals("/s/my.photos/IMG.heic"));
        check("hidden file keeps its name", Compression.outputPath("/s/x/.jpg", Techniques.PHOTOS).equals("/s/x/.jpg.heic"));
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
        check("minimal block: maker name at its offset", tail.equals("StoragePlanner") && mini[mini.length - 1] == 0
                && (mini[6 + 18] & 0xFF) == 38 && mini[6 + 14] == 15 && mini[6 + 10] == 0x0F && mini[6 + 11] == 0x01);
    }

    static void w(ByteArrayOutputStream o, byte[] b) { o.write(b, 0, b.length); }
    static void u16(ByteArrayOutputStream o, int v, boolean le) {
        if (le) { o.write(v & 255); o.write((v >> 8) & 255); } else { o.write((v >> 8) & 255); o.write(v & 255); }
    }
    static void u32(ByteArrayOutputStream o, long v, boolean le) {
        for (int i = 0; i < 4; i++) o.write((int) (v >> (8 * (le ? i : 3 - i))) & 255);
    }
}
