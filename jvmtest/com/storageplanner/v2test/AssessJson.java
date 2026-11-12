package com.storageplanner.v2test;

import com.storageplanner.v2.model.*;
import com.storageplanner.v2.util.JsonWriter;

import java.util.*;

/** Writes files[].assessment and the provisional recommendations (PHASE_4 0, 16). */
final class AssessJson {
    private AssessJson() {}
    static Map<String, Verdict> verdicts;
    static List<Recommendation> recs;
    static String mode;

    static void interval(JsonWriter w, String n, Interval i) {
        w.name(n);
        if (i == null || i.unknown()) w.nullValue(); else w.beginArray().value(r(i.low)).value(r(i.mid)).value(r(i.high)).endArray();
    }
    static double r(double v) { return Math.round(v * 1e6) / 1e6; }
    static void amap(JsonWriter w, String n, EnumMap<Action, Double> m) {
        w.name(n).beginObject(); for (Map.Entry<Action, Double> e : m.entrySet()) w.name(e.getKey().name()).value(r(e.getValue())); w.endObject();
    }
    static void assessment(JsonWriter w, String fileId) {
        Verdict v = verdicts == null ? null : verdicts.get(fileId);
        if (v == null || v.row == 1) { w.nullValue(); return; }
        Assessment a = v.assessment;
        w.beginObject().name("usage").value(a.usage);
        interval(w, "u30", a.u30); interval(w, "u90", a.u90); interval(w, "u365", a.u365); interval(w, "u1095", a.u1095);
        interval(w, "loss", a.lossRange);
        w.name("recoverability").value(a.recoverability).name("recoveryKind").value(a.recoveryKind).name("recoveryEvidence").strings(a.recoveryEvidence)
         .name("rvMid").value(a.rvMid).name("rvPess").value(a.rvPess).name("confidence").value(r(a.confidence));
        w.name("confidenceParts").beginObject(); for (Map.Entry<String, Double> e : a.confidenceParts.entrySet()) w.name(e.getKey()).value(r(e.getValue())); w.endObject();
        amap(w, "irreversibility", a.irreversibility); amap(w, "elMid", a.elMid); amap(w, "elPess", a.elPess); amap(w, "utility", a.utility);
        w.name("benefit").beginObject(); for (Map.Entry<Action, Long> e : a.benefit.entrySet()) w.name(e.getKey().name()).valueCount(e.getValue()); w.endObject();
        w.name("contradiction").value(a.contradiction).name("familiesFor").strings(a.familiesFor).name("familiesAgainst").strings(a.familiesAgainst)
         .name("hardBlocks").strings(a.hardBlocks).name("keeperOf").strings(a.keeperOf).name("keeperReasons").strings(a.keeperReasons)
         .name("anyUnknown").value(a.anyUnknown)
         .name("tier").value(v.tier).name("action").value(v.action).name("row").value(v.row).name("reasonCodes").strings(v.reasonCodes)
         .endObject();
    }
    static void recommendations(JsonWriter w) {
        w.beginArray();
        if (recs != null) for (Recommendation x : recs) {
            w.beginObject().name("recommendationId").value(x.recommendationId).name("fileId").value(x.fileId).name("tier").value(x.tier)
             .name("action").value(x.proposedAction).name("expectedSavingBytes").valueCount(x.expectedSavingBytes)
             .name("expectedLoss").value(x.expectedLoss).name("uncertainty").value(x.uncertainty).name("confidence").value(r(x.confidence))
             .name("reasonCodes").strings(x.reasonCodes).name("reasonParams").beginObject();
            for (Map.Entry<String, String> e : x.reasonParams.entrySet()) w.name(e.getKey()).value(e.getValue());
            w.endObject().name("reasons").beginArray();
            for (String c : x.reasonCodes) w.value(com.storageplanner.v2.plan.ReasonCatalog.render(c, x.reasonParams));
            w.endArray().name("evidenceRefs").strings(x.evidenceRefs).name("dependencyIds").strings(x.dependencyIds).name("hardBlocks").strings(x.hardBlocks)
             .name("planVersion").value(x.planVersion).name("provisional").value(true).endObject();
        }
        w.endArray();
    }
}
