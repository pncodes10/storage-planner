package com.storageplanner.core;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs body(0..n-1) on a few threads. With threads <= 1 it runs on the calling thread, in order, so the
 * single-threaded behaviour is exactly the old one. Items are handed out in order; stops early once cancelled.
 */
final class Parallel {
    private Parallel() {}

    interface Body { void run(int i); }

    static void forEach(final int n, int threads, final Scanner.Options opt, final Scanner.Progress pr, final Body body) {
        if (threads <= 1 || n <= 1) {
            for (int i = 0; i < n; i++) {
                if (pr.cancelled()) return;
                body.run(i);
            }
            return;
        }
        final AtomicInteger next = new AtomicInteger();
        Thread[] ts = new Thread[Math.min(threads, n)];
        for (int t = 0; t < ts.length; t++) {
            ts[t] = new Thread(new Runnable() {
                public void run() {
                    if (opt.workerInit != null) { try { opt.workerInit.run(); } catch (Throwable ignored) {} }
                    int i;
                    while (!pr.cancelled() && (i = next.getAndIncrement()) < n) {
                        try { body.run(i); } catch (Throwable ignored) {}
                    }
                }
            }, "analysis-worker-" + t);
            ts[t].start();
        }
        for (Thread t : ts) {
            boolean interrupted = false;
            while (true) {
                try { t.join(); break; } catch (InterruptedException e) { interrupted = true; }
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
}
