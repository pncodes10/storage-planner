package com.storageplanner.test;

import com.storageplanner.core.*;

import java.util.Random;

/**
 * File tags: source names, unknown stays unknown (faces, blur, picture measurements), the blur percentile among
 * your own camera photos, brightness and text from the picture measurements, and the tags that come from the
 * planner's detections (duplicate, burst, sent copy, installers), taken from a real UnitBuilder on a small
 * synthetic scan. No virtual phone needed.
 */
public final class TagsTest {
    static int checks, fails;
    static final long NOW = 1792000000000L, DAY = 86400000L;

    static void check(String what, boolean ok) {
        checks++;
        if (!ok) { fails++; System.out.println("FAIL " + what); }
    }

    static FileItem file(String rel, String area, String type, long size, long ageDays) {
        FileItem f = new FileItem(rel, "/sdcard/" + rel, size, NOW - ageDays * DAY);
        f.area = area; f.type = type;
        return f;
    }

    static FileItem cam(String name, double sharp, long phash, long ageMinutes) {
        FileItem f = new FileItem("DCIM/Camera/" + name, "/sdcard/DCIM/Camera/" + name, 3000000, NOW - 40 * DAY + ageMinutes * 60000L);
        f.area = "camera"; f.type = "jpeg"; f.width = 4000; f.height = 3000;
        f.hasPhash = true; f.phash = phash; f.sharp = sharp;
        return f;
    }

    static Tags.Context ctxOf(Scanner.Result r) { return Tags.context(r, new UnitBuilder.Detections()); }

    public static void main(String[] args) {
        // ---- source names and kind
        String[][] src = {{"camera", "jpeg", "camera photo", "image"}, {"camera", "mp4", "camera video", "video"}, {"screenshot", "png", "screenshot", "image"},
            {"wa_image", "jpeg", "chat image (received)", "image"}, {"wa_sent", "jpeg", "chat image (sent)", "image"}, {"wa_video", "mp4", "chat video (received)", "video"},
            {"download", "pdf", "download", "document"}, {"pictures", "png", "pictures folder", "image"}, {"music", "mp3", "music", "audio"},
            {"document", "pdf", "document", "document"}, {"telegram_media", "jpeg", "telegram", "image"}, {"trash", "jpeg", "trash", "image"},
            {"thumbnails", "other", "thumbnail cache", "other"}, {"user_other", "other", "other", "other"}, {"weird_area", "bin", "weird_area", "other"}};
        Scanner.Result empty = new Scanner.Result();
        Tags.Context none = ctxOf(empty);
        for (String[] s : src) {
            FileItem f = file("x/a." + s[1], s[0], s[1], 1000, 3);
            Tags.T t = Tags.of(f, none, NOW);
            check("source of " + s[0] + "/" + s[1] + " = " + t.source, s[2].equals(t.source));
            check("kind of " + s[1] + " = " + t.kind, s[3].equals(t.kind));
            check("raw area kept for " + s[0], s[0].equals(t.area));
        }
        FileItem apk = file("Download/a.apk", "download", "zip", 5000, 1);
        check("an .apk zip is kind apk", Tags.APK.equals(Tags.of(apk, none, NOW).kind));

        // ---- basics
        FileItem b = file("Download/x.png", "download", "png", 123456, 30);
        b.width = 2000; b.height = 1000;
        Tags.T t = Tags.of(b, none, NOW);
        check("size", t.sizeBytes == 123456);
        check("age in days (from the modified date)", Math.abs(t.ageDays - 30) < 1e-6);
        check("megapixels", Math.abs(t.megapixels - 2.0) < 1e-9 && t.width == 2000 && t.height == 1000);
        FileItem noDims = file("Download/y.zip", "download", "zip", 10, 0);
        Tags.T td = Tags.of(noDims, none, NOW);
        check("no dimensions = unknown, not 0", td.width == Tags.UNKNOWN && td.megapixels < 0 && !td.get(Tags.MEGAPIXELS).known);
        FileItem future = file("Download/z.zip", "download", "zip", 10, -5);
        check("a modified date in the future is age 0", Tags.of(future, none, NOW).ageDays == 0);

        // ---- faces: unknown is never 0
        FileItem p = file("Pictures/p.jpg", "pictures", "jpeg", 10, 1);
        p.category = PhotoCategory.PHOTO;
        check("faces -1 stays unknown", Tags.of(p, none, NOW).faces == Tags.UNKNOWN && !Tags.of(p, none, NOW).get(Tags.FACES).known);
        p.faces = 0;
        check("faces 0 is known and 0", Tags.of(p, none, NOW).faces == 0 && Tags.of(p, none, NOW).get(Tags.FACES).known
                && "0".equals(Tags.of(p, none, NOW).get(Tags.FACES).value));
        p.faces = 3;
        check("faces 3", Tags.of(p, none, NOW).faces == 3);

        // ---- category, text, code
        FileItem u = file("Pictures/u.jpg", "pictures", "jpeg", 10, 1);
        Tags.T tu = Tags.of(u, none, NOW);
        check("not sorted: category, text and code unknown", tu.category == null && tu.textRead == null && tu.hasCode == null);
        u.category = PhotoCategory.RECEIPT; u.textPending = true;
        check("text not read yet", Tags.TEXT_NOT_YET.equals(Tags.of(u, none, NOW).textRead));
        u.textPending = false; u.text = "total 120";
        check("text read", Tags.TEXT_HAS.equals(Tags.of(u, none, NOW).textRead));
        u.text = null;
        check("no text", Tags.TEXT_NONE.equals(Tags.of(u, none, NOW).textRead) && Boolean.FALSE.equals(Tags.of(u, none, NOW).hasCode));
        u.code = "payment link";
        check("has code", Boolean.TRUE.equals(Tags.of(u, none, NOW).hasCode));

        // ---- ID document
        FileItem id = file("Pictures/id.jpg", "pictures", "jpeg", 10, 1);
        check("not an ID before sorting", !Tags.of(id, none, NOW).idDocument);
        id.category = PhotoCategory.ID;
        check("ID document", Tags.of(id, none, NOW).idDocument);

        // ---- screenshot, chat media, camera photo
        FileItem ss = file("DCIM/Camera/Screenshot_1.png", "camera", "png", 10, 1);
        check("screenshot by name", Tags.of(ss, none, NOW).screenshot);
        check("screenshot by folder", Tags.of(file("Pictures/Screenshots/a.png", "screenshot", "png", 10, 1), none, NOW).screenshot);
        check("chat media", Tags.of(file("a", "wa_image", "jpeg", 1, 1), none, NOW).chatMedia && Tags.of(file("a", "telegram_media", "jpeg", 1, 1), none, NOW).chatMedia
                && !Tags.of(file("a", "camera", "jpeg", 1, 1), none, NOW).chatMedia);
        check("camera photo, not camera video", Tags.of(file("a", "camera", "jpeg", 1, 1), none, NOW).cameraPhoto && !Tags.of(file("a", "camera", "mp4", 1, 1), none, NOW).cameraPhoto);

        // ---- picture measurements
        FileItem m = file("Pictures/m.jpg", "pictures", "jpeg", 10, 1);
        Tags.T t0 = Tags.of(m, none, NOW);
        check("no measurements = all unknown", t0.brightness == Tags.UNKNOWN && t0.darkShare == Tags.UNKNOWN && t0.colourfulness == Tags.UNKNOWN
                && t0.graphicShare == Tags.UNKNOWN && t0.paperShare == Tags.UNKNOWN && t0.textAmount == null
                && !t0.get(Tags.BRIGHTNESS).known && !t0.get(Tags.TEXT_AMOUNT).known);
        ImageSignals.S s = new ImageSignals.S();
        s.meanLuma = 127.5; s.darkFrac = 0.2; s.colorful = 23.4; s.flat = 0.35; s.paper = 0.07; s.textBlobs = 5;
        m.signals = s;
        Tags.T t1 = Tags.of(m, none, NOW);
        check("pictures measured before the page check: paper, ink and page are unknown (not the old absolute share)", t1.paperShare == Tags.UNKNOWN
                && t1.inkShare == Tags.UNKNOWN && t1.looksLikePage == null && !t1.get(Tags.LOOKS_LIKE_PAGE).known && !t1.get(Tags.INK_SHARE).known);
        s.whiteLevel = 160; s.pagePaper = 0.07; s.smooth = -1; s.ink = -1; s.inkBands = -1;       // measured, but stopped at an early check
        t1 = Tags.of(m, none, NOW);
        check("paper share is the relative page paper share; ink unknown when not measured; not a page", t1.paperShare == 7 && t1.inkShare == Tags.UNKNOWN
                && Boolean.FALSE.equals(t1.looksLikePage) && Tags.of(m, none, NOW).get(Tags.LOOKS_LIKE_PAGE).value.equals("no"));
        s.whiteLevel = 161; s.pagePaper = 0.93; s.smooth = 0.9; s.ink = 0.027; s.inkBands = 7; s.colorful = 5;
        t1 = Tags.of(m, none, NOW);
        check("page: paper 93, ink 3, looks like a page", t1.paperShare == 93 && t1.inkShare == 3 && Boolean.TRUE.equals(t1.looksLikePage)
                && Boolean.TRUE.equals(t1.flagTag(Tags.LOOKS_LIKE_PAGE)) && t1.numTag(Tags.INK_SHARE) == 3 && t1.numTag(Tags.PAPER_SHARE) == 93);
        s.colorful = 23.4; s.pagePaper = 0.07; t1 = Tags.of(m, none, NOW);
        check("brightness 127.5/255 = 50", t1.brightness == 50);
        check("dark share 20", t1.darkShare == 20);
        check("glare not measured (sorted before glare existed) = unknown", t1.glareShare == Tags.UNKNOWN && !t1.get(Tags.GLARE_SHARE).known);
        s.glare = 0.194; t1 = Tags.of(m, none, NOW);
        check("glare share 19", t1.glareShare == 19 && t1.numTag(Tags.GLARE_SHARE) == 19 && Tags.typeOf(Tags.GLARE_SHARE) == Tags.Type.NUMBER);
        s.glare = 0; check("no glare = 0 (known)", Tags.of(m, none, NOW).glareShare == 0 && Tags.of(m, none, NOW).get(Tags.GLARE_SHARE).known);
        check("colourfulness 23", t1.colourfulness == 23);
        check("graphic share 35, paper 7", t1.graphicShare == 35 && t1.paperShare == 7);
        check("text amount none", Tags.AMOUNT_NONE.equals(t1.textAmount));
        s.meanLuma = 0; s.darkFrac = 1;
        check("a black picture has brightness 0 (known), dark 100", Tags.of(m, none, NOW).brightness == 0 && Tags.of(m, none, NOW).get(Tags.BRIGHTNESS).known
                && Tags.of(m, none, NOW).darkShare == 100);
        s.textBlobs = 30; s.textArea = 0.01; s.textLines = 2;
        check("text amount some", Tags.AMOUNT_SOME.equals(Tags.of(m, none, NOW).textAmount));
        s.textBlobs = 90; s.textLines = 6;
        check("text amount lots", Tags.AMOUNT_LOTS.equals(Tags.of(m, none, NOW).textAmount));

        // ---- eyes closed (only when the photo was compared in a burst)
        FileItem e = cam("e.jpg", 100, 1, 0);
        check("eyes unknown without quality", Tags.of(e, none, NOW).eyesClosed == null && !Tags.of(e, none, NOW).get(Tags.EYES_CLOSED).known);
        e.quality = new PhotoQuality.Q(); e.quality.faces = 2; e.quality.openFaces = 1;
        check("one of two blinking = eyes closed", Boolean.TRUE.equals(Tags.of(e, none, NOW).eyesClosed));
        e.quality.openFaces = 2;
        check("all open = not closed", Boolean.FALSE.equals(Tags.of(e, none, NOW).eyesClosed));
        e.quality.faces = 0; e.quality.openFaces = 0;
        check("no faces = not closed", Boolean.FALSE.equals(Tags.of(e, none, NOW).eyesClosed));
        check("no faces: eyes-open count unknown", Tags.of(e, none, NOW).eyesOpen == Tags.UNKNOWN);
        FileItem s2 = new FileItem("DCIM/Camera/s2.jpg", "/x/s2.jpg", 1000, NOW);
        s2.area = "camera"; s2.type = "jpeg"; s2.faces = 3;
        check("eyes not looked at: unknown", Tags.of(s2, none, NOW).eyesClosed == null && Tags.of(s2, none, NOW).eyesOpen == Tags.UNKNOWN);
        s2.eyeFaces = 3; s2.eyesOpen = 3;
        check("sorter: all three open", Tags.of(s2, none, NOW).eyesOpen == 3 && Boolean.FALSE.equals(Tags.of(s2, none, NOW).eyesClosed));
        s2.eyesOpen = 2;
        check("sorter: one closed", Tags.of(s2, none, NOW).eyesOpen == 2 && Boolean.TRUE.equals(Tags.of(s2, none, NOW).eyesClosed));
        check("eyes-open tag shown", Tags.of(s2, none, NOW).get(Tags.EYES_OPEN).value.equals("2"));
        s2.faces = 0; s2.eyeFaces = -1; s2.eyesOpen = -1;
        check("sorter: no faces = not closed", Boolean.FALSE.equals(Tags.of(s2, none, NOW).eyesClosed));

        // ---- blur: percentile among your own camera photos
        Scanner.Result r20 = new Scanner.Result();
        Random rnd = new Random(5);
        for (int i = 1; i <= 20; i++) r20.files.add(cam("c" + i + ".jpg", 100.0 * i, rnd.nextLong(), i * 60));
        Tags.Context c20 = ctxOf(r20);
        int[] exp = new int[21];
        for (int i = 1; i <= 20; i++) exp[i] = (int) Math.round(100 - 5.0 * (i - 0.5));      // mid-rank percentile
        boolean all = true;
        for (int i = 1; i <= 20; i++) all &= Tags.of(r20.files.get(i - 1), c20, NOW).blur == exp[i];
        check("20 camera photos: blur = 100 - mid-rank percentile (sharpest 3, softest 98)", all
                && Tags.of(r20.files.get(0), c20, NOW).blur == 98 && Tags.of(r20.files.get(19), c20, NOW).blur == 3);
        check("blur is higher for the softer photo", Tags.of(r20.files.get(2), c20, NOW).blur > Tags.of(r20.files.get(12), c20, NOW).blur);
        check("a photo with no sharpness has unknown blur", Tags.of(cam("n.jpg", -1, 7, 5), c20, NOW).blur == Tags.UNKNOWN);
        FileItem shot = file("Pictures/s.jpg", "pictures", "jpeg", 10, 1); shot.sharp = 300;
        check("blur of a non-camera picture is computed against the camera photos only when it has a sharpness", Tags.of(shot, c20, NOW).blur != Tags.UNKNOWN);
        FileItem noSharp = file("Pictures/t.jpg", "pictures", "jpeg", 10, 1);
        check("pictures without ImageFeatures sharpness have unknown blur", Tags.of(noSharp, c20, NOW).blur == Tags.UNKNOWN);
        Scanner.Result r9 = new Scanner.Result();
        for (int i = 1; i < Tags.MIN_CAMERA_PHOTOS; i++) r9.files.add(cam("d" + i + ".jpg", 100.0 * i, rnd.nextLong(), i * 60));
        check("fewer than " + Tags.MIN_CAMERA_PHOTOS + " camera photos: blur unknown", Tags.of(r9.files.get(0), ctxOf(r9), NOW).blur == Tags.UNKNOWN);
        r9.files.add(cam("d10.jpg", 5000, rnd.nextLong(), 700));
        check("exactly " + Tags.MIN_CAMERA_PHOTOS + " camera photos: blur known", Tags.of(r9.files.get(0), ctxOf(r9), NOW).blur == 95);

        Scanner.Result rt = new Scanner.Result();
        for (int i = 0; i < 10; i++) rt.files.add(cam("t" + i + ".jpg", 250, rnd.nextLong(), i * 60));
        check("ten equally sharp photos: ties count half, blur 50", Tags.of(rt.files.get(0), ctxOf(rt), NOW).blur == 50);

        // ---- detections from a real UnitBuilder on a small scan
        Scanner.Result r = new Scanner.Result();
        r.now = NOW; r.total = 100000000000L; r.free = 1000000000L;
        for (int i = 1; i <= 14; i++) r.files.add(cam("p" + i + ".jpg", 1000 + 10 * i, rnd.nextLong(), i * 120));      // spread out: no bursts
        long bp = rnd.nextLong();
        FileItem burstA = cam("burstA.jpg", 1500, bp, 3000), burstB = cam("burstB.jpg", 1100, bp ^ 0b11, 3003);         // 3 minutes apart, nearly same picture
        r.files.add(burstA); r.files.add(burstB);
        FileItem soft = cam("soft.jpg", 5, rnd.nextLong(), 4000);                                                      // far below a quarter of the median
        r.files.add(soft);
        FileItem orig = r.files.get(3);                                                                                 // p4, sent to a friend on WhatsApp
        FileItem sent = file("WhatsApp/Media/WhatsApp Images/Sent/IMG-1.jpg", "wa_sent", "jpeg", 200000, 39);
        sent.hasPhash = true; sent.phash = orig.phash ^ 0b101; sent.width = 1600; sent.height = 1200;
        r.files.add(sent);
        FileItem dupA = r.files.get(5), dupB = file("Download/copy.jpg", "download", "jpeg", dupA.size, 20);
        dupA.sha256 = dupB.sha256 = "abc123";
        r.files.add(dupB);
        AppItem inst = new AppItem("com.example.installed", "Installed"); inst.versionCode = 10;
        r.apps.add(inst);
        FileItem apkOld = file("Download/old.apk", "download", "zip", 9000, 2); apkOld.apkPackage = "com.example.installed"; apkOld.apkVersion = 9;
        FileItem apkSame = file("Download/same.apk", "download", "zip", 9000, 2); apkSame.apkPackage = "com.example.installed"; apkSame.apkVersion = 10;
        FileItem apkNew = file("Download/new.apk", "download", "zip", 9000, 2); apkNew.apkPackage = "com.example.installed"; apkNew.apkVersion = 11;
        FileItem apkOther = file("Download/other.apk", "download", "zip", 9000, 2); apkOther.apkPackage = "com.example.missing"; apkOther.apkVersion = 1;
        r.files.add(apkOld); r.files.add(apkSame); r.files.add(apkNew); r.files.add(apkOther);
        UnitBuilder ub = new UnitBuilder(r, new Estimates(), 34);
        Tags.Context ctx = Tags.context(r, ub.det);
        Tags.T tA = Tags.of(burstA, ctx, NOW), tB = Tags.of(burstB, ctx, NOW);
        check("planner found the burst", ub.det.burstExtraOf.containsKey(burstB.rel) && ub.det.burstExtraOf.get(burstB.rel).equals(burstA.rel));
        check("burst extra + its better shot", tB.burstExtra && burstA.rel.equals(tB.burstKeptShot) && !tB.burstKeeper);
        check("burst keeper", tA.burstKeeper && !tA.burstExtra && tA.burstKeptShot == null);
        check("planner found the soft photo", ub.det.blurry.contains(soft.rel));
        Tags.T tS = Tags.of(soft, ctx, NOW);
        check("blurry vs your photos + high blur score", tS.blurry && tS.blur >= 90);
        check("a sharp photo is not blurry and has a low blur score", !Tags.of(r.files.get(13), ctx, NOW).blurry && Tags.of(r.files.get(13), ctx, NOW).blur <= 30);
        Tags.T tSent = Tags.of(sent, ctx, NOW);
        check("planner found the sent copy", ub.det.sentCopyOf.containsKey(sent.rel));
        check("sent copy + its original", tSent.sentCopy && orig.rel.equals(tSent.sentCopyOf) && !Tags.of(orig, ctx, NOW).sentCopy);
        check("sent copy is a chat image", tSent.chatMedia && "chat image (sent)".equals(tSent.source));
        check("planner found the duplicate", ub.det.duplicateOf.size() == 1);
        String dupRel = ub.det.duplicateOf.keySet().iterator().next();
        FileItem dupFile = dupRel.equals(dupA.rel) ? dupA : dupB;
        FileItem keepFile = dupFile == dupA ? dupB : dupA;
        check("exact duplicate + its twin", Tags.of(dupFile, ctx, NOW).duplicate && keepFile.rel.equals(Tags.of(dupFile, ctx, NOW).duplicateOf)
                && !Tags.of(keepFile, ctx, NOW).duplicate);
        check("older installer, app already installed (same rule as the planner)", Tags.of(apkOld, ctx, NOW).installerAlreadyInstalled && !Tags.of(apkOld, ctx, NOW).installerNotInstalled);
        check("same-version installer is already installed", Tags.of(apkSame, ctx, NOW).installerAlreadyInstalled);
        check("newer installer: app not up to date", Tags.of(apkNew, ctx, NOW).installerNotInstalled && !Tags.of(apkNew, ctx, NOW).installerAlreadyInstalled);
        check("installer for a missing app", Tags.of(apkOther, ctx, NOW).installerNotInstalled);
        check("installer tags match the planner's choices", has(ub, apkOld, Techniques.INSTALLED_APK) && has(ub, apkSame, Techniques.INSTALLED_APK)
                && has(ub, apkNew, Techniques.OLD_DOWNLOADS) && has(ub, apkOther, Techniques.OLD_DOWNLOADS));
        check("a photo has no installer tags", !Tags.of(orig, ctx, NOW).installerAlreadyInstalled && !Tags.of(orig, ctx, NOW).installerNotInstalled);

        // ---- listing
        Tags.T all1 = Tags.of(burstB, ctx, NOW);
        boolean ids = true;
        java.util.Set<String> seen = new java.util.HashSet<String>();
        for (Tags.Entry en : all1.all()) ids &= seen.add(en.id) && en.id.matches("[a-z_]+") && en.label.length() > 0;
        check("74 unique lower_snake ids", ids && seen.size() == 74 && all1.get(Tags.BLUR) != null);
        boolean typed = true;                                   // every listed id has a type, and the typed accessors agree with it
        for (Tags.Entry en : all1.all()) {
            Tags.Type ty = Tags.typeOf(en.id);
            typed &= ty != null && (ty == Tags.Type.FLAG) == en.flag;
            if (ty == Tags.Type.NUMBER) typed &= (all1.numTag(en.id) >= 0) == en.known;
            if (ty == Tags.Type.TEXT) typed &= (all1.textTag(en.id) != null) == en.known;
            if (ty == Tags.Type.FLAG) typed &= (all1.flagTag(en.id) != null) == en.known;
        }
        check("every tag has a type; the typed readers match known/unknown", typed && Tags.typeOf("nonsense") == null);

        // ---- tags added for the rule engine
        FileItem big = file("DCIM/Camera/big.jpg", "camera", "jpeg", 3000000, 10);
        big.width = 2000; big.height = 1500; big.vcodec = "video/avc"; big.bitrate = 12345678; big.apkPackage = "com.x";
        Tags.T bt = Tags.of(big, ctx, NOW);
        check("file_type, size_mb, bits_per_pixel, video_codec, bitrate_kbps, apk_package",
                "jpeg".equals(bt.fileType) && Math.abs(bt.sizeMb - 3.0) < 1e-9 && Math.abs(bt.bitsPerPixel - Estimates.bpp(big)) < 1e-12
                && "video/avc".equals(bt.videoCodec) && Math.abs(bt.bitrateKbps - 12345.678) < 1e-6 && "com.x".equals(bt.apkPackage));
        FileItem bare = file("x/y.bin", "user_other", null, 100, 1);
        Tags.T nt = Tags.of(bare, ctx, NOW);
        check("no type, picture size, codec, bitrate, date: all unknown (never 0)",
                nt.fileType == null && nt.bitsPerPixel == Tags.UNKNOWN && nt.videoCodec == null && nt.bitrateKbps == Tags.UNKNOWN
                && nt.dateInPicture == null && nt.daysSinceDate == Tags.UNKNOWN && nt.apkPackage == null);
        FileItem tk = file("Pictures/ticket.jpg", "pictures", "jpeg", 100, 30);
        tk.category = PhotoCategory.TICKET; tk.catDate = NOW - 10 * DAY;
        Tags.T tt = Tags.of(tk, ctx, NOW);
        check("date read in a picture: flag yes and days since", Boolean.TRUE.equals(tt.dateInPicture) && Math.abs(tt.daysSinceDate - 10) < 1e-9);
        tk.catDate = 0;
        tt = Tags.of(tk, ctx, NOW);
        check("sorted picture with no date: flag no, days unknown", Boolean.FALSE.equals(tt.dateInPicture) && tt.daysSinceDate == Tags.UNKNOWN);
        tk.catDate = NOW + 5 * DAY;
        check("a date still ahead counts as 0 days, not unknown", Tags.of(tk, ctx, NOW).daysSinceDate == 0);
        boolean shownOk = true;
        for (Tags.Entry en : all1.shown()) shownOk &= en.known && (!en.flag || en.on);
        check("shown() has only known tags, and yes/no tags only when yes", shownOk);
        check("shown() has the burst tag", all1.get(Tags.BURST_EXTRA).on && shownHas(all1, Tags.BURST_EXTRA) && !shownHas(all1, Tags.BURST_KEEPER));

        System.out.println((checks - fails) + "/" + checks + " tag checks pass");
        if (fails > 0) System.exit(1);
    }

    static boolean shownHas(Tags.T t, String id) {
        for (Tags.Entry en : t.shown()) if (en.id.equals(id)) return true;
        return false;
    }

    static boolean has(UnitBuilder ub, FileItem f, String technique) {
        for (Unit un : ub.units) if (un.id.equals("file:" + f.rel) || un.id.endsWith(f.rel)) for (Option o : un.options) if (o.technique.equals(technique)) return true;
        return false;
    }
}
