package com.storageplanner.v2.content;

import com.storageplanner.v2.model.TextStatus;

/** The reader of a device without OCR: nothing is ever read. */
public final class UnavailableTextReader implements TextReader {
    public String id() { return "none"; }
    public int version() { return 0; }
    public boolean available() { return false; }
    public OcrResult read(String volumeId, String path, int[] argb, int w, int h, long timeoutMs) {
        OcrResult r = new OcrResult(); r.status = TextStatus.UNAVAILABLE; return r;
    }
}
