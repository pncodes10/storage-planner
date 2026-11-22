package com.storageplanner.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads the answer of Mirage (Cactus Compute's vision model, run by the phone in the Algorithm Lab): the kind of scene,
 * the objects it named and the lines of text it read. Boxes are on Mirage's 0..1000 scale of the picture's width and
 * height. See tools/mirage/README.md.
 */
public final class Mirage {
    private Mirage() {}

    /** What the Lab asks for: scene, object names and text lines with scores and boxes (no masks, they are long). */
    public static final String OPTIONS = "{\"sections\":[\"scene\",\"objects\",\"text\"],\"fields\":[\"score\",\"bbox\"]}";

    public static final class Thing {
        public final String name;          // an object's name, or a line of text
        public final double score;
        public final int[] box;            // x0, y0, x1, y1 on 0..1000, or null
        public String about = "";          // an object's colour, material, position...
        Thing(String name, double score, int[] box) { this.name = name; this.score = score; this.box = box; }
    }

    public static final class Answer {
        public final List<String> scene = new ArrayList<String>();     // "key value" pairs
        public final List<Thing> objects = new ArrayList<Thing>();
        public final List<Thing> text = new ArrayList<Thing>();

        /** The text lines joined, top to bottom as Mirage gave them. */
        public String allText() {
            StringBuilder b = new StringBuilder();
            for (Thing t : text) b.append(b.length() == 0 ? "" : "\n").append(t.name);
            return b.toString();
        }
    }

    /** Throws IllegalArgumentException if the answer is not JSON. */
    public static Answer read(String json) {
        Object root = Json.parse(json);
        Answer a = new Answer();
        Map<String, Object> sc = Json.obj(root, "scene");
        if (sc != null) for (Map.Entry<String, Object> e : sc.entrySet()) if (e.getValue() != null) a.scene.add(e.getKey().replace('_', ' ') + " " + e.getValue());
        for (Object o : Json.list(root, "objects")) {
            String n = Json.str(o, "name");
            if (n == null) continue;
            Thing t = new Thing(n, Json.num(o, "score", Double.NaN), box(o));
            StringBuilder b = new StringBuilder();
            Map<String, Object> d = Json.obj(o, "description");
            if (d != null) for (Object v : d.values()) if (v != null) b.append(b.length() == 0 ? "" : ", ").append(v);
            String pos = Json.str(o, "position");
            if (pos != null) b.append(b.length() == 0 ? "" : ", ").append(pos);
            t.about = b.toString();
            a.objects.add(t);
        }
        for (Object o : Json.list(root, "text")) {
            String n = Json.str(o, "string");
            if (n == null) n = Json.str(o, "text");
            if (n == null) continue;
            a.text.add(new Thing(n, Json.num(o, "score", Double.NaN), box(o)));
        }
        return a;
    }

    @SuppressWarnings("unchecked")
    static int[] box(Object o) {
        List<Object> l = Json.list(o, "bbox");
        if (l.size() != 4) return null;
        int[] b = new int[4];
        for (int k = 0; k < 4; k++) { if (!(l.get(k) instanceof Double)) return null; b[k] = (int) Math.round((Double) l.get(k)); }
        return b;
    }

    /** Lines of one or two characters below this score are dropped (icons read as letters: "C", "7", "W"). */
    public static final double FRAGMENT = 0.45;

    /** Text after clean-up, and what was taken out. */
    public static final class Clean {
        public final List<Thing> kept = new ArrayList<Thing>();
        public int merged, fragments;
    }

    /**
     * Mirage reads text in overlapping windows, so a caption can come out several times in pieces on the same row
     * ("bout this slow english", "vish parents knew about this slow english"). Lines whose boxes share most of their
     * height and overlap sideways are merged, keeping the longest; very short low-score lines are dropped. Low scores are
     * otherwise kept: they are not a reliable sign of a wrong reading (a number plate read well scored 0.40).
     */
    public static Clean clean(List<Thing> text) {
        Clean c = new Clean();
        List<Thing> l = new ArrayList<Thing>();
        for (Thing t : text) {
            if (t.name.trim().length() <= 2 && !(t.score >= FRAGMENT)) { c.fragments++; continue; }
            l.add(t);
        }
        boolean[] gone = new boolean[l.size()];
        for (int i = 0; i < l.size(); i++) {
            if (gone[i]) continue;
            for (int j = i + 1; j < l.size(); j++) {
                if (gone[j] || !sameRow(l.get(i).box, l.get(j).box)) continue;
                Thing a = l.get(i), b = l.get(j);
                boolean keepA = a.name.trim().length() > b.name.trim().length()
                        || a.name.trim().length() == b.name.trim().length() && !(b.score > a.score);
                c.merged++;
                if (keepA) gone[j] = true; else { gone[i] = true; break; }
            }
        }
        for (int i = 0; i < l.size(); i++) if (!gone[i]) c.kept.add(l.get(i));
        return c;
    }

    /** Two boxes on the same row: they share at least half the smaller one's height and overlap sideways. */
    public static boolean sameRow(int[] a, int[] b) {
        if (a == null || b == null) return false;
        int h = Math.min(a[3] - a[1], b[3] - b[1]);
        int vy = Math.min(a[3], b[3]) - Math.max(a[1], b[1]);
        int vx = Math.min(a[2], b[2]) - Math.max(a[0], b[0]);
        return h > 0 && vy * 2 >= h && vx > 0;
    }

    /**
     * The text as the app's text rules take it: each word with a confidence (0-100) from its line's score. Mirage's
     * scores run lower than Tesseract's for the same quality (clean print scores 0.6-0.8), so 0.30 maps to 70, the
     * level the rules count as read confidently.
     */
    public static TextRules.Words words(List<Thing> text) {
        TextRules.Words w = new TextRules.Words();
        for (Thing t : text) {
            int conf = Double.isNaN(t.score) ? 70 : (int) Math.round(Math.max(0, Math.min(99, 40 + 100 * t.score)));
            for (String s : t.name.trim().split("\\s+")) if (!s.isEmpty()) w.add(s, conf);
        }
        return w;
    }

    /** What Mirage's scene says about the kind of picture: "photograph", "screenshot", "graphic", or null when it did not say. */
    public static String style(Answer a) {
        String s = null;
        for (String p : a.scene) if (p.startsWith("style ")) s = p.substring(6).toLowerCase(Locale.ROOT);
        if (s == null) return null;
        if (s.contains("screenshot") || s.contains("interface")) return "screenshot";
        if (s.contains("photo")) return "photograph";
        return "graphic";         // graphic design, flat vector illustration, abstract art, poster, minimalist...
    }

    static final java.util.regex.Pattern PERSON = java.util.regex.Pattern.compile("\\b(man|woman|person|people|boy|girl|child|baby|kid|lady|men|women|face|bride|groom)\\b");
    static final java.util.regex.Pattern REFERENCE = java.util.regex.Pattern.compile(
            "\\b(price tag|label|sign|signboard|whiteboard|blackboard|menu|monitor|computer screen|screen|tv|television|notice|poster|parking|barcode|receipt|paper|document|card|meter)\\b");

    /** People Mirage named (each entity once). */
    public static int people(Answer a) {
        java.util.Set<String> seen = new java.util.HashSet<String>();
        int n = 0;
        for (Thing t : a.objects) if (PERSON.matcher(t.name.toLowerCase(Locale.ROOT)).find() && seen.add(t.name + "@" + java.util.Arrays.toString(t.box))) n++;
        return n;
    }

    /** Things that suggest a photo taken only to remember something (a sign, a price tag, a screen, a paper...). */
    public static List<String> reference(Answer a) {
        List<String> l = new ArrayList<String>();
        for (Thing t : a.objects) {
            String n = t.name.toLowerCase(Locale.ROOT);
            if (REFERENCE.matcher(n).find() && !l.contains(n)) l.add(n);
        }
        return l;
    }

    /** The Lab's lines for an answer. */
    public static List<String> lines(Answer a) {
        List<String> o = new ArrayList<String>();
        o.add("  scene: " + (a.scene.isEmpty() ? "(nothing said)" : join(a.scene, "; ")));
        if (a.objects.isEmpty()) o.add("  objects: none named");
        else {
            StringBuilder b = new StringBuilder();
            for (Thing t : a.objects) {
                b.append(b.length() == 0 ? "" : "; ").append(t.name);
                if (!Double.isNaN(t.score)) b.append(String.format(Locale.US, " %.2f", t.score));
                if (!t.about.isEmpty()) b.append(" (").append(t.about).append(')');
            }
            o.add("  objects (detection score): " + b);
        }
        if (a.text.isEmpty()) o.add("  text: no lines found");
        else {
            o.add(String.format(Locale.US, "  text: %d line%s (score, box on 0-1000):", a.text.size(), a.text.size() == 1 ? "" : "s"));
            for (Thing t : a.text)
                o.add(String.format(Locale.US, "    \"%s\" %s%s", t.name, Double.isNaN(t.score) ? "" : String.format(Locale.US, "%.2f", t.score),
                        t.box == null ? "" : String.format(Locale.US, " [%d,%d - %d,%d]", t.box[0], t.box[1], t.box[2], t.box[3])));
        }
        return o;
    }

    static String join(List<String> l, String sep) {
        StringBuilder b = new StringBuilder();
        for (String s : l) b.append(b.length() == 0 ? "" : sep).append(s);
        return b.toString();
    }
}
