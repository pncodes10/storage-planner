package com.storageplanner.test;

import com.storageplanner.core.*;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

/**
 * Mirage in the decision engine (1.11): the mirage_* tags (unknown until Mirage looked; mirage_looked and mirage_sees
 * always known), the "contains" operator, the "never delete" (protect) action, the three built-in rules that use
 * Mirage (accidental shot only when Mirage sees nothing, old reference photo, people seen by Mirage never deleted),
 * saving with the results, and the Mirage pass of a scan (with a saved answer: the model runs only on the phone).
 * Invented data only.
 */
public final class MirageTagsTest {
    static int checks, fails;
    static final long NOW = 1795000000000L, DAY = 86400000L;

    static void check(String what, boolean ok) {
        checks++;
        if (!ok) { fails++; System.out.println("FAIL " + what); }
    }

    static FileItem pic(String rel, String area, String category, double ageDays) {
        FileItem f = new FileItem(rel, "/sd/" + rel, 2000000, NOW - (long) (ageDays * DAY));
        f.area = area; f.type = "jpeg"; f.category = category; f.width = 4000; f.height = 3000; f.faces = 0; f.sortVersion = 6;
        return f;
    }

    static void looked(FileItem f, String style, int people, boolean sees, boolean reference, String objects) {
        f.mirageVersion = AlgorithmV3.VERSION; f.mirageStyle = style; f.miragePeople = people; f.mirageSees = sees;
        f.mirageReference = reference; f.mirageObjects = objects; f.mirageScene = "setting outdoor; lighting night"; f.v3Category = f.category;
    }

    static List<Option> options(UnitBuilder ub, FileItem f) {
        for (Unit u : ub.units) if (f.rel.equals(u.id)) return u.options;
        return new java.util.ArrayList<Option>();
    }

    static boolean has(List<Option> l, String technique) { for (Option o : l) if (o.technique.equals(technique)) return true; return false; }

    static boolean deletes(List<Option> l) { for (Option o : l) if (o.action.equals("delete")) return true; return false; }

    public static void main(String[] a) throws Exception {
        // ---- tags
        FileItem not = pic("DCIM/Camera/a.jpg", "camera", PhotoCategory.PHOTO, 5);
        Scanner.Result r0 = new Scanner.Result();
        r0.files.add(not);
        Tags.Context ctx0 = Tags.context(r0, new UnitBuilder.Detections());
        Tags.T t0 = Tags.of(not, ctx0, NOW);
        check("not looked at: the Mirage facts are unknown", t0.mirageStyle == null && t0.miragePeople == Tags.UNKNOWN && t0.mirageObjects == null
                && t0.mirageScene == null && t0.referencePhoto == null && t0.v3Category == null && !t0.get(Tags.MIRAGE_STYLE).known);
        check("not looked at: mirage_looked and mirage_sees are known, and no", Boolean.FALSE.equals(t0.flagTag(Tags.MIRAGE_LOOKED))
                && Boolean.FALSE.equals(t0.flagTag(Tags.MIRAGE_SEES)));
        FileItem seen = pic("WhatsApp/Media/WhatsApp Images/b.jpg", "wa_image", PhotoCategory.PEOPLE, 5);
        looked(seen, AlgorithmV3.PHOTOGRAPH, 3, true, false, "woman, man, red car, flag");
        Tags.T t1 = Tags.of(seen, ctx0, NOW);
        check("looked at: style, people, things, scene, category", "photograph".equals(t1.textTag(Tags.MIRAGE_STYLE)) && t1.numTag(Tags.MIRAGE_PEOPLE) == 3
                && t1.textTag(Tags.MIRAGE_OBJECTS).equals("woman, man, red car, flag") && t1.textTag(Tags.MIRAGE_SCENE).startsWith("setting outdoor")
                && Boolean.FALSE.equals(t1.flagTag(Tags.REFERENCE_PHOTO)) && "people".equals(t1.textTag(Tags.V3_CATEGORY))
                && Boolean.TRUE.equals(t1.flagTag(Tags.MIRAGE_LOOKED)) && Boolean.TRUE.equals(t1.flagTag(Tags.MIRAGE_SEES))
                && t1.get(Tags.V3_CATEGORY).value.equals("People"));
        check("every new tag is listed for the rule editor", Tags.info(Tags.MIRAGE_OBJECTS) != null && Tags.info(Tags.V3_CATEGORY) != null
                && Tags.info(Tags.REFERENCE_PHOTO) != null && Tags.choices(Tags.MIRAGE_STYLE).size() == 5 && Tags.choices(Tags.V3_CATEGORY).size() == PhotoCategory.ALL.length);

        // ---- contains
        Rule.Condition car = Rule.Condition.parse("mirage_objects contains car, price tag");
        check("contains: parsed, written back the same", car != null && car.op == Rule.Op.CONTAINS && car.problem() == null
                && car.toText().equals("mirage_objects contains car, price tag"));
        check("contains: whole words, any of the list", car.matches(t1));
        looked(seen, AlgorithmV3.PHOTOGRAPH, 3, true, false, "carpet, sofa");
        check("contains: 'car' does not find 'carpet'", !car.matches(Tags.of(seen, ctx0, NOW)));
        looked(seen, AlgorithmV3.PHOTOGRAPH, 0, true, true, "sign, price tag");
        check("contains: a phrase", car.matches(Tags.of(seen, ctx0, NOW)));
        Rule.Condition notCar = Rule.Condition.parse("NOT mirage_objects contains car");
        check("NOT contains: unknown never matches", notCar.matches(Tags.of(seen, ctx0, NOW)) && !notCar.matches(t0));
        check("contains needs a text tag", Rule.Condition.parse("faces contains 2").problem() != null);
        check("v3 category matches its name too", Rule.Condition.parse("mirage_category = Photos without faces").matches(Tags.of(pic("x", "camera", PhotoCategory.PHOTO, 1), ctx0, NOW)) == false
                && new Rule.Condition(Tags.V3_CATEGORY, Rule.Op.EQ, "People", false).matches(t1));

        // ---- the rules
        FileItem acc = pic("DCIM/Camera/acc.jpg", "camera", PhotoCategory.ACCIDENTAL, 3);
        FileItem accSeen = pic("DCIM/Camera/acc2.jpg", "camera", PhotoCategory.ACCIDENTAL, 3);
        looked(accSeen, AlgorithmV3.PHOTOGRAPH, 0, true, false, "dog");
        FileItem accNothing = pic("DCIM/Camera/acc3.jpg", "camera", PhotoCategory.ACCIDENTAL, 3);
        looked(accNothing, AlgorithmV3.UNSPECIFIED, 0, false, false, "");
        FileItem ref = pic("DCIM/Camera/ref.jpg", "camera", PhotoCategory.PHOTO, 90);
        looked(ref, AlgorithmV3.PHOTOGRAPH, 0, true, true, "sign, parking");
        FileItem refNew = pic("DCIM/Camera/ref2.jpg", "camera", PhotoCategory.PHOTO, 30);
        looked(refNew, AlgorithmV3.PHOTOGRAPH, 0, true, true, "price tag");
        FileItem crowd = pic("WhatsApp/Media/WhatsApp Images/crowd.jpg", "wa_image", PhotoCategory.GRAPHIC, 20);
        looked(crowd, AlgorithmV3.PHOTOGRAPH, 5, true, false, "woman, man");
        FileItem comic = pic("WhatsApp/Media/WhatsApp Images/comic.jpg", "wa_image", PhotoCategory.GRAPHIC, 20);
        looked(comic, AlgorithmV3.COLLAGE, 3, true, false, "man, laptop");
        FileItem dupA = pic("DCIM/Camera/dupA.jpg", "camera", PhotoCategory.PEOPLE, 10), dupB = pic("DCIM/Camera/dupB.jpg", "camera", PhotoCategory.PEOPLE, 10);
        dupA.sha256 = dupB.sha256 = "ab12";
        looked(dupA, AlgorithmV3.PHOTOGRAPH, 2, true, false, "man"); looked(dupB, AlgorithmV3.PHOTOGRAPH, 2, true, false, "man");
        Scanner.Result r = new Scanner.Result();
        r.now = NOW;
        for (FileItem f : new FileItem[] {acc, accSeen, accNothing, ref, refNew, crowd, comic, dupA, dupB}) r.files.add(f);
        UnitBuilder ub = new UnitBuilder(r, new Estimates(), 34, RuleSet.defaults());
        check("accidental shot, Mirage did not look: still suggested", has(options(ub, acc), Techniques.ACCIDENTAL));
        check("accidental shot, but Mirage sees a dog: not suggested", !has(options(ub, accSeen), Techniques.ACCIDENTAL));
        check("accidental shot, Mirage looked and sees nothing: suggested", has(options(ub, accNothing), Techniques.ACCIDENTAL));
        check("a photo of a sign, 90 days old: deleted as a reference photo", has(options(ub, ref), Techniques.REFERENCE));
        check("a reference photo 30 days old: not yet", !has(options(ub, refNew), Techniques.REFERENCE));
        List<Option> co = options(ub, crowd);
        check("a real photo of people (graphic for v2): never deleted, still compressed", !deletes(co) && has(co, Techniques.CHAT_IMAGES));
        check("a comic (collage) with drawn people: the meme rule still deletes it", has(options(ub, comic), Techniques.FORWARDS));
        boolean dupGoes = has(options(ub, dupA), Techniques.DUPLICATES) || has(options(ub, dupB), Techniques.DUPLICATES);
        check("people seen by Mirage: an identical copy may still go", dupGoes);
        RuleSet off = RuleSet.defaults();
        off.find("protect_people_mirage").enabled = false;
        check("protect rule switched off: the photo of people is a meme deletion again", has(options(new UnitBuilder(r, new Estimates(), 34, off), crowd), Techniques.FORWARDS));
        Rule pr = off.find("protect_people_mirage");
        check("the protect rule in words", RuleText.action(pr).startsWith("Never delete") && RuleText.actionSentence(pr).contains("compressing them is still suggested")
                && pr.effectiveRisk() == -1 && !pr.action.compress());

        // ---- a protect rule of your own, saved and loaded
        RuleSet mine = RuleSet.defaults();
        Rule p = mine.addUser("Keep cars", Rule.Action.PROTECT);
        p.when(Tags.MIRAGE_OBJECTS, Rule.Op.CONTAINS, "car");
        RuleSet back = RuleSet.fromText(mine.toText());
        Rule pb = back.find(p.id);
        check("your protect rule is saved and loaded", pb != null && pb.action == Rule.Action.PROTECT && pb.when.get(0).op == Rule.Op.CONTAINS
                && pb.problem() == null && back.warnings.isEmpty());

        // ---- saved with the results
        ResultStore.Saved sv = new ResultStore.Saved();
        sv.result = r; sv.est = new Estimates();
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        ResultStore.write(o, sv);
        Map<String, FileItem> l = new HashMap<String, FileItem>();
        for (FileItem f : ResultStore.read(new ByteArrayInputStream(o.toByteArray())).result.files) l.put(f.rel, f);
        FileItem c2 = l.get(crowd.rel), a2 = l.get(acc.rel);
        check("saved: what Mirage saw comes back", c2.mirageVersion == AlgorithmV3.VERSION && "photograph".equals(c2.mirageStyle) && c2.miragePeople == 5
                && c2.mirageSees && !c2.mirageReference && c2.mirageObjects.equals("woman, man") && c2.mirageScene.startsWith("setting") && "graphic".equals(c2.v3Category)
                && l.get(ref.rel).mirageReference && a2.mirageVersion == 0 && a2.miragePeople == -1);
        ByteArrayOutputStream o10 = new ByteArrayOutputStream();
        ResultStore.write(o10, sv, 10);
        FileItem old = null;
        for (FileItem f : ResultStore.read(new ByteArrayInputStream(o10.toByteArray())).result.files) if (f.rel.equals(crowd.rel)) old = f;
        check("a format 10 file loads, Mirage not looked at yet", old != null && old.mirageVersion == 0 && old.mirageStyle == null);

        // ---- the Mirage pass of a scan
        File dev = Files.createTempDirectory("miragetags").toFile();
        new File(dev, "meta").mkdirs();
        Files.write(new File(dev, "meta/usagestats.json").toPath(), "{}".getBytes(StandardCharsets.UTF_8));
        File wa = new File(dev, "sdcard/WhatsApp/Media/WhatsApp Images/IMG-20261101-WA0001.jpg");
        wa.getParentFile().mkdirs();
        BufferedImage im = new BufferedImage(640, 480, BufferedImage.TYPE_INT_RGB);
        java.util.Random rnd = new java.util.Random(9);
        for (int y = 0; y < 480; y++) for (int x = 0; x < 640; x++) im.setRGB(x, y, (60 + rnd.nextInt(120)) << 16 | (60 + rnd.nextInt(120)) << 8 | (40 + rnd.nextInt(100)));
        ImageIO.write(im, "jpg", wa);
        wa.setLastModified(NOW - 20 * DAY);
        File answer = new File(dev, "answer.json");
        Files.write(answer.toPath(), ("{\"scene\":{\"setting\":\"street\",\"style\":\"photograph\"},\"objects\":[{\"name\":\"woman\",\"score\":0.7},"
                + "{\"name\":\"woman\",\"bbox\":[500,10,700,900],\"score\":0.66},{\"name\":\"car\",\"score\":0.55},{\"name\":\"text\",\"score\":0.4},{\"name\":\"bush\",\"score\":0.2}],"
                + "\"text\":[]}").getBytes(StandardCharsets.UTF_8));
        JvmPlatform pf = new JvmPlatform(dev.getPath());
        Scanner.Progress quiet = new Scanner.Progress() { public void update(String s, int d, int t) {} public boolean cancelled() { return false; } };
        Scanner.Result s0 = Scanner.scan(pf, quiet);
        FileItem w0 = s0.files.get(0);
        check("scan without Mirage: nothing recorded, tags unknown", w0.mirageVersion == 0 && w0.category != null);
        System.setProperty("mirage", answer.getPath());
        Scanner.Result s1 = Scanner.scan(pf, quiet);
        FileItem w1 = s1.files.get(0);
        if (a.length > 0) System.out.println(w1.rel + ": " + w1.category + " / v3 " + w1.v3Category + " / " + w1.mirageStyle + " / " + w1.miragePeople + " / " + w1.mirageObjects);
        check("scan with Mirage: style, people with a good score, things (no 'text', no weak guesses), scene, v3 category", w1.mirageVersion == AlgorithmV3.VERSION
                && "photograph".equals(w1.mirageStyle) && w1.miragePeople == 2 && w1.mirageObjects.equals("woman, car") && w1.mirageScene.equals("setting street; style photograph")
                && w1.mirageSees && !w1.mirageReference && "people".equals(w1.v3Category));
        Files.write(answer.toPath(), "{broken".getBytes(StandardCharsets.UTF_8));
        Scanner.Options again = new Scanner.Options();
        again.previous = new HashMap<String, FileItem>();
        for (FileItem f : s1.files) again.previous.put(f.rel, f);
        Scanner.Result s2 = Scanner.scan(pf, quiet, again);
        check("an unchanged picture keeps what Mirage saw (not looked at again)", s2.files.get(0).mirageVersion == AlgorithmV3.VERSION && s2.files.get(0).miragePeople == 2);
        Scanner.Options offMirage = new Scanner.Options();
        offMirage.mirageBudgetMs = 0;
        Files.write(answer.toPath(), "{\"scene\":{},\"objects\":[],\"text\":[]}".getBytes(StandardCharsets.UTF_8));
        check("Mirage time limit 0: the pass is off", Scanner.scan(pf, quiet, offMirage).files.get(0).mirageVersion == 0);
        System.clearProperty("mirage");

        System.out.println("MirageTagsTest: " + (checks - fails) + "/" + checks + " checks passed");
        if (fails > 0) System.exit(1);
    }
}
