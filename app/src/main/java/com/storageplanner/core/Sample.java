package com.storageplanner.core;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;

/**
 * "Try on my pictures": picks a number of pictures at random from each chosen place on the phone (camera, screenshots,
 * downloads, WhatsApp, ...) and runs the sorting and the rules on each, exactly as the Algorithm Lab does for one picture.
 * Read-only: it lists folders and decodes pictures, nothing else.
 *
 * Like the Lab, each picture is judged on its own: facts that need the whole phone (duplicates, bursts, blur compared
 * with your other photos, sent copies) are unknown here, so rules that test them do not match.
 */
public final class Sample {
    private Sample() {}

    /** The places a sample can come from (storage areas, see Classifier), in the order shown. */
    public static final String[] SOURCES = {"camera", "screenshot", "download", "wa_image", "wa_sent", "pictures"};
    public static final int MAX_EACH = 100000;
    /** Pictures whose every line goes to the log; after these, one line each (a full entry is about 6 KB). */
    public static final int DETAIL_MAX = 300;

    public static String label(String area) {
        switch (area) {
            case "camera": return "Camera photos";
            case "screenshot": return "Screenshots";
            case "download": return "Downloads";
            case "wa_image": return "WhatsApp images (received)";
            case "wa_sent": return "WhatsApp images (sent)";
            case "pictures": return "Other pictures (Pictures folder)";
            default: return area;
        }
    }

    /** The folders under the storage root where those places are; everything else is not walked. */
    static final String[] ROOTS = {"DCIM", "Pictures", "Download", "WhatsApp", "WhatsApp Business",
            "Android/media/com.whatsapp", "Android/media/com.whatsapp.w4b"};

    public static boolean pictureName(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp") || n.endsWith(".heic") || n.endsWith(".heif");
    }

    /**
     * Every picture (by its name) in the wanted places, by place. Never follows symbolic links out of a folder.
     * Hidden files (".trashed-...", ".pending-...") belong to other areas and are left out by the area check.
     */
    public static Map<String, List<FileItem>> list(Platform pf, Set<String> areas, Scanner.Progress pr) {
        Map<String, List<FileItem>> out = new LinkedHashMap<String, List<FileItem>>();
        for (String a : areas) out.put(a, new ArrayList<FileItem>());
        File root = new File(pf.storageRoot());
        String rootPath = root.getAbsolutePath();
        Set<String> none = new HashSet<String>();
        Set<String> walked = new HashSet<String>();
        int seen = 0;
        for (String r : ROOTS) {
            File start = new File(root, r);
            if (!start.isDirectory()) continue;
            ArrayDeque<File> stack = new ArrayDeque<File>();
            stack.push(start);
            while (!stack.isEmpty()) {
                if (pr.cancelled()) return out;
                File d = stack.pop();
                String dp = d.getAbsolutePath();
                if (!walked.add(dp)) continue;                       // WhatsApp under Android/media is not walked twice
                File[] kids = d.listFiles();
                if (kids == null) continue;
                for (File k : kids) {
                    String abs = k.getAbsolutePath();
                    if (!abs.startsWith(rootPath + "/")) continue;
                    if (k.isDirectory()) { if (!isSymlink(k)) stack.push(k); continue; }
                    if (!pictureName(k.getName())) continue;
                    String rel = abs.substring(rootPath.length() + 1);
                    String area = Classifier.classify(rel, none)[0];
                    List<FileItem> l = out.get(area);
                    if (l == null) continue;
                    FileItem f = new FileItem(rel, abs, k.length(), k.lastModified());
                    f.area = area;
                    l.add(f);
                    if (++seen % 500 == 0) pr.update("Listing pictures", seen, 0);
                }
            }
        }
        for (List<FileItem> l : out.values())                         // a fixed order, so the same seed picks the same pictures
            Collections.sort(l, new java.util.Comparator<FileItem>() { public int compare(FileItem a, FileItem b) { return a.rel.compareTo(b.rel); } });
        return out;
    }

    private static boolean isSymlink(File f) {
        try { return java.nio.file.Files.isSymbolicLink(f.toPath()); } catch (Throwable e) { return true; }
    }

    /** n pictures at random from all (all of them, in random order, when there are no more than n). */
    public static List<FileItem> pick(List<FileItem> all, int n, Random rnd) {
        List<FileItem> l = new ArrayList<FileItem>(all);
        int k = Math.min(Math.max(0, n), l.size());
        for (int i = 0; i < k; i++) Collections.swap(l, i, i + rnd.nextInt(l.size() - i));   // partial Fisher-Yates
        return new ArrayList<FileItem>(l.subList(0, k));
    }

    /** n newest pictures (by date modified; the path breaks a tie), so bursts and near-identical shots stay together. */
    public static List<FileItem> newest(List<FileItem> all, int n) {
        List<FileItem> l = new ArrayList<FileItem>(all);
        Collections.sort(l, new java.util.Comparator<FileItem>() {
            public int compare(FileItem a, FileItem b) { return a.mtime != b.mtime ? (a.mtime > b.mtime ? -1 : 1) : a.rel.compareTo(b.rel); }
        });
        return new ArrayList<FileItem>(l.subList(0, Math.min(Math.max(0, n), l.size())));
    }

    // ------------------------------------------------------------------ a limited Analyse
    /**
     * A limit for the main analysis: how many pictures from each place, picked at random (seed) or the newest. A scan with a
     * limit keeps only those pictures and nothing else (no other files, no apps), and runs the full pipeline on them:
     * duplicates, bursts, blur against the other picked photos, sorting, the rules and the plan.
     */
    public static final class Limit {
        public final Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
        public long seed;
        public boolean newest;
        /** When above 0: this many pictures in all, picked from every place together; the per-place counts are then ignored. */
        public int totalCap;

        public int total() { if (totalCap > 0) return totalCap; int n = 0; for (Integer v : counts.values()) n += v == null ? 0 : v; return n; }

        /** "100 camera photos, 20 screenshots (random)". */
        public String describe() {
            if (totalCap > 0) return totalCap + " pictures" + (newest ? " (newest)" : " (random)");
            StringBuilder b = new StringBuilder();
            for (Map.Entry<String, Integer> e : counts.entrySet()) {
                if (e.getValue() == null || e.getValue() <= 0) continue;
                if (b.length() > 0) b.append(", ");
                b.append(e.getValue()).append(' ').append(label(e.getKey()).toLowerCase(Locale.ROOT));
            }
            return b.append(newest ? " (newest)" : " (random)").toString();
        }
    }

    /**
     * The files a limited scan keeps: from each place in the limit, that many pictures (by name, as list() finds them);
     * everything else is left out. Same files and seed give the same pictures. Keeps the order of files.
     */
    public static List<FileItem> restrict(List<FileItem> files, Limit lim) {
        if (lim.totalCap > 0) {                                     // one number for everything: pick from all places together
            List<FileItem> all = new ArrayList<FileItem>();
            Set<String> places = new HashSet<String>(java.util.Arrays.asList(SOURCES));
            for (FileItem f : files) if (places.contains(f.area) && pictureName(f.name())) all.add(f);
            Collections.sort(all, new java.util.Comparator<FileItem>() { public int compare(FileItem x, FileItem y) { return x.rel.compareTo(y.rel); } });
            int n = Math.min(lim.totalCap, all.size());
            Set<FileItem> chosen = new HashSet<FileItem>(lim.newest ? newest(all, n) : pick(all, n, new Random(lim.seed)));
            List<FileItem> out = new ArrayList<FileItem>();
            for (FileItem f : files) if (chosen.contains(f)) out.add(f);
            return out;
        }
        Map<String, List<FileItem>> by = new LinkedHashMap<String, List<FileItem>>();
        for (FileItem f : files) {
            Integer n = lim.counts.get(f.area);
            if (n == null || n <= 0 || !pictureName(f.name())) continue;
            List<FileItem> l = by.get(f.area);
            if (l == null) { l = new ArrayList<FileItem>(); by.put(f.area, l); }
            l.add(f);
        }
        Set<FileItem> keep = new HashSet<FileItem>();
        Random rnd = new Random(lim.seed);
        for (String a : lim.counts.keySet()) {                       // the limit's order, so the random picks do not depend on the walk
            List<FileItem> l = by.get(a);
            if (l == null) continue;
            Collections.sort(l, new java.util.Comparator<FileItem>() { public int compare(FileItem x, FileItem y) { return x.rel.compareTo(y.rel); } });
            int n = Math.min(lim.counts.get(a), MAX_EACH);
            keep.addAll(lim.newest ? newest(l, n) : pick(l, n, rnd));
        }
        List<FileItem> out = new ArrayList<FileItem>();
        for (FileItem f : files) if (keep.contains(f)) out.add(f);
        return out;
    }

    /** One picture of the sample. */
    public static final class Item {
        public final String source;
        public final FileItem file;
        public String category;                 // null when it could not be read
        public String problem;                  // why it was skipped
        public final List<Option> options = new ArrayList<Option>();
        public final List<String> matched = new ArrayList<String>();
        Item(String source, FileItem file) { this.source = source; this.file = file; }
    }

    /** What the sample found. */
    public static final class Result {
        public final Map<String, Integer> available = new LinkedHashMap<String, Integer>();   // pictures found in each place
        public final Map<String, Integer> asked = new LinkedHashMap<String, Integer>();
        public final List<Item> items = new ArrayList<Item>();
        public long ms;
        public boolean cancelled;

        public int done() { int n = 0; for (Item i : items) if (i.category != null) n++; return n; }
        public int skipped() { int n = 0; for (Item i : items) if (i.problem != null) n++; return n; }

        /** Category -> count, for one place or (source null) all. */
        public Map<String, Integer> categories(String source) {
            Map<String, Integer> m = new TreeMap<String, Integer>();
            for (Item i : items) if (i.category != null && (source == null || source.equals(i.source))) inc(m, PhotoCategory.label(i.category));
            return sortByCount(m);
        }

        /** "Delete — Accidental shot" -> count, over the options the plan would get; "No suggestion (kept)" for the rest. */
        public Map<String, Integer> suggestions(String source) {
            Map<String, Integer> m = new TreeMap<String, Integer>();
            for (Item i : items) {
                if (i.category == null || source != null && !source.equals(i.source)) continue;
                if (i.options.isEmpty()) { inc(m, "No suggestion (kept)"); continue; }
                for (Option o : i.options) inc(m, actionWord(o.action) + " — " + o.technique);
            }
            return sortByCount(m);
        }

        /** Bytes the suggestions would free (the biggest saving of each picture; a picture is deleted or compressed, not both). */
        public long freed(String source) {
            long b = 0;
            for (Item i : items) {
                if (source != null && !source.equals(i.source)) continue;
                long best = 0;
                for (Option o : i.options) best = Math.max(best, o.saved);
                b += best;
            }
            return b;
        }
    }

    public static String actionWord(String action) {
        if ("delete".equals(action)) return "Delete";
        if (action != null && action.startsWith("reencode") || "lossless_image".equals(action)) return "Compress";
        return action;
    }

    private static void inc(Map<String, Integer> m, String k) { Integer v = m.get(k); m.put(k, v == null ? 1 : v + 1); }

    private static Map<String, Integer> sortByCount(Map<String, Integer> m) {
        List<Map.Entry<String, Integer>> l = new ArrayList<Map.Entry<String, Integer>>(m.entrySet());
        Collections.sort(l, new java.util.Comparator<Map.Entry<String, Integer>>() {
            public int compare(Map.Entry<String, Integer> a, Map.Entry<String, Integer> b) {
                return !a.getValue().equals(b.getValue()) ? b.getValue() - a.getValue() : a.getKey().compareTo(b.getKey());
            }
        });
        Map<String, Integer> out = new LinkedHashMap<String, Integer>();
        for (Map.Entry<String, Integer> e : l) out.put(e.getKey(), e.getValue());
        return out;
    }

    /** Called after each picture, for a live view. */
    public interface Listener { void picture(Item it, int done, int total); }

    /**
     * Lists the wanted places, picks want.get(place) pictures at random from each (seed: the same seed on the same files
     * picks the same pictures), and sorts each one and runs the rules on it. detail gets the Lab's full lines for every
     * picture (category measurements, text read, every rule's verdict) for the first DETAIL_MAX pictures, then one line
     * each; lst is told after each picture.
     */
    public static Result run(Platform pf, Map<String, Integer> want, RuleSet rules, long now, long seed,
                             Scanner.Progress pr, Lab.Out detail, Listener lst) {
        long t0 = System.currentTimeMillis();
        Result res = new Result();
        Set<String> areas = new java.util.LinkedHashSet<String>();
        for (Map.Entry<String, Integer> e : want.entrySet()) if (e.getValue() != null && e.getValue() > 0) areas.add(e.getKey());
        pr.update("Listing pictures", 0, 0);
        Map<String, List<FileItem>> all = list(pf, areas, pr);
        Random rnd = new Random(seed);
        List<Item> todo = new ArrayList<Item>();
        for (String a : areas) {
            List<FileItem> l = all.get(a);
            int n = Math.min(want.get(a), MAX_EACH);
            res.available.put(a, l == null ? 0 : l.size());
            res.asked.put(a, n);
            if (l != null) for (FileItem f : pick(l, n, rnd)) todo.add(new Item(a, f));
        }
        detail.line("TRY ON MY PICTURES — seed " + seed);
        for (String a : areas)
            detail.line("  " + label(a) + ": " + res.asked.get(a) + " asked, " + res.available.get(a) + " on the phone"
                    + (res.available.get(a) < res.asked.get(a) ? " (all of them used)" : ""));
        for (int k = 0; k < todo.size(); k++) {
            if (pr.cancelled()) { res.cancelled = true; break; }
            Item it = todo.get(k);
            pr.update("Picture", k + 1, todo.size());
            FileItem f = it.file;
            boolean full = k < DETAIL_MAX;
            if (k == DETAIL_MAX) detail.line("\n(after " + DETAIL_MAX + " pictures, one line each: the full details would make the log too big)");
            if (full) { detail.line(""); detail.line("[" + (k + 1) + "/" + todo.size() + "] " + label(it.source) + ": " + f.rel); }
            Lab.Out out = full ? detail : new Lab.Out() { public void line(String s) {} };
            try {
                String type = Classifier.magic(f.abs, f.size);
                if (!"jpeg".equals(type) && !"png".equals(type) && !"webp".equals(type) && !"heic".equals(type) && !"avif".equals(type)) {
                    it.problem = "not a picture (file type " + type + ")";
                    out.line("  skipped: " + it.problem);
                } else {
                    Lab.Pic p = new Lab.Pic(f.abs, f.name(), f.size, f.mtime);
                    p.area = f.area; p.rel = f.rel; p.type = type;
                    Lab.Shown sh = Lab.rules(pf, p, rules, now, out);
                    if (sh.file == null) it.problem = "could not be decoded";
                    else {
                        it.category = sh.file.category;
                        it.options.addAll(sh.options);
                        it.matched.addAll(sh.matched);
                        f.type = type; f.category = sh.file.category; f.faces = sh.file.faces;
                    }
                }
            } catch (Throwable t) {
                it.problem = "failed: " + t;
                out.line("  ! " + it.problem);
            }
            if (!full) {
                StringBuilder b = new StringBuilder("[" + (k + 1) + "/" + todo.size() + "] " + f.rel + ": ");
                if (it.problem != null) b.append("skipped, ").append(it.problem);
                else {
                    b.append(PhotoCategory.label(it.category));
                    for (Option o : it.options) b.append("; ").append(actionWord(o.action)).append(" by ").append(o.technique);
                }
                detail.line(b.toString());
            }
            res.items.add(it);
            if (lst != null) lst.picture(it, k + 1, todo.size());
        }
        res.ms = System.currentTimeMillis() - t0;
        return res;
    }

    /** The summary as text lines (for the screen and the log). */
    public static List<String> summary(Result r) {
        List<String> out = new ArrayList<String>();
        out.add(String.format(Locale.US, "%d pictures sorted%s%s in %.1f min", r.done(),
                r.skipped() > 0 ? ", " + r.skipped() + " skipped" : "", r.cancelled ? " (stopped early)" : "", r.ms / 60000.0));
        for (String a : r.asked.keySet()) {
            int n = 0;
            for (Item i : r.items) if (i.source.equals(a) && i.category != null) n++;
            out.add("");
            out.add(label(a) + ": " + n + " of " + r.available.get(a) + " on the phone"
                    + String.format(Locale.US, "; suggestions would free about %.1f MB", r.freed(a) / 1e6));
            for (Map.Entry<String, Integer> e : r.categories(a).entrySet()) out.add("  " + e.getValue() + "  " + e.getKey());
            out.add("  suggestions:");
            for (Map.Entry<String, Integer> e : r.suggestions(a).entrySet()) out.add("    " + e.getValue() + "  " + e.getKey());
        }
        return out;
    }
}
