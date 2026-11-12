package com.storageplanner.v2.features;

import com.storageplanner.v2.model.*;

/** extractionConfidence and extractionStatus (PHASE_2 9). */
public final class Confidence {
    private Confidence() {}

    public static double of(FileRecord f, FeatureRecord fr) {
        if (f.extractionStatus == ExtractionStatus.UNREADABLE || f.extractionStatus == ExtractionStatus.FAILED) return 0.1;
        double c;
        switch (f.typeSource) { case SIGNATURE: c = 1.0; break; case STRUCTURE: c = 0.9; break; case EXTENSION: c = 0.5; break; default: c = 0.3; }
        switch (f.integrity) { case OK: break; case UNKNOWN: c *= 0.9; break; case ENCRYPTED: c *= 0.7; break; case TRUNCATED: c *= 0.6; break; default: c *= 0.4; }
        String p = fr == null ? null : fr.extra.get("probe");
        if ("partial".equals(p)) c *= 0.85; else if ("exception".equals(p)) c *= 0.6;
        if (Tiers.t3Applicable(f)) {
            DecodeStatus d = fr == null || fr.image == null ? null : fr.image.decodeStatus;
            if (d == null) c *= 0.85;
            else if (d == DecodeStatus.NO_DECODER || d == DecodeStatus.TOO_LARGE || d == DecodeStatus.UNSUPPORTED_FORMAT) c *= 0.7;
            else if (d != DecodeStatus.OK) c *= 0.5;
        }
        if (fr != null && fr.archive != null) {
            String s = fr.archive.listingStatus;
            if ("TRUNCATED".equals(s) || "LIMIT".equals(s)) c *= 0.8; else if ("CORRUPT".equals(s)) c *= 0.5;
        }
        return Math.round(c * 1000) / 1000.0;
    }

    public static ExtractionStatus status(FileRecord f) {
        if (f.extractionStatus == ExtractionStatus.UNREADABLE || f.extractionStatus == ExtractionStatus.FAILED) return f.extractionStatus;
        if ((f.tiersDone | f.tiersFailed) == 0) return ExtractionStatus.NOT_STARTED;
        if ("unknown".equals(f.type)) return ExtractionStatus.UNSUPPORTED;
        if ((f.tiersPending | f.tiersFailed) != 0) return ExtractionStatus.PARTIAL;
        return ExtractionStatus.COMPLETE;
    }

    /** Recomputes status then confidence. Unreadable/failed statuses are set by the caller before. */
    public static void apply(FileRecord f, FeatureRecord fr) {
        f.extractionStatus = status(f);
        f.extractionConfidence = of(f, fr);
        if (fr != null) fr.confidence = f.extractionConfidence;
    }
}
