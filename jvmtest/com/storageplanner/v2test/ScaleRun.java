package com.storageplanner.v2test;

import com.storageplanner.v2.model.AccessGrant;
import com.storageplanner.v2.discovery.PosixStorageAccess;
import com.storageplanner.v2.discovery.DiscoveryEngine;
import com.storageplanner.v2.discovery.WalkLimits;
import com.storageplanner.v2.features.T1MetadataStage;
import com.storageplanner.v2.features.T2HashStructStage;
import com.storageplanner.v2.features.T3ImageStage;
import com.storageplanner.v2.graph.GraphStage;
import com.storageplanner.v2.model.AccessMode;
import com.storageplanner.v2.model.ActionPlan;
import com.storageplanner.v2.model.FileRecord;
import com.storageplanner.v2.model.Recommendation;
import com.storageplanner.v2.model.Tri;
import com.storageplanner.v2.model.Verdict;
import com.storageplanner.v2.model.VolumeKind;
import com.storageplanner.v2.pipeline.Pipeline;
import com.storageplanner.v2.stage.PipelineConfig;
import com.storageplanner.v2.pipeline.PipelineResult;
import com.storageplanner.v2.plan.AppSubjects;
import com.storageplanner.v2.plan.HashOnDemand;
import com.storageplanner.v2.plan.Plans;
import com.storageplanner.v2.plan.PresentationPolicy;
import com.storageplanner.v2.plan.ProvisionalRecommendations;
import com.storageplanner.v2.plan.UnitBuilder2;
import com.storageplanner.v2.risk.Assessor;
import com.storageplanner.v2.risk.RiskConfig;
import com.storageplanner.v2.stage.Stage;
import com.storageplanner.v2.stage.StageContext;
import com.storageplanner.v2.stage.StageOutcome;
import com.storageplanner.v2.store.MemoryStore;
import com.storageplanner.v2.store.StoreException;
import com.storageplanner.v2.store.V2Store;
import com.storageplanner.v2adapter.V2Adapter;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Scale harness (PHASE_S_STORE slice S0): DISCOVER, T1-T3, GRAPH through the pipeline, then the app's analysis path
 * ({@code V2Analysis}, the code V2Runner runs: ASSESS, recommendations, hash-on-demand, app subjects, PLAN, adapter). Prints used
 * heap per phase.
 *
 * <pre>ScaleRun ROOT [--store memory|disk] [--profile] [--histograms DIR] [--tables] [--no-adapter]</pre>
 * {@code --store disk}: a DiskStore over a fresh directory under $TMPDIR (removed at the end); {@code --tables} is memory only.
 * Without --profile only the end-to-end time and the final line are printed (for runs under a hard -Xmx cap).
 * Exit 0 on success; an OutOfMemoryError propagates (exit 1, "OOM" on stderr).
 */
public final class ScaleRun {
    static final long NOW = 1792000000000L;

    public static void main(String[] a) throws Exception {
        String root = null, store = "memory", histDir = null;
        boolean profile = false, tables = false, adapter = true;
        for (int i = 0; i < a.length; i++) {
            if (a[i].equals("--store")) store = a[++i];
            else if (a[i].equals("--profile")) profile = true;
            else if (a[i].equals("--histograms")) { histDir = a[++i]; profile = true; }
            else if (a[i].equals("--tables")) { tables = true; profile = true; }
            else if (a[i].equals("--no-adapter")) adapter = false;
            else if (root == null) root = a[i];
            else { System.err.println("usage: ScaleRun ROOT [--store memory|disk] [--profile] [--histograms DIR] [--tables] [--no-adapter]"); System.exit(2); }
        }
        if (root == null) { System.err.println("ROOT missing"); System.exit(2); }
        if (!store.equals("memory") && !store.equals("disk")) { System.err.println("--store memory|disk"); System.exit(2); }
        if (store.equals("disk") && tables) { System.err.println("--tables: memory store only"); System.exit(2); }
        if (histDir != null) new File(histDir).mkdirs();
        java.nio.file.Path stateDir = null;
        try {
            V2Store st;
            if (store.equals("disk")) {
                stateDir = java.nio.file.Files.createTempDirectory("v2scale-ds");
                st = com.storageplanner.v2.store.DiskStore.create(new DirBlobStore(stateDir), -1, new SystemClock(NOW));
            } else st = MemoryStore.volatileStore();
            run(root, st, profile ? new HeapProfile(histDir, System.out) : null, tables, adapter, System.out);
            if (stateDir != null) {
                long disk = 0;
                for (File f : stateDir.toFile().listFiles()) disk += f.length();
                com.storageplanner.v2.store.StoreStats ss = st.stats();
                com.storageplanner.v2.store.DiskStore ds = (com.storageplanner.v2.store.DiskStore) st;
                System.out.println("disk store: " + disk + " bytes on disk (all files), " + ss + " compactions=" + ds.compactions()
                        + " skipped=" + ds.compactionsSkipped() + " liveBytes=" + ss.liveBytes + " fileBytes=" + disk);
            }
        } catch (OutOfMemoryError e) {
            System.err.println("OOM: " + e.getMessage());
            System.exit(1);
        } finally {
            if (stateDir != null) { File[] fs = stateDir.toFile().listFiles(); if (fs != null) for (File f : fs) f.delete(); stateDir.toFile().delete(); }
        }
    }

    static void run(String root, final V2Store store, final HeapProfile hp, boolean tables, boolean adapter, final PrintStream out) throws Exception {
        long t0 = System.nanoTime();
        SystemClock clock = new SystemClock(NOW);
        PosixStorageAccess.Root r = new PosixStorageAccess.Root();
        r.volumeId = "primary"; r.kind = VolumeKind.DIRECTORY; r.label = "x";
        r.path = Paths.get(root).toAbsolutePath().normalize().toString(); r.fileKeyStable = Tri.TRUE;
        r.restrictedPrefixes = Arrays.asList("Android/data", "Android/obb");
        final PosixStorageAccess sa = new PosixStorageAccess(Collections.singletonList(r),
                Collections.singletonList(AccessGrant.readAll("primary", "", AccessMode.B_ALL_FILES)));
        PipelineConfig cfg = new PipelineConfig();
        cfg.maxTier = 3;
        JvmInstalledApps apps = new JvmInstalledApps((String) null);
        JvmDevice dev = new JvmDevice(sa, clock, new JvmResourceMonitor(), null, new JvmMediaDecoder(sa), apps);
        if (hp != null) hp.start();
        List<Stage> stages = new ArrayList<Stage>();
        stages.add(new DiscoveryEngine(new WalkLimits(), null));
        stages.add(new T1MetadataStage());
        stages.add(new T2HashStructStage(false));
        stages.add(new T3ImageStage());
        GraphStage gs = new GraphStage();
        stages.add(gs);
        final ProfControl control = hp == null ? null : new ProfControl(hp, store);
        List<Stage> wrapped = new ArrayList<Stage>();
        for (final Stage s : stages) wrapped.add(new Stage() {
            public String id() { return s.id(); }
            public StageOutcome run(StageContext c) throws StoreException {
                if (control != null) control.stage = s.id();
                long ts = System.nanoTime();
                StageOutcome o = s.run(c);
                out.println(s.id() + " ms=" + (System.nanoTime() - ts) / 1000000 + " outcome=" + o);
                if (hp != null) hp.mark("after_" + s.id(), c.store.files().size());
                return o;
            }
        });
        PipelineResult res = new Pipeline(dev, store, cfg, wrapped).run(control);
        out.println("session " + res.session.state + " graph coverage " + gs.coverage);
        wrapped = null; stages = null; gs = null;            // drop GraphStage.last (Perceptual, signature cache)
        int n = store.files().size();
        out.println("files=" + n + " features=" + store.features().size() + " hashes=" + store.hashes().size() + " rel=" + store.relationships().size()
                + " groups=" + store.duplicateGroups().size() + " pairChecks=" + store.pairChecks().size() + " dirs=" + store.directories().size());
        if (hp != null) hp.mark("store_after_graph", n);

        // ---- the app's analysis path: V2Analysis, exactly as V2Runner calls it (PHASE_S_STORE 4 item 6) ----
        long ta = System.nanoTime();
        com.storageplanner.v2adapter.V2Analysis.Params p = new com.storageplanner.v2adapter.V2Analysis.Params();
        p.apps = apps; p.now = clock.nowMillis(); p.scanId = res.session.scanId; p.sdk = 34; p.root = r.path; p.total = 0; p.free = 0;
        p.hasher = new HashOnDemand.Hasher() { public String sha256(FileRecord f) { return sha256Of(sa.localPath(f.volumeId, f.path)); } };
        p.hashBudget = 2000000000L;
        p.adapt = adapter;
        final int nFiles = n;
        if (hp != null) p.marks = new com.storageplanner.v2adapter.V2Analysis.Marks() { public void mark(String phase) { hp.mark(phase, nFiles); } };
        com.storageplanner.v2adapter.V2Analysis.Result ar = com.storageplanner.v2adapter.V2Analysis.run(store, p);
        int items = ar.adapted == null ? -1 : ar.adapted.result.files.size();
        out.println("analyse ms=" + (System.nanoTime() - ta) / 1000000 + " recs=" + ar.in.recs.size() + " adapterFiles=" + items);
        ar = null; p = null;
        if (hp != null) {
            long st = hp.mark("store_only_end", n);
            out.println("peak marked used=" + hp.peakMarked + " perFile=" + (n == 0 ? 0 : (hp.peakMarked - hp.base) / n));
            if (tables) hp.perTable(store, n, st);
        }
        out.println("SCALE_RUN OK files=" + n + " totalMs=" + (System.nanoTime() - t0) / 1000000 + " maxHeap=" + Runtime.getRuntime().maxMemory());
    }

    /**
     * With a profile: one heap mark at the first progress call (done == 0) of each job loop, i.e. inside the stage while its
     * working set (job list) is referenced, and one at T2 part B's first size group (its stopReason check follows the part A
     * jobs' checks). These "in_" marks are the stage peaks of PHASE_S S5.
     */
    static final class ProfControl implements com.storageplanner.v2.device.ScanControl {
        final HeapProfile hp; final V2Store store;
        final Map<String, Integer> seen = new HashMap<String, Integer>();
        String stage;
        long t2a, checks;
        ProfControl(HeapProfile hp, V2Store store) { this.hp = hp; this.store = store; }
        public boolean cancelRequested() { return false; }
        public boolean pauseRequested() {
            if ("T2_HASH_STRUCT".equals(stage) && ++checks == t2a + 1) hp.mark("in_T2_HASH_STRUCT_groups", store.files().size());
            return false;
        }
        public void progress(String st, long done, long total) {
            if (done != 0 || st.equals("DISCOVER")) return;
            if (st.equals("T2_HASH_STRUCT") && !seen.containsKey(st)) t2a = total;
            Integer k = seen.get(st); seen.put(st, k == null ? 1 : k + 1);
            hp.mark("in_" + st + (k == null ? "" : "#" + (k + 1)) + " n=" + total, store.files().size());
        }
    }

    static String sha256Of(String path) {
        if (path == null) return null;
        try (InputStream in = new FileInputStream(path)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] b = new byte[65536];
            int k;
            while ((k = in.read(b)) > 0) md.update(b, 0, k);
            StringBuilder sb = new StringBuilder();
            for (byte x : md.digest()) sb.append(String.format("%02x", x & 0xff));
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }
}
