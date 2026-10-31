package com.storageplanner.test.bench;

import com.storageplanner.algorithms.Decision;
import com.storageplanner.algorithms.DeletionAlgorithm;
import com.storageplanner.algorithms.Registry;
import com.storageplanner.core.Estimates;
import com.storageplanner.core.FileItem;
import com.storageplanner.core.Option;
import com.storageplanner.core.Scanner;
import com.storageplanner.core.Unit;
import com.storageplanner.core.UnitBuilder;
import com.storageplanner.test.JvmPlatform;
import com.storageplanner.tools.Tools;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Every deletion algorithm version on the same labelled test phone (testdata/futureneed/build.py). The scan runs
 * once, as on the phone (Scanner + Sorter: faces by YuNet from the pixels, words from the app's own text reader
 * recorded as "<picture>.ocr.json"); then UnitBuilder runs once per algorithm. For each version: not-needed
 * pictures suggested (caught), still-needed pictures suggested (wrong), and pictures left as "your call".
 * usage: AlgorithmBench <phone dir> [--list] [--algo <id>] [--check]
 *   --list   every mistake of the chosen algorithm (default: Registry.current()), with the text read
 *   --check  fail unless the chosen algorithm wrongly suggests nothing and catches at least 85%
 */
public final class AlgorithmBench {
    static final class Score { int caught, wrong, unsure; final Map<String, int[]> perKind = new LinkedHashMap<String, int[]>(); }

    public static void main(String[] a) throws Exception {
        boolean list = false, check = false;
        String chosen = Registry.current().id();
        for (int i = 1; i < a.length; i++) {
            if (a[i].equals("--list")) list = true;
            if (a[i].equals("--check")) check = true;
            if (a[i].equals("--algo") && i + 1 < a.length) chosen = a[++i];
        }
        String js = new String(Files.readAllBytes(new File(a[0], "labels.json").toPath()), StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("\"([^\"]+)\": \\{\\s*\"need\": \"([a-z]+)\",\\s*\"kind\": \"([a-z_]+)\"").matcher(js);
        Map<String, String[]> labels = new TreeMap<String, String[]>();
        while (m.find()) labels.put(m.group(1), new String[]{m.group(2), m.group(3)});
        int del = 0, keep = 0;
        for (String[] l : labels.values()) if (l[0].equals("delete")) del++; else keep++;

        JvmPlatform pf = new JvmPlatform(a[0]);
        Scanner.Result r = Scanner.scan(pf, new Scanner.Progress() { public void update(String s, int d, int t) {} public boolean cancelled() { return false; } });
        Map<String, FileItem> files = new HashMap<String, FileItem>();
        for (FileItem f : r.files) files.put(f.rel, f);

        System.out.println("tools:");
        System.out.print(Tools.summary());
        System.out.printf(Locale.ROOT, "test phone: %d pictures, %d not needed, %d still needed%n%n", labels.size(), del, keep);
        System.out.printf(Locale.ROOT, "%-15s %-48s %-20s %-18s %s%n", "algorithm", "", "not needed caught", "wrongly suggested", "your call");
        Map<String, Score> scores = new LinkedHashMap<String, Score>();
        for (DeletionAlgorithm alg : Registry.ALL) {
            UnitBuilder ub = new UnitBuilder(r, new Estimates(), pf.sdkInt(), alg);
            Map<String, Unit> units = new HashMap<String, Unit>();
            for (Unit u : ub.units) units.put(u.id, u);
            Score s = new Score();
            for (Map.Entry<String, String[]> e : labels.entrySet()) {
                String rel = e.getKey(), want = e.getValue()[0], kind = e.getValue()[1];
                Decision d = ub.need.get(rel);
                Option o = suggestion(units.get(rel), d);
                boolean suggested = o != null, ok = suggested == want.equals("delete");
                int[] p = s.perKind.get(kind); if (p == null) { p = new int[2]; s.perKind.put(kind, p); }
                p[1]++; if (ok) p[0]++;
                if (suggested && want.equals("delete")) s.caught++;
                if (suggested && !want.equals("delete")) s.wrong++;
                if (d != null && d.verdict == Decision.UNSURE) s.unsure++;
                if (list && !ok && alg.id().equals(chosen)) {
                    FileItem f = files.get(rel);
                    System.out.printf(Locale.ROOT, "  %s %-20s cat %-10s %s need %.2f [%s]%n      text: %s%n", suggested ? "WRONG " : "MISSED", kind,
                            f == null ? "-" : f.category, d == null ? "-" : d.kind, d == null ? -1 : d.need, suggested ? o.why : d == null ? "" : d.why,
                            f == null || f.text == null ? "(none)" : f.text.substring(0, Math.min(160, f.text.length())));
                }
            }
            scores.put(alg.id(), s);
            System.out.printf(Locale.ROOT, "%-15s %-48s %3d / %3d (%5.1f%%)   %3d / %3d          %3d%s%n", alg.id(), alg.name(), s.caught, del, 100.0 * s.caught / del,
                    s.wrong, keep, s.unsure, alg.id().equals(Registry.current().id()) ? "   <- the app uses this" : "");
        }

        System.out.printf("%nper kind of picture (right / total):%n%-22s", "");
        for (String id : scores.keySet()) System.out.printf(" %15s", id);
        System.out.println();
        for (String kind : scores.values().iterator().next().perKind.keySet()) {
            System.out.printf("  %-20s", kind);
            for (Score s : scores.values()) { int[] p = s.perKind.get(kind); System.out.printf(" %9d / %3d", p[0], p[1]); }
            System.out.println();
        }
        if (check) {
            Score s = scores.get(chosen);
            boolean fail = s == null || s.wrong > 0 || s.caught < 0.85 * del;
            System.out.println(fail ? "FAIL " + chosen + " bench checks" : chosen + " bench checks pass");
            if (fail) System.exit(1);
        }
    }

    /**
     * The deletion the planner was offered for a picture because of the algorithm: an option of the algorithm's
     * technique, or any deletion of a picture it finds not needed (one file keeps one delete option, so a blur or
     * duplicate suggestion may carry it). Blur, burst and duplicate checks are measured by their own tests.
     */
    static Option suggestion(Unit u, Decision d) {
        if (u == null || d == null) return null;
        Option found = null;
        for (Option o : u.options)
            if (o.action.equals("delete") && (o.technique.equals(d.technique) || d.verdict == Decision.NOT_NEEDED)) found = o;
        return found;
    }
}
