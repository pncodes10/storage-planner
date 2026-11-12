package com.storageplanner.v2.risk;

import com.storageplanner.v2.device.InstalledApps;
import com.storageplanner.v2.model.*;
import com.storageplanner.v2.plan.PresentationPolicy;
import com.storageplanner.v2.plan.TierAssigner;
import com.storageplanner.v2.store.StoreException;
import com.storageplanner.v2.store.V2Store;

import java.util.*;

/**
 * Stage ASSESS (PHASE_4 2): identity, hard rules (before any score), relationships, signals, U, L, R, candidates, C/I/B/EL/utility,
 * tier. A pure function of the store, the clock, the installed apps and the policy.
 */
public final class Assessor {
    private Assessor() {}

    /** Assess one prepared context. */
    public static Verdict assess(FileContext ctx, PresentationPolicy pol) {
        Constraint k = HardRules.constraints(ctx);                              // stage 2: before any score
        Assessment a = new Assessment(); a.fileId = ctx.f.fileId; a.protection = ctx.f.protection; a.usage = ctx.usage;
        if (k.noVerdict) { Verdict v = new Verdict(); v.fileId = ctx.f.fileId; v.row = 1; v.assessment = a; return v; }
        a.anyUnknown = ctx.usage == UsageClass.UNKNOWN || HardRules.featuresUnknown(ctx);
        for (DuplicateGroup d : ctx.keeperOf) { a.keeperOf.add(d.groupId); for (String r : d.keeperReasons) if (!a.keeperReasons.contains(r)) a.keeperReasons.add(r); }
        UtilityEstimator.estimate(ctx, a);                                      // stage 5
        LossEstimator.estimate(ctx, a);                                         // stage 6
        RecoverabilityEstimator.estimate(ctx, a);
        IrreversibilityEstimator.estimate(a);
        BenefitEstimator.estimate(ctx, a);
        ExpectedLoss.estimate(ctx, a);
        List<Candidate> cands = CandidateRules.generate(ctx, a, pol);           // stage 7
        EnumSet<EvidenceFamily> fFor = EnumSet.noneOf(EvidenceFamily.class), fAgainst = EnumSet.noneOf(EvidenceFamily.class);
        for (Candidate c : cands) if (c.action.removal()) fFor.addAll(c.families);
        for (Signal s : ctx.signals) if (s.direction > 0) fAgainst.add(s.family);
        for (EvidenceFamily x : fFor) a.familiesFor.add(x.name());
        for (EvidenceFamily x : fAgainst) a.familiesAgainst.add(x.name());
        // contradiction (spec 8.4): non-structural removals only; a record/sentimental keyword contradicts even a PROV-supported candidate
        // (PHASE_4 6 example "Y_CHAT_COPY but the name says invoice", although Y_CHAT_COPY itself carries PROV)
        for (Candidate c : cands) {
            if (!c.action.removal() || c.structural) continue;
            for (Signal s : ctx.signals)
                if (s.direction > 0 && s.strength >= RiskConfig.CONTRADICTION_MIN_STRENGTH && (!c.families.contains(s.family) || s.code.startsWith("PATH_KEYWORD"))) {
                    a.contradiction = true; a.contradictionA = c.reasonCode;
                    a.contradictionB = "the " + (s.family == EvidenceFamily.PROV ? "name or folder" : s.family.name().toLowerCase(Locale.ROOT)) + " suggests it matters (" + s.ref + ")";
                }
        }
        for (Candidate c : cands) {                                             // stage 8
            c.confidence = ConfidenceEstimator.of(ctx, a, c, null);
            c.blocks.addAll(HardRules.check(ctx, a, c));
        }
        Verdict v = TierAssigner.assign(ctx, a, cands, k, pol);                 // stage 9
        a.confidence = ConfidenceEstimator.of(ctx, a, v.chosen, a.confidenceParts);
        a.hardBlocks.addAll(v.hardBlocks);
        return v;
    }

    /** Assesses every PRESENT file of the store; copies U/L/R/C into FileRecord on change (commit every 500). */
    public static List<Verdict> run(V2Store store, InstalledApps apps, long now, PresentationPolicy pol, RiskConfig cfg) throws StoreException {
        ContextBuilder b = new ContextBuilder(store, apps, now, cfg);
        List<Verdict> out = new ArrayList<Verdict>();
        int dirty = 0;
        for (FileRecord f : store.files().all()) {
            if (f.presence != Presence.PRESENT) continue;
            FileContext ctx = b.build(f);
            Verdict v = assess(ctx, pol);
            out.add(v);
            Assessment a = v.assessment;
            if (v.row == 1) continue;
            double u = a.u90.mid, l = a.lossRange.mid, c = a.confidence;
            if (!same(f.utility, u) || !same(f.lossSeverity, l) || f.recoverability != a.recoverability || !same(f.confidence, c)) {
                FileRecord w = f.copy(); w.utility = u; w.lossSeverity = l; w.recoverability = a.recoverability; w.confidence = c;
                store.files().put(w);
                if (++dirty % 500 == 0) store.commit();
            }
        }
        store.commit();
        return out;
    }
    static boolean same(double x, double y) { return Double.isNaN(x) ? Double.isNaN(y) : x == y; }
}
