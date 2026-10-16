package com.storageplanner.test;

import com.storageplanner.core.Estimates;
import com.storageplanner.core.Option;
import com.storageplanner.core.Planner;
import com.storageplanner.core.Review;
import com.storageplanner.core.Scanner;
import com.storageplanner.core.Techniques;
import com.storageplanner.core.Unit;
import com.storageplanner.core.UnitBuilder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Ticking copies of a photo (duplicates, burst shots, Sent copies) on the virtual phone: whatever is ticked
 * (one, several, all, the suggested keeper, nothing) the plan deletes exactly the ticked files of that group,
 * never an unticked one, still reaches the target when possible, and changing the ticks later replaces the
 * earlier choice completely.
 */
public final class GroupDecisionTest {
    public static void main(String[] a) {
        JvmPlatform pf = new JvmPlatform(a[0]);
        Scanner.Result r = Scanner.scan(pf, new Scanner.Progress() { public void update(String s, int d, int t) {} public boolean cancelled() { return false; } });
        UnitBuilder ub = new UnitBuilder(r, new Estimates(), 34);
        Map<String, Unit> byId = new HashMap<String, Unit>();
        for (Unit u : ub.units) byId.put(u.id, u);
        // groups: keeper -> copies, as the app builds them
        Map<String, List<String>> groups = new LinkedHashMap<String, List<String>>();
        Map<String, String> tech = new HashMap<String, String>();
        add(groups, tech, ub.det.duplicateOf, Techniques.DUPLICATES);
        add(groups, tech, ub.det.burstExtraOf, Techniques.BURSTS);
        add(groups, tech, ub.det.sentCopyOf, Techniques.SENT);
        long need = (r.total - r.free) - (long) (r.total * 70.0 / 128.0);
        Random rnd = new Random(9);
        int checks = 0, bad = 0, multi = 0;
        for (List<String> g : groups.values()) if (g.size() > 2) multi++;
        for (int run = 0; run < 150; run++) {
            Set<String> kept = new HashSet<String>();
            Map<String, String> acc = new HashMap<String, String>();
            Map<String, Boolean> last = new HashMap<String, Boolean>();      // file -> ticked in the latest decision touching it
            Set<String> lastAsBest = new HashSet<String>();
            List<String> keys = new ArrayList<String>(groups.keySet());
            for (int step = 0; step < 12; step++) {                       // decide groups, sometimes re-decide one
                String k = keys.get(rnd.nextInt(keys.size()));
                List<String> members = groups.get(k);
                Set<String> del = new HashSet<String>();
                int mode = rnd.nextInt(5);
                for (String m : members) {
                    boolean isBest = m.equals(k);
                    if (mode == 0) del.add(m);                              // all
                    else if (mode == 1) { /* none */ }
                    else if (mode == 2) { if (!isBest) del.add(m); }        // the suggestion
                    else if (mode == 3) { if (isBest) del.add(m); }         // keep a different one instead
                    else if (rnd.nextBoolean()) del.add(m);                 // anything
                }
                List<Unit> us = new ArrayList<Unit>();
                for (String m : members) if (byId.containsKey(m)) us.add(byId.get(m));
                Review.GroupDecision d = Review.groupDecision(us, byId.get(k), del, tech.get(k), acc);
                for (Unit u : d.clear) { kept.remove(u.id); acc.remove(u.id); }
                for (Unit u : d.keep) { kept.add(u.id); acc.remove(u.id); }
                for (Map.Entry<Unit, String> e : d.accept.entrySet()) { acc.put(e.getKey().id, e.getValue()); kept.remove(e.getKey().id); }
                for (String m : members) {
                    last.put(m, del.contains(m));
                    if (m.equals(k)) lastAsBest.add(m); else lastAsBest.remove(m);
                }
            }
            for (int pm = 0; pm < 2; pm++) {
                Planner.Plan p = Review.planWithDecisions(ub.units, kept, acc, need, pm);
                Set<String> deleted = new HashSet<String>();
                for (Map.Entry<Unit, Option> e : p.chosen.entrySet()) if (e.getValue().action.equals("delete")) deleted.add(e.getKey().id);
                for (Map.Entry<String, Boolean> e : last.entrySet()) {
                    String m = e.getKey();
                    if (!byId.containsKey(m)) continue;
                    boolean want = e.getValue();
                    boolean ownDelete = Review.deleteOption(byId.get(m)) != null;
                    // an unticked suggested keeper only loses earlier approvals; if it is itself a copy of
                    // something else, the planner may still use its own delete option
                    boolean ok = want ? deleted.contains(m) : !deleted.contains(m) || (lastAsBest.contains(m) && ownDelete && !kept.contains(m));
                    checks++;
                    if (!ok) { bad++; if (bad < 6) System.out.println("FAIL run " + run + " " + m + " ticked=" + want + " deleted=" + deleted.contains(m)); }
                }
                checks++;
                if (p.reachesTarget != (p.saved() >= need)) { bad++; System.out.println("FAIL reach flag"); }
            }
        }
        System.out.println("  " + groups.size() + " groups (" + multi + " with 3+ copies) on the virtual phone");
        if (groups.isEmpty() || multi == 0) { bad++; System.out.println("FAIL fixture has no multi-copy groups"); }
        System.out.println((checks - bad) + "/" + checks + " group decision checks pass");
        if (bad > 0) System.exit(1);
    }

    static void add(Map<String, List<String>> groups, Map<String, String> tech, Map<String, String> copyOf, String t) {
        for (Map.Entry<String, String> e : copyOf.entrySet()) {
            List<String> g = groups.get(e.getValue());
            if (g == null) { g = new ArrayList<String>(); g.add(e.getValue()); groups.put(e.getValue(), g); }
            if (!g.contains(e.getKey())) g.add(e.getKey());
            if (!tech.containsKey(e.getValue()) || t.equals(Techniques.BURSTS)) tech.put(e.getValue(), t);
        }
    }
}
