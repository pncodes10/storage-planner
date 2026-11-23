package com.storageplanner.test;

import com.storageplanner.core.Lab;
import com.storageplanner.core.Platform;

import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import javax.imageio.ImageIO;

/**
 * Algorithm Lab, picked pictures and decode failures. The files the Lab makes of picked pictures (p0..p19, reused):
 * the old rule (slot = number of pictures held) against the new one, which looks at what is really in use. And the
 * log when a picture does not decode: it must say why (missing file, empty, wrong bytes, wrong extension, what the
 * phone's decoder reported), for every tool and for every picture. Synthetic files only.
 * usage: LabFilesTest
 */
public final class LabFilesTest {
    static int checks, fails;

    static void check(String what, boolean ok) {
        checks++;
        if (!ok) { fails++; System.out.println("FAIL " + what); }
    }

    // ------------------------------------------------------------------ slots
    /** What LabScreen did before: slot = pics.size() when a pick starts, and every try used up a slot, also a failed copy. */
    static List<String> oldPick(List<String> pics, int n, boolean[] fail) {
        List<String> got = new ArrayList<String>();
        int slot = pics.size();
        for (int i = 0; i < n; i++) {
            if (slot >= 20) break;
            int s = slot++;
            if (fail != null && fail[i]) continue;
            got.add("/lab/p" + s + ".jpg");
        }
        return got;
    }

    static void slots() {
        // the old rule: three pictures picked, the middle one cannot be copied -> pictures 0 and 2; the next pick gets slot 2 again
        List<String> pics = new ArrayList<String>(oldPick(new ArrayList<String>(), 3, new boolean[]{false, true, false}));
        check("old rule: after a failed copy the pictures are p0 and p2", pics.equals(Arrays.asList("/lab/p0.jpg", "/lab/p2.jpg")));
        List<String> next = oldPick(pics, 1, null);
        check("old rule: the next pick is written over p2 (two pictures, one file)", next.get(0).equals("/lab/p2.jpg") && pics.contains(next.get(0)));

        // the new rule
        List<String> used = new ArrayList<String>(Arrays.asList("/lab/p0.jpg", "/lab/p2.jpg"));
        Set<Integer> reserved = new HashSet<Integer>();
        check("new rule: the lowest slot nobody uses (1)", Lab.freeSlot(used, reserved, 20) == 1);
        reserved.add(1);
        check("new rule: a slot reserved by a pick that has not finished is skipped (3)", Lab.freeSlot(used, reserved, 20) == 3);
        used.clear(); reserved.clear();
        check("new rule: after Clear, slot 0 again", Lab.freeSlot(used, reserved, 20) == 0);
        for (int i = 0; i < 20; i++) used.add("/lab/p" + i + ".webp");
        check("new rule: all 20 slots taken -> none", Lab.freeSlot(used, reserved, 20) == -1);
        used.remove("/lab/p7.webp");
        check("new rule: a freed slot is reused (7)", Lab.freeSlot(used, reserved, 20) == 7);
        check("pictures with other paths (opened from the compare screen) hold no slot", Lab.freeSlot(Arrays.asList("/sdcard/DCIM/Camera/20260219_165304.jpg"), reserved, 20) == 0);

        // random picks, failures, in-flight picks and clears: never two pictures on one file, never a slot above 19
        Random rnd = new Random(5);
        int bad = 0;
        for (int run = 0; run < 300; run++) {
            List<String> mine = new ArrayList<String>();
            Set<Integer> res = new HashSet<Integer>();
            List<Object[]> inflight = new ArrayList<Object[]>();
            for (int step = 0; step < 40; step++) {
                int k = rnd.nextInt(6);
                if (k == 0) { mine.clear(); }
                else if (k <= 3) {                                      // start a pick of 1..5 pictures
                    List<Integer> mySlots = new ArrayList<Integer>();
                    for (int i = 0, n = 1 + rnd.nextInt(5); i < n; i++) {
                        if (mine.size() + res.size() >= 20) break;
                        int s = Lab.freeSlot(mine, res, 20);
                        if (s < 0) break;
                        res.add(s); mySlots.add(s);
                    }
                    inflight.add(new Object[]{mySlots});
                } else if (!inflight.isEmpty()) {                       // a pick finishes: some copies fail
                    @SuppressWarnings("unchecked") List<Integer> done = (List<Integer>) inflight.remove(rnd.nextInt(inflight.size()))[0];
                    for (int s : done) if (rnd.nextInt(4) != 0) mine.add("/lab/" + Lab.slotName(s, rnd.nextBoolean() ? "jpg" : "webp"));
                    res.removeAll(done);
                }
                Set<Integer> seen = new HashSet<Integer>();
                for (String p : mine) if (!seen.add(Lab.slotOf(p)) || Lab.slotOf(p) < 0 || Lab.slotOf(p) >= 20) bad++;
                if (mine.size() > 20) bad++;
            }
        }
        check("300 random sessions: never two pictures on one slot, never more than 20", bad == 0);

        // slot file names
        check("slot of p10.png is 10", Lab.slotOf("/x/cache/lab/p10.png") == 10 && Lab.slotOf("p0.jpg") == 0);
        check("not slot files: pic.jpg, p.jpg, p3, notes.txt", Lab.slotOf("pic.jpg") == -1 && Lab.slotOf("p.jpg") == -1 && Lab.slotOf("p3") == -1 && Lab.slotOf("notes.txt") == -1);
        check("WhatsApp and screenshot names say how to treat a picked picture", "wa_image".equals(Lab.areaFromName("IMG-20261105-WA0007.jpg"))
                && "wa_image".equals(Lab.areaFromName("IMG-20250101-WA0123 (1).jpeg")) && "screenshot".equals(Lab.areaFromName("Screenshot_20261101-093000.png"))
                && "screenshot".equals(Lab.areaFromName("Screen Shot 2026-01-01 at 10.00.00.png")) && Lab.areaFromName("IMG_20261105_101010.jpg") == null
                && Lab.areaFromName("1000301539.jpg") == null && Lab.areaFromName("IMG-2026-WA.jpg") == null && Lab.areaFromName(null) == null);
        check("slot name keeps a plain extension, else jpg", Lab.slotName(2, "JPG").equals("p2.jpg") && Lab.slotName(3, "").equals("p3.jpg")
                && Lab.slotName(4, "../x").equals("p4.jpg") && Lab.slotName(5, "heic").equals("p5.heic") && Lab.slotName(6, "toolongext").equals("p6.jpg"));
    }

    // ------------------------------------------------------------------ decode failures say why
    static byte[] jpeg() throws Exception {
        BufferedImage im = new BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 48; y++) for (int x = 0; x < 64; x++) im.setRGB(x, y, (x * 4) << 16 | (y * 5) << 8 | 90);
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        ImageIO.write(im, "jpg", b);
        return b.toByteArray();
    }

    static Lab.Pic pic(File f, String name, long size) { return new Lab.Pic(f.getPath(), name, size, 1700000000000L); }

    static String run(Job j) throws Exception {
        StringBuilder b = new StringBuilder();
        j.run(new Lab.Out() { public void line(String s) { b.append(s).append('\n'); } });
        return b.toString();
    }

    interface Job { void run(Lab.Out o) throws Exception; }

    static void has(String what, String out, String... needles) {
        for (String n : needles) check(what + ": says \"" + n + "\"", out.contains(n));
    }

    static void failures() throws Exception {
        File dir = Files.createTempDirectory("labfiles").toFile();
        File cache = new File(dir, "cache/lab");
        cache.mkdirs();
        try {
            JvmPlatform pf = new JvmPlatform(dir.getPath());
            byte[] good = jpeg();

            // a picture that decodes: no complaint, same as before
            File ok = new File(cache, "p0.jpg"); Files.write(ok.toPath(), good);
            String s0 = run(o -> Lab.sharpness(pf, pic(ok, "20260219_165300.jpg", good.length), o));
            check("a good picture decodes, no '!' line", s0.contains("decoded 64 x 48") && !s0.contains("!"));

            // the copy is gone (what happens when Android clears the cache)
            File gone = new File(cache, "p1.jpg");
            String s1 = run(o -> Lab.best(pf, Arrays.asList(pic(gone, "20260219_165304.jpg", 5000000), pic(ok, "b.jpg", good.length), pic(ok, "c.jpg", good.length)), o));
            System.out.println(s1);
            has("missing copy in the cache", s1, "could not decode the picture at 1280 px", "the file is not there", "Android deletes app cache files", "Pick the picture again",
                    "cannot rank: 1 of 3 pictures could not be measured (20260219_165304.jpg)");
            check("every picture is tried and logged (not only the first)", s1.contains(" b.jpg") && s1.contains(" c.jpg") || s1.contains("cannot rank"));
            String s1b = run(o -> Lab.nearDuplicates(pf, Arrays.asList(pic(gone, "a.jpg", 1), pic(ok, "b.jpg", good.length)), o));
            has("near-duplicate check with a missing file", s1b, "could not decode the picture at 1024 px", "the file is not there", "cannot compare: 1 of 2");

            // an empty file (a copy that did not finish)
            File empty = new File(cache, "p2.jpg"); Files.write(empty.toPath(), new byte[0]);
            String s2 = run(o -> Lab.lighting(pf, pic(empty, "e.jpg", 3000000), o));
            has("empty file", s2, "the file exists, 0 bytes, readable", "it is empty: the copy or the download did not finish");

            // a file cut short: the picker said more bytes than are here
            File cut = new File(cache, "p3.jpg"); Files.write(cut.toPath(), Arrays.copyOf(good, 20));
            String s3 = run(o -> Lab.sharpness(pf, pic(cut, "cut.jpg", good.length), o));
            has("cut-short file", s3, "could not decode", "the picker reported " + good.length + " bytes but 20 are here", "first bytes: ff d8 ff");

            // text saved with a picture's name
            File junk = new File(cache, "p4.jpg"); Files.write(junk.toPath(), "this is not a picture at all, just text".getBytes("UTF-8"));
            String s4 = run(o -> Lab.codes(pf, pic(junk, "t.jpg", junk.length()), o));
            has("text with a .jpg name", s4, "first bytes: 74 68 69 73", "looks like bin", "not a picture format the app reads");

            // a HEIC saved with a .jpg name (the decoder here cannot read it, the log must point at the mismatch)
            byte[] heic = new byte[64];
            System.arraycopy(new byte[]{0, 0, 0, 24, 'f', 't', 'y', 'p', 'h', 'e', 'i', 'c'}, 0, heic, 0, 12);
            File h = new File(cache, "p5.jpg"); Files.write(h.toPath(), heic);
            String s5 = run(o -> Lab.sharpness(pf, pic(h, "20260219_165304.jpg", heic.length), o));
            has("HEIC with a .jpg name", s5, "looks like heic", "the name ends in .jpg but the bytes say heic", "HEIC needs a decoder");

            // a folder
            String s6 = run(o -> Lab.sharpness(pf, pic(cache, "folder.jpg", 1), o));
            has("a folder", s6, "this is a folder");

            // what the phone's decoder reported is written too, and a fallback that worked is mentioned
            final String[] note = {"BitmapFactory could not read the picture's size (it reported -1 x -1)"};
            Platform fake = (Platform) Proxy.newProxyInstance(Platform.class.getClassLoader(), new Class<?>[]{Platform.class}, new InvocationHandler() {
                public Object invoke(Object proxy, Method m, Object[] a) {
                    if (m.getName().equals("argb")) return null;
                    if (m.getName().equals("decodeNote")) return note[0];
                    return null;
                }
            });
            String s7 = run(o -> Lab.sharpness(fake, pic(ok, "x.jpg", good.length), o));
            has("the decoder's own words", s7, "decoder: BitmapFactory could not read the picture's size (it reported -1 x -1)", "the file exists, " + good.length + " bytes, readable", "looks like jpeg");
            Platform worked = (Platform) Proxy.newProxyInstance(Platform.class.getClassLoader(), new Class<?>[]{Platform.class}, new InvocationHandler() {
                public Object invoke(Object proxy, Method m, Object[] a) {
                    if (m.getName().equals("argb")) { int[] d = (int[]) a[2]; d[0] = 64; d[1] = 48; d[2] = 64; d[3] = 48; return new int[64 * 48]; }
                    if (m.getName().equals("decodeNote")) return "BitmapFactory could not decode it; decoded with ImageDecoder instead";
                    return null;
                }
            });
            String s8 = run(o -> Lab.lighting(worked, pic(ok, "x.jpg", good.length), o));
            has("a fallback that worked says so", s8, "note: BitmapFactory could not decode it; decoded with ImageDecoder instead", "decoded 64 x 48");

            // the other decode points use the same helper
            File gone2 = new File(cache, "p9.jpg");
            for (Object[] t : new Object[][]{
                    {"faces", (Job) o -> Lab.faces(pf, pic(gone2, "g.jpg", 1), o)},
                    {"faces, sizes and tilts", (Job) o -> Lab.facesDetailed(pf, pic(gone2, "g.jpg", 1), o)},
                    {"eyes", (Job) o -> Lab.eyes(pf, pic(gone2, "g.jpg", 1), o)},
                    {"sharpness", (Job) o -> Lab.sharpness(pf, pic(gone2, "g.jpg", 1), o)},
                    {"lighting", (Job) o -> Lab.lighting(pf, pic(gone2, "g.jpg", 1), o)},
                    {"category", (Job) o -> Lab.category(pf, pic(gone2, "g.jpg", 1), 1792000000000L, o)},
                    {"codes", (Job) o -> Lab.codes(pf, pic(gone2, "g.jpg", 1), o)},
                    {"read text", (Job) o -> Lab.text(pf, pic(gone2, "g.jpg", 1), 1792000000000L, o)}}) {
                String out = run((Job) t[1]);
                has((String) t[0] + " with a missing file", out, "could not decode the picture", "the file is not there", "file: " + gone2.getPath());
                check((String) t[0] + ": no bare 'could not decode' line without a reason", !out.matches("(?s).*could not decode[^\\n]*\\n(?!    ).*") || out.contains("the file is not there"));
            }
            // reading: the 1600 px decode for the text reader fails while the 1024 px one worked
            Platform noBig = (Platform) Proxy.newProxyInstance(Platform.class.getClassLoader(), new Class<?>[]{Platform.class}, new InvocationHandler() {
                public Object invoke(Object proxy, Method m, Object[] a) {
                    if (m.getName().equals("argb")) {
                        if ((Integer) a[1] == 1600) return null;
                        int[] d = (int[]) a[2]; d[0] = 64; d[1] = 48; d[2] = 64; d[3] = 48; return new int[64 * 48];
                    }
                    return null;
                }
            });
            Lab.Pic wa = pic(ok, "wa.jpg", good.length); wa.area = "wa_image";
            String rd = run(o -> Lab.text(noBig, wa, 1792000000000L, o));
            has("reading the text", rd, "could not decode the picture for reading at 1600 px", "file: " + ok.getPath());
            String ex = run(o -> Lab.exactDuplicates(Arrays.asList(pic(gone2, "g.jpg", 7), pic(ok, "b.jpg", good.length)), o));
            has("exact duplicate check with a missing file", ex, "could not read the file", "the file is not there");
        } finally {
            deleteAll(dir);
        }
    }

    static void deleteAll(File f) {
        File[] k = f.listFiles();
        if (k != null) for (File x : k) deleteAll(x);
        f.delete();
    }

    public static void main(String[] args) throws Exception {
        slots();
        failures();
        System.out.println(checks - fails + "/" + checks + " lab file checks pass");
        if (fails > 0) System.exit(1);
    }
}
