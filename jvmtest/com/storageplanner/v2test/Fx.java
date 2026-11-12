package com.storageplanner.v2test;

import com.storageplanner.v2.device.BlobStore;
import com.storageplanner.v2.device.StorageAccess;
import com.storageplanner.v2.discovery.DiscoveryEngine;
import com.storageplanner.v2.discovery.PosixStorageAccess;
import com.storageplanner.v2.discovery.WalkLimits;
import com.storageplanner.v2.model.*;
import com.storageplanner.v2.pipeline.Pipeline;
import com.storageplanner.v2.pipeline.PipelineResult;
import com.storageplanner.v2.stage.PipelineConfig;
import com.storageplanner.v2.stage.Stage;
import com.storageplanner.v2.store.Codecs;
import com.storageplanner.v2.store.MemoryStore;
import com.storageplanner.v2.store.StoreException;
import com.storageplanner.v2.store.V2Store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Test fixture: a TempTree as volume "primary" (DIRECTORY, ino scheme, B grant), a store and a pipeline runner. */
public final class Fx implements AutoCloseable {
    public final TempTree tree;
    public final FixedClock clock = new FixedClock();
    public final List<PosixStorageAccess.Root> roots = new ArrayList<PosixStorageAccess.Root>();
    public final List<AccessGrant> grants = new ArrayList<AccessGrant>();
    public PosixStorageAccess posix;
    public FaultyStorageAccess faulty;
    /** What the pipeline uses; defaults to faulty. */
    public StorageAccess sa;
    public MemoryStore store = MemoryStore.volatileStore();
    public BlobStore blobs;
    public WalkLimits lim = new WalkLimits();
    public Set<String> installed;
    public TestControl control;
    /** Phase 2: extraction tiers run by scan() (0 = discovery only), hash policy, installed apps, decoder, budget. */
    public int maxTier = 0;
    public boolean graph;
    public com.storageplanner.v2.graph.GraphStage graphStage = new com.storageplanner.v2.graph.GraphStage();
    public boolean hashAll;
    public com.storageplanner.v2.device.InstalledApps apps;
    public com.storageplanner.v2.device.MediaDecoder media;
    public com.storageplanner.v2.stage.Budget budget;
    private final List<TempTree> extra = new ArrayList<TempTree>();

    public static Fx create() throws IOException { return new Fx(TempTree.create(), Tri.TRUE); }
    public static Fx create(Tri fileKeyStable) throws IOException { return new Fx(TempTree.create(), fileKeyStable); }

    public Fx(TempTree tree, Tri fileKeyStable) {
        this.tree = tree;
        PosixStorageAccess.Root r = new PosixStorageAccess.Root();
        r.volumeId = "primary"; r.kind = VolumeKind.DIRECTORY; r.label = "primary"; r.path = tree.root().toString();
        r.fileKeyStable = fileKeyStable; r.restrictedPrefixes = new ArrayList<String>(Arrays.asList("Android/data", "Android/obb"));
        roots.add(r);
        grants.add(AccessGrant.readAll("primary", "", AccessMode.B_ALL_FILES));
        rebuild();
    }

    /** Adds a second root (e.g. a SAF tree) in its own temp dir; returns its tree. */
    public TempTree addRoot(String volumeId, VolumeKind kind, AccessMode mode) throws IOException {
        TempTree t = TempTree.create();
        extra.add(t);
        PosixStorageAccess.Root r = new PosixStorageAccess.Root();
        r.volumeId = volumeId; r.kind = kind; r.label = volumeId; r.path = t.root().toString(); r.fileKeyStable = Tri.TRUE;
        r.restrictedPrefixes = new ArrayList<String>();
        roots.add(r);
        grants.add(AccessGrant.readAll(volumeId, "", mode));
        rebuild();
        return t;
    }

    /** Re-creates the storage stack (fault rules are lost). */
    public void rebuild() {
        posix = new PosixStorageAccess(roots, grants);
        faulty = new FaultyStorageAccess(posix);
        sa = faulty;
    }

    public PipelineResult scan() throws StoreException { return scan(ScanKind.FULL, false); }
    public PipelineResult scan(ScanKind kind, boolean resume) throws StoreException {
        PipelineConfig cfg = new PipelineConfig();
        cfg.kind = kind; cfg.resume = resume;
        List<Stage> stages = new ArrayList<Stage>();
        stages.add(new DiscoveryEngine(lim, installed));
        cfg.maxTier = maxTier;
        if (budget != null) cfg.budget = budget;
        if (maxTier >= 1) stages.add(new com.storageplanner.v2.features.T1MetadataStage());
        if (maxTier >= 2) stages.add(new com.storageplanner.v2.features.T2HashStructStage(hashAll));
        if (maxTier >= 3) stages.add(new com.storageplanner.v2.features.T3ImageStage());
        if (graph) stages.add(graphStage);
        return new Pipeline(new JvmDevice(sa, clock, new JvmResourceMonitor(), blobs, media, apps), store, cfg, stages).run(control);
    }

    public FileRecord f(String path) { return store.files().byPath("primary", path); }
    public DirRecord d(String path) { return store.directories().get("primary", path); }
    public List<FileRecord> files() { List<FileRecord> l = new ArrayList<FileRecord>(); for (FileRecord r : store.files().all()) l.add(r); return l; }
    public int count(Presence p) { int n = 0; for (FileRecord r : store.files().all()) if (r.presence == p) n++; return n; }
    public int changed(long scanId, ChangeState c) { int n = 0; for (FileRecord r : store.files().all()) if (r.changeScanId == scanId && r.change == c) n++; return n; }
    public ScanSession session(long id) { return store.scanSessions().get(ScanSession.keyOf(id)); }

    public static boolean hasEvent(ScanSession s, String code) { for (ScanEvent e : s.events) if (code.equals(e.code)) return true; return false; }

    public static long inode(Path p) throws IOException {
        Object k = Files.readAttributes(p, BasicFileAttributes.class, java.nio.file.LinkOption.NOFOLLOW_LINKS).fileKey();
        Matcher m = Pattern.compile("ino=(\\d+)").matcher(String.valueOf(k));
        return m.find() ? Long.parseLong(m.group(1)) : -1;
    }

    /** Files and directories tables as Codecs JSON lines. */
    public static String dumpFilesDirs(V2Store s) {
        StringBuilder b = new StringBuilder();
        for (FileRecord r : s.files().all()) b.append(Codecs.json(Codecs.FILE, r)).append('\n');
        for (DirRecord r : s.directories().all()) b.append(Codecs.json(Codecs.DIR, r)).append('\n');
        return b.toString();
    }

    public void close() throws IOException { tree.close(); for (TempTree t : extra) t.close(); }
}
