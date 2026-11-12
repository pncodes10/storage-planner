package com.storageplanner.v2.model;

import java.util.ArrayList;
import java.util.List;

/** Table file_relationships (ARCHITECTURE 2.6). "src TYPE dst" reads as a sentence; symmetric types have src < dst. */
public final class Relationship implements Record {
    public String edgeId, src, dst;
    public RelType type;
    public double confidence;
    public List<Evidence> evidence = new ArrayList<Evidence>();
    public Verification verification = Verification.UNVERIFIED;
    public Fingerprint srcFp, dstFp;
    public long createdScanId = Unknown.LONG, verifiedAt = Unknown.TIME;
    public String key() { return edgeId; }
    public Relationship copy() {
        Relationship r = new Relationship(); r.edgeId = edgeId; r.src = src; r.dst = dst; r.type = type; r.confidence = confidence;
        r.evidence = evidence == null ? null : new ArrayList<Evidence>(evidence); r.verification = verification;
        r.srcFp = srcFp; r.dstFp = dstFp; r.createdScanId = createdScanId; r.verifiedAt = verifiedAt;
        return r;
    }
}
