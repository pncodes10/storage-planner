package com.storageplanner.test;
import com.storageplanner.core.*;
import java.util.*;
/** Randomised checks: plan meets target when feasible, uses the lowest feasible tier, and never exceeds it. */
public final class PlannerTest {
    public static void main(String[] a) throws Exception {
        Random rnd = new Random(7); int bad = 0, runs = 2000;
        for (int t = 0; t < runs; t++) {
            List<Unit> units = new ArrayList<Unit>();
            int n = 2 + rnd.nextInt(30);
            for (int i = 0; i < n; i++) {
                Unit u = Unit.forFile(new FileItem("f" + i, "/x/f" + i, 1, 0));
                int k = 1 + rnd.nextInt(3);
                for (int j = 0; j < k; j++) {
                    int r = rnd.nextInt(5);
                    u.add(new Option(j == 0 ? "delete" : "reencode" + j, "T" + r, 1 + rnd.nextInt(1000), r, true, ""));
                }
                units.add(u);
            }
            long max = Planner.bestWithin(units, 4).saved();
            long target = rnd.nextInt(10) == 0 ? 0 : (long) (rnd.nextDouble() * max * 1.2);
            Planner.Plan p = Planner.tiered(units, target);
            int lowest = -1;
            for (int k = 0; k <= 4; k++) if (Planner.bestWithin(units, k).saved() >= target) { lowest = k; break; }
            boolean ok;
            if (lowest < 0) ok = !p.reachesTarget && p.saved() == max;
            else {
                int maxRisk = -1; for (Option o : p.chosen.values()) maxRisk = Math.max(maxRisk, o.risk);
                ok = p.reachesTarget && p.saved() >= target && p.tier == lowest && maxRisk <= lowest;
                for (Map.Entry<Unit, Option> e : p.chosen.entrySet()) if (!e.getKey().options.contains(e.getValue())) ok = false;
            }
            if (!ok) { bad++; if (bad < 5) System.out.println("FAIL run " + t + " target " + target + " saved " + p.saved() + " tier " + p.tier + " lowest " + lowest); }
        }
        System.out.println((runs - bad) + "/" + runs + " planner property checks pass");
    }
}
