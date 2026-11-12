package com.storageplanner.v2test;

import com.storageplanner.v2.graph.AliasResolver;
import com.storageplanner.v2.graph.NameStem;
import com.storageplanner.v2.model.*;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** PHASE_3 12: AliasResolverTest, ExactGrouperTest, NameStemTest cases. */
public final class ExactGrouperTest {
    static byte[] rnd(int n, long seed) { byte[] b = new byte[n]; new Random(seed).nextBytes(b); return b; }
    static Volume vol(String id, VolumeKind k, String uuid) { Volume v = new Volume(); v.volumeId = id; v.kind = k; v.fsUuid = uuid; v.mounted = true; return v; }
    static FileRecord fr(String pk) { FileRecord f = new FileRecord(); f.physicalKey = pk; return f; }
    static List<Relationship> edges(Fx fx, RelType t) { List<Relationship> l = new ArrayList<Relationship>(); for (Relationship r : fx.store.relationships().all()) if (r.type == t) l.add(r); return l; }

    public static int run(String[] args) throws Exception {
        T.section("AliasResolver");
        Volume p = vol("p", VolumeKind.PRIMARY_SHARED, "u1"), s = vol("s", VolumeKind.REMOVABLE, "u2"), saf = vol("t", VolumeKind.SAF_TREE, "u3");
        T.eq("equal keys", Distinctness.SAME_FILE, AliasResolver.distinct(fr("k1"), fr("k1"), p, p));
        T.eq("different keys", Distinctness.PROVEN_DISTINCT, AliasResolver.distinct(fr("k1"), fr("k2"), p, p));
        T.eq("null key same volume", Distinctness.UNKNOWN, AliasResolver.distinct(fr(null), fr("k2"), p, p));
        T.eq("different fsUuid, non-SAF", Distinctness.PROVEN_DISTINCT, AliasResolver.distinct(fr(null), fr(null), p, s));
        T.eq("SAF + primary, keys null", Distinctness.UNKNOWN, AliasResolver.distinct(fr(null), fr(null), saf, p));

        T.section("NameStem");
        String[][] tab = {
            {"IMG_20240712_074308~2.jpg", "img_20240712_074308"}, {"PXL_20210926_103914000_mirrored.jpg", "pxl_20210926_103914000"},
            {"Statement (1).pdf", "statement"}, {"Screenshot_20210204-084544_Messages.png", "screenshot_20210204-084544_messages"},
            {"IMG_20230401_152003_1024.jpg", "img_20230401_152003"}, {"IMG_20230401_152003_edited.jpg", "img_20230401_152003"},
            {"photo - Copy.jpg", "photo"}, {"IMG_20230401_152003_1.jpg", "img_20230401_152003"}, {"IMG_1976.HEIC", "img_1976"},
            {"report_v2_final.pdf", "report_v2_final"}, {"IMG_20230401_152003_annotated (2).jpg", "img_20230401_152003"},
            {"PXL_20220101_101010_cropped.jpg", "pxl_20220101_101010"}, {"x_text_added.png", "x"},
            {"IMG-20210101-WA0001.jpg", "img-20210101-wa0001"}, {"किताब.jpg", "किताब"},
        };
        for (String[] r : tab) T.eq("stem " + r[0], r[1], NameStem.of(r[0]));
        T.check("generic temp1", NameStem.generic("temp1.pdf")); T.check("generic copy suffix", NameStem.generic("Invoice (1).pdf"));
        T.check("generic WA", NameStem.generic("IMG-20210101-WA0001.jpg")); T.check("descriptive", !NameStem.generic("Invoice_March.pdf"));

        T.section("ExactGrouper");
        try (Fx fx = Fx.create()) {
            fx.maxTier = 2; fx.graph = true;
            TempTree t = fx.tree;
            byte[] a = rnd(3000, 1);
            t.file("Download/a.bin", a); t.file("DCIM/Camera/a.bin", a); t.file("Documents/x/a.bin", a);
            t.file("Download/z1.bin", new byte[0]); t.file("Download/z2.bin", new byte[0]);
            byte[] b = rnd(2000, 2);
            t.file("Download/b.bin", b); t.file("Pictures/.trashed-123-b.bin", b);
            byte[] c = rnd(1500, 3);
            t.file("Download/c1.bin", c); Files.createLink(t.path("Download/c2.bin"), t.path("Download/c1.bin"));
            fx.scan();
            String ga = fx.f("Download/a.bin").duplicateGroupId;
            DuplicateGroup g = fx.store.duplicateGroups().get(ga);
            T.check("group a exists", g != null && g.exact && g.kind == NearGroupKind.EXACT);
            T.eq("3 members", 3, g.members.size());
            T.eq("keeper: camera beats download (and Documents/ ties by name/mtime)", true,
                    g.keeperId.equals(fx.f("DCIM/Camera/a.bin").fileId) || g.keeperId.equals(fx.f("Documents/x/a.bin").fileId));
            T.eq("group VERIFIED", Verification.VERIFIED, g.verification);
            int na = 0; for (Relationship r : edges(fx, RelType.EXACT_DUPLICATE)) if (g.members.contains(r.src)) { na++; T.eq("edge verified", Verification.VERIFIED, r.verification); }
            T.eq("3 clique edges", 3, na);
            T.eq("zero bytes: no group", null, fx.f("Download/z1.bin").duplicateGroupId);
            DuplicateGroup gb = fx.store.duplicateGroups().get(fx.f("Download/b.bin").duplicateGroupId);
            T.eq("trashed never keeper", fx.f("Download/b.bin").fileId, gb == null ? null : gb.keeperId);
            T.eq("hard link: alias, no group", null, fx.f("Download/c1.bin").duplicateGroupId);
            T.eq("total exact edges", 4, edges(fx, RelType.EXACT_DUPLICATE).size());
            int ng = fx.store.duplicateGroups().size();
            // stale hash: change b's twin -> group disappears
            long bm = t.mtime("Download/b.bin");
            t.file("Download/b.bin", rnd(2000, 9));
            t.mtime("Download/b.bin", bm + 2000);   // same size: without a new mtime the change is invisible (coarse FS clock)
            fx.scan(ScanKind.INCREMENTAL, false);
            T.eq("changed member: group gone", null, fx.store.duplicateGroups().get(gb.groupId));
            T.eq("groups after change", ng - 1, fx.store.duplicateGroups().size());
            // group of 70 -> star
            byte[] d = rnd(700, 4);
            for (int i = 0; i < 70; i++) t.file("Download/many/d" + i + ".bin", d);
            fx.scan(ScanKind.INCREMENTAL, false);
            DuplicateGroup gd = fx.store.duplicateGroups().get(fx.f("Download/many/d0.bin").duplicateGroupId);
            int star = 0; for (Relationship r : edges(fx, RelType.EXACT_DUPLICATE)) if (gd.members.contains(r.src)) { star++; T.check("star edge touches keeper", r.src.equals(gd.keeperId) || r.dst.equals(gd.keeperId)); }
            T.eq("70 members, 69 star edges", 69, star);
        }
        return T.finish("ExactGrouperTest");
    }
}
