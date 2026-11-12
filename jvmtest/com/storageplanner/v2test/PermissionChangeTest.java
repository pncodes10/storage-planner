package com.storageplanner.v2test;

import com.storageplanner.v2.model.*;
import com.storageplanner.v2.pipeline.PipelineResult;

import java.util.*;

public final class PermissionChangeTest {
    static boolean hasInacc(CoverageReport c, String vol, String path, InaccessReason r) {
        for (InaccessibleLocation l : c.inaccessible) if (l.volumeId.equals(vol) && l.path.equals(path) && l.reason == r) return true;
        return false;
    }

    public static int run(String[] args) throws Exception {
        T.section("revoked between scans");
        try (Fx fx = Fx.create()) {
            fx.tree.file("DCIM/p.jpg", 10, 1);
            // A SAF tree on "Documents" is its own pseudo-volume (PHASE_1 1): revoking it removes the only grant covering it.
            TempTree docs = fx.addRoot("saf:docs", VolumeKind.SAF_TREE, AccessMode.C_SAF_TREE);
            docs.file("a.pdf", 10, 2); docs.file("sub/b.pdf", 10, 3);
            fx.scan();
            T.eq("SAF files present", 2, countVol(fx, "saf:docs", Presence.PRESENT));
            AccessGrant b = fx.grants.get(0);
            fx.faulty.setGrants(Collections.singletonList(b));
            PipelineResult r = fx.scan();
            T.check("D_GRANT_REVOKED", Fx.hasEvent(r.session, "D_GRANT_REVOKED"));
            DirRecord root = fx.store.directories().get("saf:docs", "");
            T.eq("Documents NOT_GRANTED", DirStatus.NOT_GRANTED, root.status);
            FileRecord a = fx.store.files().byPath("saf:docs", "a.pdf");
            T.eq("files UNVERIFIABLE", 2, countVol(fx, "saf:docs", Presence.UNVERIFIABLE));
            T.eq("reason D_PERMISSION_REVOKED", "D_PERMISSION_REVOKED", a.presenceReason);
            T.eq("none REMOVED", 0, fx.count(Presence.REMOVED));
            T.check("coverage PERMISSION_DENIED (revoked)", hasInacc(r.coverage, "saf:docs", "", InaccessReason.PERMISSION_DENIED));
            T.eq("area of SAF file", "user_other", a.area);
            fx.faulty.setGrants(null);
            r = fx.scan();
            T.check("D_GRANT_RESTORED", Fx.hasEvent(r.session, "D_GRANT_RESTORED"));
            T.eq("files PRESENT again", 2, countVol(fx, "saf:docs", Presence.PRESENT));
        }

        T.section("revoked during scan");
        try (Fx fx = Fx.create()) {
            fx.tree.file("p.jpg", 10, 1);
            TempTree docs = fx.addRoot("saf:docs", VolumeKind.SAF_TREE, AccessMode.C_SAF_TREE);
            docs.file("x/a.pdf", 10, 2); docs.file("y/b.pdf", 10, 3); docs.file("c.pdf", 10, 4);
            fx.scan();
            int primaryCalls = 1;   // primary has only its root directory
            fx.faulty.revokeAfterListCalls(fx.faulty.listCalls() + primaryCalls + 1, "C_SAF_TREE:saf:docs:");
            PipelineResult r = fx.scan();
            T.check("D_GRANTS_CHANGED_DURING_SCAN", Fx.hasEvent(r.session, "D_GRANTS_CHANGED_DURING_SCAN"));
            T.check("coverage grantsChangedDuringScan", r.coverage.grantsChangedDuringScan);
            T.check("affected files UNVERIFIABLE", countVol(fx, "saf:docs", Presence.UNVERIFIABLE) >= 2);
            T.eq("none REMOVED", 0, fx.count(Presence.REMOVED));
            boolean revokedReason = true;
            for (FileRecord f : fx.store.files().byVolume("saf:docs"))
                if (f.presence == Presence.UNVERIFIABLE) revokedReason &= "D_PERMISSION_REVOKED".equals(f.presenceReason);
            T.check("reason D_PERMISSION_REVOKED", revokedReason);
        }

        T.section("Mode A after Mode B (F-M2)");
        try (Fx fx = Fx.create()) {
            fx.tree.file("DCIM/photo.jpg", 10, 1); fx.tree.file("Download/doc.pdf", 10, 2); fx.tree.file("Download/app.apk", 10, 3);
            fx.tree.file("DCIM/hidden.jpg", 10, 4); fx.tree.file("DCIM/deleted.jpg", 10, 5);
            fx.scan();
            fx.tree.delete("DCIM/deleted.jpg");
            fx.sa = new MediaOnlyStorageAccess(fx.posix, "image", "video", "audio").hide("DCIM/hidden.jpg");
            PipelineResult r = fx.scan();
            T.eq("pdf UNVERIFIABLE", Presence.UNVERIFIABLE, fx.f("Download/doc.pdf").presence);
            T.eq("pdf reason", "D_MEDIA_ONLY_VISIBILITY", fx.f("Download/doc.pdf").presenceReason);
            T.eq("apk UNVERIFIABLE", Presence.UNVERIFIABLE, fx.f("Download/app.apk").presence);
            T.eq("hidden jpg UNVERIFIABLE", Presence.UNVERIFIABLE, fx.f("DCIM/hidden.jpg").presence);
            T.eq("deleted jpg UNVERIFIABLE, not REMOVED", Presence.UNVERIFIABLE, fx.f("DCIM/deleted.jpg").presence);
            T.eq("visible jpg PRESENT", Presence.PRESENT, fx.f("DCIM/photo.jpg").presence);
            T.eq("none REMOVED", 0, fx.count(Presence.REMOVED));
            T.check("coverage MEDIA_ONLY_VISIBILITY", hasInacc(r.coverage, "primary", "", InaccessReason.MEDIA_ONLY_VISIBILITY));
            T.check("grant events", Fx.hasEvent(r.session, "D_GRANT_ADDED") && Fx.hasEvent(r.session, "D_GRANT_REVOKED"));
        }

        T.section("owner package (F-M3)");
        try (Fx fx = Fx.create()) {
            fx.tree.file("Android/media/com.foo/x.jpg", 10, 1);
            fx.scan();
            T.eq("installed unknown → app_owner_unknown", "app_owner_unknown", fx.f("Android/media/com.foo/x.jpg").area);
            T.eq("owner kept", "com.foo", fx.f("Android/media/com.foo/x.jpg").ownerPackage);
            fx.installed = new TreeSet<String>(Collections.singletonList("com.bar"));
            fx.scan();
            T.eq("FULL set lacking pkg → residue", "residue", fx.f("Android/media/com.foo/x.jpg").area);
            fx.installed = new TreeSet<String>(Arrays.asList("com.bar", "com.foo"));
            fx.scan();
            T.eq("package appears → re-derived", "app_media", fx.f("Android/media/com.foo/x.jpg").area);
            fx.installed = null;
            fx.scan();
            T.eq("unknown again → never residue", "app_owner_unknown", fx.f("Android/media/com.foo/x.jpg").area);
        }
        return T.finish("PermissionChangeTest");
    }

    static int countVol(Fx fx, String vol, Presence p) {
        int n = 0;
        for (FileRecord f : fx.store.files().byVolume(vol)) if (f.presence == p) n++;
        return n;
    }
}
