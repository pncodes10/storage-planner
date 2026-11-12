package com.storageplanner.v2test;

import com.storageplanner.v2.model.*;
import com.storageplanner.v2.model.Record;
import com.storageplanner.v2.store.*;
import com.storageplanner.v2.store.disk.TableIds;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/**
 * PHASE_S_STORE S2: random operation sequences on DiskStore and MemoryStore (the oracle), over all 17 tables, with
 * keys mixing ASCII, non-hex ids, emoji, U+E000 and U+FF01–U+FFEF (F-03). Results of every query are compared; full
 * dumps (pair checks included) after every commit/rollback/flush and after every reopen (clean close, process kill,
 * power loss), where the disk store must equal the oracle at its last checkpoint. Iterators are interleaved with writes.
 * Seeds: -Dv2.oracleSeeds (default 300), ops per seed -Dv2.oracleOps (default 300).
 */
public final class StoreOracleTest {
    static final String[] IDS = {"0123456789abcdef0123456789abcdef", "fedcba9876543210fedcba9876543210", "f1", "f2", "Z", "é1",
            "😀a", "x", "！y", "aÿb", "x😀～", "￯q"};
    static final String[] VOLS = {"v", "w", "é"};
    static final String[] DIRS = {"", "a", "a/b", "ab", "a0", "DCIM/Camera", "😀dir", "", "Ｄ", "a/😀", "a/！"};
    static final String[] NAMES = {"x.jpg", "😀.png", "！.txt", ".bin", "é è.txt", "0", "b", "a"};
    static final String[] SHAS = {null, null, "aa", "bb"};
    static final OpPhase[] PHASES = OpPhase.values();

    static long ops, seqs, reopens, crashes, powerLosses, interleaved, checked, compactionsForced, compactionsTotal;

    static String pick(Random r, String[] a) { return a[r.nextInt(a.length)]; }
    static String path(Random r) { String d = pick(r, DIRS); String n = pick(r, NAMES); return d.isEmpty() ? n : d + "/" + n; }

    /** A random record of table id (1..17). */
    static Record make(int id, Random r) {
        switch (id) {
            case TableIds.FILES: {
                FileRecord f = new FileRecord(); f.fileId = pick(r, IDS); f.volumeId = pick(r, VOLS); f.path = r.nextInt(8) == 0 ? pick(r, DIRS) : path(r);
                f.name = f.path.substring(f.path.lastIndexOf('/') + 1); f.sizeBytes = r.nextInt(4);
                f.presence = r.nextInt(3) == 0 ? Presence.REMOVED : r.nextInt(4) == 0 ? Presence.UNVERIFIABLE : Presence.PRESENT;
                f.changeScanId = r.nextInt(4); f.sha256 = pick(r, SHAS);
                return f;
            }
            case TableIds.FEATURES: { FeatureRecord x = new FeatureRecord(); x.fileId = pick(r, IDS); x.extractedScanId = r.nextInt(3); return x; }
            case TableIds.HASHES: { HashRecord x = new HashRecord(); x.fileId = pick(r, IDS); x.crc32 = r.nextInt(3); return x; }
            case TableIds.EMBEDDINGS: { EmbeddingRecord x = new EmbeddingRecord(); x.fileId = pick(r, IDS); x.modelId = r.nextBoolean() ? "m" : "ñ"; return x; }
            case TableIds.RELATIONSHIPS: {
                Relationship x = new Relationship(); x.edgeId = "e" + r.nextInt(6) + (r.nextInt(5) == 0 ? "😀" : "");
                x.src = r.nextInt(8) == 0 ? null : pick(r, IDS); x.dst = r.nextInt(8) == 0 ? null : pick(r, IDS); x.createdScanId = r.nextInt(3);
                return x;
            }
            case TableIds.DUPLICATE_GROUPS: { DuplicateGroup x = new DuplicateGroup(); x.groupId = "x:" + pick(r, IDS); x.memberBytes = r.nextInt(3); return x; }
            case TableIds.BACKUPS: { BackupRecord x = new BackupRecord(); x.backupId = "b" + r.nextInt(5); x.verifiedAt = r.nextInt(3); return x; }
            case TableIds.PAIR_CHECKS: {
                PairCheck x = new PairCheck(); x.a = pick(r, IDS); x.b = pick(r, IDS); x.pairKey = x.a + "|" + x.b; x.checkedScanId = r.nextInt(3);
                x.verdict = PairVerdict.values()[r.nextInt(PairVerdict.values().length)];
                return x;
            }
            case TableIds.RECOMMENDATIONS: {
                Recommendation x = new Recommendation(); x.recommendationId = "r" + r.nextInt(6) + (r.nextInt(4) == 0 ? "！" : "");
                x.fileId = r.nextInt(6) == 0 ? null : pick(r, IDS); x.createdScanId = r.nextInt(3);
                return x;
            }
            case TableIds.ACTION_PLANS: { ActionPlan x = new ActionPlan(); x.planId = "p" + r.nextInt(3); x.targetBytes = r.nextInt(3); return x; }
            case TableIds.OPERATIONS: {
                OperationEntry x = new OperationEntry(); x.opId = r.nextBoolean() ? "o" + r.nextInt(3) : "ö😀"; x.seq = r.nextInt(4);
                x.phase = PHASES[r.nextInt(PHASES.length)];
                return x;
            }
            case TableIds.FEEDBACK: { Feedback x = new Feedback(); x.feedbackId = pick(r, IDS); return x; }
            case TableIds.PREFERENCES: { Preference x = new Preference(); x.name = "p" + r.nextInt(4); x.value = "v" + r.nextInt(3); return x; }
            case TableIds.MODELS: { ModelRecord x = new ModelRecord(); x.modelId = pick(r, IDS); return x; }
            case TableIds.SESSIONS: { ScanSession x = new ScanSession(); x.scanId = r.nextInt(5); x.kind = ScanKind.FULL; x.state = ScanState.RUNNING; x.resumedCount = r.nextInt(3); return x; }
            case TableIds.VOLUMES: { Volume x = new Volume(); x.volumeId = pick(r, VOLS); x.totalBytes = r.nextInt(3); return x; }
            default: {
                DirRecord d = new DirRecord(); d.volumeId = pick(r, VOLS); d.path = pick(r, DIRS); d.status = r.nextBoolean() ? DirStatus.OK : DirStatus.NOT_FOUND;
                d.entries = r.nextInt(3);
                return d;
            }
        }
    }

    @SuppressWarnings("rawtypes")
    static Table table(V2Store s, int id) { return (Table) FrameTest.tables(s)[id - 1][0]; }
    @SuppressWarnings("rawtypes")
    static Codecs.Codec codec(int id) { return TableIds.codec(id); }

    @SuppressWarnings("unchecked")
    static String json(int id, Object r) { return r == null ? "null" : Codecs.json(codec(id), (Record) r); }

    /** All 17 tables (StorePersistenceTest.dumpAll leaves out pair checks). */
    @SuppressWarnings("unchecked")
    static String dump(V2Store s) {
        StringBuilder b = new StringBuilder();
        for (int id = 1; id <= TableIds.COUNT; id++) {
            b.append('#').append(TableIds.name(id)).append(' ').append(table(s, id).size()).append('\n');
            for (Object r : table(s, id).all()) b.append(json(id, r)).append('\n');
        }
        return b.toString();
    }

    static String jsonList(int id, Iterable<?> l) {
        StringBuilder b = new StringBuilder("[");
        for (Object o : l) b.append(json(id, o)).append(',');
        return b.append(']').toString();
    }

    @SuppressWarnings("unchecked")
    static MemoryStore copy(V2Store s) throws StoreException {
        MemoryStore m = MemoryStore.volatileStore();
        for (int id = 1; id <= TableIds.COUNT; id++) for (Object r : table(s, id).all()) table(m, id).put((Record) r);
        m.commit();
        return m;
    }

    /** Same op on both; returns a comparable result string. */
    @SuppressWarnings("unchecked")
    static String write(V2Store s, int id, Record rec, boolean remove) {
        try {
            if (remove) return String.valueOf(table(s, id).remove(rec.key()));
            table(s, id).put(rec.copy());
            return "ok";
        } catch (IllegalStateException e) {
            return "ISE";
        }
    }

    static final class Fail extends RuntimeException { Fail(String m) { super(m); } }
    static void same(String what, String a, String b) {
        checked++;
        if (!a.equals(b)) throw new Fail(what + "\n  oracle: " + abbreviate(a) + "\n  disk:   " + abbreviate(b));
    }
    static String abbreviate(String s) { return s.length() > 3000 ? s.substring(0, 3000) + "…" : s; }

    static DiskStore.Options options(Random r) {
        DiskStore.Options o = new DiskStore.Options();
        int[] merge = {1, 8, 8, 4096};
        o.mergeMin = merge[r.nextInt(merge.length)];
        long[] seg = {700, 4096, 1L << 20};
        o.maxSegmentBytes = seg[r.nextInt(seg.length)];
        long[] tail = {0, 2048, 8L << 20};
        o.indexTailBytes = tail[r.nextInt(tail.length)];
        int[] lru = {0, 3, 4096};
        o.lruRecords = lru[r.nextInt(lru.length)];
        o.indexAtClose = r.nextInt(4) != 0;
        long[] cmin = {0, 4096, 64L << 20};         // S4: compaction at checkpoints whenever garbage ≥ max(min, live)
        o.compactMinGarbage = cmin[r.nextInt(cmin.length)];
        return o;
    }

    /** One sequence; returns null or a failure description. */
    static String sequence(long seed, int nOps) throws Exception {
        Random r = new Random(seed);
        PowerLossBlobStore blobs = new PowerLossBlobStore();
        FixedClock clock = new FixedClock();
        long[] every = {0, 0, 7, -1};
        long ckEvery = every[r.nextInt(every.length)];
        DiskStore.Options o = options(r);
        DiskStore d = DiskStore.create(blobs, ckEvery, clock, o);
        MemoryStore m = MemoryStore.volatileStore();
        MemoryStore durableM = copy(m);
        int lastCk = d.checkpointsWritten();
        int i = 0;
        try {
            for (i = 0; i < nOps; i++) {
                ops++;
                if (i % 50 == 49) {                                                     // S4: compaction forced every 50 ops
                    m.flush();
                    if (d.compactNow()) compactionsForced++;
                    same("dump after compaction", dump(m), dump(d));
                    lastCk = d.checkpointsWritten(); durableM = copy(m);
                    continue;
                }
                int p = r.nextInt(1000);
                int id = r.nextInt(5) == 0 ? TableIds.FILES : 1 + r.nextInt(TableIds.COUNT);
                if (p < 330) {                                                          // put
                    Record rec = make(id, r);
                    same("put " + TableIds.name(id), write(m, id, rec, false), write(d, id, rec, false));
                } else if (p < 400) {                                                   // unchanged re-put (no-op)
                    Record rec = make(id, r);
                    Object cur = table(m, id).get(rec.key());
                    if (cur != null && id != TableIds.OPERATIONS) {
                        long before = d.changedPutsSinceOpen();
                        same("re-put", write(m, id, (Record) cur, false), write(d, id, (Record) cur, false));
                        same("re-put is a no-op", "" + before, "" + d.changedPutsSinceOpen());
                    }
                } else if (p < 500) {                                                   // remove
                    Record rec = make(id, r);
                    same("remove " + TableIds.name(id), write(m, id, rec, true), write(d, id, rec, true));
                } else if (p < 580 || p >= 990) {                                       // commit / rollback / flush
                    String what;
                    if (p < 545) { m.commit(); d.commit(); what = "commit"; }
                    else if (p < 570 || p >= 990) { m.rollback(); d.rollback(); what = "rollback"; }
                    else { m.flush(); d.flush(); what = "flush"; }
                    same("dump after " + what, dump(m), dump(d));
                    if (d.checkpointsWritten() != lastCk && !"rollback".equals(what)) { lastCk = d.checkpointsWritten(); durableM = copy(m); }
                } else if (p < 600) {                                                   // reopen: clean close, kill, power loss
                    int how = r.nextInt(3);
                    if (how == 0) {
                        d.close(); m.commit(); durableM = copy(m);
                    } else {
                        if (how == 2) { blobs.powerLoss(r, r.nextBoolean(), r.nextBoolean()); powerLosses++; } else crashes++;
                        m = copy(durableM);
                    }
                    reopens++;
                    d = DiskStore.open(blobs, ckEvery, clock, o);
                    lastCk = d.checkpointsWritten();
                    same("dump after reopen (" + (how == 0 ? "close" : how == 1 ? "kill" : "power loss") + ")", dump(m), dump(d));
                } else if (p < 610) {
                    d.debugDropCache();
                } else if (p < 700) {                                                   // point queries
                    Record rec = make(id, r);
                    same("get " + TableIds.name(id), json(id, table(m, id).get(rec.key())), json(id, table(d, id).get(rec.key())));
                    same("size " + TableIds.name(id), "" + table(m, id).size(), "" + table(d, id).size());
                } else if (p < 800) {                                                   // secondary queries
                    String v = pick(r, VOLS), dir = pick(r, DIRS), x = pick(r, IDS);
                    int q = r.nextInt(12);
                    switch (q) {
                        case 0: { String pp = path(r); same("byPath", json(1, m.files().byPath(v, pp)), json(1, d.files().byPath(v, pp))); break; }
                        case 1: same("childrenOf files", jsonList(1, m.files().childrenOf(v, dir)), jsonList(1, d.files().childrenOf(v, dir))); break;
                        case 2: same("childrenOf dirs", jsonList(17, m.directories().childrenOf(v, dir)), jsonList(17, d.directories().childrenOf(v, dir))); break;
                        case 3: same("underPrefix files", jsonList(1, m.files().underPrefix(v, dir)), jsonList(1, d.files().underPrefix(v, dir))); break;
                        case 4: same("underPrefix dirs", jsonList(17, m.directories().underPrefix(v, dir)), jsonList(17, d.directories().underPrefix(v, dir))); break;
                        case 5: { long sz = r.nextInt(4); same("bySize", jsonList(1, m.files().bySize(sz)), jsonList(1, d.files().bySize(sz))); break; }
                        case 6: { String sh = pick(r, SHAS); if (sh != null) same("bySha256", jsonList(1, m.files().bySha256(sh)), jsonList(1, d.files().bySha256(sh))); break; }
                        case 7: same("relationships.of", jsonList(5, m.relationships().of(x)), jsonList(5, d.relationships().of(x))); break;
                        case 8: same("forFile", jsonList(9, m.recommendations().forFile(x)), jsonList(9, d.recommendations().forFile(x))); break;
                        case 9: { String op = r.nextBoolean() ? "o" + r.nextInt(3) : "ö😀"; same("forOp", jsonList(11, m.operations().forOp(op)), jsonList(11, d.operations().forOp(op))); break; }
                        case 10: same("openOps", m.operations().openOps().toString(), d.operations().openOps().toString()); break;
                        default: same("byVolume", jsonList(1, m.files().byVolume(v)), jsonList(1, d.files().byVolume(v))); break;
                    }
                    same("byPath existing", json(1, m.files().byPath(v, dir)), json(1, d.files().byPath(v, dir)));
                } else {                                                                // iterators interleaved with writes
                    interleaved++;
                    int kind = r.nextInt(5);
                    String v = pick(r, VOLS), dir = pick(r, DIRS);
                    Iterator<?> im, idk;
                    int jid = id;
                    if (kind == 0) { im = table(m, id).all().iterator(); idk = table(d, id).all().iterator(); }
                    else if (kind == 1) { im = table(m, id).keys().iterator(); idk = table(d, id).keys().iterator(); jid = -1; }
                    else if (kind == 2) { im = m.files().underPrefix(v, dir).iterator(); idk = d.files().underPrefix(v, dir).iterator(); jid = 1; }
                    else if (kind == 3) { im = m.directories().underPrefix(v, dir).iterator(); idk = d.directories().underPrefix(v, dir).iterator(); jid = 17; }
                    else { im = m.files().byVolume(v).iterator(); idk = d.files().byVolume(v).iterator(); jid = 1; }
                    StringBuilder a = new StringBuilder(), b = new StringBuilder();
                    while (true) {
                        boolean hm = im.hasNext(), hd = idk.hasNext();
                        same("iterator hasNext", "" + hm, "" + hd);
                        if (!hm) break;
                        Object x = im.next(), y = idk.next();
                        a.append(jid < 0 ? x : json(jid, x)).append('\n'); b.append(jid < 0 ? y : json(jid, y)).append('\n');
                        if (r.nextInt(3) == 0) {
                            int wid = r.nextInt(3) == 0 ? jid < 0 ? id : jid : 1 + r.nextInt(TableIds.COUNT);
                            Record rec = make(wid, r);
                            boolean rm = r.nextInt(3) == 0;
                            same("write while iterating", write(m, wid, rec, rm), write(d, wid, rec, rm));
                        }
                    }
                    same("interleaved iteration", a.toString(), b.toString());
                }
            }
            m.commit(); d.commit();
            compactionsTotal += d.compactions();
            same("final dump", dump(m), dump(d));
            d.close();
            DiskStore again = DiskStore.open(blobs, ckEvery, clock, o);
            same("final reopen", dump(m), dump(again));
            again.close();
            return null;
        } catch (Fail f) {
            return "seed " + seed + " op " + i + ": " + f.getMessage();
        } catch (RuntimeException e) {
            java.io.StringWriter sw = new java.io.StringWriter();
            e.printStackTrace(new java.io.PrintWriter(sw));
            return "seed " + seed + " op " + i + ": exception " + sw;
        } catch (StoreException e) {
            return "seed " + seed + " op " + i + ": StoreException " + e.getMessage();
        }
    }

    public static int run(String[] args) throws Exception {
        T.section("StoreOracle");
        int seeds = Integer.getInteger("v2.oracleSeeds", 300);
        int nOps = Integer.getInteger("v2.oracleOps", 300);
        compactionsForced = compactionsTotal = 0;
        ops = seqs = reopens = crashes = powerLosses = interleaved = checked = 0;
        int failed = 0;
        for (long seed = 1; seed <= seeds; seed++) {
            String f = sequence(seed, nOps);
            seqs++;
            if (f != null) { failed++; if (failed <= 3) System.out.println("  " + f); }
        }
        System.out.println("  oracle: " + seqs + " sequences, " + ops + " ops, " + checked + " comparisons, " + reopens + " reopens ("
                + crashes + " kills, " + powerLosses + " power losses), " + interleaved + " interleaved iterations, "
                + compactionsForced + " forced compactions (+ " + compactionsTotal + " counted by the last store instances)");
        T.eq("oracle sequences failing (of " + seeds + ")", 0, failed);
        return T.finish("StoreOracleTest");
    }

    public static void main(String[] args) throws Exception { System.exit(run(args) == 0 ? 0 : 1); }
}
