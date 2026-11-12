package com.storageplanner.v2.model;

import java.util.*;

/** PHASE_4 2 stage 2: the hard-rule outcome for a file, computed before any score exists. */
public final class Constraint {
    /** Most aggressive tier any removal may take (null = no limit). */
    public Tier floorTier;
    public EnumSet<Action> forbidden = EnumSet.noneOf(Action.class);
    public List<String> blocks = new ArrayList<String>();
    public Tier forcedTier; public String forcedReason;
    public Map<String, String> forcedParams = new TreeMap<String, String>();
    /** HC2: no verdict at all. */
    public boolean noVerdict;
    public void block(String code) { if (!blocks.contains(code)) blocks.add(code); }
    /** Stable text form used by the policy-invariance test. */
    public String signature() { return noVerdict + "|" + forcedTier + "|" + forcedReason + "|" + floorTier + "|" + forbidden + "|" + blocks; }
}
