package com.storageplanner.v2.model;

/** Phase 6 derived document facts (PHASE_6 4.2). No title, author, text or identifier value is stored. */
public final class DocInfo extends Sub {
    public DocStatus status = DocStatus.NOT_RUN;
    public String format;                                      // pdf, docx, xlsx, pptx, odt, ods, odp, txt, csv, ole2
    public int pages = -1;
    public long createdAt = Unknown.TIME, modifiedAt = Unknown.TIME;
    public int chars = -1, words = -1;
    public double latinShare = Double.NaN;
    public double devanagariShare = Double.NaN;
    public String category = "UNKNOWN";                        // DOC_* of ARCHITECTURE 2.10 or UNKNOWN
    public double categoryScore = Double.NaN;
    public java.util.List<String> categoryEvidence;            // keyword ids, at most 5
    public java.util.List<String> idKinds;                     // identifier kinds; [] = none, null = unknown
    public int dates = -1, amounts = -1;
    public long latestDate = Unknown.TIME;                     // stored only if within 400 d before extraction (F-M8)
    public int[] minhash;                                      // 16 values, null = unknown
    public int unsupportedStreams = -1;                        // PDF streams with filters we do not decode
}
