package com.storageplanner.algorithms;

import com.storageplanner.facts.PictureFacts;

/**
 * Decides whether a picture is still needed, from the tools' facts only. Versions live side by side
 * (v1_rules, v2_futureneed, ...); Registry says which one the app uses, and AlgorithmBench compares them.
 */
public interface DeletionAlgorithm {
    /** Short stable id, e.g. "v2_futureneed". */
    String id();

    /** What it is, for people. */
    String name();

    Decision decide(PictureFacts p, long now);
}
