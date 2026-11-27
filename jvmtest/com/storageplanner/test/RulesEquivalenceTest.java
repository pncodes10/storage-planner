package com.storageplanner.test;

import com.storageplanner.core.*;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The rule engine with the built-in rules must give exactly the units the old hard-coded UnitBuilder gave
 * (LegacyUnitBuilder, a copy from before the engine): same units in the same order, and for every option the
 * same action, technique, saved bytes, risk, exact flag and reason. Compared on many random scans that put files
 * just below, at and just above every threshold, and on the virtual phone when its folder is given.
 * Usage: RulesEquivalenceTest [virtual phone folder]
 */
public final class RulesEquivalenceTest {
    static final long NOW = 1792000000000L, DAY = 86400000L;
    static int units, options, files, scans, bad;

    static final String[] AREAS = {"camera", "screenshot", "pictures", "movies", "download", "music", "document", "trash", "thumbnails", "temp",
        "residue", "ext_cache", "ext_data", "wa_image", "wa_sent", "wa_video", "wa_sent_video", "wa_voice", "wa_doc", "wa_status", "wa_sticker",
        "wa_gif", "wa_old_backup", "wa_other", "telegram_media", "app_obb", "app_media", "user_other"};
    static final String[] TYPES = {"jpeg", "jpeg", "jpeg", "png", "png", "mp4", "mp4", "mkv", "mp3", "flac", "m4a", "ogg", "pdf", "zip", "webp", "heic", "avif", null};
    static final String[] CODECS = {null, "video/avc", "video/avc", "video/hevc", "video/mp4v-es", "video/3gpp", "video/av01", "VIDEO/AVC"};
    static final double[] AGES = {-3, 0, 0.5, 1, 2, 3, 6.99, 7, 8, 30, 89.99, 90, 91, 179.99, 180, 181, 365, 1000};

    static long ageMs(Random rnd, double days) { return Math.round(days * DAY) + rnd.nextInt(3) - 1; }

    static void fail(String s) { bad++; if (bad < 15) System.out.println("FAIL " + s); }

    static void same(Scanner.Result r, int sdk, Estimates est, String where) {
        LegacyUnitBuilder a = new LegacyUnitBuilder(r, est, sdk);
        UnitBuilder b = new UnitBuilder(r, est, sdk);
        scans++; files += r.files.size();
        if (a.units.size() != b.units.size()) { fail(where + ": unit count " + a.units.size() + " vs " + b.units.size()); return; }
        for (int i = 0; i < a.units.size(); i++) {
            Unit x = a.units.get(i), y = b.units.get(i);
            units++;
            if (!x.id.equals(y.id) || !x.title.equals(y.title) || !x.kind.equals(y.kind)) { fail(where + ": unit " + i + " " + x.id + " vs " + y.id); continue; }
            if (x.options.size() != y.options.size()) { fail(where + ": " + x.id + " options " + x.options.size() + " vs " + y.options.size()); continue; }
            for (int k = 0; k < x.options.size(); k++) {
                Option p = x.options.get(k), q = y.options.get(k);
                options++;
                if (!p.action.equals(q.action) || !p.technique.equals(q.technique) || p.saved != q.saved || p.risk != q.risk || p.exact != q.exact || !p.why.equals(q.why))
                    fail(where + ": " + x.id + " option " + k + ": " + p.action + "/" + p.technique + "/" + p.saved + "/R" + p.risk + "/" + p.exact + "/" + p.why
                            + "  vs  " + q.action + "/" + q.technique + "/" + q.saved + "/R" + q.risk + "/" + q.exact + "/" + q.why);
            }
        }
        // the detections are computed by the same code, but compare them too
        if (!a.det.duplicateOf.equals(b.det.duplicateOf) || !a.det.burstExtraOf.equals(b.det.burstExtraOf) || !a.det.blurry.equals(b.det.blurry)
                || !a.det.sentCopyOf.equals(b.det.sentCopyOf)) fail(where + ": detections differ");
    }

    static String summary(List<Unit> us) {
        StringBuilder b = new StringBuilder();
        for (Unit u : us) { b.append(u.id).append('|'); for (Option o : u.options) b.append(o.technique).append(o.saved).append(o.why).append(';'); b.append('\n'); }
        return b.toString();
    }

    static Scanner.Result scan(Random rnd) {
        Scanner.Result r = new Scanner.Result();
        r.now = NOW; r.total = 128L << 30; r.free = 10L << 30;
        // apps: some installed, installer versions around them
        String[] pkgs = {"com.a.one", "com.b.two", "com.c.three", "com.d.four", "com.e.five"};
        for (int i = 0; i < pkgs.length; i++) {
            if (rnd.nextInt(5) == 0) continue;                       // not installed
            AppItem a = new AppItem(pkgs[i], "App " + i);
            a.versionCode = 10 + rnd.nextInt(3); a.codeBytes = 1000000L * rnd.nextInt(500); a.dataBytes = 1000000L * rnd.nextInt(500);
            a.cacheBytes = rnd.nextInt(4) == 0 ? 0 : 1000000L * rnd.nextInt(100);
            a.lastUsed = NOW - (long) (rnd.nextDouble() * 200 * DAY); a.firstInstall = NOW - 400 * DAY;
            a.hasLauncher = rnd.nextInt(6) != 0; a.isGame = rnd.nextBoolean(); a.isSystem = rnd.nextInt(8) == 0; a.isEssential = rnd.nextInt(8) == 0;
            r.apps.add(a);
        }
        int n = 150 + rnd.nextInt(250);
        List<String> shas = new ArrayList<String>();
        for (int i = 0; i < 12; i++) shas.add("sha" + i);
        List<FileItem> cam = new ArrayList<FileItem>();
        for (int i = 0; i < n; i++) {
            String area = AREAS[rnd.nextInt(rnd.nextInt(3) == 0 ? AREAS.length : 8)];
            FileItem f;
            double age = AGES[rnd.nextInt(AGES.length)] + (rnd.nextInt(4) == 0 ? rnd.nextDouble() * 5 : 0);
            long size;
            switch (rnd.nextInt(6)) { case 0: size = 0; break; case 1: size = 999999 + rnd.nextInt(3); break; case 2: size = 1 + rnd.nextInt(3000); break;
                default: size = 1000 + (long) (rnd.nextDouble() * rnd.nextInt(60) * 1000000); }
            String type = TYPES[rnd.nextInt(TYPES.length)];
            if (area.equals("camera") && rnd.nextInt(3) > 0) type = "jpeg";
            if (area.equals("screenshot") && rnd.nextInt(3) > 0) type = "png";
            if (area.equals("music") && rnd.nextInt(3) > 0) type = rnd.nextBoolean() ? "mp3" : "m4a";
            String name = (rnd.nextInt(10) == 0 ? "Screenshot_" : "f") + i + (type == null ? "" : "." + type);
            f = new FileItem(area + "/" + (rnd.nextInt(30) == 0 ? "long/deep/folder/" : "") + name, "/sdcard/" + name, size, NOW - ageMs(rnd, age));
            f.area = area; f.type = type;
            if (rnd.nextInt(3) > 0) {                                  // picture size and bits per pixel around 0.5 and 1.0
                f.width = 400 + rnd.nextInt(5) * 1000; f.height = 300 + rnd.nextInt(4) * 700;
            }
            if (rnd.nextInt(8) == 0) f.pkg = "com.res." + rnd.nextInt(5);
            if (rnd.nextInt(3) == 0) f.sha256 = shas.get(rnd.nextInt(shas.size()));
            f.faces = rnd.nextInt(4) == 0 ? rnd.nextInt(4) : -1;
            if (rnd.nextInt(4) == 0) {
                f.category = rnd.nextInt(5) == 0 ? PhotoCategory.ID : PhotoCategory.ALL[rnd.nextInt(PhotoCategory.ALL.length)];
                if (rnd.nextInt(3) > 0) f.catDate = NOW - ageMs(rnd, new double[] {-2, 0, 3, 6.99, 7, 7.0001, 8, 40, 200}[rnd.nextInt(9)]);
                if (rnd.nextInt(3) == 0) f.catDate = f.catDate / DAY * DAY;       // UTC midnight like a date read from text
            }
            if (type != null && type.equals("apk")) f.apkPackage = null;
            if (rnd.nextInt(10) == 0) { f.apkPackage = pkgs[rnd.nextInt(pkgs.length)]; f.apkVersion = 9 + rnd.nextInt(5); f.type = rnd.nextBoolean() ? "zip" : null; }
            if (f.type != null && (f.type.equals("mp4") || f.type.equals("mkv") || area.equals("music") || rnd.nextInt(6) == 0)) {
                f.vcodec = CODECS[rnd.nextInt(CODECS.length)];
                if (f.width <= 0 && rnd.nextInt(5) > 0) { f.width = 1920; f.height = 1080; }
                if (area.equals("music") || f.type.equals("mp3") || f.type.equals("m4a") || f.type.equals("flac")) {
                    f.bitrate = new long[] {0, 1, 999, 128000, 255999, 256000, 320000, 1411000}[rnd.nextInt(8)];
                    if (rnd.nextInt(6) > 0) f.vcodec = null;
                } else {
                    double bppf = new double[] {0.01, 0.059, 0.06, 0.119, 0.12, 0.27, 0.5}[rnd.nextInt(7)];
                    f.bitrate = rnd.nextInt(8) == 0 ? 0 : (long) (bppf * Math.max(1, f.width) * Math.max(1, f.height) * 30) + rnd.nextInt(3) - 1;
                }
            }
            // as in a real scan: only pictures are sorted into categories, and in the music folder only audio files get a bitrate
            // (the rules' file types rely on this; a category on a video or a bitrate on a JPEG never occurs)
            if (!Tags.IMAGE.equals(Tags.kind(f))) { f.category = null; f.catDate = 0; }
            if (area.equals("music") && !Tags.AUDIO.equals(Tags.kind(f))) f.bitrate = 0;
            // what Mirage saw (1.11), on some sorted pictures: people (photo or drawn), something seen, a reference photo
            if (f.category != null && rnd.nextInt(3) == 0) {
                f.mirageVersion = AlgorithmV3.VERSION;
                f.mirageStyle = AlgorithmV3.STYLES[rnd.nextInt(AlgorithmV3.STYLES.length)];
                f.miragePeople = new int[] {0, 0, 1, 3}[rnd.nextInt(4)];
                f.mirageSees = f.miragePeople > 0 || rnd.nextBoolean();
                f.mirageReference = f.miragePeople == 0 && rnd.nextInt(3) == 0;
                f.mirageObjects = f.mirageReference ? "sign, price tag" : ""; f.mirageScene = ""; f.v3Category = f.category;
            }
            // what the frames of a video showed (1.12, VideoCheck), on some videos; lengths and fingerprints from small sets so
            // WhatsApp copies of camera videos turn up, and values on each threshold
            if (Tags.VIDEO.equals(Tags.kind(f)) && rnd.nextInt(3) > 0) {
                f.durationMs = new long[] {0, 1000, 2499, 2500, 2501, 30000, 180000, 180001, 600000}[rnd.nextInt(9)];
                if (rnd.nextInt(3) > 0) {
                    f.videoVersion = VideoCheck.VERSION; f.vFrames = rnd.nextInt(6); f.vDark = f.vFrames == 0 ? 0 : rnd.nextInt(f.vFrames + 1);
                    f.vMotion = new double[] {-1, 0, 2.99, 3, 20}[rnd.nextInt(5)]; f.vSharpMax = new double[] {-1, 0.5, 5, 60}[rnd.nextInt(4)];
                    f.vPeople = new int[] {-1, 0, 0, 1, 2}[rnd.nextInt(5)]; f.vWords = new int[] {-1, 0, 3, 4, 9}[rnd.nextInt(5)];
                    f.vHash = new long[] {0L, 0xFFFL, 0xFFFFFFL}[rnd.nextInt(3)]; f.vHashed = rnd.nextInt(4) > 0;
                    if (rnd.nextBoolean()) {                                   // what the index said (1.15)
                        f.vIndexed = true; f.vKeyframes = 1 + rnd.nextInt(30); f.vStill = new double[] {-1, 0, 0.79, 0.8, 1}[rnd.nextInt(5)];
                        f.vAudio = rnd.nextInt(3) - 1; f.vQuiet = f.vAudio == 1 ? new double[] {0, 0.89, 0.9, 1}[rnd.nextInt(4)] : -1;
                        if (rnd.nextInt(3) == 0) { f.vFrames = 0; f.vDark = 0; }            // the index was enough
                    }
                }
                if (rnd.nextInt(3) == 0) { int w = f.width; f.width = f.height; f.height = w; }      // upright ones
            }
            // size to land bits per pixel on a threshold
            if (f.width > 0 && rnd.nextInt(2) == 0) {
                double bpp = new double[] {0.1, 0.4999, 0.5, 0.5001, 1.0, 0.9999, 1.0001, 3}[rnd.nextInt(8)];
                FileItem g = new FileItem(f.rel, f.abs, (long) Math.ceil(bpp * f.width * f.height / 8.0) + (rnd.nextInt(3) - 1), f.mtime);
                g.area = f.area; g.type = f.type; g.sha256 = f.sha256; g.width = f.width; g.height = f.height; g.pkg = f.pkg; g.faces = f.faces;
                g.category = f.category; g.catDate = f.catDate; g.apkPackage = f.apkPackage; g.apkVersion = f.apkVersion;
                g.vcodec = f.vcodec; g.bitrate = f.bitrate;
                g.mirageVersion = f.mirageVersion; g.mirageSees = f.mirageSees; g.mirageReference = f.mirageReference; g.miragePeople = f.miragePeople;
                g.mirageStyle = f.mirageStyle; g.mirageObjects = f.mirageObjects; g.mirageScene = f.mirageScene; g.v3Category = f.v3Category;
                g.durationMs = f.durationMs; g.videoVersion = f.videoVersion; g.vFrames = f.vFrames; g.vDark = f.vDark; g.vMotion = f.vMotion;
                g.vSharpMax = f.vSharpMax; g.vPeople = f.vPeople; g.vWords = f.vWords; g.vHash = f.vHash; g.vHashed = f.vHashed;
                g.vIndexed = f.vIndexed; g.vKeyframes = f.vKeyframes; g.vStill = f.vStill; g.vAudio = f.vAudio; g.vQuiet = f.vQuiet;
                f = g;
            }
            r.files.add(f);
            if (area.equals("camera") && rnd.nextInt(2) == 0) { cam.add(f); }
        }
        // camera videos and the WhatsApp copies of some of them (1.12): length, orientation, time and fingerprint on each side of the limits
        for (int k = 0; k < 1 + rnd.nextInt(3); k++) {
            long dur = 5000 + rnd.nextInt(60000), t = NOW - (long) (rnd.nextDouble() * 300 * DAY);
            FileItem c = new FileItem("DCIM/Camera/VID_" + k + ".mp4", "/sdcard/VID_" + k + ".mp4", 20000000 + rnd.nextInt(9000000), t);
            c.area = "camera"; c.type = "mp4"; c.vcodec = "video/avc"; c.width = 1920; c.height = 1080; c.bitrate = 17000000; c.durationMs = dur;
            c.videoVersion = VideoCheck.VERSION; c.vFrames = 5; c.vPeople = rnd.nextInt(2); c.vWords = 0; c.vMotion = 20; c.vSharpMax = 60; c.vHash = rnd.nextLong(); c.vHashed = true;
            r.files.add(c);
            FileItem s = new FileItem("WhatsApp/Media/WhatsApp Video/Sent/VID-" + k + ".mp4", "/sdcard/s" + k + ".mp4", 3000000 + rnd.nextInt(900000),
                    t + (rnd.nextInt(4) == 0 ? -61000 : (long) (rnd.nextDouble() * 5 * DAY)));
            s.area = "wa_sent_video"; s.type = "mp4"; s.vcodec = "video/avc"; s.width = rnd.nextInt(4) == 0 ? 720 : 848; s.height = s.width == 720 ? 1280 : 480;
            s.bitrate = 1500000; s.durationMs = dur + new long[] {0, 400, 1500, 1501, -1500}[rnd.nextInt(5)];
            s.videoVersion = VideoCheck.VERSION; s.vFrames = 5; s.vPeople = c.vPeople; s.vWords = 0; s.vMotion = 20; s.vSharpMax = 30;
            s.vHashed = true; s.vHash = c.vHash ^ new long[] {0L, 0xFFFL, 0x1FFFL, -1L}[rnd.nextInt(4)];                 // 0, 12, 13 or 64 bits apart
            r.files.add(s);
        }
        // camera photos with features: bursts, blur, sent copies of them
        long base = NOW - 100 * DAY;
        int c = 0;
        for (int g = 0; g < 4 + rnd.nextInt(10); g++) {
            long ph = rnd.nextLong(); int w = rnd.nextBoolean() ? 4000 : 3000, h = w == 4000 ? 3000 : 4000;
            boolean withQ = rnd.nextInt(2) == 0;
            for (int k = 0; k < 1 + rnd.nextInt(5); k++) {
                FileItem p = new FileItem("DCIM/Camera/IMG_" + (c++) + ".jpg", "/sdcard/DCIM/Camera/x", 2000000 + rnd.nextInt(3000000),
                        base + g * 3600000L * 5 + k * 60000L * (1 + rnd.nextInt(5)));
                p.area = "camera"; p.type = "jpeg"; p.width = w; p.height = h;
                p.hasPhash = true; p.phash = ph ^ (1L << rnd.nextInt(60)) ^ (rnd.nextInt(4) == 0 ? (1L << rnd.nextInt(60)) : 0);
                p.sharp = rnd.nextInt(8) == 0 ? 0.5 : 5 + rnd.nextDouble() * 100;
                if (withQ) p.quality = quality(rnd);
                r.files.add(p);
                if (rnd.nextInt(3) == 0) {                              // the chat's compressed copy of it
                    FileItem s = new FileItem("WhatsApp/Sent/IMG-" + (c++) + ".jpg", "/sdcard/WA", 200000, NOW - 50 * DAY);
                    s.area = "wa_sent"; s.type = "jpeg"; s.hasPhash = true; s.phash = p.phash ^ (rnd.nextInt(3) == 0 ? 0 : 0xFFL);
                    s.width = w / 4; s.height = h / 4 + (rnd.nextInt(4) == 0 ? 40 : 0);
                    r.files.add(s);
                }
            }
        }
        for (int k = 0; k < 20; k++) {                                  // lots of other camera photos so the median sharpness is stable
            FileItem p = new FileItem("DCIM/Camera/O_" + k + ".jpg", "/sdcard/o", 3000000, NOW - 300 * DAY - k * 7200000L);
            p.area = "camera"; p.type = "jpeg"; p.width = 4000; p.height = 3000; p.hasPhash = true; p.phash = rnd.nextLong(); p.sharp = 40 + rnd.nextDouble() * 60;
            r.files.add(p);
        }
        return r;
    }

    static PhotoQuality.Q quality(Random rnd) {
        PhotoQuality.Q q = new PhotoQuality.Q();
        q.faces = rnd.nextInt(4); q.openFaces = q.faces == 0 ? 0 : rnd.nextInt(q.faces + 1);
        q.face = new float[q.faces * 5];
        for (int i = 0; i < q.faces; i++) { q.face[i * 5] = 0.5f; q.face[i * 5 + 1] = 0.5f; q.face[i * 5 + 2] = 0.1f; q.face[i * 5 + 3] = rnd.nextFloat(); q.face[i * 5 + 4] = 0.9f; }
        q.eyes = q.faces == 0 ? -1 : rnd.nextDouble(); q.faceSharp = q.faces == 0 ? -1 : rnd.nextDouble() * 100;
        q.faceLuma = q.faces == 0 ? -1 : rnd.nextDouble() * 255;
        q.sharp = rnd.nextDouble() * 100; q.meanLuma = rnd.nextDouble() * 255; q.clipHi = rnd.nextDouble() * 0.1; q.clipLo = rnd.nextDouble() * 0.1;
        return q;
    }

    public static void main(String[] args) {
        Estimates[] ests = {new Estimates(), new Estimates()};
        ests[1].cameraPhoto = 0.5; ests[1].chatImage = 0.33; ests[1].screenshotLossless = 0.17;
        ests[1].photoSource = "measured on 12 of your photos"; ests[1].chatSource = "measured on 5 of your chat images"; ests[1].screenshotSource = "measured";
        for (int seed = 0; seed < 400; seed++) {
            Random rnd = new Random(seed);
            same(scan(rnd), new int[] {33, 34, 35}[seed % 3], ests[seed % 2], "random scan " + seed);
        }
        System.out.println("  random scans: " + scans + ", " + files + " files, " + units + " units, " + options + " options compared");
        // teeth: with any one built-in rule switched off the result must differ from the old code somewhere (so every rule was exercised)
        int untested = 0;
        for (Rule rule : RuleSet.defaults().rules) {
            if (rule.id.equals("keep_favourites")) continue;           // new since the old code: checked on its own below
            boolean differs = false;
            for (int seed = 0; seed < 80 && !differs; seed++) {
                Scanner.Result r = scan(new Random(seed));
                RuleSet rs = RuleSet.defaults();
                rs.find(rule.id).enabled = false;
                differs = !summary(new LegacyUnitBuilder(r, ests[0], 34).units).equals(summary(new UnitBuilder(r, ests[0], 34, rs).units));
            }
            if (!differs) { untested++; fail("rule " + rule.id + " is never exercised by the random scans"); }
        }
        System.out.println("  every one of the " + RuleSet.defaults().rules.size() + " built-in rules changes the result when switched off: " + (untested == 0));
        // favourites (new since the old code): a favourite gets no option at all; every other file exactly as before
        int favs = 0, favWithOld = 0;
        for (int seed = 0; seed < 40; seed++) {
            Scanner.Result r = scan(new Random(seed));
            java.util.Set<String> fav = new java.util.HashSet<String>();
            for (int i = 0; i < r.files.size(); i++) if (i % 7 == 0) { r.files.get(i).favourite = true; fav.add(r.files.get(i).rel); }
            List<Unit> old = new LegacyUnitBuilder(r, ests[0], 34).units, now = new UnitBuilder(r, ests[0], 34).units;
            java.util.Map<String, Unit> nowById = new java.util.HashMap<String, Unit>();
            for (Unit u : now) nowById.put(u.id, u);
            for (Unit u : old) {
                Unit n = nowById.get(u.id);
                if (fav.contains(u.id)) {
                    favs++;
                    if (!u.options.isEmpty()) favWithOld++;
                    if (n != null && !n.options.isEmpty()) fail("favourite " + u.id + " still gets an option");
                } else if (n == null || !summary(java.util.Collections.singletonList(u)).equals(summary(java.util.Collections.singletonList(n))))
                    fail("non-favourite " + u.id + " changed");
            }
        }
        if (favWithOld == 0) fail("rule keep_favourites is never exercised by the random scans");
        System.out.println("  favourites: " + favs + " favourite files (" + favWithOld + " had an option before) get none; the rest unchanged");
        if (args.length > 0 && new File(args[0]).isDirectory()) {
            JvmPlatform pf = new JvmPlatform(args[0]);
            Scanner.Result r = Scanner.scan(pf, new Scanner.Progress() {
                public void update(String s, int d, int t) {} public boolean cancelled() { return false; } });
            int u0 = units, o0 = options, f0 = files;
            same(r, pf.sdkInt(), new Estimates(), "virtual phone");
            System.out.println("  virtual phone: " + (files - f0) + " files, " + (units - u0) + " units, " + (options - o0) + " options compared");
        } else System.out.println("  (no virtual phone folder given)");
        System.out.println(bad == 0 ? "RulesEquivalenceTest: all " + options + " options in " + units + " units identical" : "RulesEquivalenceTest: " + bad + " DIFFERENCES");
    }
}
