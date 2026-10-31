package com.storageplanner.tools.ocr;

/**
 * Reads the words in a picture. On the phone this is the offline Tesseract page (OcrEngine); on the desktop the
 * words that page recorded for each test picture. TextRules decides what the words mean.
 */
public interface TextReader {
    /**
     * Words in a decoded picture, read on the device (null when no text reader is available or it failed).
     * The picture is the ARGB array from argb(); path identifies the file.
     */
    TextRules.Words readText(String path, int[] argb, int w, int h);
}
