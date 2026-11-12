package com.storageplanner.v2.plan;

import com.storageplanner.v2.model.*;
import com.storageplanner.v2.store.V2Store;

import java.util.*;

/**
 * PHASE_5 PLAN step (called from the CLI after ASSESS, like Phase 4's Assessor; PHASE_5_NOTES D-1): units -> plan -> validator ->
 * fingerprints; versioning (planVersion = previous + 1, planId "p" + version, last 3 kept).
 */
public final class Plans {
    private Plans() {}
    public static final int KEEP_PLANS = 3;

    public static final class Made { public ActionPlan plan; public UnitBuilder2.Built built; public List<String> problems = new ArrayList<String>(); }

    public static Made make(UnitBuilder2.Input in, long target, Tier maxTier) {
        Made m = new Made();
        m.built = UnitBuilder2.build(in);
        m.plan = Planner2.plan(m.built.units, target, maxTier);
        m.plan.hashPending = m.built.hashPending;
        if (m.built.hashPending > 0) { m.plan.reasonCodes.add("PL_HASH_PENDING"); m.plan.reasonParams.put("pending", String.valueOf(m.built.hashPending)); }
        m.problems.addAll(PlanValidator.validate(m.plan, m.built.recById, in.keepers));
        PlanStaleness.stamp(m.plan, in.files, m.built.recById);
        return m;
    }
    /** Keepers of every exact / content group in the store (never removed). */
    public static Set<String> keepers(V2Store store) {
        Set<String> k = new HashSet<String>();
        for (DuplicateGroup g : store.duplicateGroups().all()) if (g.keeperId != null) k.add(g.keeperId);
        return k;
    }
    /** Assigns the next version and keeps the last {@link #KEEP_PLANS} plans in the store table. */
    public static void version(V2Store store, ActionPlan p, long now, long scanId) {
        int max = 0; List<String> ids = new ArrayList<String>();
        for (ActionPlan x : store.actionPlans().all()) { max = Math.max(max, ActionPlan.versionOf(x.planId)); ids.add(x.planId); }
        p.planVersion = max + 1; p.planId = "p" + p.planVersion; p.createdAt = now; p.scanId = scanId; p.state = "DRAFT";
        store.actionPlans().put(p);
        for (String id : ids) if (ActionPlan.versionOf(id) <= p.planVersion - KEEP_PLANS) store.actionPlans().remove(id);
    }
}
