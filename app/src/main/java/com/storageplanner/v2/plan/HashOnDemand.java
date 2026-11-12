package com.storageplanner.v2.plan;

import com.storageplanner.v2.model.*;

import java.util.*;

/**
 * PHASE_5 2 rule 2 (ARCHITECTURE V3): every DELETE rec needs a full SHA-256 of its file before it is plannable. Missing hashes are
 * computed in plan-priority order, then bytes descending, within a byte budget; recs still missing one stay {@code hashPending}
 * (counted under PL_HASH_PENDING). The hash goes into evidenceRefs as "sha256:<hex>".
 */
public final class HashOnDemand {
    private HashOnDemand() {}
    public interface Hasher { /** lower-hex SHA-256 of the file's current content, null if it cannot be read. */ String sha256(FileRecord f); }
    public static final class Result { public int hashed, pending, failed; public long bytesRead; }

    public static Result run(List<Recommendation> recs, Map<String, FileRecord> files, Hasher h, long budgetBytes) {
        Result res = new Result();
        List<Recommendation> todo = new ArrayList<Recommendation>();
        for (Recommendation r : recs) {
            if (r.proposedAction != Action.DELETE) continue;
            FileRecord f = files.get(r.fileId);
            if (f != null && f.sha256 != null && !UnitBuilder2.hashPending(r)) { evidence(r, f.sha256); continue; }
            todo.add(r);
        }
        Collections.sort(todo, new Comparator<Recommendation>() {
            public int compare(Recommendation a, Recommendation b) {
                int c = Integer.compare(Tier.rank(a.tier), Tier.rank(b.tier)); if (c != 0) return c;
                c = Integer.compare(PlanPriority.of(a.proposedAction, a.reasonCodes.isEmpty() ? null : a.reasonCodes.get(0)),
                        PlanPriority.of(b.proposedAction, b.reasonCodes.isEmpty() ? null : b.reasonCodes.get(0))); if (c != 0) return c;
                c = Long.compare(b.expectedSavingBytes, a.expectedSavingBytes); if (c != 0) return c;
                return a.recommendationId.compareTo(b.recommendationId);
            }
        });
        for (Recommendation r : todo) {
            FileRecord f = files.get(r.fileId);
            long sz = f == null ? -1 : Math.max(0, f.sizeBytes);
            String hex = null;
            if (f != null && h != null && res.bytesRead + sz <= budgetBytes) { hex = h.sha256(f); res.bytesRead += sz; if (hex == null) res.failed++; }
            if (hex == null) { r.reasonParams.put("hashPending", "true"); r.reasonParams.put("hash", "pending"); res.pending++; continue; }
            if (f.sha256 != null && !f.sha256.equals(hex)) { r.reasonParams.put("hashPending", "true"); r.reasonParams.put("hash", "changed"); res.pending++; continue; }
            r.reasonParams.remove("hashPending"); r.reasonParams.remove("hash");
            evidence(r, hex); res.hashed++;
        }
        return res;
    }
    static void evidence(Recommendation r, String hex) { String e = "sha256:" + hex; if (!r.evidenceRefs.contains(e)) r.evidenceRefs.add(e); }
}
