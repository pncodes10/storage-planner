package com.storageplanner.test;
import com.storageplanner.core.Estimates;
import com.storageplanner.core.Option;
import com.storageplanner.core.Planner;
import com.storageplanner.core.Review;
import com.storageplanner.core.Unit;
import com.storageplanner.core.UnitBuilder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
/** Swipe decisions: accepted items are always in the plan with the accepted option, kept items never are, target still met. */
public final class DecisionTest {
    public static void main(String[] a) {
        JvmPlatform pf = new JvmPlatform(a[0]);
        com.storageplanner.core.Scanner.Result r = com.storageplanner.core.Scanner.scan(pf, new com.storageplanner.core.Scanner.Progress() {
            public void update(String s, int d, int t) {} public boolean cancelled() { return false; } });
        UnitBuilder ub = new UnitBuilder(r, new Estimates(), 34);
        long need = (r.total - r.free) - (long) (r.total * 70.0 / 128.0);
        Random rnd = new Random(3); int bad = 0, runs = 300;
        for (int t = 0; t < runs; t++) {
            Set<String> kept = new HashSet<String>(); Map<String, String> acc = new HashMap<String, String>();
            for (Unit u : ub.units) {
                double x = rnd.nextDouble();
                if (x < 0.08) kept.add(u.id);
                else if (x < 0.20) acc.put(u.id, u.options.isEmpty()
                        ? Review.CHOSEN + com.storageplanner.core.Techniques.BURSTS          // a kept shot the user picked for deletion
                        : u.options.get(rnd.nextInt(u.options.size())).technique);
            }
            // conflicting decision on purpose: keep must win
            for (String id : new ArrayList<String>(acc.keySet())) { if (rnd.nextDouble() < 0.05) kept.add(id); }
            for (int mode = 0; mode < 2; mode++) {
                Planner.Plan p = Review.planWithDecisions(ub.units, kept, acc, need, mode);
                boolean ok = true;
                for (Map.Entry<Unit, Option> e : p.chosen.entrySet()) if (kept.contains(e.getKey().id)) ok = false;
                for (Unit u : ub.units) {
                    String tech = acc.get(u.id);
                    if (tech == null || kept.contains(u.id)) continue;
                    Option o = p.chosen.get(u);
                    Option want = Review.optionFor(u, tech);
                    if (o == null || o != want) ok = false;
                }
                List<Unit> act = Review.active(ub.units, kept);
                // feasibility: if everything allowed (accepted forced + best of the rest) reaches need, the plan must too
                long best = 0;
                for (Unit u : act) {
                    String tech = acc.get(u.id); long m = 0;
                    if (tech != null) m = Review.optionFor(u, tech).saved;
                    else for (Option o : u.options) if (mode == 0 || o.risk <= Option.R2) m = Math.max(m, o.saved);
                    best += m;
                }
                if (mode == 0 && best >= need && !p.reachesTarget) ok = false;
                if (p.reachesTarget != (p.saved() >= need)) ok = false;
                if (!ok) { bad++; if (bad < 4) System.out.println("FAIL run " + t + " mode " + mode); }
            }
        }
        int empty = 0; for (Unit u : ub.units) if (u.options.isEmpty()) empty++;
        System.out.println("  " + empty + " kept copies can be picked for deletion by the user");
        if (empty == 0) { bad++; System.out.println("FAIL no kept-copy units"); }
        System.out.println((2 * runs - bad) + "/" + (2 * runs) + " decision checks pass");
    }
}
