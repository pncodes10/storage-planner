package com.storageplanner.test;

import com.storageplanner.core.*;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;

import javax.imageio.ImageIO;

/**
 * "Try on my pictures": which folders are listed, how many pictures are picked from each, that the same seed picks the
 * same pictures, that files that are not pictures are skipped, that every picked picture is sorted and gets the rules'
 * verdict, and that nothing on the phone changes.
 */
public final class SampleTest {
    static int checks, fails;

    static void check(String what, boolean ok) {
        checks++;
        if (!ok) { fails++; System.out.println("FAIL " + what); }
    }

    static void img(File f, int seed, String fmt) throws Exception {
        f.getParentFile().mkdirs();
        BufferedImage b = new BufferedImage(320, 240, BufferedImage.TYPE_INT_RGB);
        Random r = new Random(seed);
        for (int y = 0; y < 240; y++) for (int x = 0; x < 320; x++) {
            int v = (x / 16 + y / 16 + seed) % 2 == 0 ? 60 + r.nextInt(40) : 170 + r.nextInt(40);
            b.setRGB(x, y, (v << 16) | ((v * 3 / 4) << 8) | (v / 2));
        }
        ImageIO.write(b, fmt, f);
    }

    static void text(File f, String s) throws Exception {
        f.getParentFile().mkdirs();
        FileOutputStream o = new FileOutputStream(f); o.write(s.getBytes("UTF-8")); o.close();
    }

    public static void main(String[] args) throws Exception {
        File dev = Files.createTempDirectory("sample").toFile(), sd = new File(dev, "sdcard");
        for (int i = 0; i < 12; i++) img(new File(sd, "DCIM/Camera/IMG_" + i + ".jpg"), i, "jpg");
        for (int i = 0; i < 3; i++) img(new File(sd, "DCIM/Screenshots/Screenshot_" + i + ".png"), 50 + i, "png");
        img(new File(sd, "Download/poster.jpg"), 70, "jpg");
        text(new File(sd, "Download/notes.txt"), "not a picture");
        text(new File(sd, "Download/fake.jpg"), "a text file with a picture's name");
        String wa = "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/";
        for (int i = 0; i < 4; i++) img(new File(sd, wa + "IMG-" + i + "-WA.jpg"), 80 + i, "jpg");
        img(new File(sd, wa + "Sent/IMG-s-WA.jpg"), 90, "jpg");
        img(new File(sd, "DCIM/.thumbnails/t.jpg"), 91, "jpg");               // thumbnails are not camera photos
        img(new File(sd, "Movies/still.jpg"), 92, "jpg");                     // not one of the places
        Map<String, Long> before = new TreeMap<String, Long>();
        for (File f : all(sd)) before.put(f.getPath(), f.length() * 31 + f.lastModified());

        JvmPlatform pf = new JvmPlatform(dev.getPath());
        Scanner.Progress quiet = new Scanner.Progress() { public void update(String s, int d, int t) {} public boolean cancelled() { return false; } };
        Set<String> areas = new LinkedHashSet<String>(Arrays.asList(Sample.SOURCES));
        Map<String, List<FileItem>> l = Sample.list(pf, areas, quiet);
        check("lists camera photos (not thumbnails)", l.get("camera").size() == 12);
        check("lists screenshots", l.get("screenshot").size() == 3);
        check("lists downloads by name (a fake .jpg too, no .txt)", l.get("download").size() == 2);
        check("lists WhatsApp received and sent apart", l.get("wa_image").size() == 4 && l.get("wa_sent").size() == 1);
        check("Movies is not a place", l.get("pictures").isEmpty());

        List<FileItem> a = Sample.pick(l.get("camera"), 5, new Random(7)), b = Sample.pick(l.get("camera"), 5, new Random(7));
        check("picks 5 of 12", a.size() == 5 && new HashSet<FileItem>(a).size() == 5);
        boolean same = true;
        for (int i = 0; i < 5; i++) same &= a.get(i).rel.equals(b.get(i).rel);
        check("the same seed picks the same pictures", same);
        boolean differ = false;
        for (long s = 1; s < 20 && !differ; s++) differ = !Sample.pick(l.get("camera"), 5, new Random(s)).get(0).rel.equals(a.get(0).rel);
        check("another seed can pick others", differ);
        check("asking for more than there are gives all of them", Sample.pick(l.get("wa_image"), 100, new Random(1)).size() == 4);
        check("asking for 0 gives none", Sample.pick(l.get("camera"), 0, new Random(1)).isEmpty());

        Map<String, Integer> want = new LinkedHashMap<String, Integer>();
        want.put("camera", 5); want.put("screenshot", 100); want.put("download", 2); want.put("wa_image", 2); want.put("wa_sent", 0);
        final List<String> lines = new ArrayList<String>();
        final int[] calls = {0};
        RuleSet rules = RuleSet.defaults();
        Rule u = rules.addUser("Old pictures", Rule.Action.DELETE).when(Tags.AGE_DAYS, Rule.Op.GT, "-1");
        u.fileType = Tags.IMAGE;
        Sample.Result r = Sample.run(pf, want, rules, System.currentTimeMillis(), 42, quiet,
                new Lab.Out() { public void line(String s) { lines.add(s); } },
                new Sample.Listener() { public void picture(Sample.Item it, int done, int total) { calls[0]++; } });
        check("places with 0 are not run", !r.asked.containsKey("wa_sent"));
        check("asked and available", r.asked.get("camera") == 5 && r.available.get("camera") == 12 && r.asked.get("screenshot") == 100 && r.available.get("screenshot") == 3);
        check("12 pictures tried (5 + all 3 + 2 + 2)", r.items.size() == 12 && calls[0] == 12);
        check("the fake .jpg is skipped as not a picture", r.skipped() == 1 && r.done() == 11);
        boolean sorted = true, matched = true;
        for (Sample.Item it : r.items) if (it.problem == null) {
            sorted &= it.category != null;
            matched &= it.matched.contains("Old pictures") && !it.options.isEmpty();
        }
        check("every picture is sorted", sorted);
        check("every picture gets the rules' verdict (the user's picture rule matches each)", matched);
        check("screenshots are sorted as screenshots", r.categories("screenshot").containsKey(PhotoCategory.label(PhotoCategory.SCREENSHOT)));
        check("suggestions are counted", r.suggestions(null).get("Delete — " + Rule.USER_PREFIX + "Old pictures") == 11 && r.freed(null) > 0);
        List<String> sum = Sample.summary(r);
        check("summary names each place", sum.toString().contains("Camera photos: 5 of 12") && sum.toString().contains("Screenshots: 3 of 3"));
        check("the log has every picture with its rules", count(lines, "RULES — ") == 11 && lines.get(0).startsWith("TRY ON MY PICTURES — seed 42"));

        Map<String, Long> after = new TreeMap<String, Long>();
        for (File f : all(sd)) after.put(f.getPath(), f.length() * 31 + f.lastModified());
        check("nothing on the phone changed", before.equals(after));

        check("log detail limit is sane", Sample.DETAIL_MAX >= 100);
        final int[] stopAt = {0};
        Scanner.Progress stop = new Scanner.Progress() { public void update(String s, int d, int t) {} public boolean cancelled() { return stopAt[0] >= 2; } };
        Sample.Result rs = Sample.run(pf, want, rules, System.currentTimeMillis(), 42, stop, new Lab.Out() { public void line(String s) {} },
                new Sample.Listener() { public void picture(Sample.Item it, int done, int total) { stopAt[0]++; } });
        check("stopping keeps what was done", rs.cancelled && rs.items.size() == 2);

        System.out.println((checks - fails) + "/" + checks + " sample checks pass");
        if (fails > 0) System.exit(1);
    }

    static int count(List<String> l, String prefix) { int n = 0; for (String s : l) if (s.startsWith(prefix)) n++; return n; }

    static List<File> all(File d) {
        List<File> out = new ArrayList<File>();
        File[] k = d.listFiles();
        if (k != null) for (File f : k) { if (f.isDirectory()) out.addAll(all(f)); else out.add(f); }
        return out;
    }
}
