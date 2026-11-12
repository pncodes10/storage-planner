package com.storageplanner.v2.graph;

import com.storageplanner.v2.device.ReadableFile;
import com.storageplanner.v2.model.*;
import com.storageplanner.v2.stage.StageContext;
import com.storageplanner.v2.store.Codecs;
import com.storageplanner.v2.store.StoreException;
import com.storageplanner.v2.util.BoundedInflater;
import com.storageplanner.v2.util.Hashing;
import com.storageplanner.v2.util.Hex;

import java.io.IOException;
import java.util.*;
import java.util.zip.CRC32;

/** Archive entries (CONTAINED_IN) and archive backups (BACKED_UP_BY) with in-memory SHA-256 verification (PHASE_3 6). */
final class ArchiveMatcher {
    private ArchiveMatcher() {}

    static void run(StageContext ctx, List<Node> nodes, List<Relationship> edges, Map<String, Long> cov) throws StoreException {
        long scanId = ctx.session == null ? 0 : ctx.session.scanId;
        Map<String, List<Object[]>> bySizeCrc = new HashMap<String, List<Object[]>>();   // size:crc -> {archive node, entry}
        Set<String> archives = new HashSet<String>();
        for (Node a : nodes) {
            ArchiveInfo ai = a.fr == null ? null : a.fr.archive;
            if (ai == null || ai.entries == null || !"OK".equals(ai.listingStatus) || a.alias()) continue;
            if (a.fr.fp != null && !a.fr.fp.sameAs(a.fp)) continue;
            archives.add(a.id());
            for (ArchiveEntry e : ai.entries) {
                if (e.dir || e.size < 1 || e.depth > 0) continue;
                String ref = a.id() + "!" + e.path;
                Relationship r = Edges.make(ref, a.id(), RelType.CONTAINED_IN, 1.0, Verification.VERIFIED, null, a.fp, scanId);
                Edges.ev(r, "ARCHIVE_LISTING", null);
                edges.add(r);
                if (e.crc32 >= 0) {
                    String k = e.size + ":" + e.crc32;
                    List<Object[]> l = bySizeCrc.get(k);
                    if (l == null) bySizeCrc.put(k, l = new ArrayList<Object[]>());
                    l.add(new Object[]{a, e});
                }
            }
        }
        Map<String, BackupRecord> want = new TreeMap<String, BackupRecord>();
        List<Object[]> todo = new ArrayList<Object[]>();
        for (Node f : nodes) {
            if (f.alias() || archives.contains(f.id()) || f.sha() == null || f.hr.crc32 < 0) continue;
            List<Object[]> m = bySizeCrc.get(f.f.sizeBytes + ":" + f.hr.crc32);
            if (m == null) continue;
            int c = 0;
            for (Object[] x : m) {
                if (c++ >= GraphConfig.MAX_BACKUP_MATCHES) break;
                Node a = (Node) x[0]; ArchiveEntry e = (ArchiveEntry) x[1];
                String ref = a.id() + "!" + e.path;
                String id = Edges.make(f.id(), ref, RelType.BACKED_UP_BY, 0, Verification.UNVERIFIED, null, null, 0).edgeId;
                BackupRecord old = ctx.store.backups().get(id);
                BackupRecord b;
                if (old != null && f.fp.equals(old.subjectFp) && a.fp.equals(old.copyFp) && old.state != BackupState.UNKNOWN && old.state != BackupState.POSSIBLE) b = old;
                else {
                    b = new BackupRecord(); b.backupId = id; b.subjectId = f.id(); b.copyRef = ref; b.kind = BackupKind.ARCHIVE_ENTRY;
                    b.level = BackupLevel.CRC32_MATCH; b.state = BackupState.POSSIBLE; b.subjectFp = f.fp; b.copyFp = a.fp;
                    todo.add(new Object[]{f, a, e, b});
                }
                want.put(id, b);
            }
        }
        // BackupVerifier: priority = subject size desc, then id
        Collections.sort(todo, new Comparator<Object[]>() {
            public int compare(Object[] x, Object[] y) {
                long sx = ((Node) x[0]).f.sizeBytes, sy = ((Node) y[0]).f.sizeBytes;
                return sx != sy ? Long.compare(sy, sx) : ((BackupRecord) x[3]).backupId.compareTo(((BackupRecord) y[3]).backupId);
            }
        });
        com.storageplanner.v2.device.Clock clock = ctx.device == null ? null : ctx.device.clock();
        for (Object[] t : todo) {
            if (ctx.budget != null && clock != null && ctx.budget.exhausted(clock)) break;
            verify(ctx, (Node) t[0], (Node) t[1], (ArchiveEntry) t[2], (BackupRecord) t[3]);
        }
        for (BackupRecord b : want.values()) {
            Node f = null;
            for (Node n : nodes) if (n.id().equals(b.subjectId)) { f = n; break; }
            Node a = null; String aid = b.copyRef.substring(0, b.copyRef.indexOf('!'));
            for (Node n : nodes) if (n.id().equals(aid)) { a = n; break; }
            if (b.state == BackupState.CORRUPT || b.state == BackupState.MISSING) { inc(cov, "backup" + b.state); continue; }
            boolean v = b.state == BackupState.VERIFIED;
            Relationship r = Edges.make(f.id(), b.copyRef, RelType.BACKED_UP_BY, v ? 1.0 : 0.6, v ? Verification.VERIFIED : Verification.UNVERIFIED, f.fp, a.fp, scanId);
            r.verifiedAt = v ? b.verifiedAt : Unknown.TIME;
            Edges.ev(r, v ? "ENTRY_SHA256_EQUAL" : "ZIP_SIZE_CRC_EQUAL", null);
            edges.add(r);
            inc(cov, "backup" + b.state);
        }
        GraphStage.diff(ctx.store.backups(), Codecs.BACKUP, want, 0);
    }

    static void inc(Map<String, Long> cov, String k) { Long o = cov.get(k); cov.put(k, (o == null ? 0 : o) + 1); }

    static void verify(StageContext ctx, Node f, Node a, ArchiveEntry e, BackupRecord b) {
        Integrity ai = a.f.integrity;
        if (ai == Integrity.CORRUPT || ai == Integrity.TRUNCATED || ai == Integrity.ENCRYPTED || e.encrypted == Tri.TRUE
                || !(e.method == 0 || e.method == 8) || e.size > GraphConfig.VERIFY_MAX_ENTRY || e.localOffset < 0 || e.compressedSize < 0
                || e.size / Math.max(1, e.compressedSize) > 100) {
            b.state = BackupState.UNKNOWN; b.note = "PRECONDITION"; return;
        }
        try (ReadableFile rf = ctx.device.storage().open(a.f.volumeId, a.f.accessPath())) {
            byte[] lh = new byte[30];
            if (read(rf, e.localOffset, lh) != 30 || (lh[0] & 255) != 0x50 || (lh[1] & 255) != 0x4b || lh[2] != 3 || lh[3] != 4) { b.state = BackupState.UNKNOWN; b.note = "LOCAL_HEADER"; return; }
            int nl = (lh[26] & 255) | (lh[27] & 255) << 8, xl = (lh[28] & 255) | (lh[29] & 255) << 8;
            byte[] comp = new byte[(int) e.compressedSize];
            if (read(rf, e.localOffset + 30 + nl + xl, comp) != comp.length) { b.state = BackupState.UNKNOWN; b.note = "SHORT_READ"; return; }
            if (ctx.budget != null) ctx.budget.chargeBytes(comp.length + 30);
            byte[] data = e.method == 0 ? comp : BoundedInflater.inflate(comp, (int) e.size);
            if (data == null || data.length != e.size) { b.state = BackupState.CORRUPT; b.note = "INFLATE"; return; }
            CRC32 c = new CRC32(); c.update(data);
            if (c.getValue() != e.crc32) { b.state = BackupState.CORRUPT; b.note = "ENTRY_CRC_MISMATCH"; return; }
            String sha = Hex.of(Hashing.sha256Fresh().digest(data));
            if (sha.equals(f.sha())) { b.state = BackupState.VERIFIED; b.level = BackupLevel.SHA256_MATCH; b.verifiedAt = ctx.device.clock().nowMillis(); b.note = null; }
            else { b.state = BackupState.MISSING; b.note = "CONTENT_DIFFERS"; }
        } catch (IOException | RuntimeException x) {
            b.state = BackupState.UNKNOWN; b.note = "IO:" + x.getClass().getSimpleName();
        }
    }

    static int read(ReadableFile rf, long pos, byte[] buf) throws IOException {
        int o = 0;
        while (o < buf.length) { int n = rf.read(pos + o, buf, o, buf.length - o); if (n <= 0) break; o += n; }
        return o;
    }
}
