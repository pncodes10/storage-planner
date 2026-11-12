package com.storageplanner.v2test;

import com.storageplanner.v2.model.ScanEvent;
import com.storageplanner.v2.model.ScanSession;
import com.storageplanner.v2.model.ScanState;
import com.storageplanner.v2.store.DiskStore;
import com.storageplanner.v2.store.SpaceProbe;
import com.storageplanner.v2.store.V2Store;
import com.storageplanner.v2adapter.V2Capacity;
import com.storageplanner.v2.util.Json;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * PHASE_S_STORE S4, free space on the pipeline (3.12), CLI in process on the DiskKillTest tree with a fake SpaceProbe:
 * the free space drops below the reserve in the middle of T1 ⇒ the run stops with D_LOW_STORAGE (exit 3), the session is
 * FAILED and durable with the event, the bytes appended after the crossing stay within one check interval + the final
 * allowance; with the space back the next run completes and its plan equals the clean run's. Also the preflight figures
 * and message (V2Capacity).
 */
public final class DiskSpaceTest {
    public static int run(String[] args) throws Exception {
        T.section("S4 space");
        TempTree t = TempTree.create();
        TempTree w = TempTree.create();
        try {
            DiskKillTest.tree(t);
            Path tr = t.root();
            String cleanJson = DiskKillTest.run(tr, w.path("clean.json"), null, "memory", false, null);
            // where T1 appends: learnt from a probe run
            final long[] mark = new long[2];
            final KillingBlobStore probe = new KillingBlobStore(new PowerLossBlobStore());
            V2Cli.afterStage = new java.util.function.BiConsumer<String, V2Store>() {
                public void accept(String stage, V2Store s) {
                    if ("DISCOVER".equals(stage)) mark[0] = probe.appendedBytes();
                    if ("T1_METADATA".equals(stage)) mark[1] = probe.appendedBytes();
                }
            };
            try { DiskKillTest.run(tr, w.path("p.json"), w.dir("p"), "disk", false, probe); } finally { V2Cli.afterStage = null; }
            T.check("T1 appends (" + mark[0] + " → " + mark[1] + ")", mark[1] - mark[0] > 20000);
            final long cross = (mark[0] + mark[1]) / 2;
            PowerLossBlobStore b = new PowerLossBlobStore();
            final KillingBlobStore kb = new KillingBlobStore(b);
            final boolean[] low = {true};
            final long reserve = 64L << 20;
            DiskStore.Options o = new DiskStore.Options();
            o.reserveBytes = reserve;
            o.spaceCheckBytes = 4096;
            o.space = new SpaceProbe() { public long freeBytes() { return low[0] && kb.appendedBytes() >= cross ? reserve - 1 : 1L << 40; } };
            V2Cli.diskOptions = o;
            DiskCorruptionTest.Res r = DiskCorruptionTest.cli(tr, w.path("low.json"), w.dir("low"), "disk", kb);
            long over = kb.appendedBytes() - cross;
            T.check("run stops with D_LOW_STORAGE (exit 3, no result file)", r.rc == 3 && r.err.contains("D_LOW_STORAGE") && r.json == null);
            T.check("appended after the crossing (" + over + " B) ≤ check interval + final allowance + one frame",
                    over <= o.spaceCheckBytes + o.finalAllowanceBytes + (64 << 10));
            DiskStore re = DiskStore.open(DiskStoreTest.copy(b), 0, new FixedClock(0));
            ScanSession last = null;
            for (ScanSession s : re.scanSessions().all()) if (last == null || s.scanId > last.scanId) last = s;
            boolean ev = false;
            if (last != null) for (ScanEvent e : last.events) if ("D_LOW_STORAGE".equals(e.code)) ev = true;
            T.check("session FAILED, durable, with event D_LOW_STORAGE, stopped in T1",
                    last != null && last.state == ScanState.FAILED && ev && last.stagesDone.contains("DISCOVER") && !last.stagesDone.contains("T1_METADATA"));
            low[0] = false;
            DiskCorruptionTest.Res n = DiskCorruptionTest.cli(tr, w.path("next.json"), w.dir("low"), "disk", kb);
            T.check("space back: the next run completes", n.rc == 0 && n.json != null && n.json.contains("\"state\":\"COMPLETED\""));
            if (n.rc == 0) {
                @SuppressWarnings("unchecked") Map<String, Object> cj = (Map<String, Object>) Json.parse(cleanJson);
                @SuppressWarnings("unchecked") Map<String, Object> nj = (Map<String, Object>) Json.parse(n.json);
                T.check("…and its removals and plan equal the clean run's",
                        DiskCorruptionTest.removals(cj).equals(DiskCorruptionTest.removals(nj)) && DiskCorruptionTest.planFiles(cj).equals(DiskCorruptionTest.planFiles(nj)));
            }
        } finally { t.close(); w.close(); V2Cli.diskOptions = null; V2Cli.afterStage = null; }

        T.section("S4 preflight");
        T.eq("needed for 150,000 files", 150000L * 8192 + (64L << 20) + (256L << 20), V2Capacity.diskNeeded(150000));
        T.check("refused below, allowed at the need, unknown free allowed",
                !V2Capacity.diskFits(150000, V2Capacity.diskNeeded(150000) - 1) && V2Capacity.diskFits(150000, V2Capacity.diskNeeded(150000))
                        && V2Capacity.diskFits(150000, -1));
        String msg = V2Capacity.notEnoughStorageMessage(150000);
        T.check("message: " + msg, msg.startsWith("Not enough free storage for v2's working data (about 1,492 MB needed)"));
        return T.finish("DiskSpaceTest");
    }

    public static void main(String[] args) throws Exception { System.exit(run(args) == 0 ? 0 : 1); }
}
