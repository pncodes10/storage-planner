package com.storageplanner.v2test;

import com.storageplanner.core.Option;
import com.storageplanner.core.Planner;
import com.storageplanner.core.Review;
import com.storageplanner.core.Unit;
import com.storageplanner.v2adapter.V2Adapter;
import com.storageplanner.v2adapter.V2Plans;
import com.storageplanner.v2adapter.V2Capacity;
import com.storageplanner.v2.device.InstalledApp;
import com.storageplanner.v2.model.*;
import com.storageplanner.v2.plan.*;

import java.util.*;

/** Phase A: v2 -> classic Unit/Option adapter and the v2 session planner (ARCHITECTURE 13, PHASE_5 11, REVIEW_2 F-13, A29). */
public final class V2AdapterTest {
    static final long MB = 1000000L;

    static final class Fx {
        PlanFx x = new PlanFx();
        FileRecord keeper, copy, chat, review, prot, pending, temp, cacheFile;
        Plans.Made made;
        V2Adapter.Adapted ad;
        Fx(boolean allowYourCall) {
            temp = x.file("Download/a.tmp", 100 * MB); x.del(temp, Tier.SAFE, "S_TEMP_FILE", 0);
            keeper = x.file("DCIM/b.jpg", 200 * MB); copy = x.file("DCIM/b_copy.jpg", 200 * MB);
            x.in.keepers.add(keeper.fileId);
            x.del(copy, Tier.NO_LOSS, "N_EXACT_DUPLICATE", 0, keeper.fileId);
            x.del(keeper, Tier.NO_LOSS, "N_EXACT_DUPLICATE", 0);                              // must never surface
            x.rec(keeper.fileId, Tier.NO_LOSS, Action.COMPRESS, "N_LOSSLESS_SMALLER", 20 * MB, 0);   // keeper unit: skipped entirely
            chat = x.file("WhatsApp/c.jpg", 300 * MB); x.del(chat, Tier.YOUR_CALL, "Y_CHAT_COPY", 0.01);
            review = x.file("Download/old.pdf", 40 * MB); x.del(review, Tier.REVIEW, "R_OLD_DOWNLOAD", 0.3);
            prot = x.file("Documents/passport.jpg", 5 * MB); x.del(prot, Tier.PROTECTED, "P_UNIQUE_RECORD", 1);
            pending = x.file("DCIM/d_copy.jpg", 70 * MB);
            x.del(pending, Tier.NO_LOSS, "N_EXACT_DUPLICATE", 0).reasonParams.put("hashPending", "true");
            cacheFile = x.file("Android/data/com.x/cache/f.bin", 10 * MB); cacheFile.area = "app_cache";
            x.del(cacheFile, Tier.SAFE, "S_APP_CACHE_FILE", 0);
            x.rec("cache:com.x", Tier.SAFE, Action.CLEAR_APP_CACHE, "S_APP_CACHE", 50 * MB, 0);
            made = Plans.make(x.in, ActionPlan.MAX_SAFE, null);
            InstalledApp a = new InstalledApp(); a.packageName = "com.x"; a.label = "X"; a.cacheBytes = 50 * MB;
            Map<String, String> dup = new HashMap<String, String>();
            dup.put(copy.fileId, keeper.fileId);
            ad = V2Adapter.adapt(x.in, made.built, Collections.singletonList(a), dup, "/storage/emulated/0", 34, 1700000000000L, 128000 * MB, 1000 * MB, allowYourCall);
        }
        Unit unit(String id) { for (Unit u : ad.ub.units) if (u.id.equals(id)) return u; return null; }
    }

    public static int run(String[] args) {
        T.section("tier mapping table");
        T.eq("SAFE -> R0", Option.R0, V2Adapter.risk(Tier.SAFE, Action.DELETE));
        T.eq("NO_LOSS -> R1", Option.R1, V2Adapter.risk(Tier.NO_LOSS, Action.DELETE));
        T.eq("NO_LOSS compress -> R1", Option.R1, V2Adapter.risk(Tier.NO_LOSS, Action.COMPRESS));
        T.eq("YOUR_CALL delete -> R4", Option.R4, V2Adapter.risk(Tier.YOUR_CALL, Action.DELETE));
        T.eq("YOUR_CALL lossy -> R3", Option.R3, V2Adapter.risk(Tier.YOUR_CALL, Action.COMPRESS));
        T.eq("cache action", "clear_cache", V2Adapter.action(Tier.SAFE, Action.CLEAR_APP_CACHE));
        T.eq("archive action", "archive_app", V2Adapter.action(Tier.NO_LOSS, Action.ARCHIVE_APP));
        T.eq("uninstall action", "uninstall_app", V2Adapter.action(Tier.YOUR_CALL, Action.UNINSTALL_APP));
        T.eq("compress action", "v2_compress", V2Adapter.action(Tier.NO_LOSS, Action.COMPRESS));
        T.eq("review action", "v2_review", V2Adapter.action(Tier.REVIEW, Action.DELETE));
        T.eq("protected action", "v2_protected", V2Adapter.action(Tier.PROTECTED, Action.DELETE));
        Set<String> prefixes = new HashSet<String>();
        for (Tier t : Tier.values()) prefixes.add(V2Adapter.prefix(t));
        T.eq("five distinct tier prefixes", 5, prefixes.size());
        for (String c : ReasonCatalog.codes()) {
            String tier = ReasonCatalog.tier(c);
            if (tier.equals("PLAN") || tier.equals("block")) continue;
            T.check("label for " + c, !"Other".equals(V2Adapter.label(Action.DELETE, c)));
        }
        T.check("why has tier name and sentence", V2Adapter.why(Tier.SAFE, recWith("S_TEMP_FILE")).startsWith("v2 tier: Safe")
                && V2Adapter.why(Tier.SAFE, recWith("S_TEMP_FILE")).length() > 40);

        T.section("adapted units");
        Fx f = new Fx(false);
        T.check("keeper has no unit", f.unit(f.keeper.path) == null);
        T.eq("keeper compress unit skipped", 1, f.ad.keeperUnitsSkipped);
        T.check("hash-pending delete has no unit", f.unit(f.pending.path) == null);
        Unit temp = f.unit(f.temp.path), copy = f.unit(f.copy.path), chat = f.unit(f.chat.path), rev = f.unit(f.review.path), pr = f.unit(f.prot.path);
        T.check("SAFE unit", temp != null && temp.options.size() == 1 && temp.options.get(0).risk == Option.R0
                && temp.options.get(0).technique.startsWith(V2Adapter.SAFE) && temp.options.get(0).saved == 100 * MB);
        T.check("NO_LOSS unit", copy != null && copy.options.get(0).risk == Option.R1 && copy.options.get(0).technique.equals("No loss · Identical copies"));
        T.check("REVIEW visible as such", rev != null && rev.options.get(0).technique.startsWith(V2Adapter.REVIEW) && rev.options.get(0).saved == 0
                && f.ad.notPlannable.contains(rev.id));
        T.check("PROTECTED visible as such", pr != null && pr.options.get(0).technique.startsWith(V2Adapter.PROTECTED) && f.ad.notPlannable.contains(pr.id));
        T.check("YOUR_CALL without opt-in is display only", chat != null && chat.options.get(0).technique.startsWith(V2Adapter.YOUR_CALL)
                && chat.options.get(0).saved == 0 && f.ad.notPlannable.contains(chat.id));
        T.check("cache unit", f.unit("cache:com.x") != null && f.unit("cache:com.x").options.get(0).action.equals("clear_cache"));
        T.check("cache file included in app cache (no unit, no double count)", f.unit(f.cacheFile.path) == null);
        T.eq("copies-of link", f.keeper.path, f.ad.ub.det.duplicateOf.get(f.copy.path));
        T.check("candidates groups by technique", Review.candidates(f.ad.ub.units).containsKey("Review · Old downloads"));
        T.check("present files in result", f.ad.result.files.size() == f.x.in.files.size());

        T.section("plans never select REVIEW / PROTECTED / YOUR_CALL / keepers");
        Map<String, String> acc = new HashMap<String, String>();
        acc.put(rev.id, rev.options.get(0).technique);
        acc.put(pr.id, pr.options.get(0).technique);
        acc.put(chat.id, chat.options.get(0).technique);
        acc.put(f.keeper.path, Review.CHOSEN + com.storageplanner.core.Techniques.DUPLICATES);
        acc.put(f.copy.path, Review.CHOSEN + com.storageplanner.core.Techniques.PICKED);
        Random rnd = new Random(7);
        long maxPlan = V2Plans.plan(f.ad.ub.units, f.ad.notPlannable, new HashSet<String>(), new HashMap<String, String>(), Long.MAX_VALUE, V2Plans.MODE_SAFE).saved();
        T.eq("max plan bytes = v2 achievableBytes", f.made.plan.achievableBytes, maxPlan);
        T.eq("plannable bytes = v2 achievableBytes", f.made.plan.achievableBytes, f.ad.plannableBytes);
        boolean ok = true, bounded = true;
        for (int i = 0; i < 200; i++) {
            long need = (long) (rnd.nextDouble() * 600 * MB);
            for (int mode = 0; mode <= 1; mode++) {
                Planner.Plan p = V2Plans.plan(f.ad.ub.units, f.ad.notPlannable, new HashSet<String>(), i % 2 == 0 ? acc : new HashMap<String, String>(), need, mode);
                for (Map.Entry<Unit, Option> e : p.chosen.entrySet()) {
                    if (f.ad.notPlannable.contains(e.getKey().id) || e.getValue().risk > Option.R1 || e.getValue().saved <= 0
                            || e.getKey().id.equals(f.keeper.path) || !e.getKey().options.contains(e.getValue())) ok = false;
                }
                if (p.saved() > f.made.plan.achievableBytes) bounded = false;
                if (mode == 0 && need <= f.made.plan.achievableBytes && !p.reachesTarget) bounded = false;
            }
        }
        T.check("only SAFE/NO_LOSS options of plannable units are ever chosen", ok);
        T.check("plan bytes <= achievable; reaches every target <= achievable", bounded);
        // classic tiered over adapted units (excluding display-only) never sees REVIEW/PROTECTED
        List<Unit> plannable = new ArrayList<Unit>();
        for (Unit u : f.ad.ub.units) if (!f.ad.notPlannable.contains(u.id)) plannable.add(u);
        Planner.Plan cp = Planner.tiered(plannable, Long.MAX_VALUE);
        T.eq("classic tiered over plannable = achievable", f.made.plan.achievableBytes, cp.saved());

        T.section("YOUR_CALL opt-in");
        Fx y = new Fx(true);
        Unit yc = y.unit(y.chat.path);
        T.check("opted-in YOUR_CALL is an R4 option, not display-only", yc != null && yc.options.get(0).risk == Option.R4
                && yc.options.get(0).saved == 300 * MB && !y.ad.notPlannable.contains(yc.id));
        Planner.Plan yp = V2Plans.plan(y.ad.ub.units, y.ad.notPlannable, new HashSet<String>(), new HashMap<String, String>(), 600 * MB, V2Plans.MODE_TARGET);
        T.check("V2Plans still caps unaccepted options at NO_LOSS", !yp.chosen.containsKey(yc) && !yp.reachesTarget);
        Map<String, String> yacc = new HashMap<String, String>();
        yacc.put(yc.id, yc.options.get(0).technique);
        yp = V2Plans.plan(y.ad.ub.units, y.ad.notPlannable, new HashSet<String>(), yacc, 600 * MB, V2Plans.MODE_TARGET);
        T.check("an accepted YOUR_CALL option above R1 is still not forced", !yp.chosen.containsKey(yc));

        T.section("conflict-free (F-13)");
        UnitBuilder2.Built b = new UnitBuilder2.Built();
        PlanFx z = new PlanFx();
        FileRecord fx = z.file("Android/data/com.z/files/big.bin", 30 * MB);
        PlanUnit app = new PlanUnit(); app.id = "app:com.z"; app.subject = app.id;
        PlanUnit.Option2 ao = app.add(Action.UNINSTALL_APP, Tier.YOUR_CALL, 90 * MB, 1, 1, "app:com.z/UNINSTALL_APP", "Y_IDLE_APP");
        ao.conflicts.add(fx.fileId);
        PlanUnit fu = new PlanUnit(); fu.id = fx.fileId; fu.subject = fx.path; fu.fileIds.add(fx.fileId);
        PlanUnit.Option2 fo = fu.add(Action.DELETE, Tier.SAFE, 30 * MB, 0, 1, fx.fileId + "/DELETE", "S_TEMP_FILE");
        fo.conflicts.add(app.id);
        b.units.add(app); b.units.add(fu);
        V2Adapter.Adapted za = V2Adapter.adapt(z.in, b, new ArrayList<InstalledApp>(), null, "", 34, 0, 0, 0, true);
        boolean appHasOption = false, fileHasOption = false;
        for (Unit u : za.ub.units) { if (u.id.equals("app:com.z") && !u.options.isEmpty()) appHasOption = true; if (u.id.equals(fx.path) && !u.options.isEmpty()) fileHasOption = true; }
        T.check("conflicting app option not emitted", !appHasOption);
        T.check("file side kept", fileHasOption);
        T.eq("one conflict option dropped", 1, za.conflictOptionsDropped);
        capacity();
        return T.finish("V2AdapterTest");
    }

    static Recommendation recWith(String code) {
        Recommendation r = new Recommendation(); r.reasonCodes.add(code); r.reasonParams.put("ext", "tmp"); r.reasonParams.put("age", "3 days"); return r;
    }

    /** REVIEW_A M-1: the memory guard's arithmetic (V2Runner stops v2 before extraction above this file count). */
    static void capacity() {
        T.section("memory guard (V2Capacity)");
        long MiB = 1024L * 1024;
        // 256 MB heap, 20 MB already used: (0.8 * 256 - 20 - 64) MiB / 8 KiB = 15,462 files
        T.eq("256 MB heap", 15462L, V2Capacity.maxFiles(256 * MiB, 20 * MiB));
        // 512 MB heap, 20 MB used: (409.6 - 84) MiB / 8 KiB = 41,676 files
        T.eq("512 MB heap", 41676L, V2Capacity.maxFiles(512 * MiB, 20 * MiB));
        T.eq("more used heap, fewer files", true, V2Capacity.maxFiles(256 * MiB, 120 * MiB) < V2Capacity.maxFiles(256 * MiB, 20 * MiB));
        T.eq("heap smaller than the reserve: no files", 0L, V2Capacity.maxFiles(64 * MiB, 0));
        T.eq("used above the budget: no files, never negative", 0L, V2Capacity.maxFiles(256 * MiB, 300 * MiB));
        T.eq("unknown heap: no files", 0L, V2Capacity.maxFiles(0, 0));
        T.eq("negative used heap counts as 0", V2Capacity.maxFiles(256 * MiB, 0), V2Capacity.maxFiles(256 * MiB, -5));
        T.eq("no overflow for an unlimited heap", true, V2Capacity.maxFiles(Long.MAX_VALUE, 0) > 1000000000L);
        long lim = V2Capacity.maxFiles(256 * MiB, 20 * MiB);
        T.eq("limit itself fits", true, V2Capacity.fits(lim, 256 * MiB, 20 * MiB));
        T.eq("one more does not fit", false, V2Capacity.fits(lim + 1, 256 * MiB, 20 * MiB));
        String m = V2Capacity.tooManyMessage(48210, lim);
        T.eq("message names the count", true, m.contains("48,210"));
        T.eq("message says v2 cannot handle it and points to Classic", true, m.startsWith("v2 cannot handle this many files yet") && m.contains("Classic"));
        T.eq("message says nothing was changed", true, m.contains("Nothing was changed"));
        T.eq("OOM message points to Classic", true, V2Capacity.OUT_OF_MEMORY.contains("Classic"));
    }
}
