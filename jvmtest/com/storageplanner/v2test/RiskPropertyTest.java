package com.storageplanner.v2test;

import com.storageplanner.v2.content.Classification;
import com.storageplanner.v2.content.ContentClassifier;
import com.storageplanner.v2.model.*;
import com.storageplanner.v2.plan.PresentationPolicy;
import com.storageplanner.v2.plan.ReasonCatalog;
import com.storageplanner.v2.risk.*;

import java.util.*;

/**
 * Randomised invariants (a)-(f) of the Phase 4 brief, the unknown-input property (H4), policy invariance (H7), the classifier stub (H3),
 * decision-table rows and reason-catalogue completeness.
 */
public final class RiskPropertyTest {
    public static void main(String[] a) { System.exit(run(a) == 0 ? 0 : 1); }
    static final String[][] KINDS = {
        {"DCIM/Camera/IMG_%d.jpg", "camera", "jpeg"}, {"WhatsApp/Media/WhatsApp Images/IMG-2021010%d-WA0001.jpg", "wa_image", "jpeg"},
        {"Documents/Bank/Statement_%d.pdf", "document", "pdf"}, {"Documents/notes_%d.pdf", "document", "pdf"}, {"Download/f%d.tmp", "temp", "pdf"},
        {"Download/f%d.crdownload", "temp", "unknown"}, {"DCIM/.thumbnails/%d.jpg", "thumbnails", "jpeg"}, {"Download/app%d.apk", "download", "apk"},
        {"Download/v%d.mp4", "download", "mp4"}, {"Pictures/Screenshots/s%d.png", "screenshot", "png"}, {"Download/a%d.zip", "download", "zip"},
        {"Android/media/com.old/x%d.jpg", "residue", "jpeg"}, {"WhatsApp/Media/.Statuses/%d.jpg", "wa_status", "jpeg"}, {"Pictures/.trashed-1-%d.jpg", "trash", "jpeg"},
        {"Download/u%d.bin", "download", "unknown"}, {"Music/m%d.wav", "music", "wav"}};
    static <T> T pick(Random r, T[] a) { return a[r.nextInt(a.length)]; }

    static FileRecord randomFile(RiskFx x, Random r, int i) {
        String[] k = pick(r, KINDS);
        FileRecord f = x.file(String.format(Locale.ROOT, k[0], i), k[1], k[2], r.nextInt(5) == 0 ? 0 : 1000L + r.nextInt(80 * 1024 * 1024));
        f.createdAt = RiskFx.NOW - r.nextInt(4000) * RiskFx.DAY; f.modifiedAt = f.createdAt;
        f.extractionStatus = pick(r, new ExtractionStatus[]{ExtractionStatus.COMPLETE, ExtractionStatus.COMPLETE, ExtractionStatus.PARTIAL, ExtractionStatus.UNSUPPORTED});
        f.extractionConfidence = r.nextBoolean() ? 1.0 : r.nextDouble();
        f.integrity = pick(r, new Integrity[]{Integrity.OK, Integrity.OK, Integrity.OK, Integrity.UNKNOWN, Integrity.ENCRYPTED, Integrity.TRUNCATED});
        if (r.nextInt(15) == 0) f.protection = Protection.USER_PROTECTED;
        if (r.nextInt(20) == 0) f.readable = Tri.FALSE;
        if (r.nextInt(4) == 0) f.sha256 = null;
        if (f.type.equals("apk")) x.apk(f, r.nextBoolean() ? "com.inst" : "com.gone", pick(r, InstallState.values()), pick(r, Tri.values()));
        if (Types.image(f.type)) x.pixels(f, r.nextDouble(), r.nextInt(3) == 0 ? r.nextInt(12) : 40);
        return f;
    }
    /** A random little world: files plus random groups/backups. */
    static RiskFx world(Random r, List<FileRecord> out) {
        RiskFx x = new RiskFx();
        if (r.nextInt(4) == 0) x.avail = com.storageplanner.v2.device.Availability.PARTIAL;
        int n = 3 + r.nextInt(6);
        for (int i = 0; i < n; i++) out.add(randomFile(x, r, i));
        for (FileRecord f : out) x.add(f);
        if (n >= 2 && r.nextBoolean()) x.exact(out.get(0), out.get(1), r.nextInt(3) > 0);
        if (n >= 3 && r.nextBoolean()) { if (out.size() > 3 && r.nextBoolean()) { x.edge(out.get(3).fileId, out.get(0).fileId, RelType.EXACT_DUPLICATE, Verification.VERIFIED, 1); x.groupsAdd(out.get(0), out.get(3)); } }
        if (n >= 3 && r.nextBoolean()) x.ce(out.get(2), out.get(n - 1) == out.get(2) ? out.get(1) : out.get(n - 1), pick(r, new RelType[]{RelType.COMPRESSED_FROM, RelType.THUMBNAIL_OF, RelType.VERSION_OF}), r.nextDouble());
        if (n >= 4 && r.nextBoolean()) x.backup(out.get(3), out.get(n - 1), "e", pick(r, BackupState.values()));
        if (n >= 5 && r.nextBoolean()) { x.edge(out.get(4).fileId, out.get(1).fileId, RelType.VISUALLY_SIMILAR, Verification.VERIFIED, 0.8); x.group(NearGroupKind.BURST, out.get(1), out.get(4)); }
        return x;
    }
    static boolean safeDelete(Verdict v) { return v.action.removal() && (v.tier == Tier.SAFE || v.tier == Tier.NO_LOSS); }

    public static int run(String[] args) {
        Random r = new Random(4242);
        int worlds = 1500, checked = 0;
        for (int w = 0; w < worlds; w++) {
            List<FileRecord> files = new ArrayList<FileRecord>();
            RiskFx x = world(r, files);
            Map<String, Verdict> std = new HashMap<String, Verdict>();
            for (FileRecord f : files) {
                FileContext ctx = x.ctx(f);
                Verdict v = Assessor.assess(ctx, PresentationPolicy.STANDARD), v2 = Assessor.assess(x.ctx(f), PresentationPolicy.STANDARD);
                Verdict ag = Assessor.assess(x.ctx(f), PresentationPolicy.AGGRESSIVE);
                std.put(f.fileId, v); checked++;
                // (e) total and deterministic
                if (!T.check("(e) total", v != null && v.row >= 1 && v.row <= 11)) break;
                if (!T.check("(e) deterministic " + f.path, v.signature().equals(v2.signature()))) break;
                // (a) SAFE/NO_LOSS deletes need a verified, accessible, distinct kept copy, or a structural regenerable class
                if (safeDelete(v) || safeDelete(ag)) for (Verdict q : new Verdict[]{v, ag}) {
                    if (!safeDelete(q)) continue;
                    String rk = q.assessment.recoveryKind, code = RiskFx.code(q);
                    boolean structural = code.startsWith("S_") && !(code.equals("S_TEMP_FILE") && UsageClassifier.tempContradicted(f));
                    boolean verified = ("EXACT_COPY_KEPT".equals(rk) && ctx.copyKeeper != null && !ctx.copyKeeper.equals(f.fileId) && x.g.eligible(ctx.copyKeeper)
                            && ctx.copyEdge != null && ctx.copyEdge.verification == Verification.VERIFIED) || "BACKUP_VERIFIED".equals(rk) || "REGENERABLE_INSTALLER".equals(rk);
                    if (!T.check("(a) " + code + " on " + f.path + " rk=" + rk, structural || verified)) break;
                    T.check("(a) never protected/unreadable", f.protection == Protection.NONE && f.readable != Tri.FALSE);
                }
                // (c) age or size alone never yields a delete for a unique document
                if ((ctx.usage == UsageClass.DOCUMENT || ctx.usage == UsageClass.DOCUMENT_RECORD) && ctx.memberOf.isEmpty() && x.g.backupsOf(f.fileId).isEmpty())
                    T.check("(c) unique document " + f.path, !v.action.removal() && !ag.action.removal());
                // (d) aggressive never changes a hard-rule outcome
                Constraint ks = HardRules.constraints(ctx), ka = HardRules.constraints(x.ctx(f));
                T.check("(d) constraints identical", ks.signature().equals(ka.signature()));
                if (v.row >= 2 && v.row <= 6) T.check("(d) hard/structural rows identical across modes " + f.path + " " + v.signature() + " vs " + ag.signature(), v.signature().equals(ag.signature()));
                if (!ag.signature().equals(v.signature()))
                    T.check("(d) aggressive differs only in policy rows " + ag.signature(), ag.row >= 7 && (ag.row != 7 || v.row >= 7));
                // (b) unknown never lowers loss severity / raises recoverability / lowers rank
                FileRecord u = f.copy(); u.extractionStatus = ExtractionStatus.PARTIAL; if (!(f.extractionConfidence < 0.3)) u.extractionConfidence = Double.NaN;   // a known value below the NaN default (0.3) is already worse than unknown
                FileContext uc = new FileContext(); uc.f = u; uc.g = x.g; uc.feat = x.feats.get(f.fileId); uc.now = RiskFx.NOW;
                uc.appsAvailability = ctx.appsAvailability; uc.installedPackages = ctx.installedPackages;
                if (uc.feat != null && uc.feat.extra.containsKey("graph.perceptual")) { uc.feat = uc.feat.copy(); uc.feat.extra.put("graph.perceptual", "UNKNOWN"); if (uc.feat.image != null) { uc.feat.image.sharpness = Double.NaN; uc.feat.image.contrast = Double.NaN; } }
                ContextBuilder.finish(uc, new RiskConfig());
                Verdict uv = Assessor.assess(uc, PresentationPolicy.STANDARD);
                Assessment ka0 = v.assessment, ku = uv.assessment;
                T.check("(b) L.high never decreases " + f.path, ku.lossRange.high >= ka0.lossRange.high - 1e-12);
                T.check("(b) U.high never decreases", ku.u90.high >= ka0.u90.high - 1e-12);
                T.check("(b) rvPess never increases", ku.rvPess <= ka0.rvPess + 1e-12);
                T.check("(b) I never decreases", ku.irreversibility.get(Action.DELETE) >= ka0.irreversibility.get(Action.DELETE) - 1e-12);
                T.check("(b) C never increases", ConfidenceEstimator.of(uc, ku, null, null) <= ConfidenceEstimator.of(ctx, ka0, null, null) + 1e-12);
                T.check("(b) removal rank never lower " + f.path + " " + v.signature() + " -> " + uv.signature(), rank(uv) >= rank(v));
                // reasons
                for (Verdict q : new Verdict[]{v, ag}) {
                    if (q.tier == null) continue;
                    T.check("reason exists", !q.reasonCodes.isEmpty());
                    for (String c : q.reasonCodes) {
                        if (!T.check("catalogued " + c, ReasonCatalog.exists(c))) continue;
                        for (String ph : ReasonCatalog.placeholders(c)) T.check(c + " param " + ph, q.params.containsKey(ph));
                    }
                }
            }
            // (f) every group keeps its keeper and at least one member
            for (DuplicateGroup d : distinctGroups(x, files)) {
                int kept = 0;
                for (String m : d.members) { Verdict v = std.get(m); if (v == null || !v.action.removal()) kept++; if (m.equals(d.keeperId)) T.check("(f) keeper never removed", v == null || !v.action.removal()); }
                T.check("(f) group keeps one member", kept >= 1);
            }
        }
        T.check("checked many contexts", checked > 5000);

        T.section("classifier stub (H3)");
        RiskConfig cfg = new RiskConfig(); cfg.classifiers.add(0, new ContentClassifier() {
            public String id() { return "stub"; } public int version() { return 1; }
            public EvidenceFamily family() { return EvidenceFamily.CONTENT; } public double reliability() { return 0.99; }
            public Classification classify(FileContext c) { Classification k = new Classification(); k.category = "meme"; k.confidence = 0.99; k.source = "MODEL"; return k; }
        });
        Random r2 = new Random(7);
        for (int w = 0; w < 300; w++) {
            List<FileRecord> files = new ArrayList<FileRecord>(); RiskFx x = world(r2, files);
            for (FileRecord f : files) {
                Verdict base = x.v(f);
                FileContext c = new FileContext(); c.f = f; c.g = x.g; c.feat = x.feats.get(f.fileId); c.now = RiskFx.NOW; c.appsAvailability = x.avail;
                c.installedPackages = x.avail == com.storageplanner.v2.device.Availability.FULL ? x.installed : null;
                Verdict s = Assessor.assess(ContextBuilder.finish(c, cfg), PresentationPolicy.STANDARD);
                T.check("stub adds no SAFE/NO_LOSS", !safeDelete(s) || safeDelete(base));
                T.check("stub adds no YOUR_CALL", !(s.tier == Tier.YOUR_CALL && s.action.removal()) || (base.tier == Tier.YOUR_CALL && base.action.removal()));
            }
        }

        T.section("decision table rows");
        RiskFx x = new RiskFx();
        FileRecord prot = x.file("Pictures/p.jpg", "pictures", "jpeg", 5000); prot.protection = Protection.USER_PROTECTED; x.add(prot);
        T.eq("row 2", 2, x.v(prot).row);
        FileRecord tk = x.add("DCIM/Camera/t.jpg", "camera", "jpeg", 3000000), tc = x.add("DCIM/.thumbnails/t.jpg", "thumbnails", "jpeg", 3000000);
        x.exact(tk, tc, true);
        T.eq("row 3: S beats N", 3, x.v(tc).row);
        FileRecord nk = x.add("DCIM/Camera/n.jpg", "camera", "jpeg", 3000001), nc = x.add("Pictures/n.jpg", "pictures", "jpeg", 3000001); x.exact(nk, nc, true);
        T.eq("row 4", 4, x.v(nc).row);
        FileRecord enc = x.file("Documents/Bank/locked_statement.pdf", "document", "pdf", 5001); enc.integrity = Integrity.ENCRYPTED; x.add(enc);
        T.eq("row 5: forced REVIEW beats P", 5, x.v(enc).row);
        T.eq("row 6", 6, x.v(x.add("Documents/Bank/statement.pdf", "document", "pdf", 5002)).row);
        FileRecord co = x.add("DCIM/Camera/big.jpg", "camera", "jpeg", 90000000), cc = x.add("WhatsApp/Media/WhatsApp Images/invoice_IMG-20200101-WA0009.jpg", "wa_image", "jpeg", 40000000);
        x.ce(co, cc, RelType.COMPRESSED_FROM, 0.95);
        T.eq("row 7: contradiction", "R_CONTRADICTORY", RiskFx.code(x.v(cc)));
        FileRecord co2 = x.add("DCIM/Camera/big2.jpg", "camera", "jpeg", 90000001), cc2 = x.add("WhatsApp/Media/WhatsApp Images/IMG-20200101-WA0010.jpg", "wa_image", "jpeg", 40000001);
        x.ce(co2, cc2, RelType.COMPRESSED_FROM, 0.95);
        T.eq("row 8", 8, x.v(cc2).row);
        FileRecord co3 = x.add("DCIM/Camera/sm.jpg", "camera", "jpeg", 3000002), cc3 = x.add("WhatsApp/Media/WhatsApp Images/IMG-20200101-WA0011.jpg", "wa_image", "jpeg", 100000);
        x.ce(co3, cc3, RelType.COMPRESSED_FROM, 0.95);
        T.eq("row 8 (A25: a small chat copy is YOUR_CALL; size no longer hides it)", 8, x.v(cc3).row);
        FileRecord co4 = x.add("DCIM/Camera/sm4.jpg", "camera", "jpeg", 3000003), cc4 = x.file("WhatsApp/Media/WhatsApp Images/IMG-20200101-WA0012.jpg", "wa_image", "jpeg", 100001);
        cc4.extractionConfidence = 0.3; x.add(cc4);       // weak extraction: fails cYourCall in both modes (a non-size reason)
        x.ce(co4, cc4, RelType.COMPRESSED_FROM, 0.95);
        T.eq("row 9 (aggressive: failed Y_ shown as REVIEW)", 9, x.v(cc4, PresentationPolicy.AGGRESSIVE).row);
        T.eq("row 11 (standard: failed Y_ dropped)", 11, x.v(cc4).row);
        FileRecord u1 = x.add("DCIM/Camera/u1.jpg", "camera", "jpeg", 3000003), u2 = x.add("Pictures/u1.jpg", "pictures", "jpeg", 3000003); x.exact(u1, u2, false);
        T.eq("row 10", 10, x.v(u2).row);
        FileRecord gone = x.file("Pictures/g.jpg", "pictures", "jpeg", 5003); gone.presence = Presence.REMOVED; x.add(gone);
        T.eq("row 1", 1, x.v(gone).row);
        Verdict tv = x.v(x.add("Music/rec.wav", "music", "wav", 20000000));
        T.check("row T refused on a likely record (F-10: screenshot L.high >= 0.8)", x.v(x.add("Pictures/Screenshots/big.png", "screenshot", "png", 20000000)).transform == null);
        T.check("row T transform", tv.transform != null && "until-purge".equals(tv.transform.params.get("keepOriginal")));
        return T.finish("RiskPropertyTest");
    }
    static int rank(Verdict v) { return v.action.removal() ? Tier.rank(v.tier) : 4; }
    static Collection<DuplicateGroup> distinctGroups(RiskFx x, List<FileRecord> files) {
        Map<String, DuplicateGroup> m = new TreeMap<String, DuplicateGroup>();
        for (FileRecord f : files) for (DuplicateGroup d : x.g.groupsOf(f.fileId)) m.put(d.groupId, d);
        return m.values();
    }
}
