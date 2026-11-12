package com.storageplanner.v2.model;

import java.util.ArrayList;
import java.util.List;

/** Table duplicate_groups (ARCHITECTURE 2.7, PHASE_3 2.4). */
public final class DuplicateGroup implements Record {
    public String groupId;
    public boolean exact;
    public List<String> members = new ArrayList<String>();
    public String keeperId;
    public List<String> keeperReasons = new ArrayList<String>();
    public long memberBytes = Unknown.LONG, recoverableBytes = Unknown.LONG;
    public long createdScanId = Unknown.LONG;
    public Verification verification = Verification.UNVERIFIED;
    public NearGroupKind kind;
    public boolean keeperFallback;
    public String key() { return groupId; }
    public DuplicateGroup copy() {
        DuplicateGroup r = new DuplicateGroup(); r.groupId = groupId; r.exact = exact;
        r.members = members == null ? null : new ArrayList<String>(members); r.keeperId = keeperId;
        r.keeperReasons = keeperReasons == null ? null : new ArrayList<String>(keeperReasons);
        r.memberBytes = memberBytes; r.recoverableBytes = recoverableBytes; r.createdScanId = createdScanId;
        r.verification = verification; r.kind = kind; r.keeperFallback = keeperFallback;
        return r;
    }
}
