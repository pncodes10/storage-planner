package com.storageplanner.v2test;

import com.storageplanner.v2.model.*;
import com.storageplanner.v2.plan.PresentationPolicy;
import com.storageplanner.v2.risk.*;

/** U, L, R, C, I, B and expected loss (PHASE_4 3-7, 14: UtilityEstimatorTest ... ExpectedLossTest in one suite). */
public final class EstimatorsTest {
    public static void main(String[] a) { System.exit(run(a) == 0 ? 0 : 1); }
    static final long MB = 1024 * 1024;
    static Assessment as(RiskFx x, FileRecord f) { return x.v(f).assessment; }
    static boolean close(double a, double b) { return Math.abs(a - b) < 1e-9; }

    public static int run(String[] args) {
        RiskFx x = new RiskFx();
        T.section("U");
        FileRecord th = x.add("DCIM/.thumbnails/1.jpg", "thumbnails", "jpeg", 20000);
        Assessment a = as(x, th); T.check("lambda 0 -> U = 0", a.u90.high == 0 && a.u90.mid == 0);
        FileRecord sNew = x.file("Pictures/Screenshots/s1.png", "screenshot", "png", MB); sNew.createdAt = RiskFx.NOW - 5 * RiskFx.DAY; sNew.createdAtSource = TimeSource.EXIF; x.add(sNew);
        FileRecord sOld = x.file("Pictures/Screenshots/s2.png", "screenshot", "png", MB); sOld.createdAt = RiskFx.NOW - 900 * RiskFx.DAY; x.add(sOld);
        Assessment an = as(x, sNew), ao = as(x, sOld);
        T.check("age decays screenshot mid", ao.u90.mid < an.u90.mid);
        T.check("age never moves high (H10)", close(ao.u90.high, an.u90.high));
        FileRecord dNew = x.file("Documents/Bank/Statement_2025.pdf", "document", "pdf", 1000); dNew.createdAt = RiskFx.NOW - 5 * RiskFx.DAY; x.add(dNew);
        FileRecord dOld = x.file("Documents/Bank/Statement_2015.pdf", "document", "pdf", 1001); dOld.createdAt = RiskFx.NOW - 3650 * RiskFx.DAY; x.add(dOld);
        T.check("10-year statement same U as new", close(as(x, dOld).u90.high, as(x, dNew).u90.high) && close(as(x, dOld).u90.mid, as(x, dNew).u90.mid));
        T.check("U in [0,1), ordered", an.u1095.high < 1 && an.u30.low <= an.u30.mid && an.u30.mid <= an.u30.high);
        FileRecord part = x.file("Pictures/p.jpg", "pictures", "jpeg", MB); part.extractionStatus = ExtractionStatus.PARTIAL; x.add(part);
        FileRecord full = x.add("Pictures/q.jpg", "pictures", "jpeg", MB + 1);
        T.check("unknown widens U.high", as(x, part).u90.high > as(x, full).u90.high);
        T.eq("usage class camera", UsageClass.CAMERA_PHOTO, as(x, x.add("DCIM/Camera/c.jpg", "camera", "jpeg", 3 * MB)).usage);
        T.eq("usage class record", UsageClass.DOCUMENT_RECORD, as(x, dNew).usage);
        T.eq("usage class temp", UsageClass.TEMP, as(x, x.add("Download/a.crdownload", "temp", "unknown", 5000)).usage);
        T.eq("usage class temp over complete pdf (F-05)", UsageClass.DOCUMENT, as(x, x.add("Download/x.tmp", "temp", "pdf", 5001)).usage);

        T.section("L");
        FileRecord plain = x.add("Documents/notes.pdf", "document", "pdf", 7000);
        T.check("financial keyword raises L", as(x, dNew).lossRange.high > as(x, plain).lossRange.high);
        T.check("unknown content -> importance high >= 0.9", as(x, part).lossRange.high >= 0.9 * (0.4 + 0.6 * 0.6) - 1e-9);
        T.eq("0-byte -> L = 0", 0.0, as(x, x.add("Download/e.txt", "download", "text", 0)).lossRange.high);
        FileRecord k = x.add("DCIM/Camera/k.jpg", "camera", "jpeg", 2 * MB), c = x.file("Pictures/k.jpg", "pictures", "jpeg", 2 * MB);
        x.add(c);
        Assessment before = as(x, c);
        x.exact(k, c, true);
        Assessment after = as(x, c);
        T.check("verified twin changes rv", after.rvPess == 1 && before.rvPess == 0);
        T.check("verified twin leaves lossRange unchanged (double-count guard)", close(before.lossRange.high, after.lossRange.high) && close(before.lossRange.mid, after.lossRange.mid));

        T.section("R");
        T.eq("row 1 regenerable", "REGENERABLE_CACHE", as(x, th).recoveryKind);
        FileRecord apk = x.file("Download/m.apk", "download", "apk", MB); x.apk(apk, "com.inst", InstallState.SAME_VERSION, Tri.TRUE); x.add(apk);
        T.eq("row 2 installer", "REGENERABLE_INSTALLER", as(x, apk).recoveryKind);
        T.eq("row 3 exact copy kept", "EXACT_COPY_KEPT", after.recoveryKind);
        T.check("keeper does not count its own group", !"EXACT_COPY_KEPT".equals(as(x, k).recoveryKind));
        FileRecord zip = x.add("Backups/b.zip", "user_other", "zip", 3 * MB), sub = x.add("DCIM/Camera/s.jpg", "camera", "jpeg", 1234567);
        x.backup(sub, zip, "s.jpg", BackupState.VERIFIED);
        T.eq("row 4 verified backup", "BACKUP_VERIFIED", as(x, sub).recoveryKind);
        FileRecord sub2 = x.add("DCIM/Camera/s2.jpg", "camera", "jpeg", 1234568);
        x.backup(sub2, zip, "s2.jpg", BackupState.MISSING);
        T.check("MISSING backup never VERIFIED", as(x, sub2).recoverability != Recoverability.VERIFIED_BACKUP);
        FileRecord o = x.add("DCIM/Camera/o.jpg", "camera", "jpeg", 5 * MB), w = x.add("WhatsApp/Media/WhatsApp Images/IMG-20200101-WA0001.jpg", "wa_image", "jpeg", 200000);
        x.ce(o, w, RelType.COMPRESSED_FROM, 0.9);
        a = as(x, w); T.eq("row 5 superset copy", "SUPERSET_COPY", a.recoveryKind); T.check("rvPess 0.5 for strong edge", close(a.rvPess, 0.5));
        FileRecord u1 = x.add("DCIM/Camera/u1.jpg", "camera", "jpeg", 777777), u2 = x.add("Pictures/u1.jpg", "pictures", "jpeg", 777777);
        x.exact(u1, u2, false);
        T.eq("row 6 G2-unknown exact -> POSSIBLE", Recoverability.POSSIBLE_BACKUP, as(x, u2).recoverability);
        FileRecord st1 = x.add("DCIM/Camera/st1.jpg", "camera", "jpeg", 888888), st2 = x.add("Pictures/st1.jpg", "pictures", "jpeg", 888888);
        Relationship e = x.edge(st1.fileId, st2.fileId, RelType.EXACT_DUPLICATE, Verification.VERIFIED, 1.0); e.srcFp = new Fingerprint(1, 1, 1);
        x.group(NearGroupKind.EXACT, st1, st2);
        T.check("stale edge ignored", !"EXACT_COPY_KEPT".equals(as(x, st2).recoveryKind));
        T.eq("row 8 unique, complete", Recoverability.NO_VERIFIED_BACKUP, as(x, plain).recoverability);
        FileRecord ns = x.file("Pictures/nohash.jpg", "pictures", "jpeg", 3 * MB); ns.sha256 = null; x.add(ns);
        x.add(x.file("Pictures/nohash2.jpg", "pictures", "jpeg", 3 * MB));
        T.eq("row 9 hash pending -> UNKNOWN", Recoverability.UNKNOWN, as(x, ns).recoverability);

        T.section("C");
        FileContext wc = x.ctx(w); Assessment wa = Assessor.assess(wc, PresentationPolicy.STANDARD).assessment;
        Candidate y2 = new Candidate("Y_CHAT_COPY", Tier.YOUR_CALL, Action.DELETE, EvidenceFamily.GRAPH, EvidenceFamily.PROV);
        Candidate y1 = new Candidate("Y_CHAT_COPY", Tier.YOUR_CALL, Action.DELETE, EvidenceFamily.PROV);
        java.util.Map<String, Double> parts = new java.util.TreeMap<String, Double>();
        double c2 = ConfidenceEstimator.of(wc, wa, y2, parts);
        T.check("min of parts", close(c2, java.util.Collections.min(parts.values())));
        T.check("families counted, not signals (1 family -> 0.6)", close(ConfidenceEstimator.of(wc, wa, y1, null), Math.min(0.6, c2)));
        wa.contradiction = true; T.check("contradiction caps at 0.4", ConfidenceEstimator.of(wc, wa, y2, null) <= 0.4);
        Candidate ex = new Candidate("N_EXACT_DUPLICATE", Tier.NO_LOSS, Action.DELETE, EvidenceFamily.GRAPH); ex.structural = true;
        FileContext pc = x.ctx(part); Assessment pa = Assessor.assess(pc, PresentationPolicy.STANDARD).assessment;
        T.check("structural ignores c_extract/c_avail (F-07)", ConfidenceEstimator.of(pc, pa, ex, null) == 1.0);

        T.section("I, B, EL, utility");
        Assessment ca = as(x, c);
        T.check("I(delete) of verified copy = 0", ca.irreversibility.get(Action.DELETE) == 0.0);
        T.check("I(delete) of unique = 1", as(x, plain).irreversibility.get(Action.DELETE) == 1.0);
        Assessment pl = as(x, plain);
        double m = pl.u90.mid * pl.lossRange.mid * (1 - pl.rvMid), p = pl.u90.high * pl.lossRange.high * (1 - pl.rvPess);
        T.check("EL_mid formula", close(pl.elMid.get(Action.DELETE), m));
        T.check("EL_pess formula", close(pl.elPess.get(Action.DELETE), p));
        T.check("UP >= 0", pl.elPess.get(Action.DELETE) >= pl.elMid.get(Action.DELETE));
        T.check("utility = Bn - EL_pess - EC", close(pl.utility.get(Action.DELETE), ExpectedLoss.bn(7000) - p - 0.001));
        T.check("Bn monotone and concave scale", ExpectedLoss.bn(5 * MB) < ExpectedLoss.bn(50 * MB) && close(ExpectedLoss.bn(50 * MB), 0.5) && ExpectedLoss.bn(-1) == 0);
        FileRecord h1 = x.file("Pictures/hl1.jpg", "pictures", "jpeg", MB); h1.physicalKey = "hl"; x.add(h1);
        FileRecord h2 = x.file("Pictures/hl2.jpg", "pictures", "jpeg", MB); h2.physicalKey = "hl"; x.add(h2);
        FileContext hc = x.ctx(h2); Assessment ha = new Assessment(); BenefitEstimator.estimate(hc, ha);
        T.eq("alias frees 0 bytes", 0L, ha.benefit.get(Action.DELETE));
        FileRecord wav = x.add("Music/rec.wav", "music", "wav", 50 * MB);
        T.check("lossless compress benefit", as(x, wav).benefit.get(Action.COMPRESS) > 20 * MB);
        return T.finish("EstimatorsTest");
    }
}
