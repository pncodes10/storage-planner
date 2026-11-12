package com.storageplanner.v2adapter;

import com.storageplanner.core.Option;
import com.storageplanner.core.Planner;
import com.storageplanner.core.Unit;

import java.util.*;

/**
 * Session planning for a v2 result (replaces Review.planWithDecisions only while a v2 result is shown; classic is untouched).
 * Units in notPlannable (YOUR_CALL without opt-in, REVIEW, PROTECTED) are never planned and their "accepts" are ignored;
 * "chosen:" user deletions (Unit.userDelete) are ignored, so a keeper or unique file can never enter a v2 plan that way;
 * the planner never goes above R1 (SAFE + NO_LOSS, ruling A29): an unreachable target reports the achievable amount.
 */
public final class V2Plans {
    private V2Plans() {}
    public static final int MODE_TARGET = 0, MODE_SAFE = 1;
    /** Highest classic risk a v2 plan uses without opt-in. */
    public static final int MAX_RISK = Option.R1;

    public static Planner.Plan plan(List<Unit> units, Set<String> notPlannable, Set<String> kept, Map<String, String> accepted, long need, int mode) {
        List<Unit> rest = new ArrayList<Unit>();
        Map<Unit, Option> forced = new LinkedHashMap<Unit, Option>();
        long forcedSaved = 0;
        for (Unit u : units) {
            if (notPlannable.contains(u.id) || kept.contains(u.id)) continue;
            String t = accepted.get(u.id);
            Option o = null;
            if (t != null) for (Option x : u.options) if (x.technique.equals(t) && x.risk <= MAX_RISK && x.saved > 0) o = x;
            if (o != null) { forced.put(u, o); forcedSaved += o.saved; }
            else rest.add(u);
        }
        Planner.Plan p;
        Planner.Plan top = Planner.bestWithin(rest, MAX_RISK);
        if (mode == MODE_SAFE) p = top;
        else {
            long rem = Math.max(0, need - forcedSaved);
            p = top.saved() >= rem ? Planner.tiered(rest, rem) : top;   // tiered stops at R0 or R1 here
        }
        p.chosen.putAll(forced);
        p.reachesTarget = p.saved() >= need;
        int tier = -1;
        for (Option o : p.chosen.values()) tier = Math.max(tier, o.risk);
        p.tier = p.reachesTarget ? tier : -1;
        return p;
    }
}
