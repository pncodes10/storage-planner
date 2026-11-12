package com.storageplanner.v2.model;

/** Table pair_checks (PHASE_3 2.1). Key = pairKey (covers both fingerprints and the matcher version). */
public final class PairCheck implements Record {
    public String pairKey, a, b;
    public Fingerprint fpA, fpB;
    public int matcherVersion, sources, hamming = -1;
    public PairMetrics metrics;
    public PairVerdict verdict;
    public String transform, note;
    public long checkedScanId = Unknown.LONG;
    public String key() { return pairKey; }
    public PairCheck copy() {
        PairCheck r = new PairCheck(); r.pairKey = pairKey; r.a = a; r.b = b; r.fpA = fpA; r.fpB = fpB;
        r.matcherVersion = matcherVersion; r.sources = sources; r.hamming = hamming;
        r.metrics = metrics == null ? null : metrics.<PairMetrics>copy(); r.verdict = verdict; r.transform = transform; r.note = note;
        r.checkedScanId = checkedScanId;
        return r;
    }
}
