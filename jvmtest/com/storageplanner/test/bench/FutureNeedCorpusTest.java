package com.storageplanner.test.bench;

import com.storageplanner.algorithms.v2_futureneed.FutureNeed;
import com.storageplanner.core.Estimates;
import com.storageplanner.core.FileItem;
import com.storageplanner.core.Option;
import com.storageplanner.core.Scanner;
import com.storageplanner.core.Unit;
import com.storageplanner.core.UnitBuilder;
import com.storageplanner.test.JvmPlatform;

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
 * Will each picture be needed again, end to end on a labelled test phone (testdata/futureneed/build.py): the whole
 * scan runs as on the phone (Scanner + Sorter, the app's own text reader's words from "<picture>.ocr.json"),
 * then UnitBuilder, whose deletion suggestions for pictures come from FutureNeed. Measures wrong suggestions
 * (a picture that is still needed) and missed ones (a picture nothing in which is needed any more).
 * usage: FutureNeedCorpusTest <phone dir> [--list] [--check]
 */
public final class FutureNeedCorpusTest {
    public static void main(String[] a) throws Exception {
        boolean list = false, check = false;
        for (String x : a) { if (x.equals("--list")) list = true; if (x.equals("--check")) check = true; }
        String js = new String(Files.readAllBytes(new File(a[0], "labels.json").toPath()), StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("\"([^\"]+)\": \\{\\s*\"need\": \"([a-z]+)\",\\s*\"kind\": \"([a-z_]+)\"").matcher(js);
        Map<String, String[]> labels = new TreeMap<String, String[]>();
        while (m.find()) labels.put(m.group(1), new String[]{m.group(2), m.group(3)});

        JvmPlatform pf = new JvmPlatform(a[0]);
        Scanner.Result r = Scanner.scan(pf, new Scanner.Progress() { public void update(String s, int d, int t) {} public boolean cancelled() { return false; } });
        UnitBuilder ub = new UnitBuilder(r, new Estimates(), pf.sdkInt());
        Map<String, Unit> units = new HashMap<String, Unit>();
        for (Unit u : ub.units) units.put(u.id, u);
        Map<String, FileItem> files = new HashMap<String, FileItem>();
        for (FileItem f : r.files) files.put(f.rel, f);

        Map<String, int[]> per = new LinkedHashMap<String, int[]>();
        int wrong = 0, missed = 0, del = 0, keep = 0, unsure = 0;
        for (Map.Entry<String, String[]> e : labels.entrySet()) {
            String rel = e.getKey(), want = e.getValue()[0], kind = e.getValue()[1];
            Unit u = units.get(rel);
            Option d = null;
            FutureNeed.Assessment fa = ub.need.get(rel);
            // FutureNeed's own suggestions (blur and burst checks are measured by their own tests)
            if (u != null && fa != null) for (Option o : u.options)
                if (o.action.equals("delete") && (o.technique.equals(fa.technique) || fa.verdict == FutureNeed.NOT_NEEDED)) d = o;
            boolean suggested = d != null;
            boolean ok = suggested == want.equals("delete");
            int[] p = per.get(kind); if (p == null) { p = new int[3]; per.put(kind, p); }
            p[1]++; if (ok) p[0]++;
            if (fa != null && fa.verdict == FutureNeed.UNSURE) { p[2]++; unsure++; }
            if (want.equals("delete")) del++; else keep++;
            if (!ok) {
                if (suggested) wrong++; else missed++;
                if (list) {
                    FileItem f = files.get(rel);
                    System.out.printf(Locale.ROOT, "  %s %-20s cat %-10s %s need %.2f [%s]%n      text: %s%n", suggested ? "WRONG " : "MISSED", kind,
                            f == null ? "-" : f.category, fa == null ? "-" : fa.kind, fa == null ? -1 : fa.need, suggested ? d.why : fa == null ? "" : fa.why,
                            f == null || f.text == null ? "(none)" : f.text.substring(0, Math.min(160, f.text.length())));
                }
            }
        }
        System.out.println("per kind of picture (right / total, of which 'your call'):");
        for (Map.Entry<String, int[]> e : per.entrySet())
            System.out.printf(Locale.ROOT, "  %-22s %3d / %3d %s%n", e.getKey(), e.getValue()[0], e.getValue()[1], e.getValue()[2] > 0 ? "  (" + e.getValue()[2] + " your call)" : "");
        System.out.printf(Locale.ROOT, "not needed: %d of %d suggested (%.1f%%); still needed: %d of %d wrongly suggested; %d left as 'your call'%n",
                del - missed, del, 100.0 * (del - missed) / del, wrong, keep, unsure);
        if (check) {
            boolean fail = wrong > 0 || del - missed < 0.85 * del;
            System.out.println(fail ? "FAIL future-need corpus checks" : "future-need corpus checks pass");
            if (fail) System.exit(1);
        }
    }
}
