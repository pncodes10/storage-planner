package com.storageplanner.core;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs the per-picture work of a stage on several threads. With one thread it runs on the calling thread, in order, exactly
 * as a plain loop would. Each thread has its own scratch array (the dims of the picture it decodes).
 */
final class Par {
    private Par() {}

    interface Body { void run(int k, int[] dims) throws Exception; }

    /**
     * Runs body for 0 .. n-1. Progress is reported as pictures finish. Returns false when the scan was cancelled
     * (some pictures were then not done). An exception in a body ends the stage and is thrown again here.
     */
    static boolean forEach(final int n, int threads, final boolean high, final Scanner.Progress pr, final String stage, final Body body) {
        if (n <= 0) return true;
        threads = Math.max(1, Math.min(threads, n));
        final AtomicInteger next = new AtomicInteger(), done = new AtomicInteger();
        final boolean[] cancelled = {false};
        final Throwable[] failure = {null};
        if (threads == 1) {
            int[] dims = new int[4];
            for (int k = 0; k < n; k++) {
                if (pr.cancelled()) return false;
                pr.update(stage, k + 1, n);
                try { body.run(k, dims); } catch (RuntimeException e) { throw e; } catch (Exception e) { throw new RuntimeException(e); }
            }
            return true;
        }
        Thread[] ts = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            ts[t] = new Thread(new Runnable() {
                public void run() {
                    int[] dims = new int[4];
                    while (true) {
                        int k = next.getAndIncrement();
                        if (k >= n) return;
                        synchronized (cancelled) { if (cancelled[0] || failure[0] != null) return; }
                        if (pr.cancelled()) { synchronized (cancelled) { cancelled[0] = true; } return; }
                        try { body.run(k, dims); }
                        catch (Throwable e) { synchronized (cancelled) { if (failure[0] == null) failure[0] = e; } return; }
                        int d = done.incrementAndGet();
                        synchronized (pr) { pr.update(stage, d, n); }
                    }
                }
            }, "par-" + t);
            if (high) ts[t].setPriority(Thread.MAX_PRIORITY);
            ts[t].start();
        }
        for (Thread t : ts) {
            try { t.join(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); synchronized (cancelled) { cancelled[0] = true; } return false; }
        }
        if (failure[0] != null) {
            if (failure[0] instanceof RuntimeException) throw (RuntimeException) failure[0];
            if (failure[0] instanceof Error) throw (Error) failure[0];
            throw new RuntimeException(failure[0]);
        }
        return !cancelled[0];
    }
}
