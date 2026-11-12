package com.storageplanner.v2test;

import com.storageplanner.v2.model.*;
import com.storageplanner.v2.plan.*;

import java.util.*;

/** PHASE_5 10: Planner2Test + Planner2TierOrderTest (F-11) + ReasonCatalog PL_* codes. */
public final class Planner2Test {
    static final long MB = 1000000L, GB = 1000000000L;
    static PlanFx tiers() {
        PlanFx x = new PlanFx();
        x.del(x.file("cache/a.tmp", 100 * MB), Tier.SAFE, "S_TEMP_FILE", 0);
        x.del(x.file("DCIM/b_copy.jpg", 200 * MB), Tier.NO_LOSS, "N_EXACT_DUPLICATE", 0);
        x.del(x.file("WhatsApp/c.jpg", 300 * MB), Tier.YOUR_CALL, "Y_CHAT_COPY", 0.01);
        return x;
    }
    public static int run(String[] args) {
        T.section("targets");
        ActionPlan p = tiers().plan(ActionPlan.MAX_SAFE);
        T.eq("max safe = SAFE + NO_LOSS", 300 * MB, p.achievableBytes);
        T.eq("max safe has no YOUR_CALL", 300 * MB, p.withYourCallBytes);
        T.check("max safe reaches", p.reachesTarget);
        p = tiers().plan(50 * MB);
        T.eq("SAFE reaches -> SAFE only", Tier.SAFE, p.highestTier);
        p = tiers().plan(250 * MB);
        T.eq("NO_LOSS needed -> highest NO_LOSS", Tier.NO_LOSS, p.highestTier);
        T.eq("no YOUR_CALL", 0L, PlanFx.tierBytes(p, Tier.YOUR_CALL));
        p = tiers().planYourCall(500 * MB);
        T.eq("YOUR_CALL needed", Tier.YOUR_CALL, p.highestTier);
        T.check("PL_NEEDS_YOUR_CALL", p.reasonCodes.contains("PL_NEEDS_YOUR_CALL") && p.reachesTarget);
        p = tiers().plan(500 * MB, Tier.NO_LOSS);
        T.check("not allowed YOUR_CALL -> unreachable, no YOUR_CALL", !p.reachesTarget && PlanFx.tierBytes(p, Tier.YOUR_CALL) == 0 && p.reasonCodes.contains("PL_TARGET_UNREACHABLE"));
        p = tiers().planYourCall(5 * GB);
        T.check("unreachable", !p.reachesTarget && p.reasonCodes.contains("PL_TARGET_UNREACHABLE"));
        T.eq("unreachable reports achievable", 300 * MB, p.achievableBytes);
        T.eq("unreachable withYourCall", 600 * MB, p.withYourCallBytes);
        T.check("reason renders", !ReasonCatalog.render("PL_TARGET_UNREACHABLE", p.reasonParams).contains("unknown"));

        T.section("A29 YOUR_CALL opt-in");
        p = tiers().plan(500 * MB);
        T.eq("default request: no YOUR_CALL in plan", 0L, PlanFx.tierBytes(p, Tier.YOUR_CALL));
        T.check("default request: maxTier NO_LOSS, not opted in", p.maxTier == Tier.NO_LOSS && !p.allowYourCall);
        T.check("default request: unreachable reported", !p.reachesTarget && p.reasonCodes.contains("PL_TARGET_UNREACHABLE"));
        T.eq("default request: achievable", 300 * MB, p.achievableBytes);
        T.eq("default request: plan bytes exclude YOUR_CALL", 300 * MB, p.withYourCallBytes);
        T.eq("default request: says how much YOUR_CALL would add", 300 * MB, p.yourCallExtraBytes);
        T.eq("default request: reason names the YOUR_CALL total", Fmt.bytes(600 * MB), p.reasonParams.get("withYourCall"));
        T.check("default request: no PL_NEEDS_YOUR_CALL", !p.reasonCodes.contains("PL_NEEDS_YOUR_CALL"));
        p = tiers().plan(500 * MB, Tier.YOUR_CALL);
        T.check("explicit maxTier YOUR_CALL is the opt-in", p.allowYourCall && p.reachesTarget && PlanFx.tierBytes(p, Tier.YOUR_CALL) == 300 * MB);
        T.eq("opt-in reachable: no extra reported", 0L, p.yourCallExtraBytes);
        {
            PlanFx y = new PlanFx();
            y.del(y.file("keep/base.tmp", 100 * MB), Tier.SAFE, "S_TEMP_FILE", 0);
            FileRecord lo = y.file("WhatsApp/lo.jpg", 200 * MB), hi = y.file("WhatsApp/hi.jpg", 200 * MB), neg = y.file("WhatsApp/neg.jpg", 200 * MB);
            Recommendation rl = y.del(lo, Tier.YOUR_CALL, "Y_CHAT_COPY", 0.03);
            Recommendation rh = y.del(hi, Tier.YOUR_CALL, "Y_CHAT_COPY", 0.001);
            Recommendation rn = y.del(neg, Tier.YOUR_CALL, "Y_CHAT_COPY", 0.9);
            T.check("fixture utilities ordered hi > lo > neg, neg <= 0", UnitBuilder2.utility(rh) > UnitBuilder2.utility(rl) && UnitBuilder2.utility(rl) > UnitBuilder2.utility(rn) && UnitBuilder2.utility(rn) <= 0);
            p = y.plan(250 * MB);
            T.eq("default: no YOUR_CALL even when needed", 0L, PlanFx.tierBytes(p, Tier.YOUR_CALL));
            T.eq("default: YOUR_CALL would add 600 MB", 600 * MB, p.yourCallExtraBytes);
            p = y.planYourCall(250 * MB);
            T.check("opt-in: highest-utility YOUR_CALL taken first", PlanFx.has(p, hi.fileId) && !PlanFx.has(p, lo.fileId) && !PlanFx.has(p, neg.fileId) && p.reachesTarget);
            p = y.planYourCall(450 * MB);
            T.check("opt-in: next by utility", PlanFx.has(p, hi.fileId) && PlanFx.has(p, lo.fileId) && !PlanFx.has(p, neg.fileId));
            p = y.planYourCall(650 * MB);
            T.check("opt-in: utility <= 0 item enters when needed (no gate)", PlanFx.has(p, neg.fileId) && p.reachesTarget);
            T.check("validator clean with opt-in", PlanValidator.validate(p, UnitBuilder2.build(y.in).recById, y.in.keepers).isEmpty());
        }

        T.section("prune");
        PlanFx x = new PlanFx();
        x.del(x.file("a", 400 * MB), Tier.YOUR_CALL, "Y_CHAT_COPY", 0.001);
        x.del(x.file("b", 300 * MB), Tier.YOUR_CALL, "Y_CHAT_COPY", 0.002);
        x.del(x.file("c", 500 * MB), Tier.YOUR_CALL, "Y_CHAT_COPY", 0.004);
        p = x.planYourCall(450 * MB);
        T.check("prune keeps >= target", p.withYourCallBytes >= 450 * MB);
        T.check("prune removes a redundant item", p.items.size() <= 2);

        T.section("swap (4.4 A/B/C)");
        x = new PlanFx();
        x.del(x.file("A", 900 * MB), Tier.YOUR_CALL, "Y_CHAT_COPY", 0.0445);
        x.del(x.file("B", 900 * MB), Tier.YOUR_CALL, "Y_CHAT_COPY", 0.0445);
        FileRecord c = x.file("C", 1000 * MB); x.del(c, Tier.YOUR_CALL, "Y_SMALLER_COPY", 0.0545);
        p = x.planYourCall(GB);
        T.check("swap picks C alone", p.items.size() == 1 && PlanFx.has(p, c.fileId));

        T.section("tier order F-11");
        x = new PlanFx();
        FileRecord nl = x.file("Docs/folder", 400 * MB);
        x.rec(nl.fileId, Tier.NO_LOSS, Action.DELETE, "N_ARCHIVE_FOLDER", 400 * MB, 0);      // class 7 NO_LOSS (Phase 6 shape)
        x.del(x.file("y1", 300 * MB), Tier.YOUR_CALL, "Y_CHAT_COPY", 0.001);
        x.del(x.file("y2", 150 * MB), Tier.YOUR_CALL, "Y_CHAT_COPY", 0.0005);
        x.del(x.file("y3", 120 * MB), Tier.YOUR_CALL, "Y_CHAT_COPY", 0.002);
        p = x.planYourCall(520 * MB);
        T.check("NO_LOSS class 7 selected before any YOUR_CALL", PlanFx.has(p, nl.fileId));
        T.check("only the minimum YOUR_CALL bytes", PlanFx.tierBytes(p, Tier.YOUR_CALL) <= 150 * MB);

        T.section("30 GB size-only fixture");
        x = new PlanFx(); Random rnd = new Random(7);
        for (int i = 0; i < 300; i++) {
            Tier t = i % 3 == 0 ? Tier.SAFE : i % 3 == 1 ? Tier.NO_LOSS : Tier.YOUR_CALL;
            FileRecord f = x.file("f" + i, 100 * MB);
            x.del(f, t, t == Tier.SAFE ? "S_TEMP_FILE" : t == Tier.NO_LOSS ? "N_EXACT_DUPLICATE" : "Y_CHAT_COPY", t == Tier.YOUR_CALL ? 0.001 + rnd.nextDouble() * 0.05 : 0);
        }
        for (long g : new long[]{1, 5, 10, 20}) {
            p = x.plan(g * GB);
            T.check(g + " GB reached", p.reachesTarget && p.withYourCallBytes >= g * GB);
            T.eq(g + " GB highest tier", g <= 10 ? Tier.SAFE : Tier.NO_LOSS, p.highestTier);
        }

        T.section("brute force + determinism");
        Random r = new Random(42); double gapSum = 0; int inst = 0, worse = 0;
        for (int it = 0; it < 2000; it++) {
            x = new PlanFx(); int u = 2 + r.nextInt(11); long total = 0;
            List<Long> bytes = new ArrayList<Long>(); List<Double> cost = new ArrayList<Double>();
            for (int i = 0; i < u; i++) {
                long b = (1 + r.nextInt(100)) * 10 * MB; double el = r.nextDouble() * 0.05;
                x.del(x.file("u" + i, b), Tier.YOUR_CALL, "Y_CHAT_COPY", el);
                bytes.add(b); cost.add(UnitBuilder2.cost(x.in.recs.get(i))); total += b;
            }
            long target = (long) (total * (0.1 + 0.8 * r.nextDouble()));
            p = x.planYourCall(target);
            double pc = 0; for (ActionPlan.PlanItem i : p.items) pc += i.cost;
            double opt = Double.MAX_VALUE;
            for (int m = 1; m < (1 << u); m++) {
                long b = 0; double cc = 0;
                for (int i = 0; i < u; i++) if ((m & (1 << i)) != 0) { b += bytes.get(i); cc += cost.get(i); }
                if (b >= target && cc < opt) opt = cc;
            }
            T.check("brute force: reaches", p.reachesTarget);
            if (pc > opt + 1e-12) worse++;
            gapSum += opt > 0 ? (pc - opt) / opt : 0; inst++;
            if (it < 200) {
                Collections.shuffle(x.in.recs, r);
                ActionPlan q = x.planYourCall(target);
                T.eq("deterministic under shuffled input", PlanFx.files(p), PlanFx.files(q));
            }
        }
        double gap = gapSum / inst;
        System.out.println(String.format(Locale.ROOT, "  brute force: mean cost gap %.4f, suboptimal %d / %d", gap, worse, inst));
        T.check("mean cost gap <= 5 %", gap <= 0.05);

        T.section("reason catalogue");
        for (String code : new String[]{"PL_NEEDS_YOUR_CALL", "PL_TARGET_UNREACHABLE", "PL_HASH_PENDING", "PL_VALIDATOR_DROPPED", "S_APP_CACHE", "N_APP_ARCHIVE", "Y_IDLE_APP"})
            T.check("catalogue has " + code, ReasonCatalog.codes().contains(code));
        return T.finish("Planner2Test");
    }
}
