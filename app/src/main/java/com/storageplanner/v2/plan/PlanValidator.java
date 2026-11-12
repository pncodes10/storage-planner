package com.storageplanner.v2.plan;

import com.storageplanner.v2.model.*;

import java.util.*;

/**
 * PHASE_5 3: re-checks a selected plan. Any violation is a bug: the item is dropped, counted in validatorDropped and reported with
 * PL_VALIDATOR_DROPPED; tests fail on any non-zero count. Checks: tier within {SAFE, NO_LOSS, YOUR_CALL} and the request's maxTier;
 * no fileId twice; no keeper removed; no removal whose required file is removed or transformed; no hashPending delete; the rec still
 * exists with the same tier/action (its hardBlocks list blocks of other candidates and is not re-checked, PHASE_5_NOTES D-4); achievableBytes == sum of SAFE/NO_LOSS items.
 */
public final class PlanValidator {
    private PlanValidator() {}

    public static List<String> validate(ActionPlan p, Map<String, Recommendation> recs, Set<String> keepers) {
        List<String> problems = new ArrayList<String>();
        Set<String> seen = new HashSet<String>(), removed = new HashSet<String>(), touched = new HashSet<String>();
        for (ActionPlan.PlanItem it : p.items) {
            boolean rem = it.action == Action.DELETE || it.action == Action.QUARANTINE;
            for (String f : it.fileIds) { if (rem) removed.add(f); touched.add(f); }
        }
        Iterator<ActionPlan.PlanItem> i = p.items.iterator();
        while (i.hasNext()) {
            ActionPlan.PlanItem it = i.next();
            String bad = null;
            Recommendation r = recs == null ? null : recs.get(it.recId);
            boolean rem = it.action == Action.DELETE || it.action == Action.QUARANTINE;
            if (!UnitBuilder2.plannableTier(it.tier)) bad = "tier " + it.tier;
            else if (Tier.rank(it.tier) > Tier.rank(p.maxTier)) bad = "tier above request";
            else if (recs != null && (r == null || r.tier != it.tier || r.proposedAction != it.action)) bad = "rec missing or changed";
            else if (r != null && it.action == Action.DELETE && UnitBuilder2.hashPending(r)) bad = "hash pending";
            if (bad == null) for (String f : it.fileIds) {
                if (!seen.add(f)) { bad = "file twice " + f; break; }
                if (rem && keepers != null && keepers.contains(f)) { bad = "keeper removed " + f; break; }
            }
            if (bad == null && (rem || it.action == Action.UNINSTALL_APP))
                for (String q : it.requires) if (removed.contains(q) || touched.contains(q)) { bad = "required file in plan " + q; break; }
            if (bad != null) { problems.add(it.unitId + ": " + bad); i.remove(); p.validatorDropped++; }
        }
        long ach = 0, exact = 0, all = 0;
        for (ActionPlan.PlanItem it : p.items) { all += it.bytes; if (it.tier != Tier.YOUR_CALL) { ach += it.bytes; if (it.exact) exact += it.bytes; } }
        if (p.validatorDropped > 0) {
            p.achievableBytes = ach; p.achievableExactBytes = exact; p.withYourCallBytes = all;
            if (!p.reasonCodes.contains("PL_VALIDATOR_DROPPED")) p.reasonCodes.add("PL_VALIDATOR_DROPPED");
            p.reasonParams.put("dropped", String.valueOf(p.validatorDropped));
        } else if (ach != p.achievableBytes || all != p.withYourCallBytes || exact != p.achievableExactBytes) problems.add("byte totals differ from items");
        return problems;
    }
}
