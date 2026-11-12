package com.storageplanner.v2.plan;

import com.storageplanner.v2.model.*;
import com.storageplanner.v2.risk.*;

import java.util.*;

/**
 * PHASE_4 9.3: deterministic first-match decision table. Tiers are decided by the kind of evidence (structural, verified, plausible);
 * scores only gate and rank YOUR_CALL. Total: every input yields exactly one verdict (KEEP = tier null).
 */
public final class TierAssigner {
    private TierAssigner() {}

    static boolean passes(Candidate c) { return c.blocks.isEmpty(); }
    static boolean is(Candidate c, String prefix) { return c.reasonCode.startsWith(prefix); }

    public static Verdict assign(FileContext ctx, Assessment a, List<Candidate> cands, Constraint k, PresentationPolicy pol) {
        Verdict v = new Verdict(); v.fileId = ctx.f.fileId; v.assessment = a;
        if (k.noVerdict) { v.row = 1; return v; }                                                                 // row 1 (HC2)
        for (Candidate c : cands) for (String b : c.blocks) if (!v.hardBlocks.contains(b) && b.startsWith("H_")) v.hardBlocks.add(b);
        for (String b : k.blocks) if (!v.hardBlocks.contains(b) && b.startsWith("H_")) v.hardBlocks.add(b);
        if (k.forcedTier == Tier.PROTECTED) return forced(v, 2, Tier.PROTECTED, k.forcedReason, k.forcedParams);   // row 2
        for (Candidate c : cands)                                                                                 // row 3
            if (is(c, "S_") && c.action.removal() && passes(c)) return choose(v, 3, c);
        for (Candidate c : cands)                                                                                 // row 4 (+HC15)
            if (is(c, "N_") && c.action.removal() && passes(c) && c.confidence >= RiskConfig.NO_LOSS_MIN_CONFIDENCE) return choose(v, 4, c);
        if (k.forcedTier == Tier.REVIEW) return forced(v, 5, Tier.REVIEW, k.forcedReason, k.forcedParams);       // row 5
        for (Candidate c : cands) if (is(c, "P_") && passes(c)) return choose(v, 6, c);                          // row 6
        for (Candidate c : cands)                                                                                 // row 7
            if (a.contradiction && c.action.removal() && !c.structural && passes(c)) {
                Map<String, String> p = new TreeMap<String, String>();
                p.put("a", ReasonCatalog.render(c.reasonCode, c.params)); p.put("b", a.contradictionB);
                v.evidenceRefs.addAll(c.evidenceRefs);
                return forced(v, 7, Tier.REVIEW, "R_CONTRADICTORY", p);
            }
        Candidate best = null; double bu = 0; boolean failedY = false; Candidate firstFailed = null;
        for (Candidate c : cands) {                                                                               // row 8 (+HC15)
            if (!is(c, "Y_") || !c.action.removal()) continue;
            Double el = a.elPess.get(c.action), ut = a.utility.get(c.action);
            // A25: eligibility by the kind of evidence (candidate code + loss/confidence gates), never by a size floor;
            // utility only ranks YOUR_CALL candidates here and orders plan inclusion in Planner2.
            boolean ok = passes(c) && el != null && el <= pol.tYourCall && c.confidence >= pol.cYourCall && ut != null;
            if (ok && (best == null || ut > bu)) { best = c; bu = ut; }
            if (!ok && passes(c)) { failedY = true; if (firstFailed == null) firstFailed = c; }
        }
        Verdict out = null;
        if (best != null) out = choose(v, 8, best);
        if (out == null) for (Candidate c : cands)                                                                // row 9
            if (!passes(c) && c.fallbackTier == Tier.REVIEW && c.fallbackCode != null) {
                Map<String, String> p = new TreeMap<String, String>(c.params); v.evidenceRefs.addAll(c.evidenceRefs);
                out = forced(v, 9, Tier.REVIEW, c.fallbackCode, p); break;
            }
        if (out == null && failedY && pol.showFailedYourCallAsReview) {
            Map<String, String> p = new TreeMap<String, String>(firstFailed.params);
            if (!p.containsKey("other")) p.put("other", ctx.f.name);
            v.evidenceRefs.addAll(firstFailed.evidenceRefs);
            out = forced(v, 9, Tier.REVIEW, "R_UNCERTAIN_COPY", p);
        }
        if (out == null) for (Candidate c : cands)                                                                // row 10
            if (is(c, "R_") && c.action == Action.REVIEW && passes(c)) { out = choose(v, 10, c); break; }
        if (out == null) { v.row = 11; out = v; }                                                                 // row 11 KEEP
        for (Candidate c : cands)                                                                                 // row T
            if (c.reasonCode.equals("N_LOSSLESS_SMALLER") && passes(c) && a.lossRange.high < RiskConfig.P_RECORD_MIN_LOSS_HIGH) { out.transform = c; break; }
        return out;
    }
    static Verdict choose(Verdict v, int row, Candidate c) {
        v.row = row; v.tier = c.proposed; v.action = c.action == Action.KEEP && c.proposed == Tier.PROTECTED ? Action.KEEP : c.action; v.chosen = c;
        v.reasonCodes.add(c.reasonCode); v.reasonCodes.addAll(c.secondaryCodes);
        v.params.putAll(c.params); v.evidenceRefs.addAll(c.evidenceRefs); v.dependencyIds.addAll(c.dependencyIds);
        return v;
    }
    static Verdict forced(Verdict v, int row, Tier t, String code, Map<String, String> params) {
        v.row = row; v.tier = t; v.action = t == Tier.PROTECTED ? Action.KEEP : Action.REVIEW;
        v.reasonCodes.add(code); v.params.putAll(params);
        return v;
    }
}
