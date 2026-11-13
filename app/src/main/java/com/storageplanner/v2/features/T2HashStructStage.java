package com.storageplanner.v2.features;

import com.storageplanner.v2.device.ReadableFile;
import com.storageplanner.v2.model.*;
import com.storageplanner.v2.stage.Stage;
import com.storageplanner.v2.stage.StageContext;
import com.storageplanner.v2.stage.StageOutcome;
import com.storageplanner.v2.store.StoreException;
import com.storageplanner.v2.util.FileIdList;
import com.storageplanner.v2.util.Io;
import com.storageplanner.v2.util.PairSort;
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
        FileIdList jobs = new FileIdList();
        for (FileRecord f : ExtractionLoop.present(ctx)) {
            if (!archiveType(f.type) || f.readable == Tri.FALSE || f.sizeBytes <= 0) continue;
            FeatureRecord fr = ctx.store.features().get(f.fileId);
            if (fr == null || fr.fp == null || !fr.fp.sameAs(f.fingerprint())) continue;   // T1 first
            int id = f.type.equals("tar") ? Extractors.TAR : Extractors.ZIP;
            if (fr.versions[id] != Extractors.version(id) || (f.type.equals("apk") && fr.versions[Extractors.APK] != Extractors.version(Extractors.APK))
                    || (full && fr.archive != null && !Long.toString(scanId).equals(fr.extra.get("t2scan")))) jobs.add(f.fileId);
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
        // Size groups as one long[] of (size, ordinal) pairs sorted by (size, ordinal); ordinals index a FileIdList of the
        // eligible ids in ascending fileId, so each group's members come out in fileId order as the old TreeMap<size, List>
        // gave them. Records are loaded one group at a time (PHASE_S_STORE 4 item 2).
        final FileIdList ids = new FileIdList();
        long[] pairs = new long[64];
        int np = 0;
        Set<Long> entrySizes = new HashSet<Long>();
        for (FileRecord f : ExtractionLoop.present(ctx)) {
            if (f.sizeBytes >= 1 && f.readable != Tri.FALSE) {
                if (2 * np + 2 > pairs.length) pairs = Arrays.copyOf(pairs, Math.max(64, pairs.length + (pairs.length >> 1)) & ~1);
                pairs[2 * np] = f.sizeBytes; pairs[2 * np + 1] = ids.size(); np++;
                ids.add(f.fileId);
            }
            if (archiveType(f.type)) {
                FeatureRecord fr = ctx.store.features().get(f.fileId);
                if (fr != null && fr.archive != null && fr.archive.entries != null)
                    for (ArchiveEntry e : fr.archive.entries) if (!e.dir && e.size >= 1) entrySizes.add(e.size);
            }
        }
        PairSort.pairs(pairs, np);
        final long[] gp = pairs;
        // work groups that need hashing: wStart/wLen (pair index, members); work = their ordinals, sorted below
        int[] wStart = new int[16], wLen = new int[16];
        int nWork = 0;
        for (int i = 0; i < np; ) {
            int j = i + 1;
            while (j < np && gp[2 * j] == gp[2 * i]) j++;
            boolean need = all || j - i >= 2 || entrySizes.contains(gp[2 * i]);
            if (need) {
                if (nWork == wStart.length) { wStart = Arrays.copyOf(wStart, nWork * 2); wLen = Arrays.copyOf(wLen, nWork * 2); }
                wStart[nWork] = i; wLen[nWork] = j - i; nWork++;
            } else {
                FileRecord f = ctx.store.files().get(ids.get((int) gp[2 * i + 1]));   // size-unique: provisional "done", sha256 only if still current
                HashRecord hr = ctx.store.hashes().get(f.fileId);
                f.sha256 = current(hr, f) ? hr.sha256 : null;
                finishBit(ctx, f);
            }
            i = j;
        }
        ctx.session.counters.put("hash.sizeGroups", (long) nWork);
        final int[] ws = wStart, wl = wLen;
        int[] work = new int[nWork];
        for (int k = 0; k < nWork; k++) work[k] = k;
        PairSort.ints(work, nWork, new PairSort.IntOrder() {
            public int compare(int x, int y) {   // descending (size × (members − 1), size), as before; sizes are distinct
                long sa = gp[2 * ws[x]], sb = gp[2 * ws[y]];
                int c = Long.compare(sb * (wl[y] - 1), sa * (wl[x] - 1));
                return c != 0 ? c : Long.compare(sb, sa);
            }
        });
        int sinceCommit = 0;
        for (int gi = 0; gi < nWork; gi++) {
            StageOutcome stop = ExtractionLoop.stopReason(ctx);
            if (stop != null) {
                long pend = 0;
                for (int k = gi; k < nWork; k++) for (int q = ws[work[k]], end = q + wl[work[k]]; q < end; q++) {
                    FileRecord f = ctx.store.files().get(ids.get((int) gp[2 * q + 1])); Tiers.pending(f, Tiers.T2); Confidence.apply(f, ctx.store.features().get(f.fileId)); ctx.store.files().put(f); pend++;
                }
                if (stop == StageOutcome.BUDGET_EXHAUSTED) ctx.session.count("budget.pendingFiles", pend);
                ctx.store.scanSessions().put(ctx.session);
                ctx.store.commit();
                return stop;
            }
            List<FileRecord> g = new ArrayList<FileRecord>();
            for (int q = ws[work[gi]], end = q + wl[work[gi]]; q < end; q++) g.add(ctx.store.files().get(ids.get((int) gp[2 * q + 1])));
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
        // PHASE_S_STORE 4 item 2: a sorted long[] of 64-bit hashes of the SHA-256 strings; only a file whose hash occurs at
        // least twice can be in a group, and only those SHA-256s are counted exactly (a map bounded by duplicate candidates).
        long[] h = new long[64];
        int n = 0;
        for (FileRecord f : ExtractionLoop.present(ctx)) if (f.sha256 != null && f.sizeBytes > 0) {
            if (n == h.length) h = Arrays.copyOf(h, n + (n >> 1));
            h[n++] = hash64(f.sha256);
        }
        PairSort.longs(h, n);
        Map<String, Integer> count = new HashMap<String, Integer>();
        if (n > 1) for (FileRecord f : ExtractionLoop.present(ctx))
            if (f.sha256 != null && f.sizeBytes > 0 && occurs(h, n, hash64(f.sha256)) >= 2) { Integer c = count.get(f.sha256); count.put(f.sha256, c == null ? 1 : c + 1); }
        for (FileRecord f : ExtractionLoop.present(ctx)) {
            Integer c = f.sha256 != null && f.sizeBytes > 0 ? count.get(f.sha256) : null;
            String id = c != null && c >= 2 ? "x:" + f.sha256 : null;
            if (id == null ? f.duplicateGroupId != null : !id.equals(f.duplicateGroupId)) {
                FileRecord r = ctx.store.files().get(f.fileId); r.duplicateGroupId = id; ctx.store.files().put(r);
            }
        }
    }

    /** FNV-1a 64 over the chars. */
    static long hash64(String s) {
        long x = 0xcbf29ce484222325L;
        for (int i = 0; i < s.length(); i++) { x ^= s.charAt(i); x *= 0x100000001b3L; }
        return x;
    }

    /** Occurrences (capped at 2) of {@code v} in the sorted first {@code n} entries of {@code a}. */
    static int occurs(long[] a, int n, long v) {   // 0, 1 or 2 (= two or more)
        int lo = 0, hi = n;
        while (lo < hi) { int m = (lo + hi) >>> 1; if (a[m] < v) lo = m + 1; else hi = m; }
        return lo >= n || a[lo] != v ? 0 : lo + 1 < n && a[lo + 1] == v ? 2 : 1;
    }
}
