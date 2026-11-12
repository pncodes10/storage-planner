package com.storageplanner.v2.model;

import java.util.*;

/** PHASE_4 2 stage 9: tier, chosen candidate(s), reasons and evidence. tier == null = KEEP (no recommendation). */
public final class Verdict {
    public String fileId;
    public Tier tier; public Action action = Action.KEEP;
    public int row;                                  // decision-table row that fired (PHASE_4 9.3); 0 = no verdict (HC2)
    public Candidate chosen;                         // removal-class choice (null for KEEP / forced)
    public Candidate transform;                      // row T (null if none)
    public List<String> reasonCodes = new ArrayList<String>();
    public Map<String, String> params = new TreeMap<String, String>();
    public List<String> evidenceRefs = new ArrayList<String>();
    public List<String> dependencyIds = new ArrayList<String>();
    public List<String> hardBlocks = new ArrayList<String>();
    public Assessment assessment;
    public String signature() { return row + "|" + tier + "|" + action + "|" + reasonCodes + "|" + (transform == null ? "" : transform.reasonCode); }
}
