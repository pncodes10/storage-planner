package com.storageplanner.v2.risk;

import com.storageplanner.v2.device.Availability;
import com.storageplanner.v2.model.*;

import java.util.*;

/** R: recoverability state, first matching row of PHASE_4 5.1. Only rows 1-4 can support SAFE/NO_LOSS removals (H1). */
public final class RecoverabilityEstimator {
    private RecoverabilityEstimator() {}
    static final EnumSet<UsageClass> REGEN = EnumSet.of(UsageClass.THUMBNAIL, UsageClass.CACHE, UsageClass.APP_LOG, UsageClass.STATUS, UsageClass.TEMP);
    static final EnumSet<RelType> COPY_TYPES = EnumSet.of(RelType.COMPRESSED_FROM, RelType.THUMBNAIL_OF, RelType.VERSION_OF);

    /** Stage 3: group roles. */
    public static void roles(FileContext ctx) {
        String id = ctx.f.fileId;
        ctx.keeperOf.clear(); ctx.memberOf.clear(); ctx.copyGroup = null; ctx.copyKeeper = null; ctx.copyEdge = null;
        for (DuplicateGroup d : ctx.g.groupsOf(id)) {
            ctx.memberOf.add(d);
            if (id.equals(d.keeperId)) { if (d.members.size() > 1) ctx.keeperOf.add(d); continue; }
            if (d.kind == NearGroupKind.EDIT_FAMILY || d.kind == NearGroupKind.BURST) continue;   // never redundant members
            if (ctx.copyGroup == null || (d.kind == NearGroupKind.EXACT && ctx.copyGroup.kind != NearGroupKind.EXACT)) {
                ctx.copyGroup = d; ctx.copyKeeper = d.keeperId;
                ctx.copyEdge = d.keeperId == null ? null : d.kind == NearGroupKind.EXACT ? ctx.g.edge(id, d.keeperId, RelType.EXACT_DUPLICATE) : ceEdge(ctx, d.keeperId);
            }
        }
    }
    static Relationship ceEdge(FileContext ctx, String keeper) {
        for (RelType t : COPY_TYPES) { Relationship r = ctx.g.edge(ctx.f.fileId, keeper, t); if (r != null) return r; }
        return null;
    }
    static boolean archiveKept(FileContext ctx, String archiveId) {
        if (!ctx.g.eligible(archiveId)) return false;
        FileRecord af = ctx.g.file(archiveId);
        String ar = af.area == null ? "" : af.area;
        if (ar.equals("temp") || ar.equals("thumbnails") || ar.equals("ext_cache")) return false;
        for (DuplicateGroup d : ctx.g.groupsOf(archiveId)) if (!archiveId.equals(d.keeperId) && d.kind == NearGroupKind.EXACT) return false;
        return true;
    }
    static void set(Assessment a, Recoverability r, String kind, double mid, double pess) { a.recoverability = r; a.recoveryKind = kind; a.rvMid = mid; a.rvPess = pess; }

    public static void estimate(FileContext ctx, Assessment a) {
        FileRecord f = ctx.f; a.recoveryEvidence.clear();
        for (BackupRecord b : ctx.g.backupsOf(f.fileId))
            if (b.state == BackupState.CORRUPT || b.state == BackupState.MISSING) a.recoveryEvidence.add("x:" + b.backupId);
        ApkInfo apk = ctx.apk();
        DuplicateGroup g = ctx.copyGroup; Relationship e = ctx.copyEdge;
        boolean keeperOk = g != null && ctx.copyKeeper != null && !g.keeperFallback && ctx.g.eligible(ctx.copyKeeper) && !ctx.copyKeeper.equals(f.fileId);
        if (REGEN.contains(ctx.usage)) { set(a, Recoverability.REGENERABLE, "REGENERABLE_CACHE", 1, 1); return; }
        if (ctx.usage == UsageClass.INSTALLER && apk != null && apk.installState == InstallState.SAME_VERSION && apk.sameSigner == Tri.TRUE
                && ctx.appsAvailability != Availability.UNAVAILABLE) { set(a, Recoverability.REGENERABLE, "REGENERABLE_INSTALLER", 1, 1); a.recoveryEvidence.add("apk:" + apk.packageName); return; }
        if (keeperOk && g.kind == NearGroupKind.EXACT && e != null && e.verification == Verification.VERIFIED) {
            set(a, Recoverability.VERIFIED_BACKUP, "EXACT_COPY_KEPT", 1, 1); a.recoveryEvidence.add(e.edgeId); a.recoveryEvidence.add("hash:" + f.fileId); return;
        }
        for (BackupRecord b : ctx.g.backupsOf(f.fileId))
            if (b.state == BackupState.VERIFIED && b.copyRef != null && b.copyRef.indexOf('!') > 0 && archiveKept(ctx, b.copyRef.substring(0, b.copyRef.indexOf('!')))) {
                set(a, Recoverability.VERIFIED_BACKUP, "BACKUP_VERIFIED", 1, 1); a.recoveryEvidence.add(b.backupId); return;
            }
        if (keeperOk && g.kind == NearGroupKind.CONTENT_EQUIVALENT && e != null && e.verification == Verification.VERIFIED) {
            set(a, Recoverability.POSSIBLE_BACKUP, "SUPERSET_COPY", 0.7, e.confidence >= 0.8 ? 0.5 : 0); a.recoveryEvidence.add(e.edgeId); return;
        }
        for (Relationship r : ctx.g.edgesOf(f.fileId))
            if (r.type == RelType.EXACT_DUPLICATE && r.verification != Verification.VERIFIED) { set(a, Recoverability.POSSIBLE_BACKUP, "UNVERIFIED_COPY", 0.3, 0); a.recoveryEvidence.add(r.edgeId); return; }
        if (g != null && g.kind == NearGroupKind.EXACT) { set(a, Recoverability.POSSIBLE_BACKUP, "UNVERIFIED_COPY", 0.3, 0); return; }
        for (BackupRecord b : ctx.g.backupsOf(f.fileId))
            if (b.state == BackupState.POSSIBLE) { set(a, Recoverability.POSSIBLE_BACKUP, "POSSIBLE_ARCHIVE_COPY", 0.3, 0); a.recoveryEvidence.add(b.backupId); return; }
        String ps = ctx.perceptualStatus();
        boolean complete = (f.sha256 != null || ctx.g.sizeUnique(f)) && (!Types.image(f.type) || "TRUE".equals(ps) || "FALSE".equals(ps));
        if (complete) set(a, Recoverability.NO_VERIFIED_BACKUP, "NONE", 0, 0);
        else set(a, Recoverability.UNKNOWN, null, 0, 0);
    }
}
