package com.storageplanner.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The final plan: one action for every file the analysis found, delete, compress or keep, grouped by file type.
 * Each file starts with what the rules suggest (its delete option, else its compress option, else keep) and you
 * change it file by file; your choices win. Earlier decisions from the review screens count as choices too (kept:
 * keep; approved: that action). Pure Java: the screens show it, nothing here touches a file.
 */
public final class FinalPlan {
    private FinalPlan() {}

    public static final String DELETE = "delete", COMPRESS = "compress", KEEP = "keep";
    public static final String[] ACTIONS = {DELETE, COMPRESS, KEEP};

    /** File types in the order shown (Tags.kind). */
    public static final String[] TYPES = {Tags.IMAGE, Tags.VIDEO, Tags.AUDIO, Tags.DOCUMENT, Tags.APK, Tags.OTHER};

    public static String typeLabel(String type) {
        switch (type) {
            case Tags.IMAGE: return "Photos & images";
            case Tags.VIDEO: return "Videos";
            case Tags.AUDIO: return "Music & audio";
            case Tags.DOCUMENT: return "Documents";
            case Tags.APK: return "App installers (APK)";
            default: return "Other files";
        }
    }

    public static String actionLabel(String action) {
        return DELETE.equals(action) ? "Delete" : COMPRESS.equals(action) ? "Compress" : "Keep";
    }

    /** One file of the plan. */
    public static final class Entry {
        public final FileItem file;
        public final Unit unit;                 // null when no rule has anything for the file
        public final String type;
        public final Option suggested;          // the rules' choice, null = keep
        public final String suggestedAction;
        public String action;                   // the final action
        public Option option;                   // what the action does (null for keep)
        public boolean chosen;                  // you set it (not just the suggestion)

        Entry(FileItem f, Unit u) {
            file = f; unit = u; type = Tags.kind(f);
            suggested = suggestion(u);
            suggestedAction = actionOf(suggested);
        }

        /** Why it is suggested, for the screens ("Exact duplicate", "Old meme or forward"...); "" for keep. */
        public String reason() { return suggested == null ? "" : suggested.technique; }

        /** Bytes the final action frees (estimates for compressing). */
        public long saved() { return option == null ? 0 : option.saved; }
    }

    /** The rules' suggestion for a unit: its delete option, else its first compress option, else null (keep). */
    public static Option suggestion(Unit u) {
        if (u == null) return null;
        for (Option o : u.options) if (o.action.equals("delete")) return o;
        for (Option o : u.options) if (compress(o)) return o;
        return null;
    }

    static boolean compress(Option o) { return o.action.startsWith("reencode") || o.action.equals("lossless_image"); }

    public static String actionOf(Option o) { return o == null ? KEEP : o.action.equals("delete") ? DELETE : compress(o) ? COMPRESS : KEEP; }

    /**
     * The plan for these files. units: by path (Unit.id); choices: your choices by path (delete / compress / keep);
     * kept / approved: decisions from the review screens (path -> technique), used when there is no choice. Files in
     * the phone's trash are left out (already deleted).
     */
    public static List<Entry> build(Collection<FileItem> files, Map<String, Unit> units, Map<String, String> choices,
                                    Set<String> kept, Map<String, String> approved) {
        List<Entry> out = new ArrayList<Entry>();
        for (FileItem f : files) {
            if ("trash".equals(f.area)) continue;
            Entry e = new Entry(f, units.get(f.rel));
            String c = choices == null ? null : choices.get(f.rel);
            if (c == null && kept != null && kept.contains(f.rel)) c = KEEP;
            if (c == null && approved != null && approved.containsKey(f.rel) && e.unit != null) {
                Option o = Review.optionFor(e.unit, approved.get(f.rel));
                if (o != null) c = actionOf(o);
            }
            e.chosen = c != null;
            set(e, c == null ? e.suggestedAction : c);
            out.add(e);
        }
        return out;
    }

    /** Sets an entry's final action; a compress needs a compress option (else the entry is kept). */
    public static void set(Entry e, String action) {
        if (DELETE.equals(action)) {
            Option d = e.unit == null ? null : Review.deleteOption(e.unit);
            e.action = DELETE;
            e.option = d != null ? d : new Option("delete", Techniques.PICKED, e.file.size, Option.R4, true, "You chose to delete it");
        } else if (COMPRESS.equals(action)) {
            Option c = null;
            if (e.unit != null) for (Option o : e.unit.options) if (compress(o)) { c = o; break; }
            e.action = c == null ? KEEP : COMPRESS;
            e.option = c;
        } else {
            e.action = KEEP;
            e.option = null;
        }
    }

    /** Whether this action can be chosen for the entry (compress needs a compress option). */
    public static boolean possible(Entry e, String action) {
        if (!COMPRESS.equals(action)) return true;
        if (e.unit == null) return false;
        for (Option o : e.unit.options) if (compress(o)) return true;
        return false;
    }

    /** Counts and bytes. */
    public static final class Totals {
        public final Map<String, Integer> count = new LinkedHashMap<String, Integer>();
        public final Map<String, Long> bytes = new LinkedHashMap<String, Long>();       // size of the files
        public final Map<String, Long> saved = new LinkedHashMap<String, Long>();       // what the action frees
        public int files;
        public long size;

        Totals() { for (String a : ACTIONS) { count.put(a, 0); bytes.put(a, 0L); saved.put(a, 0L); } }

        public int count(String a) { return count.get(a); }
        public long bytes(String a) { return bytes.get(a); }
        public long saved(String a) { return saved.get(a); }
        public long freed() { return saved(DELETE) + saved(COMPRESS); }
    }

    /** Totals of the entries of one type (null: all). */
    public static Totals totals(List<Entry> l, String type) {
        Totals t = new Totals();
        for (Entry e : l) {
            if (type != null && !type.equals(e.type)) continue;
            t.files++; t.size += e.file.size;
            t.count.put(e.action, t.count.get(e.action) + 1);
            t.bytes.put(e.action, t.bytes.get(e.action) + e.file.size);
            t.saved.put(e.action, t.saved.get(e.action) + e.saved());
        }
        return t;
    }

    /**
     * The entries a tab shows: for delete and compress, every entry the rules suggested it for plus every entry whose
     * final action it is (so an unticked one stays in place and can be ticked again); for keep, the entries kept now.
     * Grouped by reason (largest group first), each group largest file first.
     */
    public static List<Entry> tab(List<Entry> l, String type, final String action) {
        List<Entry> out = new ArrayList<Entry>();
        for (Entry e : l) {
            if (type != null && !type.equals(e.type)) continue;
            boolean in = KEEP.equals(action) ? KEEP.equals(e.action) : action.equals(e.action) || action.equals(e.suggestedAction);
            if (in) out.add(e);
        }
        final Map<String, Long> groupBytes = new LinkedHashMap<String, Long>();
        for (Entry e : out) { String g = group(e, action); Long b = groupBytes.get(g); groupBytes.put(g, (b == null ? 0 : b) + e.file.size); }
        Collections.sort(out, new Comparator<Entry>() {
            public int compare(Entry x, Entry y) {
                String gx = group(x, action), gy = group(y, action);
                if (!gx.equals(gy)) {
                    int c = Long.compare(groupBytes.get(gy), groupBytes.get(gx));
                    return c != 0 ? c : gx.compareTo(gy);
                }
                return Long.compare(y.file.size, x.file.size);
            }
        });
        return out;
    }

    /** The group an entry is shown under in a tab: its reason, or for keep where it comes from. */
    public static String group(Entry e, String action) {
        if (KEEP.equals(action)) {
            if (!KEEP.equals(e.suggestedAction)) return "You chose to keep";       // only a choice keeps what the rules would change
            return "Nothing to do";
        }
        if (e.suggested != null && action.equals(e.suggestedAction)) return e.suggested.technique;
        return "You chose to " + actionLabel(action).toLowerCase(java.util.Locale.ROOT);
    }

    // ------------------------------------------------------------------ choices as text (one "action<TAB>path" a line)
    public static String toText(Map<String, String> choices) {
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, String> e : choices.entrySet()) b.append(e.getValue()).append('\t').append(e.getKey()).append('\n');
        return b.toString();
    }

    public static Map<String, String> fromText(String s) {
        Map<String, String> m = new LinkedHashMap<String, String>();
        if (s == null) return m;
        for (String line : s.split("\n")) {
            int i = line.indexOf('\t');
            if (i <= 0) continue;
            String a = line.substring(0, i), p = line.substring(i + 1);
            if ((a.equals(DELETE) || a.equals(COMPRESS) || a.equals(KEEP)) && !p.isEmpty()) m.put(p, a);
        }
        return m;
    }
}
