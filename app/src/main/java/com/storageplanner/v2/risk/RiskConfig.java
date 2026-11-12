package com.storageplanner.v2.risk;

import com.storageplanner.v2.content.ContentClassifier;
import com.storageplanner.v2.content.RuleClassifier;

import java.util.*;

/** PHASE_4 constants. Every number is a deterministic heuristic awaiting calibration (PHASE_4 13). */
public final class RiskConfig {
    public static final double B0 = 50.0 * 1024 * 1024;
    public static final double NO_LOSS_MIN_CONFIDENCE = 0.9;
    public static final long DAY = 86400000L;
    public static final long MIN_STRUCT_AGE_MS = DAY;
    public static final int HORIZON_TIER = 90;
    public static final double P_RECORD_MIN_LOSS_HIGH = 0.8;
    public static final double CONTRADICTION_MIN_STRENGTH = 0.8;
    /** Ordered classifier plug point (PHASE_4 10). */
    public List<ContentClassifier> classifiers = new ArrayList<ContentClassifier>(Collections.<ContentClassifier>singletonList(new RuleClassifier()));
}
