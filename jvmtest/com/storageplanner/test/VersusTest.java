package com.storageplanner.test;

import com.storageplanner.core.*;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

/**
 * v2 against v3 (the comparison page): cards are saved and read back unchanged, the score and the next card to mark,
 * both algorithms on one picture (with a saved Mirage answer: the model runs only on the phone), and which pictures a
 * run takes from a made-up phone. Invented data only.
 */
public final class VersusTest {
    static int checks, fails;

    static void check(String what, boolean ok) {
        checks++;
        if (!ok) { fails++; System.out.println("FAIL " + what); }
    }

    static Versus.Card card(String path, String a2, String a3, int mark) {
        Versus.Card c = new Versus.Card();
        c.path = path; c.rel = path; c.name = path; c.area = "wa_image";
        c.v2.action = a2; c.v3.action = a3; c.mark = mark;
        return c;
    }

    static void picture(File f, long mtime) throws Exception {
        f.getParentFile().mkdirs();
        BufferedImage im = new BufferedImage(640, 480, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = im.createGraphics();
        g.setColor(java.awt.Color.WHITE); g.fillRect(0, 0, 640, 480);
        g.setColor(new java.awt.Color(200, 60, 40)); g.fillRect(0, 0, 640, 160);
        g.setColor(java.awt.Color.BLACK); g.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD, 30));
        for (int y = 220; y < 460; y += 45) g.drawString("Lorem ipsum dolor sit amet " + y, 20, y);
        g.dispose();
        ImageIO.write(im, "jpg", f);
        f.setLastModified(mtime);
    }

    public static void main(String[] a) throws Exception {
        // ---- saving
        Versus.Card c = card("/sd/WhatsApp/Media/WhatsApp Images/IMG-1.jpg", "Keep", "Delete — Memes and forwards", Versus.V3);
        c.size = 123456; c.mtime = 1790000000000L; c.ms = 4100; c.v3Ms = 1100;
        c.v2.category = "people"; c.v2.why = "a \"quoted\" why\nnext line\twith tab \\ back"; c.v2.date = 1789000000000L;
        c.v3.category = "graphic"; c.v3.why = "Mirage: a collage with captions (31 words), received in a chat: a meme or forward"; c.v3.detail = "Meme, 20 days old; Café €";
        Versus.Card back = Versus.fromLine(Versus.toLine(c));
        check("a card is read back unchanged", back != null && back.path.equals(c.path) && back.size == c.size && back.mtime == c.mtime && back.ms == 4100
                && back.v3Ms == 1100 && back.mark == Versus.V3 && back.v2.why.equals(c.v2.why) && back.v2.date == c.v2.date && back.v3.detail.equals(c.v3.detail)
                && back.v3.action.equals(c.v3.action) && back.v2.category.equals("people") && back.problem == null && back.v3Note == null);
        check("one line per card", !Versus.toLine(c).contains("\n"));
        Versus.Card bad = card("/x", "Keep", "Keep", Versus.NONE);
        bad.problem = "could not be decoded"; bad.v3Note = "Mirage is not available";
        Versus.Card bb = Versus.fromLine(Versus.toLine(bad));
        check("problems are kept", bb.problem.equals("could not be decoded") && bb.v3Note.equals("Mirage is not available"));
        check("a damaged line is no card", Versus.fromLine("{\"path\":\"/x\",\"v2\":{") == null && Versus.fromLine("") == null && Versus.fromLine("{}") == null);

        // ---- score and the next card
        List<Versus.Card> l = new ArrayList<Versus.Card>();
        l.add(card("/1", "Keep", "Keep", Versus.NONE));                                  // agree
        l.add(card("/2", "Keep", "Delete — Memes and forwards", Versus.V3));
        l.add(card("/3", "Compress — Re-encode chat images", "Delete — Memes and forwards", Versus.V2));
        l.add(card("/4", "Keep", "Delete — Accidental shots", Versus.SKIP));
        l.add(card("/5", "Keep", "Delete — Greeting images from chats", Versus.NONE));    // to mark
        l.add(bad);
        l.add(card("/7", "Keep", "Delete — Screenshots of one-time codes", Versus.V3));
        Versus.Score s = Versus.score(l, false);
        check("score: v2 1, v3 2, skipped 1, agree 1, left 1, problems 1", s.v2 == 1 && s.v3 == 2 && s.skipped == 1 && s.agree == 1 && s.left == 1 && s.problems == 1 && s.total == 7);
        Versus.Score sa = Versus.score(l, true);
        check("showing agreeing pictures too: they are to mark", sa.agree == 0 && sa.left == 2);
        check("next card skips agreeing, marked and failed ones", Versus.next(l, 0, false) == 4 && Versus.next(l, 5, false) == 4 && Versus.next(l, 0, true) == 0);
        l.get(4).mark = Versus.V2;
        check("none left", Versus.next(l, 0, false) == -1);
        List<String> rep = Versus.report(l, false);
        check("report: the score, then each marked card", rep.get(0).contains("v2 2 · v3 2 · skipped 1") && rep.size() == 6 && rep.get(1).startsWith("  v3 right  /2"));

        // ---- both algorithms on one picture
        File dev = Files.createTempDirectory("versus").toFile();
        long now = System.currentTimeMillis(), day = 86400000L;
        File comic = new File(dev, "sdcard/WhatsApp/Media/WhatsApp Images/IMG-20261105-WA0007.jpg");
        picture(comic, now - 20 * day);
        File answer = new File(dev, "answer.json");
        Files.write(answer.toPath(), ("{\"scene\":{\"style\":\"collage\"},\"objects\":[{\"name\":\"man\",\"bbox\":[1,1,300,600],\"score\":0.78}],\"text\":["
                + "{\"string\":\"1990\",\"bbox\":[100,20,250,60],\"score\":0.61},{\"string\":\"WROTE LETTERS BY HAND\",\"bbox\":[20,800,320,850],\"score\":0.7},"
                + "{\"string\":\"SENDS ONLY EMOJIS\",\"bbox\":[660,800,980,850],\"score\":0.64},{\"string\":\"EVERY SUNDAY TO HIS MOTHER\",\"bbox\":[20,860,320,900],\"score\":0.6}]}")
                .getBytes(StandardCharsets.UTF_8));
        JvmPlatform pf = new JvmPlatform(dev.getPath());
        FileItem f = new FileItem("WhatsApp/Media/WhatsApp Images/IMG-20261105-WA0007.jpg", comic.getAbsolutePath(), comic.length(), comic.lastModified());
        f.area = "wa_image";
        List<String> lines = new ArrayList<String>();
        Lab.Out out = lines::add;
        Versus.Card none = Versus.judge(pf, f, RuleSet.defaults(), now, out);
        check("without Mirage: v2 is there, v3 says why not, nothing to mark", none.problem == null && none.v3Note != null && none.v3Note.contains("only on the phone")
                && !none.v2.category.isEmpty() && !none.judgeable(true));
        System.setProperty("mirage", answer.getPath());
        Versus.Card j = Versus.judge(pf, f, RuleSet.defaults(), now, out);
        System.clearProperty("mirage");
        if (a.length > 0) System.out.println(String.join("\n", lines) + "\n" + Versus.toLine(j));
        check("with Mirage: the comic from WhatsApp is a meme for v3, deleted", j.problem == null && j.v3Note == null && "graphic".equals(j.v3.category)
                && j.v3.action.equals("Delete — Memes and forwards") && j.v3.why.contains("a meme or forward") && j.v3.detail.contains("20 days"));
        check("v2's side is today's sorting", !j.v2.category.isEmpty() && !j.v2.action.equals(j.v3.action) && j.judgeable(false));
        // a group photo at night: the face detector finds no face in this made-up picture, Mirage names people
        File crowd = new File(dev, "elsewhere/crowd.jpg");
        crowd.getParentFile().mkdirs();
        BufferedImage ci = new BufferedImage(800, 600, BufferedImage.TYPE_INT_RGB);
        java.util.Random rr = new java.util.Random(4);
        for (int y = 0; y < 600; y++) for (int x = 0; x < 800; x++) {
            int base = y < 250 ? 20 : 90 + (x / 40 % 5) * 25;                     // dark sky, a busy lit crowd below
            int r = Math.min(255, base + rr.nextInt(60)), g = Math.min(255, base / 2 + rr.nextInt(60)), b = Math.min(255, base / 3 + rr.nextInt(60));
            ci.setRGB(x, y, r << 16 | g << 8 | b);
        }
        ImageIO.write(ci, "jpg", crowd);
        File crowdAnswer = new File(dev, "crowd.json");
        Files.write(crowdAnswer.toPath(), ("{\"scene\":{\"setting\":\"outdoor festival\",\"time_of_day\":\"night\"},\"objects\":["
                + "{\"name\":\"woman\",\"bbox\":[100,300,200,900],\"score\":0.71},{\"name\":\"woman\",\"bbox\":[220,300,320,900],\"score\":0.66},"
                + "{\"name\":\"man\",\"bbox\":[700,300,800,900],\"score\":0.38},{\"name\":\"flag\",\"score\":0.36}],\"text\":[]}").getBytes(StandardCharsets.UTF_8));
        FileItem cf = new FileItem("DCIM/Camera/crowd.jpg", crowd.getAbsolutePath(), crowd.length(), now - 2 * day);
        cf.area = "camera";
        System.setProperty("mirage", crowdAnswer.getPath());
        lines.clear();
        Versus.Card cc = Versus.judge(pf, cf, RuleSet.defaults(), now, out);
        System.clearProperty("mirage");
        if (a.length > 0) System.out.println(String.join("\n", lines) + "\n" + Versus.toLine(cc));
        check("group photo: v2 finds no face, v3 sees people", "photo".equals(cc.v2.category) && "people".equals(cc.v3.category)
                && cc.v3.why.equals("Mirage sees 3 people (the face detector found no face)"));
        check("a different category is a disagreement even with the same suggestion", cc.v2.action.equals(cc.v3.action) && !cc.agree() && cc.judgeable(false));
        Mirage.Answer pets = Mirage.read("{\"objects\":[{\"name\":\"cat face\",\"score\":0.8},{\"name\":\"man\",\"score\":0.3}]}");
        check("a pet's face is no person; low scores count only as people seen", Mirage.people(pets) == 1 && Mirage.people(pets, AlgorithmV3.PERSON_SCORE) == 0);

        // faces are looked for on photos received in chats too (sorting version 6), not on flat graphics
        File poster = new File(dev, "elsewhere/poster.png");                               // a flat designed graphic: solid colour blocks
        BufferedImage pi = new BufferedImage(600, 800, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D pg = pi.createGraphics();
        int[] cols = {0xF4D03F, 0x2E86C1, 0xFFFFFF, 0xC0392B};
        for (int k = 0; k < 4; k++) { pg.setColor(new java.awt.Color(cols[k])); pg.fillRect(0, k * 200, 600, 200); }
        pg.setColor(java.awt.Color.BLACK); pg.fillOval(200, 260, 200, 200);
        pg.dispose();
        ImageIO.write(pi, "png", poster);
        for (String[] k : new String[][]{{crowd.getPath(), "wa_image", "checked"}, {crowd.getPath(), "download", "checked"}, {poster.getPath(), "wa_image", "not checked"}}) {
            Lab.Pic lp = new Lab.Pic(k[0], new File(k[0]).getName(), new File(k[0]).length(), now);
            lp.area = k[1];
            List<String> cl = new ArrayList<String>();
            Lab.category(pf, lp, now, cl::add);
            String fl = "";
            for (String x : cl) if (x.contains("faces counted:")) fl = x;
            boolean checked = fl.matches(".*faces counted: \\d+.*");
            check("faces " + k[2] + ": " + new File(k[0]).getName() + " as " + k[1] + " (" + fl.trim() + ")", k[2].equals("checked") == checked);
        }

        File txt = new File(dev, "elsewhere/notes.jpg");                                 // outside the phone folders the run lists
        txt.getParentFile().mkdirs();
        Files.write(txt.toPath(), "just text, not a picture".getBytes(StandardCharsets.UTF_8));
        FileItem tf = new FileItem("Download/notes.jpg", txt.getAbsolutePath(), txt.length(), now);
        tf.area = "download";
        Versus.Card nc = Versus.judge(pf, tf, RuleSet.defaults(), now, out);
        check("a file that is not a picture is a problem, not a card to mark", nc.problem != null && nc.problem.startsWith("not a picture") && !nc.judgeable(true));

        // ---- which pictures a run takes
        picture(new File(dev, "sdcard/DCIM/Camera/IMG_20261101_100000.jpg"), now - 3 * day);
        picture(new File(dev, "sdcard/Pictures/Screenshots/Screenshot_20261110.jpg"), now - 1 * day);
        picture(new File(dev, "sdcard/Download/poster.jpg"), now - 40 * day);
        picture(new File(dev, "sdcard/Android/data/com.example/cache/x.jpg"), now);          // not a place the comparison takes
        Scanner.Progress pr = new Scanner.Progress() { public void update(String s1, int d1, int t1) {} public boolean cancelled() { return false; } };
        List<FileItem> two = Versus.choose(pf, 2, true, 1, pr);
        if (a.length > 0) for (FileItem x : Versus.choose(pf, 100, true, 1, pr)) System.out.println("  picked " + x.area + " " + x.rel + " " + (now - x.mtime) / day + " days");
        check("the newest pictures, newest first, from the sample places", two.size() == 2 && two.get(0).name().startsWith("Screenshot_")
                && two.get(1).name().startsWith("IMG_2026") && "screenshot".equals(two.get(0).area) && "camera".equals(two.get(1).area));
        List<FileItem> all = Versus.choose(pf, 100, false, 7, pr);
        check("random: all of them when there are fewer, newest first, app data left out", all.size() == 4 && all.get(3).name().equals("poster.jpg")
                && all.get(2).name().startsWith("IMG-2026"));

        System.out.println("VersusTest: " + (checks - fails) + "/" + checks + " checks passed");
        if (fails > 0) System.exit(1);
    }
}
