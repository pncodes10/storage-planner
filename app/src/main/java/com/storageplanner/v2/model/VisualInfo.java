package com.storageplanner.v2.model;

/** Phase 6 visual facts: one decode at long edge 1024, upright (PHASE_6 4.2). NaN / -1 / null / NOT_RUN = unknown. */
public final class VisualInfo extends Sub {
    public DecodeStatus decodeStatus;                          // null = not attempted
    public int analysedWidth = -1, analysedHeight = -1;
    public double aspect = Double.NaN;                         // upright width / height of the ORIGINAL
    public double meanLuma = Double.NaN, darkFrac = Double.NaN, contrast = Double.NaN, colorful = Double.NaN, paper = Double.NaN,
                  flat = Double.NaN, textArea = Double.NaN, textHeight = Double.NaN, bigText = Double.NaN;
    public int textBlobs = -1, textLines = -1;
    public DetectorStatus qrStatus = DetectorStatus.NOT_RUN;
    public String qrFormat;                                    // e.g. "QR_CODE"; null when none/unknown
    public String qrKind;                                      // QrKind name: URL, UPI, WIFI, CONTACT, TEXT; null when none/unknown
    public DetectorStatus faceStatus = DetectorStatus.NOT_RUN;
    public int faceCount = -1;                                 // faces with score >= 0.7
    public double faceMaxScore = Double.NaN;
    public int faceModelVersion = -1;
}
