package com.storageplanner.v2.plan;

import com.storageplanner.v2.model.*;
import com.storageplanner.v2.util.Hashing;

import java.util.*;

/** Phase 4: one provisional Recommendation per non-KEEP verdict (and per row-T transform). Phase 5's planner replaces this. */
public final class ProvisionalRecommendations {
    private ProvisionalRecommendations() {}
    public static final int PLAN_VERSION = 0;

    public static List<Recommendation> of(List<Verdict> verdicts, Map<String, FileRecord> files, long scanId) {
        List<Recommendation> out = new ArrayList<Recommendation>();
        for (Verdict v : verdicts) {
            FileRecord f = files.get(v.fileId);
            if (v.tier != null) out.add(rec(v, f, v.tier, v.action, v.reasonCodes, v.params, v.evidenceRefs, v.dependencyIds, scanId));
            if (v.transform != null) {
                Candidate t = v.transform;
                out.add(rec(v, f, t.proposed, t.action, Collections.singletonList(t.reasonCode), t.params, t.evidenceRefs, t.dependencyIds, scanId));
            }
        }
        return out;
    }
    static Recommendation rec(Verdict v, FileRecord f, Tier tier, Action act, List<String> codes, Map<String, String> params, List<String> ev,
                              List<String> deps, long scanId) {
        Assessment a = v.assessment;
        Recommendation r = new Recommendation();
        r.fileId = v.fileId; r.tier = tier; r.proposedAction = act;
        r.recommendationId = Hashing.sha256Hex(v.fileId + act.name() + PLAN_VERSION).substring(0, 24);
        r.reasonCodes.addAll(codes); r.reasonParams.putAll(params); r.evidenceRefs.addAll(ev); r.dependencyIds.addAll(deps);
        r.hardBlocks.addAll(v.hardBlocks);
        if (act == Action.DELETE && f != null && f.sha256 == null) r.reasonParams.put("hashPending", "true");   // Phase 5 hashes on demand (V3)
        Long b = a.benefit.get(act); r.expectedSavingBytes = b == null ? (act.removal() ? Unknown.LONG : 0) : b;
        Double m = a.elMid.get(act), p = a.elPess.get(act);
        r.expectedLoss = m == null ? Double.NaN : m; r.uncertainty = m == null || p == null ? Double.NaN : p - m;
        r.confidence = v.chosen != null ? v.chosen.confidence : a.confidence;
        r.planVersion = PLAN_VERSION; r.fp = f == null ? null : f.fingerprint(); r.createdScanId = scanId;
        return r;
    }
}
