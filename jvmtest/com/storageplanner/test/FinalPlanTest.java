package com.storageplanner.test;

import com.storageplanner.core.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * The final plan (the "Your plan" screens): each file's suggested action from the rules (delete before compress before
 * keep), your choices winning over it, earlier review decisions as choices, compress only where possible, totals per
 * type and action, the tabs (unticked files stay in their tab), groups by reason, and saving the choices. Invented data.
 */
public final class FinalPlanTest {
    static int checks, fails;
    static final long NOW = 1795000000000L, DAY = 86400000L;

    static void check(String what, boolean ok) {
        checks++;
        if (!ok) { fails++; System.out.println("FAIL " + what); }
    }

    static FileItem file(String rel, String area, String type, long size, double ageDays) {
        FileItem f = new FileItem(rel, "/sd/" + rel, size, NOW - (long) (ageDays * DAY));
        f.area = area; f.type = type; f.width = 4000; f.height = 3000;
        return f;
    }

    static FinalPlan.Entry find(List<FinalPlan.Entry> l, FileItem f) { for (FinalPlan.Entry e : l) if (e.file == f) return e; return null; }

    public static void main(String[] a) {
        // a small phone: duplicates, an old meme, a camera photo to re-encode, a video, an installer, a text file, trash
        FileItem p1 = file("DCIM/Camera/IMG_1.jpg", "camera", "jpeg", 4000000, 30);
        FileItem p1b = file("DCIM/Camera/IMG_1 (1).jpg", "camera", "jpeg", 4000000, 30);
        p1.sha256 = p1b.sha256 = "same";
        FileItem meme = file("WhatsApp/Media/WhatsApp Images/IMG-20261101-WA0001.jpg", "wa_image", "jpeg", 300000, 20);
        meme.category = PhotoCategory.GRAPHIC; meme.faces = 0; meme.sortVersion = 6;
        FileItem cam = file("DCIM/Camera/IMG_2.jpg", "camera", "jpeg", 5000000, 10);
        FileItem vid = file("DCIM/Camera/VID_1.mp4", "camera", "mp4", 90000000, 10);
        vid.vcodec = "video/avc"; vid.bitrate = 20000000; vid.width = 1920; vid.height = 1080; vid.durationMs = 30000;
        FileItem apk = file("Download/old.apk", "download", "zip", 30000000, 400);
        FileItem doc = file("Documents/notes.txt", "document", null, 2000, 5);
        FileItem bin = file(".trashed-123-IMG_9.jpg", "trash", "jpeg", 1000000, 5);
        Scanner.Result r = new Scanner.Result();
        r.now = NOW;
        r.files.addAll(Arrays.asList(p1, p1b, meme, cam, vid, apk, doc, bin));
        UnitBuilder ub = new UnitBuilder(r, new Estimates(), 34, RuleSet.defaults());
        Map<String, Unit> units = new HashMap<String, Unit>();
        for (Unit u : ub.units) units.put(u.id, u);

        List<FinalPlan.Entry> l = FinalPlan.build(r.files, units, new HashMap<String, String>(), new HashSet<String>(), new HashMap<String, String>());
        check("every file but the one already in the trash", l.size() == 7 && find(l, bin) == null);
        FinalPlan.Entry eDup = find(l, p1b) != null && FinalPlan.DELETE.equals(find(l, p1b).action) ? find(l, p1b) : find(l, p1);
        check("an exact duplicate: delete (before its compress option)", FinalPlan.DELETE.equals(eDup.suggestedAction) && eDup.reason().equals(Techniques.DUPLICATES)
                && eDup.saved() == 4000000);
        FinalPlan.Entry eMeme = find(l, meme), eCam = find(l, cam), eVid = find(l, vid), eDoc = find(l, doc), eApk = find(l, apk);
        check("an old meme: delete", FinalPlan.DELETE.equals(eMeme.action) && eMeme.reason().equals(Techniques.FORWARDS));
        check("a camera photo: compress", FinalPlan.COMPRESS.equals(eCam.action) && eCam.saved() > 0 && eCam.saved() < cam.size);
        check("a text file with no suggestion: keep, nothing freed", FinalPlan.KEEP.equals(eDoc.action) && eDoc.unit == null && eDoc.saved() == 0 && eDoc.reason().isEmpty());
        check("types", eCam.type.equals(Tags.IMAGE) && eVid.type.equals(Tags.VIDEO) && eApk.type.equals(Tags.APK) && eDoc.type.equals(Tags.OTHER));

        // ---- your choices
        FinalPlan.set(eMeme, FinalPlan.KEEP);
        check("untick the meme: kept", FinalPlan.KEEP.equals(eMeme.action) && eMeme.option == null && FinalPlan.DELETE.equals(eMeme.suggestedAction));
        FinalPlan.set(eDoc, FinalPlan.DELETE);
        check("delete a file nothing suggested: a delete you picked", FinalPlan.DELETE.equals(eDoc.action) && eDoc.option.technique.equals(Techniques.PICKED) && eDoc.saved() == 2000);
        check("compress is only possible where a rule compresses", !FinalPlan.possible(eDoc, FinalPlan.COMPRESS) && FinalPlan.possible(eCam, FinalPlan.COMPRESS)
                && FinalPlan.possible(eDoc, FinalPlan.KEEP) && FinalPlan.possible(eDoc, FinalPlan.DELETE));
        FinalPlan.set(eDoc, FinalPlan.COMPRESS);
        check("compress where impossible: kept", FinalPlan.KEEP.equals(eDoc.action));
        FinalPlan.set(eCam, FinalPlan.DELETE);
        check("a photo to compress can be deleted instead", FinalPlan.DELETE.equals(eCam.action) && eCam.saved() == cam.size);

        // ---- tabs and totals
        FinalPlan.set(eCam, FinalPlan.COMPRESS);
        List<FinalPlan.Entry> del = FinalPlan.tab(l, Tags.IMAGE, FinalPlan.DELETE);
        check("delete tab: the suggested ones, the unticked meme stays in place", del.contains(eMeme) && del.contains(eDup) && !del.contains(eCam));
        List<FinalPlan.Entry> keep = FinalPlan.tab(l, Tags.IMAGE, FinalPlan.KEEP);
        check("keep tab: what is kept now", keep.contains(eMeme) && !keep.contains(eDup) && FinalPlan.group(eMeme, FinalPlan.KEEP).equals("You chose to keep"));
        check("groups: by reason; a delete you picked has its own group", FinalPlan.group(eDup, FinalPlan.DELETE).equals(Techniques.DUPLICATES)
                && FinalPlan.group(eCam, FinalPlan.DELETE).equals("You chose to delete"));
        List<FinalPlan.Entry> big = new ArrayList<FinalPlan.Entry>(del);
        boolean grouped = true;                                  // entries of one reason are together
        for (int i = 2; i < big.size(); i++) if (FinalPlan.group(big.get(i), "delete").equals(FinalPlan.group(big.get(i - 2), "delete"))
                && !FinalPlan.group(big.get(i - 1), "delete").equals(FinalPlan.group(big.get(i), "delete"))) grouped = false;
        check("a reason's files are next to each other", grouped);
        FinalPlan.Totals ti = FinalPlan.totals(l, Tags.IMAGE), all = FinalPlan.totals(l, null);
        check("image totals", ti.files == 4 && ti.count(FinalPlan.DELETE) == 1 && ti.count(FinalPlan.COMPRESS) >= 1
                && ti.count(FinalPlan.KEEP) == 4 - 1 - ti.count(FinalPlan.COMPRESS) && ti.size == 4000000 * 2 + 300000 + 5000000);
        check("freed = deletes + compress savings", all.freed() == all.saved(FinalPlan.DELETE) + all.saved(FinalPlan.COMPRESS) && all.saved(FinalPlan.KEEP) == 0
                && all.files == 7);

        // ---- saved choices and earlier decisions
        Map<String, String> ch = new HashMap<String, String>();
        ch.put(meme.rel, FinalPlan.KEEP); ch.put(doc.rel, FinalPlan.DELETE); ch.put("bad\tpath", "explode");
        Map<String, String> back = FinalPlan.fromText(FinalPlan.toText(ch) + "nonsense\nexplode\tx\n");
        check("choices saved and read back; junk lines ignored", back.size() == 2 && FinalPlan.KEEP.equals(back.get(meme.rel)) && FinalPlan.DELETE.equals(back.get(doc.rel)));
        List<FinalPlan.Entry> l2 = FinalPlan.build(r.files, units, back, new HashSet<String>(), new HashMap<String, String>());
        check("choices win over suggestions", FinalPlan.KEEP.equals(find(l2, meme).action) && find(l2, meme).chosen
                && FinalPlan.DELETE.equals(find(l2, doc).action) && !find(l2, cam).chosen);
        java.util.Set<String> kept = new HashSet<String>(); kept.add(meme.rel);
        Map<String, String> approved = new HashMap<String, String>(); approved.put(cam.rel, Techniques.PHOTOS);
        List<FinalPlan.Entry> l3 = FinalPlan.build(r.files, units, new HashMap<String, String>(), kept, approved);
        check("earlier review decisions: kept is keep, approved is that action", FinalPlan.KEEP.equals(find(l3, meme).action) && find(l3, meme).chosen
                && FinalPlan.COMPRESS.equals(find(l3, cam).action) && find(l3, cam).chosen);
        // ---- the card's analysis
        Tags.Context ctx = Tags.context(r, ub.det);
        List<Explain.Section> ex = Explain.of(find(l3, meme), ctx, RuleSet.defaults(), NOW);
        String all3 = "";
        for (Explain.Section sct : ex) { all3 += "#" + sct.title + "\n"; for (String[] ln : sct.lines) all3 += ln[0] + ": " + ln[1] + "\n"; }
        if (a.length > 0) System.out.println(all3);
        check("analysis sections: verdict, options, rules, what was found, file", ex.size() == 5 && ex.get(0).title.equals("Verdict")
                && ex.get(1).title.startsWith("Options") && ex.get(2).title.equals("Rules that match") && ex.get(3).title.equals("What the app found")
                && ex.get(4).title.equals("File"));
        check("analysis: your choice against the suggestion, the rule that matched, the tags", all3.contains("Final action: Keep (your choice)")
                && all3.contains("Suggested: Delete: " + Techniques.FORWARDS) && all3.contains("Note: You changed the suggestion")
                && all3.contains("Old meme or forward: Delete") && all3.contains("Picture category: Graphics & images") && all3.contains("Name: IMG-20261101-WA0001.jpg"));
        List<Explain.Section> exDoc = Explain.of(find(l, doc), ctx, RuleSet.defaults(), NOW);
        String d3 = ""; for (Explain.Section sct : exDoc) for (String[] ln : sct.lines) d3 += ln[0] + ": " + ln[1] + "\n";
        check("analysis of a file with no suggestion", d3.contains("Suggested: Keep: no rule has anything for this file") && exDoc.size() == 4);

        check("words", FinalPlan.typeLabel(Tags.APK).contains("APK") && FinalPlan.actionLabel(FinalPlan.COMPRESS).equals("Compress") && FinalPlan.TYPES.length == 6);

        System.out.println("FinalPlanTest: " + (checks - fails) + "/" + checks + " checks passed");
        if (fails > 0) System.exit(1);
    }
}
