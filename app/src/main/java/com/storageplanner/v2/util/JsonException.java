package com.storageplanner.v2.util;

/** Malformed JSON; offset = character position of the problem (-1 if not applicable). */
public final class JsonException extends Exception {
    public final int offset;
    public JsonException(String message, int offset) { super(message + (offset >= 0 ? " at offset " + offset : "")); this.offset = offset; }
}
