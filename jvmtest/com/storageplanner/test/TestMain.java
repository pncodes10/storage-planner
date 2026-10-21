package com.storageplanner.test;

import com.storageplanner.core.Estimates;
import com.storageplanner.core.Option;
import com.storageplanner.core.Planner;
import com.storageplanner.core.Report;
import com.storageplanner.core.Scanner;
import com.storageplanner.core.UnitBuilder;


import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Runs the app's core unchanged on the virtual phone and checks it against the hidden labels. */
public final class TestMain {
    public static void main(String[] args) throws Exception {
        String dev = args[0], gtPath = args[1];
        JvmPlatform pf = new JvmPlatform(dev);
        long t0 = System.currentTimeMillis();
        Scanner.Result r = Scanner.scan(pf, new Scanner.Progress() {
            String last = "";
            public void update(String stage, int done, int total) {
                if (!stage.equals(last)) { System.err.println("  " + stage + "..."); last = stage; }
            }
            public boolean cancelled() { return false; }
        });
        Estimates est = new Estimates();
        UnitBuilder ub = new UnitBuilder(r, est, pf.sdkInt());
        long used = r.total - r.free;
        long targetUsed = (long) (r.total * 70.0 / 128.0);
        Planner.Plan plan = Planner.tiered(ub.units, used - targetUsed);
        Planner.Plan safe = Planner.bestWithin(ub.units, Option.R2);
        Report.Context c = new Report.Context();
        c.total = r.total; c.free = r.free; c.used = used; c.targetUsed = targetUsed; c.est = est; c.det = ub.det;
        c.files = r.files.size(); c.unreadableDirs = r.unreadableDirs; c.sdk = pf.sdkInt(); c.now = r.now;
        System.out.println(Report.text(c, plan, safe));
        System.out.printf("scan+plan took %.1f s, %d files, %d units%n", (System.currentTimeMillis() - t0) / 1000.0, r.files.size(), ub.units.size());
        for (int k = 0; k <= 4; k++) {
            Planner.Plan p = Planner.bestWithin(ub.units, k);
            System.out.printf("  best within R%d: %s -> scaled %.1f GB of 128%n", k, Report.size(p.saved()), 128.0 * (used - p.saved()) / r.total);
        }
        System.out.printf("  tiered plan: tier R%d, %d confirmations, scaled %.1f GB of 128%n", plan.tier, plan.confirmations(), 128.0 * (used - plan.saved()) / r.total);

        // ---- detector accuracy vs hidden labels (sdcard paths only)
        String gt = new String(Files.readAllBytes(new File(gtPath).toPath()), StandardCharsets.UTF_8);
        Set<String> burst = new HashSet<String>(), blurry = new HashSet<String>(), sent = new HashSet<String>(), dups = new HashSet<String>();
        Matcher m = Pattern.compile("\"sdcard/([^\"]+)\": \\{([^}]*)\\}").matcher(gt);
        while (m.find()) {
            String p = m.group(1), body = m.group(2);
            if (body.contains("\"kind\": \"burst\"")) burst.add(p);
            if (body.contains("\"kind\": \"blurry\"")) blurry.add(p);
            if (body.contains("\"sent_copy_of\"")) sent.add(p);
            if (body.contains("\"dup_of\"") && !p.startsWith("Android/data")) dups.add(p);
        }
        int bt = 0; for (String p : ub.det.burstExtraOf.keySet()) if (burst.contains(p)) bt++;
        int bl = 0; for (String p : ub.det.blurry) if (blurry.contains(p)) bl++;
        int st = 0; for (String p : ub.det.sentCopyOf.keySet()) if (sent.contains(p)) st++;
        // a flagged copy is correct if it and its keeper are in the same labelled duplicate group
        java.util.Map<String, String> root = new java.util.HashMap<String, String>();
        Matcher m2 = Pattern.compile("\"sdcard/([^\"]+)\": \\{[^}]*\"dup_of\": \"sdcard/([^\"]+)\"").matcher(gt);
        while (m2.find()) { root.put(m2.group(1), m2.group(2)); root.put(m2.group(2), m2.group(2)); }
        int dt = 0;
        for (Map.Entry<String, String> e : ub.det.duplicateOf.entrySet()) {
            String a = root.get(e.getKey()), b = root.get(e.getValue());
            if (a != null && a.equals(b)) dt++;
        }
        System.out.printf("detections: burst extras %d (in true bursts %d) | blurry %d flagged, %d of %d true | sent copies %d of %d, %d false | duplicates %d flagged, %d confirmed same group, %d labelled copies on sdcard%n",
                ub.det.burstExtraOf.size(), bt, ub.det.blurry.size(), bl, blurry.size(), st, sent.size(), ub.det.sentCopyOf.size() - st,
                ub.det.duplicateOf.size(), dt, dups.size());
        if (args.length > 2) {
            Files.write(new File(args[2], "plan.txt").toPath(), Report.text(c, plan, safe).getBytes(StandardCharsets.UTF_8));
            Files.write(new File(args[2], "plan.csv").toPath(), Report.csv(plan).getBytes(StandardCharsets.UTF_8));
            Files.write(new File(args[2], "plan.json").toPath(), Report.json(c, plan, safe).getBytes(StandardCharsets.UTF_8));
        }
    }
}
