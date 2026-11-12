package com.storageplanner.v2.graph;

import com.storageplanner.v2.model.*;
import com.storageplanner.v2.util.Hashing;

/** Edge construction (ARCHITECTURE 2.6). */
final class Edges {
    private Edges() {}
    static boolean symmetric(RelType t) { return t == RelType.EXACT_DUPLICATE || t == RelType.VISUALLY_SIMILAR || t == RelType.SEMANTICALLY_SIMILAR; }

    static Relationship make(String src, String dst, RelType t, double conf, Verification v, Fingerprint sFp, Fingerprint dFp, long scanId) {
        if (symmetric(t) && src.compareTo(dst) > 0) { String x = src; src = dst; dst = x; Fingerprint y = sFp; sFp = dFp; dFp = y; }
        Relationship r = new Relationship();
        r.src = src; r.dst = dst; r.type = t;
        r.edgeId = Hashing.sha256Hex(src + "\u0000" + t.name() + "\u0000" + dst).substring(0, 32);
        r.confidence = Math.round(conf * 1e6) / 1e6; r.verification = v; r.srcFp = sFp; r.dstFp = dFp; r.createdScanId = scanId;
        return r;
    }
    static void ev(Relationship r, String code, Object value) {
        if (!GraphConfig.EVIDENCE_CODES.contains(code)) throw new IllegalArgumentException("evidence code " + code);
        String v = value == null ? null : value instanceof Double ? com.storageplanner.v2.util.JsonWriter.formatDouble((Double) value) : String.valueOf(value);
        r.evidence.add(new Evidence(code, v, GraphConfig.STRUCTURAL.contains(code)));
    }
}
