package com.storageplanner.v2.content;

import com.storageplanner.v2.model.EvidenceFamily;
import com.storageplanner.v2.risk.FileContext;
import com.storageplanner.v2.risk.Keywords;

/** Deterministic name/path keyword classifier. It reads the path, so its output is PROV, never CONTENT (P4-8). */
public final class RuleClassifier implements ContentClassifier {
    public String id() { return "rules"; }
    public int version() { return 1; }
    public EvidenceFamily family() { return EvidenceFamily.PROV; }
    public double reliability() { return 0.6; }
    public Classification classify(FileContext ctx) {
        String cat = Keywords.recordCategory(ctx.f.path);
        if (cat == null) return null;
        Classification c = new Classification();
        c.category = cat; c.confidence = reliability(); c.source = "RULES"; c.evidence.add("path:" + Keywords.hit(ctx.f.path));
        return c;
    }
}
