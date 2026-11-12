package com.storageplanner.v2.store.disk;

import com.storageplanner.v2.device.BlobStore;

import java.io.IOException;
import java.io.OutputStream;

/**
 * The append side of one segment (PHASE_S_STORE 3.2, 3.7, 3.9). {@link #start} truncates the segment (and syncs the
 * truncation) before the first append, so its HEADER is always at offset 0 and no bytes of an earlier crashed run survive
 * in front of it (REVIEW_S F-02). Frames go through a 64 KiB buffer; {@link #flushedLength} is what readers may read.
 */
public final class SegmentWriter implements java.io.Closeable {
    static final int BUFFER = 1 << 16;
    private final BlobStore blobs;
    public final String name;
    private final OutputStream out;
    private final byte[] buf = new byte[BUFFER];
    private int n;
    private long length, flushed;

    private SegmentWriter(BlobStore blobs, String name, OutputStream out) { this.blobs = blobs; this.name = name; this.out = out; }

    /** Truncates the segment, syncs that, opens it for appending and appends the HEADER frame (buffered). */
    public static SegmentWriter start(BlobStore blobs, String name, String headerJson) throws IOException {
        blobs.truncate(name);
        blobs.sync(name);
        if (blobs.length(name) != 0) throw new IOException("segment " + name + " not empty after truncate");
        SegmentWriter w = new SegmentWriter(blobs, name, blobs.append(name));
        w.append(Frame.header(headerJson));
        return w;
    }

    /** Appends one encoded frame; returns its position. */
    public long append(byte[] frame) throws IOException {
        long pos = length;
        if (frame.length > buf.length - n) flushBuffer();
        if (frame.length >= buf.length) { out.write(frame); out.flush(); flushed = length + frame.length; }
        else { System.arraycopy(frame, 0, buf, n, frame.length); n += frame.length; }
        length += frame.length;
        return pos;
    }

    /** Hands the buffered frames to the blob (not yet durable). */
    public void flushBuffer() throws IOException {
        if (n > 0) { out.write(buf, 0, n); n = 0; }
        out.flush();
        flushed = length;
    }

    /** flushBuffer() and fsync. */
    public void sync() throws IOException { flushBuffer(); blobs.sync(name); }

    /** Bytes appended so far, buffered ones included. */
    public long length() { return length; }
    /** Bytes handed to the blob (readable through openRead). */
    public long flushedLength() { return flushed; }

    public void close() throws IOException { try { flushBuffer(); } finally { out.close(); } }
}
