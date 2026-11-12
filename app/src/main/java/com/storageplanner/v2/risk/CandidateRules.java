package com.storageplanner.v2.risk;

import com.storageplanner.v2.device.Availability;
import com.storageplanner.v2.graph.NameStem;
import com.storageplanner.v2.model.*;
import com.storageplanner.v2.plan.PresentationPolicy;

import java.util.*;

/** PHASE_4 9.2: the closed list of candidate rules, in table order. */
public final class CandidateRules {
    private CandidateRules() {}

    static String age(long ms) {
        if (ms < 0) return "an unknown time";
        long d = ms / RiskConfig.DAY;
        return d < 1 ? "less than a day" : d < 60 ? d + " days" : d < 730 ? (d / 30) + " months" : (d / 365) + " years";
    }
    static String pkgOf(FileContext ctx) {
        if (ctx.f.ownerPackage != null) return ctx.f.ownerPackage;
        String[] c = ctx.f.path == null ? new String[0] : ctx.f.path.split("/");
        return c.length > 2 && c[0].equalsIgnoreCase("Android") ? c[2] : "an app";
    }
    static String pathOf(FileContext ctx, String id) { FileRecord k = ctx.g.file(id); return k == null ? id : k.path; }
    static Candidate s(String code, FileContext ctx) {
        Candidate c = new Candidate(code, Tier.SAFE, Action.DELETE, EvidenceFamily.STRUCT);
        c.structural = true; c.evidenceRefs.add("area:" + ctx.f.area);
        return c;
    }
    static boolean oldEnough(FileContext ctx) { return ctx.mtimeAgeMs >= RiskConfig.MIN_STRUCT_AGE_MS; }

    public static List<Candidate> generate(FileContext ctx, Assessment a, PresentationPolicy pol) {
        List<Candidate> out = new ArrayList<Candidate>();
        FileRecord f = ctx.f; UsageClass u = ctx.usage; String low = ctx.lowerPath();
        String nameLow = f.name == null ? "" : f.name.toLowerCase(Locale.ROOT), ext = f.extension == null ? "" : f.extension.toLowerCase(Locale.ROOT);
        ApkInfo apk = ctx.apk();
        // ---- S_ ----
        if (u == UsageClass.TEMP && oldEnough(ctx)) out.add(s("S_TEMP_FILE", ctx).p("ext", ext.isEmpty() ? "no extension" : "." + ext).p("age", age(ctx.mtimeAgeMs)));
        if (u == UsageClass.TRASH) out.add(s("S_TRASHED", ctx).p("when", "after 30 days"));
        if (u == UsageClass.THUMBNAIL) out.add(s("S_THUMBNAIL", ctx));
        if (u == UsageClass.CACHE) out.add(s("S_APP_CACHE_FILE", ctx).p("app", pkgOf(ctx)));
        if (u == UsageClass.APP_LOG && oldEnough(ctx)) out.add(s("S_APP_LOG", ctx).p("app", pkgOf(ctx)));
        if (u == UsageClass.STATUS && oldEnough(ctx) && !nameLow.equals(".nomedia")) out.add(s("S_EXPIRED_STATUS", ctx));
        if (u == UsageClass.EMPTY && f.sizeBytes == 0 && !nameLow.startsWith(".") && !low.startsWith("android/data/") && !low.startsWith("android/obb/")
                && !ext.equals("nomedia") && !ext.equals("lock")) out.add(s("S_EMPTY_FILE", ctx));
        boolean residue = "residue".equals(f.area);
        boolean media = Types.image(f.type) || Types.video(f.type) || Types.audio(f.type);
        if (residue && low.startsWith("android/data/") && !media) {
            Candidate c = s("S_UNINSTALLED_LEFTOVER", ctx).p("pkg", pkgOf(ctx)).p("hint", "app visibility is limited");
            c.families.add(EvidenceFamily.APP); c.citesNotInstalled = true; c.fallbackCode = "R_UNKNOWN_DEPENDENCY"; c.fallbackTier = Tier.REVIEW;
            out.add(c);
        }
        // ---- N_ ----
        if ("EXACT_COPY_KEPT".equals(a.recoveryKind) && a.rvPess == 1) {
            Candidate c = new Candidate("N_EXACT_DUPLICATE", Tier.NO_LOSS, Action.DELETE, EvidenceFamily.GRAPH);
            c.structural = true; c.cites.add(RelType.EXACT_DUPLICATE);
            String kp = pathOf(ctx, ctx.copyKeeper); c.p("keeper", kp).p("other", kp);
            c.evidenceRefs.add(ctx.copyEdge.edgeId); c.evidenceRefs.add("hash:" + f.fileId); c.evidenceRefs.add("hash:" + ctx.copyKeeper);
            c.dependencyIds.add(ctx.copyKeeper);
            FileRecord k = ctx.g.file(ctx.copyKeeper);
            if (k != null && !NameStem.of(f.name).equals(NameStem.of(k.name))) {
                c.secondaryCodes.add("N_SAME_CONTENT_DIFFERENT_NAME"); c.p("name", f.name).p("keeperName", k.name);
            }
            c.fallbackCode = "R_UNCERTAIN_COPY"; c.fallbackTier = Tier.REVIEW;
            out.add(c);
        }
        if ("REGENERABLE_INSTALLER".equals(a.recoveryKind) && apk != null && apk.split != Tri.TRUE) {
            Candidate c = new Candidate("N_INSTALLER_INSTALLED", Tier.NO_LOSS, Action.DELETE, EvidenceFamily.APP);
            c.structural = true; c.p("app", apk.packageName).p("version", apk.versionName == null ? String.valueOf(apk.versionCode) : apk.versionName);
            c.evidenceRefs.add("apk:" + apk.packageName);
            c.fallbackCode = "Y_INSTALLER_UNVERIFIED"; c.fallbackTier = Tier.YOUR_CALL;
            out.add(c);
        }
        double ratio = BenefitEstimator.losslessRatio(ctx); Long cb = a.benefit.get(Action.COMPRESS);
        if (!Double.isNaN(ratio) && cb != null && cb > 1024 * 1024 && cb >= 0.1 * f.sizeBytes) {
            Candidate c = new Candidate("N_LOSSLESS_SMALLER", Tier.NO_LOSS, Action.COMPRESS, EvidenceFamily.STRUCT);
            c.structural = true; c.p("saving", (cb * 100 / f.sizeBytes) + "%").p("transform", "lossless").p("keepOriginal", "until-purge");
            out.add(c);
        }
        // ---- Y_ / R_ from the graph ----
        if ("SUPERSET_COPY".equals(a.recoveryKind)) {
            Relationship e = ctx.copyEdge;
            boolean chat = UsageClassifier.CHAT.contains(f.area) || NameStem.WA.matcher(NameStem.of(f.name)).matches();
            String code = e.type == RelType.VERSION_OF ? "Y_CONVERTED_COPY" : chat ? "Y_CHAT_COPY" : "Y_SMALLER_COPY";
            Candidate c = new Candidate(code, Tier.YOUR_CALL, Action.DELETE, EvidenceFamily.GRAPH);
            if (chat) c.families.add(EvidenceFamily.PROV);
            c.cites.add(e.type); String kp = pathOf(ctx, ctx.copyKeeper);
            c.p("original", kp).p("other", kp).p("format", f.type == null ? "other" : f.type.toUpperCase(Locale.ROOT));
            c.evidenceRefs.add(e.edgeId); c.dependencyIds.add(ctx.copyKeeper);
            out.add(c);
        }
        for (DuplicateGroup d : ctx.memberOf) {
            if (d.kind == NearGroupKind.BURST && d.keeperId != null && !d.keeperId.equals(f.fileId)) {
                String kp = pathOf(ctx, d.keeperId);
                Candidate c = pol.burstsAsYourCall ? new Candidate("Y_SIMILAR_SHOT", Tier.YOUR_CALL, Action.DELETE, EvidenceFamily.GRAPH, EvidenceFamily.CAPTURE)
                        : new Candidate("R_SIMILAR_SHOT", Tier.REVIEW, Action.REVIEW, EvidenceFamily.GRAPH, EvidenceFamily.CAPTURE);
                c.cites.add(RelType.VISUALLY_SIMILAR);
                c.p("keeper", kp).p("n", d.members.size()).p("why", "was taken within seconds of it").p("other", kp);
                c.evidenceRefs.add("group:" + d.groupId); c.dependencyIds.add(d.keeperId);
                out.add(c);
            }
            if ((d.keeperFallback || d.keeperId == null) && d.kind != NearGroupKind.BURST && d.kind != NearGroupKind.EDIT_FAMILY) {
                Candidate c = new Candidate("R_UNCERTAIN_COPY", Tier.REVIEW, Action.REVIEW, EvidenceFamily.GRAPH);
                c.p("other", d.members.size() - 1 + " other files"); c.evidenceRefs.add("group:" + d.groupId); out.add(c);
            }
        }
        if ("UNVERIFIED_COPY".equals(a.recoveryKind) && ctx.keeperOf.isEmpty()) {
            Candidate c = new Candidate("R_UNCERTAIN_COPY", Tier.REVIEW, Action.REVIEW, EvidenceFamily.GRAPH);
            c.citesUnverified = true; c.p("other", ctx.copyKeeper != null ? pathOf(ctx, ctx.copyKeeper) : "another file");
            c.evidenceRefs.addAll(a.recoveryEvidence); out.add(c);
        }
        // ---- installers ----
        if (u == UsageClass.INSTALLER && apk != null) {
            String app = apk.packageName == null ? f.name : apk.packageName, ver = apk.versionName == null ? String.valueOf(apk.versionCode) : apk.versionName;
            InstallState is = apk.installState;
            boolean installed = is == InstallState.SAME_VERSION || is == InstallState.INSTALLED_NEWER || is == InstallState.INSTALLED_OLDER;
            if (is == InstallState.SAME_VERSION && apk.sameSigner == Tri.UNKNOWN && ctx.appsAvailability != Availability.UNAVAILABLE) {
                Candidate c = new Candidate("Y_INSTALLER_UNVERIFIED", Tier.YOUR_CALL, Action.DELETE, EvidenceFamily.APP);
                c.p("app", app).p("version", ver); c.evidenceRefs.add("apk:" + app); c.fallbackTier = Tier.REVIEW; c.fallbackCode = "R_INSTALLER_SIGNER_MISMATCH"; out.add(c);
            }
            if (is == InstallState.INSTALLED_NEWER && apk.sameSigner == Tri.TRUE) {
                Candidate c = new Candidate("Y_OLDER_INSTALLER", Tier.YOUR_CALL, Action.DELETE, EvidenceFamily.APP);
                c.p("app", app).p("version", ver).p("installed", "version " + apk.installedVersionCode); c.evidenceRefs.add("apk:" + app); out.add(c);
            }
            if (installed && apk.sameSigner == Tri.FALSE) {
                Candidate c = new Candidate("R_INSTALLER_SIGNER_MISMATCH", Tier.REVIEW, Action.REVIEW, EvidenceFamily.APP); c.p("app", app); c.evidenceRefs.add("apk:" + app); out.add(c);
            }
            if (is == InstallState.INSTALLED_OLDER && apk.sameSigner != Tri.FALSE) {
                Candidate c = new Candidate("R_INSTALLER_NEWER", Tier.REVIEW, Action.REVIEW, EvidenceFamily.APP);
                c.p("app", app).p("version", ver).p("installed", "version " + apk.installedVersionCode); c.evidenceRefs.add("apk:" + app); out.add(c);
            }
        }
        // ---- chat backups ----
        if (u == UsageClass.CHAT_BACKUP) for (Relationship r : ctx.g.edgesOf(f.fileId)) {
            if (r.type != RelType.VERSION_OF || !r.src.equals(f.fileId)) continue;
            FileRecord n = ctx.g.file(r.dst);
            if (n == null || n.presence != Presence.PRESENT || f.sizeBytes < 0 || n.sizeBytes < 0.5 * f.sizeBytes) continue;
            Candidate c = new Candidate("Y_OLD_CHAT_BACKUP", Tier.YOUR_CALL, Action.DELETE, EvidenceFamily.GRAPH, EvidenceFamily.PROV);
            c.cites.add(RelType.VERSION_OF); c.citesUnverified = r.verification != Verification.VERIFIED;
            c.p("date", f.name).p("newer", n.name); c.evidenceRefs.add(r.edgeId); c.dependencyIds.add(n.fileId); out.add(c); break;
        }
        // ---- leftovers ----
        if (residue && low.startsWith("android/media/")) {
            Candidate c = new Candidate("Y_LEFTOVER_MEDIA", Tier.YOUR_CALL, Action.DELETE, EvidenceFamily.STRUCT, EvidenceFamily.APP);
            c.citesNotInstalled = true; c.p("pkg", pkgOf(ctx)).p("hint", "app visibility is limited"); c.fallbackCode = "R_UNKNOWN_DEPENDENCY"; c.fallbackTier = Tier.REVIEW; out.add(c);
        }
        if (residue && low.startsWith("android/obb/")) {
            Candidate c = new Candidate("R_UNKNOWN_DEPENDENCY", Tier.REVIEW, Action.REVIEW, EvidenceFamily.STRUCT, EvidenceFamily.APP);
            c.p("hint", "game data of " + pkgOf(ctx) + ", whose installer is not on the phone"); out.add(c);
        }
        // ---- pixels ----
        ImageInfo im = ctx.image();
        if (im != null && Types.image(f.type) && !Double.isNaN(im.contrast)
                && (im.contrast < 4 || (im.contrast < 10 && (im.clipLow >= 0.9 || im.clipHigh >= 0.9)))) {
            Candidate c = new Candidate("Y_LOW_QUALITY", Tier.YOUR_CALL, Action.DELETE, EvidenceFamily.PIXEL);
            c.needsFeatures = true; c.p("problem", im.clipLow >= 0.9 ? "dark" : im.clipHigh >= 0.9 ? "bright" : "blank").p("other", f.name);
            c.evidenceRefs.add("image:contrast"); out.add(c);
        }
        // ---- downloads (aggressive only) ----
        if (pol.oldDownloads && u == UsageClass.DOWNLOAD && (Types.video(f.type) || Types.audio(f.type) || Types.archive(f.type))
                && f.sizeBytes >= 10L * 1024 * 1024 && ctx.ageMs >= 180 * RiskConfig.DAY) {
            Candidate c = new Candidate("R_OLD_DOWNLOAD", Tier.REVIEW, Action.REVIEW, EvidenceFamily.TIME, EvidenceFamily.PROV);
            c.p("kind", Types.video(f.type) ? "video" : Types.audio(f.type) ? "audio file" : "archive").p("age", age(ctx.ageMs)); out.add(c);
        }
        if (f.extMismatch == ExtMismatch.SUSPICIOUS || ctx.nameContentMismatch) {
            Candidate c = new Candidate("R_NAME_CONTENT_MISMATCH", Tier.REVIEW, Action.REVIEW, EvidenceFamily.STRUCT);
            c.p("ext", ext.isEmpty() ? "(none)" : ext).p("type", f.type); c.evidenceRefs.add("type:" + f.typeSource); out.add(c);
        }
        // ---- P_ ----
        boolean redundant = ctx.copyGroup != null;
        if ((u == UsageClass.DOCUMENT_RECORD || (u == UsageClass.CHAT_BACKUP && nameLow.startsWith("msgstore.db.crypt")))
                && a.lossRange.high >= RiskConfig.P_RECORD_MIN_LOSS_HIGH && a.recoverability != Recoverability.VERIFIED_BACKUP
                && a.recoverability != Recoverability.REGENERABLE && !redundant) {
            Candidate c = new Candidate("P_UNIQUE_RECORD", Tier.PROTECTED, Action.KEEP, EvidenceFamily.PROV);
            String cat = u == UsageClass.CHAT_BACKUP ? "chat backup" : ctx.classification != null ? ctx.classification.category : "record";
            c.p("category", cat); if (ctx.classification != null) c.p("classifier", ctx.classification.source);
            c.evidenceRefs.add("path:" + Keywords.hit(f.path)); out.add(c);
        }
        ArchiveInfo ar = ctx.archive();
        if (ar != null && "OK".equals(ar.listingStatus) && ar.entries != null) {
            Set<String> covered = new HashSet<String>();
            for (BackupRecord b : ctx.g.backupsInArchive(f.fileId))
                if (b.state == BackupState.VERIFIED || b.state == BackupState.POSSIBLE) covered.add(b.copyRef.substring(b.copyRef.indexOf('!') + 1));
            int n = 0;
            for (ArchiveEntry e : ar.entries) if (!e.dir && e.path != null && !covered.contains(e.path) && (Types.docName(e.path) || Keywords.record(e.path))) n++;
            if (n > 0) { Candidate c = new Candidate("P_ARCHIVE_UNIQUE_CONTENT", Tier.PROTECTED, Action.KEEP, EvidenceFamily.GRAPH, EvidenceFamily.PROV); c.p("n", n); out.add(c); }
        }
        for (Relationship r : ctx.g.edgesOf(f.fileId)) {
            if (r.type != RelType.COMPANION_OF || !r.src.equals(f.fileId)) continue;
            FileRecord p = ctx.g.file(r.dst);
            if (p == null || p.presence != Presence.PRESENT || !Types.apk(p.type)) continue;
            if (ctx.g.apkState(p.fileId) != InstallState.NOT_INSTALLED) continue;
            Candidate c = new Candidate("P_CRITICAL_DEPENDENCY", Tier.PROTECTED, Action.KEEP, EvidenceFamily.GRAPH);
            c.p("other", p.path); c.evidenceRefs.add(r.edgeId); c.dependencyIds.add(p.fileId); out.add(c); break;
        }
        return out;
    }
}
