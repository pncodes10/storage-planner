package com.storageplanner.v2test;

import com.storageplanner.v2.device.BlobStore;
import com.storageplanner.v2.model.PairCheck;
import com.storageplanner.v2.model.PairVerdict;
import com.storageplanner.v2.model.Record;
import com.storageplanner.v2.store.Codecs;
import com.storageplanner.v2.store.MemoryStore;
import com.storageplanner.v2.store.Table;
import com.storageplanner.v2.store.V2Store;
import com.storageplanner.v2.store.disk.Frame;
import com.storageplanner.v2.store.disk.FrameException;
import com.storageplanner.v2.store.disk.SegmentReader;
import com.storageplanner.v2.store.disk.SegmentWriter;
import com.storageplanner.v2.store.disk.TableIds;
import com.storageplanner.v2.util.Json;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/** PHASE_S_STORE slice S1: frame encode/decode, CRC, truncation, readers bounded by durable, torn appends. */
public final class FrameTest {

    /** Every table with its codec, in the frame table-id order (1..17). */
    @SuppressWarnings({"rawtypes"})
    static Object[][] tables(V2Store s) {
        return new Object[][]{
                {s.files(), Codecs.FILE}, {s.features(), Codecs.FEATURE}, {s.hashes(), Codecs.HASH}, {s.embeddings(), Codecs.EMBEDDING},
                {s.relationships(), Codecs.RELATIONSHIP}, {s.duplicateGroups(), Codecs.DUPLICATE_GROUP}, {s.backups(), Codecs.BACKUP},
                {s.pairChecks(), Codecs.PAIR_CHECK}, {s.recommendations(), Codecs.RECOMMENDATION}, {s.actionPlans(), Codecs.ACTION_PLAN},
                {s.operations(), Codecs.OPERATION}, {s.feedback(), Codecs.FEEDBACK}, {s.preferences(), Codecs.PREFERENCE},
                {s.models(), Codecs.MODEL}, {s.scanSessions(), Codecs.SESSION}, {s.volumes(), Codecs.VOLUME}, {s.directories(), Codecs.DIR}};
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static void roundTrip() throws Exception {
        T.section("round trip");
        V2Store s = MemoryStore.volatileStore();
        StorePersistenceTest.fill(s, "a");
        StorePersistenceTest.fill(s, "bé😀");
        PairCheck pc = new PairCheck(); pc.pairKey = "fa|ga"; pc.a = "fa"; pc.b = "ga"; pc.verdict = PairVerdict.values()[0]; pc.hamming = 3;
        s.pairChecks().put(pc);
        s.commit();
        Object[][] ts = tables(s);
        T.eq("17 tables", Frame.MAX_TABLE, ts.length);
        int n = 0, bad = 0;
        for (int id = 1; id <= ts.length; id++) {
            Table t = (Table) ts[id - 1][0];
            Codecs.Codec c = (Codecs.Codec) ts[id - 1][1];
            T.check("TableIds.codec(" + id + ") is " + c.table(), TableIds.codec(id) == c && TableIds.id(c.table()) == id);
            int inTable = 0;
            for (Object o : t.all()) {
                Record r = (Record) o;
                String json = Codecs.json(c, r);
                long txn = 0x0123456789abcdefL + id;
                byte[] f = Frame.put(id, txn, r.key(), Frame.utf8(json));
                Frame p = Frame.parse(f, 0, f.length);
                Record back = (Record) c.read(Json.parseObject(new String(p.val, "UTF-8")));
                boolean ok = p.type == Frame.PUT && p.table == id && p.txn == txn && p.keyString().equals(r.key()) && p.length == f.length
                        && json.equals(Codecs.json(c, back)) && r.key().equals(back.key());
                if (!ok) { bad++; System.out.println("  round trip failed: " + c.table() + " " + r.key()); }
                n++; inTable++;
            }
            T.check("records in table " + c.table(), inTable > 0);
        }
        T.eq("round-trip failures over " + n + " records", 0, bad);
        Frame d = Frame.parse(Frame.del(3, 7, "k"), 0, Frame.del(3, 7, "k").length);
        T.check("DEL parses", d.type == Frame.DEL && d.table == 3 && d.txn == 7 && d.keyString().equals("k") && d.val.length == 0);
        byte[] cm = Frame.commit(9, 1234567890123L);
        Frame cf = Frame.parse(cm, 0, cm.length);
        T.check("COMMIT parses", cf.type == Frame.COMMIT && cf.table == 0 && cf.txn == 9 && cf.commitSeq() == 1234567890123L);
        byte[] hd = Frame.header("{\"version\":1}");
        Frame hf = Frame.parse(hd, 0, hd.length);
        T.check("HEADER parses", hf.type == Frame.HEADER && hf.keyString().equals(Frame.HEADER_KEY));
    }

    static List<byte[]> samples() {
        List<byte[]> l = new ArrayList<byte[]>();
        l.add(Frame.put(1, 5, "0123456789abcdef0123456789abcdef", Frame.utf8("{\"fileId\":\"x\",\"path\":\"é 😀\"}")));
        l.add(Frame.del(17, 6, "primary\u0000DCIM"));
        l.add(Frame.commit(6, 42));
        l.add(Frame.header("{\"version\":1,\"schema\":1,\"gen\":1,\"set\":\"a\",\"seq\":1,\"epoch\":0}"));
        byte[] big = new byte[300];
        new Random(3).nextBytes(big);
        char[] longKey = new char[200];
        Arrays.fill(longKey, 'k');                      // 2-byte varint key length
        l.add(Frame.put(2, Long.MAX_VALUE, new String(longKey), big));
        return l;
    }

    static void flipsAndTruncation() {
        T.section("flips");
        int undetected = 0, tried = 0;
        for (byte[] f : samples()) {
            for (int i = 0; i < f.length; i++) {
                for (int mask : new int[]{0x01, 0x10, 0x80, 0xFF}) {
                    byte[] g = f.clone();
                    g[i] ^= (byte) mask;
                    tried++;
                    try { Frame.parse(g, 0, g.length); undetected++; System.out.println("  undetected flip at " + i + " mask " + mask); }
                    catch (FrameException expected) { /* detected */ }
                }
            }
        }
        T.eq("undetected single-byte flips (of " + tried + ")", 0, undetected);
        T.section("truncation");
        int accepted = 0, notTruncated = 0;
        for (byte[] f : samples()) {
            for (int k = 0; k < f.length; k++) {
                try { Frame.parse(f, 0, k); accepted++; }
                catch (FrameException e) { if (!e.truncated) notTruncated++; }
            }
        }
        T.eq("prefixes accepted as frames", 0, accepted);
        T.eq("prefixes reported as damaged instead of truncated", 0, notTruncated);
    }

    /** RandomRead that records the highest byte position requested. */
    static final class Watching implements BlobStore.RandomRead {
        final BlobStore.RandomRead in;
        long maxEnd;
        Watching(BlobStore.RandomRead in) { this.in = in; }
        public int read(long pos, byte[] b, int off, int len) throws IOException { maxEnd = Math.max(maxEnd, pos + len); return in.read(pos, b, off, len); }
        public long length() throws IOException { return in.length(); }
        public void close() throws IOException { in.close(); }
    }

    static void segments() throws Exception {
        T.section("segments");
        try (TempTree t = TempTree.create()) {
            DirBlobStore b = new DirBlobStore(t.root());
            Random rnd = new Random(11);
            // a stale segment of a crashed run: start() must truncate it so the HEADER is at offset 0
            try (java.io.OutputStream o = b.append("ds-a-0001.seg")) { o.write(Frame.commit(1, 1)); }
            SegmentWriter w = SegmentWriter.start(b, "ds-a-0001.seg", "{\"version\":1}");
            List<Long> pos = new ArrayList<Long>();
            List<byte[]> frames = new ArrayList<byte[]>();
            for (int i = 0; i < 60; i++) {
                int len = i % 10 == 9 ? 70000 + rnd.nextInt(5000) : rnd.nextInt(6000);   // some frames larger than the buffers
                byte[] v = new byte[len];
                rnd.nextBytes(v);
                byte[] f = Frame.put(1 + i % 17, i, "key" + i, v);
                frames.add(f);
                pos.add(w.append(f));
            }
            T.check("flushedLength lags until flushBuffer", w.flushedLength() <= w.length());
            w.sync();
            T.eq("flushed == length after sync", w.length(), w.flushedLength());
            long end = w.length();
            w.close();
            T.eq("blob length", end, b.length("ds-a-0001.seg"));
            Watching rr = new Watching(b.openRead("ds-a-0001.seg"));
            SegmentReader r = new SegmentReader(rr, 4096);
            Frame h = r.readAt(0, end);
            T.check("header at offset 0", h.type == Frame.HEADER);
            int bad = 0;
            for (int i = frames.size() - 1; i >= 0; i--) {   // random-ish order
                Frame f = r.readAt(pos.get(i), end);
                if (!f.keyString().equals("key" + i) || f.length != frames.get(i).length) bad++;
            }
            T.eq("random reads", 0, bad);
            // sequential scan with a 64 KiB reader
            SegmentReader seq = new SegmentReader(b.openRead("ds-a-0001.seg"), 1 << 16);
            long p = 0;
            int count = 0;
            while (p < end) { Frame f = seq.readAt(p, end); p += f.length; count++; }
            T.eq("sequential scan frames", frames.size() + 1, count);
            T.eq("scan ends exactly at the end", end, p);
            seq.close();
            // garbage after the durable limit, and limits cutting frames
            try (java.io.OutputStream o = b.append("ds-a-0001.seg")) { byte[] g = new byte[3000]; rnd.nextBytes(g); o.write(g); o.write(Frame.commit(99, 99)); }
            int violations = 0, wrong = 0;
            for (int trial = 0; trial < 300; trial++) {
                long limit = 1 + (long) (rnd.nextDouble() * (end - 1));
                Watching w2 = new Watching(b.openRead("ds-a-0001.seg"));
                SegmentReader lr = new SegmentReader(w2, trial % 2 == 0 ? 4096 : 1 << 16);
                for (int i = 0; i < frames.size(); i++) {
                    long fp = pos.get(i);
                    boolean fits = fp + frames.get(i).length <= limit;
                    try {
                        lr.readAt(fp, limit);
                        if (!fits) wrong++;
                    } catch (FrameException e) {
                        if (fits || !e.truncated) wrong++;
                    }
                }
                if (w2.maxEnd > limit) violations++;
                lr.close();
            }
            T.eq("reads requested past the durable limit", 0, violations);
            T.eq("frames accepted/refused wrongly against the limit", 0, wrong);
            // a scan to the end of the file with the real length stops at the garbage, as damaged or truncated
            Watching w3 = new Watching(b.openRead("ds-a-0001.seg"));
            SegmentReader gr = new SegmentReader(w3, 4096);
            boolean garbageRefused = false;
            try { gr.readAt(end, w3.length()); } catch (FrameException e) { garbageRefused = true; }
            T.check("random garbage after durable is not a frame", garbageRefused);
            gr.close();
            // a segment shorter than its limit (lost tail)
            boolean shortRefused = false;
            try { new SegmentReader(b.openRead("ds-a-0001.seg"), 4096).readAt(b.length("ds-a-0001.seg"), b.length("ds-a-0001.seg") + 100000); }
            catch (FrameException e) { shortRefused = e.truncated; }
            T.check("segment shorter than the limit is refused", shortRefused);
            // a flipped byte on disk inside a frame
            int j = 17;
            try (RandomAccessFile raf = new RandomAccessFile(t.path("ds-a-0001.seg").toFile(), "rw")) {
                long at = pos.get(j) + frames.get(j).length / 2;
                raf.seek(at);
                int x = raf.read();
                raf.seek(at);
                raf.write(x ^ 0x40);
            }
            boolean damaged = false;
            try { new SegmentReader(b.openRead("ds-a-0001.seg"), 4096).readAt(pos.get(j), end); }
            catch (FrameException e) { damaged = !e.truncated; }
            T.check("on-disk flip is damage", damaged);
            r.close();
            T.check("absent blob: openRead null", b.openRead("nope.seg") == null);
        }
    }

    static void tornAppend() throws Exception {
        T.section("torn append");
        try (TempTree t = TempTree.create()) {
            Random rnd = new Random(5);
            int bad = 0;
            for (int trial = 0; trial < 40; trial++) {
                long killAt = 20 + rnd.nextInt(40000);
                KillingBlobStore kb = new KillingBlobStore(new DirBlobStore(t.root())).killAfterAppendedBytes(killAt);
                String name = "t" + trial + ".seg";
                List<Long> pos = new ArrayList<Long>();
                List<Integer> lens = new ArrayList<Integer>();
                boolean killed = false;
                try {
                    SegmentWriter w = SegmentWriter.start(kb, name, "{}");
                    for (int i = 0; i < 200; i++) {
                        byte[] v = new byte[rnd.nextInt(900)];
                        byte[] f = Frame.put(1, i, "k" + i, v);
                        pos.add(w.append(f)); lens.add(f.length);
                        if (i % 7 == 0) w.flushBuffer();
                    }
                    w.close();
                } catch (SimulatedKill k) { killed = true; }
                long len = kb.length(name);
                if (!killed || len != killAt) { bad++; continue; }
                SegmentReader r = new SegmentReader(kb.openRead(name), 4096);
                for (int i = 0; i < pos.size(); i++) {
                    boolean complete = pos.get(i) + lens.get(i) <= len;
                    try { r.readAt(pos.get(i), len); if (!complete) bad++; }
                    catch (FrameException e) { if (complete || !e.truncated) bad++; }
                }
                r.close();
            }
            T.eq("torn-append trials with a wrong outcome", 0, bad);
        }
    }

    public static int run(String[] args) throws Exception {
        roundTrip();
        flipsAndTruncation();
        segments();
        tornAppend();
        return T.finish("FrameTest");
    }

    public static void main(String[] a) throws Exception { System.exit(run(a) == 0 ? 0 : 1); }
}
