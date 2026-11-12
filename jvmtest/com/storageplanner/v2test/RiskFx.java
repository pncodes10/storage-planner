package com.storageplanner.v2test;

import com.storageplanner.v2.device.Availability;
import com.storageplanner.v2.model.*;
import com.storageplanner.v2.plan.PresentationPolicy;
import com.storageplanner.v2.risk.*;

import java.util.*;

/**
 * Synthetic Phase 4 fixtures: files, features and graph built directly (no scan). With {@link #storeKind} set
 * (PHASE_S_STORE 5.2), every verdict is computed from a store of that kind instead: the registered records are written to a
 * fresh store, read back through the codecs, and assessed with {@code GraphView.of(store)} and the store's features; the
 * verdict must equal the direct one ({@link #storeMismatches} counts differences).
 */
final class RiskFx {
    static com.storageplanner.v2.store.Stores.Kind storeKind;
    static int storeMismatches, storeVerdicts;
    final Map<String, FileRecord> added = new LinkedHashMap<String, FileRecord>();
    final List<Relationship> edgesAdded = new ArrayList<Relationship>();
    final Map<String, DuplicateGroup> groupsAdded = new LinkedHashMap<String, DuplicateGroup>();
    final List<BackupRecord> backupsAdded = new ArrayList<BackupRecord>();
    static final long NOW = 1794500000000L, DAY = 86400000L;
    final GraphView g = new GraphView();
    final Map<String, FeatureRecord> feats = new HashMap<String, FeatureRecord>();
    Availability avail = Availability.FULL;
    Set<String> installed = new TreeSet<String>(Arrays.asList("com.inst"));
    int n;

    FileRecord file(String path, String area, String type, long size) {
        FileRecord f = new FileRecord();
        f.fileId = String.format(Locale.ROOT, "f%04d", n++); f.volumeId = "primary"; f.path = path;
        f.name = path.substring(path.lastIndexOf('/') + 1);
        int d = f.name.lastIndexOf('.'); f.extension = d > 0 ? f.name.substring(d + 1) : "";
        f.area = area; f.type = type; f.sizeBytes = size; f.presence = Presence.PRESENT; f.readable = Tri.TRUE;
        f.extractionStatus = ExtractionStatus.COMPLETE; f.extractionConfidence = 1.0; f.typeSource = TypeSource.SIGNATURE; f.integrity = Integrity.OK;
        f.modifiedAt = NOW - 400 * DAY; f.createdAt = NOW - 400 * DAY; f.createdAtSource = TimeSource.NONE;
        f.sha256 = "sha-" + f.fileId; f.physicalKey = "pk-" + f.fileId; f.inode = n;
        return f;
    }
    /** Registers the file (after all FileRecord fields are final). */
    FileRecord add(FileRecord f) { g.add(f); added.put(f.fileId, f); FeatureRecord fr = feat(f); if (fr.apk != null) g.apkStates.put(f.fileId, fr.apk.installState); return f; }
    FileRecord add(String path, String area, String type, long size) { return add(file(path, area, type, size)); }
    FeatureRecord feat(FileRecord f) {
        FeatureRecord fr = feats.get(f.fileId);
        if (fr == null) { fr = new FeatureRecord(); fr.fileId = f.fileId; feats.put(f.fileId, fr); }
        fr.fp = f.fingerprint();
        if (Types.image(f.type) && fr.image == null) { pixels(f, 0.5, 40); }
        return fr;
    }
    void pixels(FileRecord f, double sharp, double contrast) {
        FeatureRecord fr = feats.get(f.fileId);
        if (fr == null) { fr = new FeatureRecord(); fr.fileId = f.fileId; fr.fp = f.fingerprint(); feats.put(f.fileId, fr); }
        ImageInfo im = fr.image == null ? new ImageInfo() : fr.image;
        im.width = 4000; im.height = 3000; im.derive(); im.sharpness = sharp; im.contrast = contrast; im.clipLow = 0.01; im.clipHigh = 0.01; im.brightness = 0.5;
        fr.image = im; fr.extra.put("graph.perceptual", "FALSE");
    }
    ApkInfo apk(FileRecord f, String pkg, InstallState st, Tri signer) {
        ApkInfo a = new ApkInfo(); a.packageName = pkg; a.versionName = "1.0"; a.versionCode = 10; a.installedVersionCode = 10; a.installState = st; a.sameSigner = signer; a.split = Tri.FALSE;
        FeatureRecord fr = feats.get(f.fileId); if (fr == null) { fr = new FeatureRecord(); fr.fileId = f.fileId; feats.put(f.fileId, fr); }
        fr.apk = a; fr.fp = f.fingerprint(); g.apkStates.put(f.fileId, st); return a;
    }
    Relationship edge(String src, String dst, RelType t, Verification v, double conf) {
        Relationship r = new Relationship(); r.src = src; r.dst = dst; r.type = t; r.verification = v; r.confidence = conf;
        r.edgeId = "e-" + src + "-" + t + "-" + dst; g.add(r); edgesAdded.add(r); return r;
    }
    DuplicateGroup group(NearGroupKind k, FileRecord keeper, FileRecord... members) {
        DuplicateGroup d = new DuplicateGroup(); d.kind = k; d.exact = k == NearGroupKind.EXACT;
        d.groupId = (k == NearGroupKind.EXACT ? "x:" : "n:") + keeper.fileId; d.keeperId = keeper.fileId; d.members.add(keeper.fileId);
        d.keeperReasons.add("G_KEEPER_CANONICAL"); d.verification = Verification.VERIFIED;
        for (FileRecord m : members) d.members.add(m.fileId);
        g.add(d); groupsAdded.put(d.groupId, d); return d;
    }
    /** Adds a member to the (single) exact group kept by keeper. */
    void groupsAdd(FileRecord keeper, FileRecord m) {
        for (DuplicateGroup d : g.groupsOf(keeper.fileId)) if (d.kind == NearGroupKind.EXACT && keeper.fileId.equals(d.keeperId)) { d.members.add(m.fileId); g.add(d); groupsAdded.put(d.groupId, d); return; }
        group(NearGroupKind.EXACT, keeper, m);
    }
    DuplicateGroup exact(FileRecord keeper, FileRecord copy, boolean verified) {
        edge(copy.fileId.compareTo(keeper.fileId) < 0 ? copy.fileId : keeper.fileId, copy.fileId.compareTo(keeper.fileId) < 0 ? keeper.fileId : copy.fileId,
                RelType.EXACT_DUPLICATE, verified ? Verification.VERIFIED : Verification.UNVERIFIED, verified ? 1.0 : 0.9);
        return group(NearGroupKind.EXACT, keeper, copy);
    }
    DuplicateGroup ce(FileRecord keeper, FileRecord copy, RelType t, double conf) {
        edge(copy.fileId, keeper.fileId, t, Verification.VERIFIED, conf);
        return group(NearGroupKind.CONTENT_EQUIVALENT, keeper, copy);
    }
    BackupRecord backup(FileRecord subject, FileRecord archive, String entry, BackupState st) {
        BackupRecord b = new BackupRecord(); b.backupId = "b-" + subject.fileId + "-" + archive.fileId; b.subjectId = subject.fileId;
        b.copyRef = archive.fileId + "!" + entry; b.state = st; g.add(b); backupsAdded.add(b); return b;
    }
    FileContext ctx(FileRecord f) {
        FileContext c = new FileContext();
        c.f = f; c.g = g; c.feat = feats.get(f.fileId); c.now = NOW; c.appsAvailability = avail;
        c.installedPackages = avail == Availability.FULL ? installed : null;
        return ContextBuilder.finish(c, new RiskConfig());
    }
    Verdict v(FileRecord f) { return v(f, PresentationPolicy.STANDARD); }
    Verdict v(FileRecord f, PresentationPolicy p) {
        Verdict direct = Assessor.assess(ctx(f), p);
        if (storeKind == null) return direct;
        Verdict viaStore = Assessor.assess(storeCtx(f), p);
        storeVerdicts++;
        if (!same(direct, viaStore)) {
            storeMismatches++;
            System.out.println("  store " + storeKind + ": verdict of " + f.path + " differs: " + direct.tier + " " + direct.reasonCodes + " vs " + viaStore.tier + " " + viaStore.reasonCodes);
        }
        return viaStore;
    }
    static boolean same(Verdict a, Verdict b) {
        return a.tier == b.tier && a.action == b.action && a.reasonCodes.equals(b.reasonCodes) && a.hardBlocks.equals(b.hardBlocks)
                && a.assessment.keeperOf.equals(b.assessment.keeperOf) && a.assessment.recoverability == b.assessment.recoverability;
    }
    /** The same context, built from a fresh store of {@link #storeKind} holding every registered record. */
    FileContext storeCtx(FileRecord f) {
        try {
            com.storageplanner.v2.store.V2Store st = com.storageplanner.v2.store.Stores.create(storeKind, new PowerLossBlobStore(), 0, new FixedClock(NOW));
            for (FileRecord x : added.values()) st.files().put(x);
            for (FeatureRecord x : feats.values()) st.features().put(x);
            for (Relationship x : edgesAdded) st.relationships().put(x);
            for (DuplicateGroup x : groupsAdded.values()) st.duplicateGroups().put(x);
            for (BackupRecord x : backupsAdded) st.backups().put(x);
            st.flush();
            GraphView sg = GraphView.of(st);
            sg.apkStates.putAll(g.apkStates);
            FileContext c = new FileContext();
            c.f = st.files().get(f.fileId); c.g = sg; c.feat = st.features().get(f.fileId); c.now = NOW; c.appsAvailability = avail;
            c.installedPackages = avail == Availability.FULL ? installed : null;
            return ContextBuilder.finish(c, new RiskConfig());
        } catch (com.storageplanner.v2.store.StoreException e) { throw new IllegalStateException(e); }
    }
    static String code(Verdict v) { return v.reasonCodes.isEmpty() ? null : v.reasonCodes.get(0); }
}
