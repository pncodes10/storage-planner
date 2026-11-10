package com.storageplanner.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Will the user need this file? One score from 0 (surely not) to 100 (surely), built from several
 * separate "ways" of looking at the file. Each way adds or takes away points and says why in words, so
 * every decision can be explained and every way can be switched off (Policy).
 *
 * What is done with the score (Policy.sensitivity sets the two limits):
 *   at or above keepAtOrAbove      KEEP     the file is left alone; nothing is shown for review
 *   at or below suggestAtOrBelow   SUGGEST  suggested for deletion, but only when at least two different
 *                                           ways spoke against it (Policy.MIN_REASONS)
 *   in between, or one way only    REVIEW   shown for the user to decide
 * SUGGEST becomes "sure" (Result.sure) at or below planAtOrBelow: then the default plan includes it.
 *
 * The ways:
 *   source   where it came from. A camera photo starts high, a chat image or screenshot lower.
 *   content  what it shows. A one-time code or a greeting is rarely needed again; a receipt, a document,
 *            a chat or a payment usually is. A frame with nothing in it counts against.
 *   people   faces. The strongest reason to keep a picture or a video.
 *   quality  very soft, very small, or a clip of a second or two. Never enough by itself.
 *   age      judged by kind: a code is useless after a day, a greeting after a week, a ticket a month
 *            after its date. Camera photos and videos do not lose value with age. Anything from the
 *            last two days is probably still in use.
 *   event    taken together with other photos or videos (a trip, a party): part of a set worth keeping.
 *   motion   videos only: every frame looks the same, so nothing happens in it.
 *   history  what the user decided before on the same kind of suggestion.
 */
public final class Need {
    private Need() {}

    public static final int KEEP = 0, REVIEW = 1, SUGGEST = 2;

    public static final class Result {
        public int score;
        public int verdict;
        public boolean sure;
        /** Different ways that spoke against needing the file. */
        public int reasonsAgainst;
        public final List<String> forIt = new ArrayList<String>(), against = new ArrayList<String>();

        /** "for keeping: people in it, camera original; against: very soft" */
        public String evidence() {
            StringBuilder b = new StringBuilder();
            if (!forIt.isEmpty()) b.append("for keeping: ").append(join(forIt));
            if (!against.isEmpty()) b.append(b.length() > 0 ? "; " : "").append("against: ").append(join(against));
            return b.toString();
        }
    }

    /** What Need has to know beyond the file itself. */
    public static final class Around {
        /** Camera photos and videos taken within an hour of this one (not counting it). */
        public int takenNearby;
        /** Median sharpness of the user's camera photos, 0 if unknown. */
        public double medianSharp;
        /** Earlier decisions on this kind of suggestion: approved, kept. */
        public int approvedBefore, keptBefore;
        public long now;
    }

    private static final class Tally {
        int score; final Result r = new Result(); int ways;
        void way(int points, String why) {
            if (points == 0) return;
            score += points;
            if (points > 0) r.forIt.add(why); else { r.against.add(why); ways++; }
        }
    }

    public static Result of(FileItem f, String technique, Around ar, Policy p) {
        Tally t = new Tally();
        String area = f.area == null ? "" : f.area, cat = f.category;
        boolean video = isVideo(f);
        boolean camera = area.equals("camera");
        boolean chat = area.startsWith("wa_") || area.equals("telegram_media");
        boolean shot = area.equals("screenshot");
        boolean screenRec = video && isScreenRecording(f);
        double age = f.ageDays(ar.now);

        // ---- source: the starting point
        int base = 55;
        if (p.useSource) {
            if (screenRec) base = 45;
            else if (camera) base = 70;
            else if (area.equals("pictures") || area.equals("movies")) base = 60;
            else if (area.equals("download")) base = 45;
            else if (shot || chat) base = 40;
        }
        t.score = base;
        if (p.useSource && camera && !screenRec) t.r.forIt.add("taken with your camera");

        // ---- content: what it shows (one finding, the most telling one)
        if (p.useContent) {
            if (video) {
                if (f.vFrames > 0 && f.vDark >= 0.8f) t.way(-35, "almost black from start to end");
                else if (f.vFrames > 0 && f.vFlat >= 0.8f) t.way(-35, "nothing to see in it");
                else if (screenRec) t.way(-15, "a recording of the screen");
                if (f.durationMs >= 30000 && f.vFrames > 0 && (f.vDark >= 0.8f || f.vFlat >= 0.8f))
                    t.way(15, "long: it may have been recorded for its sound");
            } else if (PhotoCategory.OTP.equals(cat)) t.way(-40, "a one-time code");
            else if (PhotoCategory.GREETING.equals(cat)) t.way(-30, "a greeting image");
            else if (PhotoCategory.ACCIDENTAL.equals(cat)) t.way(-35, "nothing to see in it");
            else if (PhotoCategory.TICKET.equals(cat)) {
                if (f.catDate > 0 && ar.now - f.catDate > 30 * DAY) t.way(-15, "a ticket for " + Report.day(f.catDate) + ", over a month ago");
                else if (f.catDate > ar.now) t.way(30, "a ticket for a date still to come");
                else t.way(15, "a ticket");
            }
            else if (PhotoCategory.RECEIPT.equals(cat) || PhotoCategory.BILL.equals(cat) || PhotoCategory.DOCUMENT.equals(cat)) t.way(30, "a document or record");
            else if (PhotoCategory.PAYMENT.equals(cat)) t.way(15, "a payment record");
            else if (PhotoCategory.CHAT.equals(cat)) t.way(15, "a conversation");
            else if (PhotoCategory.QR.equals(cat)) t.way(10, "a code that may still be used");
            else if (cat == null && !camera) {                    // not a sorted picture: go by the kind of file
                String e = Assessor.ext(f.rel);
                if (in(e, "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "csv", "odt", "epub")) t.way(25, "a document");
                else if (in(e, "zip", "rar", "7z", "tar", "gz", "exe", "msi", "dmg", "iso", "torrent", "ics", "vcf", "bin")) t.way(-15, "an archive or installer, usually used once");
                else if (in(e, "jpg", "jpeg", "png", "heic", "webp", "mp4", "mov", "mkv", "mp3", "m4a", "wav", "flac")) t.way(10, "a picture, video or sound");
            }
            if (!video && f.text != null && f.text.length() >= 40 && camera && (cat == null || PhotoCategory.PHOTO.equals(cat) || PhotoCategory.PEOPLE.equals(cat)))
                t.way(10, "has writing in it");
            if (Assessor.copyName(f.name())) t.way(-10, "named like a second download of the same file");
        }

        // ---- people
        if (p.usePeople) {
            int faces = video ? f.vFaces : f.faces;
            if (faces > 0 || PhotoCategory.PEOPLE.equals(cat)) t.way(35, faces > 1 ? "people in it" : "a person in it");
        }

        // ---- quality
        if (p.useQuality) {
            if (video) {
                if (f.durationMs > 0 && f.durationMs < 2000) t.way(-20, "under two seconds long");
                else if (f.durationMs >= 2000 && f.durationMs < 4000) t.way(-10, "only a few seconds long");
            } else {
                if (f.sharp >= 0 && ar.medianSharp > 0 && f.sharp < UnitBuilder.BLUR_REL * ar.medianSharp) t.way(-20, "much softer than your other photos");
                int longEdge = Math.max(f.width, f.height);
                if (longEdge > 0 && longEdge < 640) t.way(-10, "very small");
            }
        }

        // ---- age, by kind
        if (p.useAge) {
            if (age < 2) t.way(20, "from the last two days");
            else if (video) {
                if (screenRec && age > 90) t.way(-15, "recorded " + days(age) + " ago");
            }
            else if (PhotoCategory.OTP.equals(cat)) t.way(-20, "the code stopped working " + days(age) + " ago");
            else if (PhotoCategory.GREETING.equals(cat)) { if (age > 7) t.way(-15, "received " + days(age) + " ago"); }
            else if (PhotoCategory.TICKET.equals(cat)) { if (age > 180) t.way(-20, "saved " + days(age) + " ago"); else if (age > 30) t.way(-10, "saved " + days(age) + " ago"); }
            else if (shot) { if (age > 180) t.way(-20, "a screenshot from " + days(age) + " ago"); else if (age > 30) t.way(-10, "a screenshot from " + days(age) + " ago"); }
            else if (area.equals("download")) { if (age > 180) t.way(-15, "downloaded " + days(age) + " ago and not changed since"); }
            // camera photos and videos: age is not a reason
        }

        // ---- event
        if (p.useEvent && camera && !screenRec && ar.takenNearby >= 5) t.way(10, "taken with " + ar.takenNearby + " others within the hour");

        // ---- motion (videos)
        if (p.useMotion && video && still(f) && f.vFlat < 0.8f) t.way(-15, "nothing moves in it");

        // ---- history
        int n = ar.approvedBefore + ar.keptBefore;
        if (p.useHistory && n >= Policy.HISTORY_MIN) {
            int pts = Math.round(25f * (ar.keptBefore - ar.approvedBefore) / n);
            if (pts >= 5) t.way(pts, "you kept " + ar.keptBefore + " of " + n + " like it before");
            else if (pts <= -5) t.way(pts, "you approved deleting " + ar.approvedBefore + " of " + n + " like it before");
        }

        Result r = t.r;
        r.score = Math.max(0, Math.min(100, t.score));
        r.reasonsAgainst = t.ways;
        if (r.score >= p.keepAtOrAbove()) r.verdict = KEEP;
        else if (r.score <= p.suggestAtOrBelow() && t.ways >= Policy.MIN_REASONS) {
            r.verdict = SUGGEST;
            r.sure = r.score <= p.planAtOrBelow();
        } else r.verdict = REVIEW;
        return r;
    }

    static final long DAY = 86400000L;

    public static boolean isVideo(FileItem f) {
        if ("mp4".equals(f.type) || "mkv".equals(f.type)) return !in(Assessor.ext(f.rel), "m4a", "aac", "3ga");
        return in(Assessor.ext(f.rel), "mp4", "mov", "mkv", "3gp", "webm", "m4v");
    }

    /** By folder or name: phones save these as "Screen recordings/…" or "Screen_Recording_…", "screen-2026…", "XRecorder". */
    public static boolean isScreenRecording(FileItem f) {
        String s = f.rel.toLowerCase(Locale.ROOT).replace('_', ' ').replace('-', ' ');
        return s.contains("screen record") || s.contains("screenrecord") || s.contains("screen capture") || s.contains("xrecorder")
                || f.name().toLowerCase(Locale.ROOT).startsWith("screen-");
    }

    /** Every frame looks like the one before it (at least 3 frames looked at). */
    public static boolean still(FileItem f) {
        if (f.vHashes == null || f.vHashes.length < 3) return false;
        for (int i = 1; i < f.vHashes.length; i++) if (ImageFeatures.hamming(f.vHashes[i - 1], f.vHashes[i]) > 4) return false;
        return true;
    }

    private static String days(double age) {
        long d = Math.round(age);
        if (d >= 730) return (d / 365) + " years";
        if (d >= 60) return (d / 30) + " months";
        return d + (d == 1 ? " day" : " days");
    }

    private static boolean in(String e, String... l) { for (String x : l) if (x.equals(e)) return true; return false; }

    private static String join(List<String> l) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < l.size(); i++) b.append(i == 0 ? "" : ", ").append(l.get(i));
        return b.toString();
    }
}
