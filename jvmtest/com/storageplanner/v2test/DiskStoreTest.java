package com.storageplanner.v2test;

import com.storageplanner.v2.model.*;
import com.storageplanner.v2.model.Record;
import com.storageplanner.v2.store.*;
import com.storageplanner.v2.store.disk.Frame;
import com.storageplanner.v2.store.disk.KeyCmp;
import com.storageplanner.v2.store.disk.KeyIndex;
import com.storageplanner.v2.store.disk.TableIds;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

/**
 * PHASE_S_STORE slice S2: KeyCmp (F-03), KeyIndex, the store contract and persistence on DiskStore, kill points
 * (KillingBlobStore) with recovery exactly at the last checkpoint, power loss, the index file bound to the manifest
 * (F-01), stale segments (F-02), wrong-record detection (F-04), damage inside the durable range (F-05) and the
 * corruption rule per table (3.11). Kill trials: -Dv2.killTrials (default 300).
 */
public final class DiskStoreTest {
    static final FixedClock CLOCK = new FixedClock();

    // ------------------------------------------------------------------ F-03

    static String hex32(Random r) {
        StringBuilder b = new StringBuilder();
        String pool = r.nextBoolean() ? "0123456789abcdef" : "0f";                 // narrow pool: shared prefixes
        for (int i = 0; i < 32; i++) b.append(pool.charAt(r.nextInt(pool.length())));
        return b.toString();
    }

    /** Random keys incl. the packed forms: a whole 32-hex key, or a prefix + NUL + 32 hex (KeyCmp.pack). */
    static String randomKey(Random r) {
        int m = r.nextInt(10);
        if (m == 0) return hex32(r);
        if (m == 1) return rawKey(r) + "\u0000" + hex32(r);
        if (m == 2) return hex32(r).substring(0, 31) + rawKey(r);                 // almost hex
        return rawKey(r);
    }

    static String rawKey(Random r) {
        int n = r.nextInt(6);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) {
            switch (r.nextInt(9)) {
                case 0: b.append((char) ('a' + r.nextInt(3))); break;
                case 1: b.append((char) r.nextInt(0x80)); break;
                case 2: b.appendCodePoint(0x1F600 + r.nextInt(3)); break;            // emoji (surrogate pair)
                case 3: b.appendCodePoint(0x10000 + r.nextInt(3)); break;
                case 4: b.append((char) (0xE000 + r.nextInt(3))); break;               // private use
                case 5: b.append((char) (0xFF01 + r.nextInt(0xEF))); break;            // U+FF01–U+FFEF
                case 6: b.append((char) (0x80 + r.nextInt(0x700))); break;
                case 7: b.append((char) (0xD800 + r.nextInt(3))); break;               // unpaired high surrogate
                default: b.append((char) (0xDC00 + r.nextInt(3))); break;              // unpaired low surrogate
            }
        }
        return b.toString();
    }

    static void keyCmp() {
        T.section("KeyCmp");
        Random r = new Random(5);
        int bad = 0, roundTrip = 0, utf8 = 0, packedBad = 0, packedRt = 0, n = 200000;
        for (int i = 0; i < n; i++) {
            String a = randomKey(r), b = r.nextInt(4) == 0 ? a + randomKey(r) : randomKey(r);
            byte[] ea = KeyCmp.encode(a), eb = KeyCmp.encode(b);
            if (Integer.signum(KeyCmp.compare(ea, eb)) != Integer.signum(a.compareTo(b))) { bad++; if (bad < 4) System.out.println("  order " + esc(a) + " vs " + esc(b)); }
            if (!KeyCmp.decode(ea).equals(a)) roundTrip++;
            byte[] pa = KeyCmp.pack(a), pb = KeyCmp.pack(b);
            if (Integer.signum(KeyCmp.comparePacked(pa, 0, pa.length, pb, 0, pb.length)) != Integer.signum(a.compareTo(b))) { packedBad++; if (packedBad < 4) System.out.println("  packed order " + esc(a) + " vs " + esc(b)); }
            if (!KeyCmp.unpack(pa, 0, pa.length).equals(a)) packedRt++;
            boolean valid = true;
            for (int k = 0; k < a.length(); k++) {
                char c = a.charAt(k);
                if (Character.isHighSurrogate(c) && k + 1 < a.length() && Character.isLowSurrogate(a.charAt(k + 1))) k++;
                else if (Character.isSurrogate(c)) valid = false;
            }
            if (valid && !Arrays.equals(ea, a.getBytes(StandardCharsets.UTF_8))) utf8++;
        }
        T.eq("KeyCmp order equals String.compareTo (" + n + " random pairs)", 0, bad);
        T.eq("encode/decode round trip incl. unpaired surrogates", 0, roundTrip);
        T.eq("valid strings encode as UTF-8", 0, utf8);
        T.eq("packed index form: order equals String.compareTo", 0, packedBad);
        T.eq("packed index form round trip", 0, packedRt);
        T.eq("a 32-hex key packs to 17 bytes", 17, KeyCmp.pack("0123456789abcdef0123456789abcdef").length);
        // the case raw UTF-8 byte order gets wrong
        String emoji = "😀", pua = "", full = "！";
        T.check("emoji < U+E000 (UTF-16 order)", KeyCmp.compare(KeyCmp.encode(emoji), KeyCmp.encode(pua)) < 0);
        T.check("emoji < U+FF01 (UTF-16 order)", KeyCmp.compare(KeyCmp.encode("a" + emoji), KeyCmp.encode("a" + full)) < 0);
    }

    static String esc(String s) { StringBuilder b = new StringBuilder(); for (char c : s.toCharArray()) b.append(String.format("\\u%04x", (int) c)); return b.toString(); }

    static void keyIndex() {
        T.section("KeyIndex");
        int bad = 0, merges = 0;
        for (int seed = 0; seed < 60; seed++) {
            Random r = new Random(seed);
            KeyIndex x = new KeyIndex(seed % 3 == 0 ? 1 : 8);
            TreeMap<String, Integer> o = new TreeMap<String, Integer>();
            List<Iterable<String>> snaps = new ArrayList<Iterable<String>>();
            List<List<String>> expect = new ArrayList<List<String>>();
            for (int i = 0; i < 2000; i++) {
                String k = randomKey(r);
                int p = r.nextInt(10);
                if (p < 5) { int v = r.nextInt(1000); Integer a = o.put(k, v); if ((a == null ? -1 : a) != x.put(k, v)) bad++; }
                else if (p < 8) { Integer a = o.remove(k); if ((a == null ? -1 : a) != x.remove(k)) bad++; }
                else if (p < 9) { Integer a = o.get(k); if ((a == null ? -1 : a) != x.get(k)) bad++; }
                else {
                    String lo = randomKey(r), hi = randomKey(r);
                    if (lo.compareTo(hi) > 0) { String t = lo; lo = hi; hi = t; }
                    snaps.add(x.range(lo, hi));
                    expect.add(new ArrayList<String>(o.subMap(lo, hi).keySet()));
                }
                if (x.size() != o.size()) bad++;
            }
            for (int i = 0; i < snaps.size(); i++) { List<String> got = new ArrayList<String>(); for (String s : snaps.get(i)) got.add(s); if (!got.equals(expect.get(i))) bad++; }
            List<String> all = new ArrayList<String>(); for (String s : x.keys()) all.add(s);
            if (!all.equals(new ArrayList<String>(o.keySet()))) bad++;
            merges += x.merges();
        }
        T.eq("KeyIndex equals TreeMap (60 seeds × 2,000 ops, snapshots taken before later writes)", 0, bad);
        T.check("merges happened (" + merges + ")", merges > 100);
    }

    // ------------------------------------------------------------------ contract and persistence

    static DiskStore.Options small() {
        DiskStore.Options o = new DiskStore.Options();
        o.mergeMin = 8; o.maxSegmentBytes = 4096; o.indexTailBytes = 1 << 20; o.lruRecords = 4;
        return o;
    }

    static void contractAndPersistence() throws Exception {
        T.section("contract");
        TempTree t = TempTree.create();
        try {
            Path d1 = t.dir("contract");
            DiskStore s = DiskStore.create(new DirBlobStore(d1), 0, CLOCK, small());
            int f = StoreContractTest.run(s);
            T.eq("StoreContractTest on DiskStore", 0, f);
            T.section("persistence");
            s.flush();
            String before = StoreOracleTest.dump(s);
            s.close();
            DiskStore re = DiskStore.open(new DirBlobStore(d1), 0, CLOCK, small());
            T.eq("contract store reopens equal", before, StoreOracleTest.dump(re));
            T.check("reopened from the index file", re.loadedFromIndex());
            re.close();
            Path d2 = t.dir("fill");
            DiskStore p = DiskStore.create(new DirBlobStore(d2), 0, CLOCK, small());
            StorePersistenceTest.fill(p, "A");
            StorePersistenceTest.fill(p, "bé😀");
            PairCheck pc = new PairCheck(); pc.pairKey = "fA|gA"; pc.a = "fA"; pc.b = "gA"; pc.verdict = PairVerdict.values()[0]; p.pairChecks().put(pc);
            p.flush();
            String all = StoreOracleTest.dump(p);
            MemoryStore m = MemoryStore.volatileStore();
            StorePersistenceTest.fill(m, "A"); StorePersistenceTest.fill(m, "bé😀"); m.pairChecks().put(pc); m.commit();
            T.eq("DiskStore equals MemoryStore after fill (17 tables)", StoreOracleTest.dump(m), all);
            T.eq("StorePersistenceTest.dumpAll equal", StorePersistenceTest.dumpAll(m), StorePersistenceTest.dumpAll(p));
            // reopen without close: no index file, rebuild by scanning
            DiskStore q = DiskStore.open(new DirBlobStore(d2), 0, CLOCK, small());
            T.check("no index written yet: rebuilt by scan", !q.loadedFromIndex());
            T.eq("reopen (scan) equal", all, StoreOracleTest.dump(q));
            FileRecord fa = q.files().get("fA");
            T.check("NaN and unknown sentinels round-trip", Double.isNaN(fa.utility) && fa.sizeBytes == -1 && fa.modifiedAt == Unknown.TIME && fa.readable == Tri.UNKNOWN);
            FileRecord g = q.files().get("gA");
            T.check("known values round-trip", g.sizeBytes == 0 && g.modifiedAt == -1 && g.phash == 0xfedcba9876543210L && g.writable == Tri.FALSE);
            q.close();
            DiskStore q2 = DiskStore.open(new DirBlobStore(d2), 0, CLOCK, small());
            T.check("after close: index file used", q2.loadedFromIndex());
            T.eq("reopen (index) equal", all, StoreOracleTest.dump(q2));
            StoreStats st = q2.stats();
            T.check("stats: records and bytes (" + st + ")", st.records > 30 && st.liveBytes > 0 && st.diskBytes >= st.liveBytes && st.indexBytesEstimate > 0);
            q2.close();
            // checkpoint interval
            Path d3 = t.dir("interval");
            FixedClock fc = new FixedClock();
            DirBlobStore b3 = new DirBlobStore(d3);
            DiskStore.Options big = small(); big.maxSegmentBytes = 1L << 20;   // no rotation (a rotation checkpoints)
            DiskStore iv = DiskStore.create(b3, 60000, fc, big);
            int c0 = iv.checkpointsWritten();
            StorePersistenceTest.fill(iv, "A"); iv.commit();
            StorePersistenceTest.fill(iv, "BB"); iv.commit();
            T.eq("commits within the interval write no checkpoint", c0, iv.checkpointsWritten());
            fc.advance(60001);
            StorePersistenceTest.fill(iv, "CCC"); iv.commit();
            T.eq("first commit after the interval checkpoints", c0 + 1, iv.checkpointsWritten());
            iv.flush();
            T.eq("flush with nothing dirty writes nothing", c0 + 1, iv.checkpointsWritten());
            iv.close();
            // an unknown higher version is refused; a missing manifest is a fresh store
            PowerLossBlobStore pb = new PowerLossBlobStore();
            DiskStore e = DiskStore.open(pb, 0, CLOCK, small());
            T.eq("absent manifest: empty store", 0, e.files().size());
            e.files().put(StoreContractTest.file("a", "a", 1, Presence.PRESENT, 1)); e.close();
            String man = new String(pb.bytes(DiskStore.MANIFEST), StandardCharsets.UTF_8);
            T.check("manifest has hw, index and crc32", man.contains("\"hw\":{") && man.contains("\"index\":{") && man.contains("\"crc32\":"));
            T.check("future version refused", !opens(withManifest(pb, man.replace("\"version\":1", "\"version\":2"))));
        } finally { t.close(); }
    }

    /** A copy of pb with ds.manifest replaced by text (CRC recomputed, so only the content is under test). */
    static PowerLossBlobStore withManifest(PowerLossBlobStore pb, String text) {
        PowerLossBlobStore c = copy(pb);
        String body = text.substring(0, text.length() - 20) + "}";
        java.util.zip.CRC32 crc = new java.util.zip.CRC32(); crc.update(body.getBytes(StandardCharsets.UTF_8));
        String full = body.substring(0, body.length() - 1) + ",\"crc32\":\"" + com.storageplanner.v2.util.Hex.of8(crc.getValue()) + "\"}";
        c.setBytes(DiskStore.MANIFEST, full.getBytes(StandardCharsets.UTF_8));
        return c;
    }

    static PowerLossBlobStore copy(PowerLossBlobStore pb) {
        PowerLossBlobStore c = new PowerLossBlobStore();
        for (String n : pb.names()) c.setBytes(n, pb.bytes(n));
        return c;
    }

    static boolean opens(PowerLossBlobStore b) {
        try { DiskStore.open(b, 0, CLOCK, small()); return true; } catch (StoreException e) { return false; }
    }

    // ------------------------------------------------------------------ kill points

    /** A deterministic workload over all tables; records the oracle dump at every commitSeq. */
    static final class Workload {
        final Map<Long, String> dumpAt = new HashMap<Long, String>();
        long lastCompletedCkSeq;     // commitSeq of the last checkpoint that completed
        long inProgressSeq = -1;     // commitSeq of the commit/flush/close running when the kill hit
        void run(PowerLossBlobStore inner, com.storageplanner.v2.device.BlobStore b, long seed, DiskStore.Options o) throws Exception {
            Random r = new Random(seed);
            MemoryStore m = MemoryStore.volatileStore();
            dumpAt.put(0L, StoreOracleTest.dump(m));
            DiskStore d = DiskStore.create(b, 0, CLOCK, o);
            int ck = d.checkpointsWritten();
            for (int i = 0; i < 160; i++) {
                int id = r.nextInt(4) == 0 ? TableIds.FILES : 1 + r.nextInt(TableIds.COUNT);
                Record rec = StoreOracleTest.make(id, r);
                boolean rm = r.nextInt(5) == 0;
                StoreOracleTest.write(m, id, rec, rm);
                StoreOracleTest.write(d, id, rec, rm);
                int p = r.nextInt(20);
                if (p < 4 || p == 19) {
                    m.commit();
                    long seq = d.commitSeq() + 1;
                    dumpAt.put(seq, StoreOracleTest.dump(m));
                    inProgressSeq = seq;
                    if (p == 19) d.close(); else if (p == 3) d.flush(); else d.commit();
                    if (d.checkpointsWritten() != ck) { ck = d.checkpointsWritten(); lastCompletedCkSeq = seq; }
                    inProgressSeq = -1;
                    if (p == 19) { d = DiskStore.open(b, 0, CLOCK, o); ck = d.checkpointsWritten(); }
                } else if (p == 4) {
                    m.rollback(); d.rollback();
                }
            }
            m.commit();
            long seq = d.commitSeq() + 1;
            dumpAt.put(seq, StoreOracleTest.dump(m));
            inProgressSeq = seq;
            d.close();
            lastCompletedCkSeq = seq;
            inProgressSeq = -1;
        }
    }

    static void kills() throws Exception {
        T.section("kills");
        int trials = Integer.getInteger("v2.killTrials", 300);
        DiskStore.Options o = small();
        o.maxSegmentBytes = 1500; o.indexTailBytes = 3000;
        // a clean run to learn the ranges of the kill points
        PowerLossBlobStore cleanB = new PowerLossBlobStore();
        KillingBlobStore cleanK = new KillingBlobStore(cleanB);
        new Workload().run(cleanB, cleanK, 1, o);
        int replaces = cleanK.replaceCommits();
        long appended = cleanK.appendedBytes();
        T.check("workload writes many manifests/index files (" + replaces + ") and bytes (" + appended + ")", replaces > 20 && appended > 5000);
        Random r = new Random(77);
        int bad = 0, killed = 0, notKilled = 0, inProgressHits = 0, powerLoss = 0;
        int[] byType = new int[4];
        for (int trial = 0; trial < trials; trial++) {
            PowerLossBlobStore inner = new PowerLossBlobStore();
            KillingBlobStore kb = new KillingBlobStore(inner);
            int type = trial % 4;
            if (type == 0) kb.killAfterReplaceCommits(1 + r.nextInt(replaces));
            else if (type == 1) kb.killBeforeReplaceCommit(1 + r.nextInt(replaces));
            else if (type == 2) kb.killAfterAppendedBytes(r.nextInt((int) appended));
            else kb.killBeforeTruncate(1 + r.nextInt(8));
            Workload w = new Workload();
            try { w.run(inner, kb, 1, o); notKilled++; continue; }
            catch (SimulatedKill k) { killed++; byType[type]++; }
            if (r.nextInt(3) == 0) { inner.powerLoss(r, r.nextBoolean(), r.nextBoolean()); powerLoss++; }
            DiskStore re;
            try { re = DiskStore.open(inner, 0, CLOCK, o); }
            catch (StoreException e) { bad++; System.out.println("  trial " + trial + ": reopen failed: " + e.getMessage()); continue; }
            long seq = re.commitSeq();
            boolean okSeq = seq == w.lastCompletedCkSeq || seq == w.inProgressSeq;
            String exp = w.dumpAt.get(seq);
            if (!okSeq || exp == null || !exp.equals(StoreOracleTest.dump(re))) {
                bad++;
                System.out.println("  trial " + trial + " type " + type + ": reopened at commitSeq " + seq + ", last checkpoint " + w.lastCompletedCkSeq
                        + ", in progress " + w.inProgressSeq + (exp == null ? " (no such state)" : " (dump " + (exp.equals(StoreOracleTest.dump(re)) ? "equal" : "differs") + ")"));
                continue;
            }
            if (seq == w.inProgressSeq) inProgressHits++;
            // the store keeps working after recovery
            re.files().put(StoreContractTest.file("after", "after/kill", 1, Presence.PRESENT, 1));
            re.close();
            DiskStore again = DiskStore.open(inner, 0, CLOCK, o);
            if (again.files().get("after") == null) bad++;
            again.close();
        }
        System.out.println("  kills: " + killed + " killed (after-replace " + byType[0] + ", before-replace " + byType[1] + ", torn append " + byType[2]
                + ", before-truncate " + byType[3] + "), " + notKilled + " ran to the end, " + powerLoss + " with power loss, "
                + inProgressHits + " landed on the checkpoint being written");
        T.eq("kill trials recovering anywhere but the last checkpoint (of " + trials + ")", 0, bad);
        T.check("most trials killed", killed > trials * 2 / 3);
    }

    // ------------------------------------------------------------------ F-01 index bound to the manifest

    static void indexMismatch() throws Exception {
        T.section("F-01");
        // kill between the index replace and the manifest replace: the newer index must be ignored
        PowerLossBlobStore inner = new PowerLossBlobStore();
        DiskStore.Options o = small();
        o.indexTailBytes = 0;     // every checkpoint with a tail writes the index, then the manifest
        DiskStore s = DiskStore.create(inner, 0, CLOCK, o);
        s.files().put(StoreContractTest.file("a", "x/a", 1, Presence.PRESENT, 1));
        s.flush();
        String state1 = StoreOracleTest.dump(s);
        int rc = inner.replaces();
        byte[] idxBefore = inner.bytes("ds-a.idx");
        KillingBlobStore kb = new KillingBlobStore(inner).killBeforeReplaceCommit(3);   // 1 = hw raise, 2 = index, 3 = manifest
        DiskStore k = DiskStore.open(kb, 0, CLOCK, o);
        T.check("first open used the index", k.loadedFromIndex());
        k.files().put(StoreContractTest.file("b", "x/b", 2, Presence.PRESENT, 1));
        boolean killed = false;
        try { k.flush(); } catch (SimulatedKill e) { killed = true; }
        T.check("killed between index and manifest", killed && inner.replaces() == rc + 2 && !Arrays.equals(idxBefore, inner.bytes("ds-a.idx")));
        DiskStore re = DiskStore.open(inner, 0, CLOCK, o);
        T.check("newer index rejected (idxSeq/crc differ from the manifest's)", !re.loadedFromIndex());
        T.eq("reopens exactly at the previous checkpoint", state1, StoreOracleTest.dump(re));
        T.check("record of the lost checkpoint absent", re.files().get("b") == null);
        re.close();
        // a damaged or truncated index file is rebuilt with identical content
        PowerLossBlobStore b2 = new PowerLossBlobStore();
        DiskStore s2 = DiskStore.create(b2, 0, CLOCK, small());
        StorePersistenceTest.fill(s2, "A"); StorePersistenceTest.fill(s2, "😀");
        s2.close();
        String full = StoreOracleTest.dump(DiskStore.open(copy(b2), 0, CLOCK, small()));
        byte[] idx = b2.bytes("ds-a.idx");
        T.check("index file exists", idx != null && idx.length > 100);
        int okFlip = 0, okCut = 0, n = 0;
        for (int off = 0; off < idx.length; off += Math.max(1, idx.length / 40)) {
            n++;
            PowerLossBlobStore c = copy(b2);
            byte[] x = idx.clone(); x[off] ^= 0x20; c.setBytes("ds-a.idx", x);
            DiskStore d = DiskStore.open(c, 0, CLOCK, small());
            if (!d.loadedFromIndex() && full.equals(StoreOracleTest.dump(d))) okFlip++;
            PowerLossBlobStore c2 = copy(b2);
            c2.setBytes("ds-a.idx", Arrays.copyOf(idx, off));
            DiskStore d2 = DiskStore.open(c2, 0, CLOCK, small());
            if (!d2.loadedFromIndex() && full.equals(StoreOracleTest.dump(d2))) okCut++;
        }
        T.eq("flipped index byte → rebuilt, identical", n, okFlip);
        T.eq("truncated index → rebuilt, identical", n, okCut);
        // an index whose covers lie beyond durable is never used
        PowerLossBlobStore c3 = copy(b2);
        String man = new String(c3.bytes(DiskStore.MANIFEST), StandardCharsets.UTF_8);
        int i = man.indexOf("\"durable\":");
        int j = man.indexOf('}', i);
        long dur = Long.parseLong(man.substring(i + 10, j));
        // shrinking durable makes the last frames "not exist"; the index (covering them) must not be used, and the open
        // must not read past the new durable (it ends inside a frame → damage inside the durable range → refused)
        PowerLossBlobStore c4 = withManifest(c3, man.substring(0, i + 10) + (dur - 1) + man.substring(j));
        T.check("covers > durable: index refused and the cut frame fails the open (F-01, F-05)", !opens(c4));
    }

    // ------------------------------------------------------------------ F-02 stale segments

    static void staleSegments() throws Exception {
        T.section("F-02");
        PowerLossBlobStore b = new PowerLossBlobStore();
        DiskStore.Options o = small();
        o.indexAtClose = false;
        DiskStore s = DiskStore.create(b, -1, CLOCK, o);          // adaptive: commits do not checkpoint (5 s)
        s.files().put(StoreContractTest.file("a", "x/a", 1, Presence.PRESENT, 1));
        s.flush();                                               // durable: segment 1 with "a"
        String state1 = StoreOracleTest.dump(s);
        s.close();
        DiskStore k = DiskStore.open(b, -1, CLOCK, o);           // next appends go to segment 2 (hw raised first)
        k.files().put(StoreContractTest.file("phantom", "x/phantom", 9, Presence.PRESENT, 1));
        k.commit();                                              // committed, but no checkpoint
        T.check("phantom readable before the crash", k.files().get("phantom") != null);   // forces the buffer to the file
        byte[] seg2 = b.bytes("ds-a-0002.seg");
        T.check("segment 2 holds a committed phantom", seg2 != null && new String(seg2, StandardCharsets.ISO_8859_1).contains("phantom"));
        // crash (process kill): segment 2 keeps its bytes, the manifest does not list it
        DiskStore re = DiskStore.open(b, -1, CLOCK, o);
        T.eq("phantom commit never replayed", state1, StoreOracleTest.dump(re));
        T.check("rebuilt by scan (no index)", !re.loadedFromIndex());
        // reuse of segment 2: truncated before the first append, so its HEADER is at offset 0
        re.files().put(StoreContractTest.file("c", "x/c", 3, Presence.PRESENT, 1));
        re.flush();
        byte[] after = b.bytes("ds-a-0002.seg");
        T.check("reused segment no longer holds the phantom", !new String(after, StandardCharsets.ISO_8859_1).contains("phantom"));
        Frame h = Frame.parse(after, 0, after.length);
        T.check("HEADER at offset 0 of the reused segment", h.type == Frame.HEADER && new String(h.val, StandardCharsets.UTF_8).contains("\"seq\":2"));
        re.close();
        DiskStore re2 = DiskStore.open(b, -1, CLOCK, o);
        T.check("reopen: c present, phantom absent", re2.files().get("c") != null && re2.files().get("phantom") == null);
        String st = StoreOracleTest.dump(re2);
        re2.close();
        // a stale segment beyond the list, pre-filled with valid committed frames with the next txn/commitSeq numbers and a
        // HEADER that matches gen/set/seq: never replayed (also after a rebuild)
        String man = new String(b.bytes(DiskStore.MANIFEST), StandardCharsets.UTF_8);
        long gen = Long.parseLong(field(man, "gen")), txnNext = Long.parseLong(field(man, "txnNext")), cs = Long.parseLong(field(man, "commitSeq"));
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        bo.write(Frame.header("{\"version\":1,\"schema\":1,\"gen\":" + gen + ",\"set\":\"a\",\"seq\":3,\"epoch\":" + cs + "}"));
        FileRecord ph = StoreContractTest.file("phantom2", "x/phantom2", 5, Presence.PRESENT, 1);
        bo.write(Frame.put(1, txnNext, "phantom2", Codecs.json(Codecs.FILE, ph).getBytes(StandardCharsets.UTF_8)));
        bo.write(Frame.commit(txnNext, cs + 1));
        b.setBytes("ds-a-0003.seg", bo.toByteArray());
        DiskStore re3 = DiskStore.open(b, -1, CLOCK, o);
        T.eq("pre-filled stale segment never replayed", st, StoreOracleTest.dump(re3));
        re3.files().put(StoreContractTest.file("d", "x/d", 4, Presence.PRESENT, 1));
        re3.flush();
        DiskStore re4 = DiskStore.open(b, -1, CLOCK, o);
        T.check("after reuse: d present, phantom2 absent", re4.files().get("d") != null && re4.files().get("phantom2") == null);
        // a listed segment whose HEADER names another generation: refused
        byte[] s1 = b.bytes("ds-a-0001.seg");
        PowerLossBlobStore c = copy(b);
        byte[] fake = Frame.header("{\"version\":1,\"schema\":1,\"gen\":" + (gen + 7) + ",\"set\":\"a\",\"seq\":1,\"epoch\":0}");
        Frame real = Frame.parse(s1, 0, s1.length);
        if (fake.length == real.length) {
            byte[] x = s1.clone(); System.arraycopy(fake, 0, x, 0, fake.length); c.setBytes("ds-a-0001.seg", x);
            T.check("segment HEADER of another generation refused", !opens(c));
        } else T.check("header lengths comparable", false);
    }

    static String field(String json, String name) {
        int i = json.indexOf("\"" + name + "\":") + name.length() + 3;
        int j = i; while (j < json.length() && (Character.isDigit(json.charAt(j)) || json.charAt(j) == '-')) j++;
        return json.substring(i, j);
    }

    // ------------------------------------------------------------------ F-04 wrong record

    static long fnv(byte[] b) { long h = 0xcbf29ce484222325L; for (byte x : b) { h ^= (x & 0xff); h *= 0x100000001b3L; } return (h ^ (h >>> 32)) & 0xffffffffL; }

    @SuppressWarnings("unchecked")
    static void wrongRecord() throws Exception {
        T.section("F-04");
        int caught = 0, cases = 0;
        for (int id = 1; id <= TableIds.COUNT; id++) {
            PowerLossBlobStore b = new PowerLossBlobStore();
            DiskStore s = DiskStore.create(b, 0, CLOCK, small());
            Random r = new Random(id);
            List<String> keys = new ArrayList<String>();
            for (int i = 0; i < 40 && keys.size() < 2; i++) {
                Record rec = StoreOracleTest.make(id, r);
                if (StoreOracleTest.table(s, id).get(rec.key()) == null) { StoreOracleTest.write(s, id, rec, false); keys.add(rec.key()); }
            }
            s.flush();
            if (keys.size() < 2) { T.check("two keys in " + TableIds.name(id), false); continue; }
            long[] lb = s.debugLocator(id, keys.get(1));
            // (a) locator of key 0 pointing at key 1's frame, with key 1's hash/length: key check
            s.debugSetLocator(id, keys.get(0), lb[0], lb[1], (int) lb[2]);
            cases++;
            try { Object x = StoreOracleTest.table(s, id).get(keys.get(0)); System.out.println("  " + TableIds.name(id) + ": wrong record returned " + x); }
            catch (StoreCorruption e) { caught++; }
            T.check(TableIds.name(id) + ": wrong-record read marks the store damaged", !opens(b));
        }
        T.eq("locator at another key's frame → StoreCorruption, derived tables included", cases, caught);
        // (b) files key X pointing at the features frame of X (same key bytes, other table)
        PowerLossBlobStore b = new PowerLossBlobStore();
        DiskStore s = DiskStore.create(b, 0, CLOCK, small());
        s.files().put(StoreContractTest.file("same", "p/same", 1, Presence.PRESENT, 1));
        FeatureRecord fe = new FeatureRecord(); fe.fileId = "same"; s.features().put(fe);
        s.flush();
        long[] lf = s.debugLocator(TableIds.FEATURES, "same");
        s.debugSetLocator(TableIds.FILES, "same", lf[0], lf[1], (int) lf[2]);
        boolean t1 = false;
        try { s.files().get("same"); } catch (StoreCorruption e) { t1 = true; }
        T.check("files locator at a features frame of the same key → StoreCorruption", t1);
        PowerLossBlobStore b2 = new PowerLossBlobStore();
        DiskStore s2 = DiskStore.create(b2, 0, CLOCK, small());
        s2.files().put(StoreContractTest.file("same", "p/same", 1, Presence.PRESENT, 1));
        FeatureRecord fe2 = new FeatureRecord(); fe2.fileId = "same"; s2.features().put(fe2);
        s2.flush();
        long[] lfile = s2.debugLocator(TableIds.FILES, "same");
        s2.debugSetLocator(TableIds.FEATURES, "same", lfile[0], lfile[1], (int) lfile[2]);
        boolean t2 = false;
        try { s2.features().get("same"); } catch (StoreCorruption e) { t2 = true; }
        T.check("features locator at a files frame → StoreCorruption (never null)", t2);
        // (c) a CRC-valid frame under the right table and key whose JSON is another record: decoded-key check
        PowerLossBlobStore b3 = new PowerLossBlobStore();
        DiskStore.Options o = small(); o.indexAtClose = true;
        DiskStore s3 = DiskStore.create(b3, 0, CLOCK, o);
        s3.files().put(StoreContractTest.file("k1", "p/k", 1, Presence.PRESENT, 1));
        s3.files().put(StoreContractTest.file("k2", "p/k", 1, Presence.PRESENT, 1));
        s3.close();
        DiskStore s4 = DiskStore.open(b3, 0, CLOCK, o);
        long[] l1 = s4.debugLocator(TableIds.FILES, "k1");
        long txn = 0;
        String seg = "ds-a-0001.seg";
        byte[] segBytes = b3.bytes(seg);
        int pos = (int) (l1[0] & 0xFFFFFFFFFFFFL);
        Frame orig = Frame.parse(segBytes, pos, segBytes.length - pos);
        txn = orig.txn;
        byte[] val = Codecs.json(Codecs.FILE, StoreContractTest.file("k2", "p/k", 1, Presence.PRESENT, 1)).getBytes(StandardCharsets.UTF_8);
        byte[] forged = Frame.put(1, txn, "k1", val);
        boolean t3 = false;
        if (forged.length == orig.length) {
            System.arraycopy(forged, 0, segBytes, pos, forged.length);
            b3.setBytes(seg, segBytes);
            s4.debugSetLocator(TableIds.FILES, "k1", l1[0], fnv(val), forged.length);
            try { s4.files().get("k1"); } catch (StoreCorruption e) { t3 = e.getMessage().contains("has key"); }
        }
        T.check("frame whose record key differs from the key asked → StoreCorruption", t3);
        // (d) a CRC-valid frame with the right table, value and hash but another key in the frame: only the key-bytes check
        PowerLossBlobStore b5 = new PowerLossBlobStore();
        DiskStore s5 = DiskStore.create(b5, 0, CLOCK, o);
        s5.files().put(StoreContractTest.file("k1", "p/k", 1, Presence.PRESENT, 1));
        s5.files().put(StoreContractTest.file("k2", "p/k", 1, Presence.PRESENT, 1));
        s5.close();
        DiskStore s6 = DiskStore.open(b5, 0, CLOCK, o);
        long[] m1 = s6.debugLocator(TableIds.FILES, "k1");
        byte[] sb5 = b5.bytes(seg);
        int p5 = (int) (m1[0] & 0xFFFFFFFFFFFFL);
        Frame o5 = Frame.parse(sb5, p5, sb5.length - p5);
        byte[] forged2 = Frame.put(1, o5.txn, "k2", o5.val);
        boolean t4 = false;
        if (forged2.length == o5.length) {
            System.arraycopy(forged2, 0, sb5, p5, forged2.length);
            b5.setBytes(seg, sb5);
            try { s6.files().get("k1"); } catch (StoreCorruption e) { t4 = e.getMessage().contains("reached another frame"); }
        }
        T.check("frame under another key (value, hash, length and record key all matching) → StoreCorruption", t4);
    }

    // ------------------------------------------------------------------ F-05 and the corruption rule (3.11)

    static void damage() throws Exception {
        T.section("F-05 / 3.11");
        PowerLossBlobStore base = new PowerLossBlobStore();
        DiskStore.Options o = small();
        o.maxSegmentBytes = 1L << 20;
        DiskStore s = DiskStore.create(base, 0, CLOCK, o);
        Random r = new Random(9);
        Map<Integer, String> keyOf = new TreeMap<Integer, String>();
        for (int id = 1; id <= TableIds.COUNT; id++) {
            for (int i = 0; i < 30 && !keyOf.containsKey(id); i++) {
                Record rec = StoreOracleTest.make(id, r);
                if (StoreOracleTest.table(s, id).get(rec.key()) == null) { StoreOracleTest.write(s, id, rec, false); keyOf.put(id, rec.key()); }
            }
        }
        StorePersistenceTest.fill(s, "Z");
        s.close();                                     // index file written: open does not scan, damage is met on read
        String full = StoreOracleTest.dump(DiskStore.open(copy(base), 0, CLOCK, o));
        DiskStore probe = DiskStore.open(copy(base), 0, CLOCK, o);
        int ruleOk = 0, ruleN = 0, scanRefused = 0, cutOk = 0;
        for (Map.Entry<Integer, String> e : keyOf.entrySet()) {
            int id = e.getKey();
            String key = e.getValue();
            long[] l = probe.debugLocator(id, key);
            int seq = (int) (l[0] >>> 48), pos = (int) (l[0] & 0xFFFFFFFFFFFFL), len = (int) l[2];
            String seg = String.format(Locale.ROOT, "ds-a-%04d.seg", seq);
            for (int mode = 0; mode < 2; mode++) {         // 0 = byte flip inside the frame, 1 = segment truncated inside it
                ruleN++;
                PowerLossBlobStore c = copy(base);
                DiskStore d = DiskStore.open(c, 0, CLOCK, o);
                byte[] sb = c.bytes(seg);
                if (mode == 0) { sb[pos + len / 2] ^= 0x04; c.setBytes(seg, sb); }
                else c.setBytes(seg, Arrays.copyOf(sb, pos + len - 3));
                // without the index (forced rebuild by scan) any damage below durable fails the open (F-05)
                PowerLossBlobStore c2 = copy(c);
                c2.setBytes("ds-a.idx", new byte[0]);
                if (!opens(c2)) scanRefused++;
                boolean derived = TableIds.derived(id);
                Object got = "unset";
                boolean threw = false;
                try { got = StoreOracleTest.table(d, id).get(key); } catch (StoreCorruption x) { threw = true; }
                boolean ok;
                if (derived) {
                    ok = !threw && got == null && d.damagedKeys().size() == 1 && d.stats().damaged == 1;
                    if (mode == 0) {                         // the rest of the store still reads
                        try { StoreOracleTest.dump(d); } catch (StoreCorruption x) { ok = false; }
                    }
                    ok = ok && opens(c) == (mode == 0);      // a flip is found on read only; a short segment fails the next open (F-05)
                } else {
                    if (threw) {                             // Pipeline.fail: rollback, write the FAILED session, flush
                        d.rollback();
                        ScanSession ss = new ScanSession(); ss.scanId = 99; ss.kind = ScanKind.FULL; ss.state = ScanState.FAILED;
                        d.scanSessions().put(ss);
                        try { d.flush(); } catch (StoreException x) { threw = false; }
                    }
                    ok = threw && !opens(c);                 // fail closed: manifest stays damaged after that flush, next open refuses
                    if (ok) {
                        try { DiskStore f = DiskStore.create(c, 0, CLOCK, o); ok = f.files().size() == 0; f.files().put(StoreContractTest.file("n", "n", 1, Presence.PRESENT, 1)); f.close();
                              ok = ok && DiskStore.open(c, 0, CLOCK, o).files().get("n") != null; }
                        catch (StoreException x) { ok = false; }
                    }
                }
                if (ok) { ruleOk++; if (mode == 1) cutOk++; }
                else System.out.println("  rule violated: " + TableIds.name(id) + (mode == 0 ? " flip" : " cut") + " threw=" + threw + " got=" + got);
            }
        }
        T.eq("corruption rule per table: derived → null + counted; others → StoreCorruption, damaged, reset works", ruleN, ruleOk);
        T.eq("damage below durable with a rebuild by scan → open refused (F-05)", ruleN, scanRefused);
        // garbage after durable is ignored
        PowerLossBlobStore g = copy(base);
        for (String n : g.names()) if (n.endsWith(".seg")) { byte[] x = g.bytes(n); byte[] y = Arrays.copyOf(x, x.length + 777); new Random(1).nextBytes(Arrays.copyOfRange(y, 0, 0)); for (int i = x.length; i < y.length; i++) y[i] = (byte) (i * 31); g.setBytes(n, y); }
        DiskStore gd = DiskStore.open(g, 0, CLOCK, o);
        T.eq("garbage after durable ignored", full, StoreOracleTest.dump(gd));
        PowerLossBlobStore g2 = copy(g); g2.setBytes("ds-a.idx", new byte[0]);
        T.eq("garbage after durable ignored (scan)", full, StoreOracleTest.dump(DiskStore.open(g2, 0, CLOCK, o)));
        // a damaged manifest refuses to open
        PowerLossBlobStore dm = copy(base);
        byte[] mb = dm.bytes(DiskStore.MANIFEST); mb[mb.length / 2] ^= 0x01; dm.setBytes(DiskStore.MANIFEST, mb);
        T.check("damaged manifest refused", !opens(dm));
    }

    // ------------------------------------------------------------------ S4: compaction and space

    static String writeSome(MemoryStore m, DiskStore s, Random r, int n) throws StoreException {
        for (int i = 0; i < n; i++) {
            int id = r.nextInt(4) == 0 ? TableIds.FILES : 1 + r.nextInt(TableIds.COUNT);
            Record rec = StoreOracleTest.make(id, r);
            boolean rm = r.nextInt(5) == 0;
            StoreOracleTest.write(m, id, rec, rm);
            StoreOracleTest.write(s, id, rec, rm);
        }
        m.commit(); s.flush();
        return StoreOracleTest.dump(m);
    }

    static boolean allEmpty(PowerLossBlobStore b, String prefix) {
        for (String n : b.names()) if (n.startsWith(prefix) && b.bytes(n).length > 0) return false;
        return true;
    }

    static void compaction() throws Exception {
        T.section("S4 compaction");
        DiskStore.Options o = small();
        o.compactMinGarbage = 1L << 40;                 // only compactNow() in this section
        PowerLossBlobStore base = new PowerLossBlobStore();
        DiskStore s = DiskStore.create(base, 0, CLOCK, o);
        MemoryStore m = MemoryStore.volatileStore();
        Random r = new Random(17);
        for (int round = 0; round < 6; round++) writeSome(m, s, r, 150);
        StorePersistenceTest.fill(s, "C"); StorePersistenceTest.fill(m, "C"); m.commit(); s.flush();
        String state = StoreOracleTest.dump(m);
        T.eq("store equals the oracle before compaction", state, StoreOracleTest.dump(s));
        StoreStats before = s.stats();
        s.close();
        PowerLossBlobStore pc = copy(base);
        KillingBlobStore probe = new KillingBlobStore(pc);
        DiskStore p = DiskStore.open(probe, 0, CLOCK, o);
        int r0 = probe.replaceCommits(), t0 = probe.truncates();
        T.check("compaction ran", p.compactNow() && p.compactions() == 1);
        int reps = probe.replaceCommits() - r0, truncs = probe.truncates() - t0;
        StoreStats after = p.stats();
        T.check("garbage gone (" + before.garbageBytes + " → " + after.garbageBytes + " B, live " + before.liveBytes + " → " + after.liveBytes + ")",
                before.garbageBytes > 3 * before.liveBytes && after.garbageBytes < after.liveBytes / 10 && after.liveBytes <= before.liveBytes);
        T.eq("compacted store equals the oracle", state, StoreOracleTest.dump(p));
        T.check("old set truncated (segments and index)", allEmpty(pc, "ds-a"));
        DiskStore ri = DiskStore.open(copy(pc), 0, CLOCK, o);
        T.check("reopen from the new index", ri.loadedFromIndex());
        T.eq("reopen from the new index equals", state, StoreOracleTest.dump(ri));
        PowerLossBlobStore noIdx = copy(pc);
        for (String n : noIdx.names()) if (n.endsWith(".idx")) noIdx.setBytes(n, new byte[0]);
        DiskStore rs = DiskStore.open(noIdx, 0, CLOCK, o);
        T.check("index removed: rebuilt by scan of the base segments", !rs.loadedFromIndex());
        T.eq("rebuild by scan of compacted segments equals", state, StoreOracleTest.dump(rs));
        // writes after a compaction, a second compaction (back to set a), reopen
        String state2 = writeSome(m, p, r, 200);
        T.eq("writes after compaction", state2, StoreOracleTest.dump(p));
        T.check("second compaction", p.compactNow() && allEmpty(pc, "ds-b"));
        T.eq("after the second compaction", state2, StoreOracleTest.dump(p));
        p.close();
        T.eq("reopen after the second compaction", state2, StoreOracleTest.dump(DiskStore.open(copy(pc), 0, CLOCK, o)));
        PowerLossBlobStore noIdx2 = copy(pc);
        for (String n : noIdx2.names()) if (n.endsWith(".idx")) noIdx2.setBytes(n, new byte[0]);
        T.eq("…and by scan", state2, StoreOracleTest.dump(DiskStore.open(noIdx2, 0, CLOCK, o)));

        // kill at every replace and truncate inside a compaction (and with power loss): reopen = the state, then usable
        T.check("compaction has replace (" + reps + ") and truncate (" + truncs + ") commits", reps >= 2 && truncs >= 3);
        int trials = 0, ok = 0, killedN = 0;
        Random rr = new Random(5);
        for (int kind = 0; kind < 3; kind++) {
            int max = kind == 2 ? truncs : reps;
            for (int k = 1; k <= max; k++) for (int pl = 0; pl < 2; pl++) {
                trials++;
                PowerLossBlobStore c = copy(base);
                KillingBlobStore kb = new KillingBlobStore(c);
                if (kind == 0) kb.killAfterReplaceCommits(r0 + k); else if (kind == 1) kb.killBeforeReplaceCommit(r0 + k); else kb.killBeforeTruncate(t0 + k);
                DiskStore d = DiskStore.open(kb, 0, CLOCK, o);
                boolean killed = false;
                try { d.compactNow(); } catch (SimulatedKill e) { killed = true; }
                if (killed) killedN++;
                if (pl == 1) c.powerLoss(rr, rr.nextBoolean(), rr.nextBoolean());
                boolean good;
                try {
                    DiskStore re = DiskStore.open(c, 0, CLOCK, o);
                    good = state.equals(StoreOracleTest.dump(re));
                    re.files().put(StoreContractTest.file("after-kill", "x/after", 3, Presence.PRESENT, 1));
                    re.compactNow();
                    re.close();
                    DiskStore again = DiskStore.open(c, 0, CLOCK, o);
                    good = good && again.files().get("after-kill") != null && again.files().size() == re.files().size();
                } catch (StoreException e) { good = false; System.out.println("  compaction kill " + kind + "/" + k + ": " + e.getMessage()); }
                if (good) ok++;
                else System.out.println("  compaction kill kind " + kind + " k " + k + " power " + pl + ": state differs");
            }
        }
        T.eq("compaction kills: reopened store equals the state and stays usable", trials, ok);
        T.eq("every compaction trial was killed", trials, killedN);

        // a damaged derived record is dropped by the compaction (it reads as absent anyway); a damaged files record fails closed
        PowerLossBlobStore dd = copy(pc);
        DiskStore d0 = DiskStore.open(dd, 0, CLOCK, o);
        String fk = null, xk = null;
        for (String k : d0.features().keys()) { fk = k; break; }
        for (String k : d0.files().keys()) { xk = k; break; }
        long[] lf = d0.debugLocator(TableIds.FEATURES, fk), lx = d0.debugLocator(TableIds.FILES, xk);
        PowerLossBlobStore dx = copy(dd);
        dd.flip(d0.debugSegmentName(lf[0]), (lf[0] & 0xFFFFFFFFFFFFL) + lf[2] / 2, 4);
        dx.flip(d0.debugSegmentName(lx[0]), (lx[0] & 0xFFFFFFFFFFFFL) + lx[2] / 2, 4);
        DiskStore d1 = DiskStore.open(dd, 0, CLOCK, o);
        int nf = d1.features().size();
        T.check("damaged feature dropped by compaction and counted", d1.compactNow() && d1.features().size() == nf - 1 && d1.stats().damaged == 1
                && d1.features().get(fk) == null);
        DiskStore d2 = DiskStore.open(dx, 0, CLOCK, o);
        boolean threw = false;
        try { d2.compactNow(); } catch (StoreCorruption e) { threw = true; }
        T.check("damaged files record: compaction fails closed, next open refuses", threw && !opens(dx));
    }

    /** 3.12: below the reserve the store stops appending, then accepts only the final allowance; compaction is skipped. */
    static void space() throws Exception {
        T.section("S4 space");
        final long[] free = {1L << 40};
        DiskStore.Options o = small();
        o.space = new SpaceProbe() { public long freeBytes() { return free[0]; } };
        o.reserveBytes = 1 << 20; o.spaceCheckBytes = 2048; o.finalAllowanceBytes = 4096; o.compactMinGarbage = 1L << 40;
        PowerLossBlobStore b = new PowerLossBlobStore();
        KillingBlobStore kb = new KillingBlobStore(b);
        DiskStore s = DiskStore.create(kb, 0, CLOCK, o);
        MemoryStore m = MemoryStore.volatileStore();
        Random r = new Random(3);
        writeSome(m, s, r, 100);
        String durableState = StoreOracleTest.dump(m);
        free[0] = o.reserveBytes - 1;
        long crossing = kb.appendedBytes();
        boolean low = false;
        int n = 0;
        try {
            for (n = 0; n < 100000; n++) s.files().put(StoreContractTest.file("low" + n, "low/" + n, n, Presence.PRESENT, 1));
        } catch (StoreLowSpace e) { low = true; }
        T.check("puts stop with D_LOW_STORAGE after at most one check interval (" + n + " puts)", low && s.lowSpace()
                && kb.appendedBytes() - crossing <= o.spaceCheckBytes + 4096);
        // Pipeline.fail: rollback, the FAILED session, flush — within the final allowance
        s.rollback();
        ScanSession ss = new ScanSession(); ss.scanId = 7; ss.kind = ScanKind.FULL; ss.state = ScanState.FAILED; ss.lastError = "D_LOW_STORAGE";
        s.scanSessions().put(ss);
        s.flush();
        long afterFail = kb.appendedBytes();
        boolean refused = false;
        try { for (int i = 0; i < 1000; i++) s.files().put(StoreContractTest.file("more" + i, "more/" + i, i, Presence.PRESENT, 1)); }
        catch (StoreLowSpace e) { refused = true; }
        T.check("then only the final allowance: appended past the crossing ≤ check interval + allowance + one frame",
                refused && kb.appendedBytes() - crossing <= o.spaceCheckBytes + o.finalAllowanceBytes + 4096 && afterFail > crossing);
        DiskStore re = DiskStore.open(b, 0, CLOCK, o);
        ScanSession got = re.scanSessions().get(ScanSession.keyOf(7));
        T.check("the FAILED session is durable; the rolled-back puts are not", got != null && got.state == ScanState.FAILED && re.files().get("low0") == null);
        T.check("durable state = before the crossing + the session", re.scanSessions().size() >= 1 && re.files().size() == m.files().size());
        free[0] = 1L << 40;
        DiskStore ok = DiskStore.open(b, 0, CLOCK, o);
        ok.files().put(StoreContractTest.file("later", "later/x", 1, Presence.PRESENT, 1));
        ok.flush();
        T.check("space back: the next open works", DiskStore.open(b, 0, CLOCK, o).files().get("later") != null);
        // compaction skipped when free < live + reserve
        free[0] = o.reserveBytes + ok.stats().liveBytes - 1;
        T.check("compaction skipped below live + reserve", !ok.compactNow() && ok.compactionsSkipped() == 1 && ok.compactions() == 0);
        free[0] = o.reserveBytes + ok.stats().liveBytes + 1;
        T.check("compaction runs with live + reserve free", ok.compactNow() && ok.compactions() == 1);
        // a store that starts below the reserve refuses its first append
        free[0] = 10;
        DiskStore st = DiskStore.open(b, 0, CLOCK, o);
        boolean first = false;
        try { st.files().put(StoreContractTest.file("x0", "x/0", 1, Presence.PRESENT, 1)); } catch (StoreLowSpace e) { first = true; }
        T.check("below the reserve at open: the first append refused", first);
    }

    // ------------------------------------------------------------------ F-03 in the store

    static void nonBmpOrder() throws Exception {
        T.section("F-03 store");
        String[] names = {"a", "a😀", "a", "a！", "a￯", "😀", "", "～", "b😁x", "Ａ", "z", "é"};
        for (int pass = 0; pass < 2; pass++) {
            PowerLossBlobStore b = new PowerLossBlobStore();
            DiskStore.Options o = small(); o.mergeMin = 1;
            DiskStore s = DiskStore.create(b, 0, CLOCK, o);
            MemoryStore m = MemoryStore.volatileStore();
            int k = 0;
            for (String d : names) for (String n : names) {
                FileRecord f = StoreContractTest.file(n + d + (k++), d + "/" + n, k, Presence.PRESENT, 1);
                s.files().put(f); m.files().put(f);
                DirRecord dr = new DirRecord(); dr.volumeId = "v"; dr.path = d; s.directories().put(dr); m.directories().put(dr);
            }
            s.commit(); m.commit();
            T.check("merges forced", s.merges() > 0);
            if (pass == 1) { s.close(); s = DiskStore.open(b, 0, CLOCK, o); T.check("index used", s.loadedFromIndex()); }
            else { s.flush(); s = DiskStore.open(b, 0, CLOCK, o); }
            List<String> sorted = new ArrayList<String>();
            for (FileRecord f : m.files().all()) sorted.add(f.fileId);
            List<String> got = new ArrayList<String>();
            for (FileRecord f : s.files().all()) got.add(f.fileId);
            T.eq("all() in String order (" + (pass == 0 ? "scan" : "index") + ")", sorted, got);
            int found = 0;
            for (FileRecord f : m.files().all()) { FileRecord x = s.files().byPath("v", f.path); if (x != null && x.fileId.equals(f.fileId)) found++; }
            T.eq("byPath finds every non-BMP path", m.files().size(), found);
            T.eq("underPrefix order", StoreOracleTest.jsonList(1, m.files().underPrefix("v", "a")), StoreOracleTest.jsonList(1, s.files().underPrefix("v", "a")));
            T.eq("childrenOf dirs", StoreOracleTest.jsonList(17, m.directories().childrenOf("v", "")), StoreOracleTest.jsonList(17, s.directories().childrenOf("v", "")));
        }
    }

    public static int run(String[] args) throws Exception {
        contractAndPersistence();      // first: StoreContractTest.run ends with T.finish
        keyCmp();
        keyIndex();
        kills();
        indexMismatch();
        staleSegments();
        wrongRecord();
        damage();
        nonBmpOrder();
        compaction();
        space();
        return T.finish("DiskStoreTest");
    }

    public static void main(String[] args) throws Exception { System.exit(run(args) == 0 ? 0 : 1); }
}
