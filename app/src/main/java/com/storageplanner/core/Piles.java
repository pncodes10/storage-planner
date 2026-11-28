package com.storageplanner.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The plan in plain words for the "Clean up" screens: each file goes into one pile (copies, memes and forwards, blurry
 * shots ...) by what the rules suggested for it, so a file you untick stays in its pile, marked as kept. Each pile has
 * a title and a one-line hint, and each file a one-sentence reason. The rules and tags stay out of these words: they are
 * shown only on the "Why this decision?" page.
 */
public final class Piles {
    private Piles() {}

    public static final String COPIES = "copies", FORWARDS = "forwards", BLURRY = "blurry", SCREENSHOTS = "screenshots",
            DOWNLOADS = "downloads", JUNK = "junk", PICKED = "picked", OTHER = "other", SMALLER = "smaller";

    /** The piles of files to remove, in the order they are shown. */
    public static final String[] REMOVE = {COPIES, FORWARDS, BLURRY, SCREENSHOTS, DOWNLOADS, JUNK, PICKED, OTHER};

    /** The pile a file belongs to, or null when nothing would change it. */
    public static String of(FinalPlan.Entry e) {
        if (FinalPlan.DELETE.equals(e.suggestedAction)) return removePile(e.reason());
        if (FinalPlan.COMPRESS.equals(e.suggestedAction)) return SMALLER;
        if (!FinalPlan.KEEP.equals(e.action)) return FinalPlan.COMPRESS.equals(e.action) ? SMALLER : PICKED;
        return null;
    }

    static String removePile(String technique) {
        if (technique == null) return OTHER;
        switch (technique) {
            case Techniques.DUPLICATES: case Techniques.SENT: case Techniques.BURSTS: return COPIES;
            case Techniques.FORWARDS: case Techniques.GREETINGS: case Techniques.TELEGRAM: return FORWARDS;
            case Techniques.BLURRY: case Techniques.ACCIDENTAL: case Techniques.ACCIDENTAL_CLIPS: return BLURRY;
            case Techniques.FORWARD_VIDEOS: return FORWARDS;
            case Techniques.OTP_SHOTS: case Techniques.OLD_PAYMENTS: case Techniques.EXPIRED_TICKETS: case Techniques.REFERENCE: return SCREENSHOTS;
            case Techniques.OLD_DOWNLOADS: case Techniques.INSTALLED_APK: return DOWNLOADS;
            case Techniques.JUNK: case Techniques.TRASH: case Techniques.WA_BACKUP: case Techniques.CACHE: return JUNK;
            case Techniques.PICKED: return PICKED;
            default: return OTHER;
        }
    }

    public static String title(String pile) {
        switch (pile) {
            case COPIES: return "Copies of the same thing";
            case FORWARDS: return "Memes and forwards";
            case BLURRY: return "Blurry and accidental shots";
            case SCREENSHOTS: return "Old screenshots and tickets";
            case DOWNLOADS: return "Old downloads and installers";
            case JUNK: return "Leftovers and old backups";
            case PICKED: return "Files you picked";
            case SMALLER: return "Made smaller, look the same";
            default: return "Other files";
        }
    }

    /** What the pile is, in a few words (after the count). */
    public static String hint(String pile) {
        switch (pile) {
            case COPIES: return "the best copy is kept";
            case FORWARDS: return "from chats";
            case BLURRY: return "blurry, dark or taken by mistake";
            case SCREENSHOTS: return "codes, payments, past tickets";
            case DOWNLOADS: return "installers and files you got long ago";
            case JUNK: return "temp files and backups you don't need";
            case PICKED: return "you chose these";
            case SMALLER: return "saved in a smaller format";
            default: return "";
        }
    }

    /** The short banner at the top of a pile. */
    public static String banner(String pile) {
        switch (pile) {
            case COPIES: return "The best one of each set stays. The other copies are removed unless you tap them.";
            case SMALLER: return "These are saved again in a smaller format and look the same. Tap any you want left exactly as they are.";
            case PICKED: return "You chose to remove these. Tap any to keep it after all.";
            default: return "These are removed unless you tap them to keep.";
        }
    }

    /** One sentence: why this file is removed, made smaller or kept. */
    public static String why(FinalPlan.Entry e) {
        if (FinalPlan.KEEP.equals(e.action)) {
            if (e.chosen && !FinalPlan.KEEP.equals(e.suggestedAction)) return "You chose to keep this.";
            if (Techniques.PEOPLE_PROTECTED.equals(e.reason())) return "It has people in it, so it is never removed.";
            if (Techniques.FAVOURITES.equals(e.reason())) return "It's a favourite, so it stays.";
            return "Nothing to do: it stays as it is.";
        }
        String t = e.option != null ? e.option.technique : e.reason();
        if (FinalPlan.COMPRESS.equals(e.action)) {
            if (Techniques.VIDEOS.equals(t)) return "Saved again in a smaller video format. It looks the same.";
            if (Techniques.MUSIC.equals(t)) return "Saved again in a smaller audio format. It sounds the same.";
            return "Saved again in a smaller format. It looks the same.";
        }
        if (e.chosen && !FinalPlan.DELETE.equals(e.suggestedAction)) return "You chose to remove this.";
        if (t == null) return "It is removed.";
        switch (t) {
            case Techniques.DUPLICATES: return "An exact copy of another file that stays.";
            case Techniques.SENT: return "A copy WhatsApp made when you sent it. The original stays.";
            case Techniques.BURSTS: return "One of several shots of the same moment. The best one stays.";
            case Techniques.BLURRY: return "It looks blurry.";
            case Techniques.ACCIDENTAL: return "It looks like it was taken by accident.";
            case Techniques.ACCIDENTAL_CLIPS: return "A clip that looks filmed by mistake: very short, dark, blurred or with nothing happening, and nobody in it.";
            case Techniques.FORWARD_VIDEOS: return "A forwarded-looking video from a chat, over a month old, with nobody in it.";
            case Techniques.FORWARDS: return "A meme or forward from a chat, more than a week old.";
            case Techniques.GREETINGS: return "A greeting image from a chat.";
            case Techniques.TELEGRAM: return "Telegram media you can download again from the chat.";
            case Techniques.OTP_SHOTS: return "A screenshot of a one-time code. The code has expired.";
            case Techniques.OLD_PAYMENTS: return "An old payment screenshot.";
            case Techniques.EXPIRED_TICKETS: return "A ticket for a trip or show that is over.";
            case Techniques.REFERENCE: return "A photo taken to remember something, now months old.";
            case Techniques.OLD_DOWNLOADS: return "An old download you haven't opened in a long time.";
            case Techniques.INSTALLED_APK: return "The app is already installed, so its installer isn't needed.";
            case Techniques.WA_BACKUP: return "An old WhatsApp backup. A newer one exists.";
            case Techniques.JUNK: return "Leftover junk: thumbnails or temp files.";
            case Techniques.TRASH: return "It is already in a trash folder.";
            case Techniques.PICKED: return "You chose to remove this.";
            default: return t + ".";
        }
    }

    /** One pile: its files (removed or not) and what it frees now. */
    public static final class Pile {
        public final String id;
        public final List<FinalPlan.Entry> files = new ArrayList<FinalPlan.Entry>();
        Pile(String id) { this.id = id; }

        /** Files that still change (removed, or made smaller in the Smaller pile). */
        public int acting() { int n = 0; for (FinalPlan.Entry e : files) if (!FinalPlan.KEEP.equals(e.action)) n++; return n; }
        public long freed() { long b = 0; for (FinalPlan.Entry e : files) b += e.saved(); return b; }
        public String title() { return Piles.title(id); }

        /** How many of its files you have looked at (scrolled past or tapped) on its page. */
        public int reviewed(Set<String> keys) { int n = 0; for (FinalPlan.Entry e : files) if (Piles.reviewed(e, keys)) n++; return n; }

        /** What its reviewed files free: the part of the pile a clean-up acts on. */
        public long readyFreed(Set<String> keys) { long b = 0; for (FinalPlan.Entry e : files) if (Piles.reviewed(e, keys)) b += e.saved(); return b; }

        /** "412 photos · the best copy is kept". */
        public String subtitle() {
            int n = acting();
            String h = hint(id);
            return count(n, noun(files)) + (h.isEmpty() ? "" : " · " + h);
        }
    }

    /** The piles of a plan, removal piles first in {@link #REMOVE} order, then the Smaller pile; empty piles left out. */
    public static List<Pile> build(List<FinalPlan.Entry> plan) {
        Map<String, Pile> m = new LinkedHashMap<String, Pile>();
        for (String id : REMOVE) m.put(id, new Pile(id));
        m.put(SMALLER, new Pile(SMALLER));
        for (FinalPlan.Entry e : plan) {
            String p = of(e);
            if (p != null) m.get(p).files.add(e);
        }
        List<Pile> out = new ArrayList<Pile>();
        for (Pile p : m.values()) if (!p.files.isEmpty()) out.add(p);
        return out;
    }

    // ------------------------------------------------------------------ review: only files you have looked at are acted on

    /**
     * The key a file is marked reviewed under: its pile and its path. A file that moves to another pile (a new analysis
     * suggests something else for it) needs a new look. Null for a file in no pile (nothing happens to it).
     */
    public static String reviewKey(FinalPlan.Entry e) {
        String p = of(e);
        return p == null ? null : p + "\t" + e.file.rel;
    }

    public static boolean reviewed(FinalPlan.Entry e, Set<String> keys) {
        String k = reviewKey(e);
        return k != null && keys.contains(k);
    }

    /** The files a clean-up acts on: removed or made smaller, and reviewed on their pile's page. */
    public static List<FinalPlan.Entry> ready(List<FinalPlan.Entry> all, Set<String> keys) {
        List<FinalPlan.Entry> out = new ArrayList<FinalPlan.Entry>();
        for (FinalPlan.Entry e : all) if (!FinalPlan.KEEP.equals(e.action) && reviewed(e, keys)) out.add(e);
        return out;
    }

    /** Biggest saving first (what removing or shrinking it frees; its size when it is kept), then the bigger file. */
    public static final Comparator<FinalPlan.Entry> BY_SAVING = new Comparator<FinalPlan.Entry>() {
        public int compare(FinalPlan.Entry x, FinalPlan.Entry y) {
            long a = worth(x), b = worth(y);
            if (a != b) return a > b ? -1 : 1;
            if (x.file.size != y.file.size) return x.file.size > y.file.size ? -1 : 1;
            return x.file.rel.compareTo(y.file.rel);
        }
    };

    /** What a file is worth to the clean-up: what it frees, or what it would free if you put it back (a kept file). */
    public static long worth(FinalPlan.Entry e) {
        if (!FinalPlan.KEEP.equals(e.action)) return e.saved();
        return e.suggested != null ? e.suggested.saved : 0;
    }

    /** Sorts a pile's files, biggest saving first. */
    public static void sortBySaving(List<FinalPlan.Entry> l) { Collections.sort(l, BY_SAVING); }

    /** "photo", "video" or "file", whichever fits every file. */
    static String noun(List<FinalPlan.Entry> l) {
        String t = null;
        for (FinalPlan.Entry e : l) { if (t == null) t = e.type; else if (!t.equals(e.type)) return "file"; }
        return Tags.IMAGE.equals(t) ? "photo" : Tags.VIDEO.equals(t) ? "video" : "file";
    }

    public static String count(int n, String one) { return String.format(Locale.US, "%,d", n) + " " + (n == 1 ? one : one + "s"); }
}
