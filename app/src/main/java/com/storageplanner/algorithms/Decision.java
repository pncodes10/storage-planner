package com.storageplanner.algorithms;

import java.util.ArrayList;
import java.util.List;

/** What a deletion algorithm decided about one picture, and why. */
public final class Decision {
    public static final int KEEP = 0, UNSURE = 1, NOT_NEEDED = 2;

    public String algorithm;          // id of the algorithm that decided (see Registry)
    public int verdict;
    public double need;               // 0-1: how likely the picture is wanted again
    public String kind;               // what the picture is for, in the algorithm's own terms
    public String kindLabel = "";     // the same, for people
    public String technique;          // recommendation it goes under when not needed, else null
    public String why = "";           // one line for the UI
    public final List<String> reasons = new ArrayList<String>();
    public long usefulUntil;          // expiry, trip, delivery or appointment date read in it; 0 if none
    public long deletableFrom;        // when it becomes "not needed" if nothing changes; 0 = never (or already)

    public static String verdictLabel(int v) {
        return v == KEEP ? "Keep: likely needed again" : v == UNSURE ? "Your call: may not be needed" : "Not needed any more";
    }
}
