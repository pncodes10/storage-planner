package com.storageplanner.v2test;

import com.storageplanner.v2.model.*;
import com.storageplanner.v2.plan.*;

import java.util.*;

/** Synthetic Phase 5 inputs: recommendations and file records built directly. */
final class PlanFx {
    final UnitBuilder2.Input in = new UnitBuilder2.Input();
    int n;
    FileRecord file(String path, long size) {
        FileRecord f = new FileRecord(); f.fileId = String.format(Locale.ROOT, "f%04d", n++); f.path = path; f.sizeBytes = size;
        f.modifiedAt = 1700000000000L; f.inode = n; f.presence = Presence.PRESENT; f.sha256 = "sha-" + f.fileId;
        in.files.put(f.fileId, f); return f;
    }
    Recommendation rec(String subject, Tier t, Action a, String code, long bytes, double el, String... deps) {
        Recommendation r = new Recommendation(); r.fileId = subject; r.tier = t; r.proposedAction = a; r.reasonCodes.add(code);
        r.expectedSavingBytes = bytes; r.expectedLoss = el; r.uncertainty = el; r.confidence = 1;
        r.recommendationId = subject + "/" + a; r.dependencyIds.addAll(Arrays.asList(deps));
        in.recs.add(r); return r;
    }
    Recommendation del(FileRecord f, Tier t, String code, double el, String... deps) { return rec(f.fileId, t, Action.DELETE, code, f.sizeBytes, el, deps); }
    ActionPlan plan(long target) { return Plans.make(in, target, null).plan; }
    /** An explicit max of YOUR_CALL is the request's opt-in to that tier (A29). */
    ActionPlan plan(long target, Tier max) { return Plans.make(in, target, max, max == Tier.YOUR_CALL).plan; }
    ActionPlan planYourCall(long target) { return Plans.make(in, target, null, true).plan; }
    static Set<String> files(ActionPlan p) { Set<String> s = new TreeSet<String>(); for (ActionPlan.PlanItem i : p.items) s.addAll(i.fileIds); return s; }
    static boolean has(ActionPlan p, String unitId) { for (ActionPlan.PlanItem i : p.items) if (i.unitId.equals(unitId)) return true; return false; }
    static long tierBytes(ActionPlan p, Tier t) { long b = 0; for (ActionPlan.PlanItem i : p.items) if (i.tier == t) b += i.bytes; return b; }
}
