package com.storageplanner.v2.content;

/** PHASE_6 6.2: device boundary for OCR. S2 only needs the type (the pass is S7). */
public interface TextReader {
    String id();                 // e.g. "tesseract.js-eng", "sidecar-sim", "none"
    int version();               // bump when engine/traineddata changes => re-OCR
    boolean available();         // false => OCR pass is skipped this run (files stay NOT_RUN)
    /** Reads one picture. argb is upright, long edge <= ContentConfig.ocrLongEdge. Never throws; errors come back as status FAILED. */
    OcrResult read(String volumeId, String path, int[] argb, int w, int h, long timeoutMs);
}
