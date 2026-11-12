package com.storageplanner.v2.features;

import com.storageplanner.v2.device.FileStat;
import com.storageplanner.v2.device.IoStatus;
import com.storageplanner.v2.device.ReadableFile;
import com.storageplanner.v2.device.StorageAccess;
import com.storageplanner.v2.model.*;
import com.storageplanner.v2.stage.Stage;
import com.storageplanner.v2.stage.StageContext;
import com.storageplanner.v2.stage.StageOutcome;
import com.storageplanner.v2.store.StoreException;
import com.storageplanner.v2.util.Io;
import com.storageplanner.v2.util.Text;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Tier 1: type, MIME, mismatch, format probe, integrity, name date, createdAt (PHASE_2 3.2). */
public final class T1MetadataStage implements Stage {
    public static final String ID = "T1_METADATA";
    public static final String SHA256_EMPTY = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    /** Probe invocations (tests: incremental runs execute none). */
    public static long probesRun;
    private final Limits lim;

    public T1MetadataStage() { this(Limits.DEFAULT); }
    public T1MetadataStage(Limits lim) { this.lim = lim; }
    public String id() { return ID; }

    /** PHASE_2 3.1 selection. */
    static boolean selected(FileRecord f, FeatureRecord fr, long scanId, boolean full) {
        if (fr == null || fr.fp == null || !fr.fp.sameAs(f.fingerprint())) return true;
        if ((f.tiersDone & Tiers.T1) == 0 && (f.tiersFailed & Tiers.T1) == 0) return true;
        if (!Extractors.current(fr, Extractors.tier1(f.type))) return true;
        if (full && fr.extractedScanId < scanId) return true;
        return (f.extractionStatus == ExtractionStatus.UNREADABLE || f.extractionStatus == ExtractionStatus.FAILED) && fr.extractedScanId < scanId;
    }

    public StageOutcome run(final StageContext ctx) throws StoreException {
        final long scanId = ctx.session.scanId;
        final boolean full = ctx.session.kind == ScanKind.FULL;
        List<FileRecord> jobs = new ArrayList<FileRecord>();
        long reused = 0;
        for (FileRecord f : ExtractionLoop.present(ctx)) {
            if (selected(f, ctx.store.features().get(f.fileId), scanId, full)) jobs.add(f); else reused++;
        }
        ctx.session.counters.put("T1.reused", reused);
        return ExtractionLoop.run(ctx, ID, jobs, Tiers.T1, new ExtractionLoop.Job() {
            public void run(FileRecord f) throws StoreException { extract(ctx, f, scanId); }
        });
    }

    void extract(StageContext ctx, FileRecord f, long scanId) throws StoreException {
        StorageAccess sa = ctx.device.storage();
        long now = ctx.device.clock().nowMillis();
        FeatureRecord fr = new FeatureRecord();
        fr.fileId = f.fileId; fr.fp = f.fingerprint(); fr.extractedScanId = scanId;
        fr.nameDate = NameDates.parse(f.name, now);
        Extractors.stamp(fr, new int[]{Extractors.NAMEDATE});
        f.lastError = null;
        ctx.session.count("T1.extracted", 1);
        probesRun++;
        if (f.sizeBytes == 0) {
            f.type = "empty"; f.typeSource = TypeSource.SIGNATURE; f.mimeType = null; f.integrity = Integrity.OK; f.extMismatch = ExtMismatch.UNKNOWN;
            f.sha256 = SHA256_EMPTY; f.readable = f.readable == Tri.FALSE ? Tri.UNKNOWN : f.readable;
            long[] ca = CreatedAt.choose(null, null, null, fr.nameDate, Long.MIN_VALUE, now);
            f.createdAt = ca[0]; f.createdAtSource = TimeSource.values()[(int) ca[1]];
            Tiers.done(f, Tiers.T1); Tiers.na(f, Tiers.T2); Tiers.na(f, Tiers.T3);
            f.extractionStatus = ExtractionStatus.COMPLETE;
            Extractors.stamp(fr, Extractors.tier1("empty"));
            fr.extra.put("probe", "none");
            Confidence.apply(f, fr);
            ctx.store.features().put(fr); ctx.store.files().put(f);
            return;
        }
        Io.CountingReadableFile rf = null;
        ProbeCtx c = new ProbeCtx();
        try {
            try {
                rf = new Io.CountingReadableFile(sa.open(f.volumeId, f.accessPath()));
            } catch (IOException e) {
                unreadable(ctx, f, fr, e, now);
                return;
            }
            long size = f.sizeBytes >= 0 ? f.sizeBytes : rf.size();
            c.rf = rf; c.size = size; c.fr = fr; c.lim = lim; c.ext = f.extension == null ? "" : f.extension.toLowerCase(java.util.Locale.ROOT);
            c.head = Io.readAt(rf, 0, (int) Math.min(Math.max(size, 0), lim.headBytes));
            c.headLen = c.head.length;
            Signature sig = TypeDetector.detect(c.head, c.headLen, size, c.ext);
            c.type = sig.type;
            f.type = sig.type; f.typeSource = sig.source;
            String mime = MimeMap.of(sig.type);
            if (mime != null && sig.source != TypeSource.EXTENSION) fr.extra.put("mimeSource", "CONTENT");
            else { mime = MimeMap.ofExtension(c.ext); if (mime != null) fr.extra.put("mimeSource", "EXTENSION"); }
            f.mimeType = mime;
            if (sig.detail != null) fr.extra.put("typeDetail", sig.detail);
            f.extMismatch = ExtensionMap.mismatch(c.ext, sig.type, sig.source);
            Probes.run(c);
            f.integrity = IntegrityProbe.check(c);
            Extractors.stamp(fr, Extractors.tier1(sig.type));
            fr.extra.put("probe", Extractors.probe(sig.type) < 0 ? "none" : c.probeException ? "exception" : c.probeProblems > 0 ? "partial" : "complete");
        } catch (IOException e) {
            closeQuietly(rf);
            charge(ctx, rf);
            f.extractionStatus = ExtractionStatus.FAILED; f.lastError = Text.truncate("T1: " + e, 200);
            Tiers.failed(f, Tiers.T1);
            Confidence.apply(f, fr);
            ctx.store.features().put(fr); ctx.store.files().put(f);
            return;
        }
        closeQuietly(rf);
        charge(ctx, rf);
        FileStat st = sa.stat(f.volumeId, f.accessPath());
        if (st != null && st.status == IoStatus.OK && !new Fingerprint(st.size, st.mtime, st.inode).sameAs(f.fingerprint())) {
            FeatureRecord old = ctx.store.features().get(f.fileId);
            if (old != null) { old.problem("stage:CHANGED_DURING_READ"); ctx.store.features().put(old); }
            FileRecord cur = ctx.store.files().get(f.fileId);
            Tiers.pending(cur, Tiers.T1);
            ctx.store.files().put(cur);
            ctx.session.count("T1.changedDuringRead", 1);
            return;
        }
        long birth = st != null && st.status == IoStatus.OK ? st.birthTime : Long.MIN_VALUE;
        long[] ca = CreatedAt.choose(fr.exif, fr.video, fr.pdf, fr.nameDate, birth, now);
        f.createdAt = ca[0]; f.createdAtSource = TimeSource.values()[(int) ca[1]];
        if (f.readable == Tri.FALSE) f.readable = Tri.UNKNOWN;
        if (f.extractionStatus == ExtractionStatus.UNREADABLE || f.extractionStatus == ExtractionStatus.FAILED) f.extractionStatus = ExtractionStatus.PARTIAL;
        Tiers.done(f, Tiers.T1);
        if (Tiers.t2Applicable(f)) Tiers.pending(f, Tiers.T2); else Tiers.na(f, Tiers.T2);
        if (Tiers.t3Applicable(f)) Tiers.pending(f, Tiers.T3); else Tiers.na(f, Tiers.T3);
        Confidence.apply(f, fr);
        ctx.store.features().put(fr);
        ctx.store.files().put(f);
    }

    void unreadable(StageContext ctx, FileRecord f, FeatureRecord fr, IOException e, long now) throws StoreException {
        boolean denied = e instanceof java.nio.file.AccessDeniedException || String.valueOf(e.getMessage()).toLowerCase(java.util.Locale.ROOT).contains("denied");
        f.extractionStatus = denied ? ExtractionStatus.UNREADABLE : ExtractionStatus.FAILED;
        f.readable = Tri.FALSE;
        f.lastError = Text.truncate((denied ? "PERMISSION_DENIED: " : "OPEN: ") + e.getMessage(), 200);
        Signature s = TypeDetector.byExtension(f.extension == null ? "" : f.extension.toLowerCase(java.util.Locale.ROOT));
        f.type = s.type; f.typeSource = s.source; f.mimeType = MimeMap.ofExtension(f.extension); f.extMismatch = ExtMismatch.UNKNOWN;
        f.integrity = Integrity.UNKNOWN;
        long[] ca = CreatedAt.choose(null, null, null, fr.nameDate, Long.MIN_VALUE, now);
        f.createdAt = ca[0]; f.createdAtSource = TimeSource.values()[(int) ca[1]];
        Tiers.failed(f, Tiers.T1);
        if (Tiers.t2Applicable(f)) Tiers.pending(f, Tiers.T2);
        if (Tiers.t3Applicable(f)) Tiers.pending(f, Tiers.T3);
        Confidence.apply(f, fr);
        ctx.store.features().put(fr); ctx.store.files().put(f);
    }

    static void charge(StageContext ctx, Io.CountingReadableFile rf) { if (rf != null && ctx.budget != null) ctx.budget.bytesRead += rf.bytes; }
    static void closeQuietly(ReadableFile rf) { if (rf != null) try { rf.close(); } catch (IOException ignored) { /* nothing to do */ } }
}
