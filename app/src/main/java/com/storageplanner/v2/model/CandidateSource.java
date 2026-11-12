package com.storageplanner.v2.model;

/** PHASE_3 / ARCHITECTURE 2.6-2.8. */
public enum CandidateSource { PHASH_MIH, TIME_WINDOW, NAME_STEM; public int bit() { return 1 << ordinal(); } }
