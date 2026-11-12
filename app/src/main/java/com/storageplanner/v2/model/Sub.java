package com.storageplanner.v2.model;

/** Base of FeatureRecord sub-records: a shallow clone (all fields are values or lists that are replaced, never mutated). */
public abstract class Sub implements Cloneable {
    @SuppressWarnings("unchecked")
    public <T extends Sub> T copy() {
        try { return (T) super.clone(); } catch (CloneNotSupportedException e) { throw new IllegalStateException(e); }
    }
}
