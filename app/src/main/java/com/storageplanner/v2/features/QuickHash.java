package com.storageplanner.v2.features;

import com.storageplanner.v2.device.ReadableFile;
import com.storageplanner.v2.util.Hashing;
import com.storageplanner.v2.util.Hex;
import com.storageplanner.v2.util.Io;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.zip.CRC32;

/**
 * Hashes of HashStage. QuickHash = SHA-256 over [0, min(64 KiB, size)), then [size - 64 KiB, size) if size > 128 KiB, then
 * Long.toString(size) — byte-for-byte core.Scanner.quickHash.
 */
public final class QuickHash {
    private QuickHash() {}
    static final int Q = 65536;

    public static String of(ReadableFile rf, long size) throws IOException {
        MessageDigest md = Hashing.sha256();
        byte[] a = Io.readAt(rf, 0, (int) Math.min(Q, size));
        md.update(a);
        if (size > 2L * Q) md.update(Io.readAt(rf, size - Q, Q));
        md.update(Long.toString(size).getBytes(StandardCharsets.US_ASCII));
        return Hex.of(md.digest());
    }

    /** {sha256, crc32 (decimal), quickHash} in one sequential pass (+ one 64 KiB re-read of the tail for sizes > 128 KiB). */
    public static String[] full(ReadableFile rf, long size) throws IOException {
        MessageDigest md = Hashing.sha256();
        MessageDigest q = Hashing.sha256Fresh();
        CRC32 crc = new CRC32();
        byte[] buf = new byte[(int) Math.min(1 << 20, Math.max(1, size))];
        long pos = 0;
        while (pos < size) {
            int n = Io.readFully(rf, pos, buf, 0, (int) Math.min(buf.length, size - pos));
            if (n <= 0) throw new IOException("short read at " + pos + " of " + size);
            md.update(buf, 0, n); crc.update(buf, 0, n);
            if (pos < Q) q.update(buf, 0, (int) Math.min(n, Q - pos));
            pos += n;
        }
        if (rf.read(size, new byte[1], 0, 1) > 0) throw new IOException("file grew during hashing");
        if (size > 2L * Q) q.update(Io.readAt(rf, size - Q, Q));
        q.update(Long.toString(size).getBytes(StandardCharsets.US_ASCII));
        return new String[]{Hex.of(md.digest()), Long.toString(crc.getValue()), Hex.of(q.digest())};
    }
}
