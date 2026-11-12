package com.storageplanner.v2.model;

import java.util.*;

/** ARCHITECTURE 2.9 with the PHASE_4 1.2 additions. Six separate estimates: U, L, R, C, I, B. */
public final class Assessment {
    public String fileId; public long scanId;
    public Interval u30 = Interval.UNKNOWN, u90 = Interval.UNKNOWN, u365 = Interval.UNKNOWN, u1095 = Interval.UNKNOWN;
    public double loss = Double.NaN; public Map<String, Double> lossFactors = new TreeMap<String, Double>();
    public Recoverability recoverability = Recoverability.UNKNOWN;
    public List<String> recoveryEvidence = new ArrayList<String>();
    public double confidence = Double.NaN;
    public EnumMap<Action, Double> irreversibility = new EnumMap<Action, Double>(Action.class);
    public EnumMap<Action, Long> benefit = new EnumMap<Action, Long>(Action.class);
    public List<String> hardBlocks = new ArrayList<String>();
    public Protection protection = Protection.NONE;
    public Interval lossRange = Interval.UNKNOWN;
    public double rvMid = Double.NaN, rvPess = Double.NaN;
    public String recoveryKind;
    public EnumMap<Action, Double> elMid = new EnumMap<Action, Double>(Action.class), elPess = new EnumMap<Action, Double>(Action.class),
            utility = new EnumMap<Action, Double>(Action.class);
    public Map<String, Double> confidenceParts = new TreeMap<String, Double>();
    public boolean contradiction; public String contradictionA, contradictionB;
    public List<String> familiesFor = new ArrayList<String>(), familiesAgainst = new ArrayList<String>();
    public UsageClass usage;
    /** Inputs that were unknown (U/L widening, c_avail). */
    public boolean anyUnknown;
    /** Groups this file keeps (annotation only, A17) and their keeper reasons. */
    public List<String> keeperOf = new ArrayList<String>(), keeperReasons = new ArrayList<String>();
}
