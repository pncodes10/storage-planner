package com.storageplanner.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * v2 against v3: runs both sorting algorithms (AlgorithmV3) on the same pictures, with the same rules, and keeps one
 * card per picture with each algorithm's category and the plan's suggestion; you mark which one is right. The score is
 * how many times each was right. Pictures where both give the same category and suggestion are counted as agreeing
 * and need no mark.
 * Read-only on the phone: it decodes pictures and writes nothing but its own cards (see VersusStore in the app).
 */
public final class Versus {
    private Versus() {}

    /** Your mark on a card. */
    public static final int NONE = 0, V2 = 2, V3 = 3, SKIP = 1;

    /** One algorithm's answer for a picture. */
    public static final class Side {
        public String category = "", why = "", action = "Keep", detail = "";
        public long date;
    }

    public static final class Card {
        public String path = "", rel = "", area = "", name = "";
        public long size, mtime;
        public Side v2 = new Side(), v3 = new Side();
        public String problem;               // the picture could not be sorted (both sides empty)
        public String v3Note;                // Mirage did not run (v3 side empty)
        public long ms, v3Ms;
        public int mark = NONE;

        /** Both give the same category and suggest the same: nothing to judge. */
        public boolean agree() { return problem == null && v3Note == null && v2.action.equals(v3.action) && v2.category.equals(v3.category); }

        /** A card you are asked to mark (unless agreeing cards are shown too). */
        public boolean judgeable(boolean withAgreeing) { return problem == null && v3Note == null && (withAgreeing || !agree()); }
    }

    /** The score so far. */
    public static final class Score {
        public int v2, v3, skipped, agree, left, problems, total;

        public String line() {
            return String.format(Locale.US, "v2 %d · v3 %d · skipped %d · both agree %d · left to mark %d", v2, v3, skipped, agree, left);
        }
    }

    public static Score score(List<Card> cards, boolean withAgreeing) {
        Score s = new Score();
        for (Card c : cards) {
            s.total++;
            if (c.problem != null || c.v3Note != null) { s.problems++; continue; }
            if (c.mark == V2) s.v2++;
            else if (c.mark == V3) s.v3++;
            else if (c.mark == SKIP) s.skipped++;
            else if (c.agree() && !withAgreeing) s.agree++;
            else s.left++;
        }
        return s;
    }

    /** The next card to mark from index from (wrapping), or -1 when none is left. */
    public static int next(List<Card> cards, int from, boolean withAgreeing) {
        int n = cards.size();
        for (int k = 0; k < n; k++) {
            int i = (Math.max(0, from) + k) % n;
            Card c = cards.get(i);
            if (c.mark == NONE && c.judgeable(withAgreeing)) return i;
        }
        return -1;
    }

    // ------------------------------------------------------------------ choosing and running
    /**
     * The pictures to compare: from every place a sample can come from (camera, screenshots, downloads, WhatsApp, other
     * pictures), the n newest overall, or n at random (seed). Newest first either way, so cards come in a stable order.
     */
    public static List<FileItem> choose(Platform pf, int n, boolean newest, long seed, Scanner.Progress pr) {
        Set<String> areas = new LinkedHashSet<String>();
        Collections.addAll(areas, Sample.SOURCES);
        Map<String, List<FileItem>> by = Sample.list(pf, areas, pr);
        List<FileItem> all = new ArrayList<FileItem>();
        for (List<FileItem> l : by.values()) all.addAll(l);
        List<FileItem> picked = newest ? Sample.newest(all, n) : Sample.pick(all, n, new Random(seed));
        return Sample.newest(picked, picked.size());
    }

    /** Sorts one picture with both algorithms and the rules; detail gets the Lab's lines for it. */
    public static Card judge(Platform pf, FileItem f, RuleSet rules, long now, Lab.Out detail) {
        Card c = new Card();
        c.path = f.abs; c.rel = f.rel; c.area = f.area == null ? "camera" : f.area; c.name = f.name(); c.size = f.size; c.mtime = f.mtime;
        long t0 = System.currentTimeMillis();
        try {
            String type = Classifier.magic(f.abs, f.size);
            if (!"jpeg".equals(type) && !"png".equals(type) && !"webp".equals(type) && !"heic".equals(type) && !"avif".equals(type)) {
                c.problem = "not a picture (file type " + type + ")";
            } else {
                Lab.Pic p = new Lab.Pic(f.abs, f.name(), f.size, f.mtime);
                p.area = c.area; p.rel = f.rel; p.type = type;
                Lab.Shown sh = Lab.mirageVerdict(pf, p, rules, now, detail);
                if (sh.v2 == null) c.problem = "could not be decoded";
                else {
                    side(c.v2, sh.v2, sh.v2Options);
                    if (sh.v3 == null) c.v3Note = sh.v3Note == null ? "Mirage did not run" : sh.v3Note;
                    else side(c.v3, sh.v3, sh.v3Options);
                    c.v3Ms = sh.v3Ms;
                }
            }
        } catch (Throwable t) {
            c.problem = "failed: " + t;
        }
        c.ms = System.currentTimeMillis() - t0;
        return c;
    }

    static void side(Side s, PhotoCategory.Result r, List<Option> ops) {
        s.category = r.category == null ? "" : r.category;
        s.why = r.why == null ? "" : r.why;
        s.date = r.date;
        s.action = AlgorithmV3.action(ops, r.category);
        StringBuilder b = new StringBuilder();
        for (Option o : ops) if (o.why != null && !o.why.isEmpty()) b.append(b.length() == 0 ? "" : "; ").append(o.why);
        s.detail = b.toString();
    }

    // ------------------------------------------------------------------ saving (one JSON object per line)
    public static String toLine(Card c) {
        StringBuilder b = new StringBuilder("{");
        kv(b, "path", c.path); kv(b, "rel", c.rel); kv(b, "area", c.area); kv(b, "name", c.name);
        num(b, "size", c.size); num(b, "mtime", c.mtime); num(b, "ms", c.ms); num(b, "v3ms", c.v3Ms); num(b, "mark", c.mark);
        if (c.problem != null) kv(b, "problem", c.problem);
        if (c.v3Note != null) kv(b, "v3note", c.v3Note);
        side(b, "v2", c.v2); side(b, "v3", c.v3);
        b.setLength(b.length() - 1);
        return b.append('}').toString();
    }

    /** A card from a saved line, or null when the line is damaged. */
    public static Card fromLine(String line) {
        try {
            Object o = Json.parse(line);
            Card c = new Card();
            c.path = str(o, "path"); c.rel = str(o, "rel"); c.area = str(o, "area"); c.name = str(o, "name");
            c.size = (long) Json.num(o, "size", 0); c.mtime = (long) Json.num(o, "mtime", 0); c.ms = (long) Json.num(o, "ms", 0);
            c.v3Ms = (long) Json.num(o, "v3ms", 0); c.mark = (int) Json.num(o, "mark", NONE);
            c.problem = Json.str(o, "problem"); c.v3Note = Json.str(o, "v3note");
            readSide(Json.obj(o, "v2"), c.v2); readSide(Json.obj(o, "v3"), c.v3);
            return c.path.isEmpty() ? null : c;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String str(Object o, String k) { String s = Json.str(o, k); return s == null ? "" : s; }

    private static void readSide(Object o, Side s) {
        if (o == null) return;
        s.category = str(o, "category"); s.why = str(o, "why"); s.action = str(o, "action"); s.detail = str(o, "detail");
        s.date = (long) Json.num(o, "date", 0);
        if (s.action.isEmpty()) s.action = "Keep";
    }

    private static void side(StringBuilder b, String k, Side s) {
        b.append('"').append(k).append("\":{");
        kv(b, "category", s.category); kv(b, "why", s.why); kv(b, "action", s.action); kv(b, "detail", s.detail); num(b, "date", s.date);
        b.setLength(b.length() - 1);
        b.append("},");
    }

    private static void kv(StringBuilder b, String k, String v) { b.append('"').append(k).append("\":").append(quote(v)).append(','); }

    private static void num(StringBuilder b, String k, long v) { b.append('"').append(k).append("\":").append(v).append(','); }

    static String quote(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; s != null && i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c == '\n') b.append("\\n");
            else if (c == '\r') b.append("\\r");
            else if (c == '\t') b.append("\\t");
            else if (c < 0x20) b.append(String.format(Locale.US, "\\u%04x", (int) c));
            else b.append(c);
        }
        return b.append('"').toString();
    }

    /** The summary for the log: the score and every card you marked. */
    public static List<String> report(List<Card> cards, boolean withAgreeing) {
        List<String> out = new ArrayList<String>();
        Score s = score(cards, withAgreeing);
        out.add("V2 AGAINST V3 — " + s.total + " pictures: " + s.line() + (s.problems > 0 ? " · could not compare " + s.problems : ""));
        for (Card c : cards) {
            if (c.mark == NONE) continue;
            out.add(String.format(Locale.US, "  %s  %s (%s): v2 %s / %s | v3 %s / %s", c.mark == V2 ? "v2 right" : c.mark == V3 ? "v3 right" : "skipped ",
                    c.rel, c.area, PhotoCategory.label(c.v2.category), c.v2.action, PhotoCategory.label(c.v3.category), c.v3.action));
        }
        return out;
    }
}
