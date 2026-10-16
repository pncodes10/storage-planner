package com.storageplanner.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** User review: items the user chose to keep are removed from planning; statuses for the UI. */
public final class Review {
    private Review() {}

    public enum Status { IN_PLAN, PLANNED_OTHER, NOT_NEEDED, KEPT, NONE }

    /** Units the planner may use (everything the user has not chosen to keep). */
    public static List<Unit> active(List<Unit> units, Set<String> kept) {
        List<Unit> out = new ArrayList<Unit>(units.size());
        for (Unit u : units) if (!kept.contains(u.id)) out.add(u);
        return out;
    }

    /** Prefix of an accepted technique the user picked for a file the planner would keep (see Unit.userDelete). */
    public static final String CHOSEN = "chosen:";

    public static Option optionFor(Unit u, String technique) {
        if (technique == null) return null;
        if (technique.startsWith(CHOSEN)) return u.userDelete(technique.substring(CHOSEN.length()));
        for (Option o : u.options) if (o.technique.equals(technique)) return o;
        return null;
    }

    /** The unit's delete option, if any (a file has at most one). */
    public static Option deleteOption(Unit u) {
        for (Option o : u.options) if (o.action.equals("delete")) return o;
        return null;
    }

    /** Status of a unit, seen from one recommendation (technique may be null = any). */
    public static Status status(Unit u, String technique, Planner.Plan plan, Set<String> kept) {
        if (u == null) return Status.NONE;
        if (kept.contains(u.id)) return Status.KEPT;
        Option chosen = plan.chosen.get(u);
        if (chosen == null) return Status.NOT_NEEDED;
        if (technique == null || chosen.technique.equals(technique)) return Status.IN_PLAN;
        return Status.PLANNED_OTHER;
    }

    /** Every unit that could use a technique, grouped by technique; inside a group: largest saving first. */
    public static Map<String, List<Unit>> candidates(List<Unit> units) {
        Map<String, List<Unit>> m = new LinkedHashMap<String, List<Unit>>();
        for (Unit u : units)
            for (Option o : u.options) {
                List<Unit> l = m.get(o.technique);
                if (l == null) { l = new ArrayList<Unit>(); m.put(o.technique, l); }
                l.add(u);
            }
        for (final Map.Entry<String, List<Unit>> e : m.entrySet())
            Collections.sort(e.getValue(), new Comparator<Unit>() {
                public int compare(Unit a, Unit b) {
                    return Long.compare(optionFor(b, e.getKey()).saved, optionFor(a, e.getKey()).saved);
                }
            });
        return m;
    }

    /**
     * Plan that respects the user's review: kept units are never used; accepted units (unit id ->
     * accepted technique) are locked in with that option; the planner covers what is still needed.
     * mode 0 = minimum to reach the target, 1 = every safe step (R0-R2).
     */
    public static Planner.Plan planWithDecisions(List<Unit> units, Set<String> kept, Map<String, String> accepted, long need, int mode) {
        List<Unit> rest = new ArrayList<Unit>();
        Map<Unit, Option> forced = new LinkedHashMap<Unit, Option>();
        long forcedSaved = 0; int forcedTier = -1;
        for (Unit u : units) {
            if (kept.contains(u.id)) continue;
            String t = accepted.get(u.id);
            Option o = t == null ? null : optionFor(u, t);
            if (o != null) { forced.put(u, o); forcedSaved += o.saved; forcedTier = Math.max(forcedTier, o.risk); }
            else rest.add(u);
        }
        Planner.Plan p = mode == 1 ? Planner.bestWithin(rest, Option.R2) : Planner.tiered(rest, Math.max(0, need - forcedSaved));
        p.chosen.putAll(forced);
        p.reachesTarget = p.saved() >= need;
        int tier = -1;
        for (Option o : p.chosen.values()) tier = Math.max(tier, o.risk);
        p.tier = p.reachesTarget ? Math.max(tier, forcedTier) : -1;
        return p;
    }

    /** What a group decision changes: units to keep, units to approve for deletion (unit -> technique), units to clear. */
    public static final class GroupDecision {
        public final List<Unit> keep = new ArrayList<Unit>(), clear = new ArrayList<Unit>();
        public final Map<Unit, String> accept = new LinkedHashMap<Unit, String>();
    }

    /**
     * Copies of one photo, decided together: ticked units are approved for deletion (their own delete option,
     * or a user-picked deletion for the suggested keeper); unticked copies are kept; the suggested keeper,
     * when unticked, only loses an earlier deletion approval (it may still be planned for re-encoding).
     * groupTechnique: technique recorded when the keeper itself is deleted. accepted: current approvals.
     */
    public static GroupDecision groupDecision(List<Unit> members, Unit best, Set<String> delete, String groupTechnique,
                                              Map<String, String> accepted) {
        GroupDecision d = new GroupDecision();
        for (Unit u : members) {
            if (delete.contains(u.id)) {
                Option o = deleteOption(u);
                d.accept.put(u, o != null ? o.technique : CHOSEN + groupTechnique);
            } else if (u == best) {
                Option o = optionFor(u, accepted.get(u.id));
                if (o != null && o.action.equals("delete")) d.clear.add(u);
            } else d.keep.add(u);
        }
        return d;
    }

    /** Lowest risk tier among a technique's options (all options of a technique share one tier in practice). */
    public static int tier(String technique, List<Unit> cands) {
        int t = Option.R4;
        for (Unit u : cands) { Option o = optionFor(u, technique); if (o != null) t = Math.min(t, o.risk); }
        return t;
    }
}
