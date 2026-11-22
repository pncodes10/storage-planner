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

        System.out.println("MirageTest: " + (checks - fails) + "/" + checks + " checks passed");
        if (fails > 0) System.exit(1);
    }
}
