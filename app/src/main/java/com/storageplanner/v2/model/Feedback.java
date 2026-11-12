package com.storageplanner.v2.model;

/** Placeholder of a later phase: key field(s) only, so the store schema is complete from Phase 1. */
public final class Feedback implements Record {
    public String feedbackId;
    public String key() { return feedbackId; }
    public Feedback copy() { Feedback r = new Feedback(); r.feedbackId = feedbackId; return r; }
}
