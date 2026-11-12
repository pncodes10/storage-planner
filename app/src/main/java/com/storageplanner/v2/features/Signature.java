package com.storageplanner.v2.features;

import com.storageplanner.v2.model.TypeSource;

/** Result of TypeDetector.detect. */
public final class Signature {
    public final String type; public final TypeSource source; public final double confidence; public final String detail;
    public Signature(String type, TypeSource source, double confidence, String detail) {
        this.type = type; this.source = source; this.confidence = confidence; this.detail = detail;
    }
    public String toString() { return type + "/" + source; }
}
