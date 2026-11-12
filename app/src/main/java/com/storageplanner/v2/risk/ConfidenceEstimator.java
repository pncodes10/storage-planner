package com.storageplanner.v2.risk;

import com.storageplanner.v2.model.*;

import java.util.*;

/** C = min(c_extract, c_graph, c_class, c_indep, c_avail), capped at 0.4 on contradiction (PHASE_4 6, REVIEW_2 F-07). */
public final class ConfidenceEstimator {
    private ConfidenceEstimator() {}
    static final EnumSet<EvidenceFamily> STRUCTURAL_FAMS = EnumSet.of(EvidenceFamily.STRUCT, EvidenceFamily.GRAPH, EvidenceFamily.APP);

    /** Confidence of a candidate (c == null: of the assessment without any decision). Fills parts when given. */
    public static double of(FileContext ctx, Assessment a, Candidate c, Map<String, Double> parts) {
        boolean structural = c != null && c.structural;
        double ex = ctx.f.extractionConfidence;
        double cExtract = structural ? 1.0 : Double.isNaN(ex) ? 0.3 : ex;
        double cGraph = 1.0;
        if (c != null && c.citesUnverified) cGraph = 0.7;
        if (c != null && c.proposed == Tier.PROTECTED && Types.image(ctx.f.type) && "UNKNOWN".equals(ctx.perceptualStatus())) cGraph = 0.5;
        double cClass = 1.0;
        if (c != null && c.params.containsKey("classifier")) cClass = ctx.classification == null ? 1.0 : ctx.classification.confidence;
        double cIndep = 1.0;
        if (c != null && !(STRUCTURAL_FAMS.containsAll(c.families) && !c.citesUnverified)) {
            int n = c.families.size();
            cIndep = n <= 1 ? 0.6 : n == 2 ? 0.8 : 0.9;
        }
        int k = (a.anyUnknown ? 2 : 0) + (a.recoverability == Recoverability.UNKNOWN ? 1 : 0);
        double cAvail = structural ? 1.0 : Math.max(0.4, 1 - 0.15 * k);
        double cc = Math.min(Math.min(cExtract, cGraph), Math.min(cClass, Math.min(cIndep, cAvail)));
        if (a.contradiction && !structural) cc = Math.min(cc, 0.4);
        if (parts != null) { parts.put("c_extract", cExtract); parts.put("c_graph", cGraph); parts.put("c_class", cClass); parts.put("c_indep", cIndep); parts.put("c_avail", cAvail); }
        return cc;
    }
}
