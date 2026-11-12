package com.storageplanner.v2.plan;

import com.storageplanner.v2.model.*;

import java.util.*;

/** PHASE_5 3: the atomic thing the planner chooses. At most one option per unit is selected (keep = no option). */
public final class PlanUnit {
    public String id, subject;
    public List<String> fileIds = new ArrayList<String>();
    public List<Option2> options = new ArrayList<Option2>();
    /** Files that must survive if this unit is acted on (keepers, primaries: rec.dependencyIds). */
    public Set<String> requires = new TreeSet<String>();
    /** Losing side of an app-vs-file conflict: display only, "included in {app}" (PHASE_5 3, F-13). */
    public String includedIn;

    public static final class Option2 {
        public PlanUnit unit;
        public Action action; public Tier tier; public long bytes; public double cost, utility;
        public String recId, code; public int priorityClass; public boolean exact = true;
        /** Units this option may not be selected together with (app vs file subjects). */
        public Set<String> conflicts = new TreeSet<String>();
        public boolean removesFiles() { return action == Action.DELETE || action == Action.QUARANTINE; }
        public boolean transformsFiles() { return action == Action.COMPRESS || action == Action.ARCHIVE; }
        @Override public String toString() { return unit.id + ":" + action + "/" + tier + "/" + bytes; }
    }
    public Option2 add(Action a, Tier t, long bytes, double cost, double utility, String recId, String code) {
        Option2 o = new Option2(); o.unit = this; o.action = a; o.tier = t; o.bytes = bytes; o.cost = cost; o.utility = utility;
        o.recId = recId; o.code = code; o.priorityClass = PlanPriority.of(a, code); o.exact = !a.transform();
        options.add(o); return o;
    }
}
