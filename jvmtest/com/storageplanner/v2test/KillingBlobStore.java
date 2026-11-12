package com.storageplanner.v2test;

import com.storageplanner.v2.device.BlobStore;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Decorator that kills the "process" around atomic-replace commits: after the n-th successful commit (kill after a
 * durable commit) or inside the n-th commit before the move (kill during a snapshot write).
 */
public final class KillingBlobStore implements BlobStore {
    private final BlobStore inner;
    private int killAfter = -1, killBefore = -1;
    private int attempts, commits;
    private boolean halt;

    public KillingBlobStore(BlobStore inner) { this.inner = inner; }
    public KillingBlobStore killAfterReplaceCommits(int n) { killAfter = n; return this; }
    public KillingBlobStore killBeforeReplaceCommit(int n) { killBefore = n; return this; }
    /** Kill with Runtime.halt(137) instead of throwing SimulatedKill (V2Cli --halt-after-commits). */
    public KillingBlobStore haltProcess(boolean h) { halt = h; return this; }
    public int replaceCommits() { return commits; }

    private void kill(String why) {
        if (halt) { System.out.flush(); System.err.flush(); Runtime.getRuntime().halt(137); }
        throw new SimulatedKill(why);
    }

    public InputStream read(String name) throws IOException { return inner.read(name); }
    public OutputStream append(String name) throws IOException { return inner.append(name); }
    public void sync(String name) throws IOException { inner.sync(name); }
    public void truncate(String name) throws IOException { inner.truncate(name); }
    public long length(String name) { return inner.length(name); }

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
