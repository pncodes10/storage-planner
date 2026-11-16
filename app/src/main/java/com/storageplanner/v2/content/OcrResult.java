package com.storageplanner.v2.content;

import com.storageplanner.v2.model.TextStatus;

/** PHASE_6 6.2: what a {@link TextReader} returns for one picture. */
public final class OcrResult {
    public TextStatus status;    // READ (words valid), NO_TEXT, FAILED, UNAVAILABLE
    public java.util.List<String> words = new java.util.ArrayList<String>();
    public java.util.List<Integer> conf = new java.util.ArrayList<Integer>();   // 0..100 per word
    public String error;
}
