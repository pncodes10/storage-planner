package com.storageplanner.v2.features;

import com.storageplanner.v2.device.ReadableFile;
import com.storageplanner.v2.model.*;
import com.storageplanner.v2.stage.Stage;
import com.storageplanner.v2.stage.StageContext;
import com.storageplanner.v2.stage.StageOutcome;
import com.storageplanner.v2.store.StoreException;
import com.storageplanner.v2.util.Io;
import com.storageplanner.v2.util.Text;

import java.io.IOException;
import java.util.*;

/**
 * Tier 2 (PHASE_2 3.4). Part A: archive / APK structure. Part B: HashStage — size groups, quick hash, full SHA-256 + CRC32 inside
 * quick-hash collisions (A10), then exact-duplicate group ids ("x:" + sha256) on FileRecord.duplicateGroupId.
 */
public final class T2HashStructStage implements Stage {
    public static final String ID = "T2_HASH_STRUCT";
    static final long SMALL = 131072;
    private final boolean all;
    private final Limits lim;

    public T2HashStructStage(boolean hashAll) { this(hashAll, Limits.DEFAULT); }
    public T2HashStructStage(boolean hashAll, Limits lim) { this.all = hashAll; this.lim = lim; }
    public String id() { return ID; }

    public StageOutcome run(StageContext ctx) throws StoreException {
        StageOutcome a = partA(ctx);
        if (a != StageOutcome.DONE) return a;
        return partB(ctx);
    }

    // ------------------------------------------------------------------------------------------------ Part A

    static boolean archiveType(String t) { return FileType.isZipFamily(t) || t.equals("tar"); }

    StageOutcome partA(final StageContext ctx) throws StoreException {
        final long scanId = ctx.session.scanId;
        final boolean full = ctx.session.kind == ScanKind.FULL;
        List<FileRecord> jobs = new ArrayList<FileRecord>();
        for (FileRecord f : ExtractionLoop.present(ctx)) {
            if (!archiveType(f.type) || f.readable == Tri.FALSE || f.sizeBytes <= 0) continue;
            FeatureRecord fr = ctx.store.features().get(f.fileId);
            if (fr == null || fr.fp == null || !fr.fp.sameAs(f.fingerprint())) continue;   // T1 first
            int id = f.type.equals("tar") ? Extractors.TAR : Extractors.ZIP;
            if (fr.versions[id] != Extractors.version(id) || (f.type.equals("apk") && fr.versions[Extractors.APK] != Extractors.version(Extractors.APK))
                    || (full && fr.archive != null && !Long.toString(scanId).equals(fr.extra.get("t2scan")))) jobs.add(f);
        }
        return ExtractionLoop.run(ctx, ID, jobs, Tiers.T2, new ExtractionLoop.Job() {
            public void run(FileRecord f) throws StoreException { structure(ctx, f, scanId); }
        });
    }

    void structure(StageContext ctx, FileRecord f, long scanId) throws StoreException {
        FeatureRecord fr = ctx.store.features().get(f.fileId);
        Io.CountingReadableFile rf = null;
        ctx.session.count("T2.extracted", 1);
        try {
            rf = new Io.CountingReadableFile(ctx.device.storage().open(f.volumeId, f.accessPath()));
            Archives.structure(ctx, f, fr, rf, lim);
        } catch (IOException e) {
            f.lastError = Text.truncate("T2: " + e, 200);
            fr.problem("zip:IO_ERROR");
        } catch (RuntimeException e) {
            fr.problem("stage:EXCEPTION");
            fr.problem("zip:EXCEPTION:" + e.getClass().getSimpleName());
        } finally {
            T1MetadataStage.closeQuietly(rf);
            T1MetadataStage.charge(ctx, rf);
        }
        Extractors.stamp(fr, new int[]{f.type.equals("tar") ? Extractors.TAR : Extractors.ZIP});
        if (f.type.equals("apk") || f.type.equals("apks") || f.type.equals("xapk")) Extractors.stamp(fr, new int[]{Extractors.APK});
        fr.extra.put("t2scan", Long.toString(scanId));
        f.mimeType = MimeMap.of(f.type) != null ? MimeMap.of(f.type) : f.mimeType;
        Confidence.apply(f, fr);
        ctx.store.features().put(fr);
        ctx.store.files().put(f);
    }

    // ------------------------------------------------------------------------------------------------ Part B

    static boolean current(HashRecord hr, FileRecord f) { return hr != null && hr.fp != null && hr.fp.sameAs(f.fingerprint()); }

    StageOutcome partB(StageContext ctx) throws StoreException {
        if (all) ctx.session.counters.put("hash.policyAll", 1L);
        long now = ctx.device.clock().nowMillis();
        List<FileRecord> present = ExtractionLoop.present(ctx);
        Map<Long, List<FileRecord>> groups = new TreeMap<Long, List<FileRecord>>();
        Set<Long> entrySizes = new HashSet<Long>();
        for (FileRecord f : present) {
            if (f.sizeBytes >= 1 && f.readable != Tri.FALSE) {
                List<FileRecord> g = groups.get(f.sizeBytes);
                if (g == null) groups.put(f.sizeBytes, g = new ArrayList<FileRecord>());
                g.add(f);
            }
            if (archiveType(f.type)) {
                FeatureRecord fr = ctx.store.features().get(f.fileId);
                if (fr != null && fr.archive != null && fr.archive.entries != null)
                    for (ArchiveEntry e : fr.archive.entries) if (!e.dir && e.size >= 1) entrySizes.add(e.size);
            }
        }
        List<List<FileRecord>> work = new ArrayList<List<FileRecord>>();
        for (List<FileRecord> g : groups.values()) {
            boolean need = all || g.size() >= 2 || entrySizes.contains(g.get(0).sizeBytes);
            if (need) { work.add(g); continue; }
            FileRecord f = ctx.store.files().get(g.get(0).fileId);   // size-unique: provisional "done", sha256 only if still current
            HashRecord hr = ctx.store.hashes().get(f.fileId);
            f.sha256 = current(hr, f) ? hr.sha256 : null;
            finishBit(ctx, f);
        }
        ctx.session.counters.put("hash.sizeGroups", (long) work.size());
        Collections.sort(work, new Comparator<List<FileRecord>>() {
            public int compare(List<FileRecord> a, List<FileRecord> b) {
                long sa = a.get(0).sizeBytes, sb = b.get(0).sizeBytes;
                int c = Long.compare(sb * (b.size() - 1), sa * (a.size() - 1));
                return c != 0 ? c : Long.compare(sb, sa);
            }
        });
        int sinceCommit = 0;
        for (int gi = 0; gi < work.size(); gi++) {
            StageOutcome stop = ExtractionLoop.stopReason(ctx);
            if (stop != null) {
                long pend = 0;
                for (int k = gi; k < work.size(); k++) for (FileRecord m : work.get(k)) {
                    FileRecord f = ctx.store.files().get(m.fileId); Tiers.pending(f, Tiers.T2); Confidence.apply(f, ctx.store.features().get(f.fileId)); ctx.store.files().put(f); pend++;
                }
                if (stop == StageOutcome.BUDGET_EXHAUSTED) ctx.session.count("budget.pendingFiles", pend);
                ctx.store.scanSessions().put(ctx.session);
                ctx.store.commit();
                return stop;
            }
            List<FileRecord> g = new ArrayList<FileRecord>(work.get(gi));
            Collections.sort(g, new Comparator<FileRecord>() { public int compare(FileRecord a, FileRecord b) { return a.path.compareTo(b.path); } });
            long size = g.get(0).sizeBytes;
            boolean fullAll = all || size <= SMALL || entrySizes.contains(size);
            Map<String, HashRecord> recs = new LinkedHashMap<String, HashRecord>();
            Set<String> failed = new HashSet<String>();
            for (FileRecord m : g) {
                HashRecord hr = ctx.store.hashes().get(m.fileId);
                if (current(hr, m) && hr.quickHash != null && (!fullAll || hr.sha256 != null)) { recs.put(m.fileId, hr); ctx.session.count("hash.reused", 1); continue; }
                HashRecord n = hash(ctx, m, fullAll, now);
                if (n == null) failed.add(m.fileId); else recs.put(m.fileId, n);
            }
            if (!fullAll) {
                Map<String, List<String>> byQ = new HashMap<String, List<String>>();
                for (HashRecord hr : recs.values()) { List<String> l = byQ.get(hr.quickHash); if (l == null) byQ.put(hr.quickHash, l = new ArrayList<String>()); l.add(hr.fileId); }
                for (List<String> sub : byQ.values()) {
                    if (sub.size() < 2) continue;
                    for (String id : sub) {
                        HashRecord hr = recs.get(id);
                        if (hr.sha256 != null) continue;
                        HashRecord n = hash(ctx, ctx.store.files().get(id), true, now);
                        if (n == null) { failed.add(id); recs.remove(id); } else recs.put(id, n);
                    }
                }
            }
            for (FileRecord m : g) {
                FileRecord f = ctx.store.files().get(m.fileId);
                HashRecord hr = recs.get(m.fileId);
                if (failed.contains(m.fileId) || hr == null) {
                    f.sha256 = null; Tiers.failed(f, Tiers.T2); ctx.session.count("hash.failed", 1);
                    Confidence.apply(f, ctx.store.features().get(f.fileId));
                    ctx.store.files().put(f);
                    continue;
                }
                ctx.store.hashes().put(hr);
                f.sha256 = hr.sha256;
                finishBit(ctx, f);
                sinceCommit++;
            }
            if (sinceCommit >= ExtractionLoop.COMMIT_EVERY) { ctx.store.scanSessions().put(ctx.session); ctx.store.commit(); sinceCommit = 0; }
        }
        assignGroups(ctx);
        ctx.store.scanSessions().put(ctx.session);
        ctx.store.commit();
        return StageOutcome.DONE;
    }

    void finishBit(StageContext ctx, FileRecord f) {
        if ((f.tiersDone & Tiers.T1) != 0) Tiers.done(f, Tiers.T2);
        Confidence.apply(f, ctx.store.features().get(f.fileId));
        ctx.store.files().put(f);
    }

    /** null on a read error (lastError set on the FileRecord). */
    HashRecord hash(StageContext ctx, FileRecord f, boolean fullHash, long now) {
        Io.CountingReadableFile rf = null;
        try {
            rf = new Io.CountingReadableFile(ctx.device.storage().open(f.volumeId, f.accessPath()));
            HashRecord hr = new HashRecord();
            hr.fileId = f.fileId; hr.fp = f.fingerprint(); hr.hashedAt = now;
            if (fullHash) {
                String[] h = QuickHash.full(rf, f.sizeBytes);
                hr.sha256 = h[0]; hr.crc32 = Long.parseLong(h[1]); hr.quickHash = h[2];
                hr.reason = all ? "ALL" : "SIZE_COLLISION";
                ctx.session.count("hash.fullHashed", 1);
            } else {
                hr.quickHash = QuickHash.of(rf, f.sizeBytes);
                ctx.session.count("hash.quickHashed", 1);
            }
            ctx.session.count("T2.extracted", 1);
            return hr;
        } catch (IOException e) {
            FileRecord cur = ctx.store.files().get(f.fileId);
            cur.lastError = Text.truncate("HASH: " + e, 200);
            ctx.store.files().put(cur);
            return null;
        } finally {
            T1MetadataStage.closeQuietly(rf);
            if (rf != null) { T1MetadataStage.charge(ctx, rf); ctx.session.count("hash.bytesRead", rf.bytes); }
        }
    }

    /** Exact-duplicate group ids over PRESENT files with a SHA-256 (A10: only hashed files can be grouped). */
    static void assignGroups(StageContext ctx) {
        Map<String, Integer> count = new HashMap<String, Integer>();
        List<FileRecord> present = ExtractionLoop.present(ctx);
        for (FileRecord f : present) if (f.sha256 != null && f.sizeBytes > 0) { Integer c = count.get(f.sha256); count.put(f.sha256, c == null ? 1 : c + 1); }
        for (FileRecord f : present) {
            String id = f.sha256 != null && f.sizeBytes > 0 && count.get(f.sha256) >= 2 ? "x:" + f.sha256 : null;
            if (id == null ? f.duplicateGroupId != null : !id.equals(f.duplicateGroupId)) {
                FileRecord c = ctx.store.files().get(f.fileId); c.duplicateGroupId = id; ctx.store.files().put(c);
            }
        }
    }
}
