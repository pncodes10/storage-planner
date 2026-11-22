package com.storageplanner.test;

import com.storageplanner.core.*;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

/**
 * Reading Mirage's answer (the model itself runs only on the phone): the JSON reader, the scene / objects / text lines
 * the Lab shows, and the Lab check with a saved answer and without Mirage. Invented data only.
 */
public final class MirageTest {
    static int checks, fails;

    static void check(String what, boolean ok) {
        checks++;
        if (!ok) { fails++; System.out.println("FAIL " + what); }
    }

    static boolean bad(String json) {
        try { Json.parse(json); return false; } catch (IllegalArgumentException e) { return true; }
    }

    /** An answer in Mirage's format, for an invented cafe receipt photographed on a table. */
    static final String ANSWER = "{\"scene\":{\"background\":\"brown\",\"style\":\"document\",\"time_of_day\":null},"
            + "\"objects\":[{\"id\":1,\"name\":\"paper\",\"bbox\":[150,140,520,900],\"score\":0.71,"
            + "\"description\":{\"color\":\"white\",\"material\":\"paper\"},\"position\":\"center\"},"
            + "{\"id\":2,\"name\":\"text\",\"score\":0.5}],"
            + "\"text\":[{\"string\":\"GREEN LEAF CAFE\",\"bbox\":[163,172,484,237],\"score\":0.738},"
            + "{\"string\":\"Caf\\u00e9 au lait 3.50 \\u20ac\",\"bbox\":[178,290,326,337],\"score\":0.5},"
            + "{\"string\":\"TOTAL\",\"score\":0.518}]}";

    static String deep(int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) b.append('[');
        for (int i = 0; i < n; i++) b.append(']');
        return b.toString();
    }

    public static void main(String[] a) throws Exception {
        // ---- the JSON reader
        Object v = Json.parse(" {\"a\": [1, -2.5, 3e2, true, false, null], \"b\": {\"c\": \"x\\\"y\\n\\u00e9\"}, \"d\": []} ");
        check("object with keys in order", v instanceof Map && ((Map<?, ?>) v).keySet().toString().equals("[a, b, d]"));
        List<Object> l = Json.list(v, "a");
        check("numbers, booleans, null", l.size() == 6 && l.get(0).equals(1.0) && l.get(1).equals(-2.5) && l.get(2).equals(300.0)
                && l.get(3) == Boolean.TRUE && l.get(4) == Boolean.FALSE && l.get(5) == null);
        check("escapes", "x\"y\n\u00e9".equals(Json.str(Json.obj(v, "b"), "c")));
        check("empty array", Json.list(v, "d").isEmpty());
        check("missing keys are null / empty / default", Json.obj(v, "zz") == null && Json.list(v, "zz").isEmpty()
                && Json.str(v, "zz") == null && Json.num(v, "zz", 7) == 7 && Json.str(v, "a") == null);
        check("unicode outside the escapes", "naïve €".equals(Json.parse("\"naïve €\"")));
        for (String b : new String[]{"", "{", "{\"a\":}", "[1,]", "[1 2]", "{\"a\" 1}", "\"abc", "tru", "1 2", "{'a':1}", "\"\\x\"", "-", deep(71)})
            check("rejects " + b, bad(b));

        // ---- Mirage's answer
        Mirage.Answer ans = Mirage.read(ANSWER);
        check("scene pairs, nulls left out", ans.scene.size() == 2 && ans.scene.get(0).equals("background brown") && ans.scene.get(1).equals("style document"));
        check("objects", ans.objects.size() == 2 && ans.objects.get(0).name.equals("paper") && Math.abs(ans.objects.get(0).score - 0.71) < 1e-9
                && ans.objects.get(0).about.equals("white, paper, center") && ans.objects.get(0).box[3] == 900 && ans.objects.get(1).box == null);
        check("text lines in order, with accents and €", ans.text.size() == 3 && ans.text.get(1).name.equals("Café au lait 3.50 €")
                && ans.allText().equals("GREEN LEAF CAFE\nCafé au lait 3.50 €\nTOTAL"));
        check("a line without a box", ans.text.get(2).box == null && ans.text.get(0).box[0] == 163);
        List<String> lines = Mirage.lines(ans);
        String all = String.join("\n", lines);
        check("Lab lines: scene, objects, text", lines.get(0).equals("  scene: background brown; style document")
                && lines.get(1).startsWith("  objects (detection score): paper 0.71 (white, paper, center); text 0.50")
                && lines.get(2).equals("  text: 3 lines (score, box on 0-1000):")
                && all.contains("    \"GREEN LEAF CAFE\" 0.74 [163,172 - 484,237]") && all.contains("    \"TOTAL\" 0.52"));
        Mirage.Answer empty = Mirage.read("{\"scene\":{},\"objects\":[],\"text\":[]}");
        List<String> el = Mirage.lines(empty);
        check("nothing found", el.get(0).equals("  scene: (nothing said)") && el.get(1).equals("  objects: none named") && el.get(2).equals("  text: no lines found"));
        check("options ask for no masks", Json.list(Json.parse(Mirage.OPTIONS), "fields").toString().equals("[score, bbox]")
                && Json.list(Json.parse(Mirage.OPTIONS), "sections").size() == 3);

        // ---- the Lab check, on a drawn picture
        File dir = Files.createTempDirectory("mirage").toFile();
        File png = new File(dir, "p.png");
        BufferedImage im = new BufferedImage(320, 200, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 200; y++) for (int x = 0; x < 320; x++) im.setRGB(x, y, (x * 255 / 320) << 16 | (y * 255 / 200) << 8 | 90);
        ImageIO.write(im, "png", png);
        Lab.Pic pic = new Lab.Pic(png.getPath(), "p.png", png.length(), 0);
        JvmPlatform pf = new JvmPlatform(dir.getPath());
        List<String> out = new ArrayList<String>();
        Lab.mirage(pf, pic, out::add);
        String o1 = String.join("\n", out);
        check("without Mirage the Lab says why", o1.contains("! Mirage gave nothing") && o1.contains("only on the phone") && o1.contains("Tesseract"));
        File saved = new File(dir, "answer.json");
        Files.write(saved.toPath(), ANSWER.getBytes(StandardCharsets.UTF_8));
        System.setProperty("mirage", saved.getPath());
        out.clear();
        Lab.mirage(pf, pic, out::add);
        String o2 = String.join("\n", out);
        check("with an answer the Lab shows it", o2.contains("Mirage: ") && o2.contains("on a 320 x 200 copy") && o2.contains("\"Café au lait 3.50 €\" 0.50"));
        Files.write(saved.toPath(), "{\"scene\":".getBytes(StandardCharsets.UTF_8));
        out.clear();
        Lab.mirage(pf, pic, out::add);
        check("a broken answer is reported, not thrown", String.join("\n", out).contains("! could not read Mirage's answer"));
        System.clearProperty("mirage");

        // ---- clean-up: a caption read in overlapping pieces on one row, icon fragments, a low-score line that is real
        List<Mirage.Thing> raw = Mirage.read("{\"text\":["
                + "{\"string\":\"9:41\",\"bbox\":[20,10,200,30],\"score\":0.64},"
                + "{\"string\":\"C\",\"bbox\":[890,570,950,600],\"score\":0.41},"
                + "{\"string\":\"7\",\"bbox\":[880,650,950,680],\"score\":0.34},"
                + "{\"string\":\"OK\",\"bbox\":[400,950,460,980],\"score\":0.8},"
                + "{\"string\":\"rning to all frie\",\"bbox\":[100,820,700,843],\"score\":0.62},"
                + "{\"string\":\"Good morning to all friends\",\"bbox\":[60,818,950,842],\"score\":0.41},"
                + "{\"string\":\"to all\",\"bbox\":[530,824,700,840],\"score\":0.33},"
                + "{\"string\":\"KA01AB1234\",\"bbox\":[190,477,320,502],\"score\":0.40}]}").text;
        Mirage.Clean cl = Mirage.clean(raw);
        List<String> kept = new ArrayList<String>();
        for (Mirage.Thing t : cl.kept) kept.add(t.name);
        check("clean-up keeps the full caption once, the clock, OK and the low-score plate", kept.toString().equals("[9:41, OK, Good morning to all friends, KA01AB1234]"));
        check("clean-up counts " + cl.merged + " " + cl.fragments, cl.merged == 2 && cl.fragments == 2);
        check("lines on different rows are not merged", !Mirage.sameRow(new int[]{0, 0, 100, 20}, new int[]{0, 30, 100, 50})
                && !Mirage.sameRow(new int[]{0, 0, 100, 20}, new int[]{200, 0, 300, 20}) && Mirage.sameRow(new int[]{0, 0, 100, 20}, new int[]{50, 5, 300, 22}));
        TextRules.Words ws = Mirage.words(cl.kept);
        check("words with confidence from the score", ws.text.size() == 8 && ws.text.get(2).equals("Good") && ws.conf.get(2) == 81 && ws.conf.get(0) == 99);
        check("style", "graphic".equals(Mirage.style(Mirage.read("{\"scene\":{\"style\":\"flat vector illustration\"}}")))
                && "photograph".equals(Mirage.style(Mirage.read("{\"scene\":{\"style\":\"photograph\"}}")))
                && "screenshot".equals(Mirage.style(Mirage.read("{\"scene\":{\"style\":\"screenshot\"}}")))
                && Mirage.style(Mirage.read("{\"scene\":{\"setting\":\"indoor\"}}")) == null);
        Mirage.Answer ppl = Mirage.read("{\"objects\":[{\"name\":\"man\",\"bbox\":[1,1,5,5]},{\"name\":\"woman\",\"bbox\":[6,1,9,5]},"
                + "{\"name\":\"price tag\"},{\"name\":\"manhole\"},{\"name\":\"door\"}]}");
        check("people and things photographed to remember", Mirage.people(ppl) == 2 && Mirage.reference(ppl).toString().equals("[price tag]"));

        // ---- the verdict: a greeting forward, a one-time code screenshot, a photo of a person
        // a realistic drawn picture: a coloured panel and lines of dark text on white (not "accidental" to the pixel checks)
        File drawn = new File(dir, "drawn.png");
        BufferedImage di = new BufferedImage(720, 1280, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = di.createGraphics();
        g.setColor(java.awt.Color.WHITE); g.fillRect(0, 0, 720, 1280);
        g.setColor(new java.awt.Color(230, 120, 40)); g.fillRect(0, 0, 720, 360);
        g.setColor(new java.awt.Color(40, 120, 220)); g.fillOval(420, 60, 220, 220);
        g.setColor(java.awt.Color.BLACK); g.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD, 40));
        for (int y = 460; y < 1200; y += 70) g.drawString("Lorem ipsum dolor sit amet " + y, 40, y);
        g.dispose();
        ImageIO.write(di, "png", drawn);
        long now = System.currentTimeMillis(), old = now - 30L * 86400000L;
        RuleSet rules = RuleSet.defaults();
        String[][] cases = {
            {"wa_image", "{\"scene\":{\"style\":\"graphic design\"},\"objects\":[{\"name\":\"flower\",\"score\":0.6}],"
                + "\"text\":[{\"string\":\"Good Morning\",\"bbox\":[100,100,900,200],\"score\":0.7},{\"string\":\"Have a blessed day\",\"bbox\":[100,800,900,880],\"score\":0.6}]}"},
            {"screenshot", "{\"scene\":{\"style\":\"screenshot\"},\"objects\":[],"
                + "\"text\":[{\"string\":\"Messages\",\"bbox\":[50,40,300,70],\"score\":0.7},{\"string\":\"482913 is your OTP for login.\",\"bbox\":[50,300,950,340],\"score\":0.7},"
                + "{\"string\":\"Do not share it with anyone.\",\"bbox\":[50,350,950,390],\"score\":0.66}]}"},
            {"camera", "{\"scene\":{\"setting\":\"indoor\",\"style\":\"photograph\"},\"objects\":[{\"name\":\"man\",\"score\":0.78},{\"name\":\"door\",\"score\":0.57}],\"text\":[]}"},
            {"wa_image", "{\"scene\":{\"style\":\"flat vector illustration\"},\"objects\":[{\"name\":\"circle\",\"score\":0.5}],\"text\":[]}"},
        };
        String[] want = {"SUGGEST DELETING", "SUGGEST DELETING", "KEEP", null};
        for (int k = 0; k < cases.length; k++) {
            Files.write(saved.toPath(), cases[k][1].getBytes(StandardCharsets.UTF_8));
            System.setProperty("mirage", saved.getPath());
            Lab.Pic vp = new Lab.Pic(drawn.getPath(), "case" + k + ".png", drawn.length(), old);
            vp.area = cases[k][0];
            out.clear();
            Lab.Shown vs = Lab.mirageVerdict(pf, vp, rules, now, out::add);
            String vo = String.join("\n", out);
            String verdictLine = "";
            for (String x : out) if (x.contains("=> VERDICT")) verdictLine = x;
            if (a.length > 0) System.out.println(vo + "\n");
            if (k == 0) check("greeting forward: category from Mirage's text, deleted as an old greeting", vo.contains("category with Mirage: Greetings & forwards")
                    && verdictLine.contains("SUGGEST DELETING") && vs.file != null && PhotoCategory.GREETING.equals(vs.file.category));
            if (k == 1) check("one-time code screenshot", vo.contains("category with Mirage: One-time codes") && verdictLine.contains(want[1]) && vo.contains("\"otp\""));
            if (k == 2) check("a photo of a person is kept, its text not used", verdictLine.contains("KEEP") && vo.contains("1 person"));
            if (k == 3) check("a chat image drawn as a graphic is a forward", vo.contains("category with Mirage: Graphics & images (Mirage: drawn as \"flat vector illustration\""));
        }
        // the gradient picture looks "accidental" to the pixel checks; Mirage reading a greeting on it overrules that
        Files.write(saved.toPath(), cases[0][1].getBytes(StandardCharsets.UTF_8));
        Lab.Pic gp = new Lab.Pic(png.getPath(), "gradient.png", png.length(), old);
        gp.area = "wa_image";
        out.clear();
        Lab.mirageVerdict(pf, gp, rules, now, out::add);
        String go = String.join("\n", out);
        if (a.length > 0) System.out.println(go + "\n");
        check("an 'accidental' picture with text Mirage reads is not accidental", go.contains("category today (the app's text reader, not needed here): Accidental shots")
                && go.contains("category with Mirage: Greetings & forwards"));
        Files.write(saved.toPath(), cases[2][1].getBytes(StandardCharsets.UTF_8));
        gp.area = "camera";
        out.clear();
        Lab.mirageVerdict(pf, gp, rules, now, out::add);
        go = String.join("\n", out);
        if (a.length > 0) System.out.println(go + "\n");
        check("an 'accidental' camera photo where Mirage sees a man is a photo of a person", go.contains("category with Mirage: People (Mirage recognised a man: not an accidental shot)")
                && !go.contains("VERDICT with Mirage: SUGGEST DELETING"));
        System.clearProperty("mirage");
        out.clear();
        Lab.mirageVerdict(pf, pic, rules, now, out::add);
        String nv = String.join("\n", out);
        check("verdict without Mirage still says today's", nv.contains("! Mirage gave nothing") && nv.contains("=> VERDICT today:"));

        System.out.println("MirageTest: " + (checks - fails) + "/" + checks + " checks passed");
        if (fails > 0) System.exit(1);
    }
}
