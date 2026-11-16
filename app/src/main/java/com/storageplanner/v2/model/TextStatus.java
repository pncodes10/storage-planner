package com.storageplanner.v2.model;

/**
 * Outcome of reading the text of a picture. READ: all text read; PARTIAL_SCRIPT: some text read, other text present but unreadable;
 * NO_TEXT: pixels show no text; LOW_CONFIDENCE: text present, reader could not read it; FAILED: reader error/timeout;
 * UNAVAILABLE: no reader this run (never stored). Only READ may enable YOUR_CALL (REVIEW_6 F-B2).
 */
public enum TextStatus { NOT_RUN, READ, PARTIAL_SCRIPT, NO_TEXT, LOW_CONFIDENCE, FAILED, UNAVAILABLE }
