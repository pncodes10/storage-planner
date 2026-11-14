package com.storageplanner.v2test;

import com.storageplanner.v2.device.ScanControl;
import com.storageplanner.v2.discovery.PosixStorageAccess;
import com.storageplanner.v2.model.*;
import com.storageplanner.v2.pipeline.Pipeline;
import com.storageplanner.v2.pipeline.PipelineResult;
import com.storageplanner.v2.sample.SampleSelector;
import com.storageplanner.v2.sample.SampleSpec;
import com.storageplanner.v2.sample.SampleStage;
import com.storageplanner.v2.stage.PipelineConfig;
import com.storageplanner.v2.stage.Stage;
import com.storageplanner.v2.store.DiskStore;
import com.storageplanner.v2.store.MemoryStore;
import com.storageplanner.v2.store.V2Store;
import com.storageplanner.v2adapter.V2Analysis;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * The "Quick v2 test" button: SampleSelector (newest N images and M videos, deterministic), SampleStage (only the sample goes on),
 * a separate sample store, no overclaiming (notice, no app recommendations) and a full run that is unaffected by an earlier sample.
 */
public final class SampleTest {
    static final long NOW = 1792000000000L, DAY = 86400000L;

    // ------------------------------------------------------------------ selector
    static FileRecord rec(String path, long mtime) {
        FileRecord f = new FileRecord();
        f.volumeId = "primary"; f.path = path; f.fileId = "id:" + path; f.name = path.substring(path.lastIndexOf('/') + 1);
        f.extension = com.storageplanner.v2.util.Text.extension(f.name); f.modifiedAt = mtime; f.presence = Presence.PRESENT;
        return f;
    }

    static List<String> paths(Set<String> ids) { List<String> l = new ArrayList<String>(); for (String s : ids) l.add(s.substring(3)); Collections.sort(l); return l; }

    static void selector() {
        T.section("SampleSelector");
        T.eq("defaults", 1000, SampleSpec.SAMPLE_IMAGES); T.eq("defaults videos", 100, SampleSpec.SAMPLE_VIDEOS);
        T.eq("notice text", "Sample result: 1,000 newest photos and 100 newest videos only.", SampleSpec.DEFAULT.notice());
        List<FileRecord> all = new ArrayList<FileRecord>();
        for (int i = 0; i < 10; i++) all.add(rec("p/img" + i + ".jpg", 1000 + i));          // images: img9 newest
        for (int i = 0; i < 6; i++) all.add(rec("v/vid" + i + ".MP4", 5000 + i));            // upper-case extension counts
        for (String e : new String[]{"pdf", "mp3", "apk", "zip", "txt", "avif", "dng", "ts", "jpg.bak", ""}) all.add(rec("o/new." + e, 999999));   // newer than everything, never selected
        FileRecord gone = rec("p/gone.jpg", 999999); gone.presence = Presence.REMOVED; all.add(gone);
        FileRecord unv = rec("p/unv.jpg", 999999); unv.presence = Presence.UNVERIFIABLE; all.add(unv);
        Set<String> s = SampleSelector.select(all, new SampleSpec(3, 2));
        T.eq("3 newest images and 2 newest videos, nothing else", Arrays.asList("p/img7.jpg", "p/img8.jpg", "p/img9.jpg", "v/vid4.MP4", "v/vid5.MP4"), paths(s));
        T.eq("fewer available than asked: all of them", 16, SampleSelector.select(all, new SampleSpec(1000, 100)).size());
        T.eq("zero images", Arrays.asList("v/vid5.MP4"), paths(SampleSelector.select(all, new SampleSpec(0, 1))));
        T.eq("nothing asked", 0, SampleSelector.select(all, new SampleSpec(0, 0)).size());
        T.eq("empty input", 0, SampleSelector.select(new ArrayList<FileRecord>(), SampleSpec.DEFAULT).size());
        // ties: same mtime for 8 images; the smallest paths win, whatever the order of offers
        List<FileRecord> ties = new ArrayList<FileRecord>();
        for (String n : new String[]{"d/h.jpg", "d/b.jpg", "d/f.jpg", "d/a.jpg", "d/g.jpg", "d/c.jpg", "d/e.jpg", "d/d.jpg"}) ties.add(rec(n, 777));
        ties.add(rec("d/zzz_newer.png", 778));
        List<String> expect = Arrays.asList("d/a.jpg", "d/b.jpg", "d/c.jpg", "d/zzz_newer.png");
        for (int seed = 0; seed < 5; seed++) {
            Collections.shuffle(ties, new Random(seed));
            T.eq("ties by path, order-independent (" + seed + ")", expect, paths(SampleSelector.select(ties, new SampleSpec(4, 0))));
        }
        FileRecord unk = rec("d/unknown_time.jpg", Unknown.TIME);
        ties.add(unk);
        T.check("unknown modification time ranks last (taken only when there is room)",
                SampleSelector.select(ties, new SampleSpec(10, 0)).contains(unk.fileId) && !SampleSelector.select(ties, new SampleSpec(9, 0)).contains(unk.fileId));
        T.eq("kindOf", Arrays.asList(1, 1, 1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 2, 2, 2, 0, 0, 0),
                Arrays.asList(SampleSelector.kindOf("jpg"), SampleSelector.kindOf("jpeg"), SampleSelector.kindOf("png"), SampleSelector.kindOf("webp"),
                        SampleSelector.kindOf("heic"), SampleSelector.kindOf("heif"), SampleSelector.kindOf("gif"), SampleSelector.kindOf("BMP"),
                        SampleSelector.kindOf("mp4"), SampleSelector.kindOf("mov"), SampleSelector.kindOf("3gp"), SampleSelector.kindOf("mkv"),
                        SampleSelector.kindOf("webm"), SampleSelector.kindOf("avi"), SampleSelector.kindOf("m4v"),
                        SampleSelector.kindOf("pdf"), SampleSelector.kindOf(null), SampleSelector.kindOf("")));
    }

    // ------------------------------------------------------------------ pipeline runs
    /** The fixture: a phone-like tree. Images img0..img29 (img29 newest), 2 exact copies in the sample and 1 old pair outside it. */
    static void fixture(TempTree t) throws Exception {
        for (int i = 0; i < 30; i++) {
            String p = "DCIM/Camera/IMG_" + (i < 10 ? "0" : "") + i + ".jpg";
            t.file(p, FxImg.jpeg(FxImg.pattern(48, 48, 100 + i), 0.9f));
            t.mtime(p, NOW - (60 - i) * DAY);
        }
        byte[] newest = t.read("DCIM/Camera/IMG_29.jpg");
        t.file("Download/IMG_29_copy.jpg", newest); t.mtime("Download/IMG_29_copy.jpg", NOW - 30 * DAY + 1);     // exact duplicate, both in the sample
        byte[] old = t.read("DCIM/Camera/IMG_00.jpg");
        t.file("Pictures/old_copy.jpg", old); t.mtime("Pictures/old_copy.jpg", NOW - 59 * DAY + 1);              // exact duplicate, both outside the sample
        for (int i = 0; i < 8; i++) { String p = "Movies/v" + i + ".mp4"; t.file(p, 3000 + i, 500 + i); t.mtime(p, NOW - (40 - i) * DAY); }
        t.file("Movies/dup1.mp4", t.read("Movies/v7.mp4")); t.mtime("Movies/dup1.mp4", NOW - 33 * DAY + 5);
        for (int i = 0; i < 6; i++) { t.file("Download/doc" + i + ".pdf", 500 + i, 900 + i); t.mtime("Download/doc" + i + ".pdf", NOW - i * DAY); }
        t.file("Download/same_a.pdf", 700, 4242); t.file("Documents/same_b.pdf", t.read("Download/same_a.pdf"));
        t.file("Download/setup.apk", 900, 77); t.file("Download/bundle.zip", 900, 78); t.file("Music/a.mp3", 900, 79);
        t.file("Download/session.tmp", 1200, 80); t.mtime("Download/session.tmp", NOW - 90 * DAY);
        t.file("Android/data/com.x/cache/c.jpg", FxImg.jpeg(FxImg.pattern(48, 48, 999), 0.9f));
    }

    static final class Run {
        V2Store store; V2Analysis.Result result; long scanId; PipelineResult pipe; Set<String> pruneMid;
    }

    interface Hook { void progress(V2Store store, String stage); }

    /** The app's order: discovery, (SAMPLE), T1-T3, graph, then V2Analysis. */
    static Run run(Path root, Path stateDir, boolean disk, SampleSpec sample, Hook hook) throws Exception {
        PosixStorageAccess.Root r = new PosixStorageAccess.Root();
        r.volumeId = "primary"; r.kind = VolumeKind.DIRECTORY; r.label = "primary"; r.path = root.toString(); r.fileKeyStable = Tri.TRUE;
        r.restrictedPrefixes = Arrays.asList("Android/data", "Android/obb");
        PosixStorageAccess sa = new PosixStorageAccess(Collections.singletonList(r), Collections.singletonList(AccessGrant.readAll("primary", "", AccessMode.B_ALL_FILES)));
        FixedClock clock = new FixedClock(NOW);
        final V2Store store = disk ? DiskStore.create(new DirBlobStore(stateDir), -1, clock) : MemoryStore.volatileStore();
        JvmDevice dev = new JvmDevice(sa, clock, new JvmResourceMonitor(), new DirBlobStore(stateDir), new JvmMediaDecoder(sa), new JvmInstalledApps(null));
        PipelineConfig cfg = new PipelineConfig();
        cfg.maxTier = 3;
        List<Stage> stages = Pipeline.defaultStages(cfg, dev);
        if (sample != null) SampleStage.insertAfterDiscovery(stages, sample);
        stages.add(new com.storageplanner.v2.graph.GraphStage());
        final Hook h = hook;
        ScanControl ctl = new ScanControl() {
            public boolean cancelRequested() { return false; }
            public boolean pauseRequested() { return false; }
            public void progress(String stage, long done, long total) { if (h != null) h.progress(store, stage); }
        };
        Run out = new Run();
        out.store = store;
        out.pipe = new Pipeline(dev, store, cfg, stages).run(ctl);
        T.eq("pipeline done", com.storageplanner.v2.stage.StageOutcome.DONE, out.pipe.outcome);
        out.scanId = out.pipe.session.scanId;
        V2Analysis.Params p = new V2Analysis.Params();
        p.apps = new JvmInstalledApps(null); p.now = NOW; p.scanId = out.scanId; p.sdk = 34; p.root = root.toString();
        p.hasher = StreamingTest.hasher(root.toString()); p.hashBudget = 2000000000L;
        p.sample = sample; p.releaseIndexes = false;
        out.result = V2Analysis.run(store, p);
        store.close();
        return out;
    }

    static Map<String, String> bytesOf(Path dir) throws Exception {
        Map<String, String> m = new TreeMap<String, String>();
        if (!Files.isDirectory(dir)) return m;
        try (java.util.stream.Stream<Path> s = Files.list(dir)) {
            for (Path f : (Iterable<Path>) s.sorted()::iterator) m.put(f.getFileName().toString(), com.storageplanner.v2.util.Hex.of(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(f))));
        }
        return m;
    }

    static Set<String> pathsOfStore(V2Store s) { Set<String> l = new TreeSet<String>(); for (FileRecord f : s.files().all()) l.add(f.path); return l; }

    static void pipeline(boolean disk) throws Exception {
        String k = disk ? "disk" : "memory";
        TempTree t = TempTree.create(), w = TempTree.create();
        try {
            fixture(t);
            SampleSpec spec = new SampleSpec(20, 4);
            // expected sample, computed from the tree itself
            Set<String> expect = new TreeSet<String>();
            List<String> imgs = new ArrayList<String>(), vids = new ArrayList<String>();
            Map<String, Long> mt = new HashMap<String, Long>();
            try (java.util.stream.Stream<Path> s = Files.walk(t.root())) {
                for (Path f : (Iterable<Path>) s::iterator) {
                    if (!Files.isRegularFile(f)) continue;
                    String rel = t.root().relativize(f).toString();
                    if (rel.startsWith("Android/data")) continue;
                    mt.put(rel, Files.getLastModifiedTime(f).toMillis());
                    int kd = SampleSelector.kindOf(com.storageplanner.v2.util.Text.extension(f.getFileName().toString()));
                    if (kd == 1) imgs.add(rel); else if (kd == 2) vids.add(rel);
                }
            }
            final Map<String, Long> fm = mt;
            Comparator<String> newest = new Comparator<String>() {
                public int compare(String a, String b) { int c = Long.compare(fm.get(b), fm.get(a)); return c != 0 ? c : a.compareTo(b); }
            };
            Collections.sort(imgs, newest); Collections.sort(vids, newest);
            expect.addAll(imgs.subList(0, 20)); expect.addAll(vids.subList(0, 4));
            T.check(k + ": fixture has more than the sample and the old pair is outside it", imgs.size() > 20 && vids.size() > 4
                    && !expect.contains("Pictures/old_copy.jpg") && !expect.contains("DCIM/Camera/IMG_00.jpg")
                    && expect.contains("DCIM/Camera/IMG_29.jpg") && expect.contains("Download/IMG_29_copy.jpg"));

            // a full run alone, in its own store dir
            Run fullAlone = run(t.root(), w.dir("fullAlone"), disk, null, null);
            // the full-scan store of the "phone": a full run, then a sample run elsewhere: the full store stays byte for byte as it was
            Run full = run(t.root(), w.dir("full"), disk, null, null);
            Map<String, String> before = bytesOf(w.path("full"));
            Run smp = run(t.root(), w.dir("sample"), disk, spec, null);
            if (disk) {
                T.eq(k + ": full-scan store untouched by a sample run (" + before.size() + " files)", before, bytesOf(w.path("full")));
                T.check(k + ": the sample has its own store files", !bytesOf(w.path("sample")).isEmpty() && !bytesOf(w.path("sample")).equals(before));
            }

            T.eq(k + ": the sample store holds exactly the sample", expect, pathsOfStore(smp.store));
            boolean clean = true;
            for (FileRecord f : smp.store.files().all()) clean &= f.presence == Presence.PRESENT && f.change != ChangeState.REMOVED && f.removedScanId == Unknown.LONG;
            T.check(k + ": no removed / absent bookkeeping in the sample store", clean);
            T.eq(k + ": counters", 20L, smp.pipe.session.counter("sampleImages"));
            T.eq(k + ": counters videos", 4L, smp.pipe.session.counter("sampleVideos"));
            T.eq(k + ": added counter is the sample size", 24L, smp.pipe.session.counter("added"));
            T.check(k + ": the SAMPLE stage is recorded", smp.pipe.session.stagesDone.contains("SAMPLE"));

            Set<String> idsInSample = new HashSet<String>();
            Set<String> pathById = new HashSet<String>();
            for (FileRecord f : smp.store.files().all()) idsInSample.add(f.fileId);
            boolean allIn = true; int recCount = 0;
            for (Recommendation rc : smp.result.in.recs) { recCount++; if (rc.fileId != null && !idsInSample.contains(rc.fileId)) allIn = false; if (rc.fileId != null && rc.fileId.startsWith("cache:")) allIn = false; }
            T.check(k + ": every recommendation is about a sample file (" + recCount + " recs)", allIn && recCount > 0);
            boolean planIn = true; int items = 0;
            for (ActionPlan.PlanItem it : smp.result.made.plan.items) {
                items++;
                for (String id : it.fileIds) planIn &= idsInSample.contains(id);
                for (String id : it.requires) planIn &= idsInSample.contains(id);
                planIn &= it.subject == null || !it.subject.startsWith("cache:");
            }
            T.check(k + ": every plan item and keeper is in the sample (" + items + " items)", planIn && items > 0);
            boolean dupInSample = false;
            for (DuplicateGroup g : smp.store.duplicateGroups().all()) for (String m : g.members) { if (!idsInSample.contains(m)) planIn = false; dupInSample = true; }
            T.check(k + ": duplicate groups only hold sample files, and the pair inside the sample is found", planIn && dupInSample);
            T.check(k + ": the adapted result lists only sample files, and no apps",
                    smp.result.adapted.result.files.size() == 24 && smp.result.adapted.result.apps.isEmpty());
            for (com.storageplanner.core.FileItem f : smp.result.adapted.result.files) if (!expect.contains(f.rel)) { T.check(k + ": adapted file outside sample " + f.rel, false); break; }
            boolean noApps = true;
            for (Recommendation rc : smp.result.in.recs) noApps &= rc.proposedAction != Action.CLEAR_APP_CACHE && rc.proposedAction != Action.ARCHIVE_APP && rc.proposedAction != Action.UNINSTALL_APP;
            T.check(k + ": no app / cache recommendations in sample mode", noApps);

            T.eq(k + ": sample notice present", "Sample result: 20 newest photos and 4 newest videos only.", smp.result.sampleNote);
            T.check(k + ": default notice wording", SampleSpec.DEFAULT.notice().equals("Sample result: 1,000 newest photos and 100 newest videos only."));
            T.check(k + ": a full result carries no sample notice", full.result.sampleNote == null);
            // sanity: the full run does see files the sample leaves out
            boolean fullHasOutside = false;
            for (Recommendation rc : full.result.in.recs) if (rc.fileId != null && !idsInSample.contains(rc.fileId)) fullHasOutside = true;
            T.check(k + ": (sanity) a full run recommends files outside the sample", fullHasOutside);

            // a sample run followed by a full run = a full run alone
            String a = StreamingTest.dump(fullAlone.result.in.recs, fullAlone.result.made, fullAlone.result.adapted);
            String b = StreamingTest.dump(full.result.in.recs, full.result.made, full.result.adapted);
            T.check(k + ": full run after a sample run gives the same result as a full run alone (" + a.length() + " chars)", a.equals(b) && a.length() > 200);

            // dropping while discovery still walks (the app prunes every 10,000 records) gives the same sample
            final SampleSpec sp = spec;
            final int[] calls = {0};
            Run mid = run(t.root(), w.dir("mid"), disk, spec, new Hook() {
                public void progress(V2Store s, String stage) {
                    if ("DISCOVER".equals(stage)) { calls[0]++; try { SampleStage.prune(s, sp); } catch (Exception e) { throw new IllegalStateException(e); } }
                }
            });
            T.check(k + ": pruning during discovery (" + calls[0] + " times) selects the same sample", calls[0] > 0 && expect.equals(pathsOfStore(mid.store)));
            T.eq(k + ": same plan with mid-walk pruning",
                    StreamingTest.dump(smp.result.in.recs, smp.result.made, smp.result.adapted),
                    StreamingTest.dump(mid.result.in.recs, mid.result.made, mid.result.adapted));
            // idempotent
            T.eq(k + ": pruning again changes nothing", 0, SampleStage.prune(smp.store, spec)[2]);
        } finally { t.close(); w.close(); }
    }

    public static int run(String[] args) throws Exception {
        selector();
        T.section("sample runs");
        pipeline(true);
        pipeline(false);
        return T.finish("SampleTest");
    }
}
