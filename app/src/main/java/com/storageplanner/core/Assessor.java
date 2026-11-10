package com.storageplanner.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The decision algorithm. UnitBuilder proposes what could be done with each file; the Assessor decides
 * which proposals stand.
 *
 * For each file it works out five things separately and never adds them into one score:
 *   retention    how much keeping the file is worth (unknown = valuable)
 *   redundancy   how sure it is that another file, which stays, holds the same content
 *   regenerable  how sure it is that the file can be had again (rebuilt, re-downloaded)
 *   confidence   how strong the evidence is: different kinds of evidence that agree
 *   harm         what is lost if the suggestion is wrong
 *
 * Then, for every proposal:
 *   1. Gates. A proposal that breaks one is withdrawn or becomes review-only. Size never overrides a gate.
 *        sensitive records: only an identical extra copy may go, and nothing re-encodes them
 *        one reason is not enough: age, blur, darkness or a folder name alone never plans a deletion
 *        the kept copy of a group is never planned for deletion itself
 *        a file with companions (RAW + JPEG, edit sidecar) is not split up
 *        an older backup may go only while a newer one is there
 *        expected harm above Policy.REVIEW_HARM
 *   2. Tier, derived from the five values (Policy): Safe, No loss, or Your call. Re-encodes keep the tier of
 *      their kind; the tools check the real result when they run.
 *
 * "Review-only" (Option.review) means: shown to the user, selectable by the user, never chosen by the planner.
 *
 * It only reads the scan. It calls no tool and changes no file. What it decides is handed on as Actions.
 */
public final class Assessor {
    private Assessor() {}

    private static final Set<String> SENSITIVE_WORDS = set("aadhaar", "aadhar", "uidai", "passport", "visa", "pancard", "voter",
            "licence", "license", "kyc", "bank", "statement", "passbook", "cheque", "itr", "form16", "tax", "tds", "salary",
            "payslip", "insurance", "policy", "medical", "prescription", "diagnosis", "discharge", "certificate", "marksheet",
            "degree", "transcript", "deed", "agreement", "contract", "affidavit", "loan", "mortgage", "will", "nominee");
    private static final Set<String> RECORD_WORDS = set("invoice", "receipt", "bill", "warranty", "ticket", "booking", "resume",
            "cv", "offer", "appointment", "admit", "result", "report", "scan", "signed");
    private static final Set<String> COMPANION_EXT = set("xmp", "aae", "dng", "raw", "cr2", "cr3", "nef", "arw", "orf", "rw2",
            "raf", "srt", "sub", "ass", "lrc", "thm", "lrv", "cue");
    private static final Set<String> IMAGE_EXT = set("jpg", "jpeg", "heic", "heif", "png", "webp");
    private static final Set<String> VIDEO_EXT = set("mp4", "mov", "3gp", "mkv");
    private static final Set<String> USER_MEDIA_EXT = set("jpg", "jpeg", "heic", "heif", "png", "webp", "gif", "dng", "mp4", "mov",
            "3gp", "mkv", "webm", "mp3", "m4a", "aac", "wav", "ogg", "opus", "flac", "amr", "pdf", "doc", "docx", "xls", "xlsx",
            "ppt", "pptx", "txt", "csv", "zip");
    /** Folders the system or an app manages: a file name there says nothing about the file's worth. */
    private static final Set<String> MANAGED_AREAS = set("thumbnails", "temp", "ext_cache", "trash", "wa_status");
    /** Pictures whose point is the text or code in them: not re-encoded with loss. */
    private static final Set<String> TEXT_CATEGORIES = set(PhotoCategory.ID, PhotoCategory.RECEIPT, PhotoCategory.BILL,
            PhotoCategory.DOCUMENT, PhotoCategory.QR, PhotoCategory.PAYMENT, PhotoCategory.TICKET);

    private static Set<String> set(String... a) { Set<String> s = new HashSet<String>(); for (String x : a) s.add(x); return s; }

    /** What the Assessor needs to know about the whole scan. */
    private static final class Ctx {
        final Map<String, FileItem> byRel = new HashMap<String, FileItem>();
        final Map<String, Integer> keeperOf = new HashMap<String, Integer>();
        /** folder + base name (no extension) -> extensions seen there */
        final Map<String, Set<String>> stems = new HashMap<String, Set<String>>();
        /** folder -> newest WhatsApp backup in it */
        final Map<String, Long> newestBackup = new HashMap<String, Long>();
        UnitBuilder.Detections det;
        long now;
    }

    /** Assess every file unit of a finished UnitBuilder, replacing its proposals with the ones that stand. */
    public static void apply(UnitBuilder ub, Scanner.Result r) {
        Ctx c = new Ctx();
        c.det = ub.det; c.now = r.now;
        for (FileItem f : r.files) {
            c.byRel.put(f.rel, f);
            String stem = stem(f.rel);
            if (stem != null) {
                Set<String> e = c.stems.get(stem);
                if (e == null) { e = new HashSet<String>(); c.stems.put(stem, e); }
                e.add(ext(f.rel));
            }
            if (f.name().startsWith("msgstore") && f.rel.contains("/Databases/")) {
                String dir = dir(f.rel);
                Long m = c.newestBackup.get(dir);
                if (m == null || f.mtime > m) c.newestBackup.put(dir, f.mtime);
            }
        }
        count(c.keeperOf, ub.det.duplicateOf); count(c.keeperOf, ub.det.burstExtraOf); count(c.keeperOf, ub.det.sentCopyOf);

        for (Unit u : ub.units) {
            if (u.file == null) continue;                       // apps and caches: decided from system facts in UnitBuilder
            Assessment a = describe(u.file, c);
            u.assessment = a;
            List<Option> stand = new ArrayList<Option>();
            for (Option o : u.options) {
                Option j = judge(u.file, o, a, c);
                if (j != null) stand.add(j);
            }
            u.options.clear();
            u.options.addAll(stand);
        }
    }

    private static void count(Map<String, Integer> n, Map<String, String> copyToKeeper) {
        for (String keeper : copyToKeeper.values()) { Integer k = n.get(keeper); n.put(keeper, k == null ? 1 : k + 1); }
    }

    // ------------------------------------------------------------------ the file itself

    /** Retention, sensitivity and what the file is tied to. Uses only what the scan already measured. */
    static Assessment describe(FileItem f, Ctx c) {
        Assessment a = new Assessment();
        String area = f.area == null ? "" : f.area, cat = f.category;
        boolean managed = MANAGED_AREAS.contains(area);

        double v = Policy.KEEP_DEFAULT;
        if (area.equals("camera")) { v = Policy.KEEP_CAMERA; a.labels.add("camera original"); }
        else if (area.equals("document") || area.equals("wa_doc")) { v = Policy.KEEP_RECORD; a.labels.add("document"); }
        else if (area.equals("wa_voice")) { v = 0.7; a.labels.add("voice message"); }
        else if (area.equals("pictures")) v = 0.65;
        else if (area.equals("screenshot") || area.equals("wa_image") || area.equals("download")) v = 0.5;
        else if (area.equals("wa_video")) v = 0.55;
        else if (area.equals("wa_sent") || area.equals("wa_sent_video")) v = 0.4;
        else if (area.equals("telegram_media")) v = 0.35;
        else if (area.equals("residue") || area.equals("wa_old_backup") || area.equals("wa_sticker") || area.equals("wa_gif")) v = 0.3;
        else if (area.equals("trash")) { v = 0.05; a.labels.add("already deleted by you"); }
        else if (managed) { v = 0.1; a.labels.add("managed by the system or an app"); }

        if (!managed) {
            // what the picture shows can only raise the value, except for the three kinds that are known to be throwaway
            if (f.faces > 0 || PhotoCategory.PEOPLE.equals(cat)) { v = Math.max(v, Policy.KEEP_PEOPLE); a.labels.add("people"); }
            if (PhotoCategory.RECEIPT.equals(cat) || PhotoCategory.BILL.equals(cat) || PhotoCategory.DOCUMENT.equals(cat)) {
                v = Math.max(v, Policy.KEEP_RECORD); a.labels.add("record or document");
            }
            if (PhotoCategory.PAYMENT.equals(cat)) { v = Math.max(v, 0.75); a.labels.add("payment record"); }
            if (PhotoCategory.QR.equals(cat) || PhotoCategory.CHAT.equals(cat)) v = Math.max(v, 0.7);
            if (PhotoCategory.TICKET.equals(cat)) v = Math.max(v, 0.6);
            if (PhotoCategory.OTP.equals(cat)) { v = 0.15; a.labels.add("one-time code"); }
            else if (PhotoCategory.GREETING.equals(cat) && f.faces <= 0) { v = 0.25; a.labels.add("greeting image"); }
            else if (PhotoCategory.ACCIDENTAL.equals(cat) && f.faces <= 0) { v = Math.min(v, 0.3); a.labels.add("nothing visible"); }

            if (PhotoCategory.ID.equals(cat)) { a.sensitive = true; a.labels.add("ID document"); }
            String word = nameWord(f.name(), SENSITIVE_WORDS);
            if (word != null) { a.sensitive = true; a.labels.add("named like a personal record (\"" + word + "\")"); }
            else if ((word = nameWord(f.name(), RECORD_WORDS)) != null) {
                v = Math.max(v, Policy.KEEP_RECORD); a.labels.add("named like a record (\"" + word + "\")");
            }
            if (a.sensitive) v = Policy.KEEP_SENSITIVE;

            Set<String> exts = c.stems.get(stem(f.rel));
            if (exts != null && exts.size() > 1) {
                boolean img = false, vid = false, side = false;
                for (String e : exts) { if (IMAGE_EXT.contains(e)) img = true; if (VIDEO_EXT.contains(e)) vid = true; if (COMPANION_EXT.contains(e)) side = true; }
                if (side || (img && vid)) { a.hasCompanion = true; a.labels.add("has companion files with the same name"); }
            }
        }
        Integer k = c.keeperOf.get(f.rel);
        if (k != null) { a.keeperOf = k; a.labels.add("kept copy of " + k + (k == 1 ? " other file" : " other files")); }
        a.retention = v;
        return a;
    }

    // ------------------------------------------------------------------ one proposal

    /** Returns the proposal as it stands (maybe at another tier, maybe review-only), or null when it is withdrawn. */
    static Option judge(FileItem f, Option o, Assessment a, Ctx c) {
        boolean delete = o.action.equals("delete");
        return delete ? judgeDelete(f, o, a, c) : judgeChange(f, o, a);
    }

    /** Re-encodes and lossless conversions. The tool measures the real result later; here only who may be offered one. */
    private static Option judgeChange(FileItem f, Option o, Assessment a) {
        boolean lossy = !o.action.equals("lossless_image");
        if (a.sensitive) return withdraw(a, o, "personal record, left exactly as it is");
        if (a.hasCompanion) return withdraw(a, o, "other files belong to it and would no longer match");
        if (lossy && f.category != null && TEXT_CATEGORIES.contains(f.category)) return withdraw(a, o, "the text or code in it must stay exact");
        double conf = o.exact ? 0.9 : 0.8;
        return new Option(o.action, o.technique, o.saved, o.risk, o.exact, o.why, false, null, null,
                a.retention, 0, 0, lossy ? 0.1 : 0.0, conf);
    }

    private static Option judgeDelete(FileItem f, Option o, Assessment a, Ctx c) {
        String t = o.technique, area = f.area == null ? "" : f.area;
        double red = 0, regen = 0, conf;        // redundancy, regenerability, confidence
        boolean assumed = false;                // regenerable only as far as the app can know
        boolean discarded = false;              // the user already threw it away
        String review = null;                   // set = review-only, with this reason
        String keeps = null, technique = t;
        double age = f.ageDays(c.now);
        double aged = age > 1 ? 0.6 : 0;

        if (t.equals(Techniques.DUPLICATES)) {
            keeps = c.det.duplicateOf.get(f.rel);
            FileItem k = keeps == null ? null : c.byRel.get(keeps);
            // the copy that stays must be in this scan with the same bytes
            if (k == null || f.sha256 == null || !f.sha256.equals(k.sha256) || k.size != f.size) return withdraw(a, o, "the copy to keep could not be confirmed");
            red = 1.0; conf = 1.0;
        } else if (t.equals(Techniques.JUNK) && area.equals("thumbnails")) { regen = 1.0; conf = 0.95; }
        else if (t.equals(Techniques.JUNK) && area.equals("temp")) { regen = 0.95; conf = agree(0.9, aged); }
        else if (t.equals(Techniques.JUNK) && area.equals("wa_status")) { regen = 0.95; conf = agree(0.9, aged); }
        else if (t.equals(Techniques.JUNK) && area.equals("residue")) {
            if (f.rel.startsWith("Android/obb/")) { regen = 0.9; conf = agree(0.9, 0.9); }       // game data, downloaded again with the game
            else {                                                                                  // Android/media, Android/data: may be the user's own files
                technique = Techniques.LEFTOVER_MEDIA; conf = agree(0.8, 0.9);
                if (USER_MEDIA_EXT.contains(ext(f.rel))) review = "it may be something you made or saved in that app";
            }
        }
        else if (t.equals(Techniques.CACHE)) { regen = 1.0; conf = 0.95; }
        else if (t.equals(Techniques.TRASH)) { discarded = true; conf = 0.95; }
        else if (t.equals(Techniques.TELEGRAM)) { regen = 0.6; assumed = true; conf = agree(0.8); }
        else if (t.equals(Techniques.WA_BACKUP)) {
            Long newest = c.newestBackup.get(dir(f.rel));
            if (newest != null && newest > f.mtime) { red = 0.9; regen = 0.9; conf = agree(0.9, 0.9); }
            else { conf = agree(0.9); review = "no newer backup was found next to it"; }
        }
        else if (t.equals(Techniques.INSTALLED_APK)) { regen = 0.9; conf = agree(0.95, 0.95); }
        else if (t.equals(Techniques.SENT)) {
            keeps = c.det.sentCopyOf.get(f.rel);
            FileItem k = keeps == null ? null : c.byRel.get(keeps);
            if (k == null || !k.hasPhash || !f.hasPhash) return withdraw(a, o, "the photo it was sent from could not be confirmed");
            int bits = ImageFeatures.hamming(k.phash, f.phash);
            boolean sure = bits <= Policy.SENT_SURE_BITS && (long) k.width * k.height >= (long) f.width * f.height;
            red = sure ? 0.98 : 0.85; conf = agree(0.8, sure ? 0.9 : 0.7);
            if (!sure) technique = Techniques.SENT_CHECK;
        }
        else if (t.equals(Techniques.BURSTS)) {
            keeps = c.det.burstExtraOf.get(f.rel);
            FileItem k = keeps == null ? null : c.byRel.get(keeps);
            if (k == null) return withdraw(a, o, "the shot to keep could not be confirmed");
            boolean measured = k.quality != null && f.quality != null;
            red = 0.6; conf = agree(0.7, 0.5, measured ? 0.6 : 0);
            if (!measured) review = "the shots look alike, but which one is better was not measured";
        }
        else if (t.equals(Techniques.BLURRY)) { conf = agree(0.5); review = "a soft photo may be the only one of that moment"; }
        else if (t.equals(Techniques.ACCIDENTAL)) { conf = agree(0.6); review = "darkness or blur alone is not a reason to delete"; }
        else if (t.equals(Techniques.OTP_SHOTS)) conf = agree(0.8, aged);
        else if (t.equals(Techniques.GREETINGS)) conf = agree(0.6, 0.7, 0.5);
        else if (t.equals(Techniques.OLD_PAYMENTS)) { conf = agree(0.8, 0.5); review = "a payment record; your call whether you still need it"; }
        else if (t.equals(Techniques.EXPIRED_TICKETS)) { conf = agree(0.8, 0.5); review = "may still be needed for a refund or an expense claim"; }
        else if (t.equals(Techniques.OLD_DOWNLOADS)) {
            if (f.apkPackage != null) { regen = 0.5; assumed = true; conf = agree(0.95, 0.9); }   // an installer; the store may still have it
            else { conf = agree(0.5); review = "old does not mean unimportant"; }
        }
        else { conf = agree(0.5); review = "not enough is known about this file"; }

        // ---- gates
        if (a.sensitive && !t.equals(Techniques.DUPLICATES)) return withdraw(a, o, "personal record, only an identical extra copy may be suggested");
        if (a.keeperOf > 0 && review == null)
            review = "it is the copy that is kept of " + a.keeperOf + (a.keeperOf == 1 ? " other file" : " other files");
        if (a.hasCompanion && !t.equals(Techniques.DUPLICATES) && review == null)
            review = "other files with the same name belong to it";
        if ("unreadable".equals(f.type)) return withdraw(a, o, "the file could not be read");

        double keep = a.retention;
        double harm = discarded ? 0.05 : keep * (1 - red) * (1 - regen);
        harm = 1 - (1 - harm) * conf;                           // weak evidence counts as possible harm
        if (review == null && harm > Policy.REVIEW_HARM) review = "the evidence is not strong enough to plan this for you";

        // ---- tier, from the values
        int tier;
        boolean sureEnough = conf >= Policy.SAFE_CONFIDENCE;
        if (sureEnough && !assumed && regen >= Policy.SAFE_REGENERABLE && keep <= Policy.SAFE_MAX_KEEP) tier = Option.R0;
        else if (sureEnough && (red >= Policy.NO_LOSS_REDUNDANT || (regen >= Policy.NO_LOSS_REGENERABLE && !assumed) || discarded)) tier = Option.R1;
        else tier = Option.R4;
        if (review != null) tier = Option.R4;                    // nothing review-only is ever labelled safe

        return new Option(o.action, technique, o.saved, tier, o.exact, o.why, review != null, review, keeps,
                keep, red, regen, harm, conf);
    }

    private static Option withdraw(Assessment a, Option o, String reason) {
        a.withdrawn.add(o.technique + ": " + reason);
        return null;
    }

    /**
     * Confidence from different kinds of evidence that agree: 1 - (1-a)(1-b)... One kind alone is capped
     * (Policy.ONE_KIND_CAP) unless it is conclusive, so a single rule never looks like proof.
     */
    static double agree(double... kinds) {
        double miss = 1; int n = 0; double only = 0;
        for (double k : kinds) if (k > 0) { miss *= 1 - k; n++; only = k; }
        if (n == 0) return 0;
        if (n == 1) return only >= Policy.CONCLUSIVE ? only : Math.min(only, Policy.ONE_KIND_CAP);
        return 1 - miss;
    }

    // ------------------------------------------------------------------ names

    /** A whole word of the file name (letters only, lower case) that is in the list, or null. */
    static String nameWord(String name, Set<String> words) {
        int dot = name.lastIndexOf('.');
        String base = (dot > 0 ? name.substring(0, dot) : name).toLowerCase(Locale.ROOT);
        for (String w : base.split("[^a-z0-9]+")) if (words.contains(w)) return w;
        return null;
    }

    static String ext(String rel) {
        int s = rel.lastIndexOf('/'), d = rel.lastIndexOf('.');
        return d > s + 1 ? rel.substring(d + 1).toLowerCase(Locale.ROOT) : "";
    }

    static String dir(String rel) { int s = rel.lastIndexOf('/'); return s < 0 ? "" : rel.substring(0, s); }

    /** Folder plus file name without its extension, lower case; null for names without an extension. */
    static String stem(String rel) {
        int s = rel.lastIndexOf('/'), d = rel.lastIndexOf('.');
        if (d <= s + 1) return null;
        return rel.substring(0, d).toLowerCase(Locale.ROOT);
    }
}
