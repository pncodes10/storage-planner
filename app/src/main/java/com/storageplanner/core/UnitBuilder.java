package com.storageplanner.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Stage 2: turn scan results into decision units with options (port of the prototype's actions.py). */
public final class UnitBuilder {

    // thresholds fixed in the research prototype (not tuned per device)
    public static final double ARCHIVE_IDLE_DAYS = 60, UNINSTALL_IDLE_DAYS = 120;
    public static final double BLUR_REL = 0.25;
    public static final int NEAR_DUP_BITS = 10, SENT_MATCH_BITS = 8;
    public static final long BURST_WINDOW_MS = 10 * 60 * 1000L;
    static final long DAY = 86400000L;
    public static final double OLD_DOWNLOAD_DAYS = 180; public static final long OLD_DOWNLOAD_MIN = 1000000;

    public static final class Detections {
        public final Map<String, String> duplicateOf = new LinkedHashMap<String, String>();
        public final Map<String, String> burstExtraOf = new LinkedHashMap<String, String>();
        public final List<String> blurry = new ArrayList<String>();
        public final Map<String, String> sentCopyOf = new LinkedHashMap<String, String>();
        public int duplicateGroups, burstGroups;
    }

    public final List<Unit> units = new ArrayList<Unit>();
    public final Detections det = new Detections();

    /** With the built-in rules (what the app does today). */
    public UnitBuilder(Scanner.Result r, Estimates est, int sdk) { this(r, est, sdk, RuleSet.defaults()); }

    public UnitBuilder(Scanner.Result r, Estimates est, int sdk, RuleSet rules) {
        long now = r.now;

        // ---------------- apps
        for (AppItem a : r.apps) {
            if (a.cacheBytes > 0) {
                Unit cu = Unit.forCache(a);
                cu.add(new Option("clear_cache", Techniques.CACHE, a.cacheBytes, Option.R0, true,
                        "Cache of " + a.label + " (rebuilt automatically)"));
                units.add(cu);
            }
            if (a.isSystem || !a.hasLauncher || a.isEssential) continue;
            double idle = a.idleDays(now);
            Unit u = Unit.forApp(a);
            if (sdk >= 35 && idle >= ARCHIVE_IDLE_DAYS)
                u.add(new Option("archive_app", Techniques.ARCHIVE, a.codeBytes, Option.R1, true,
                        a.label + " unused " + Math.round(idle) + " days; archiving keeps its data, reinstalls on tap"));
            if (idle >= UNINSTALL_IDLE_DAYS && (a.isGame || sdk < 35))
                u.add(new Option("uninstall_app", a.isGame ? Techniques.UNINSTALL_GAMES : Techniques.UNINSTALL_APPS,
                        a.codeBytes + a.dataBytes, Option.R4, true,
                        a.label + " unused " + Math.round(idle) + " days; removes the app and its data"));
            if (!u.options.isEmpty()) units.add(u);
        }

        // ---------------- exact duplicates (keep the copy in the most canonical place)
        final List<String> prio = Arrays.asList("camera", "screenshot", "document", "music", "movies", "pictures",
                "download", "wa_doc", "wa_image", "wa_video", "telegram", "wa_sent", "wa_sent_video");
        Map<String, List<FileItem>> bySha = new HashMap<String, List<FileItem>>();
        for (FileItem f : r.files) if (f.sha256 != null) {
            List<FileItem> l = bySha.get(f.sha256);
            if (l == null) { l = new ArrayList<FileItem>(); bySha.put(f.sha256, l); }
            l.add(f);
        }
        for (List<FileItem> g : bySha.values()) {
            if (g.size() < 2) continue;
            Collections.sort(g, new Comparator<FileItem>() {
                public int compare(FileItem a, FileItem b) {
                    int pa = prio.indexOf(a.area), pb = prio.indexOf(b.area);
                    if (pa < 0) pa = 99; if (pb < 0) pb = 99;
                    if (pa != pb) return pa - pb;
                    if (a.rel.length() != b.rel.length()) return a.rel.length() - b.rel.length();
                    return a.rel.compareTo(b.rel);
                }
            });
            det.duplicateGroups++;
            for (int i = 1; i < g.size(); i++) det.duplicateOf.put(g.get(i).rel, g.get(0).rel);
        }

        // ---------------- bursts (near-duplicates taken within minutes) and blur, camera photos only
        List<FileItem> cam = cameraPhotos(r.files);
        for (List<FileItem> c : burstClusters(r.files)) {
            det.burstGroups++;
            FileItem keeper = bestShot(c);
            for (FileItem f : c) if (f != keeper) det.burstExtraOf.put(f.rel, keeper.rel);
        }
        if (!cam.isEmpty()) {
            double[] s = new double[cam.size()];
            for (int i = 0; i < s.length; i++) s[i] = cam.get(i).sharp;
            Arrays.sort(s);
            double med = s[s.length / 2];
            for (FileItem f : cam)
                if (f.sharp < BLUR_REL * med && !det.burstExtraOf.containsKey(f.rel)) det.blurry.add(f.rel);
        }

        // ---------------- WhatsApp "Sent" copies of photos still in the camera roll
        for (FileItem s : r.files) {
            if (!s.area.equals("wa_sent") || !s.hasPhash || s.height == 0) continue;
            double ar = (double) s.width / s.height;
            for (FileItem c : cam) {
                if (c.height == 0 || det.burstExtraOf.containsKey(c.rel) || det.blurry.contains(c.rel)) continue;
                if (Math.abs((double) c.width / c.height - ar) < 0.02 && ImageFeatures.hamming(c.phash, s.phash) <= SENT_MATCH_BITS) {
                    det.sentCopyOf.put(s.rel, c.rel); break;
                }
            }
        }
        // ---------------- and of camera videos (same length, same middle frame; VideoCheck)
        VideoCheck.sentCopies(r.files, det.sentCopyOf);

        // ---------------- per-file units: the file's tags, then the rules (BuiltInRules plus the user's own, see RuleSet)
        // Apps and app caches above stay hard-coded; rules are for files only.
        java.util.Set<String> keepers = new java.util.HashSet<String>();
        keepers.addAll(det.duplicateOf.values()); keepers.addAll(det.burstExtraOf.values()); keepers.addAll(det.sentCopyOf.values());
        Tags.Context tctx = Tags.context(r, det);
        RuleSet.Compiled compiled = rules.compile();
        for (FileItem f : r.files) {
            Unit u = Unit.forFile(f);
            compiled.apply(u, f, Tags.of(f, tctx, now), tctx, est, now);
            if (!u.options.isEmpty()) units.add(u);
            else if (keepers.contains(f.rel) || f.category != null) units.add(u);   // no options, but the user may pick it for deletion
        }
    }

    /** Camera photos with features, oldest first (the burst search input). */
    static List<FileItem> cameraPhotos(List<FileItem> files) {
        List<FileItem> cam = new ArrayList<FileItem>();
        for (FileItem f : files) if ("camera".equals(f.area) && f.hasPhash && f.sharp >= 0) cam.add(f);
        Collections.sort(cam, new Comparator<FileItem>() {
            public int compare(FileItem a, FileItem b) { return a.mtime < b.mtime ? -1 : a.mtime > b.mtime ? 1 : a.rel.compareTo(b.rel); }
        });
        return cam;
    }

    /** Groups of near-identical camera shots taken within minutes of each other (2+ photos each). */
    public static List<List<FileItem>> burstClusters(List<FileItem> files) {
        List<FileItem> cam = cameraPhotos(files);
        int[] parent = new int[cam.size()];
        for (int i = 0; i < parent.length; i++) parent[i] = i;
        for (int i = 0; i < cam.size(); i++) {
            FileItem a = cam.get(i);
            for (int j = i + 1; j < cam.size() && cam.get(j).mtime - a.mtime <= BURST_WINDOW_MS; j++) {
                FileItem b = cam.get(j);
                if (a.width == b.width && a.height == b.height && ImageFeatures.hamming(a.phash, b.phash) <= NEAR_DUP_BITS)
                    parent[find(parent, i)] = find(parent, j);
            }
        }
        Map<Integer, List<FileItem>> clusters = new LinkedHashMap<Integer, List<FileItem>>();
        for (int i = 0; i < cam.size(); i++) {
            int root = find(parent, i);
            List<FileItem> l = clusters.get(root);
            if (l == null) { l = new ArrayList<FileItem>(); clusters.put(root, l); }
            l.add(cam.get(i));
        }
        List<List<FileItem>> out = new ArrayList<List<FileItem>>();
        for (List<FileItem> c : clusters.values()) if (c.size() >= 2) out.add(c);
        return out;
    }

    /** The shot to keep: best by PhotoQuality (eyes, faces, sharpness, light) when measured, else the sharpest. */
    public static FileItem bestShot(List<FileItem> c) {
        boolean measured = true;
        for (FileItem f : c) if (f.quality == null) { measured = false; break; }
        if (measured) {
            List<PhotoQuality.Q> qs = new ArrayList<PhotoQuality.Q>();
            for (FileItem f : c) qs.add(f.quality);
            return c.get(PhotoQuality.best(qs));
        }
        FileItem keeper = c.get(0);
        for (FileItem f : c) if (f.sharp > keeper.sharp) keeper = f;
        return keeper;
    }

    static String burstWhy(FileItem f, FileItem keeper) {
        String name = keeper == null ? "another shot" : keeper.name();
        if (keeper == null || keeper.quality == null || f.quality == null) return "Near-duplicate of sharper " + (keeper == null ? "shot" : keeper.rel);
        List<String> why = PhotoQuality.reasons(keeper.quality, f.quality);
        if (why.isEmpty()) return "Near-duplicate of " + name + ", practically the same shot";
        StringBuilder b = new StringBuilder("Near-duplicate of a better shot, " + name + ": ");
        for (int i = 0; i < why.size(); i++) b.append(i == 0 ? "" : ", ").append(why.get(i).toLowerCase(java.util.Locale.ROOT));
        return b.toString();
    }

    private static int find(int[] p, int x) {
        while (p[x] != x) { p[x] = p[p[x]]; x = p[x]; }
        return x;
    }
}
