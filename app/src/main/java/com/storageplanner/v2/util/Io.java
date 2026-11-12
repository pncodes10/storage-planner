package com.storageplanner.v2.util;

import com.storageplanner.v2.device.ReadableFile;

import java.io.IOException;
import java.util.Arrays;

/** ReadableFile helpers. */
public final class Io {
    private Io() {}

    /** Up to len bytes at pos (shorter at EOF). len is capped by the caller before calling (no allocation from file data). */
    public static byte[] readAt(ReadableFile rf, long pos, int len) throws IOException {
        if (len <= 0 || pos < 0) return new byte[0];
        byte[] b = new byte[len];
        int n = readFully(rf, pos, b, 0, len);
        return n == len ? b : Arrays.copyOf(b, n);
    }

    /** Reads until len bytes or EOF; returns the count read. */
    public static int readFully(ReadableFile rf, long pos, byte[] b, int off, int len) throws IOException {
        int done = 0;
        while (done < len) {
            int n = rf.read(pos + done, b, off + done, len - done);
            if (n <= 0) break;
            done += n;
        }
        return done;
    }

    /** Counts bytes read (charged to the budget by the stage). */
    public static final class CountingReadableFile implements ReadableFile {
        private final ReadableFile in; public long bytes;
        public CountingReadableFile(ReadableFile in) { this.in = in; }
        public long size() throws IOException { return in.size(); }
        public int read(long position, byte[] buf, int off, int len) throws IOException {
            int n = in.read(position, buf, off, len); if (n > 0) bytes += n; return n;
        }
        public void close() throws IOException { in.close(); }
    }

    /** A window [off, off + len) of another file (nested STORED archive entries). close() does not close the base. */
    public static final class SliceReadableFile implements ReadableFile {
        private final ReadableFile in; private final long off, len;
        public SliceReadableFile(ReadableFile in, long off, long len) { this.in = in; this.off = off; this.len = len; }
        public long size() { return len; }
        public int read(long position, byte[] buf, int o, int n) throws IOException {
            if (position >= len) return -1;
            return in.read(off + position, buf, o, (int) Math.min(n, len - position));
        }
        public void close() {}
    }
}
