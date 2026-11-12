package com.storageplanner.v2test;

import com.storageplanner.v2.model.*;
import com.storageplanner.v2.pipeline.PipelineResult;
import com.storageplanner.v2.store.MemoryStore;
import com.storageplanner.v2.store.StoreException;

import java.io.IOException;
import java.nio.file.Path;

/** Kill / pause / cancel at many points; the resumed scan must equal an uninterrupted one. */
public final class DiscoveryResumeTest {
    static void buildTree(TempTree t) throws IOException {
        for (int i = 0; i < 1200; i++) t.file(String.format("big/f%04d.dat", i), 16, i);
        t.file("top.txt", 10, 1);
        t.file("A/a1.txt", 11, 2); t.file("A/B/b1.txt", 12, 3); t.file("A/B/C/c1.txt", 13, 4);
        t.file("D/d1.txt", 14, 5); t.file("D/E/e1.txt", 15, 6); t.file("D/E/F/f1.txt", 16, 7);
        t.file("G/g1.txt", 17, 8);
    }

    /** One interrupted run (the given kill setup), then resume runs until completion. */
    interface Setup { void apply(Fx fx, Path state) throws IOException; }

    static Fx persistent(TempTree tree, Path state, long snapEvery, FixedClock clock) throws Exception {
        Fx fx = new Fx(tree, Tri.TRUE);
        fx.lim.commitEvery = 500;
        fx.blobs = new DirBlobStore(state);
        fx.store = MemoryStore.open(fx.blobs, snapEvery, clock == null ? fx.clock : clock);
        return fx;
    }

    static String resumeToEnd(TempTree tree, Path state, String name, int expectResumes) throws Exception {
        Fx r = persistent(tree, state, 0, null);
        boolean hadSession = r.store.scanSessions().size() > 0;
        PipelineResult res = r.scan(ScanKind.FULL, true);
        T.eq(name + ": COMPLETED", ScanState.COMPLETED, res.session.state);
        if (hadSession) {
            T.eq(name + ": resumedCount", expectResumes, res.session.resumedCount);
            T.check(name + ": D_INTERRUPTED + D_RESUMED", Fx.hasEvent(res.session, "D_INTERRUPTED") && Fx.hasEvent(res.session, "D_RESUMED"));
        } else {
            T.eq(name + ": killed before the first snapshot → fresh scan 1", 1L, res.session.scanId);
        }
        return Fx.dumpFilesDirs(r.store);
    }

    public static int run(String[] args) throws Exception {
        T.section("DiscoveryResume");
        TempTree tree = TempTree.create();
        TempTree states = TempTree.create();
        try {
            buildTree(tree);
            Fx ref = new Fx(tree, Tri.TRUE);
            ref.lim.commitEvery = 500;
            PipelineResult rr = ref.scan();
            T.eq("reference completes", ScanState.COMPLETED, rr.session.state);
            T.eq("reference file count", 1208, ref.files().size());
            String reference = Fx.dumpFilesDirs(ref.store);
            int n = 0;
            for (final int k : new int[]{1, 2, 4, 7}) {
                // kill before a commit (inside a listing call)
                Path s1 = states.dir("list" + k);
                Fx a = persistent(tree, s1, 0, null);
                a.faulty.killOnListCall(k);
                T.check("killOnListCall(" + k + ") kills", kills(a));
                T.eq("killOnListCall(" + k + ") equal", reference, resumeToEnd(tree, s1, "list" + k, 1));
                // kill right after a durable commit
                Path s2 = states.dir("after" + k);
                Fx b = persistent(tree, s2, 0, null);
                b.store = MemoryStore.open(new KillingBlobStore(b.blobs).killAfterReplaceCommits(k), 0, b.clock);
                T.check("killAfterReplaceCommits(" + k + ") kills", kills(b));
                T.eq("killAfterReplaceCommits(" + k + ") equal", reference, resumeToEnd(tree, s2, "after" + k, 1));
                // kill during a snapshot write
                Path s3 = states.dir("before" + k);
                Fx c = persistent(tree, s3, 0, null);
                c.store = MemoryStore.open(new KillingBlobStore(c.blobs).killBeforeReplaceCommit(k), 0, c.clock);
                T.check("killBeforeReplaceCommit(" + k + ") kills", kills(c));
                T.eq("killBeforeReplaceCommit(" + k + ") equal", reference, resumeToEnd(tree, s3, "before" + k, 1));
                n++;
            }
            T.eq("all kill points tried", 4, n);

            // two kills in one scan
            Path s4 = states.dir("twice");
            Fx a = persistent(tree, s4, 0, null);
            a.faulty.killOnListCall(3);
            T.check("first kill", kills(a));
            Fx b = persistent(tree, s4, 0, null);
            b.faulty.killOnListCall(4);
            boolean killed = false;
            try { b.scan(ScanKind.FULL, true); } catch (SimulatedKill e) { killed = true; }
            T.check("second kill", killed);
            T.eq("two kills: equal", reference, resumeToEnd(tree, s4, "twice", 2));

            // resume from an older snapshot (snapshotEveryMs = 60000, FixedClock)
            Path s5 = states.dir("older");
            final FixedClock fc = new FixedClock();
            Fx o = persistent(tree, s5, 60000, fc);
            o.control = new TestControl().onProgress(3, new Runnable() { public void run() { fc.advance(70000); } });
            o.faulty.killOnListCall(9);
            Fx oo = o;
            killed = false;
            try {
                com.storageplanner.v2.stage.PipelineConfig cfg = new com.storageplanner.v2.stage.PipelineConfig();
                java.util.List<com.storageplanner.v2.stage.Stage> st = new java.util.ArrayList<com.storageplanner.v2.stage.Stage>();
                st.add(new com.storageplanner.v2.discovery.DiscoveryEngine(oo.lim, null));
                new com.storageplanner.v2.pipeline.Pipeline(new JvmDevice(oo.sa, fc, new JvmResourceMonitor(), oo.blobs), oo.store, cfg, st).run(oo.control);
            } catch (SimulatedKill e) { killed = true; }
            T.check("older-snapshot run killed", killed);
            MemoryStore older = MemoryStore.open(new DirBlobStore(s5), 0, fc);
            ScanSession os = older.scanSessions().get(ScanSession.keyOf(1));
            T.check("older snapshot holds a mid-scan session", os != null && os.checkpoint != null);
            T.eq("older snapshot: equal after resume", reference, resumeToEnd(tree, s5, "older", 1));

            // pause → resume
            Path s6 = states.dir("pause");
            Fx p = persistent(tree, s6, 0, null);
            p.control = new TestControl().pauseAfterProgress(3);
            PipelineResult pr = p.scan();
            T.eq("PAUSED", ScanState.PAUSED, pr.session.state);
            T.eq("pause reason", "PL_PAUSED_USER", pr.session.pauseReason);
            Fx p2 = persistent(tree, s6, 0, null);
            PipelineResult pr2 = p2.scan(ScanKind.FULL, true);
            T.eq("paused → resumed COMPLETED", ScanState.COMPLETED, pr2.session.state);
            T.eq("paused → resumed resumedCount", 1, pr2.session.resumedCount);
            T.eq("pause/resume equal", reference, Fx.dumpFilesDirs(p2.store));

            // cancel → new scan 2
            Path s7 = states.dir("cancel");
            Fx c = persistent(tree, s7, 0, null);
            c.control = new TestControl().cancelAfterProgress(3);
            T.eq("CANCELLED", ScanState.CANCELLED, c.scan().session.state);
            Fx c2 = persistent(tree, s7, 0, null);
            PipelineResult cr = c2.scan();
            T.eq("next scan id 2", 2L, cr.session.scanId);
            T.eq("scan 2 COMPLETED", ScanState.COMPLETED, cr.session.state);
            T.eq("scan 1 stays CANCELLED", ScanState.CANCELLED, c2.session(1).state);
            T.eq("cancelled then full scan: all files", 1208, c2.count(Presence.PRESENT));
        } finally { tree.close(); states.close(); }
        return T.finish("DiscoveryResumeTest");
    }

    static boolean kills(Fx fx) throws StoreException {
        try { fx.scan(); return false; } catch (SimulatedKill e) { return true; }
    }
}
