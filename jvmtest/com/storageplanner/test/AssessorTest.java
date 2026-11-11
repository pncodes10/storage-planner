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
        Policy.active = new Policy();
        cases();
        need();
        videos();
        try { lab(); } catch (Exception e) { check("lab run: " + e, false); }
        try { indic(); } catch (Exception e) { check("indic: " + e, false); }
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
        check("blurry only-copy with faces: kept without asking", !anyDelete(ub, blurry.rel));

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
        check("actions: review state with its reason", js.contains("\"state\": \"review\"") && js.contains("\"review_because\": \"one reason is not enough (downloaded 13 months ago and not changed since)\""));
        check("actions: protected list", js.contains("\"item\": \"DCIM/Camera/IMG_id.jpg\", \"protected\": true"));
        check("actions: kept by user left out", js.contains("\"kept_by_user\": [\"DCIM/.thumbnails/1.jpg\"]") && !js.contains("\"item\": \"DCIM/.thumbnails/1.jpg\""));
        check("actions: balanced JSON", count(js, '{') == count(js, '}') && count(js, '[') == count(js, ']'));
    }

    static Policy policy(int sensitivity) { Policy p = new Policy(); p.sensitivity = sensitivity; return p; }

    /** "Will the user need it?": the same files at the three settings, with ways switched off, and with earlier choices. */
    static void need() {
        Scanner.Result r = phone();
        for (int i = 0; i < 9; i++) photo(r, "DCIM/Camera/IMG_20" + i + ".jpg", 4000000, 400 + i * 3, "camera", 0x1111L * (i + 3) << 7 ^ (0x5A5AL << (i * 5)), 100);
        // a party: eight photos in ten minutes, one of them soft, no faces found in it
        for (int i = 0; i < 8; i++) photo(r, "DCIM/Camera/IMG_party" + i + ".jpg", 4000000, 100 + i * 0.001, "camera", 0xABCDEFL * (i + 5) << 9 ^ (0x3333L << (i * 6)), i == 3 ? 10 : 100);
        FileItem soft = photo(r, "DCIM/Camera/IMG_soft_alone.jpg", 4000000, 250, "camera", 0x4444000011110000L, 10);
        FileItem pocket = photo(r, "DCIM/Camera/IMG_pocket.jpg", 1500000, 250.5, "camera", 0, 5); pocket.category = PhotoCategory.ACCIDENTAL;
        FileItem otpNew = photo(r, "Pictures/Screenshots/Screenshot_otp_new.png", 300000, 1.5, "screenshot", 1, 50); otpNew.type = "png"; otpNew.category = PhotoCategory.OTP;
        FileItem otpOld = photo(r, "Pictures/Screenshots/Screenshot_otp_old.png", 300000, 40, "screenshot", 1, 50); otpOld.type = "png"; otpOld.category = PhotoCategory.OTP;
        FileItem tOld = photo(r, "Pictures/Screenshots/Screenshot_trip.png", 300000, 200, "screenshot", 3, 50); tOld.type = "png"; tOld.category = PhotoCategory.TICKET; tOld.catDate = NOW - 150 * DAY;
        FileItem tNext = photo(r, "Pictures/Screenshots/Screenshot_next.png", 300000, 40, "screenshot", 3, 50); tNext.type = "png"; tNext.category = PhotoCategory.TICKET; tNext.catDate = NOW + 5 * DAY;
        file(r, "Download/old_archive.zip", 80000000, 400, "download", "zip");
        file(r, "Download/thesis_draft.pdf", 5000000, 400, "download", "pdf");
        file(r, "Download/setup (1).exe", 90000000, 300, "download", "other");

        UnitBuilder bal = new UnitBuilder(r, new Estimates(), 34, policy(Policy.BALANCED));
        UnitBuilder car = new UnitBuilder(r, new Estimates(), 34, policy(Policy.CAREFUL));
        UnitBuilder tho = new UnitBuilder(r, new Estimates(), 34, policy(Policy.THOROUGH));

        check("soft photo from a party: kept without asking", opt(bal, "DCIM/Camera/IMG_party3.jpg", Techniques.BLURRY) == null);
        Option sa = opt(bal, soft.rel, Techniques.BLURRY);
        check("soft photo taken alone: one reason, review", sa != null && sa.review && sa.note.startsWith("one reason is not enough"));
        check("soft photo taken alone, Careful: kept without asking", opt(car, soft.rel, Techniques.BLURRY) == null);
        Option pk = del(bal, pocket.rel);        // a file has one delete suggestion; here it is filed under "possibly blurry"
        check("black and soft pocket shot: suggested (two reasons)", pk != null && !pk.review && pk.risk == Option.R4 && pk.need <= 25 && pk.evidence.contains("against"));
        check("pocket shot: not in the default plan at Balanced", Planner.safe(bal.units).chosen.get(unit(bal, pocket.rel)) != pk);

        check("one-time code from yesterday: UnitBuilder waits a day; from 1.5 days: recent, not suggested",
                opt(bal, otpNew.rel, Techniques.OTP_SHOTS) == null || opt(bal, otpNew.rel, Techniques.OTP_SHOTS).review);
        Option ob = opt(bal, otpOld.rel, Techniques.OTP_SHOTS), ot = opt(tho, otpOld.rel, Techniques.OTP_SHOTS);
        check("old one-time code, Balanced: suggested, your call", ob != null && !ob.review && !ob.sure && !ob.safeStep());
        check("old one-time code, Thorough: in the default plan", ot != null && ot.sure && Planner.safe(tho.units).chosen.get(unit(tho, otpOld.rel)) == ot);
        check("Thorough never marks a review item sure", noSureReview(tho));

        Option tk = opt(bal, tOld.rel, Techniques.EXPIRED_TICKETS);
        check("ticket for a trip five months ago: suggested", tk != null && !tk.review);
        check("ticket for next week: no suggestion", opt(bal, tNext.rel, Techniques.EXPIRED_TICKETS) == null);

        Option zip = opt(bal, "Download/old_archive.zip", Techniques.OLD_DOWNLOADS), pdf = opt(bal, "Download/thesis_draft.pdf", Techniques.OLD_DOWNLOADS);
        check("old archive in Downloads: suggested", zip != null && !zip.review);
        check("old document in Downloads: review, not suggested", pdf != null && pdf.review);
        check("old document, Careful: kept without asking", opt(car, "Download/thesis_draft.pdf", Techniques.OLD_DOWNLOADS) == null);
        Option exe = opt(bal, "Download/setup (1).exe", Techniques.OLD_DOWNLOADS);
        check("old second download of an installer: suggested", exe != null && !exe.review && exe.evidence.contains("second download"));

        // ---- switching a way off can only take suggestions away
        Policy noAge = policy(Policy.BALANCED); noAge.useAge = false;
        UnitBuilder na = new UnitBuilder(r, new Estimates(), 34, noAge);
        Option z2 = opt(na, "Download/old_archive.zip", Techniques.OLD_DOWNLOADS);
        check("age switched off: the old archive has one reason left, so review", z2 != null && z2.review);
        String[] ways = {"source", "content", "people", "quality", "age", "event", "motion", "history"};
        int base = suggestions(bal); boolean never = true;
        for (int w = 1; w < ways.length; w++) {              // source only moves the starting point, so it is not part of this rule
            Policy q = policy(Policy.BALANCED);
            if (w == 1) q.useContent = false; if (w == 2) q.usePeople = false; if (w == 3) q.useQuality = false; if (w == 4) q.useAge = false;
            if (w == 5) q.useEvent = false; if (w == 6) q.useMotion = false; if (w == 7) q.useHistory = false;
            // people and event only ever speak for keeping, so without them more may be suggested; the others only against
            if (w == 2 || w == 5) continue;
            if (suggestions(new UnitBuilder(r, new Estimates(), 34, q)) > base) { never = false; System.out.println("  more suggestions without " + ways[w]); }
        }
        check("switching off a way that speaks against files never adds suggestions", never);
        System.out.println("  suggested without review: Careful " + suggestions(car) + ", Balanced " + suggestions(bal) + ", Thorough " + suggestions(tho));
        check("more is suggested at Thorough than at Careful", suggestions(tho) > suggestions(car));

        // ---- earlier choices: the user kept six old archives before
        Policy learned = policy(Policy.BALANCED);
        learned.kept = new HashSet<String>(); learned.accepted = new HashMap<String, String>();
        Scanner.Result r2 = phone();
        for (int i = 0; i < 7; i++) file(r2, "Download/archive" + i + ".zip", 80000000, 400 + i, "download", "zip");
        for (int i = 0; i < 6; i++) learned.kept.add("Download/archive" + i + ".zip");
        UnitBuilder plain = new UnitBuilder(r2, new Estimates(), 34, policy(Policy.BALANCED)), taught = new UnitBuilder(r2, new Estimates(), 34, learned);
        Option before = opt(plain, "Download/archive6.zip", Techniques.OLD_DOWNLOADS), after = opt(taught, "Download/archive6.zip", Techniques.OLD_DOWNLOADS);
        check("earlier choices: kept six old archives, so the seventh is no longer suggested",
                before != null && !before.review && (after == null || after.review) && (after == null || after.evidence.contains("you kept 6 of 6")));
    }

    static Option del(UnitBuilder ub, String rel) {
        Unit u = unit(ub, rel); if (u == null) return null;
        for (Option o : u.options) if (o.action.equals("delete")) return o;
        return null;
    }

    static boolean noSureReview(UnitBuilder ub) {
        for (Unit u : ub.units) for (Option o : u.options) if (o.sure && (o.review || (u.assessment != null && u.assessment.isProtected()))) return false;
        return true;
    }

    /** Deletions of files that are not copies of anything, suggested without asking for a review. */
    static int suggestions(UnitBuilder ub) {
        int n = 0;
        for (Unit u : ub.units) for (Option o : u.options) if (o.need >= 0 && !o.review) n++;
        return n;
    }

    static FileItem video(Scanner.Result r, String rel, long size, double ageDays, String area, long durationMs, long seed, int frames) {
        FileItem f = file(r, rel, size, ageDays, area, "mp4");
        f.width = 1920; f.height = 1080; f.durationMs = durationMs; f.vcodec = "video/hevc"; f.bitrate = 8000000;
        if (frames > 0) {
            f.vFrames = frames; f.vHashes = new long[frames]; f.vFaces = 0;
            java.util.Random rnd = new java.util.Random(seed);
            for (int i = 0; i < frames; i++) f.vHashes[i] = rnd.nextLong() >>> 1;
        }
        return f;
    }

    static void black(FileItem f) { f.vDark = 1; f.vFlat = 1; java.util.Arrays.fill(f.vHashes, 0L); }

    static void videos() {
        Scanner.Result r = phone();
        FileItem pocket = video(r, "DCIM/Camera/VID_pocket.mp4", 30000000, 80, "camera", 3000, 1, 5); black(pocket);
        FileItem blackMid = video(r, "DCIM/Camera/VID_black12s.mp4", 90000000, 80.5, "camera", 12000, 2, 5); black(blackMid);
        FileItem talk = video(r, "DCIM/Camera/VID_talk.mp4", 400000000, 81, "camera", 600000, 3, 5); black(talk);
        FileItem blink = video(r, "DCIM/Camera/VID_blink.mp4", 6000000, 82, "camera", 1200, 4, 5);
        FileItem blinkKid = video(r, "DCIM/Camera/VID_kid.mp4", 6000000, 83, "camera", 1500, 5, 5); blinkKid.vFaces = 1;
        FileItem normal = video(r, "DCIM/Camera/VID_trip.mp4", 200000000, 84, "camera", 45000, 6, 5);
        FileItem notLooked = video(r, "DCIM/Camera/VID_notlooked.mp4", 9000000, 85, "camera", 1000, 7, 0);
        FileItem rec = video(r, "DCIM/Screen recordings/Screen_Recording_20260101.mp4", 120000000, 200, "camera", 60000, 8, 5);
        FileItem recNew = video(r, "DCIM/Screen recordings/Screen_Recording_20261101.mp4", 120000000, 5, "camera", 60000, 9, 5);
        // a video and the copy WhatsApp made when it was sent; and a different video of the same length
        FileItem orig = video(r, "DCIM/Camera/VID_sent.mp4", 150000000, 20, "camera", 30000, 10, 5);
        FileItem sent = video(r, "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Video/Sent/VID-1.mp4", 9000000, 20, "wa_sent_video", 30200, 10, 5);
        sent.width = 848; sent.height = 478; for (int i = 0; i < 5; i++) sent.vHashes[i] ^= 1L << i;
        FileItem other = video(r, "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Video/Sent/VID-2.mp4", 9000000, 20, "wa_sent_video", 30100, 99, 5);
        other.width = 848; other.height = 478;

        UnitBuilder ub = new UnitBuilder(r, new Estimates(), 34, policy(Policy.BALANCED));
        Option a1 = opt(ub, pocket.rel, Techniques.ACCIDENTAL_VIDEOS);
        check("black three-second video: suggested as accidental", a1 != null && !a1.review && a1.risk == Option.R4);
        Option a2 = opt(ub, blackMid.rel, Techniques.ACCIDENTAL_VIDEOS);
        check("black twelve-second video: darkness alone, review", a2 != null && a2.review);
        Option a3 = opt(ub, talk.rel, Techniques.ACCIDENTAL_VIDEOS);
        check("black ten-minute video: may be kept for its sound, never suggested", a3 == null || a3.review);
        Option a4 = opt(ub, blink.rel, Techniques.ACCIDENTAL_VIDEOS);
        check("one-second clip with something in it: shortness alone, review", a4 != null && a4.review);
        check("one-second clip of a person: kept without asking", opt(ub, blinkKid.rel, Techniques.ACCIDENTAL_VIDEOS) == null);
        check("ordinary video: nothing suggested", !anyDelete(ub, normal.rel));
        Option a5 = opt(ub, notLooked.rel, Techniques.ACCIDENTAL_VIDEOS);
        check("short clip that was not looked into: review at most", a5 == null || a5.review);
        Option s1 = opt(ub, rec.rel, Techniques.SCREEN_RECORDINGS);
        check("screen recording from months ago: suggested", s1 != null && !s1.review);
        check("screen recording from this week: nothing", !anyDelete(ub, recNew.rel));
        Option sv = opt(ub, sent.rel, Techniques.SENT_VIDEOS);
        check("Sent copy of a video still on the phone: found, keeps the original", sv != null && orig.rel.equals(sv.keeps) && !sv.review);
        check("a different Sent video of the same length: not matched", !anyDelete(ub, other.rel));
        check("the original of a Sent video is never deleted automatically", !everAutoDeleted(ub, orig.rel));

        Policy noMotion = policy(Policy.THOROUGH);
        UnitBuilder th = new UnitBuilder(r, new Estimates(), 34, noMotion);
        check("Thorough: no video goes into the default plan unless it is almost certainly not needed", videoPlanOk(th));

        // ---- VideoLook on made-up frames: black, flat, moving
        check("frames: black video", look(0).vDark == 1f && look(0).vFlat == 1f && !Need.still(look(2)));
        check("frames: flat grey video is empty but not dark", look(1).vDark == 0f && look(1).vFlat == 1f);
        check("frames: moving video is neither, and its frames differ", look(2).vFlat == 0f && look(2).vFrames == 5);
        check("frames: unreadable video", look(3).vFrames == 0 && look(3).vHashes == null);
    }

    static boolean videoPlanOk(UnitBuilder ub) {
        for (Map.Entry<Unit, Option> e : Planner.safe(ub.units).chosen.entrySet()) {
            Option o = e.getValue();
            if (o.need >= 0 && (o.need > 10 || o.review)) return false;
        }
        return true;
    }

    /** kind 0 = black, 1 = flat grey, 2 = a pattern that changes over time, 3 = cannot be read */
    static FileItem look(final int kind) {
        FileItem f = new FileItem("v.mp4", "/v.mp4", 1000, NOW);
        f.durationMs = 10000;
        VideoLook.look(new JvmFrames(kind), f);
        return f;
    }

    /** The Lab's "Run the algorithm" on four made-up pictures: two identical, one black and soft, one ordinary. */
    static void lab() throws Exception {
        java.io.File dir = java.nio.file.Files.createTempDirectory("lab").toFile();
        byte[] jpeg = new byte[300000]; new Random(5).nextBytes(jpeg); jpeg[0] = (byte) 0xFF; jpeg[1] = (byte) 0xD8; jpeg[2] = (byte) 0xFF;
        byte[] dark = jpeg.clone(); dark[1000] ^= 1;
        byte[] other = new byte[5000000]; new Random(6).nextBytes(other); other[0] = (byte) 0xFF; other[1] = (byte) 0xD8; other[2] = (byte) 0xFF;
        String[] names = {"IMG_a.jpg", "IMG_a_copy.jpg", "IMG_dark.jpg", "IMG_view.jpg"};
        byte[][] data = {jpeg, jpeg, dark, other};
        List<Lab.Pic> pics = new ArrayList<Lab.Pic>();
        for (int i = 0; i < 4; i++) {
            java.io.File f = new java.io.File(dir, names[i]);
            java.nio.file.Files.write(f.toPath(), data[i]);
            pics.add(new Lab.Pic(f.getPath(), names[i], f.length(), NOW - (100 + i * 10) * DAY));
        }
        final StringBuilder log = new StringBuilder();
        Policy p = policy(Policy.BALANCED);
        List<Lab.Conclusion> c = Lab.algorithm(new JvmFrames(2) {
            @Override public int[] argb(String path, int longEdge, int[] dims) {
                int w = 400, h = 300; dims[0] = 4000; dims[1] = 3000; dims[2] = w; dims[3] = h;
                int[] px = new int[w * h];
                boolean black = path.contains("dark"); int k = path.contains("view") ? 7 : 3;
                for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
                    int v = black ? 2 : ((x * k / 20 + y / 15) % 2 == 0 ? 40 : 215);
                    px[y * w + x] = 0xFF000000 | v << 16 | (black ? v : 255 - v) << 8 | v;
                }
                return px;
            }
        }, pics, NOW, p, new Lab.Out() { public void line(String s) { log.append(s).append('\n'); } });
        String out = log.toString();
        if (System.getProperty("show") != null) System.out.println(out);
        check("lab run: one conclusion per picture", c.size() == 4);
        check("lab run: settings, six steps and a conclusion block", out.contains("settings: Balanced") && out.contains("STEP 6") && out.contains("== CONCLUSION =="));
        check("lab run: finds the identical pair", out.contains("IMG_a.jpg and IMG_a_copy.jpg: IDENTICAL"));
        String copy = verdict(c, "IMG_a_copy.jpg"), orig = verdict(c, "IMG_a.jpg");
        check("lab run: one of the identical pair is deleted by the plan, the other kept", (copy + orig).contains("DELETE (in the plan)")
                && (copy.equals("KEEP") || orig.equals("KEEP")) && (why(c, "IMG_a_copy.jpg") + why(c, "IMG_a.jpg")).contains("Exact duplicates"));
        check("lab run: estimate shown step by step for the black picture", out.contains("do you need it? estimate, step by step:") && out.contains("start 70"));
        check("lab run: says nothing was deleted", out.contains("nothing was deleted or changed"));
        check("lab run: the settings in use are not left in debug mode", !p.trace && !Policy.active.trace);
        for (java.io.File f : dir.listFiles()) f.delete();
        dir.delete();
    }

    static TextRules.Words words(Object... textThenConf) {
        TextRules.Words w = new TextRules.Words();
        for (int i = 0; i < textThenConf.length; i += 2) w.add((String) textThenConf[i], (Integer) textThenConf[i + 1]);
        return w;
    }

    /**
     * Marathi and Hindi greetings. The words are what the app's own reader (Tesseract, mar+hin, in Chromium) returned
     * for a real WhatsApp picture: a Diwali Padwa greeting with a lamp, 720 x 843, read at 360 px.
     */
    static void indic() throws Exception {
        final TextRules.Words padwa = words("6", 0, "बलिप्रतिपदा", 96, "व", 91, "'निजितत", 0, "मर्वांना", 74, "मंगलमय", 87, "शुभेच्छा.", 46, "७", 81, "र", 9,
                "र.", 47, "र»", 23, "2", 63, "टं", 38, "न", 13, ">>", 19, "७2%", 71, "आ", 35, "ळी", 68);
        TextRules.Verdict v = TextRules.classifyIndic(padwa, true);
        check("Marathi greeting words are recognised", PhotoCategory.GREETING.equals(v.kind) && v.why.contains("बलिप्रतिपदा") && v.why.contains("शुभेच्छ"));
        check("the same words on a plain page are not a greeting", TextRules.classifyIndic(padwa, false).kind == null);
        check("Hindi: शुभकामनाएं", PhotoCategory.GREETING.equals(TextRules.classifyIndic(words("दीपावली", 80, "की", 90, "हार्दिक", 85, "शुभकामनाएं", 70), true).kind));
        check("ordinary Marathi words are not a greeting", TextRules.classifyIndic(words("आज", 90, "बैठक", 88, "दुपारी", 80, "तीन", 85, "वाजता", 90), true).kind == null);
        check("badly read words do not count", TextRules.classifyIndic(words("शुभेच्छा", 20, "दिवाळी", 30), true).kind == null);
        check("a greeting word inside another word does not count", TextRules.classifyIndic(words("अशुभेच्छा", 90), true).kind == null);
        check("Lab: WhatsApp name -> chat image", Lab.areaFromName("IMG-20261110-WA0001.jpg").equals("wa_image"));
        check("Lab: camera and screenshot names", Lab.areaFromName("20261103_235249.jpg").equals("camera") && Lab.areaFromName("IMG_20260101_101010.jpg").equals("camera")
                && Lab.areaFromName("Screenshot_20260101.png").equals("screenshot") && Lab.areaFromName("poster.jpg").equals("download"));

        // ---- posters, memes and adverts: recognised by lettering and shape, without reading a word
        check("camera shapes: 4:3, 16:9, 4000x1848, a chat copy of a 16:9 photo", PhotoCategory.cameraShape(4000, 3000) && PhotoCategory.cameraShape(1080, 1920)
                && PhotoCategory.cameraShape(4000, 1848) && PhotoCategory.cameraShape(1599, 899));
        check("not camera shapes: 720x843, 1067x1280, 1206x793, 1170x1215", !PhotoCategory.cameraShape(720, 843) && !PhotoCategory.cameraShape(1067, 1280)
                && !PhotoCategory.cameraShape(1206, 793) && !PhotoCategory.cameraShape(1170, 1215));
        {
            Scanner.Result r = phone();
            String wa = "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/";
            FileItem meme = photo(r, wa + "IMG-meme.jpg", 200000, 20, "wa_image", 11, 50); meme.width = 1206; meme.height = 793;
            meme.category = PhotoCategory.POSTER; meme.faces = 3; meme.text = "knew thirty phone numbers heart can't find his way home without gps asks write thanks";
            FileItem advert = photo(r, wa + "IMG-ad.jpg", 300000, 20, "wa_image", 12, 50); advert.category = PhotoCategory.POSTER; advert.faces = 1; advert.text = "botsolar 8796383291 solarbot.in";
            FileItem card = photo(r, wa + "IMG-card.jpg", 300000, 20, "wa_image", 13, 50); card.category = PhotoCategory.POSTER; card.faces = 1; card.text = "happy birthday aarav";
            FileItem quote = photo(r, wa + "IMG-quote.jpg", 100000, 20, "wa_image", 14, 50); quote.category = PhotoCategory.POSTER; quote.faces = 0;
            FileItem fresh = photo(r, wa + "IMG-new.jpg", 100000, 3, "wa_image", 15, 50); fresh.category = PhotoCategory.POSTER; fresh.faces = 0;
            FileItem glitter = photo(r, wa + "IMG-diya.jpg", 98000, 13, "wa_image", 16, 50); glitter.category = PhotoCategory.GRAPHIC; glitter.faces = 0;
            FileItem saved = photo(r, "Pictures/poster.jpg", 100000, 200, "pictures", 17, 50); saved.category = PhotoCategory.POSTER; saved.faces = 0;
            UnitBuilder ub = new UnitBuilder(r, new Estimates(), 34, policy(Policy.BALANCED)), th = new UnitBuilder(r, new Estimates(), 34, policy(Policy.THOROUGH));
            Option m = opt(ub, meme.rel, Techniques.FORWARDS), ad = opt(ub, advert.rel, Techniques.FORWARDS), cd = opt(ub, card.rel, Techniques.FORWARDS),
                    q = opt(ub, quote.rel, Techniques.FORWARDS), g = opt(ub, glitter.rel, Techniques.FORWARDS);
            check("meme full of text, drawn faces in it: suggested", m != null && !m.review && m.evidence.contains("full of text"));
            check("advert with a person and a phone number: suggested", ad != null && !ad.review && ad.evidence.contains("an advert"));
            check("card with a face and few words (may be personal): for you to decide", cd != null && cd.review);
            check("quote poster without faces: suggested, your call, not in the default plan", q != null && !q.review && q.risk == Option.R4 && !q.safeStep());
            check("graphic without any readable text: suggested", g != null && !g.review);
            check("poster from three days ago: nothing yet", !anyDelete(ub, fresh.rel));
            check("poster the user saved to Pictures: nothing (only chat forwards are suggested)", !anyDelete(ub, saved.rel));
            check("Thorough: a faceless forward goes into the default plan, a card with a face never does",
                    opt(th, quote.rel, Techniques.FORWARDS).sure && !opt(th, card.rel, Techniques.FORWARDS).sure);
        }

        // ---- the whole run on a colourful picture with a headline: English reads nothing, Marathi reads the greeting
        java.io.File dir = java.nio.file.Files.createTempDirectory("indic").toFile();
        byte[] jpeg = new byte[56000]; new Random(9).nextBytes(jpeg); jpeg[0] = (byte) 0xFF; jpeg[1] = (byte) 0xD8; jpeg[2] = (byte) 0xFF;
        java.io.File f = new java.io.File(dir, "IMG-20261110-WA0001.jpg");
        java.nio.file.Files.write(f.toPath(), jpeg);
        for (final double ageDays : new double[]{10, 0.8}) {
            Lab.Pic pic = new Lab.Pic(f.getPath(), f.getName(), f.length(), NOW - (long) (ageDays * DAY));
            pic.area = Lab.areaFromName(pic.name);
            final StringBuilder log = new StringBuilder();
            final int[] reads = {0, 0};
            List<Lab.Conclusion> c = Lab.algorithm(new JvmFrames(2) {
                @Override public int[] argb(String path, int longEdge, int[] dims) {      // orange picture, a white headline of block letters across it
                    int w = Math.min(longEdge, 720) * 720 / 843, h = Math.min(longEdge, 720) * 843 / 843; h = Math.max(h, 1);
                    dims[0] = 720; dims[1] = 843; dims[2] = w; dims[3] = h;
                    int[] px = new int[w * h];
                    for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
                        boolean row = y > h / 12 && y < h / 12 + h / 14, letter = row && x > w / 8 && x < w * 7 / 8 && (x * 24 / w) % 2 == 0;
                        px[y * w + x] = letter ? 0xFFFFFFFF : 0xFF000000 | (150 + (x * 60 / w)) << 16 | (70 + (y * 50 / h)) << 8 | 30;
                    }
                    return px;
                }
                @Override public boolean imageSize(String path, int[] dims) { dims[0] = 720; dims[1] = 843; return true; }
                @Override public TextRules.Words readText(String path, int[] argb, int w, int h) { reads[0]++; return words("afeufeaen", 0, "a", 59, "wate", 23); }
                @Override public TextRules.Words readTextIndic(String path, int[] argb, int w, int h) { reads[1]++; return padwa; }
            }, java.util.Arrays.asList(pic), NOW, policy(Policy.BALANCED), new Lab.Out() { public void line(String s) { log.append(s).append('\n'); } });
            String out = log.toString();
            if (System.getProperty("show") != null) System.out.println(out);
            check("greeting run (" + ageDays + " d): English reader tried first, then Marathi/Hindi", reads[0] >= 1 && reads[1] == 1 && out.contains("Marathi/Hindi text says: Greetings & forwards"));
            check("greeting run (" + ageDays + " d): the conclusion says what it is", c.size() == 1 && c.get(0).what.startsWith("Greetings & forwards") && c.get(0).what.contains("बलिप्रतिपदा"));
            if (ageDays > 7) check("greeting, ten days old: suggested for deletion, with the estimate", c.get(0).verdict.startsWith("SUGGESTED TO DELETE")
                    && c.get(0).why.contains("estimate 0 of 100") && out.contains("-30  a greeting image"));
            else check("greeting from yesterday: kept for now, and the log says when it will be suggested", c.get(0).verdict.startsWith("KEEP")
                    && c.get(0).why.contains("once they are a week old"));
        }
        // the setting: with Marathi/Hindi reading off, it is not recognised
        Policy off = policy(Policy.BALANCED); off.readIndic = false;
        check("the setting is carried into the run", !off.copy().readIndic && new Policy().readIndic);
        f.delete(); dir.delete();
    }

    static String verdict(List<Lab.Conclusion> c, String name) { for (Lab.Conclusion x : c) if (x.name.equals(name)) return x.verdict; return ""; }
    static String why(List<Lab.Conclusion> c, String name) { for (Lab.Conclusion x : c) if (x.name.equals(name)) return x.why; return ""; }

    static class JvmFrames implements Platform {
        final int kind; JvmFrames(int kind) { this.kind = kind; }
        public int[] videoFrame(String path, long timeMs, int longEdge, int[] dims) {
            if (kind == 3) return null;
            int w = 160, h = 90; dims[0] = w; dims[1] = h;
            int[] px = new int[w * h];
            java.util.Random rnd = new java.util.Random(timeMs);
            int cx = rnd.nextInt(w), cy = rnd.nextInt(h);
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
                int v = kind == 0 ? rnd.nextInt(6) : kind == 1 ? 128 + rnd.nextInt(3) : (Math.abs(x - cx) + Math.abs(y - cy)) * 255 / (w + h);
                px[y * w + x] = 0xFF000000 | v << 16 | v << 8 | v;
            }
            return px;
        }
        public float[] faces(String path, int[] argb, int w, int h) { return new float[0]; }
        public String storageRoot() { return "/"; } public long totalBytes() { return 0; } public long freeBytes() { return 0; }
        public List<AppItem> apps() { return new ArrayList<AppItem>(); }
        public int[] luma(String path, int[] dims) { return null; } public int[] argb(String path, int longEdge, int[] dims) { return null; }
        public float[] otherFaces(String path, int[] argb, int w, int h) { return null; } public String otherFacesName() { return ""; }
        public TextRules.Words readText(String path, int[] argb, int w, int h) { return null; }
        public TextRules.Words readTextIndic(String path, int[] argb, int w, int h) { return null; }
        public boolean imageSize(String path, int[] dims) { return false; } public boolean video(FileItem f) { return false; }
        public long audioBitrate(String path) { return 0; } public String[] apkInfo(String path) { return null; }
        public int sdkInt() { return 34; } public long now() { return NOW; }
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
            UnitBuilder ub = new UnitBuilder(r, new Estimates(), 34, policy(t % 3));
            Planner.Plan all = Planner.bestWithin(ub.units, Option.R4);
            Planner.Plan goal = Planner.tiered(ub.units, Long.MAX_VALUE / 4);
            for (Planner.Plan p : new Planner.Plan[]{all, goal, Planner.bestWithin(ub.units, Option.R2), Planner.safe(ub.units)}) {
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
