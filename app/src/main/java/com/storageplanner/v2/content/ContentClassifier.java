package com.storageplanner.v2.content;

import com.storageplanner.v2.model.EvidenceFamily;
import com.storageplanner.v2.risk.FileContext;

/**
 * PHASE_4 10 plug point (A7: deterministic now, model later). Allowed effects: raise L factors, raise U multipliers in [1, 2],
 * fire P_UNIQUE_RECORD, support a Y_ candidate only together with a second family. Never lowers L or U, never yields SAFE/NO_LOSS.
 */
public interface ContentClassifier {
    String id();
    int version();
    EvidenceFamily family();
    /** Calibrated precision at its operating point; c_class uses it. */
    double reliability();
    /** null = no opinion; no I/O. */
    Classification classify(FileContext ctx);
}
