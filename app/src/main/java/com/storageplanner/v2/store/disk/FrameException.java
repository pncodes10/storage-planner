package com.storageplanner.v2.store.disk;

/** A frame that cannot be accepted: damaged (magic, field domain, CRC) or truncated (not all of it is readable). */
public final class FrameException extends Exception {
    public final boolean truncated;
    /** For a truncated frame: the number of bytes from the frame start needed to go on parsing (a lower bound). */
    public final int needed;

    private FrameException(String m, boolean truncated, int needed) { super(m); this.truncated = truncated; this.needed = needed; }

    static FrameException damaged(String m) { return new FrameException(m, false, -1); }
    static FrameException truncated(String m, int needed) { return new FrameException(m, true, needed); }
}
