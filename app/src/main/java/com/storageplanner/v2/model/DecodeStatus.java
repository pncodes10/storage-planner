package com.storageplanner.v2.model;

/** Result of a pixel decode (ARCHITECTURE 3.2). NO_DECODER is never evidence about the image. */
public enum DecodeStatus { OK, NO_DECODER, UNSUPPORTED_FORMAT, CORRUPT, TOO_LARGE, IO_ERROR, OUT_OF_MEMORY, TIMEOUT }
