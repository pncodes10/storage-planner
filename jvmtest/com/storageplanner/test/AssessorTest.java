package com.storageplanner.test;

import com.storageplanner.core.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * The decision algorithm on a hand-built phone: each hard case from docs/storage-engine-design.md (8.2),
 * then safety properties on random phones. Needs no files: the scan result is made in memory.
 */
public final class AssessorTest {
    static int bad = 0, checks = 0;
    static final long DAY = 86400000L, NOW = 1800000000000L;

    static void check(String what, boolean ok) { checks++; if (!ok) { bad++; System.out.println("FAIL " + what); } }

    static FileItem file(Scanner.Result r, String rel, long size, double ageDays, String area, String type) {
        FileItem f = new FileItem(rel, "/storage/emulated/0/" + rel, size, NOW - (long) (ageDays * DAY));
        f.area = area; f.type = type;
        r.files.add(f);
        return f;
    }

    static FileItem photo(Scanner.Result r, String rel, long size, double ageDays, String area, long phash, double sharp) {
        FileItem f = file(r, rel, size, ageDays, area, "jpeg");
        f.width = 4000; f.height = 3000; f.hasPhash = true; f.phash = phash; f.sharp = sharp;
        return f;
    }

    static Scanner.Result phone() { Scanner.Result r = new Scanner.Result(); r.now = NOW; r.total = 128000000000L; r.free = 8000000000L; return r; }

    static Unit unit(UnitBuilder ub, String rel) { for (Unit u : ub.units) if (u.id.equals(rel)) return u; return null; }
    static Option opt(UnitBuilder ub, String rel, String technique) {
        Unit u = unit(ub, rel); if (u == null) return null;
        for (Option o : u.options) if (o.technique.equals(technique)) return o;
        return null;
    }
    static boolean anyDelete(UnitBuilder ub, String rel) {
        Unit u = unit(ub, rel); if (u == null) return false;
        for (Option o : u.options) if (o.action.equals("delete")) return true;
        return false;
    }
    /** In the default plan (every safe step)? */
    static boolean planned(UnitBuilder ub, String rel) {
        Unit u = unit(ub, rel);
        return u != null && Planner.bestWithin(ub.units, Option.R2).chosen.containsKey(u);
    }
    /** Could the planner ever pick a deletion of it, at any tier? */
    static boolean everAutoDeleted(UnitBuilder ub, String rel) {
        Unit u = unit(ub, rel); if (u == null) return false;
        for (Option o : u.options) if (o.action.equals("delete") && !o.review) return true;
        return false;
    }

    public static void main(String[] args) {
        cases();
        properties();
        System.out.println((checks - bad) + "/" + checks + " assessor checks pass");
        if (bad > 0) System.exit(1);
    }

    static void cases() {
        Scanner.Result r = phone();
        AppItem maps = new AppItem("com.example.maps", "Maps"); maps.versionCode = 50; maps.isEssential = true; r.apps.add(maps);

        // ---- only copy of a blurry family photo, among sharp ones
        for (int i = 0; i < 9; i++) photo(r, "DCIM/Camera/IMG_10" + i + ".jpg", 4000000, 400 + i, "camera", 0x1111L * (i + 3) << 7 ^ (0x5A5AL << (i * 5)), 100);
        FileItem blurry = photo(r, "DCIM/Camera/IMG_blur.jpg", 4000000, 900, "camera", 0x7777777700000000L, 5); blurry.faces = 3;

        // ---- identical copies: one stays
        FileItem d1 = file(r, "Download/report.pdf", 500000, 30, "download", "pdf"); d1.sha256 = "aa";
        FileItem d2 = file(r, "Download/report (1).pdf", 500000, 29, "download", "pdf"); d2.sha256 = "aa";
        // ---- same name pattern, different bytes: nothing
        file(r, "Download/notes.pdf", 600000, 30, "download", "pdf");
        file(r, "Download/notes (1).pdf", 600001, 29, "download", "pdf");

        // ---- ten-year-old deed and an old ordinary download
        file(r, "Download/property_deed_2016.pdf", 3000000, 3650, "download", "pdf");
        file(r, "Download/setup_guide.pdf", 3000000, 400, "download", "pdf");

        // ---- ID card picture and its identical extra copy
        FileItem id1 = photo(r, "DCIM/Camera/IMG_id.jpg", 2000000, 200, "camera", 0x0F0F0F0F0F0F0F0FL, 90); id1.category = PhotoCategory.ID; id1.sha256 = "id";
        FileItem id2 = photo(r, "Pictures/copy_of_card.jpg", 2000000, 100, "pictures", 0x0F0F0F0F0F0F0F0FL, 90); id2.category = PhotoCategory.ID; id2.sha256 = "id";

        // ---- junk
        file(r, "DCIM/.thumbnails/1.jpg", 20000, 50, "thumbnails", "other");
        file(r, "Download/movie.mp4.crdownload", 9000000, 5, "temp", "other");
        file(r, "DCIM/Camera/.trashed-171-IMG_1.jpg", 3000000, 3, "trash", "jpeg");
        // ---- leftovers of an uninstalled app: game data vs the user's recordings
        FileItem obb = file(r, "Android/obb/com.gone.game/main.1.obb", 900000000, 300, "residue", "other"); obb.pkg = "com.gone.game";
        FileItem rec = file(r, "Android/media/com.gone.recorder/Recordings/talk.m4a", 50000000, 300, "residue", "other"); rec.pkg = "com.gone.recorder";

        // ---- WhatsApp backups: older ones only while a newer one is there
        file(r, "Android/media/com.whatsapp/WhatsApp/Databases/msgstore.db.crypt15", 80000000, 1, "wa_other", "other");
        file(r, "Android/media/com.whatsapp/WhatsApp/Databases/msgstore-2026-01-01.1.db.crypt15", 80000000, 60, "wa_old_backup", "other");
        file(r, "Android/media/com.whatsapp.w4b/WhatsApp Business/Databases/msgstore-2026-01-01.1.db.crypt15", 80000000, 60, "wa_old_backup", "other");

        // ---- installers
        FileItem apkOld = file(r, "Download/maps-40.apk", 60000000, 100, "download", "zip"); apkOld.apkPackage = "com.example.maps"; apkOld.apkVersion = 40;
        FileItem apkNew = file(r, "Download/maps-60.apk", 60000000, 10, "download", "zip"); apkNew.apkPackage = "com.example.maps"; apkNew.apkVersion = 60;

        // ---- WhatsApp Sent copies: a sure match and a loose one
        FileItem orig = photo(r, "DCIM/Camera/IMG_sent.jpg", 5000000, 20, "camera", 0x123456789ABCDEFL, 100);
        FileItem sentSure = photo(r, "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/Sent/IMG-1.jpg", 300000, 20, "wa_sent", 0x123456789ABCDEFL ^ 1L, 80);
        sentSure.width = 1600; sentSure.height = 1200;
        FileItem orig2 = photo(r, "DCIM/Camera/IMG_sent2.jpg", 5000000, 21, "camera", 0x00FF00FF00FF00FFL, 100);
        FileItem sentLoose = photo(r, "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/Sent/IMG-2.jpg", 300000, 21, "wa_sent", 0x00FF00FF00FF00FFL ^ 0x7FL, 80);
        sentLoose.width = 1600; sentLoose.height = 1200;

        // ---- burst: three shots a few seconds apart; one is also in Download as an old file
        FileItem b1 = photo(r, "DCIM/Camera/IMG_b1.jpg", 4000000, 50, "camera", 0x3C3C3C3C3C3C3C3CL, 120);
        FileItem b2 = photo(r, "DCIM/Camera/IMG_b2.jpg", 4000000, 50 - 1e-4, "camera", 0x3C3C3C3C3C3C3C3CL ^ 3L, 90);
        FileItem b3 = photo(r, "DCIM/Camera/IMG_b3.jpg", 4000000, 50 - 2e-4, "camera", 0x3C3C3C3C3C3C3C3CL ^ 12L, 80);

        // ---- kept copy that is itself an old download: must never be planned away with its copy
        FileItem k1 = file(r, "Download/old_video.mp4", 700000000, 500, "download", "mp4"); k1.sha256 = "vv";
        FileItem k2 = file(r, "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Video/VID-1.mp4", 700000000, 400, "wa_video", "mp4"); k2.sha256 = "vv";

        // ---- RAW + JPEG pair: not split, not re-encoded
        photo(r, "DCIM/Camera/IMG_raw.jpg", 6000000, 10, "camera", 0x5555AAAA5555AAAAL, 100);
        file(r, "DCIM/Camera/IMG_raw.dng", 25000000, 10, "camera", "other");

        // ---- screenshots by what they show
        FileItem otp = photo(r, "Pictures/Screenshots/Screenshot_otp.png", 300000, 5, "screenshot", 1, 50); otp.type = "png"; otp.category = PhotoCategory.OTP;
        FileItem pay = photo(r, "Pictures/Screenshots/Screenshot_pay.png", 300000, 200, "screenshot", 2, 50); pay.type = "png"; pay.category = PhotoCategory.PAYMENT;
        FileItem tick = photo(r, "Pictures/Screenshots/Screenshot_tick.png", 300000, 300, "screenshot", 3, 50); tick.type = "png"; tick.category = PhotoCategory.TICKET;
        FileItem dark = photo(r, "DCIM/Camera/IMG_dark.jpg", 2000000, 60, "camera", 0, 40); dark.category = PhotoCategory.ACCIDENTAL;
        FileItem greet = photo(r, "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/IMG-g.jpg", 200000, 30, "wa_image", 9, 50); greet.category = PhotoCategory.GREETING;
        FileItem greetFace = photo(r, "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/IMG-gf.jpg", 200000, 30, "wa_image", 10, 50);
        greetFace.category = PhotoCategory.GREETING; greetFace.faces = 2;
        FileItem receipt = photo(r, "DCIM/Camera/IMG_receipt.jpg", 4000000, 90, "camera", 0x6996699669966996L, 100); receipt.category = PhotoCategory.RECEIPT;
        // ---- Telegram: only assumed to be in the cloud
        file(r, "Telegram/Telegram Video/clip.mp4", 90000000, 40, "telegram_media", "mp4");

        UnitBuilder ub = new UnitBuilder(r, new Estimates(), 34);

        check("blurry only-copy with faces: never deleted automatically", !everAutoDeleted(ub, blurry.rel));
        Option bl = opt(ub, blurry.rel, Techniques.BLURRY);
        check("blurry: still shown for review", bl != null && bl.review && bl.risk == Option.R4);

        Option dup = opt(ub, d2.rel, Techniques.DUPLICATES);
        check("identical copy: No loss, planned", dup != null && dup.risk == Option.R1 && !dup.review && planned(ub, d2.rel) && d1.rel.equals(dup.keeps));
        check("identical copy: the kept one is not deletable", !anyDelete(ub, d1.rel));
        check("same name, different bytes: nothing", !anyDelete(ub, "Download/notes.pdf") && !anyDelete(ub, "Download/notes (1).pdf"));

        Unit deed = unit(ub, "Download/property_deed_2016.pdf");
        check("old deed: protected, nothing suggested", !anyDelete(ub, "Download/property_deed_2016.pdf") && (deed == null || deed.assessment.isProtected()));
        Option old = opt(ub, "Download/setup_guide.pdf", Techniques.OLD_DOWNLOADS);
        check("old download: review only", old != null && old.review && !planned(ub, "Download/setup_guide.pdf"));

        Unit idKeep = unit(ub, id1.rel), idCopy = unit(ub, id2.rel);
        check("ID picture: kept one has no suggestion at all", idKeep != null && idKeep.options.isEmpty() && idKeep.assessment.isProtected());
        check("ID picture: the identical extra copy is the only suggestion", idCopy != null && idCopy.options.size() == 1
                && idCopy.options.get(0).technique.equals(Techniques.DUPLICATES));

        check("thumbnail: Safe", opt(ub, "DCIM/.thumbnails/1.jpg", Techniques.JUNK).risk == Option.R0);
        check("stale partial download: Safe", opt(ub, "Download/movie.mp4.crdownload", Techniques.JUNK).risk == Option.R0);
        check("trash: No loss", opt(ub, "DCIM/Camera/.trashed-171-IMG_1.jpg", Techniques.TRASH).risk == Option.R1);
        Option o1 = opt(ub, obb.rel, Techniques.JUNK), o2 = opt(ub, rec.rel, Techniques.LEFTOVER_MEDIA);
        check("game data of an uninstalled game: planned", o1 != null && o1.risk <= Option.R1 && !o1.review);
        check("recording left by an uninstalled app: review only, not junk", o2 != null && o2.review && opt(ub, rec.rel, Techniques.JUNK) == null);

        Option wb1 = opt(ub, "Android/media/com.whatsapp/WhatsApp/Databases/msgstore-2026-01-01.1.db.crypt15", Techniques.WA_BACKUP);
        Option wb2 = opt(ub, "Android/media/com.whatsapp.w4b/WhatsApp Business/Databases/msgstore-2026-01-01.1.db.crypt15", Techniques.WA_BACKUP);
        check("old backup with a newer one beside it: No loss", wb1 != null && wb1.risk == Option.R1 && !wb1.review);
        check("old backup that is the only one: review only", wb2 != null && wb2.review);

        check("installer older than the installed app: No loss", opt(ub, apkOld.rel, Techniques.INSTALLED_APK) != null
                && opt(ub, apkOld.rel, Techniques.INSTALLED_APK).risk == Option.R1);
        check("installer newer than the installed app: not in the safe plan", !planned(ub, apkNew.rel));

        Option s1 = opt(ub, sentSure.rel, Techniques.SENT), s2 = opt(ub, sentLoose.rel, Techniques.SENT_CHECK);
        check("Sent copy, sure match: No loss, keeps the original", s1 != null && s1.risk == Option.R1 && orig.rel.equals(s1.keeps));
        check("Sent copy, loose match: your call", s2 != null && s2.risk == Option.R4 && opt(ub, sentLoose.rel, Techniques.SENT) == null
                && orig2.rel.equals(s2.keeps));
        check("original of a Sent copy: never deleted automatically", !everAutoDeleted(ub, orig.rel));

        int burstDeletes = 0; String keeper = null;
        for (FileItem b : new FileItem[]{b1, b2, b3}) {
            Option o = opt(ub, b.rel, Techniques.BURSTS);
            if (o != null) { burstDeletes++; keeper = o.keeps; check("burst extra: never in the safe plan", !planned(ub, b.rel) || !Planner.bestWithin(ub.units, Option.R2).chosen.get(unit(ub, b.rel)).action.equals("delete")); }
        }
        check("burst of three: two extras, one kept", burstDeletes == 2 && b1.rel.equals(keeper));
        check("burst: the kept shot is never deleted automatically", !everAutoDeleted(ub, b1.rel));

        Option kd = opt(ub, k1.rel, Techniques.OLD_DOWNLOADS);
        check("kept copy that is an old download: not deletable automatically", !everAutoDeleted(ub, k1.rel) && (kd == null || kd.review));
        check("its duplicate may go", opt(ub, k2.rel, Techniques.DUPLICATES) != null);

        Unit raw = unit(ub, "DCIM/Camera/IMG_raw.jpg");
        check("RAW + JPEG: the JPEG is not re-encoded", raw == null || raw.options.isEmpty());

        check("OTP screenshot: your call, not safe plan", opt(ub, otp.rel, Techniques.OTP_SHOTS) != null && !opt(ub, otp.rel, Techniques.OTP_SHOTS).review
                && opt(ub, otp.rel, Techniques.OTP_SHOTS).risk == Option.R4);
        check("payment screenshot: review only", opt(ub, pay.rel, Techniques.OLD_PAYMENTS).review);
        check("ticket: review only", opt(ub, tick.rel, Techniques.EXPIRED_TICKETS).review);
        check("dark shot: review only", opt(ub, dark.rel, Techniques.ACCIDENTAL).review);
        check("greeting: your call", !opt(ub, greet.rel, Techniques.GREETINGS).review);
        check("greeting with faces: review only", opt(ub, greetFace.rel, Techniques.GREETINGS).review);
        Unit rc = unit(ub, receipt.rel);
        boolean lossy = false; if (rc != null) for (Option o : rc.options) if (o.action.equals("reencode_photo")) lossy = true;
        check("receipt photo: not re-encoded with loss", !lossy);
        Option tg = opt(ub, "Telegram/Telegram Video/clip.mp4", Techniques.TELEGRAM);
        check("Telegram media: your call, not in the safe plan", tg != null && tg.risk == Option.R4 && !planned(ub, "Telegram/Telegram Video/clip.mp4"));

        // ---- the hand-over to the action engine
        Planner.Plan safe = Planner.bestWithin(ub.units, Option.R2);
        Map<String, String> accepted = new HashMap<String, String>();
        accepted.put(d2.rel, Techniques.DUPLICATES);
        Set<String> kept = new HashSet<String>(); kept.add("DCIM/.thumbnails/1.jpg");
        String js = Actions.json(ub.units, safe, kept, accepted, true, NOW);
        check("actions: approved state", js.contains("\"item\": \"Download/report (1).pdf\", \"kind\": \"file\", \"action\": \"delete\", \"state\": \"approved\""));
        check("actions: keeps + verify", js.contains("\"keeps\": \"Download/report.pdf\"") && js.contains("\"verify\": {\"size\": 500000, \"mtime\": "));
        check("actions: review state with its reason", js.contains("\"state\": \"review\"") && js.contains("\"review_because\": \"old does not mean unimportant\""));
        check("actions: protected list", js.contains("\"item\": \"DCIM/Camera/IMG_id.jpg\", \"protected\": true"));
        check("actions: kept by user left out", js.contains("\"kept_by_user\": [\"DCIM/.thumbnails/1.jpg\"]") && !js.contains("\"item\": \"DCIM/.thumbnails/1.jpg\""));
        check("actions: balanced JSON", count(js, '{') == count(js, '}') && count(js, '[') == count(js, ']'));
    }

    static int count(String s, char c) { int n = 0; for (int i = 0; i < s.length(); i++) if (s.charAt(i) == c) n++; return n; }

    /** Random phones: whatever the planner picks, at any tier, no group loses every copy and no protected file is touched. */
    static void properties() {
        Random rnd = new Random(11);
        String[] areas = {"camera", "download", "wa_image", "wa_sent", "screenshot", "pictures", "document", "telegram_media", "trash", "residue"};
        String[] cats = {null, null, null, PhotoCategory.ID, PhotoCategory.OTP, PhotoCategory.PAYMENT, PhotoCategory.GREETING,
                PhotoCategory.ACCIDENTAL, PhotoCategory.RECEIPT, PhotoCategory.PEOPLE, PhotoCategory.TICKET};
        String[] names = {"IMG_%d.jpg", "passport_%d.jpg", "bank statement %d.pdf", "file%d.bin", "Screenshot_%d.png", "song%d.mp3"};
        int lost = 0, touched = 0, reviewPicked = 0, runs = 300;
        for (int t = 0; t < runs; t++) {
            Scanner.Result r = phone();
            int n = 20 + rnd.nextInt(60);
            for (int i = 0; i < n; i++) {
                String area = areas[rnd.nextInt(areas.length)];
                String name = String.format(names[rnd.nextInt(names.length)], i);
                String rel = area + "_dir" + rnd.nextInt(3) + "/" + name;
                if (area.equals("residue")) rel = (rnd.nextBoolean() ? "Android/obb/" : "Android/media/") + "com.gone/" + name;
                FileItem f = photo(r, rel, 1000000 + rnd.nextInt(5) * 1000000L, rnd.nextInt(900) + rnd.nextDouble() / 100, area,
                        (long) rnd.nextInt(6) * 0x0101010101010101L ^ rnd.nextInt(8), 5 + rnd.nextInt(150));
                f.type = name.endsWith(".png") ? "png" : name.endsWith(".jpg") ? "jpeg" : "other";
                f.category = cats[rnd.nextInt(cats.length)];
                f.faces = rnd.nextInt(4) - 1;
                if (rnd.nextInt(3) == 0) f.sha256 = "h" + f.size + "_" + rnd.nextInt(3);     // same size + same tag = identical
                if (area.equals("residue")) f.pkg = "com.gone";
            }
            UnitBuilder ub = new UnitBuilder(r, new Estimates(), 34);
            Planner.Plan all = Planner.bestWithin(ub.units, Option.R4);
            Planner.Plan goal = Planner.tiered(ub.units, Long.MAX_VALUE / 4);
            for (Planner.Plan p : new Planner.Plan[]{all, goal, Planner.bestWithin(ub.units, Option.R2)}) {
                Set<String> deleted = new HashSet<String>();
                for (Map.Entry<Unit, Option> e : p.chosen.entrySet()) {
                    Option o = e.getValue(); Unit u = e.getKey();
                    if (o.review) reviewPicked++;
                    if (o.action.equals("delete")) deleted.add(u.id);
                    if (u.assessment != null && u.assessment.isProtected() && !o.technique.equals(Techniques.DUPLICATES)) touched++;
                }
                for (Map.Entry<Unit, Option> e : p.chosen.entrySet())
                    if (e.getValue().keeps != null && deleted.contains(e.getValue().keeps)) lost++;
            }
            // nothing review-only is ever labelled safer than "your call"
            for (Unit u : ub.units) for (Option o : u.options) if (o.review && o.risk != Option.R4) reviewPicked++;
        }
        check("random phones: a kept copy is never planned for deletion with its copies (" + lost + ")", lost == 0);
        check("random phones: protected files are only ever offered as identical extra copies (" + touched + ")", touched == 0);
        check("random phones: the planner never picks a review-only option (" + reviewPicked + ")", reviewPicked == 0);
    }
}
