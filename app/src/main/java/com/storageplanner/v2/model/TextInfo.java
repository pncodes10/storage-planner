package com.storageplanner.v2.model;

/** Phase 6 derived text facts of a picture (PHASE_6 4.2). No raw text is ever stored. */
public final class TextInfo extends Sub {
    public TextStatus status = TextStatus.NOT_RUN;
    public String engine; public int engineVersion = -1;
    public int words = -1, goodWords = -1, garbage = -1;
    public double meanConf = Double.NaN, readRatio = Double.NaN;
    public String kind;                                        // TextRules verdict kind (PhotoCategory id) or null
    public int kindScore = -1;
    public java.util.List<String> classes;                     // keyword class names; null = unknown, [] = none
    public int idPatterns = -1, amounts = -1, digitRuns4 = -1; // counts; values never stored
    public long textDate = Unknown.TIME;                       // stored only if within 400 d before extraction (F-M8)
    public int attempts = 0;                                   // failed attempts with this engine version
}
