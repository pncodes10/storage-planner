package com.storageplanner.v2test;

import com.storageplanner.v2.model.*;
import com.storageplanner.v2.plan.PresentationPolicy;

/** PHASE_4 12: spec scenarios 1-11 as small fixtures asserting tier + primary reason code. */
public final class ScenarioFixtureTest {
    public static void main(String[] a) { System.exit(run(a) == 0 ? 0 : 1); }
    static final long MB = 1024 * 1024;
    static boolean removal(Verdict v) { return v.action == Action.DELETE || v.action == Action.QUARANTINE; }
    static void expect(String name, Verdict v, Tier tier, String code) { T.eq(name + " tier", tier, v.tier); T.eq(name + " code", code, RiskFx.code(v)); }

    public static int run(String[] args) {
        for (PresentationPolicy pol : new PresentationPolicy[]{PresentationPolicy.STANDARD, PresentationPolicy.AGGRESSIVE}) {
            T.section("S01 " + pol.name);
            RiskFx x = new RiskFx();
            FileRecord blurry = x.file("DCIM/Camera/IMG_20211226_131543.jpg", "camera", "jpeg", 3 * MB); x.pixels(blurry, 0.01, 30); x.add(blurry);
            Verdict v = x.v(blurry, pol); expect("blurry family photo", v, null, null); T.check("no removal", !removal(v));
            FileRecord pocket = x.file("DCIM/Camera/IMG_20220101_000000.jpg", "camera", "jpeg", 2 * MB); x.pixels(pocket, 0.01, 2); x.add(pocket);
            v = x.v(pocket, pol);
            T.check("pocket shot never SAFE/NO_LOSS", v.tier != Tier.SAFE && v.tier != Tier.NO_LOSS);
            T.check("pocket shot: YOUR_CALL only via Y_LOW_QUALITY", v.tier != Tier.YOUR_CALL || "Y_LOW_QUALITY".equals(RiskFx.code(v)));

            T.section("S02 " + pol.name);
            x = new RiskFx();
            FileRecord st = x.add("Documents/Bank/Ayesha_Statement_2018_03.pdf", "document", "pdf", 200000);
            expect("old statement", x.v(st, pol), Tier.PROTECTED, "P_UNIQUE_RECORD");
            FileRecord tmp = x.add("Download/temp123.tmp", "temp", "pdf", 150000);
            v = x.v(tmp, pol); expect("temp name over unique PDF", v, Tier.REVIEW, "R_NAME_CONTENT_MISMATCH");
            FileRecord keep = x.add("Documents/Bank/Statement_2021_01.pdf", "document", "pdf", 150001);
            FileRecord tmp2 = x.add("Download/temp456.tmp", "temp", "pdf", 150001);
            x.exact(keep, tmp2, true);
            v = x.v(tmp2, pol); expect("temp name, verified named twin", v, Tier.NO_LOSS, "N_EXACT_DUPLICATE");
            T.check("different name code", v.reasonCodes.contains("N_SAME_CONTENT_DIFFERENT_NAME"));
            v = x.v(keep, pol); T.check("record keeper never removed (P_UNIQUE_RECORD: its own group's copies do not count)", !removal(v) && v.assessment.keeperOf.size() == 1);
            FileRecord inv1 = x.add("Documents/Finance/google_invoice.pdf", "document", "pdf", 7777);
            FileRecord inv2 = x.add("Documents/Finance/amazon_invoice.pdf", "document", "pdf", 7777);
            x.exact(inv1, inv2, true);
            v = x.v(inv2, pol); expect("identical invoices, different names", v, Tier.NO_LOSS, "N_EXACT_DUPLICATE");
            T.eq("secondary", "N_SAME_CONTENT_DIFFERENT_NAME", v.reasonCodes.size() > 1 ? v.reasonCodes.get(1) : null);
            FileRecord old = x.file("Documents/Notes/old_letter.pdf", "document", "pdf", 5000); old.createdAt = RiskFx.NOW - 3650 * RiskFx.DAY; x.add(old);
            v = x.v(old, pol); T.check("10-year-old unique document not removed", !removal(v));

            T.section("S03 " + pol.name);
            x = new RiskFx();
            FileRecord orig = x.add("DCIM/Camera/IMG_1.jpg", "camera", "jpeg", 3 * MB), copy = x.add("Pictures/IMG_1.jpg", "pictures", "jpeg", 3 * MB);
            x.exact(orig, copy, true);
            expect("exact copy", x.v(copy, pol), Tier.NO_LOSS, "N_EXACT_DUPLICATE");
            v = x.v(orig, pol); T.eq("original KEEP", null, v.tier); T.eq("keeper annotated", 1, v.assessment.keeperOf.size());
            FileRecord u1 = x.add("DCIM/Camera/IMG_2.jpg", "camera", "jpeg", 4 * MB), u2 = x.add("Pictures/IMG_2.jpg", "pictures", "jpeg", 4 * MB);
            x.exact(u1, u2, false);
            expect("unverified exact copy", x.v(u2, pol), Tier.REVIEW, "R_UNCERTAIN_COPY");

            T.section("S04 " + pol.name);
            x = new RiskFx();
            FileRecord eo = x.add("DCIM/Camera/IMG_3.jpg", "camera", "jpeg", 3 * MB), ed = x.add("Pictures/Edited/IMG_3_edit.jpg", "pictures", "jpeg", 2 * MB);
            x.edge(ed.fileId, eo.fileId, RelType.EDITED_FROM, Verification.VERIFIED, 0.9); x.group(NearGroupKind.EDIT_FAMILY, eo, ed);
            v = x.v(ed, pol); T.eq("edit KEEP", null, v.tier);
            FileRecord b0 = x.add("DCIM/Camera/IMG_4.jpg", "camera", "jpeg", 3 * MB), b1 = x.add("DCIM/Camera/IMG_5.jpg", "camera", "jpeg", 3 * MB);
            x.edge(b0.fileId, b1.fileId, RelType.VISUALLY_SIMILAR, Verification.VERIFIED, 0.8); x.group(NearGroupKind.BURST, b0, b1);
            v = x.v(b1, pol);
            if (pol == PresentationPolicy.STANDARD) expect("burst sibling", v, Tier.REVIEW, "R_SIMILAR_SHOT");
            else T.check("burst sibling never SAFE/NO_LOSS", v.tier != Tier.SAFE && v.tier != Tier.NO_LOSS);

            T.section("S05 " + pol.name);
            x = new RiskFx();
            FileRecord wa = x.add("WhatsApp/Media/WhatsApp Images/IMG-20210101-WA0001.jpg", "wa_image", "jpeg", 200000);
            v = x.v(wa, pol); T.eq("unique chat copy KEEP", null, v.tier);
            FileRecord co = x.add("DCIM/Camera/IMG_6.jpg", "camera", "jpeg", 60 * MB), wc = x.add("WhatsApp/Media/WhatsApp Video/VID-20210101-WA0002.jpg", "wa_image", "jpeg", 30 * MB);
            x.ce(co, wc, RelType.COMPRESSED_FROM, 0.95);
            expect("large chat copy with original", x.v(wc, pol), Tier.YOUR_CALL, "Y_CHAT_COPY");
            FileRecord co2 = x.add("DCIM/Camera/IMG_7.jpg", "camera", "jpeg", 3 * MB), wc2 = x.add("WhatsApp/Media/WhatsApp Images/IMG-20210101-WA0003.jpg", "wa_image", "jpeg", 150000);
            x.ce(co2, wc2, RelType.COMPRESSED_FROM, 0.95);
            v = x.v(wc2, pol); T.check("small chat copy never SAFE/NO_LOSS", v.tier != Tier.SAFE && v.tier != Tier.NO_LOSS);

            T.section("S06 " + pol.name);
            x = new RiskFx();
            FileRecord a1 = x.file("Download/m.apk", "download", "apk", 40 * MB); x.apk(a1, "com.inst", InstallState.SAME_VERSION, Tri.TRUE); x.add(a1);
            expect("same version+signer", x.v(a1, pol), Tier.NO_LOSS, "N_INSTALLER_INSTALLED");
            FileRecord a2 = x.file("Download/old.apk", "download", "apk", 40 * MB); x.apk(a2, "com.inst", InstallState.INSTALLED_NEWER, Tri.TRUE); x.add(a2);
            expect("older same signer", x.v(a2, pol), Tier.YOUR_CALL, "Y_OLDER_INSTALLER");
            FileRecord a3 = x.file("Download/mod.apk", "download", "apk", 40 * MB); x.apk(a3, "com.inst", InstallState.SAME_VERSION, Tri.FALSE); x.add(a3);
            expect("other signer", x.v(a3, pol), Tier.REVIEW, "R_INSTALLER_SIGNER_MISMATCH");
            FileRecord a4 = x.file("Download/beta.apk", "download", "apk", 40 * MB); x.apk(a4, "com.inst", InstallState.INSTALLED_OLDER, Tri.TRUE); x.add(a4);
            expect("newer than installed", x.v(a4, pol), Tier.REVIEW, "R_INSTALLER_NEWER");

            T.section("S07 " + pol.name);
            x = new RiskFx();
            FileRecord ni = x.file("Download/Dict.apk", "download", "apk", 40 * MB); x.apk(ni, "com.gone", InstallState.NOT_INSTALLED, Tri.UNKNOWN); x.add(ni);
            expect("not installed", x.v(ni, pol), Tier.REVIEW, "R_INSTALLER_NOT_INSTALLED");
            FileRecord obb = x.add("Android/obb/com.gone/main.1.com.gone.obb", "app_obb", "unknown", 80 * MB);
            x.edge(obb.fileId, ni.fileId, RelType.COMPANION_OF, Verification.VERIFIED, 0.95);
            expect("obb of not-installed apk", x.v(obb, pol), Tier.PROTECTED, "P_CRITICAL_DEPENDENCY");
            FileRecord exe = x.add("Download/setup.exe", "download", "unknown", 5 * MB);
            T.check("exe never removed", !removal(x.v(exe, pol)));

            T.section("S08 " + pol.name);
            x = new RiskFx();
            FileRecord vid = x.file("DCIM/Camera/VID_1.mp4", "camera", "mp4", 900 * MB); vid.extractionStatus = ExtractionStatus.PARTIAL; vid.extractionConfidence = 0.6; x.add(vid);
            v = x.v(vid, pol); T.eq("long video KEEP", null, v.tier); T.check("no transform", v.transform == null);

            T.section("S09 " + pol.name);
            x = new RiskFx();
            FileRecord enc = x.file("Documents/locked.pdf", "document", "pdf", 90000); enc.integrity = Integrity.ENCRYPTED; x.add(enc);
            expect("encrypted pdf", x.v(enc, pol), Tier.REVIEW, "R_ENCRYPTED");

            T.section("S10 " + pol.name);
            x = new RiskFx();
            FileRecord gz = x.file("Backups/Gallery_backup.zip", "user_other", "zip", 5 * MB); x.add(gz);
            ArchiveInfo ai = new ArchiveInfo(); ai.listingStatus = "OK"; ai.entries = new java.util.ArrayList<ArchiveEntry>(); x.feat(gz).archive = ai;
            FileRecord p1 = x.add("DCIM/Camera/IMG_8.jpg", "camera", "jpeg", 3 * MB);
            x.backup(p1, gz, "IMG_8.jpg", BackupState.CORRUPT);
            v = x.v(p1, pol); T.check("corrupt backup is not VERIFIED", v.assessment.recoverability != Recoverability.VERIFIED_BACKUP); T.eq("original KEEP", null, v.tier);
            T.check("corrupt evidence listed", v.assessment.recoveryEvidence.contains("x:b-" + p1.fileId + "-" + gz.fileId));
            expect("archive with corrupt entry", x.v(gz, pol), Tier.REVIEW, "R_ARCHIVE_UNSAFE");

            T.section("S11 " + pol.name);
            x = new RiskFx();
            FileRecord z = x.file("Documents/Important_Papers.zip", "document", "zip", 2 * MB); x.add(z);
            ArchiveInfo zi = new ArchiveInfo(); zi.listingStatus = "OK"; zi.entries = new java.util.ArrayList<ArchiveEntry>();
            ArchiveEntry e1 = new ArchiveEntry(); e1.path = "deed.pdf"; zi.entries.add(e1); ArchiveEntry e2 = new ArchiveEntry(); e2.path = "passport.jpg"; zi.entries.add(e2);
            x.feat(z).archive = zi;
            FileRecord loose = x.add("Documents/passport.jpg", "document", "jpeg", 300000);
            x.backup(loose, z, "passport.jpg", BackupState.VERIFIED);
            v = x.v(z, pol); expect("important zip", v, Tier.PROTECTED, "P_ARCHIVE_UNIQUE_CONTENT"); T.eq("n = 1 unmatched document", "1", v.params.get("n"));
            v = x.v(loose, pol); T.eq("loose verified copy recoverability", Recoverability.VERIFIED_BACKUP, v.assessment.recoverability); T.check("loose copy not removed", !removal(v));
            FileRecord tz = x.file("Download/evil.zip", "download", "zip", 4000); x.add(tz);
            ArchiveInfo ti = new ArchiveInfo(); ti.listingStatus = "OK"; ti.pathTraversal = Tri.TRUE; x.feat(tz).archive = ti;
            expect("traversal zip", x.v(tz, pol), Tier.REVIEW, "R_ARCHIVE_UNSAFE");
        }
        return T.finish("ScenarioFixtureTest");
    }
}
