package com.storageplanner.v2.model;

/** ARCHITECTURE 2.11. KEEP = absence of a recommendation. */
public enum Tier {
    SAFE, NO_LOSS, YOUR_CALL, REVIEW, PROTECTED;
    /** Removal rank (PHASE_5 9.1): SAFE 0 < NO_LOSS 1 < YOUR_CALL 2 < REVIEW 3; PROTECTED 5; KEEP (null) 4. */
    public static int rank(Tier t) { return t == null ? 4 : t == SAFE ? 0 : t == NO_LOSS ? 1 : t == YOUR_CALL ? 2 : t == REVIEW ? 3 : 5; }
}
