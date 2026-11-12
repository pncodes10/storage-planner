package com.storageplanner.v2.model;

import java.util.*;

/** PHASE_4 9.1: one possible action with its reason and evidence. */
public final class Candidate {
    public Action action; public Tier proposed; public String reasonCode;
    public Map<String, String> params = new TreeMap<String, String>();
    public List<String> evidenceRefs = new ArrayList<String>();
    public EnumSet<EvidenceFamily> families = EnumSet.noneOf(EvidenceFamily.class);
    public List<String> dependencyIds = new ArrayList<String>();
    public List<String> bundle = new ArrayList<String>();
    public boolean structural;
    /** Relationship types the justification cites (HC6) and whether any cited edge is UNVERIFIED (c_graph). */
    public EnumSet<RelType> cites = EnumSet.noneOf(RelType.class);
    public boolean citesUnverified;
    /** The candidate's own evidence needs content features (HC8 YOUR_CALL clause). */
    public boolean needsFeatures;
    /** Cites a "not installed" fact (HC10). */
    public boolean citesNotInstalled;
    public String fallbackCode; public Tier fallbackTier;
    public List<String> secondaryCodes = new ArrayList<String>();
    // filled in stage 8
    public double confidence = Double.NaN;
    public List<String> blocks = new ArrayList<String>();

    public Candidate(String code, Tier tier, Action action, EvidenceFamily... fams) {
        reasonCode = code; proposed = tier; this.action = action;
        for (EvidenceFamily f : fams) families.add(f);
    }
    public Candidate p(String k, Object v) { params.put(k, String.valueOf(v)); return this; }
    public String prefix() { return reasonCode.substring(0, reasonCode.indexOf('_') + 1); }
    @Override public String toString() { return reasonCode + "/" + proposed + "/" + action + (blocks.isEmpty() ? "" : " blocked " + blocks); }
}
