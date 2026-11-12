package com.storageplanner.v2.model;

/** Placeholder of a later phase: key field(s) only, so the store schema is complete from Phase 1. */
public final class ActionPlan implements Record {
    public String planId;
    public String key() { return planId; }
    public ActionPlan copy() { ActionPlan r = new ActionPlan(); r.planId = planId; return r; }
}
