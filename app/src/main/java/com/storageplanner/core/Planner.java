package com.storageplanner.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stage 3: pick at most one option per unit.
 * Tiered planner: use the lowest risk tier that can reach the target; inside it, take the cheapest
 * cost-per-byte upgrades first, then prune anything not needed. In the research prototype this
 * greedy+prune matched the exact knapsack DP to within half a cost point, and runs in milliseconds
 * on tens of thousands of units.
 */
public final class Planner {

    public static final class Plan {
        public final Map<Unit, Option> chosen = new LinkedHashMap<Unit, Option>();
        public int tier = -1;            // highest risk tier used, -1 if target unreachable
        public boolean reachesTarget;
        public long saved() { long s = 0; for (Option o : chosen.values()) s += o.saved; return s; }
        public long savedExact() { long s = 0; for (Option o : chosen.values()) if (o.exact) s += o.saved; return s; }
        public double cost() { double c = 0; for (Option o : chosen.values()) c += o.cost(); return c; }
        public int confirmations() { int n = 0; for (Option o : chosen.values()) if (o.risk >= Option.R4) n++; return n; }
    }

    /** Best (largest) option per unit with risk <= maxRisk. */
    public static Plan bestWithin(List<Unit> units, int maxRisk) {
        Plan p = new Plan();
        for (Unit u : units) {
            Option best = null;
            for (Option o : u.options)
                if (o.risk <= maxRisk && (best == null || o.saved > best.saved || (o.saved == best.saved && o.cost() < best.cost())))
                    best = o;
            if (best != null) p.chosen.put(u, best);
        }
        return p;
    }

    /** Minimum-intervention plan reaching targetSaving bytes. */
    public static Plan tiered(List<Unit> units, long targetSaving) {
        for (int k = 0; k <= Option.R4; k++) {
            Plan top = bestWithin(units, k);
            if (top.saved() < targetSaving) continue;
            Plan p = greedy(units, targetSaving, k);
            prune(p, targetSaving, k);
            p.tier = k; p.reachesTarget = true;
            return p;
        }
        Plan all = bestWithin(units, Option.R4);
        all.reachesTarget = false; all.tier = -1;
        return all;
    }

    private static final class Move { Unit u; Option o; double key; }

    private static Plan greedy(List<Unit> units, long target, int maxRisk) {
        Plan p = bestWithin(units, Option.R0);          // free options are always taken
        List<Move> moves = new ArrayList<Move>();
        for (Unit u : units) {
            Option cur = p.chosen.get(u);
            long cs = cur == null ? 0 : cur.saved; double cc = cur == null ? 0 : cur.cost();
            for (Option o : u.options) {
                if (o.risk > maxRisk || o.saved <= cs) continue;
                Move m = new Move(); m.u = u; m.o = o; m.key = (o.cost() - cc) / (double) (o.saved - cs);
                moves.add(m);
            }
        }
        Collections.sort(moves, new Comparator<Move>() {
            public int compare(Move a, Move b) { return Double.compare(a.key, b.key); }
        });
        long total = p.saved();
        for (Move m : moves) {
            if (total >= target) break;
            Option cur = p.chosen.get(m.u);
            if (cur == null || m.o.saved > cur.saved) {
                total += m.o.saved - (cur == null ? 0 : cur.saved);
                p.chosen.put(m.u, m.o);
            }
        }
        return p;
    }

    /** Downgrade or drop the costliest-per-byte choices while staying at or above the target. */
    private static void prune(Plan p, long target, int maxRisk) {
        List<Map.Entry<Unit, Option>> es = new ArrayList<Map.Entry<Unit, Option>>(p.chosen.entrySet());
        Collections.sort(es, new Comparator<Map.Entry<Unit, Option>>() {
            public int compare(Map.Entry<Unit, Option> a, Map.Entry<Unit, Option> b) {
                double ka = a.getValue().cost() / Math.max(1, a.getValue().saved), kb = b.getValue().cost() / Math.max(1, b.getValue().saved);
                return Double.compare(kb, ka);
            }
        });
        long total = p.saved();
        for (Map.Entry<Unit, Option> e : es) {
            Option cur = e.getValue();
            if (cur.cost() <= 0) continue;
            Option bestAlt = null; long bestAltSaved = 0; double bestAltCost = cur.cost();
            // "keep" = saved 0, cost 0
            if (total - cur.saved >= target) { bestAltCost = 0; bestAlt = null; bestAltSaved = 0; }
            else {
                for (Option o : e.getKey().options) {
                    if (o == cur || o.risk > maxRisk || o.cost() >= bestAltCost) continue;
                    if (total - cur.saved + o.saved >= target) { bestAlt = o; bestAltSaved = o.saved; bestAltCost = o.cost(); }
                }
                if (bestAltCost >= cur.cost()) continue;   // nothing cheaper fits
            }
            total = total - cur.saved + bestAltSaved;
            if (bestAlt == null) p.chosen.remove(e.getKey()); else p.chosen.put(e.getKey(), bestAlt);
        }
    }

    /** Bytes by technique, in display order (lowest risk first, then size). */
    public static List<Object[]> byTechnique(Plan p) {
        Map<String, Object[]> m = new LinkedHashMap<String, Object[]>();
        for (Map.Entry<Unit, Option> e : p.chosen.entrySet()) {
            Option o = e.getValue();
            Object[] row = m.get(o.technique);
            if (row == null) { row = new Object[]{o.technique, 0L, 0, o.risk, Boolean.TRUE, new ArrayList<Unit>()}; m.put(o.technique, row); }
            row[1] = (Long) row[1] + o.saved; row[2] = (Integer) row[2] + 1;
            row[3] = Math.max((Integer) row[3], o.risk);
            if (!o.exact) row[4] = Boolean.FALSE;
            @SuppressWarnings("unchecked") List<Unit> l = (List<Unit>) row[5]; l.add(e.getKey());
        }
        List<Object[]> rows = new ArrayList<Object[]>(m.values());
        Collections.sort(rows, new Comparator<Object[]>() {
            public int compare(Object[] a, Object[] b) {
                int ra = (Integer) a[3], rb = (Integer) b[3];
                if (ra != rb) return ra - rb;
                return Long.compare((Long) b[1], (Long) a[1]);
            }
        });
        return rows;
    }
}
