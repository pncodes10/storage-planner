package com.storageplanner.v2test;

import com.storageplanner.v2.model.*;
import com.storageplanner.v2.store.*;
import com.storageplanner.v2.util.Hex;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.CRC32;

public final class StorePersistenceTest {
    static <R extends Record> void add(StringBuilder b, Table<R> t, Codecs.Codec<R> c) {
        b.append('#').append(c.table()).append('\n');
        for (R r : t.all()) b.append(Codecs.json(c, r)).append('\n');
    }

    /** All 16 tables as Codecs JSON lines. */
    public static String dumpAll(V2Store s) {
        StringBuilder b = new StringBuilder();
        add(b, s.files(), Codecs.FILE); add(b, s.features(), Codecs.FEATURE); add(b, s.hashes(), Codecs.HASH);
        add(b, s.embeddings(), Codecs.EMBEDDING); add(b, s.relationships(), Codecs.RELATIONSHIP);
        add(b, s.duplicateGroups(), Codecs.DUPLICATE_GROUP); add(b, s.backups(), Codecs.BACKUP);
        add(b, s.recommendations(), Codecs.RECOMMENDATION); add(b, s.actionPlans(), Codecs.ACTION_PLAN);
        add(b, s.operations(), Codecs.OPERATION); add(b, s.feedback(), Codecs.FEEDBACK); add(b, s.preferences(), Codecs.PREFERENCE);
        add(b, s.models(), Codecs.MODEL); add(b, s.scanSessions(), Codecs.SESSION); add(b, s.volumes(), Codecs.VOLUME);
        add(b, s.directories(), Codecs.DIR);
        return b.toString();
    }

    static void fill(V2Store s, String tag) {
        FileRecord f = new FileRecord(); f.fileId = "f" + tag; f.volumeId = "primary"; f.path = "DCIM/" + tag + ".jpg"; f.name = tag + ".jpg";
        s.files().put(f);   // every other field at its unknown value (NaN, -1, TIME, null, UNKNOWN)
        FileRecord g = new FileRecord(); g.fileId = "g" + tag; g.volumeId = "primary"; g.path = "x/é 😀" + tag; g.name = "n";
        g.sizeBytes = 0; g.modifiedAt = -1; g.hasPhash = true; g.phash = 0xfedcba9876543210L; g.utility = 0.25; g.readable = Tri.TRUE;
        g.writable = Tri.FALSE; g.accessMode = AccessMode.B_ALL_FILES; g.change = ChangeState.REPLACED; g.tiersDone = 6;
        s.files().put(g);
        FeatureRecord fe = new FeatureRecord(); fe.fileId = "f" + tag; s.features().put(fe);
        HashRecord h = new HashRecord(); h.fileId = "f" + tag; s.hashes().put(h);
        EmbeddingRecord e = new EmbeddingRecord(); e.fileId = "f" + tag; e.modelId = "m"; s.embeddings().put(e);
        Relationship r = new Relationship(); r.edgeId = "e" + tag; r.src = "f" + tag; r.dst = "g" + tag; s.relationships().put(r);
        DuplicateGroup dg = new DuplicateGroup(); dg.groupId = "x:" + tag; s.duplicateGroups().put(dg);
        BackupRecord br = new BackupRecord(); br.backupId = "b" + tag; s.backups().put(br);
        Recommendation rec = new Recommendation(); rec.recommendationId = "r" + tag; rec.fileId = "f" + tag; s.recommendations().put(rec);
        ActionPlan ap = new ActionPlan(); ap.planId = "p" + tag; s.actionPlans().put(ap);
        OperationEntry op = new OperationEntry(); op.opId = "o" + tag; op.seq = 0; op.phase = OpPhase.INTENT; s.operations().put(op);
        Feedback fb = new Feedback(); fb.feedbackId = "fb" + tag; s.feedback().put(fb);
        Preference pr = new Preference(); pr.name = "pref" + tag; pr.value = "v"; s.preferences().put(pr);
        ModelRecord mr = new ModelRecord(); mr.modelId = "model" + tag; s.models().put(mr);
        ScanSession ss = new ScanSession(); ss.scanId = tag.length(); ss.kind = ScanKind.FULL; ss.state = ScanState.RUNNING;
        ss.checkpoint = new ScanCheckpoint(); ss.checkpoint.frontier.add("a"); ss.event(5, "D_X", "primary", "a", null); ss.count("c", 3);
        s.scanSessions().put(ss);
        Volume v = new Volume(); v.volumeId = "vol" + tag; v.grants.add(AccessGrant.readAll(v.volumeId, "", AccessMode.A_MEDIASTORE));
        v.grants.get(0).mediaTypes = java.util.Arrays.asList("image"); s.volumes().put(v);
        DirRecord d = new DirRecord(); d.volumeId = "primary"; d.path = "DCIM" + tag; d.status = DirStatus.OK; s.directories().put(d);
    }

    static boolean opens(Path dir) {
        try { MemoryStore.open(new DirBlobStore(dir), 0, new FixedClock()); return true; }
        catch (StoreException e) { return false; }
        catch (IOException e) { return false; }
    }

    public static int run(String[] args) throws Exception {
        T.section("StorePersistence");
        TempTree t = TempTree.create();
        try {
            FixedClock clock = new FixedClock();
            // flush + reopen equal
            Path d1 = t.dir("s1");
            MemoryStore s = MemoryStore.open(new DirBlobStore(d1), 0, clock);
            fill(s, "A");
            s.flush();
            String before = dumpAll(s);
            MemoryStore re = MemoryStore.open(new DirBlobStore(d1), 0, clock);
            T.eq("reopen equal (16 tables)", before, dumpAll(re));
            int tables = 0; for (String line : before.split("\n")) if (line.startsWith("#")) tables++;
            T.eq("16 tables", 16, tables);
            FileRecord f = re.files().get("fA");
            T.check("NaN round-trips", Double.isNaN(f.utility) && Double.isNaN(f.confidence) && Double.isNaN(f.extractionConfidence));
            T.check("unknown sentinels round-trip", f.sizeBytes == -1 && f.modifiedAt == Unknown.TIME && f.inode == -1 && f.readable == Tri.UNKNOWN && !f.hasPhash);
            FileRecord g = re.files().get("gA");
            T.check("known values round-trip", g.sizeBytes == 0 && g.modifiedAt == -1 && g.phash == 0xfedcba9876543210L && g.writable == Tri.FALSE);

            // kill during the 2nd snapshot → 1st state
            Path d2 = t.dir("s2");
            MemoryStore k = MemoryStore.open(new KillingBlobStore(new DirBlobStore(d2)).killBeforeReplaceCommit(2), 0, clock);
            fill(k, "A"); k.commit();
            String state1 = dumpAll(k);
            fill(k, "BB");
            boolean killed = false;
            try { k.commit(); } catch (SimulatedKill e) { killed = true; }
            T.check("killed before 2nd snapshot", killed);
            T.eq("reopen gives 1st snapshot", state1, dumpAll(MemoryStore.open(new DirBlobStore(d2), 0, clock)));

            // kill right after the 2nd durable snapshot → 2nd state
            Path d3 = t.dir("s3");
            k = MemoryStore.open(new KillingBlobStore(new DirBlobStore(d3)).killAfterReplaceCommits(2), 0, clock);
            fill(k, "A"); k.commit();
            fill(k, "BB");
            String state2 = dumpAll(k);
            killed = false;
            try { k.commit(); } catch (SimulatedKill e) { killed = true; }
            T.check("killed after 2nd snapshot", killed);
            T.eq("reopen gives 2nd snapshot", state2, dumpAll(MemoryStore.open(new DirBlobStore(d3), 0, clock)));

            // damaged snapshots are rejected, never partially loaded
            byte[] snap = Files.readAllBytes(d1.resolve(MemoryStore.SNAPSHOT));
            int cutBad = 0, flipBad = 0, n = 0;
            for (int off = 0; off < snap.length; off += 997) {
                n++;
                Path c = t.dir("cut" + off);
                Files.write(c.resolve(MemoryStore.SNAPSHOT), java.util.Arrays.copyOf(snap, off));
                if (opens(c)) cutBad++;
                Path fl = t.dir("flip" + off);
                byte[] x = snap.clone(); x[off] ^= 0x01;
                Files.write(fl.resolve(MemoryStore.SNAPSHOT), x);
                if (opens(fl)) flipBad++;
            }
            Path c = t.dir("cutlast");
            Files.write(c.resolve(MemoryStore.SNAPSHOT), java.util.Arrays.copyOf(snap, snap.length - 1));
            if (opens(c)) cutBad++;
            T.check("several offsets tested", n >= 3);
            T.eq("every cut rejected", 0, cutBad);
            T.eq("every flipped byte rejected", 0, flipBad);

            // snapshot interval
            Path d4 = t.dir("s4");
            FixedClock fc = new FixedClock();
            DirBlobStore b4 = new DirBlobStore(d4);
            MemoryStore p = MemoryStore.open(b4, 60000, fc);
            fill(p, "A"); p.commit();
            fill(p, "BB"); p.commit();
            T.eq("commits within interval write nothing", -1L, b4.length(MemoryStore.SNAPSHOT));
            fc.advance(60001);
            fill(p, "CCC"); p.commit();
            T.eq("first commit after interval writes one snapshot", 1, p.snapshotsWritten());
            T.check("snapshot exists", b4.length(MemoryStore.SNAPSHOT) > 0);
            p.flush();
            T.eq("flush with nothing dirty writes nothing", 1, p.snapshotsWritten());
            fc.advance(60001); p.commit();
            T.eq("commit with nothing dirty writes nothing", 1, p.snapshotsWritten());

            // forward/backward compatibility of the record format
            Path d5 = t.dir("s5");
            String[] lines = {
                "{\"format\":\"sp-v2-store\",\"schema\":1,\"commitSeq\":3,\"createdAt\":0,\"futureHeader\":1}",
                "{\"t\":\"files\",\"v\":{\"fileId\":\"z\",\"volumeId\":\"primary\",\"path\":\"a/z.txt\",\"futureField\":[1,2],\"presence\":\"PRESENT\"}}",
                "{\"t\":\"future_table\",\"v\":{\"k\":1}}",
            };
            StringBuilder sb = new StringBuilder();
            for (String l : lines) sb.append(l).append('\n');
            byte[] body = sb.toString().getBytes(StandardCharsets.UTF_8);
            CRC32 crc = new CRC32(); crc.update(body);
            String all = sb + "{\"end\":true,\"records\":2,\"crc32\":\"" + Hex.of8(crc.getValue()) + "\"}\n";
            Files.write(d5.resolve(MemoryStore.SNAPSHOT), all.getBytes(StandardCharsets.UTF_8));
            MemoryStore hs = MemoryStore.open(new DirBlobStore(d5), 0, clock);
            FileRecord z = hs.files().get("z");
            T.check("unknown fields ignored", z != null && z.path.equals("a/z.txt"));
            T.check("missing fields take unknown values", z != null && z.sizeBytes == -1 && z.modifiedAt == Unknown.TIME
                    && Double.isNaN(z.confidence) && z.readable == Tri.UNKNOWN && "UNKNOWN".equals(z.category) && "".equals(z.extension));
        } finally { t.close(); }
        return T.finish("StorePersistenceTest");
    }
}
