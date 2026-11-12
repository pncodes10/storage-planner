package com.storageplanner.v2test;

import com.storageplanner.v2.device.*;
import com.storageplanner.v2.discovery.VolumeRegistry;
import com.storageplanner.v2.model.*;
import com.storageplanner.v2.store.MemoryStore;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class VolumeRegistryTest {
    /** StorageAccess stub reporting a configurable volume list. */
    static final class Vols implements StorageAccess {
        List<VolumeInfo> infos = new ArrayList<VolumeInfo>();
        public List<VolumeInfo> volumes() { return infos; }
        public List<AccessGrant> grants() { return new ArrayList<AccessGrant>(); }
        public DirListing list(String v, String d) { return DirListing.failed(IoStatus.UNSUPPORTED, null); }
        public FileStat stat(String v, String p) { return FileStat.failed(IoStatus.UNSUPPORTED, null); }
        public ReadableFile open(String v, String p) throws IOException { throw new IOException("no"); }
        public String dirIdentity(String v, String d) { return null; }
        public String localPath(String v, String p) { return null; }
        public String resolveLink(String v, String p) { return null; }
    }

    static VolumeInfo info(String id, VolumeKind k, String root, Tri stable) {
        VolumeInfo i = new VolumeInfo(); i.volumeId = id; i.kind = k; i.root = root; i.fileKeyStable = stable; return i;
    }

    static int events(ScanSession s, String code) { int n = 0; for (ScanEvent e : s.events) if (code.equals(e.code)) n++; return n; }

    public static int run(String[] args) throws Exception {
        T.section("VolumeRegistry");
        TempTree t = TempTree.create();
        try {
            MemoryStore store = MemoryStore.volatileStore();
            Vols sa = new Vols();
            t.dir("a"); t.dir("b");
            sa.infos.add(info("primary", VolumeKind.PRIMARY_SHARED, t.path("a").toString(), Tri.TRUE));
            sa.infos.add(info("uuid:1234-ABCD", VolumeKind.REMOVABLE, t.path("b").toString(), Tri.FALSE));
            ScanSession s = new ScanSession();
            List<Volume> vols = VolumeRegistry.refresh(store, sa, 1000, s, null);
            T.eq("two volumes", 2, vols.size());
            T.eq("sorted by id", "primary", vols.get(0).volumeId);
            T.eq("stable → ino", "ino", store.volumes().get("primary").identityScheme);
            T.eq("unstable → path", "path", store.volumes().get("uuid:1234-ABCD").identityScheme);
            T.eq("firstSeenAt", 1000L, store.volumes().get("primary").firstSeenAt);
            sa.infos.get(0).fileKeyStable = Tri.FALSE;
            VolumeRegistry.refresh(store, sa, 2000, s, null);
            T.eq("scheme kept", "ino", store.volumes().get("primary").identityScheme);
            T.eq("scheme-kept event", 1, events(s, "D_IDENTITY_SCHEME_KEPT"));
            T.eq("firstSeenAt unchanged", 1000L, store.volumes().get("primary").firstSeenAt);

            // alias de-duplication: same canonical root
            MemoryStore st2 = MemoryStore.volatileStore();
            Vols sa2 = new Vols();
            t.symlink("alias", t.path("a").toString());
            sa2.infos.add(info("dirvol", VolumeKind.DIRECTORY, t.path("alias").toString(), Tri.TRUE));
            sa2.infos.add(info("primary", VolumeKind.PRIMARY_SHARED, t.path("a").toString(), Tri.TRUE));
            sa2.infos.add(info("", VolumeKind.REMOVABLE, t.path("b").toString(), Tri.TRUE));
            ScanSession s2 = new ScanSession();
            List<Volume> v2 = VolumeRegistry.refresh(st2, sa2, 1000, s2, null);
            T.eq("alias dropped", 1, v2.size());
            T.eq("primary kept", "primary", v2.get(0).volumeId);
            T.eq("alias event", 1, events(s2, "D_VOLUME_ALIAS"));
            T.eq("invalid id event", 1, events(s2, "D_VOLUME_INVALID"));
        } finally { t.close(); }

        // unmount / remount through the pipeline
        Fx fx = Fx.create();
        try {
            fx.tree.file("DCIM/a.jpg", 100, 1); fx.tree.file("DCIM/b.jpg", 200, 2);
            fx.scan();
            T.eq("present before unmount", 2, fx.count(Presence.PRESENT));
            VolumeInfo off = new VolumeInfo(); off.volumeId = "primary"; off.mounted = false; off.kind = VolumeKind.DIRECTORY;
            fx.faulty.volumeOverrides(off);
            fx.scan();
            T.eq("files VOLUME_ABSENT", 2, fx.count(Presence.VOLUME_ABSENT));
            T.eq("absent event", 1, events(fx.session(2), "D_VOLUME_ABSENT"));
            T.eq("reason", "D_VOLUME_ABSENT", fx.f("DCIM/a.jpg").presenceReason);
            ScanSession s3 = new ScanSession();
            long puts = fx.store.changedPutsSinceOpen();
            VolumeRegistry.refresh(fx.store, fx.sa, fx.clock.nowMillis(), s3, null);
            T.eq("second refresh: no event", 0, events(s3, "D_VOLUME_ABSENT"));
            T.eq("second refresh: no writes", puts, fx.store.changedPutsSinceOpen());
            fx.store.rollback();
            fx.rebuild();
            fx.scan();
            T.eq("remount + walk → PRESENT", 2, fx.count(Presence.PRESENT));
            T.eq("returned event", 1, events(fx.session(3), "D_VOLUME_RETURNED"));
            T.eq("no REMOVED", 0, fx.count(Presence.REMOVED));
        } finally { fx.close(); }
        return T.finish("VolumeRegistryTest");
    }
}
