package com.storageplanner.v2.risk;

import com.storageplanner.v2.device.Availability;
import com.storageplanner.v2.model.*;

import java.util.*;

/**
 * Hard safety constraints HC1-HC14 (PHASE_4 8, ARCHITECTURE 7). One implementation, two entry points: constraints() runs in stage 2
 * before any score exists; check() runs per candidate. There is deliberately no mode/policy parameter (H7); HC15 (confidence floors,
 * which read a policy threshold) is applied by TierAssigner.
 */
public final class HardRules {
    private HardRules() {}
    public static final List<String> ORDER = Arrays.asList("HC1", "HC2", "HC3", "HC4", "HC5", "HC6", "HC7", "HC8", "HC9", "HC10", "HC11", "HC12", "HC13", "HC14");
    static final EnumSet<RelType> SIMILAR = EnumSet.of(RelType.VISUALLY_SIMILAR, RelType.SEMANTICALLY_SIMILAR, RelType.EDITED_FROM,
            RelType.COMPRESSED_FROM, RelType.THUMBNAIL_OF, RelType.VERSION_OF);

    // ---- file-level facts ----
    public static boolean identityFails(FileContext ctx) { return ctx.f.presence != Presence.PRESENT || ctx.g.alias(ctx.f); }
    public static boolean unreadable(FileContext ctx) { return ctx.f.readable == Tri.FALSE || ctx.f.extractionStatus == ExtractionStatus.UNREADABLE; }
    public static boolean featuresUnknown(FileContext ctx) {
        ExtractionStatus s = ctx.f.extractionStatus;
        if (s == ExtractionStatus.PARTIAL || s == ExtractionStatus.FAILED || s == ExtractionStatus.UNSUPPORTED || s == ExtractionStatus.NOT_STARTED) return true;
        ImageInfo im = ctx.image();
        if (Types.image(ctx.f.type) && (im == null || Double.isNaN(im.sharpness) || Double.isNaN(im.contrast))) return true;
        return Types.video(ctx.f.type);   // video frames are not sampled before Phase 6
    }
    /** HC9: R_ENCRYPTED / R_ARCHIVE_UNSAFE / R_UNREADABLE, or null. */
    public static String integrityCode(FileContext ctx) {
        Integrity i = ctx.f.integrity;
        if (i == Integrity.ENCRYPTED) return "R_ENCRYPTED";
        ArchiveInfo ar = ctx.archive();
        if (ar != null) {
            if (ar.encryptedEntries == Tri.TRUE) return "R_ENCRYPTED";
            if ("LIMIT".equals(ar.listingStatus) || "CORRUPT".equals(ar.listingStatus) || ar.bombSuspect == Tri.TRUE || ar.pathTraversal == Tri.TRUE
                    || ar.absolutePaths == Tri.TRUE || (ar.nestedArchives >= 0 && ar.nestedListed < ar.nestedArchives)) return "R_ARCHIVE_UNSAFE";
        }
        for (BackupRecord b : ctx.g.backupsInArchive(ctx.f.fileId)) if (b.state == BackupState.CORRUPT) return "R_ARCHIVE_UNSAFE";
        if (i == Integrity.TRUNCATED || i == Integrity.CORRUPT) return "R_UNREADABLE";
        return null;
    }
    static String archiveIssue(FileContext ctx) {
        ArchiveInfo ar = ctx.archive();
        if (ar != null) {
            if (ar.bombSuspect == Tri.TRUE) return "possible zip bomb";
            if (ar.pathTraversal == Tri.TRUE || ar.absolutePaths == Tri.TRUE) return "entry paths that point outside the archive";
            if (ar.nestedArchives >= 0 && ar.nestedListed < ar.nestedArchives) return "nested archives not checked";
            if (ar.listingStatus != null && !ar.listingStatus.equals("OK")) return "listing " + ar.listingStatus.toLowerCase(Locale.ROOT);
        }
        return "an entry failed its checksum";
    }
    public static boolean installerNotInstalled(FileContext ctx) {
        if (ctx.usage != UsageClass.INSTALLER) return false;
        if (!Types.apk(ctx.f.type)) return true;    // any non-Android installer
        ApkInfo apk = ctx.apk();
        return apk == null || apk.installState == InstallState.NOT_INSTALLED || apk.installState == InstallState.UNKNOWN;
    }
    public static boolean dependency(FileContext ctx) {
        String id = ctx.f.fileId;
        for (Relationship r : ctx.g.edgesOf(id)) {
            if (r.type != RelType.COMPANION_OF) continue;
            if (r.src.equals(id)) { FileRecord p = ctx.g.file(r.dst); if (p != null && p.presence == Presence.PRESENT) return true; }
            else {
                FileRecord s = ctx.g.file(r.src);
                if (s != null && s.path != null && s.path.toLowerCase(Locale.ROOT).startsWith("android/obb/")) {
                    ApkInfo apk = ctx.apk();
                    if (apk != null && apk.installState != InstallState.NOT_INSTALLED) return true;
                }
            }
        }
        return false;
    }
    public static boolean appDataProtected(FileContext ctx) {
        String low = ctx.lowerPath();
        if (low.startsWith("android/obb/")) return true;
        if (low.startsWith("android/data/")) {
            String[] c = low.split("/");
            if (c.length > 4 && c[3].equals("files")) return ctx.installedPackages == null || ctx.installedPackages.contains(c[2]);
        }
        return false;
    }

    /** Stage 2 entry point: before any score. */
    public static Constraint constraints(FileContext ctx) {
        Constraint k = new Constraint();
        if (identityFails(ctx)) { k.noVerdict = true; return k; }                          // HC2 (ordered after HC1 in the table; no verdict wins)
        if (ctx.f.protection != Protection.NONE) {                                           // HC1
            k.forcedTier = Tier.PROTECTED; k.forcedReason = "P_USER_PROTECTED"; k.block("P_USER_PROTECTED");
            k.forbidden.addAll(EnumSet.complementOf(EnumSet.of(Action.KEEP)));
        }
        if (unreadable(ctx)) {                                                               // HC3
            force(k, Tier.REVIEW, "R_UNREADABLE"); k.forcedParams.put("error", ctx.f.lastError != null ? ctx.f.lastError : "no read access");
            k.forbidden.addAll(EnumSet.complementOf(EnumSet.of(Action.KEEP, Action.REVIEW)));
        }
        if (dependency(ctx)) { k.block("H_DEPENDENCY"); k.forbidden.add(Action.DELETE); k.forbidden.add(Action.QUARANTINE); }   // HC4
        if (featuresUnknown(ctx)) { k.block("H_UNKNOWN_FEATURES"); floor(k, Tier.YOUR_CALL); }                                  // HC8 (S_/exact exempt per candidate)
        String ic = integrityCode(ctx);                                                      // HC9
        if (ic != null) {
            force(k, Tier.REVIEW, ic);
            if (ic.equals("R_ARCHIVE_UNSAFE")) k.forcedParams.put("issue", archiveIssue(ctx));
            if (ic.equals("R_UNREADABLE")) k.forcedParams.put("error", "file is " + ctx.f.integrity.name().toLowerCase(Locale.ROOT));
            floor(k, Tier.REVIEW);
        }
        if (installerNotInstalled(ctx)) {                                                    // HC11
            k.block("R_INSTALLER_NOT_INSTALLED"); k.forbidden.add(Action.DELETE); k.forbidden.add(Action.QUARANTINE);
            if (Types.apk(ctx.f.type)) { force(k, Tier.REVIEW, "R_INSTALLER_NOT_INSTALLED"); ApkInfo apk = ctx.apk(); k.forcedParams.put("app", apk != null && apk.packageName != null ? apk.packageName : ctx.f.name); }
        }
        if (!ctx.keeperOf.isEmpty()) { k.block("H_LAST_VERIFIED_COPY"); k.forbidden.add(Action.DELETE); k.forbidden.add(Action.QUARANTINE); k.forbidden.add(Action.COMPRESS); k.forbidden.add(Action.ARCHIVE); }   // HC13
        if (appDataProtected(ctx)) k.block("H_DEPENDENCY");                                  // HC14
        return k;
    }
    static void force(Constraint k, Tier t, String code) {
        if (k.forcedTier == null) { k.forcedTier = t; k.forcedReason = code; }
        k.block(code);
    }
    static void floor(Constraint k, Tier t) { if (k.floorTier == null || Tier.rank(t) > Tier.rank(k.floorTier)) k.floorTier = t; }

    static boolean safeTier(Tier t) { return t == Tier.SAFE || t == Tier.NO_LOSS; }
    static boolean exactRule(Candidate c) { return c.reasonCode.equals("N_EXACT_DUPLICATE"); }
    static boolean sRule(Candidate c) { return c.reasonCode.startsWith("S_"); }

    /** Per-candidate entry point: the block codes that forbid this candidate at its proposed tier (empty = allowed). */
    public static List<String> check(FileContext ctx, Assessment a, Candidate c) {
        List<String> b = new ArrayList<String>();
        boolean rem = c.action.removal(), tr = c.action.transform(), any = rem || tr;
        Tier t = c.proposed;
        if (identityFails(ctx)) { b.add("HC2"); return b; }
        if (ctx.f.protection != Protection.NONE && c.action != Action.KEEP) add(b, "P_USER_PROTECTED");                         // HC1
        if (unreadable(ctx) && any) add(b, "R_UNREADABLE");                                                                      // HC3
        if (rem && dependency(ctx)) add(b, "H_DEPENDENCY");                                                                      // HC4
        if (rem && safeTier(t) && !sRule(c) && !("REGENERABLE_INSTALLER".equals(a.recoveryKind) || "EXACT_COPY_KEPT".equals(a.recoveryKind)
                || "BACKUP_VERIFIED".equals(a.recoveryKind))) add(b, "H_LAST_VERIFIED_COPY");                                     // HC5
        if (rem && safeTier(t)) for (RelType r : c.cites) if (SIMILAR.contains(r)) add(b, "H_SIMILAR_NOT_IDENTICAL");             // HC6
        if (rem) {                                                                                                               // HC7
            EnumSet<EvidenceFamily> ns = EnumSet.copyOf(c.families.isEmpty() ? EnumSet.of(EvidenceFamily.STRUCT) : c.families); ns.remove(EvidenceFamily.STRUCT);
            if (!ns.isEmpty() && (EnumSet.of(EvidenceFamily.CONTENT).containsAll(ns) || EnumSet.of(EvidenceFamily.TIME).containsAll(ns))) add(b, "H_CLASSIFICATION_ONLY");
            if (safeTier(t) && (c.families.contains(EvidenceFamily.CONTENT) || c.families.contains(EvidenceFamily.TIME))) add(b, "H_CLASSIFICATION_ONLY");
        }
        if (featuresUnknown(ctx)) {                                                                                              // HC8
            if (rem && safeTier(t) && !sRule(c) && !exactRule(c)) add(b, "H_UNKNOWN_FEATURES");
            if (rem && t == Tier.YOUR_CALL && c.needsFeatures) add(b, "H_UNKNOWN_FEATURES");
            if (tr && "lossy".equals(c.params.get("transform"))) add(b, "H_UNKNOWN_FEATURES");
        }
        String ic = integrityCode(ctx);                                                                                          // HC9
        if (ic != null && any && !sRule(c) && !exactRule(c)) add(b, ic);
        if (c.citesNotInstalled && ctx.appsAvailability != Availability.FULL) add(b, "H_UNKNOWN_FEATURES");                      // HC10
        if (rem && installerNotInstalled(ctx)) add(b, "R_INSTALLER_NOT_INSTALLED");                                              // HC11
        if (rem && (ctx.usage == UsageClass.CAMERA_PHOTO || ctx.usage == UsageClass.CAMERA_VIDEO || ctx.usage == UsageClass.VOICE)
                && c.families.contains(EvidenceFamily.PIXEL) && !c.reasonCode.equals("Y_LOW_QUALITY")) add(b, "H_CLASSIFICATION_ONLY"); // HC12
        if (any && !ctx.keeperOf.isEmpty()) add(b, "H_LAST_VERIFIED_COPY");                                                      // HC13
        if (rem && appDataProtected(ctx) && !(c.reasonCode.equals("S_UNINSTALLED_LEFTOVER") && ctx.lowerPath().startsWith("android/data/"))) add(b, "H_DEPENDENCY"); // HC14
        return b;
    }
    static void add(List<String> b, String code) { if (!b.contains(code)) b.add(code); }
}
