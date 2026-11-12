package com.storageplanner.v2test;

import com.storageplanner.v2.device.VolumeInfo;
import com.storageplanner.v2.discovery.CoverageBuilder;
import com.storageplanner.v2.model.*;
import com.storageplanner.v2.pipeline.PipelineResult;

import java.util.ArrayList;
import java.util.Arrays;

public final class CoverageTest {
    static boolean has(CoverageReport c, String vol, String path, InaccessReason r) {
        for (InaccessibleLocation l : c.inaccessible) if (l.volumeId.equals(vol) && l.path.equals(path) && l.reason == r) return true;
        return false;
    }

    public static int run(String[] args) throws Exception {
        T.section("Coverage");
        try (Fx fx = Fx.create()) {
            fx.roots.get(0).restrictedPrefixes = new ArrayList<String>(Arrays.asList("Android/data", "Android/obb", "Never/there"));
            fx.rebuild();
            fx.tree.file("DCIM/Camera/a.jpg", 1000, 1); fx.tree.file("Download/b.pdf", 2000, 2); fx.tree.file("WhatsApp/Media/WhatsApp Images/c.jpg", 300, 3);
            fx.tree.file("Android/data/com.x/d.bin", 50, 4); fx.tree.file("Android/obb/com.x/e.obb", 60, 5);
            fx.tree.file("d1/x.txt", 1, 6); fx.tree.file("d2/y.txt", 1, 7); fx.tree.file("ex/z.txt", 1, 8);
            fx.tree.file("l1/l2/l3/deep.txt", 1, 9);
            fx.scan();
            fx.faulty.denyList("d1"); fx.faulty.ioErrorList("d2");
            fx.lim.excluded.add("ex");
            fx.lim.maxDepth = 2;
            PipelineResult r = fx.scan();
            long scan = r.session.scanId;
            CoverageReport full = CoverageBuilder.build(fx.store, scan, 1000);
            long areaBytes = 0, disc = 0;
            for (AreaCoverage a : full.areas) areaBytes += a.bytes;
            for (VolumeCoverage v : full.volumes) disc += v.discoveredBytes;
            T.eq("Σ areas.bytes == Σ discoveredBytes", disc, areaBytes);
            T.check("areas from core.Classifier", areaNames(full).containsAll(Arrays.asList("camera", "download")));
            int nonOk = 0, missing = 0;
            for (DirRecord d : fx.store.directories().all()) {
                if (d.lastVisitedScanId != scan || d.status == DirStatus.OK || d.status == DirStatus.NOT_FOUND) continue;
                nonOk++;
                boolean found = false;
                for (InaccessibleLocation l : full.inaccessible) if (l.path.equals(d.path)) found = true;
                if (!found) missing++;
            }
            T.check("several non-OK dirs", nonOk >= 5);
            T.eq("every non-OK dir listed", 0, missing);
            T.check("restricted listed even if absent on disk", has(full, "primary", "Never/there", InaccessReason.OS_RESTRICTED));
            T.check("read error listed", has(full, "primary", "d2", InaccessReason.READ_ERROR));
            T.check("known files under denied dir", knownFiles(full, "d1") == 1);
            CoverageReport capped = CoverageBuilder.build(fx.store, scan, 5);
            T.eq("cap 5 listed", 5, capped.inaccessible.size());
            T.eq("inaccessibleTotal correct", full.inaccessibleTotal, capped.inaccessibleTotal);
            T.check("total beyond cap", capped.inaccessibleTotal > 5);
            VolumeCoverage v = full.volumes.get(0);
            T.eq("DIRECTORY totals unknown", -1L, v.totalBytes);
            T.eq("unaccounted unknown", -1L, v.unaccountedBytes);
            T.check("ratio unknown", Double.isNaN(v.coverageRatio));
        }
        try (Fx fx = Fx.create()) {
            fx.tree.file("a.bin", 1234, 1);
            VolumeInfo o = new VolumeInfo(); o.volumeId = "primary"; o.kind = VolumeKind.PRIMARY_SHARED;
            o.totalBytes = 10L * 1000 * 1000 * 1000; o.freeBytes = 4L * 1000 * 1000 * 1000;
            fx.faulty.volumeOverrides(o);
            TempTree card = fx.addRoot("uuid:CARD-1", VolumeKind.REMOVABLE, AccessMode.B_ALL_FILES);
            card.file("x.jpg", 10, 2);
            fx.faulty.volumeOverrides(o);
            fx.scan();
            VolumeInfo gone = new VolumeInfo(); gone.volumeId = "uuid:CARD-1"; gone.mounted = false;
            fx.faulty.volumeOverrides(gone);
            PipelineResult r = fx.scan();
            VolumeCoverage pv = null;
            for (VolumeCoverage c : r.coverage.volumes) if (c.volumeId.equals("primary")) pv = c;
            T.eq("used = total - free", 6L * 1000 * 1000 * 1000, pv.usedBytes);
            T.eq("unaccounted = used - discovered", 6L * 1000 * 1000 * 1000 - 1234, pv.unaccountedBytes);
            T.check("ratio known", !Double.isNaN(pv.coverageRatio));
            T.check("unmounted volume → VOLUME_ABSENT", has(r.coverage, "uuid:CARD-1", "", InaccessReason.VOLUME_ABSENT));
            T.eq("its file VOLUME_ABSENT", Presence.VOLUME_ABSENT, fx.store.files().byPath("uuid:CARD-1", "x.jpg").presence);
        }
        return T.finish("CoverageTest");
    }

    static java.util.Set<String> areaNames(CoverageReport c) { java.util.Set<String> s = new java.util.HashSet<String>(); for (AreaCoverage a : c.areas) s.add(a.area); return s; }
    static long knownFiles(CoverageReport c, String path) { for (InaccessibleLocation l : c.inaccessible) if (l.path.equals(path)) return l.knownFiles; return -2; }
}
