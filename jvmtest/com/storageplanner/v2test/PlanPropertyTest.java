package com.storageplanner.v2test;

import com.storageplanner.v2.model.*;
import com.storageplanner.v2.plan.*;

import java.util.*;

/**
 * Randomised property test of plans (lead's list + PHASE_5 10 PlanValidatorTest): random exact groups, content copies whose original
 * may itself be removable, bundles, app caches over cache files, REVIEW/PROTECTED and hashPending recs, transforms on keepers;
 * random targets and allowed tiers.
 */
public final class PlanPropertyTest {
    static final long MB = 1000000L;
    static final Tier[] REMOVAL = {Tier.SAFE, Tier.NO_LOSS, Tier.YOUR_CALL};

    public static int run(String[] args) {
        Random r = new Random(20261112L);
        int plans = 0;
        for (int it = 0; it < 1500; it++) {
            PlanFx x = new PlanFx();
            List<List<String>> exactGroups = new ArrayList<List<String>>();
            Map<String, String> copyOf = new HashMap<String, String>();
            Set<String> forbidden = new HashSet<String>(), pending = new HashSet<String>();
            int groups = r.nextInt(6);
            for (int g = 0; g < groups; g++) {
                long sz = (1 + r.nextInt(50)) * MB;
                FileRecord k = x.file("DCIM/k" + g + ".jpg", sz); x.in.keepers.add(k.fileId);
                List<String> mem = new ArrayList<String>(); mem.add(k.fileId);
                if (r.nextBoolean()) x.rec(k.fileId, Tier.NO_LOSS, Action.COMPRESS, "N_LOSSLESS_SMALLER", sz / 3, 0);
                if (r.nextInt(4) == 0) x.del(k, Tier.NO_LOSS, "N_EXACT_DUPLICATE", 0);        // must be dropped (keeper)
                int n = 1 + r.nextInt(3);
                for (int i = 0; i < n; i++) {
                    FileRecord c = x.file("Download/k" + g + "_" + i + ".jpg", sz); mem.add(c.fileId);
                    Recommendation d = x.del(c, Tier.NO_LOSS, "N_EXACT_DUPLICATE", 0, k.fileId);
                    if (r.nextInt(6) == 0) { d.reasonParams.put("hashPending", "true"); pending.add(c.fileId); }
                }
                exactGroups.add(mem);
            }
            int copies = r.nextInt(8);
            List<FileRecord> originals = new ArrayList<FileRecord>();
            for (int i = 0; i < copies; i++) {
                FileRecord o = !originals.isEmpty() && r.nextBoolean() ? originals.get(r.nextInt(originals.size())) : x.file("DCIM/o" + i + ".jpg", (1 + r.nextInt(80)) * MB);
                if (!originals.contains(o)) {
                    originals.add(o);
                    int k = r.nextInt(4);   // the original may itself be removable (low quality) or be REVIEW/PROTECTED
                    if (k == 0) x.del(o, Tier.YOUR_CALL, "Y_LOW_QUALITY", r.nextDouble() * 0.02);
                    if (k == 1) { x.rec(o.fileId, Tier.PROTECTED, Action.KEEP, "P_UNIQUE_RECORD", 0, 0); forbidden.add(o.fileId); }
                    if (k == 2) { x.rec(o.fileId, Tier.REVIEW, Action.REVIEW, "R_SIMILAR_SHOT", 0, 0); forbidden.add(o.fileId); }
                }
                FileRecord c = x.file("WhatsApp/c" + i + ".jpg", (1 + r.nextInt(20)) * MB / 2);
                x.del(c, Tier.YOUR_CALL, "Y_CHAT_COPY", r.nextDouble() * 0.03, o.fileId);
                copyOf.put(c.fileId, o.fileId);
            }
            int safe = r.nextInt(6);
            for (int i = 0; i < safe; i++) x.del(x.file("tmp/t" + i, (1 + r.nextInt(30)) * MB), Tier.SAFE, "S_TEMP_FILE", 0);
            Map<String, String> cachePkgOf = new HashMap<String, String>();
            if (r.nextBoolean()) {
                long tot = 0;
                for (int i = 0; i < 2; i++) { FileRecord cf = x.file("Android/data/com.p/cache/c" + i, 2 * MB); tot += 2 * MB; x.del(cf, Tier.SAFE, "S_APP_CACHE_FILE", 0); cachePkgOf.put(cf.fileId, "com.p"); }
                x.rec("cache:com.p", Tier.SAFE, Action.CLEAR_APP_CACHE, "S_APP_CACHE", tot + 3 * MB, 0);
            }
            if (r.nextBoolean()) {
                FileRecord b0 = x.file("Download/s/base.apk", 20 * MB), b1 = x.file("Download/s/split.apk", 4 * MB);
                x.del(b0, Tier.NO_LOSS, "N_INSTALLER_INSTALLED", 0);
                if (r.nextBoolean()) x.del(b1, Tier.NO_LOSS, "N_INSTALLER_INSTALLED", 0); else x.rec(b1.fileId, Tier.REVIEW, Action.REVIEW, "R_UNKNOWN_DEPENDENCY", 0, 0);
                x.in.bundles.put(b0.fileId, Arrays.asList(b0.fileId, b1.fileId));
            }
            Tier max = r.nextInt(3) == 0 ? Tier.NO_LOSS : Tier.YOUR_CALL;
            long[] targets = {ActionPlan.MAX_SAFE, 10 * MB, 60 * MB, 150 * MB, 400 * MB, 5000 * MB};
            Tier prev = null; long prevT = -1;
            for (long t : targets) {
                Plans.Made m = Plans.make(x.in, t, t == ActionPlan.MAX_SAFE ? null : max);
                ActionPlan p = m.plan; plans++;
                T.check("validator clean " + m.problems, m.problems.isEmpty() && p.validatorDropped == 0);
                Set<String> seen = new HashSet<String>(), removed = new HashSet<String>(), touched = new HashSet<String>();
                long sum = 0;
                for (ActionPlan.PlanItem i : p.items) {
                    sum += i.bytes;
                    T.check("only SAFE/NO_LOSS/YOUR_CALL", i.tier == Tier.SAFE || i.tier == Tier.NO_LOSS || i.tier == Tier.YOUR_CALL);
                    T.check("tier within request", Tier.rank(i.tier) <= Tier.rank(p.maxTier));
                    long fb = 0;
                    for (String f : i.fileIds) {
                        T.check("no file has two actions", seen.add(f));
                        T.check("no REVIEW/PROTECTED file", !forbidden.contains(f));
                        T.check("no hashPending delete", !pending.contains(f));
                        touched.add(f); if (i.action == Action.DELETE) removed.add(f);
                        fb += x.in.files.get(f).sizeBytes;
                    }
                    if (i.action == Action.DELETE) T.eq("delete bytes = distinct physical file bytes", fb, i.bytes);
                }
                T.eq("claimed bytes = sum of items", sum, p.withYourCallBytes);
                for (List<String> g : exactGroups) {
                    boolean survives = false; for (String f : g) if (!removed.contains(f)) survives = true;
                    T.check("group keeps a verified copy", survives && !removed.contains(g.get(0)));
                }
                for (Map.Entry<String, String> e : copyOf.entrySet())
                    if (removed.contains(e.getKey())) T.check("original of a removed copy untouched", !touched.contains(e.getValue()));
                boolean cacheUnit = PlanFx.has(p, "cache:com.p");
                for (String f : cachePkgOf.keySet()) if (cacheUnit) T.check("app cache and its files never both", !touched.contains(f));
                if (t == ActionPlan.MAX_SAFE) T.check("max safe never YOUR_CALL", PlanFx.tierBytes(p, Tier.YOUR_CALL) == 0);
                else {
                    if (p.reachesTarget) T.check("reaches -> bytes >= target", p.withYourCallBytes >= t);
                    else T.check("unreachable -> achievable reported", p.withYourCallBytes < t && p.reasonCodes.contains("PL_TARGET_UNREACHABLE")
                            && p.reasonParams.containsKey("achievable"));
                    if (prev != null && p.highestTier != null)
                        T.check("raising the target never lowers... and never exceeds allowed tier", Tier.rank(p.highestTier) >= Tier.rank(prev) && Tier.rank(p.highestTier) <= Tier.rank(max));
                    if (p.highestTier != null) { prev = p.highestTier; prevT = t; }
                }
            }
        }
        System.out.println("  plans checked: " + plans);
        return T.finish("PlanPropertyTest");
    }
}
