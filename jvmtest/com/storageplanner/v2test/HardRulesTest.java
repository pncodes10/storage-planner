package com.storageplanner.v2test;

import com.storageplanner.v2.device.Availability;
import com.storageplanner.v2.model.*;
import com.storageplanner.v2.plan.PresentationPolicy;
import com.storageplanner.v2.risk.*;

import java.util.List;

/** One positive and one negative case per hard rule HC1-HC15 (PHASE_4 8, 14), plus agreement of constraints() and check(). */
public final class HardRulesTest {
    public static void main(String[] a) { System.exit(run(a) == 0 ? 0 : 1); }
    static final long MB = 1024 * 1024;
    static List<String> blocks(RiskFx x, FileRecord f, Candidate c) {
        FileContext ctx = x.ctx(f); Assessment a = Assessor.assess(ctx, PresentationPolicy.STANDARD).assessment;
        return HardRules.check(ctx, a, c);
    }
    static Candidate del(String code, Tier t, EvidenceFamily... fam) { return new Candidate(code, t, Action.DELETE, fam); }

    public static int run(String[] args) {
        RiskFx x = new RiskFx();
        T.section("HC1 protection");
        FileRecord p = x.file("Pictures/a.jpg", "pictures", "jpeg", MB); p.protection = Protection.USER_PROTECTED; x.add(p);
        FileRecord q = x.add("Pictures/b.jpg", "pictures", "jpeg", MB);
        T.check("+ protected forbids delete", blocks(x, p, del("Y_X", Tier.YOUR_CALL)).contains("P_USER_PROTECTED"));
        T.eq("+ verdict", Tier.PROTECTED, x.v(p).tier);
        T.check("- unprotected", !blocks(x, q, del("Y_X", Tier.YOUR_CALL)).contains("P_USER_PROTECTED"));

        T.section("HC2 identity");
        FileRecord gone = x.file("Pictures/c.jpg", "pictures", "jpeg", MB); gone.presence = Presence.REMOVED; x.add(gone);
        T.eq("+ removed file: no verdict", 1, x.v(gone).row);
        FileRecord h1 = x.file("Pictures/h1.jpg", "pictures", "jpeg", MB); h1.physicalKey = "same"; x.add(h1);
        FileRecord h2 = x.file("Pictures/h2.jpg", "pictures", "jpeg", MB); h2.physicalKey = "same"; x.add(h2);
        T.eq("+ alias: no verdict", 1, x.v(h2).row);
        T.check("- canonical has a verdict", x.v(h1).row != 1);

        T.section("HC3 readability");
        FileRecord ur = x.file("Download/u.bin", "download", "unknown", MB); ur.readable = Tri.FALSE; x.add(ur);
        Verdict v = x.v(ur); T.eq("+ REVIEW", Tier.REVIEW, v.tier); T.eq("+ code", "R_UNREADABLE", RiskFx.code(v));
        T.check("- readable", !blocks(x, q, del("Y_X", Tier.YOUR_CALL)).contains("R_UNREADABLE"));

        T.section("HC4 dependency");
        FileRecord prim = x.add("DCIM/Camera/IMG_9.jpg", "camera", "jpeg", MB), side = x.add("DCIM/Camera/IMG_9.xmp", "camera", "text", 300);
        x.edge(side.fileId, prim.fileId, RelType.COMPANION_OF, Verification.VERIFIED, 0.95);
        T.check("+ sidecar", blocks(x, side, del("S_TEMP_FILE", Tier.SAFE, EvidenceFamily.STRUCT)).contains("H_DEPENDENCY"));
        T.check("- primary", !blocks(x, prim, del("S_TEMP_FILE", Tier.SAFE, EvidenceFamily.STRUCT)).contains("H_DEPENDENCY"));

        T.section("HC5 last verified copy");
        T.check("+ NO_LOSS without verified copy", blocks(x, q, del("N_EXACT_DUPLICATE", Tier.NO_LOSS, EvidenceFamily.GRAPH)).contains("H_LAST_VERIFIED_COPY"));
        FileRecord k = x.add("DCIM/Camera/IMG_10.jpg", "camera", "jpeg", 2 * MB), c = x.add("Pictures/IMG_10.jpg", "pictures", "jpeg", 2 * MB);
        x.exact(k, c, true);
        T.check("- verified twin kept", !blocks(x, c, del("N_EXACT_DUPLICATE", Tier.NO_LOSS, EvidenceFamily.GRAPH)).contains("H_LAST_VERIFIED_COPY"));
        T.check("- S_ rule", !blocks(x, q, del("S_TRASHED", Tier.SAFE, EvidenceFamily.STRUCT)).contains("H_LAST_VERIFIED_COPY"));

        T.section("HC6 similarity");
        Candidate sim = del("Y_X", Tier.NO_LOSS, EvidenceFamily.GRAPH); sim.cites.add(RelType.COMPRESSED_FROM);
        T.check("+ similar at NO_LOSS", blocks(x, c, sim).contains("H_SIMILAR_NOT_IDENTICAL"));
        Candidate simY = del("Y_X", Tier.YOUR_CALL, EvidenceFamily.GRAPH); simY.cites.add(RelType.COMPRESSED_FROM);
        T.check("- similar at YOUR_CALL", !blocks(x, c, simY).contains("H_SIMILAR_NOT_IDENTICAL"));

        T.section("HC7 classification / age only");
        T.check("+ CONTENT only", blocks(x, q, del("Y_X", Tier.YOUR_CALL, EvidenceFamily.CONTENT)).contains("H_CLASSIFICATION_ONLY"));
        T.check("+ TIME only", blocks(x, q, del("Y_X", Tier.YOUR_CALL, EvidenceFamily.TIME, EvidenceFamily.STRUCT)).contains("H_CLASSIFICATION_ONLY"));
        T.check("+ CONTENT cited at SAFE", blocks(x, q, del("S_X", Tier.SAFE, EvidenceFamily.CONTENT, EvidenceFamily.GRAPH)).contains("H_CLASSIFICATION_ONLY"));
        T.check("- CONTENT + GRAPH at YOUR_CALL", !blocks(x, q, del("Y_X", Tier.YOUR_CALL, EvidenceFamily.CONTENT, EvidenceFamily.GRAPH)).contains("H_CLASSIFICATION_ONLY"));

        T.section("HC8 unknown features");
        FileRecord part = x.file("Download/v.mp4", "download", "mp4", 10 * MB); part.extractionStatus = ExtractionStatus.PARTIAL; x.add(part);
        T.check("+ unknown at NO_LOSS (non-exact)", blocks(x, part, del("N_INSTALLER_INSTALLED", Tier.NO_LOSS, EvidenceFamily.APP)).contains("H_UNKNOWN_FEATURES"));
        Candidate lq = del("Y_LOW_QUALITY", Tier.YOUR_CALL, EvidenceFamily.PIXEL); lq.needsFeatures = true;
        T.check("+ YOUR_CALL needing the feature", blocks(x, part, lq).contains("H_UNKNOWN_FEATURES"));
        Candidate lossy = new Candidate("Y_X", Tier.YOUR_CALL, Action.COMPRESS); lossy.p("transform", "lossy");
        T.check("+ lossy compress", blocks(x, part, lossy).contains("H_UNKNOWN_FEATURES"));
        T.check("- exact rule exempt", !blocks(x, part, del("N_EXACT_DUPLICATE", Tier.NO_LOSS, EvidenceFamily.GRAPH)).contains("H_UNKNOWN_FEATURES"));
        T.check("- known features", !blocks(x, q, lq).contains("H_UNKNOWN_FEATURES"));

        T.section("HC9 integrity");
        FileRecord tr = x.file("Documents/t.pdf", "document", "pdf", 5000); tr.integrity = Integrity.TRUNCATED; x.add(tr);
        v = x.v(tr); T.eq("+ truncated -> REVIEW", Tier.REVIEW, v.tier); T.eq("+ code", "R_UNREADABLE", RiskFx.code(v));
        FileRecord unk = x.file("DCIM/Camera/u.jpg", "camera", "jpeg", MB); unk.integrity = Integrity.UNKNOWN; x.add(unk);
        T.check("- integrity UNKNOWN is not damage", x.v(unk).tier != Tier.REVIEW);
        T.check("- S_ exempt", !blocks(x, tr, del("S_TEMP_FILE", Tier.SAFE, EvidenceFamily.STRUCT)).contains("R_UNREADABLE"));

        T.section("HC10 app visibility");
        RiskFx y = new RiskFx(); y.avail = Availability.PARTIAL;
        FileRecord res = y.add("Android/data/com.old/files/x.dat", "residue", "unknown", MB);
        v = y.v(res); T.check("+ leftover not SAFE while apps PARTIAL", v.tier != Tier.SAFE); T.eq("+ fallback", "R_UNKNOWN_DEPENDENCY", RiskFx.code(v));
        RiskFx z = new RiskFx(); FileRecord res2 = z.add("Android/data/com.old/files/x.dat", "residue", "unknown", MB);
        T.eq("- apps FULL", "S_UNINSTALLED_LEFTOVER", RiskFx.code(z.v(res2)));

        T.section("HC11 installers not installed");
        FileRecord ni = x.file("Download/n.apk", "download", "apk", MB); x.apk(ni, "com.gone", InstallState.UNKNOWN, Tri.UNKNOWN); x.add(ni);
        T.check("+ unknown install state", blocks(x, ni, del("Y_X", Tier.YOUR_CALL, EvidenceFamily.APP)).contains("R_INSTALLER_NOT_INSTALLED"));
        T.eq("+ REVIEW aggressive too", Tier.REVIEW, x.v(ni, PresentationPolicy.AGGRESSIVE).tier);
        FileRecord ok = x.file("Download/o.apk", "download", "apk", MB); x.apk(ok, "com.inst", InstallState.SAME_VERSION, Tri.TRUE); x.add(ok);
        T.check("- installed", !blocks(x, ok, del("N_INSTALLER_INSTALLED", Tier.NO_LOSS, EvidenceFamily.APP)).contains("R_INSTALLER_NOT_INSTALLED"));

        T.section("HC12 personal capture quality");
        FileRecord cam = x.add("DCIM/Camera/IMG_11.jpg", "camera", "jpeg", MB);
        T.check("+ pixel evidence on camera photo", blocks(x, cam, del("Y_BLURRY", Tier.YOUR_CALL, EvidenceFamily.PIXEL)).contains("H_CLASSIFICATION_ONLY"));
        T.check("- nothing-visible rule", !blocks(x, cam, del("Y_LOW_QUALITY", Tier.YOUR_CALL, EvidenceFamily.PIXEL)).contains("H_CLASSIFICATION_ONLY"));

        T.section("HC13 keepers");
        T.check("+ keeper", blocks(x, k, del("S_THUMBNAIL", Tier.SAFE, EvidenceFamily.STRUCT)).contains("H_LAST_VERIFIED_COPY"));
        T.check("+ keeper transform", blocks(x, k, new Candidate("N_LOSSLESS_SMALLER", Tier.NO_LOSS, Action.COMPRESS, EvidenceFamily.STRUCT)).contains("H_LAST_VERIFIED_COPY"));
        T.check("- non-keeper", !blocks(x, c, del("S_THUMBNAIL", Tier.SAFE, EvidenceFamily.STRUCT)).contains("H_LAST_VERIFIED_COPY"));

        T.section("HC14 obb / app data");
        FileRecord ob = x.add("Android/obb/com.inst/main.obb", "app_obb", "unknown", MB);
        T.check("+ obb", blocks(x, ob, del("S_APP_CACHE_FILE", Tier.SAFE, EvidenceFamily.STRUCT)).contains("H_DEPENDENCY"));
        FileRecord ad = x.add("Android/data/com.inst/files/a.dat", "app_media", "unknown", MB);
        T.check("+ installed app files", blocks(x, ad, del("S_APP_CACHE_FILE", Tier.SAFE, EvidenceFamily.STRUCT)).contains("H_DEPENDENCY"));
        FileRecord ac = x.add("Android/data/com.inst/cache/a.tmp", "ext_cache", "unknown", MB);
        T.check("- app cache dir", !blocks(x, ac, del("S_APP_CACHE_FILE", Tier.SAFE, EvidenceFamily.STRUCT)).contains("H_DEPENDENCY"));

        T.section("HC15 confidence floors");
        FileRecord ka = x.add("DCIM/Camera/IMG_12.jpg", "camera", "jpeg", 2 * MB), ca = x.add("Pictures/IMG_12.jpg", "pictures", "jpeg", 2 * MB);
        x.exact(ka, ca, true);
        T.eq("- exact rule reaches NO_LOSS (structural confidence 1.0)", Tier.NO_LOSS, x.v(ca).tier);
        FileRecord ce = x.file("Pictures/IMG_12b.jpg", "pictures", "jpeg", 2 * MB); ce.extractionConfidence = 0.3; x.add(ce);
        x.exact(ka, ce, true);
        T.eq("- low extraction confidence does not block the exact rule (F-07)", Tier.NO_LOSS, x.v(ce).tier);

        T.section("constraints vs check agree");
        for (FileRecord f : new FileRecord[]{p, ur, side, tr, ni, k, ob}) {
            FileContext ctx = x.ctx(f); Constraint con = HardRules.constraints(ctx);
            Assessment a = Assessor.assess(ctx, PresentationPolicy.STANDARD).assessment;
            List<String> b = HardRules.check(ctx, a, del("Y_X", Tier.YOUR_CALL, EvidenceFamily.GRAPH, EvidenceFamily.PROV));
            for (String s : con.blocks) if (!s.equals("H_UNKNOWN_FEATURES")) T.check(f.path + " " + s + " in check", b.contains(s));
        }
        return T.finish("HardRulesTest");
    }
}
