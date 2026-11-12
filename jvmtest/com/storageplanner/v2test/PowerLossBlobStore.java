package com.storageplanner.v2test;

import com.storageplanner.v2.device.BlobStore;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.TreeMap;

/**
 * In-memory BlobStore that knows what was fsynced (REVIEW_S F-12). {@link #powerLoss} reverts every file to its last
 * synced length (optionally keeping a random part of the unsynced tail and/or appending random garbage), drops files
 * whose creation was never synced, and loses uncommitted replaces. Also used as a fast, handle-free store for the
 * oracle test.
 */
public final class PowerLossBlobStore implements BlobStore {
    static final class F {
        byte[] data = new byte[256];
        int len, synced;
        boolean durableExists;
        void write(byte[] b, int off, int n) {
            if (len + n > data.length) data = Arrays.copyOf(data, Math.max(len + n, data.length * 2));
            System.arraycopy(b, off, data, len, n);
            len += n;
        }
    }
    final TreeMap<String, F> files = new TreeMap<String, F>();
    private int syncs, replaces;

    private F f(String name, boolean create) {
        F x = files.get(name);
        if (x == null && create) files.put(name, x = new F());
        return x;
    }

    public synchronized InputStream read(String name) {
        F x = f(name, false);
        return x == null ? null : new ByteArrayInputStream(Arrays.copyOf(x.data, x.len));
    }

    public synchronized OutputStream append(final String name) {
        f(name, true);
        return new OutputStream() {
            @Override public void write(int b) { write(new byte[]{(byte) b}, 0, 1); }
            @Override public void write(byte[] b, int off, int n) {
                synchronized (PowerLossBlobStore.this) { f(name, true).write(b, off, n); }
            }
        };
    }

    public synchronized void sync(String name) throws IOException {
        F x = f(name, false);
        if (x == null) throw new IOException("sync of absent " + name);
        x.synced = x.len; x.durableExists = true; syncs++;
    }

    public synchronized AtomicWrite replace(final String name) {
        final ByteArrayOutputStream bo = new ByteArrayOutputStream();
        return new AtomicWrite() {
            public OutputStream stream() { return bo; }
            public void commit() {
                synchronized (PowerLossBlobStore.this) {
                    F x = new F();
                    byte[] b = bo.toByteArray();
                    x.write(b, 0, b.length);
                    x.synced = x.len; x.durableExists = true;
                    files.put(name, x);
                    replaces++;
                }
            }
            public void abort() { }
        };
    }

    /** As DirBlobStore: truncate to 0 and force (creates the file if absent). */
    public synchronized void truncate(String name) {
        F x = f(name, true);
        x.len = 0; x.synced = 0; x.durableExists = true;
    }

    public synchronized long length(String name) { F x = f(name, false); return x == null ? -1 : x.len; }

    public synchronized RandomRead openRead(final String name) {
        if (f(name, false) == null) return null;
        return new RandomRead() {
            public int read(long pos, byte[] b, int off, int n) {
                synchronized (PowerLossBlobStore.this) {
                    F x = files.get(name);
                    if (x == null || pos >= x.len) return -1;
                    int k = (int) Math.min(n, x.len - pos);
                    System.arraycopy(x.data, (int) pos, b, off, k);
                    return k;
                }
            }
            public long length() { synchronized (PowerLossBlobStore.this) { F x = files.get(name); return x == null ? 0 : x.len; } }
            public void close() { }
        };
    }

    /**
     * Power cut. keepUnsynced: a random part of each file's unsynced tail survives (as if the page cache was partly
     * written back). garbage: some files that had an unsynced tail get 0–4 KiB of random bytes after their surviving length
     * (a fully synced file, e.g. one written by replace, keeps its exact length).
     */
    public synchronized void powerLoss(Random rnd, boolean keepUnsynced, boolean garbage) {
        List<String> gone = new ArrayList<String>();
        for (java.util.Map.Entry<String, F> e : files.entrySet()) {
            F x = e.getValue();
            if (!x.durableExists) { gone.add(e.getKey()); continue; }
            int keep = x.synced;
            boolean unsynced = x.len > x.synced;
            if (keepUnsynced && unsynced) keep = x.synced + rnd.nextInt(x.len - x.synced + 1);
            x.len = keep;
            if (garbage && unsynced && rnd.nextInt(2) == 0) {   // junk only where unsynced blocks were in flight
                byte[] g = new byte[rnd.nextInt(4097)];
                rnd.nextBytes(g);
                x.write(g, 0, g.length);
            }
            x.synced = x.len;
        }
        for (String g : gone) files.remove(g);
    }

    /** Process death only: nothing is lost (the page cache survives). */
    public void kill() { }

    /** Direct access for corruption injection. */
    public synchronized byte[] bytes(String name) { F x = f(name, false); return x == null ? null : Arrays.copyOf(x.data, x.len); }
    public synchronized void setBytes(String name, byte[] b) { F x = new F(); x.write(b, 0, b.length); x.synced = x.len; x.durableExists = true; files.put(name, x); }
    /** Flips bits of one byte in place (lengths and sync state unchanged): damage under an open store (F-07). */
    public synchronized void flip(String name, long pos, int mask) { F x = f(name, false); x.data[(int) pos] ^= (byte) mask; }
    public synchronized List<String> names() { return new ArrayList<String>(files.keySet()); }
    public synchronized int replaces() { return replaces; }
}
