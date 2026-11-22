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
