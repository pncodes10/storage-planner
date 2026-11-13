package com.storageplanner.v2test;

import com.storageplanner.v2.device.BlobStore;
import com.storageplanner.v2.util.Json;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * PHASE_S_STORE S7 (REVIEW_S F-13): GRAPH's decode budget is a per-run budget. Through the CLI (the app's ASSESS/PLAN path):
 * <ul>
 * <li>a run whose budget is smaller than the work ends COMPLETED with a full result; every image with an unfinished check is
 *     perceptual UNKNOWN, in no near group, on no perceptual edge, never NO_VERIFIED_BACKUP ("unique"), and has no removal or
 *     SAFE/NO_LOSS recommendation unless its recovery is structural (exact copy, verified backup, regenerable); removals ⊆ the
 *     unbudgeted run's; the session carries G_COMPARISON_INCOMPLETE with the count;</li>
 * <li>consecutive budgeted runs over one store converge (pending 0) to the unbudgeted result of the same scan number;</li>
 * <li>a kill inside the budgeted GRAPH resumes and converges the same way;</li>
 * <li>a budget larger than the work gives the unbudgeted result; decodes ≤ distinct pending images + components.</li>
 * </ul>
 * Equality: evaluate.py's IGNORED plus {@code graphCoverage.decodes} (work counter, F-18) and the {@code createdScanId} of
 * relationships and duplicate groups (an edge first made in run 3 is stamped 3; in one unbudgeted run it is stamped 1).
 * {@code main --tree DIR --budget N [--store S] [--mode M] [--out DIR]} runs the same chain on another tree (the dataset;
 * tools/v2eval/budget_converge.sh).
 */
public final class PerceptualBudgetTest {
    static final String NOW = "1792000000000";
    static final Set<String> RUN_STAMPS = new HashSet<String>(Arrays.asList("relationships.createdScanId", "duplicateGroups.createdScanId"));
    static final Set<String> STRUCTURAL_RECOVERY = new HashSet<String>(Arrays.asList("EXACT_COPY_KEPT", "BACKUP_VERIFIED",
            "REGENERABLE_CACHE", "REGENERABLE_INSTALLER"));
    static final Set<String> PERCEPTUAL_TYPES = new HashSet<String>(Arrays.asList("COMPRESSED_FROM", "THUMBNAIL_OF", "VERSION_OF",
            "EDITED_FROM", "VISUALLY_SIMILAR"));
    static final Set<String> REMOVALS = new HashSet<String>(Arrays.asList("DELETE", "QUARANTINE", "UNINSTALL_APP"));

    /** Families of an original with a compressed chat copy, a thumbnail, a rotated and a filtered edit, a look-alike, two burst frames. */
    static void tree(TempTree t, int families) throws Exception {
        for (int i = 0; i < families; i++) {
            BufferedImage o = PerceptualTest.scene(640, 480, 100 + i);
            String ts = String.format(Locale.ROOT, "202401%02d_10%02d10", 1 + i % 28, i % 60);
            t.file("DCIM/Camera/IMG_" + ts + ".jpg", FxImg.jpeg(o, 0.95f));
            t.file(String.format(Locale.ROOT, "WhatsApp/Media/WhatsApp Images/IMG-202401%02d-WA%04d.jpg", 1 + i % 28, i), FxImg.jpeg(PerceptualTest.scaled(o, 480, 360), 0.6f));
            t.file("DCIM/.thumbnails/" + i + ".jpg", FxImg.jpeg(PerceptualTest.scaled(o, 160, 120), 0.8f));
            if (i % 2 == 0) t.file("DCIM/Camera/IMG_" + ts + "_rotated.jpg", FxImg.jpeg(PerceptualTest.rot90(o), 0.9f));
            BufferedImage lay = PerceptualTest.copy(o); Graphics2D g = lay.createGraphics(); g.setColor(Color.RED); g.fillRect(160, 160, 64, 64); g.dispose();
            t.file("Pictures/lookalike" + i + ".jpg", FxImg.jpeg(lay, 0.9f));
            for (int k = 1; k <= 2; k++) {
                BufferedImage b = PerceptualTest.copy(o); Graphics2D h = b.createGraphics(); h.setColor(new Color(20 * k, 200, 40)); h.fillOval(40 * k, 300, 50, 50); h.dispose();
                t.file(String.format(Locale.ROOT, "DCIM/Camera/IMG_202401%02d_10%02d1%d.jpg", 1 + i % 28, i % 60, 2 * k), FxImg.jpeg(b, 0.92f));
            }
            if (i % 3 == 0) t.file("Download/copy" + i + ".jpg", t.read("DCIM/Camera/IMG_" + ts + ".jpg"));   // exact copy
        }
        for (int i = 0; i < 6; i++) t.file("Pictures/unique" + i + ".jpg", FxImg.jpeg(PerceptualTest.scene(640, 480, 900 + i), 0.9f));
    }

    static String opt(List<String> extra, String k, String d) { int i = extra.indexOf(k); return i >= 0 && i + 1 < extra.size() ? extra.get(i + 1) : d; }

    /** One CLI run; budget &lt; 0 = default (unbudgeted for these trees). */
    static Map<String, Object> run(Path tree, Path out, Path state, String store, String mode, boolean resume, BlobStore hook, int budget) throws Exception {
        List<String> a = new ArrayList<String>(Arrays.asList("scan", tree.toString(), "--out", out.toString(), "--now", NOW,
                "--target-bytes", "max", "--snapshot-every-ms", "0", "--store", store, "--mode", mode, "--state-dir", state.toString()));
        if (budget >= 0) { a.add("--graph-decode-budget"); a.add(Integer.toString(budget)); }
        if (resume) a.add("--resume");
        final BlobStore hs = hook;
        V2Cli.blobsHook = hs == null ? null : new java.util.function.Function<BlobStore, BlobStore>() { public BlobStore apply(BlobStore b) { return hs; } };
        PrintStream sink = new PrintStream(new ByteArrayOutputStream(), true, "UTF-8");
        try {
            int rc = V2Cli.run(a.toArray(new String[0]), sink, sink);
            if (rc != 0) throw new IllegalStateException("V2Cli exit " + rc);
        } finally { V2Cli.blobsHook = null; }
        @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) Json.parse(new String(Files.readAllBytes(out), StandardCharsets.UTF_8));
        return m;
    }

    @SuppressWarnings("unchecked") static Map<String, Object> map(Object o, String k) { return (Map<String, Object>) ((Map<String, Object>) o).get(k); }
    @SuppressWarnings("unchecked") static List<Object> list(Object o, String k) { Object v = ((Map<String, Object>) o).get(k); return v == null ? new ArrayList<Object>() : (List<Object>) v; }
    static String str(Object o, String k) { Object v = ((Map<?, ?>) o).get(k); return v == null ? null : String.valueOf(v); }
    static long num(Object o, String k) { Object v = ((Map<?, ?>) o).get(k); return v == null ? 0 : ((Number) v).longValue(); }
    static long pending(Map<String, Object> r) { return num(map(r, "graphCoverage"), "pending"); }

    static Object norm(Object r, boolean ignoreDecodes) {
        Set<String> ig = new HashSet<String>(DiskKillTest.IGNORED);
        if (!ignoreDecodes) ig.remove("graphCoverage.decodes");
        ig.addAll(RUN_STAMPS);
        return DiskKillTest.strip(r, "", ig);
    }

    static String incomplete(Map<String, Object> r) {
        for (Object e : list(map(r, "coverage"), "events")) if ("G_COMPARISON_INCOMPLETE".equals(str(e, "code"))) return str(e, "detail");
        return null;
    }

    static Set<String> removals(Map<String, Object> r) {
        Set<String> s = new TreeSet<String>();
        for (Object x : list(r, "recommendations")) if (REMOVALS.contains(str(x, "action"))) s.add(str(x, "fileId") + " " + str(x, "action"));
        return s;
    }

    /**
     * Safety of a budget-stopped result {@code r} against the converged one {@code full}. Returns the number of unchecked images
     * (perceptual UNKNOWN here, known in {@code full}).
     */
    static long safety(String label, Map<String, Object> r, Map<String, Object> full) {
        Map<String, String> fullStat = new HashMap<String, String>();
        for (Object f : list(full, "files")) fullStat.put(str(f, "fileId"), str(f, "perceptualStatus"));
        Set<String> unchecked = new TreeSet<String>(), unknown = new TreeSet<String>();
        int changedKnown = 0;
        Map<String, Object> fileById = new HashMap<String, Object>();
        for (Object f : list(r, "files")) {
            String id = str(f, "fileId"), ps = str(f, "perceptualStatus");
            fileById.put(id, f);
            if ("UNKNOWN".equals(ps)) { unknown.add(id); if (!"UNKNOWN".equals(fullStat.get(id))) unchecked.add(id); }
            else if (ps != null && !ps.equals(fullStat.get(id))) changedKnown++;
        }
        T.check(label + ": scan COMPLETED with a result", "COMPLETED".equals(str(map(r, "scan"), "state")) && !list(r, "files").isEmpty());
        T.check(label + ": some images are left unchecked (" + unchecked.size() + ")", !unchecked.isEmpty());
        T.eq(label + ": TRUE/FALSE statuses are final (never 'unique' before the comparison is done)", 0, changedKnown);
        T.eq(label + ": G_COMPARISON_INCOMPLETE = pendingImages", String.valueOf(num(map(r, "graphCoverage"), "pendingImages")), incomplete(r));
        int inGroup = 0, onEdge = 0, unique = 0, badRec = 0, superset = 0;
        for (Object g : list(r, "duplicateGroups")) {
            if ("EXACT".equals(str(g, "kind"))) continue;
            for (Object m : list(g, "members")) if (unknown.contains(String.valueOf(m))) inGroup++;
        }
        for (Object e : list(r, "relationships"))
            if (PERCEPTUAL_TYPES.contains(str(e, "type")) && (unknown.contains(str(e, "src")) || unknown.contains(str(e, "dst")))) onEdge++;
        for (String id : unknown) {
            Object a = ((Map<?, ?>) fileById.get(id)).get("assessment");
            if (a == null) continue;
            if ("NO_VERIFIED_BACKUP".equals(str(a, "recoverability"))) unique++;
            if ("SUPERSET_COPY".equals(str(a, "recoveryKind"))) superset++;
        }
        for (Object x : list(r, "recommendations")) {
            String id = str(x, "fileId");
            if (!unchecked.contains(id)) continue;      // images UNKNOWN for other reasons (not decodable, low contrast) are as before
            boolean risky = REMOVALS.contains(str(x, "action")) || "SAFE".equals(str(x, "tier")) || "NO_LOSS".equals(str(x, "tier"));
            Object a = ((Map<?, ?>) fileById.get(id)).get("assessment");
            if (risky && (a == null || !STRUCTURAL_RECOVERY.contains(str(a, "recoveryKind")))) badRec++;
        }
        T.eq(label + ": unknown images in a near group", 0, inGroup);
        T.eq(label + ": perceptual edges touching an unknown image", 0, onEdge);
        T.eq(label + ": unknown images rated NO_VERIFIED_BACKUP", 0, unique);
        T.eq(label + ": unknown images recoverable through a near copy", 0, superset);
        T.eq(label + ": removal / SAFE / NO_LOSS recommendations of unchecked images without structural recovery", 0, badRec);
        Set<String> rem = removals(r), fullRem = removals(full);
        Set<String> extra = new TreeSet<String>(rem); extra.removeAll(fullRem);
        T.check(label + ": removals (" + rem.size() + ") ⊆ the complete result's (" + fullRem.size() + ")" + (extra.isEmpty() ? "" : " extra " + extra), extra.isEmpty());
        for (Object it : list(map(r, "plan"), "items")) {
            if (!REMOVALS.contains(str(it, "action"))) continue;
            for (Object fid : list(it, "fileIds"))
                if (unchecked.contains(String.valueOf(fid)) && !rem.contains(fid + " " + str(it, "action"))) { T.check(label + ": plan item without a recommendation " + fid, false); }
        }
        return unchecked.size();
    }

    /** Budgeted runs over {@code state} until nothing is pending; returns the results in order (first = the first budgeted run). */
    static List<Map<String, Object>> chain(Path tree, TempTree w, Path state, String store, String mode, int budget, int maxRuns, String tag) throws Exception {
        List<Map<String, Object>> l = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < maxRuns; i++) {
            Map<String, Object> r = run(tree, w.path(tag + i + ".json"), state, store, mode, false, null, budget);
            l.add(r);
            if (pending(r) == 0) break;
        }
        return l;
    }

    static List<Map<String, Object>> reference(Path tree, TempTree w, String store, String mode, int runs, String tag) throws Exception {
        Path st = w.dir(tag + "-state");
        List<Map<String, Object>> l = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < runs; i++) l.add(run(tree, w.path(tag + i + ".json"), st, store, mode, false, null, -1));
        return l;
    }

    /** Convergence and safety for one tree; returns the number of budgeted runs needed. */
    static int converge(Path tree, TempTree w, String store, String mode, int budget, String label) throws Exception {
        List<Map<String, Object>> ch = chain(tree, w, w.dir(label + "-b"), store, mode, budget, 40, label + "-b");
        int k = ch.size();
        Map<String, Object> last = ch.get(k - 1);
        T.check(label + ": budget " + budget + " converged in " + k + " runs (pending 0, no G_COMPARISON_INCOMPLETE)", pending(last) == 0 && incomplete(last) == null && k > 1);
        // one more budgeted run after convergence (nothing left to decode)
        Map<String, Object> after = run(tree, w.path(label + "-after.json"), w.path(label + "-b"), store, mode, false, null, budget);
        List<Map<String, Object>> ref = reference(tree, w, store, mode, k + 1, label + "-ref");
        Object a = norm(last, true), b = norm(ref.get(k - 1), true);
        T.check(label + ": converged run " + k + " equals unbudgeted run " + k + " (decodes and creation stamps aside)" + (a.equals(b) ? "" : ": " + DiskKillTest.firstDiff("", b, a)), a.equals(b));
        Object c = norm(after, false), d = norm(ref.get(k), false);
        T.check(label + ": run " + (k + 1) + " equals unbudgeted run " + (k + 1) + " incl. decodes (creation stamps aside)" + (c.equals(d) ? "" : ": " + DiskKillTest.firstDiff("", d, c)), c.equals(d));
        Object e = norm(ref.get(0), true);
        Object f0 = DiskKillTest.strip(norm(last, true), "", new HashSet<String>(Arrays.asList("scan", "coverage", "plan.planId", "plan.planVersion", "files", "recommendations", "plan")));
        Object e0 = DiskKillTest.strip(e, "", new HashSet<String>(Arrays.asList("scan", "coverage", "plan.planId", "plan.planVersion", "files", "recommendations", "plan")));
        T.check(label + ": converged edges, groups and backups equal the single unbudgeted run's" + (f0.equals(e0) ? "" : ": " + DiskKillTest.firstDiff("", e0, f0)), f0.equals(e0));
        safety(label + " run 1", ch.get(0), last);
        for (int i = 1; i < k - 1; i++) safety(label + " run " + (i + 1), ch.get(i), last);
        long d1 = num(map(ch.get(0), "graphCoverage"), "decodes");
        T.check(label + ": run 1 decodes " + d1 + " ≤ budget + 2", d1 <= budget + 2);
        return k;
    }

    public static int run(String[] args) throws Exception {
        T.section("PerceptualBudget");
        TempTree t = TempTree.create(), w = TempTree.create();
        try {
            tree(t, 10);
            Path tr = t.root();
            Map<String, Object> full = run(tr, w.path("full.json"), w.dir("full"), "disk", "standard", false, null, -1);
            long gated = num(map(full, "graphCoverage"), "gatedPairs"), dec = num(map(full, "graphCoverage"), "decodes");
            T.check("fixture: gated pairs " + gated + ", decodes " + dec + ", removals " + removals(full).size(), gated >= 40 && !removals(full).isEmpty());
            T.check("unbudgeted: nothing pending, no G_COMPARISON_INCOMPLETE, no pendingImages key", pending(full) == 0 && incomplete(full) == null
                    && !map(full, "graphCoverage").containsKey("pendingImages"));
            // ordering: one decode per eligible image of a pair at most (components are small, the cache holds them)
            long images = num(map(full, "graphCoverage"), "eligibleImages");
            T.check("cache order: decodes " + dec + " ≤ images in pairs (≤ " + images + ")", dec <= images);
            Map<String, Object> big = run(tr, w.path("big.json"), w.dir("big"), "disk", "standard", false, null, 100000);
            T.check("budget larger than the work = unbudgeted, byte for byte (timings aside)", norm(big, false).equals(norm(full, false)));
            int k = converge(tr, w, "disk", "standard", 12, "disk b12");
            converge(tr, w, "memory", "standard", 25, "memory b25");
            System.out.println("  fixture: " + gated + " gated pairs, " + images + " eligible images, " + dec + " decodes unbudgeted; budget 12 converged in " + k + " runs");
            killed(tr, w);
        } finally { t.close(); w.close(); }
        return T.finish("PerceptualBudgetTest");
    }

    /**
     * Kill inside the first budgeted GRAPH (after appended bytes between the end of T3 and the end of GRAPH), resume, then
     * continue: the resumed run is safe, and the chain converges to the unbudgeted result of the same scan number.
     */
    static void killed(Path tr, TempTree w) throws Exception {
        T.section("PerceptualBudget kill");
        final int budget = 12;
        PowerLossBlobStore pb = new PowerLossBlobStore();
        final KillingBlobStore probe = new KillingBlobStore(pb);
        final long[] at = new long[2];
        V2Cli.afterStage = new java.util.function.BiConsumer<String, com.storageplanner.v2.store.V2Store>() {
            public void accept(String s, com.storageplanner.v2.store.V2Store st) {
                if (s.equals("T3_IMAGE")) at[0] = probe.appendedBytes();
                if (s.equals("GRAPH")) at[1] = probe.appendedBytes();
            }
        };
        try { run(tr, w.path("kp.json"), w.dir("kp"), "disk", "standard", false, probe, budget); }
        finally { V2Cli.afterStage = null; }
        T.check("probe: GRAPH appends bytes (" + at[0] + " .. " + at[1] + ")", at[0] > 0 && at[1] > at[0]);
        // unbudgeted reference chain, grown on demand to the scan number a killed chain needs (file ids, hence the
        // order of the checks and the number of runs, differ between fixture trees)
        Path refState = w.dir("kref-state");
        List<Map<String, Object>> ref = new ArrayList<Map<String, Object>>();
        Random r = new Random(17);
        int trials = 4, ok = 0;
        for (int i = 0; i < trials; i++) {
            PowerLossBlobStore inner = new PowerLossBlobStore();
            KillingBlobStore kb = new KillingBlobStore(inner).killAfterAppendedBytes(at[0] + 1 + (long) (r.nextDouble() * (at[1] - at[0] - 1)));
            Path st = w.dir("kill" + i);
            boolean killed = false;
            try { run(tr, w.path("k" + i + ".json"), st, "disk", "standard", false, kb, budget); }
            catch (SimulatedKill e) { killed = true; }
            T.check("trial " + i + ": killed inside GRAPH", killed);
            if (i % 2 == 1) inner.powerLoss(r, r.nextBoolean(), r.nextBoolean());
            Map<String, Object> res = run(tr, w.path("kr" + i + ".json"), st, "disk", "standard", true, inner, budget);
            T.check("trial " + i + ": resumed scan 1 completes", "1".equals(str(map(res, "scan"), "scanId")) && "COMPLETED".equals(str(map(res, "scan"), "state")));
            List<Map<String, Object>> rest = new ArrayList<Map<String, Object>>();
            Map<String, Object> last = res;
            for (int n = 2; pending(last) > 0 && n <= 30; n++) { last = run(tr, w.path("kc" + i + "-" + n + ".json"), st, "disk", "standard", false, inner, budget); rest.add(last); }
            int scans = (int) num(map(last, "scan"), "scanId");
            T.check("trial " + i + ": converged after " + scans + " scans", pending(last) == 0);
            if (pending(res) > 0) safety("trial " + i + " resumed", res, last);
            while (ref.size() < scans) ref.add(run(tr, w.path("kref" + ref.size() + ".json"), refState, "disk", "standard", false, null, -1));
            Object a = norm(last, true), b = norm(ref.get(scans - 1), true);
            if (a.equals(b)) ok++;
            else System.out.println("  trial " + i + ": converged result differs: " + DiskKillTest.firstDiff("", b, a));
        }
        T.eq("killed-in-GRAPH chains converge to the unbudgeted result", trials, ok);
    }

    /** Dataset mode: {@code --tree DIR --budget N [--store disk|memory] [--mode standard|aggressive] [--out DIR]}. */
    public static void main(String[] args) throws Exception {
        List<String> a = Arrays.asList(args);
        Path tree = java.nio.file.Paths.get(opt(a, "--tree", "."));
        int budget = Integer.parseInt(opt(a, "--budget", "200"));
        String store = opt(a, "--store", "disk"), mode = opt(a, "--mode", "standard"), out = opt(a, "--out", null);
        TempTree w = out == null ? TempTree.create() : null;
        try {
            TempTree ww = w != null ? w : TempTree.at(java.nio.file.Paths.get(out));
            int k = converge(tree, ww, store, mode, budget, "ds-" + store + "-" + mode);
            System.out.println("BUDGET_CONVERGE runs=" + k);
        } finally { if (w != null) w.close(); }
        System.exit(T.finish("PerceptualBudgetTest(" + tree + ")") == 0 ? 0 : 1);
    }
}
