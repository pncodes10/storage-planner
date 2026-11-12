package com.storageplanner.v2.model;

/** PHASE_4 1.3. direction +1 supports value/need, -1 supports disposability. */
public final class Signal {
    public final EvidenceFamily family; public final String code; public final int direction; public final double strength; public final String ref;
    public Signal(EvidenceFamily family, String code, int direction, double strength, String ref) {
        this.family = family; this.code = code; this.direction = direction; this.strength = strength; this.ref = ref;
    }
    @Override public String toString() { return family + ":" + code + (direction > 0 ? "+" : "-") + strength; }
}
