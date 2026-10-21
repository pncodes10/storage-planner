package com.storageplanner.core;

import com.storageplanner.algorithms.Decision;
import com.storageplanner.algorithms.DeletionAlgorithm;
import com.storageplanner.algorithms.Registry;
import com.storageplanner.facts.PictureFacts;
import com.storageplanner.tools.category.PhotoCategory;
import com.storageplanner.tools.quality.PhotoQuality;
import com.storageplanner.tools.similarity.PerceptualHash;

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
    /** Whether each sorted picture will be needed again (by path), as the deletion algorithm decided. */
    public final Map<String, Decision> need = new HashMap<String, Decision>();
    /** The deletion algorithm that decided about pictures (Registry.current() unless chosen). */
    public final DeletionAlgorithm algorithm;

    public UnitBuilder(Scanner.Result r, Estimates est, int sdk) { this(r, est, sdk, Registry.current()); }

    public UnitBuilder(Scanner.Result r, Estimates est, int sdk, DeletionAlgorithm algorithm) {
        this.algorithm = algorithm;
        long now = r.now;
        Map<String, FileItem> byRel = new HashMap<String, FileItem>();
        for (FileItem f : r.files) byRel.put(f.rel, f);
        Map<String, AppItem> apps = new HashMap<String, AppItem>();
        for (AppItem a : r.apps) apps.put(a.pkg, a);

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
                if (Math.abs((double) c.width / c.height - ar) < 0.02 && PerceptualHash.hamming(c.phash, s.phash) <= SENT_MATCH_BITS) {
                    det.sentCopyOf.put(s.rel, c.rel); break;
                }
            }
        }

        // ---------------- per-file units
        java.util.Set<String> keepers = new java.util.HashSet<String>();
        keepers.addAll(det.duplicateOf.values()); keepers.addAll(det.burstExtraOf.values()); keepers.addAll(det.sentCopyOf.values());
        for (FileItem f : r.files) {
            Unit u = Unit.forFile(f);
            String a = f.area; long s = f.size; double age = f.ageDays(now);
            if (a.equals("thumbnails")) u.add(new Option("delete", Techniques.JUNK, s, Option.R0, true, "Thumbnail cache, regenerated by the gallery"));
            else if (a.equals("temp") && age > 1) u.add(new Option("delete", Techniques.JUNK, s, Option.R0, true, "Stale temporary or partial download"));
            else if (a.equals("residue")) u.add(new Option("delete", Techniques.JUNK, s, Option.R0, true, "Left behind by uninstalled " + f.pkg));
            else if (a.equals("ext_cache")) u.add(new Option("delete", Techniques.CACHE, s, Option.R0, true, "Cache of " + f.pkg));
            else if (a.equals("wa_status") && age > 1) u.add(new Option("delete", Techniques.JUNK, s, Option.R0, true, "Expired WhatsApp status"));
            else if (a.equals("trash")) u.add(new Option("delete", Techniques.TRASH, s, Option.R1, true, "Already in trash; purge now instead of after 30 days"));
            else if (a.equals("telegram_media")) u.add(new Option("delete", Techniques.TELEGRAM, s, Option.R1, true, "Re-downloadable from Telegram's cloud (Telegram > Settings > Data and storage > Storage usage)"));
            else if (a.equals("wa_old_backup")) u.add(new Option("delete", Techniques.WA_BACKUP, s, Option.R1, true, "Older local chat backup; WhatsApp keeps the newest"));

            String dupOf = det.duplicateOf.get(f.rel);
            if (dupOf != null) u.add(new Option("delete", Techniques.DUPLICATES, s, Option.R1, true, "Identical to " + dupOf));
            if (f.apkPackage != null) {
                AppItem inst = apps.get(f.apkPackage);
                if (inst != null && inst.versionCode >= f.apkVersion)
                    u.add(new Option("delete", Techniques.INSTALLED_APK, s, Option.R1, true, "Installer for " + inst.label + ", already installed"));
                else u.add(new Option("delete", Techniques.OLD_DOWNLOADS, s, Option.R4, true, "Installer for an app that is not installed"));
            }
            String sentOf = det.sentCopyOf.get(f.rel);
            if (sentOf != null) u.add(new Option("delete", Techniques.SENT, s, Option.R1, true, "Compressed copy of " + sentOf + ", which is kept"));
            String burstOf = det.burstExtraOf.get(f.rel);
            if (burstOf != null) u.add(new Option("delete", Techniques.BURSTS, s, Option.R4, true, burstWhy(f, byRel.get(burstOf))));
            if (det.blurry.contains(f.rel)) u.add(new Option("delete", Techniques.BLURRY, s, Option.R4, true, "Much softer than your other photos"));
            // pictures: suggested only when the deletion algorithm finds what they show no longer needed (1.9)
            if (f.category != null) {
                Decision fn = algorithm.decide(PictureFacts.of(f), now);
                need.put(f.rel, fn);
                if (fn.verdict == Decision.NOT_NEEDED && fn.technique != null)
                    u.add(new Option("delete", fn.technique, s, Option.R4, true, fn.why));
            }
            if (a.equals("download") && age > OLD_DOWNLOAD_DAYS && s >= OLD_DOWNLOAD_MIN && u.options.isEmpty())
                u.add(new Option("delete", Techniques.OLD_DOWNLOADS, s, Option.R4, true, "Download untouched for " + Math.round(age) + " days"));

            // re-encoding (estimated or calibrated sizes)
            if (f.type != null) {
                if (f.type.equals("jpeg") && (a.equals("camera") || a.equals("pictures")) && Estimates.bpp(f) >= 1.0)
                    u.add(new Option("reencode_photo", Techniques.PHOTOS, (long) (s * est.cameraPhoto), Option.R2, false,
                            "Visually identical at matched quality; " + est.photoSource));
                else if (f.type.equals("jpeg") && (a.equals("wa_image") || a.equals("wa_sent")) && Estimates.bpp(f) >= 0.5)
                    u.add(new Option("reencode_photo", Techniques.CHAT_IMAGES, (long) (s * est.chatImage), Option.R2, false,
                            "Visually identical at matched quality; " + est.chatSource));
                else if (f.type.equals("png") && a.equals("screenshot"))
                    u.add(new Option("lossless_image", Techniques.SCREENSHOTS, (long) (s * est.screenshotLossless), Option.R1, false,
                            "Pixel-identical; " + est.screenshotSource));
                else if ((f.type.equals("mp4") || f.type.equals("mkv")) && f.vcodec != null) {
                    double v = est.video(f);
                    if (v > 0) u.add(new Option("reencode_video", Techniques.VIDEOS, (long) (s * v), Option.R2, false,
                            String.format("H.264 at %.1f Mbps; HEVC at matched quality (estimate)", f.bitrate / 1e6)));
                } else if (a.equals("music") && f.bitrate > 0) {
                    double m = est.music(f);
                    if (m > 0) u.add(new Option("reencode_audio", Techniques.MUSIC, (long) (s * m), Option.R3, false,
                            String.format("%d kbps -> 128 kbps; small audible trade-off for some listeners", f.bitrate / 1000)));
                }
            }
            if (PhotoCategory.ID.equals(f.category)) {             // ID documents: only an identical extra copy may go
                for (java.util.Iterator<Option> it = u.options.iterator(); it.hasNext(); ) {
                    Option o = it.next();
                    if (o.action.equals("delete") && !o.technique.equals(Techniques.DUPLICATES)) it.remove();
                }
            }
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
                if (a.width == b.width && a.height == b.height && PerceptualHash.hamming(a.phash, b.phash) <= NEAR_DUP_BITS)
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
