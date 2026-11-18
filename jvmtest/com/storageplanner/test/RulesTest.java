package com.storageplanner.test;

import com.storageplanner.core.*;
import com.storageplanner.core.Rule.Action;
import com.storageplanner.core.Rule.Condition;
import com.storageplanner.core.Rule.Op;
import com.storageplanner.core.Rule.Saving;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The rule engine: every operator, unknown never matches (also with NOT), user rule limits (R4 delete, R2 re-encode,
 * KEEP), the ID document rail, switching off and editing built-in rules, the text format, and the speed.
 */
public final class RulesTest {
    static int checks, fails;
    static final long NOW = 1792000000000L, DAY = 86400000L;

    static void check(String what, boolean ok) {
        checks++;
        if (!ok) { fails++; System.out.println("FAIL " + what); }
    }

    static boolean m(String line, Tags.T t) { return Condition.parse(line).matches(t); }

    static FileItem file(String rel, String area, String type, long size, double ageDays) {
        FileItem f = new FileItem(rel, "/sdcard/" + rel, size, NOW - Math.round(ageDays * DAY));
        f.area = area; f.type = type;
        return f;
    }

    static Scanner.Result scan(FileItem... fs) {
        Scanner.Result r = new Scanner.Result();
        r.now = NOW; r.total = 100L << 30; r.free = 10L << 30;
        for (FileItem f : fs) r.files.add(f);
        return r;
    }

    static Unit unit(UnitBuilder ub, String rel) { for (Unit u : ub.units) if (u.id.equals(rel)) return u; return null; }
    static Option opt(UnitBuilder ub, String rel, String action) {
        Unit u = unit(ub, rel);
        if (u != null) for (Option o : u.options) if (o.action.equals(action)) return o;
        return null;
    }

    public static void main(String[] args) {
        // ---------------------------------------------------------------- operators
        Tags.T t = new Tags.T();
        t.ageDays = 5; t.faces = 2; t.source = "chat image (received)"; t.area = "wa_image"; t.category = PhotoCategory.GREETING;
        t.screenshot = false; t.chatMedia = true; t.megapixels = 12.5;
        check("= number", m("age_days = 5", t) && !m("age_days = 6", t) && m("faces = 2.0", t));
        check("!= number", m("age_days != 6", t) && !m("age_days != 5", t));
        check("> >= < <=", m("age_days > 4.99", t) && !m("age_days > 5", t) && m("age_days >= 5", t) && !m("age_days >= 5.01", t)
                && m("age_days < 5.01", t) && !m("age_days < 5", t) && m("age_days <= 5", t) && !m("age_days <= 4.99", t));
        check("decimal values", m("megapixels > 12.4", t) && m("megapixels < 12.6", t));
        check("= text ignores case", m("source = Chat Image (Received)", t) && !m("source = camera photo", t));
        check("!= text", m("source != camera photo", t) && !m("source != chat image (received)", t));
        check("in list", m("area in camera, pictures,wa_image", t) && !m("area in camera,pictures", t) && m("area in WA_IMAGE", t));
        check("category by id or by shown name", m("category = greeting", t) && m("category = Greetings & forwards", t) && m("category in otp,greeting", t)
                && !m("category = otp", t));
        check("is yes / is no", m("chat_media is yes", t) && !m("chat_media is no", t) && m("screenshot is no", t) && !m("screenshot is yes", t));
        check("= yes / = no on a yes/no tag", m("chat_media = yes", t) && m("screenshot = no", t) && !m("screenshot = yes", t) && m("chat_media != no", t));
        check("NOT", !m("NOT age_days > 3", t) && m("NOT age_days > 9", t) && m("NOT chat_media is no", t) && m("NOT source = camera photo", t));
        check("is known", m("faces is known", t) && m("category is known", t) && !m("NOT faces is known", t));

        // ---------------------------------------------------------------- unknown never matches, with or without NOT
        Tags.T u = new Tags.T();                                // nothing measured
        u.ageDays = 5;
        String[] unknownLines = {"faces > 0", "faces = 0", "faces != 0", "faces < 5", "faces >= 0", "faces <= 3", "blur > 70", "brightness < 20",
            "bits_per_pixel >= 1.0", "bitrate_kbps > 0", "days_since_date_in_picture > 7", "category = greeting", "category != greeting",
            "category in otp,greeting", "file_type = jpeg", "video_codec is known", "eyes_closed is yes", "eyes_closed is no", "eyes_closed = no",
            "has_code is yes", "date_in_picture is no", "faces is known"};
        boolean anyMatch = false, anyNotMatch = false;
        for (String l : unknownLines) { anyMatch |= m(l, u); anyNotMatch |= m("NOT " + l, u); }
        check("an unknown value never matches", !anyMatch);
        check("an unknown value never matches with NOT either", !anyNotMatch);
        check("NOT faces > 0 does not match a picture whose faces were never checked", !m("NOT faces > 0", u));
        u.faces = 0;
        check("0 faces is known: NOT faces > 0 matches, faces > 0 does not", m("NOT faces > 0", u) && !m("faces > 0", u) && m("faces = 0", u));
        check("a bad number or a wrong operator never matches", !m("age_days > abc", t) && !m("source > 3", t) && !m("age_days in 5", t) && !m("age_days is yes", t)
                && !m("NOT age_days > abc", t) && !m("NOT source > 3", t));
        check("problems are reported", Condition.parse("age_days > abc").problem() != null && Condition.parse("source > 3").problem() != null
                && Condition.parse("nothing = 1").problem() != null && Condition.parse("age_days > 3").problem() == null
                && Condition.parse("screenshot = maybe").problem() != null && Condition.parse("area in ").problem() != null);
        check("lines that are not conditions", Condition.parse("age_days") == null && Condition.parse("age_days ~ 3") == null);
        check("tag names that start like an operator word are not mixed up", Condition.parse("installer_already_installed is yes").tag.equals("installer_already_installed")
                && Condition.parse("area in a,b").op == Op.IN && Condition.parse("area = interior").op == Op.EQ && Condition.parse("area = interior").value.equals("interior"));

        // ---------------------------------------------------------------- the four example rules
        {
            RuleSet rs = new RuleSet();
            Rule r1 = rs.addUser("Old greetings", Action.DELETE);
            r1.when(Tags.SOURCE, Op.EQ, "chat image (received)").when(Tags.CATEGORY, Op.EQ, "greeting").when(Tags.AGE_DAYS, Op.GT, "3").whenNot(Tags.FACES, Op.GT, "0");
            Rule r2 = rs.addUser("Chat graphics", Action.DELETE);
            r2.when(Tags.CHAT_MEDIA, Op.EQ, "yes").when(Tags.CATEGORY, Op.EQ, "graphic").when(Tags.AGE_DAYS, Op.GT, "30");
            Rule r3 = rs.addUser("Dark and blurry", Action.DELETE);
            r3.when(Tags.CAMERA_PHOTO, Op.EQ, "yes").when(Tags.BLUR, Op.GT, "70").when(Tags.BRIGHTNESS, Op.LT, "20");
            Rule r4 = rs.addUser("People", Action.KEEP);
            r4.when(Tags.FACES, Op.GT, "0");
            List<FileItem> fs = new ArrayList<FileItem>();
            FileItem g1 = file("WhatsApp/Images/g1.jpg", "wa_image", "jpeg", 50000, 10); g1.category = PhotoCategory.GREETING; g1.faces = 0; fs.add(g1);
            FileItem g2 = file("WhatsApp/Images/g2.jpg", "wa_image", "jpeg", 50000, 10); g2.category = PhotoCategory.GREETING; g2.faces = 1; fs.add(g2);
            FileItem g3 = file("WhatsApp/Images/g3.jpg", "wa_image", "jpeg", 50000, 10); g3.category = PhotoCategory.GREETING; fs.add(g3);     // faces never checked
            FileItem g4 = file("WhatsApp/Images/g4.jpg", "wa_image", "jpeg", 50000, 2); g4.category = PhotoCategory.GREETING; g4.faces = 0; fs.add(g4);   // too new
            FileItem x1 = file("Telegram/x1.png", "telegram_media", "png", 70000, 40); x1.category = PhotoCategory.GRAPHIC; fs.add(x1);
            FileItem x2 = file("Telegram/x2.png", "telegram_media", "png", 70000, 20); x2.category = PhotoCategory.GRAPHIC; fs.add(x2);
            FileItem x3 = file("DCIM/Camera/x3.jpg", "camera", "jpeg", 70000, 400); x3.category = PhotoCategory.GRAPHIC; fs.add(x3);               // not chat media
            for (int i = 0; i < 20; i++) {                                                                   // camera photos: the first is the blurriest and dark
                FileItem c = file("DCIM/Camera/c" + i + ".jpg", "camera", "jpeg", 2000000, 100 + i);
                c.width = 4000; c.height = 3000; c.sharp = 10 + i * 10;
                ImageSignals.S s = new ImageSignals.S(); s.meanLuma = i == 0 ? 20 : i == 1 ? 200 : 100;
                c.signals = s;
                fs.add(c);
            }
            FileItem cdark2 = fs.get(fs.size() - 1); cdark2.signals.meanLuma = 10;                                  // sharpest photo, dark: not blurry
            FileItem face = file("DCIM/Camera/face.jpg", "camera", "jpeg", 2000000, 100); face.faces = 3; face.sha256 = "dup"; fs.add(face);
            FileItem face2 = file("Pictures/face2.jpg", "pictures", "jpeg", 2000000, 100); face2.faces = 3; face2.sha256 = "dup"; fs.add(face2);   // would be a duplicate
            UnitBuilder ub = new UnitBuilder(scan(fs.toArray(new FileItem[0])), new Estimates(), 34, rs);
            Option o;
            check("example 1: greeting, 3+ days, no faces: delete, R4, My rule", (o = opt(ub, g1.rel, "delete")) != null && o.risk == Option.R4
                    && o.technique.equals("My rule: Old greetings") && o.saved == 50000 && o.exact);
            check("example 1: a greeting with faces is not matched", opt(ub, g2.rel, "delete") == null);
            check("example 1: faces never checked is not matched", opt(ub, g3.rel, "delete") == null);
            check("example 1: too new is not matched", opt(ub, g4.rel, "delete") == null || !opt(ub, g4.rel, "delete").technique.startsWith("My rule"));
            check("example 2: chat graphic older than 30 days", (o = opt(ub, x1.rel, "delete")) != null && o.technique.equals("My rule: Chat graphics"));
            check("example 2: younger, or not chat media: no", opt(ub, x2.rel, "delete") == null && opt(ub, x3.rel, "delete") == null);
            Option dark = opt(ub, "DCIM/Camera/c0.jpg", "delete");
            check("example 3: blurry and dark camera photo", dark != null && dark.technique.equals("My rule: Dark and blurry"));
            check("example 3: dark but sharp, or blurry but bright: no", opt(ub, "DCIM/Camera/c19.jpg", "delete") == null && opt(ub, "DCIM/Camera/c1.jpg", "delete") == null);
            check("example 4: faces > 0 keep: nothing happens to the picture, not even its exact duplicate", unit(ub, face2.rel) == null || unit(ub, face2.rel).options.isEmpty());
            check("example 4: the keeper of that duplicate group stays out too", unit(ub, face.rel) == null || unit(ub, face.rel).options.isEmpty());
        }

        // ---------------------------------------------------------------- limits on user rules
        {
            RuleSet rs = new RuleSet();
            Rule del = rs.addUser("Big docs", Action.DELETE);
            del.when(Tags.AREA, Op.EQ, "document");
            del.risk = Option.R0; del.technique = "Junk: thumbnails, temp, leftovers"; del.saving = Saving.CAMERA_PHOTO;      // a rule cannot lower its own tier
            Rule re = rs.addUser("Photo squeeze", Action.REENCODE_PHOTO);
            re.when(Tags.AREA, Op.EQ, "pictures").when(Tags.FILE_TYPE, Op.EQ, "jpeg");
            re.risk = Option.R0;
            Rule vid = rs.addUser("Music squeeze", Action.REENCODE_AUDIO);
            vid.when(Tags.AREA, Op.EQ, "download").when(Tags.FILE_TYPE, Op.EQ, "mp3");
            vid.saving = Saving.FILE_SIZE;                      // makes no sense for a re-encode: the usual saving is used
            FileItem d = file("Documents/a.pdf", "document", "pdf", 123456, 10);
            FileItem p = file("Pictures/b.jpg", "pictures", "jpeg", 1000000, 10); p.width = 100; p.height = 100;
            FileItem mp = file("Download/c.mp3", "download", "mp3", 4000000, 10); mp.bitrate = 320000;
            UnitBuilder ub = new UnitBuilder(scan(d, p, mp), new Estimates(), 34, rs);
            Option o = opt(ub, d.rel, "delete");
            check("user delete: always R4, whole file, exact, 'My rule: name'", o != null && o.risk == Option.R4 && o.saved == 123456 && o.exact && o.technique.equals("My rule: Big docs"));
            o = opt(ub, p.rel, "reencode_photo");
            check("user re-encode: always R2, an estimate, 'My rule: name'", o != null && o.risk == Option.R2 && !o.exact && o.technique.equals("My rule: Photo squeeze") && o.saved == 450000);
            o = opt(ub, mp.rel, "reencode_audio");
            check("user re-encode with a bad saving gets the usual one", o != null && o.risk == Option.R2 && !o.exact && o.saved == (long) (4000000 * (1 - 128000.0 / 320000)));
            check("user rules are named in the generic why", opt(ub, d.rel, "delete").why.contains("Big docs"));
            check("Techniques.info explains a user technique", Techniques.info("My rule: anything").what.length() > 0 && Techniques.info("My rule: anything").how.length() > 0
                    && Techniques.info(Techniques.JUNK).what.length() > 0 && Techniques.info("nothing").what.length() == 0);
            // a user delete next to a built-in delete: the lower risk stays (first on a tie)
            RuleSet rs2 = RuleSet.defaults();
            rs2.addUser("Thumbs too", Action.DELETE).when(Tags.AREA, Op.EQ, "thumbnails");
            FileItem th = file(".thumbnails/1", "thumbnails", null, 5000, 1);
            ub = new UnitBuilder(scan(th), new Estimates(), 34, rs2);
            check("user R4 delete does not replace a built-in R0 delete", opt(ub, th.rel, "delete").technique.equals(Techniques.JUNK) && unit(ub, th.rel).options.size() == 1);
            // a rule with no conditions would match everything: it never runs
            RuleSet rs3 = new RuleSet();
            rs3.addUser("Everything", Action.DELETE);
            ub = new UnitBuilder(scan(d), new Estimates(), 34, rs3);
            check("a rule without conditions does nothing", ub.units.isEmpty());
        }

        // ---------------------------------------------------------------- KEEP and the ID document rail
        {
            FileItem id = file("Pictures/aadhaar.jpg", "pictures", "jpeg", 900000, 400); id.category = PhotoCategory.ID; id.width = 1000; id.height = 700; id.sha256 = "s1";
            FileItem idCopy = file("Download/aadhaar.jpg", "download", "jpeg", 900000, 400); idCopy.category = PhotoCategory.ID; idCopy.width = 1000; idCopy.height = 700; idCopy.sha256 = "s1";
            FileItem plain = file("Pictures/p.jpg", "pictures", "jpeg", 900000, 400); plain.width = 1000; plain.height = 700;
            RuleSet rs = RuleSet.defaults();
            rs.addUser("Everything old", Action.DELETE).when(Tags.AGE_DAYS, Op.GT, "100");
            UnitBuilder ub = new UnitBuilder(scan(id, idCopy, plain), new Estimates(), 34, rs);
            Unit ui = unit(ub, id.rel), uc = unit(ub, idCopy.rel);
            check("ID document: the user's delete is not offered", ui == null || opt(ub, id.rel, "delete") == null);
            check("ID document: its exact copy may still go (the copy in a lower-priority area)", uc != null && opt(ub, idCopy.rel, "delete") != null
                    && opt(ub, idCopy.rel, "delete").technique.equals(Techniques.DUPLICATES));
            check("a file that is not an ID document gets the user's delete", opt(ub, plain.rel, "delete") != null && opt(ub, plain.rel, "delete").technique.startsWith("My rule"));
            // KEEP on a file that has built-in options removes them; a KEEP elsewhere changes nothing
            FileItem tmp = file("Download/t.tmp", "temp", null, 5000, 5);
            FileItem snap = file("Pictures/s.jpg", "pictures", "jpeg", 900000, 5); snap.width = 1000; snap.height = 700;
            RuleSet k = RuleSet.defaults();
            k.addUser("Keep temp", Action.KEEP).when(Tags.AREA, Op.EQ, "temp");
            ub = new UnitBuilder(scan(tmp, snap), new Estimates(), 34, k);
            check("KEEP removes the built-in options of matching files", unit(ub, tmp.rel) == null);
            check("KEEP leaves other files alone", opt(ub, snap.rel, "reencode_photo") != null);
            RuleSet k2 = RuleSet.defaults();                    // keep wins over a user delete too, whatever the order
            k2.addUser("Delete old", Action.DELETE).when(Tags.AGE_DAYS, Op.GT, "1");
            k2.addUser("Keep jpegs", Action.KEEP).when(Tags.FILE_TYPE, Op.EQ, "jpeg");
            ub = new UnitBuilder(scan(snap, plain), new Estimates(), 34, k2);
            check("KEEP beats every delete and re-encode rule", unit(ub, snap.rel) == null && unit(ub, plain.rel) == null);
        }

        // ---------------------------------------------------------------- switching off and editing built-in rules
        {
            FileItem tmp = file("Download/t.tmp", "temp", null, 5000, 5);
            FileItem gr = file("WhatsApp/Images/gr.jpg", "wa_image", "jpeg", 40000, 5); gr.category = PhotoCategory.GREETING;
            FileItem gr2 = file("WhatsApp/Images/gr2.jpg", "wa_image", "jpeg", 40000, 2); gr2.category = PhotoCategory.GREETING;
            Scanner.Result r = scan(tmp, gr, gr2);
            UnitBuilder ub = new UnitBuilder(r, new Estimates(), 34);
            check("default: stale temp file is junk, 5-day greeting is not yet one", opt(ub, tmp.rel, "delete") != null && opt(ub, gr.rel, "delete") == null);
            RuleSet rs = RuleSet.defaults();
            rs.find("stale_temp").enabled = false;
            ub = new UnitBuilder(r, new Estimates(), 34, rs);
            check("a disabled built-in no longer produces its option", unit(ub, tmp.rel) == null);
            rs = RuleSet.defaults();
            Rule g = rs.find("old_greeting");
            for (int i = 0; i < g.when.size(); i++) if (g.when.get(i).tag.equals(Tags.AGE_DAYS)) g.when.set(i, g.when.get(i).withValue("3"));
            ub = new UnitBuilder(r, new Estimates(), 34, rs);
            check("greetings 7 -> 3 days: the 5-day greeting is matched, the 2-day one is not", opt(ub, gr.rel, "delete") != null
                    && opt(ub, gr.rel, "delete").technique.equals(Techniques.GREETINGS) && opt(ub, gr2.rel, "delete") == null);
            check("the why text follows the file", opt(ub, gr.rel, "delete").why.equals("Greeting image received 5 days ago"));
            check("defaults() gives fresh rules each time (edits do not leak)", RuleSet.defaults().find("old_greeting").when.get(1).value.equals("7"));
            check("built-in rules: 26 with unique ids, every technique a known constant", builtInsOk());
        }

        // ---------------------------------------------------------------- text format
        {
            RuleSet rs = RuleSet.defaults();
            rs.find("old_greeting").when.set(1, rs.find("old_greeting").when.get(1).withValue("3"));
            rs.find("trash").enabled = false;
            Rule a = rs.addUser("Old greetings", Action.DELETE);
            a.when(Tags.SOURCE, Op.EQ, "chat image (received)").when(Tags.CATEGORY, Op.EQ, "greeting").when(Tags.AGE_DAYS, Op.GT, "3").whenNot(Tags.FACES, Op.GT, "0");
            a.why = "Greeting, {age} days old\nsecond line with a \\ backslash";
            Rule b = rs.addUser("Chat graphics, \"quoted\" and trailing  ", Action.DELETE);
            b.when(Tags.CHAT_MEDIA, Op.EQ, "yes").when(Tags.CATEGORY, Op.IN, "graphic, sticker").when(Tags.SCREENSHOT, Op.IS_NO, "");
            Rule c = rs.addUser("Squeeze", Action.REENCODE_VIDEO);
            c.saving = Saving.VIDEO; c.enabled = false;
            c.when(Tags.FILE_TYPE, Op.KNOWN, "").whenNot(Tags.DUPLICATE, Op.IS_YES, "");
            rs.addUser("People", Action.KEEP).when(Tags.FACES, Op.GT, "0");
            String text = rs.toText();
            RuleSet back = RuleSet.fromText(text);
            check("round trip is exact", back.toText().equals(text));
            check("round trip keeps the rules: count, order, edits, flags", back.rules.size() == rs.rules.size() && back.find("old_greeting").when.get(1).value.equals("3")
                    && !back.find("trash").enabled && back.find("user_1").name.equals("Old greetings") && back.find("user_1").why.equals(a.why)
                    && back.find("user_2").name.equals(b.name) && !back.find("user_3").enabled && back.find("user_4").action == Action.KEEP
                    && back.find("user_1").when.get(3).not && back.find("user_3").saving == Saving.VIDEO);
            check("a clean file has no warnings", back.warnings.isEmpty());
            check("the text starts with a version line", text.startsWith("storage-planner-rules 1\n"));
            // gives the same plan
            FileItem g = file("WhatsApp/Images/gr.jpg", "wa_image", "jpeg", 40000, 5); g.category = PhotoCategory.GREETING; g.faces = 0;
            Scanner.Result r = scan(g);
            UnitBuilder u1 = new UnitBuilder(r, new Estimates(), 34, rs), u2 = new UnitBuilder(r, new Estimates(), 34, back);
            check("a loaded rule set plans the same", u1.units.size() == u2.units.size() && u1.units.get(0).options.get(0).why.equals(u2.units.get(0).options.get(0).why));
            check("defaults round trip, and equal defaults", RuleSet.fromText(RuleSet.defaults().toText()).toText().equals(RuleSet.defaults().toText()));
            // robust loading
            String bad = "storage-planner-rules 1\n\nrule user_1\nname Mystery\nenabled yes\naction delete\nwhen shoe_size > 40\nwhen age_days > 3\nwhy x\nend\n"
                    + "\nrule user_2\nname Fine\nenabled yes\naction delete\nsaving file_size\nwhen age_days > 3\nfuture_key something\nend\n"
                    + "\nrule user_3\nname Odd action\nenabled yes\naction explode\nwhen age_days > 3\nend\n"
                    + "\nrule user_4\nname No operator\nenabled yes\naction delete\nwhen age_days around 3\nend\n"
                    + "\nrule user_5\nname Nothing\nenabled yes\naction delete\nend\n"
                    + "\nrule gone_in_this_version\nbuiltin yes\nenabled yes\nwhen area = x\nend\n"
                    + "\nrule old_greeting\nbuiltin yes\nenabled no\nwhen category = greeting\nwhen age_days > 9\nend\n"
                    + "\nrule user_6\nname cut off\n";
            RuleSet l = RuleSet.fromText(bad);
            check("unknown tag: the rule is loaded but off", l.find("user_1") != null && !l.find("user_1").enabled && l.find("user_1").problem() != null);
            check("a good rule with a key from the future loads on", l.find("user_2").enabled && l.find("user_2").problem() == null);
            check("unknown action, no operator, no conditions: loaded but off", !l.find("user_3").enabled && !l.find("user_4").enabled && !l.find("user_5").enabled);
            check("an unknown built-in is skipped, a known one takes the saved values", l.find("gone_in_this_version") == null && !l.find("old_greeting").enabled
                    && l.find("old_greeting").when.get(1).value.equals("9") && l.find("trash").enabled);
            check("all built-ins are there, in order, even if the text lacks them", l.rules.get(0).id.equals("junk_thumbnails") && l.rules.get(25).id.equals("reencode_music"));
            check("a text cut off inside a rule is reported, not fatal", l.find("user_6") == null && !l.warnings.isEmpty());
            check("the unknown action survives saving", l.toText().contains("action explode") && l.toText().contains("when shoe_size > 40") && l.toText().contains("when age_days around 3"));
            RuleSet again = RuleSet.fromText(l.toText());
            check("loading what was written after a bad load is stable", again.toText().equals(l.toText()));
            check("garbage, empty, a newer version: built-in rules and a warning, no crash", RuleSet.fromText("hello").rules.size() == 26 && !RuleSet.fromText("hello").warnings.isEmpty()
                    && RuleSet.fromText("").rules.size() == 26 && RuleSet.fromText(null).rules.size() == 26 && RuleSet.fromText("storage-planner-rules 99\nrule x\nend\n").rules.size() == 26
                    && !RuleSet.fromText("storage-planner-rules 99\n").warnings.isEmpty());
            Random rnd = new Random(5);                         // random damage never throws
            boolean ok = true;
            for (int i = 0; i < 300; i++) {
                StringBuilder sb = new StringBuilder(text);
                for (int k = 0; k < 8; k++) sb.setCharAt(rnd.nextInt(sb.length()), (char) (rnd.nextInt(95) + 32));
                try { RuleSet.fromText(sb.toString()).compile(); } catch (RuntimeException e) { ok = false; System.out.println(e); }
            }
            check("300 randomly damaged texts load without an exception", ok);
            // a user rule with the id of a built-in does not take its place
            RuleSet clash = RuleSet.fromText("storage-planner-rules 1\nrule trash\nname Imposter\nenabled yes\naction delete\nwhen age_days > 1\nend\n");
            check("a user rule with a built-in's id gets a new id", clash.find("trash").builtIn && clash.find("user_1") != null && clash.find("user_1").name.equals("Imposter"));
        }

        // ---------------------------------------------------------------- placeholders
        {
            FileItem f = file("Pictures/t.jpg", "pictures", "jpeg", 2500000, 20); f.catDate = NOW - 10 * DAY;
            Scanner.Result r = scan(f);
            Tags.Context ctx = Tags.context(r, new UnitBuilder.Detections());
            Tags.T tg = Tags.of(f, ctx, NOW);
            String s = RuleSet.fill("{name} is {age} days old, {size_mb} MB, {nope} {age", f, tg, ctx, new Estimates(), NOW);
            check("placeholders: known ones are filled, unknown and unfinished stay", s.equals("t.jpg is 20 days old, 2.5 MB, {nope} {age"));
        }

        // ---------------------------------------------------------------- speed
        {
            Random rnd = new Random(1);
            String[] areas = {"camera", "screenshot", "wa_image", "wa_sent", "download", "music", "movies", "pictures", "temp", "telegram_media"};
            List<FileItem> fs = new ArrayList<FileItem>();
            for (int i = 0; i < 50000; i++) {
                String a = areas[rnd.nextInt(areas.length)];
                FileItem f = file(a + "/f" + i + ".jpg", a, rnd.nextInt(5) == 0 ? "png" : "jpeg", 100000 + rnd.nextInt(5000000), rnd.nextInt(500));
                f.width = 4000; f.height = 3000;
                if (a.equals("camera")) { f.sharp = rnd.nextDouble() * 100; f.hasPhash = rnd.nextInt(3) == 0; f.phash = rnd.nextLong(); }
                if (rnd.nextInt(3) == 0) f.category = PhotoCategory.ALL[rnd.nextInt(PhotoCategory.ALL.length)];
                fs.add(f);
            }
            RuleSet rs = RuleSet.defaults();
            for (int i = 0; i < 4; i++) rs.addUser("U" + i, Action.DELETE).when(Tags.AGE_DAYS, Op.GT, "300").when(Tags.SOURCE, Op.EQ, "screenshot").whenNot(Tags.FACES, Op.GT, "0");
            Scanner.Result r = scan(fs.toArray(new FileItem[0]));
            new UnitBuilder(r, new Estimates(), 34, rs);        // warm up
            long t0 = System.nanoTime();
            UnitBuilder withRules = new UnitBuilder(r, new Estimates(), 34, rs);
            long t1 = System.nanoTime();
            LegacyUnitBuilder old = new LegacyUnitBuilder(r, new Estimates(), 34);
            long t2 = System.nanoTime();
            double ms = (t1 - t0) / 1e6, msOld = (t2 - t1) / 1e6;
            System.out.printf("  speed: 50000 files, 26 built-in + 4 user rules: whole UnitBuilder %.0f ms (old code %.0f ms)%n", ms, msOld);
            check("50000 files with 30 rules build in well under a second", ms < 1000 && withRules.units.size() > 0 && old.units.size() > 0);
        }

        fileTypes();
        System.out.println((checks - fails) + "/" + checks + " rule checks pass");
        if (fails > 0) System.exit(1);
    }

    static boolean builtInsOk() {
        List<Rule> l = BuiltInRules.all();
        java.util.Set<String> ids = new java.util.HashSet<String>();
        java.util.Set<String> tech = new java.util.HashSet<String>();
        for (java.lang.reflect.Field f : Techniques.class.getFields()) try { tech.add((String) f.get(null)); } catch (Exception e) { return false; }
        for (Rule r : l) {
            if (!ids.add(r.id) || !r.builtIn || r.problem() != null || !tech.contains(r.technique) || r.name == null || r.name.length() == 0) return false;
        }
        return l.size() == 26;
    }

    // ---------------------------------------------------------------- file types: a rule is for one type of file
    static Tags.T tags(FileItem f) { Scanner.Result r = scan(f); return Tags.of(f, Tags.context(r, new UnitBuilder.Detections()), NOW); }

    static void fileTypes() {
        FileItem jpg = file("DCIM/Camera/a.jpg", "camera", "jpeg", 4000000, 400), png = file("Pictures/Screenshots/b.png", "screenshot", "png", 900000, 400);
        FileItem mp3 = file("Music/c.mp3", "music", "mp3", 8000000, 400), mp4 = file("DCIM/Camera/d.mp4", "camera", "mp4", 90000000, 400);
        mp3.bitrate = 320000; jpg.bitrate = 320000;                          // a bitrate on a JPEG never happens; here it tests the guard
        Tags.T tj = tags(jpg), tp = tags(png), ta = tags(mp3), tv = tags(mp4);

        Rule music = Rule.user("Old music", Action.REENCODE_AUDIO).when(Tags.AGE_DAYS, Op.GT, "30");
        check("file type: a compress-music rule for any file never matches a JPEG (re-encodes stay on their own kind)", !music.matches(tj) && music.matches(ta));
        Rule photo = Rule.user("Old photos", Action.REENCODE_PHOTO).when(Tags.AGE_DAYS, Op.GT, "30");
        check("file type: a compress-photo rule never matches music or a video", photo.matches(tj) && photo.matches(tp) && !photo.matches(ta) && !photo.matches(tv));
        Rule del = Rule.user("Old JPEGs", Action.DELETE).when(Tags.AGE_DAYS, Op.GT, "30");
        check("file type: any file by default", Rule.ANY_FILE.equals(del.fileType) && del.matches(tj) && del.matches(ta) && del.matches(tv));
        del.fileType = "jpeg";
        check("file type jpeg: only JPEGs", del.matches(tj) && !del.matches(tp) && !del.matches(ta) && !del.matches(tv));
        del.fileType = Tags.IMAGE;
        check("file type pictures: JPEG and PNG, not music or video", del.matches(tj) && del.matches(tp) && !del.matches(ta) && !del.matches(tv));
        del.fileType = Tags.AUDIO;
        check("file type music: only music", !del.matches(tj) && del.matches(ta));

        RuleSet set = RuleSet.defaults();
        Rule bad = Rule.user("Squash photos as music", Action.REENCODE_AUDIO).when(Tags.AGE_DAYS, Op.GT, "30");
        bad.fileType = "jpeg";
        check("file type: compress music for JPEGs cannot be saved", !set.problems(bad).isEmpty() && bad.problem() != null
                && set.problems(bad).toString().contains("only works on music"));
        bad.fileType = "mp3";
        check("file type: compress music for MP3 can", set.problems(bad).isEmpty() && bad.problem() == null);
        bad.fileType = "gif";
        check("file type: an unknown file type cannot be saved", !set.problems(bad).isEmpty() && bad.problem() != null);

        Rule u = set.addUser("Old PNGs", Action.DELETE).when(Tags.AGE_DAYS, Op.GT, "30");
        u.fileType = "png";
        RuleSet back = RuleSet.fromText(set.toText());
        Rule u2 = null; for (Rule r : back.rules) if ("Old PNGs".equals(r.name)) u2 = r;
        check("file type: saved and loaded", u2 != null && "png".equals(u2.fileType) && u2.enabled && back.warnings.isEmpty());
        String old = set.toText().replace("filetype png\n", "");
        Rule u3 = null; for (Rule r : RuleSet.fromText(old).rules) if ("Old PNGs".equals(r.name)) u3 = r;
        check("file type: a rule saved before file types existed is for any file", u3 != null && Rule.ANY_FILE.equals(u3.fileType) && u3.enabled);
        String odd = set.toText().replace("filetype png", "filetype gif");
        RuleSet oddSet = RuleSet.fromText(odd);
        Rule u4 = null; for (Rule r : oddSet.rules) if ("Old PNGs".equals(r.name)) u4 = r;
        check("file type: an unknown file type loads switched off with a warning", u4 != null && !u4.enabled && !oddSet.warnings.isEmpty());
        check("file type: built-in rules are saved without one (it comes with the app)", !set.toText().contains("filetype jpeg"));

        String[] want = {"reencode_camera_photo", "jpeg", "lossless_screenshot", "png", "reencode_video", Tags.VIDEO, "reencode_music", Tags.AUDIO,
                "accidental_shot", Tags.IMAGE, "installer_installed", Tags.APK, "exact_duplicate", Rule.ANY_FILE, "trash", Rule.ANY_FILE};
        boolean ok = true;
        for (int i = 0; i < want.length; i += 2) ok &= want[i + 1].equals(set.find(want[i]).fileType);
        check("file type: built-in rules have theirs", ok);
        boolean allOk = true;
        for (Rule r : BuiltInRules.all()) allOk &= r.problem() == null;
        check("file type: every built-in rule's file type fits its action", allOk);
        check("file type: words", RuleText.fileType("jpeg").equals("JPEG pictures") && RuleText.fileType(Tags.AUDIO).equals("Music and audio (any format)")
                && RuleText.appliesTo(music).equals("For music and audio files (compress music works on nothing else)") && RuleText.appliesTo(u).equals("For PNG pictures only"));
        Rule copy = u.copy();
        check("file type: copied", "png".equals(copy.fileType));
    }
}
