package com.storageplanner.v2.model;

/** One observation behind an edge (ARCHITECTURE 2.6). Immutable. */
public final class Evidence {
    public final String code, value;
    public final boolean structural;
    public Evidence(String code, String value, boolean structural) { this.code = code; this.value = value; this.structural = structural; }
    @Override public boolean equals(Object o) {
        if (!(o instanceof Evidence)) return false;
        Evidence e = (Evidence) o;
        return code.equals(e.code) && (value == null ? e.value == null : value.equals(e.value)) && structural == e.structural;
    }
    @Override public int hashCode() { return code.hashCode() * 31 + (value == null ? 0 : value.hashCode()); }
}
