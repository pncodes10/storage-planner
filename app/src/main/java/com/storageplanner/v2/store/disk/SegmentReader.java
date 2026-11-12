package com.storageplanner.v2.store.disk;

import com.storageplanner.v2.device.BlobStore;

import java.io.IOException;

/**
 * Reads frames at positions of one segment, never at or past a caller-given limit (the segment's {@code durable} length,
 * or the flushed length of the active segment): bytes beyond the limit are never requested from the blob (PHASE_S_STORE
 * 3.3/3.6). A buffer serves sequential scans and small random reads. Not thread-safe (the store serializes access).
 */
public final class SegmentReader implements java.io.Closeable {
    private final BlobStore.RandomRead in;
    private final int chunk;
    private byte[] buf;
    private long bufStart = -1;
    private int bufLen;

    /** chunk: bytes read ahead per refill (4 KiB for random reads, 64 KiB for scans). */
    public SegmentReader(BlobStore.RandomRead in, int chunk) {
        this.in = in; this.chunk = Math.max(Frame.MIN_FRAME, chunk); this.buf = new byte[this.chunk];
    }

    /** Forgets buffered bytes (after the segment was truncated and rewritten). */
    public void reset() { bufStart = -1; bufLen = 0; }

    /** The frame at pos; it must end at or before limit. */
    public Frame readAt(long pos, long limit) throws FrameException, IOException {
        if (pos < 0 || pos >= limit) throw FrameException.truncated("position " + pos + " at or beyond the readable limit " + limit, Frame.MIN_FRAME);
        long room = limit - pos;
        int want = (int) Math.min(chunk, room);
        while (true) {
            fill(pos, want);
            int got = (int) Math.min(bufStart + bufLen - pos, room);
            try {
                return Frame.parse(buf, (int) (pos - bufStart), got);
            } catch (FrameException e) {
                if (!e.truncated) throw e;
                if (got < want) throw FrameException.truncated("segment ends at " + (pos + got) + ", before the frame at " + pos + " ends", e.needed);
                if (e.needed > room) throw FrameException.truncated("frame at " + pos + " passes the readable limit " + limit, e.needed);
                if (e.needed <= got) throw FrameException.damaged("parser asked for no more bytes");   // cannot happen; never loop
                want = e.needed;
            }
        }
    }

    private void fill(long pos, int n) throws IOException {
        if (bufStart >= 0 && pos >= bufStart && pos + n <= bufStart + bufLen) return;
        if (buf.length < n) buf = new byte[n];
        int want = n;                     // readAt never asks for bytes at or past its limit
        int got = 0;
        while (got < want) {
            int k = in.read(pos + got, buf, got, want - got);
            if (k <= 0) break;
            got += k;
        }
        bufStart = pos; bufLen = got;
    }

    public void close() throws IOException { in.close(); }
}
