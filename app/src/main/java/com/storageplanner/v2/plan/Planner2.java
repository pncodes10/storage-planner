package com.storageplanner.v2.plan;

import com.storageplanner.v2.model.*;
import com.storageplanner.v2.plan.PlanUnit.Option2;

import java.util.*;

/**
 * PHASE_5 4.3: lexicographic greedy (tier -> spec 10.3 priority class -> cost per byte), SAFE always taken, prune pass, single-item
 * swap within the highest tier used (REVIEW_2 F-11). YOUR_CALL options enter a plan only with utility > 0 (ruling A25) and only when
 * the request allows YOUR_CALL. Never selects REVIEW/PROTECTED (they have no options) and never raises risk silently.
 */
public final class Planner2 {
    private Planner2() {}

    public static int tierRank(Tier t) { return Tier.rank(t); }
    public static final Comparator<Option2> KEY = new Comparator<Option2>() {
        public int compare(Option2 a, Option2 b) {
            int c = Integer.compare(tierRank(a.tier), tierRank(b.tier)); if (c != 0) return c;
            c = Integer.compare(a.priorityClass, b.priorityClass); if (c != 0) return c;
            c = Double.compare(a.cost / Math.max(1, a.bytes), b.cost / Math.max(1, b.bytes)); if (c != 0) return c;
            c = Long.compare(b.bytes, a.bytes); if (c != 0) return c;
            c = a.unit.id.compareTo(b.unit.id); if (c != 0) return c;
            return a.action.compareTo(b.action);
        }
    };
    static boolean eligible(Option2 o, Tier k) {
        if (tierRank(o.tier) > tierRank(k) || !UnitBuilder2.plannableTier(o.tier) || o.bytes <= 0) return false;
        return o.tier != Tier.YOUR_CALL || o.utility > 0;      // A25: utility decides plan inclusion of YOUR_CALL items
    }

    /** Current selection with the §3 bookkeeping. */
    static final class Sel {
        final Map<String, Option2> chosen = new TreeMap<String, Option2>();
        final Set<String> removed = new HashSet<String>(), transformed = new HashSet<String>();
        final Map<String, Integer> required = new HashMap<String, Integer>();
        long bytes;
        boolean allows(Option2 o) {
            PlanUnit u = o.unit;
            if (chosen.containsKey(u.id)) return false;
            if (o.removesFiles() || o.transformsFiles()) for (String f : u.fileIds) if (required.containsKey(f)) return false;   // H1, V4
            for (String f : u.fileIds) if (removed.contains(f) || transformed.contains(f)) return false;                         // one action per file
            if (o.removesFiles() || o.action == Action.UNINSTALL_APP) for (String r : u.requires) if (removed.contains(r)) return false;
            for (String c : o.conflicts) if (chosen.containsKey(c)) return false;
            for (Option2 s : chosen.values()) if (s.conflicts.contains(u.id)) return false;
            return true;
        }
        void add(Option2 o) {
            chosen.put(o.unit.id, o); bytes += o.bytes;
            if (o.removesFiles()) removed.addAll(o.unit.fileIds);
            if (o.transformsFiles()) transformed.addAll(o.unit.fileIds);
            if (o.removesFiles() || o.action == Action.UNINSTALL_APP) for (String r : o.unit.requires) { Integer n = required.get(r); required.put(r, n == null ? 1 : n + 1); }
        }
        void remove(Option2 o) {
            if (chosen.remove(o.unit.id) == null) return;
            bytes -= o.bytes;
            if (o.removesFiles()) removed.removeAll(o.unit.fileIds);
            if (o.transformsFiles()) transformed.removeAll(o.unit.fileIds);
            if (o.removesFiles() || o.action == Action.UNINSTALL_APP) for (String r : o.unit.requires) {
                Integer n = required.get(r); if (n == null || n <= 1) required.remove(r); else required.put(r, n - 1);
            }
        }
        /** Adds o, replacing a lower-bytes option of the same unit; restores the old option if o is not allowed. */
        boolean offer(Option2 o) {
            Option2 old = chosen.get(o.unit.id);
            if (old != null) { if (old.bytes >= o.bytes) return false; remove(old); }
            if (allows(o)) { add(o); return true; }
            if (old != null) add(old);
            return false;
        }
    }

    static List<Option2> options(List<PlanUnit> units, Tier lo, Tier k) {
        List<Option2> l = new ArrayList<Option2>();
        for (PlanUnit u : units) for (Option2 o : u.options) if (eligible(o, k) && tierRank(o.tier) >= tierRank(lo)) l.add(o);
        Collections.sort(l, KEY); return l;
    }
    static Sel base(List<PlanUnit> units) {
        Sel s = new Sel();
        for (Option2 o : options(units, Tier.SAFE, Tier.SAFE)) s.offer(o);      // largest SAFE option per unit wins via offer()
        return s;
    }
    static void fill(Sel s, List<PlanUnit> units, Tier k, long target) {
        for (Option2 o : options(units, Tier.NO_LOSS, k)) {
            if (target != ActionPlan.MAX_SAFE && s.bytes >= target) return;
            s.offer(o);
        }
    }
    static long cap(List<PlanUnit> units, Tier k) { Sel s = base(units); fill(s, units, k, ActionPlan.MAX_SAFE); return s.bytes; }

    static void prune(Sel s, List<PlanUnit> units, Tier k, long target) {
        List<Option2> sel = new ArrayList<Option2>();
        for (Option2 o : s.chosen.values()) if (o.tier != Tier.SAFE) sel.add(o);
        Collections.sort(sel, Collections.reverseOrder(KEY));
        for (Option2 o : sel) {
            if (s.bytes - o.bytes >= target) { s.remove(o); continue; }
            List<Option2> alts = new ArrayList<Option2>();
            for (Option2 a : o.unit.options) if (a != o && a.bytes < o.bytes && eligible(a, k)) alts.add(a);
            Collections.sort(alts, new Comparator<Option2>() { public int compare(Option2 a, Option2 b) { return Long.compare(b.bytes, a.bytes); } });
            for (Option2 a : alts) {
                if (s.bytes - o.bytes + a.bytes < target) continue;
                s.remove(o);
                if (s.allows(a)) { s.add(a); break; }
                s.add(o);
            }
        }
    }
    /** REVIEW_2 F-11: replace the selected tier-k set G by one tier-k option covering the remainder at lower cost; lower tiers stay. */
    static void swap(Sel s, List<PlanUnit> units, Tier k, long target) {
        if (k == Tier.SAFE) return;
        List<Option2> g = new ArrayList<Option2>(); double gc = 0; long gb = 0;
        for (Option2 o : s.chosen.values()) if (o.tier == k) { g.add(o); gc += o.cost; gb += o.bytes; }
        if (g.isEmpty()) return;
        long r = target - (s.bytes - gb);
        for (Option2 o : g) s.remove(o);
        Option2 best = null;
        for (Option2 c : options(units, k, k)) {
            if (c.tier != k || c.bytes < r || c.cost >= gc || !s.allows(c)) continue;
            if (best == null || c.cost < best.cost || (c.cost == best.cost && KEY.compare(c, best) < 0)) best = c;
        }
        if (best != null) s.add(best); else for (Option2 o : g) s.add(o);
    }

    public static ActionPlan plan(List<PlanUnit> units, long target, Tier maxTier) {
        ActionPlan p = new ActionPlan(); p.targetBytes = target;
        p.maxTier = maxTier == null ? (target == ActionPlan.MAX_SAFE ? Tier.NO_LOSS : Tier.YOUR_CALL) : maxTier;
        if (tierRank(p.maxTier) > tierRank(Tier.YOUR_CALL)) p.maxTier = Tier.YOUR_CALL;
        Sel s = base(units);
        Tier used = null;
        if (target == ActionPlan.MAX_SAFE) {
            Tier k = tierRank(p.maxTier) < tierRank(Tier.NO_LOSS) ? Tier.SAFE : Tier.NO_LOSS;   // "maximum safe" never uses YOUR_CALL
            if (k == Tier.NO_LOSS) fill(s, units, Tier.NO_LOSS, target);
            p.reachesTarget = true; return finish(p, s);
        }
        if (s.bytes >= target) { p.reachesTarget = true; return finish(p, s); }
        for (Tier k : new Tier[]{Tier.NO_LOSS, Tier.YOUR_CALL}) {
            if (tierRank(k) > tierRank(p.maxTier)) break;
            if (cap(units, k) < target) continue;
            Sel t = base(units);
            fill(t, units, k, target);
            prune(t, units, k, target);
            swap(t, units, k, target);
            p.reachesTarget = t.bytes >= target; used = k;
            return finish(p, t);
        }
        // unreachable within the allowed risk: report what is achievable, never raise risk beyond maxTier
        Tier k = tierRank(p.maxTier) >= tierRank(Tier.NO_LOSS) ? Tier.NO_LOSS : Tier.SAFE;
        if (k == Tier.NO_LOSS) fill(s, units, Tier.NO_LOSS, ActionPlan.MAX_SAFE);
        if (p.maxTier == Tier.YOUR_CALL) fill(s, units, Tier.YOUR_CALL, ActionPlan.MAX_SAFE);
        p.reachesTarget = false;
        finish(p, s);
        p.reasonCodes.add("PL_TARGET_UNREACHABLE");
        p.reasonParams.put("target", Fmt.bytes(target)); p.reasonParams.put("achievable", Fmt.bytes(p.achievableBytes));
        p.reasonParams.put("withYourCall", Fmt.bytes(p.withYourCallBytes));
        return p;
    }
    static ActionPlan finish(ActionPlan p, Sel s) {
        List<Option2> sel = new ArrayList<Option2>(s.chosen.values());
        Collections.sort(sel, KEY);
        int yc = 0; long ycb = 0;
        for (Option2 o : sel) {
            ActionPlan.PlanItem it = new ActionPlan.PlanItem();
            it.unitId = o.unit.id; it.subject = o.unit.subject; it.recId = o.recId; it.code = o.code; it.fileIds.addAll(o.unit.fileIds);
            it.action = o.action; it.tier = o.tier; it.bytes = o.bytes; it.exact = o.exact; it.priorityClass = o.priorityClass;
            it.cost = o.cost; it.utility = o.utility; it.requires.addAll(o.unit.requires);
            p.items.add(it);
            if (o.tier == Tier.YOUR_CALL) { yc++; ycb += o.bytes; }
            else { p.achievableBytes += o.bytes; if (o.exact) p.achievableExactBytes += o.bytes; }
            if (p.highestTier == null || tierRank(o.tier) > tierRank(p.highestTier)) p.highestTier = o.tier;
        }
        p.withYourCallBytes = p.achievableBytes + ycb;
        if (yc > 0) {
            p.reasonCodes.add("PL_NEEDS_YOUR_CALL");
            p.reasonParams.put("target", Fmt.bytes(p.targetBytes)); p.reasonParams.put("n", String.valueOf(yc));
            p.reasonParams.put("bytes", Fmt.bytes(ycb)); p.reasonParams.put("safe", Fmt.bytes(p.achievableBytes));
        }
        return p;
    }
    /** Total bytes of a plan's items. */
    public static long itemBytes(ActionPlan p) { long b = 0; for (ActionPlan.PlanItem i : p.items) b += i.bytes; return b; }
}
