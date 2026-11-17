package com.storageplanner.test;

import com.storageplanner.core.*;
import com.storageplanner.core.Rule.Action;
import com.storageplanner.core.Rule.Condition;
import com.storageplanner.core.Rule.Op;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * What the rules screen and the rule editor lean on: the tag listing, rules in words, matches() (same engine and rails as the
 * planner), tally(), validation of a rule before it is saved, editing a rule set, and what happens to the user's decisions
 * when the rules change under them.
 */
public final class RuleMatchTest {
    static int checks, fails;
    static final long NOW = 1792000000000L, DAY = 86400000L;

    static void check(String what, boolean ok) {
        checks++;
        if (!ok) { fails++; System.out.println("FAIL " + what); }
    }

    static Rule user(RuleSet rs, String name, Action a, String... lines) {
        Rule r = Rule.user(name, a);
        for (String l : lines) r.when.add(Condition.parse(l));
        return r;
    }

    /** Files one rule alone would act on, by running the planner's own engine file by file. */
    static int engineCount(Rule r, Scanner.Result res, Tags.Context ctx, Estimates est) {
        RuleSet only = new RuleSet();
        Rule c = r.copy(); c.enabled = true; c.id = "x";
        only.rules.add(c);
        RuleSet.Compiled comp = only.compile();
        int n = 0;
        for (FileItem f : res.files) {
            Unit u = Unit.forFile(f);
            comp.apply(u, f, Tags.of(f, ctx, NOW), ctx, est, NOW);
            if (r.action == Action.KEEP ? c.matches(Tags.of(f, ctx, NOW)) && f.size > 0 : !u.options.isEmpty()) n++;
        }
        return n;
    }

    public static void main(String[] args) {
        // ---------------------------------------------------------------- the tag listing
        {
            List<Tags.Info> l = Tags.list();
            Set<String> ids = new HashSet<String>();
            boolean all = true, labels = true, types = true, words = true;
            for (Tags.Entry e : new Tags.T().all()) { Tags.Info i = Tags.info(e.id); if (i == null) all = false; else { ids.add(i.id); labels &= i.label.equals(e.label); } }
            for (Tags.Info i : l) { types &= i.type == Tags.typeOf(i.id); words &= i.phrase.length() > 0 && i.unit != null && i.label.length() > 0; }
            check("the listing has every tag, in the order of T.all()", all && l.size() == new Tags.T().all().size() && ids.size() == l.size());
            check("the listing has the same labels and types as the tags", labels && types && words);
            check("flag phrases can be turned around", flagPhrasesOk());
            check("no such tag has no info", Tags.info("nope") == null && Tags.info(null) == null);
            check("categories offered are all the picture categories, by id, with their names", Tags.choices(Tags.CATEGORY).size() == PhotoCategory.ALL.length
                    && Tags.choices(Tags.CATEGORY).get(0).value.equals(PhotoCategory.ALL[0]) && Tags.choices(Tags.CATEGORY).get(0).label.equals(PhotoCategory.label(PhotoCategory.ALL[0])));
            check("kinds, file types, areas, sources, text status are offered; free-value tags are not", Tags.choices(Tags.KIND).size() == 6 && !Tags.choices(Tags.FILE_TYPE).isEmpty()
                    && !Tags.choices(Tags.AREA).isEmpty() && Tags.choices(Tags.SOURCE).size() > 20 && Tags.choices(Tags.TEXT_READ).size() == 3
                    && Tags.choices(Tags.VIDEO_CODEC).isEmpty() && Tags.choices(Tags.AGE_DAYS).isEmpty() && Tags.choices(null).isEmpty());
            boolean srcOk = true;                                     // a source value offered must be one a file can have
            Set<String> have = new HashSet<String>();
            for (Tags.Choice c : Tags.choices(Tags.SOURCE)) have.add(c.value);
            for (String area : new String[] {"camera", "wa_image", "wa_sent", "download", "telegram_media", "user_other"}) {
                FileItem f = new FileItem(area + "/a.jpg", "/x", 10, NOW); f.area = area; f.type = "jpeg";
                srcOk &= have.contains(Tags.source(f));
            }
            FileItem cv = new FileItem("c/a.mp4", "/x", 10, NOW); cv.area = "camera"; cv.type = "mp4";
            check("source choices cover what files report", srcOk && have.contains(Tags.source(cv)));
            check("a value shows as its name", Tags.valueLabel(Tags.CATEGORY, "greeting").equals(PhotoCategory.label("greeting")) && Tags.valueLabel(Tags.AREA, "wa_image").equals("chat image (received)")
                    && Tags.valueLabel(Tags.KIND, "image").equals("image"));
            Random g = new Random(2);
            Scanner.Result r = RulesEquivalenceTest.scan(g);
            Tags.Context ctx = Tags.context(r, new UnitBuilder(r, new Estimates(), 34).det);
            List<String> kinds = Tags.occurring(Tags.KIND, r, ctx, NOW), cats = Tags.occurring(Tags.CATEGORY, r, ctx, NOW);
            check("values that occur in a scan are listed once, sorted", kinds.contains("image") && cats.size() > 1 && new HashSet<String>(cats).size() == cats.size()
                    && cats.equals(sorted(cats)) && Tags.occurring(Tags.AGE_DAYS, r, ctx, NOW).isEmpty());
        }

        // ---------------------------------------------------------------- rules in words
        {
            check("age", RuleText.condition(Condition.parse("age_days > 7")).equals("age more than 7 days"));
            check("numbers lose a pointless .0", RuleText.condition(Condition.parse("age_days >= 7.0")).equals("age at least 7 days") && RuleText.condition(Condition.parse("megapixels < 0.5")).contains("0.5 MP"));
            check("NOT on a number says the opposite, which is the same for known values and false for unknown ones",
                    RuleText.condition(Condition.parse("NOT faces > 0")).equals("number of faces at most 0") && RuleText.condition(Condition.parse("NOT age_days = 3")).equals("age is not 3 days"));
            check("yes/no tags read as a clause", RuleText.condition(Condition.parse("screenshot is yes")).equals("is a screenshot") && RuleText.condition(Condition.parse("screenshot is no")).equals("is not a screenshot")
                    && RuleText.condition(Condition.parse("NOT chat_media is yes")).equals("is not chat or Telegram media") && RuleText.condition(Condition.parse("NOT has_code is no")).equals("has a QR code or barcode")
                    && RuleText.condition(Condition.parse("eyes_closed is no")).equals("does not have someone with eyes closed"));
            check("yes/no with = and !=", RuleText.condition(Condition.parse("screenshot = yes")).equals("is a screenshot") && RuleText.condition(Condition.parse("screenshot != yes")).equals("is not a screenshot"));
            check("text values use names", RuleText.condition(Condition.parse("category = greeting")).equals("picture category is " + PhotoCategory.label("greeting"))
                    && RuleText.condition(Condition.parse("area in wa_image,wa_sent")).equals("storage area is one of chat image (received), chat image (sent)")
                    && RuleText.condition(Condition.parse("NOT kind = image")).equals("kind is not image") && RuleText.condition(Condition.parse("NOT area in temp,trash")).contains("is none of"));
            check("is known", RuleText.condition(Condition.parse("faces is known")).equals("number of faces is known") && RuleText.condition(Condition.parse("NOT faces is known")).contains("never matches"));
            check("a rule is one sentence", RuleText.conditions(find("old_greeting")).equals("Picture category is " + PhotoCategory.label("greeting") + ", age more than 7 days and is chat or Telegram media"));
            check("an empty rule says so", RuleText.conditions(new Rule()).equals("No conditions yet"));
            boolean ok = true;
            for (Rule b : BuiltInRules.all()) { String s = RuleText.conditions(b); ok &= !s.contains("unknown") && !s.contains("null") && s.length() > 5 && RuleText.action(b).length() > 0 && RuleText.actionSentence(b).length() > 10; }
            check("every built-in rule can be read aloud", ok);
            check("an unknown tag is said, not hidden", RuleText.condition(Condition.parse("shoe > 3")).contains("unknown"));
            check("actions in words", RuleText.action(Action.DELETE).equals("Delete") && RuleText.action(Action.KEEP).startsWith("Keep") && RuleText.action(Action.LOSSLESS_IMAGE).contains("lossless"));
            Rule del = Rule.user("x", Action.DELETE), re = Rule.user("y", Action.REENCODE_PHOTO), keep = Rule.user("z", Action.KEEP);
            check("risk of a user rule", del.effectiveRisk() == Option.R4 && re.effectiveRisk() == Option.R2 && keep.effectiveRisk() == -1 && find("junk_thumbnails").effectiveRisk() == Option.R0
                    && RuleText.actionSentence(del).contains("confirmation") && RuleText.actionSentence(keep).contains("No risk"));
        }

        // ---------------------------------------------------------------- matches: same engine, same rails
        {
            boolean same = true, ids = true, tallyOk = true, listOk = true;
            int checked = 0, nonEmpty = 0;
            for (int seed = 0; seed < 40; seed++) {
                Scanner.Result r = RulesEquivalenceTest.scan(new Random(seed));
                Estimates est = new Estimates();
                UnitBuilder ub = new UnitBuilder(r, est, 34);
                Tags.Context ctx = Tags.context(r, ub.det);
                RuleSet rs = RuleSet.defaults();
                List<Rule> candidates = new ArrayList<Rule>();
                candidates.add(user(rs, "a", Action.DELETE, "category = greeting", "age_days > 7", "chat_media is yes"));
                candidates.add(user(rs, "b", Action.DELETE, "age_days > 30", "NOT faces > 0"));
                candidates.add(user(rs, "c", Action.DELETE, "kind = image", "category is known"));          // meets ID documents
                candidates.add(user(rs, "d", Action.REENCODE_PHOTO, "file_type = jpeg", "size_mb > 1"));
                candidates.add(user(rs, "e", Action.REENCODE_VIDEO, "kind = video", "bitrate_kbps > 100"));
                candidates.add(user(rs, "f", Action.LOSSLESS_IMAGE, "file_type = png"));
                candidates.add(user(rs, "g", Action.REENCODE_AUDIO, "kind = audio"));
                candidates.add(user(rs, "h", Action.KEEP, "faces > 0"));
                for (Rule c : candidates) {
                    RuleSet.Matches m = rs.matches(c, r, ctx, est, NOW, 30);
                    int engine = engineCount(c, r, ctx, est);
                    same &= m.count == engine;
                    checked++; if (m.count > 0) nonEmpty++;
                    listOk &= m.files.size() == Math.min(30, m.count) && m.total == r.files.size();
                    for (int i = 1; i < m.files.size(); i++) listOk &= m.files.get(i - 1).size >= m.files.get(i).size;
                    if (c.action == Action.DELETE) for (FileItem f : m.files) ids &= !PhotoCategory.ID.equals(f.category);
                    if (c.action == Action.DELETE) {
                        int idHit = 0;
                        for (FileItem f : r.files) if (PhotoCategory.ID.equals(f.category) && c.matches(Tags.of(f, ctx, NOW))) idHit++;
                        ids &= m.idProtected == idHit;
                    }
                    if (c.action == Action.DELETE) { long b = 0; for (FileItem f : r.files) if (!PhotoCategory.ID.equals(f.category) && c.matches(Tags.of(f, ctx, NOW)) && f.size > 0) b += f.size; same &= m.bytes == b && m.saved == b; }
                }
                // tally agrees with matches, rule by rule, for the built-ins and for the user's rules
                RuleSet withUser = rs.copy();
                for (Rule c : candidates) { c.id = null; withUser.put(c); }
                Map<String, RuleSet.Matches> tally = withUser.tally(r, ctx, est, NOW);
                for (Rule x : withUser.rules) {
                    RuleSet.Matches m = withUser.matches(x, r, ctx, est, NOW, 0), t = tally.get(x.id);
                    tallyOk &= t != null && t.count == m.count && t.bytes == m.bytes && t.saved == m.saved && t.idProtected == m.idProtected;
                }
            }
            check("matches() counts exactly the files the planner's engine acts on (" + checked + " rules over 40 scans)", same && checked > 100 && nonEmpty > 50);
            check("a delete rule never lists or counts an ID document, and says how many it left out", ids);
            check("the list is the largest first and at most the limit", listOk);
            check("tally() gives every rule the same numbers as matches()", tallyOk);
        }
        {
            // a small hand-made scan: the numbers are known
            Scanner.Result r = new Scanner.Result();
            r.now = NOW; r.total = 100L << 30; r.free = 10L << 30;
            for (int i = 0; i < 10; i++) { FileItem f = new FileItem("g" + i + ".jpg", "/x/g" + i, 1000000, NOW - 20 * DAY); f.area = "wa_image"; f.type = "jpeg"; f.category = PhotoCategory.GREETING; f.width = 1000; f.height = 1000; r.files.add(f); }
            FileItem idDoc = new FileItem("id.jpg", "/x/id", 1000000, NOW - 20 * DAY); idDoc.area = "wa_image"; idDoc.type = "jpeg"; idDoc.category = PhotoCategory.ID; r.files.add(idDoc);
            for (int i = 0; i < 29; i++) { FileItem f = new FileItem("o" + i + ".mp4", "/x/o" + i, 500000, NOW - 2 * DAY); f.area = "movies"; f.type = "mp4"; r.files.add(f); }
            FileItem dupA = new FileItem("a.jpg", "/x/a", 2000000, NOW - 1 * DAY); dupA.area = "camera"; dupA.type = "jpeg"; dupA.sha256 = "s"; dupA.width = 1000; dupA.height = 1000;
            FileItem dupB = new FileItem("z/b.jpg", "/x/b", 2000000, NOW - 1 * DAY); dupB.area = "pictures"; dupB.type = "jpeg"; dupB.sha256 = "s"; dupB.width = 1000; dupB.height = 1000;
            r.files.add(dupA); r.files.add(dupB);
            Estimates est = new Estimates();
            UnitBuilder ub = new UnitBuilder(r, est, 34);
            Tags.Context ctx = Tags.context(r, ub.det);
            RuleSet rs = RuleSet.defaults();
            Rule g = user(rs, "Chat pictures now", Action.DELETE, "area = wa_image", "age_days > 3");
            RuleSet.Matches m = rs.matches(g, r, ctx, est, NOW, 5);
            check("hand-made: 10 chat pictures, the ID document left out", m.count == 10 && m.bytes == 10000000L && m.saved == 10000000L && m.idProtected == 1 && m.files.size() == 5 && m.total == 42);
            check("hand-made: the built-in rule already suggests them (age > 7)", m.alsoSuggested == 10);
            Rule g30 = user(rs, "Greetings later", Action.DELETE, "category = greeting", "age_days > 30");
            check("hand-made: nothing matches, nothing is already suggested", rs.matches(g30, r, ctx, est, NOW, 5).count == 0 && rs.matches(g30, r, ctx, est, NOW, 5).alsoSuggested == 0);
            Rule all = user(rs, "All videos", Action.DELETE, "kind = video", "age_days > 0");
            RuleSet.Matches mv = rs.matches(all, r, ctx, est, NOW, 5);
            check("hand-made: 29 of 42 files is broad (more than a quarter), 10 of 42 is not", mv.count == 29 && mv.broad(all) && !m.broad(g) && mv.alsoSuggested == 0);
            Rule k = user(rs, "Keep greetings", Action.KEEP, "category = greeting");
            RuleSet.Matches mk = rs.matches(k, r, ctx, est, NOW, 5);
            check("hand-made: a keep rule counts what it protects and the suggestions it would remove", mk.count == 10 && mk.alsoSuggested == 10);
            Rule comp = user(rs, "Compress my jpegs", Action.REENCODE_PHOTO, "file_type = jpeg", "size_mb > 1.5");
            RuleSet.Matches mc = rs.matches(comp, r, ctx, est, NOW, 5);
            check("hand-made: a re-encode counts an estimate, not the whole size", mc.count == 2 && mc.bytes == 4000000L && mc.saved == (long) (2000000 * est.cameraPhoto) * 2 && mc.saved < mc.bytes);
            check("hand-made: a built-in re-encode rule claims both photos first", mc.overridden == 2);
            check("a rule that cannot run matches nothing", rs.matches(new Rule(), r, ctx, est, NOW, 5).count == 0 && rs.matches(user(rs, "z", Action.DELETE, "age_days > abc"), r, ctx, est, NOW, 5).count == 0);
            RuleSet ed = rs.copy(); Rule own = user(ed, "Own", Action.DELETE, "category = greeting", "age_days > 3"); ed.put(own);
            check("editing a rule of the set does not count the rule against itself", ed.matches(own, r, ctx, est, NOW, 5).alsoSuggested == 10 && ed.matches(own, r, ctx, est, NOW, 5).count == 10);
            // a user delete rule in the set leaves the same files suggested by it that preview said
            UnitBuilder with = new UnitBuilder(r, est, 34, ed);
            int mine = 0; for (Unit u : with.units) for (Option o : u.options) if (o.technique.equals("My rule: Own")) mine++;
            check("what the planner makes of the rule is no more than the preview said", mine <= 10);
        }

        // ---------------------------------------------------------------- checking a rule before it is saved
        {
            RuleSet rs = RuleSet.defaults();
            Rule a = rs.addUser("Old chat pictures", Action.DELETE); a.when.add(Condition.parse("age_days > 30"));
            check("a good rule has no problems", rs.problems(a).isEmpty());
            Rule b = Rule.user("old chat pictures", Action.DELETE); b.when.add(Condition.parse("age_days > 10"));
            check("the same name again (any case) is refused", !rs.problems(b).isEmpty() && rs.problems(b).get(0).contains("name"));
            a.name = "  Old chat pictures  ";
            check("a rule may keep its own name", rs.problems(a).isEmpty());
            Rule c = Rule.user("   ", Action.DELETE); c.when.add(Condition.parse("age_days > 10"));
            check("no name is refused", !rs.problems(c).isEmpty());
            StringBuilder longName = new StringBuilder(); for (int i = 0; i < 61; i++) longName.append('x');
            check("a very long name is refused", !rs.problems(Rule.user(longName.toString(), Action.KEEP).when("faces", Op.GT, "0")).isEmpty());
            check("no conditions is refused", !rs.problems(Rule.user("n", Action.KEEP)).isEmpty());
            check("a condition that does not make sense is refused, in words", rs.problems(Rule.user("n", Action.KEEP).when("age_days", Op.GT, "abc")).get(0).contains("not a number")
                    && !rs.problems(Rule.user("n", Action.KEEP).when("age_days", Op.GT, "")).isEmpty());
            check("delete everything of one kind is refused", !rs.problems(Rule.user("pics", Action.DELETE).when("kind", Op.EQ, "image")).isEmpty()
                    && !rs.problems(Rule.user("pics", Action.DELETE).when("kind", Op.EQ, "image").when("file_type", Op.EQ, "jpeg")).isEmpty()
                    && rs.problems(Rule.user("pics", Action.DELETE).when("kind", Op.EQ, "image").when("age_days", Op.GT, "900")).isEmpty());
            check("a keep or re-encode rule on the kind alone is fine", rs.problems(Rule.user("v", Action.REENCODE_VIDEO).when("kind", Op.EQ, "video")).isEmpty()
                    && rs.problems(Rule.user("k", Action.KEEP).when("kind", Op.EQ, "document")).isEmpty());
            check("a built-in with a changed number is fine, with a broken number is not", rs.problems(withValue(rs.find("old_greeting"), 1, "3")).isEmpty() && !rs.problems(withValue(rs.find("old_greeting"), 1, "x")).isEmpty());
            Rule noAction = new Rule(); noAction.action = null; noAction.name = "q";
            check("no action is refused", !rs.problems(noAction).isEmpty());
        }

        // ---------------------------------------------------------------- editing a rule set
        {
            RuleSet rs = RuleSet.defaults();
            RuleSet cp = rs.copy();
            cp.find("trash").enabled = false;
            cp.find("old_greeting").when.set(1, Condition.parse("age_days > 3"));
            check("a copy can be changed without changing the original", rs.find("trash").enabled && rs.find("old_greeting").when.get(1).value.equals("7"));
            check("changed() sees a switched-off or edited built-in, and a reset undoes it", RuleSet.changed(cp.find("trash")) && RuleSet.changed(cp.find("old_greeting")) && !RuleSet.changed(rs.find("trash"))
                    && !RuleSet.changed(cp.find("junk_thumbnails")) && cp.reset("trash") && cp.reset("old_greeting") && cp.toText().equals(rs.toText()));
            Rule u1 = cp.addUser("One", Action.DELETE); u1.when.add(Condition.parse("age_days > 3"));
            Rule edit = u1.copy(); edit.name = "Renamed"; edit.technique = Rule.USER_PREFIX + "Renamed"; edit.when.add(Condition.parse("NOT faces > 0"));
            cp.put(edit);
            check("put() replaces by id, in place", cp.rules.size() == 27 && cp.find(u1.id).name.equals("Renamed") && cp.find(u1.id).when.size() == 2 && cp.rules.get(26).id.equals(u1.id));
            Rule fresh = Rule.user("Two", Action.KEEP); fresh.when.add(Condition.parse("faces > 0"));
            cp.put(fresh);
            check("put() gives a new user rule an id that is free", fresh.id != null && fresh.id.startsWith("user_") && !fresh.id.equals(u1.id) && cp.rules.size() == 28);
            check("remove() removes a user rule and never a built-in", cp.remove(u1.id) && cp.find(u1.id) == null && !cp.remove("trash") && cp.find("trash") != null && cp.rules.size() == 27);
            check("reset() of an unknown or user rule does nothing", !cp.reset("nope") && !cp.reset(fresh.id));
            RuleSet back = RuleSet.fromText(cp.toText());
            check("the edited set survives saving and loading", back.toText().equals(cp.toText()) && back.warnings.isEmpty() && back.find(fresh.id).name.equals("Two"));
        }

        // ---------------------------------------------------------------- the user's decisions when the rules change
        {
            Scanner.Result r = RulesEquivalenceTest.scan(new Random(7));
            Estimates est = new Estimates();
            RuleSet withMine = RuleSet.defaults();
            Rule mine = withMine.addUser("Mine", Action.DELETE); mine.when.add(Condition.parse("age_days > 5")); mine.when.add(Condition.parse("NOT area = camera"));
            UnitBuilder before = new UnitBuilder(r, est, 34, withMine);
            Set<String> kept = new HashSet<String>(); Map<String, String> accepted = new HashMap<String, String>();
            int n = 0;
            for (Unit u : before.units) {
                for (Option o : u.options) if (o.technique.equals("My rule: Mine") && n++ < 25) accepted.put(u.id, o.technique);
                if (!u.options.isEmpty() && n % 7 == 3) kept.add(u.id);
            }
            long need = (r.total - r.free) / 4;
            Planner.Plan p1 = Review.planWithDecisions(before.units, kept, accepted, need, 0);
            check("decisions made under the user's rule were forced into the plan", n > 5 && !p1.chosen.isEmpty());
            withMine.find(mine.id).enabled = false;
            UnitBuilder after = new UnitBuilder(r, est, 34, withMine);
            boolean ok = true; String err = "";
            Planner.Plan p2 = null, p3 = null;
            try {
                p2 = Review.planWithDecisions(after.units, kept, accepted, need, 0);
                p3 = Review.planWithDecisions(after.units, kept, accepted, need, 1);
            } catch (RuntimeException e) { ok = false; err = e.toString(); }
            check("a decision for a technique that is gone does not crash the planner " + err, ok);
            boolean none = ok;
            if (ok) for (Map.Entry<Unit, Option> e : p2.chosen.entrySet()) none &= !e.getValue().technique.equals("My rule: Mine");
            if (ok) for (String id : kept) for (Map.Entry<Unit, Option> e : p2.chosen.entrySet()) none &= !e.getKey().id.equals(id);
            check("the dead decisions are ignored and the kept ones still kept", none);
            boolean orphan = true;
            for (Map.Entry<String, String> e : accepted.entrySet())
                for (Unit u : after.units) if (u.id.equals(e.getKey()) && Review.optionFor(u, e.getValue()) != null) orphan = false;
            check("optionFor says there is no option for a technique that is gone", orphan);
            withMine.find(mine.id).enabled = true;
            UnitBuilder again = new UnitBuilder(r, est, 34, withMine);
            Planner.Plan p4 = Review.planWithDecisions(again.units, kept, accepted, need, 0);
            check("turning the rule back on brings the decisions back (they were never deleted)", p4.saved() == p1.saved() && p4.chosen.size() == p1.chosen.size());
            check("the plan with no user rules is the plan of the defaults", sameSummary(new UnitBuilder(r, est, 34, RuleSet.fromText(RuleSet.defaults().toText())), new UnitBuilder(r, est, 34)));
        }

        System.out.println((checks - fails) + "/" + checks + " rule-match checks pass");
        if (fails > 0) System.exit(1);
    }

    static boolean sameSummary(UnitBuilder a, UnitBuilder b) { return RulesEquivalenceTest.summary(a.units).equals(RulesEquivalenceTest.summary(b.units)); }

    static Rule find(String id) { for (Rule r : BuiltInRules.all()) if (r.id.equals(id)) return r; return null; }

    static Rule withValue(Rule r, int i, String v) { Rule c = r.copy(); c.when.set(i, c.when.get(i).withValue(v)); return c; }

    static List<String> sorted(List<String> l) { List<String> c = new ArrayList<String>(l); java.util.Collections.sort(c); return c; }

    static boolean flagPhrasesOk() {
        for (Tags.Info i : Tags.list()) if (i.type == Tags.Type.FLAG && !(i.phrase.startsWith("is ") || i.phrase.startsWith("has "))) return false;
        return true;
    }
}
