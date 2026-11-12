package com.storageplanner.v2test;

import com.storageplanner.v2.device.BlobStore;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Decorator that kills the "process" around atomic-replace commits: after the n-th successful commit (kill after a
 * durable commit) or inside the n-th commit before the move (kill during a snapshot write). PHASE_S_STORE 3.9 adds two
 * kill points: after exactly n appended bytes (summed over all append streams: a torn frame) and before the n-th truncate.
 */
public final class KillingBlobStore implements BlobStore {
    private final BlobStore inner;
    private int killAfter = -1, killBefore = -1, killTruncate = -1;
    private long killBytes = -1, appended;
    private int attempts, commits, truncates;
    private boolean halt;

    public KillingBlobStore(BlobStore inner) { this.inner = inner; }
    public KillingBlobStore killAfterReplaceCommits(int n) { killAfter = n; return this; }
    public KillingBlobStore killBeforeReplaceCommit(int n) { killBefore = n; return this; }
    /** Kill with Runtime.halt(137) instead of throwing SimulatedKill (V2Cli --halt-after-commits). */
    /** The append that would pass n bytes in total writes exactly up to n, flushes and kills. */
    public KillingBlobStore killAfterAppendedBytes(long n) { killBytes = n; return this; }
    /** Kills before the n-th truncate call (inside compaction). */
    public KillingBlobStore killBeforeTruncate(int n) { killTruncate = n; return this; }
    public long appendedBytes() { return appended; }
    public KillingBlobStore haltProcess(boolean h) { halt = h; return this; }
    public int replaceCommits() { return commits; }

    private void kill(String why) {
        if (halt) { System.out.flush(); System.err.flush(); Runtime.getRuntime().halt(137); }
        throw new SimulatedKill(why);
    }

    public InputStream read(String name) throws IOException { return inner.read(name); }
    public OutputStream append(String name) throws IOException {
        final OutputStream o = inner.append(name);
        return new OutputStream() {
            @Override public void write(int b) throws IOException { write(new byte[]{(byte) b}, 0, 1); }
            @Override public void write(byte[] b, int off, int len) throws IOException {
                if (killBytes >= 0 && appended + len > killBytes) {
                    int part = (int) (killBytes - appended);
                    o.write(b, off, part);
                    o.flush();
                    appended += part;
                    kill("kill after " + appended + " appended bytes");
                }
                o.write(b, off, len);
                appended += len;
            }
            @Override public void flush() throws IOException { o.flush(); }
            @Override public void close() throws IOException { o.close(); }
        };
    }
    public void sync(String name) throws IOException { inner.sync(name); }
    public void truncate(String name) throws IOException {
        int t = ++truncates;
        if (t == killTruncate) kill("kill before truncate " + t);
        inner.truncate(name);
    }
    public long length(String name) { return inner.length(name); }
    public RandomRead openRead(String name) throws IOException { return inner.openRead(name); }

    public AtomicWrite replace(String name) throws IOException {
        final AtomicWrite w = inner.replace(name);
        return new AtomicWrite() {
            public OutputStream stream() { return w.stream(); }
            public void commit() throws IOException {
                int a = ++attempts;
                if (a == killBefore) kill("kill before replace commit " + a);
                w.commit();
                int c = ++commits;
                if (c == killAfter) kill("kill after replace commit " + c);
            }
            public void abort() { w.abort(); }
        };
    }
}
