package com.storageplanner.test;
import com.storageplanner.core.*;
import java.util.ArrayList; import java.util.Collections; import java.util.Comparator; import java.util.HashSet; import java.util.List; import java.util.Map; import java.util.Set;
/** Keep (exclude) items, re-plan, check the plan never touches kept items and still meets target when possible. */
public final class ReviewTest {
    public static void main(String[] a) {
        JvmPlatform pf = new JvmPlatform(a[0]);
        Scanner.Result r = Scanner.scan(pf, new Scanner.Progress() { public void update(String s, int d, int t) {} public boolean cancelled() { return false; } });
        UnitBuilder ub = new UnitBuilder(r, new Estimates(), 34);
        long used = r.total - r.free, need = used - (long) (r.total * 70.0 / 128.0);
        Map<String, Long> bd = Categories.breakdown(r);
        long sum = 0; for (long v : bd.values()) sum += v;
        System.out.println("breakdown sums to used: " + (sum == used) + "  " + bd);
        Map<String, List<Unit>> cands = Review.candidates(ub.units);
        for (Map.Entry<String, List<Unit>> e : cands.entrySet()) {
            int tier = Review.tier(e.getKey(), e.getValue());
            String what = Techniques.info(e.getKey()).what;
            if (what.isEmpty()) System.out.println("MISSING explanation for " + e.getKey());
            System.out.printf("  R%d %-38s %4d candidates%n", tier, e.getKey(), e.getValue().size());
        }
        Set<String> kept = new HashSet<String>();
        Planner.Plan p0 = Planner.tiered(ub.units, need);
        // keep the 10 biggest planned items, then 50 more, re-plan each time
        List<Map.Entry<Unit, Option>> es = new ArrayList<Map.Entry<Unit, Option>>(p0.chosen.entrySet());
        Collections.sort(es, new Comparator<Map.Entry<Unit, Option>>() { public int compare(Map.Entry<Unit, Option> x, Map.Entry<Unit, Option> y) { return Long.compare(y.getValue().saved, x.getValue().saved); } });
        for (int k : new int[]{10, 60}) {
            for (int i = 0; i < Math.min(k, es.size()); i++) kept.add(es.get(i).getKey().id);
            List<Unit> act = Review.active(ub.units, kept);
            Planner.Plan p = Planner.tiered(act, need);
            boolean touched = false; for (Unit u : p.chosen.keySet()) if (kept.contains(u.id)) touched = true;
            long max = Planner.bestWithin(act, 4).saved();
            System.out.printf("kept %d -> reaches=%s (feasible=%s) saved %s tier R%d confirmations %d, kept items touched: %s%n",
                    kept.size(), p.reachesTarget, max >= need, Report.size(p.saved()), p.tier, p.confirmations(), touched);
        }
    }
}
