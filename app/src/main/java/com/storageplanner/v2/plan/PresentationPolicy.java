package com.storageplanner.v2.plan;

/** Standard vs aggressive (PHASE_4 9.4, spec 9.5, H7): thresholds and presentation only, never hard rules. */
public final class PresentationPolicy {
    public final String name;
    public final double tYourCall, cYourCall;
    public final boolean burstsAsYourCall, showFailedYourCallAsReview, oldDownloads;
    private PresentationPolicy(String n, double t, double c, boolean b, boolean s, boolean o) {
        name = n; tYourCall = t; cYourCall = c; burstsAsYourCall = b; showFailedYourCallAsReview = s; oldDownloads = o;
    }
    public static final PresentationPolicy STANDARD = new PresentationPolicy("standard", 0.25, 0.50, false, false, false);
    public static final PresentationPolicy AGGRESSIVE = new PresentationPolicy("aggressive", 0.40, 0.35, true, true, true);
    public static PresentationPolicy of(String mode) { return "aggressive".equals(mode) ? AGGRESSIVE : STANDARD; }
}
