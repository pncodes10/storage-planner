package com.storageplanner.v2.features;

import com.storageplanner.v2.model.*;
import com.storageplanner.v2.store.V2Store;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/** CoverageReport.extraction (PHASE_2 9): tier and status histograms over PRESENT files, hash and archive counters. */
public final class ExtractionCoverage {
    private ExtractionCoverage() {}

    static void inc(Map<String, Object> m, String k) { Object v = m.get(k); m.put(k, v == null ? 1L : (Long) v + 1); }

    public static Map<String, Object> build(V2Store store, ScanSession s) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        Map<String, Object> tiers = new LinkedHashMap<String, Object>();
        long[][] t = new long[4][4];
        Map<String, Object> status = new TreeMap<String, Object>(), types = new TreeMap<String, Object>(), src = new TreeMap<String, Object>(),
                mism = new TreeMap<String, Object>(), integ = new TreeMap<String, Object>(), dec = new TreeMap<String, Object>(),
                undec = new TreeMap<String, Object>();
        for (ExtractionStatus e : ExtractionStatus.values()) status.put(e.name(), 0L);
        long listed = 0, truncated = 0, bomb = 0, trav = 0, enc = 0;
        for (FileRecord f : store.files().all()) {
            if (f.presence != Presence.PRESENT) continue;
            for (int k = 1; k <= 3; k++) {
                int b = 1 << k;
                boolean d = (f.tiersDone & b) != 0, fl = (f.tiersFailed & b) != 0, p = (f.tiersPending & b) != 0;
                if (d || fl || p) t[k][0]++;
                if (d) t[k][1]++; if (fl) t[k][2]++; if (p) t[k][3]++;
            }
            inc(status, f.extractionStatus.name()); inc(types, f.type); inc(src, f.typeSource.name());
            if (f.extMismatch == ExtMismatch.BENIGN || f.extMismatch == ExtMismatch.SUSPICIOUS) inc(mism, f.extMismatch.name());
            inc(integ, f.integrity.name());
            FeatureRecord fr = store.features().get(f.fileId);
            if (fr == null) continue;
            if (fr.image != null && fr.image.decodeStatus != null) {
                inc(dec, fr.image.decodeStatus.name());
                if (fr.image.decodeStatus != DecodeStatus.OK) inc(undec, f.type);
            }
            if (fr.archive != null) {
                listed++;
                if ("TRUNCATED".equals(fr.archive.listingStatus) || "LIMIT".equals(fr.archive.listingStatus)) truncated++;
                if (fr.archive.bombSuspect == Tri.TRUE) bomb++;
                if (fr.archive.pathTraversal == Tri.TRUE || fr.archive.absolutePaths == Tri.TRUE) trav++;
                if (fr.archive.encryptedEntries == Tri.TRUE) enc++;
            }
        }
        for (int k = 1; k <= 3; k++) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("applicable", t[k][0]); m.put("done", t[k][1]); m.put("failed", t[k][2]); m.put("pending", t[k][3]);
            tiers.put(Integer.toString(k), m);
        }
        out.put("tiers", tiers); out.put("status", status); out.put("types", types); out.put("typeSource", src); out.put("extMismatch", mism);
        out.put("integrity", integ); out.put("decodeStatus", dec); out.put("undecodableByType", undec);
        Map<String, Object> h = new LinkedHashMap<String, Object>();
        h.put("policy", s.counters.containsKey("hash.policyAll") ? "all" : "collisions");
        for (String k : new String[]{"sizeGroups", "quickHashed", "fullHashed", "reused", "bytesRead", "failed"}) h.put(k, s.counter("hash." + k));
        out.put("hash", h);
        Map<String, Object> a = new LinkedHashMap<String, Object>();
        a.put("listed", listed); a.put("truncated", truncated); a.put("bombSuspect", bomb); a.put("pathTraversal", trav); a.put("encrypted", enc);
        out.put("archives", a);
        Map<String, Object> b = new LinkedHashMap<String, Object>();
        String by = null;
        for (String k : s.counters.keySet()) if (k.startsWith("budget.stoppedBy.")) by = k.substring("budget.stoppedBy.".length());
        b.put("stoppedBy", by); b.put("pendingFiles", s.counter("budget.pendingFiles"));
        out.put("budget", b);
        out.put("extractedThisScan", s.counter("T1.extracted") + s.counter("T2.extracted") + s.counter("T3.extracted"));
        out.put("reusedThisScan", s.counter("T1.reused"));
        return out;
    }
}
