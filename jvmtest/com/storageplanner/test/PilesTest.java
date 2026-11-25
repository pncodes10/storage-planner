package com.storageplanner.test;

import com.storageplanner.core.*;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * The clean-up piles: files go into plain-language piles by what was suggested, a file you keep stays in its pile,
 * the counts and bytes follow your choices, and each file has a one-sentence reason without rule words. Invented data.
 */
public final class PilesTest {
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
    static Piles.Pile pile(List<Piles.Pile> l, String id) { for (Piles.Pile p : l) if (p.id.equals(id)) return p; return null; }

    public static void main(String[] a) {
        FileItem p1 = file("DCIM/Camera/IMG_1.jpg", "camera", "jpeg", 4000000, 30);
        FileItem p1b = file("DCIM/Camera/IMG_1 (1).jpg", "camera", "jpeg", 4000000, 30);
        p1.sha256 = p1b.sha256 = "same";
        FileItem meme = file("WhatsApp/Media/WhatsApp Images/IMG-20261101-WA0001.jpg", "wa_image", "jpeg", 300000, 20);
        meme.category = PhotoCategory.GRAPHIC; meme.faces = 0; meme.sortVersion = 6;
        FileItem cam = file("DCIM/Camera/IMG_2.jpg", "camera", "jpeg", 5000000, 10);
        FileItem apk = file("Download/old.apk", "download", "zip", 30000000, 400);
        FileItem doc = file("Documents/notes.txt", "document", null, 2000, 5);
        Scanner.Result r = new Scanner.Result();
        r.now = NOW;
        r.files.addAll(Arrays.asList(p1, p1b, meme, cam, apk, doc));
        UnitBuilder ub = new UnitBuilder(r, new Estimates(), 34, RuleSet.defaults());
        Map<String, Unit> units = new HashMap<String, Unit>();
        for (Unit u : ub.units) units.put(u.id, u);
        List<FinalPlan.Entry> l = FinalPlan.build(r.files, units, new HashMap<String, String>(), new HashSet<String>(), new HashMap<String, String>());
        FinalPlan.Entry eMeme = find(l, meme), eCam = find(l, cam), eDoc = find(l, doc), eApk = find(l, apk);
        FinalPlan.Entry eDup = FinalPlan.DELETE.equals(find(l, p1b).action) ? find(l, p1b) : find(l, p1);

        List<Piles.Pile> ps = Piles.build(l);
        check("the copy is in Copies", Piles.COPIES.equals(Piles.of(eDup)) && pile(ps, Piles.COPIES).files.contains(eDup));
        check("the meme is in Memes and forwards", Piles.FORWARDS.equals(Piles.of(eMeme)));
        check("the camera photo is made smaller", Piles.SMALLER.equals(Piles.of(eCam)));
        check("a text file nobody touches is in no pile", Piles.of(eDoc) == null);
        check("removal piles first, Smaller last", ps.get(ps.size() - 1).id.equals(Piles.SMALLER) && ps.get(0).id.equals(Piles.COPIES));
        check("no empty piles", pile(ps, Piles.BLURRY) == null);
        check("old installer pile", eApk.unit == null || Piles.of(eApk) == null || Piles.of(eApk).equals(Piles.DOWNLOADS) || Piles.of(eApk).equals(Piles.OTHER));

        Piles.Pile fw = pile(ps, Piles.FORWARDS);
        check("pile frees the meme", fw.freed() == eMeme.saved() && fw.acting() == 1 && fw.subtitle().startsWith("1 photo · "));

        // keep the meme: it stays in its pile, marked kept
        FinalPlan.set(eMeme, FinalPlan.KEEP); eMeme.chosen = true;
        ps = Piles.build(l);
        fw = pile(ps, Piles.FORWARDS);
        check("a kept file stays in its pile", fw != null && fw.files.contains(eMeme) && fw.acting() == 0 && fw.freed() == 0);
        check("reason for a kept file", Piles.why(eMeme).equals("You chose to keep this."));

        // remove a file nothing suggested: it shows up in Files you picked
        FinalPlan.set(eDoc, FinalPlan.DELETE); eDoc.chosen = true;
        check("a picked file", Piles.PICKED.equals(Piles.of(eDoc)) && Piles.why(eDoc).equals("You chose to remove this."));

        check("reasons are plain sentences", Piles.why(eDup).endsWith(".") && !Piles.why(eDup).contains("rule")
                && Piles.why(eCam).contains("smaller") && Piles.why(eDup).contains("copy"));
        check("every removal pile has a title and banner", Piles.title(Piles.JUNK).length() > 0 && Piles.banner(Piles.COPIES).contains("best"));
        check("counts", Piles.count(1, "photo").equals("1 photo") && Piles.count(1234, "file").equals("1,234 files"));

        // ---- the Settings limits on pictures (Scanner.trimPictures)
        java.util.List<FileItem> fs = new java.util.ArrayList<FileItem>();
        for (int i = 0; i < 6; i++) fs.add(file("DCIM/Camera/IMG_" + i + ".jpg", "camera", "jpeg", (i + 1) * 50000, 10 - i));   // 50..300 KB, IMG_5 newest
        FileItem thumb = file("DCIM/.thumbnails/1.jpg", "thumbnails", "jpeg", 5000, 1);
        FileItem pdf = file("Download/small.pdf", "download", null, 1000, 1);
        fs.add(thumb); fs.add(pdf);
        java.util.List<FileItem> c1 = new java.util.ArrayList<FileItem>(fs);
        int[] n1 = Scanner.trimPictures(c1, 0, 100000);
        check("pictures under 100 KB left out; thumbnails and other files stay", n1[0] == 1 && n1[1] == 0 && c1.size() == 7
                && c1.contains(thumb) && c1.contains(pdf) && !c1.contains(fs.get(0)));
        java.util.List<FileItem> c2 = new java.util.ArrayList<FileItem>(fs);
        int[] n2 = Scanner.trimPictures(c2, 2, 100000);
        check("then only the newest 2", n2[0] == 1 && n2[1] == 3 && c2.contains(fs.get(5)) && c2.contains(fs.get(4)) && !c2.contains(fs.get(3))
                && c2.contains(thumb) && c2.contains(pdf));
        check("described", Scanner.trimmed(n2, 2, 100000).startsWith("newest 2 pictures, 1 pictures under ") && Scanner.trimmed(new int[]{0, 0}, 0, 0) == null);
        java.util.List<FileItem> c3 = new java.util.ArrayList<FileItem>(fs);
        check("no limits: nothing changes", Scanner.trimPictures(c3, 0, 0)[0] == 0 && c3.size() == fs.size());

        System.out.println("PilesTest: " + (checks - fails) + "/" + checks + " checks passed");
        if (fails > 0) System.exit(1);
    }
}
