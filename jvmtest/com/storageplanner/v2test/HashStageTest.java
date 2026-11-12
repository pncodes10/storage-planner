package com.storageplanner.v2test;

import com.storageplanner.core.CoreOracle;
import com.storageplanner.v2.features.QuickHash;
import com.storageplanner.v2.features.T1MetadataStage;
import com.storageplanner.v2.model.*;
import com.storageplanner.v2.stage.Budget;
import com.storageplanner.v2.util.Hex;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Random;
import java.util.zip.CRC32;

/** HashStage (PHASE_2 3.4 Part B). */
public final class HashStageTest {
    static byte[] rnd(int n, long seed) { byte[] b = new byte[n]; new Random(seed).nextBytes(b); return b; }
    static String sha(byte[] b) throws Exception { return Hex.of(MessageDigest.getInstance("SHA-256").digest(b)); }

    public static int run(String[] args) throws Exception {
        T.section("HashStage");
        try (Fx fx = Fx.create()) {
            fx.maxTier = 2;
            TempTree t = fx.tree;
            byte[] big = rnd(300000, 1);
            t.file("a/unique.bin", rnd(5000, 2));
            t.file("a/s1.bin", rnd(200000, 3)); t.file("a/s2.bin", rnd(200000, 4));            // same size, different content
            t.file("a/d1.bin", big); t.file("b/d2.bin", big);                                // identical, > 128 KiB
            byte[] small = rnd(1000, 5);
            t.file("a/m1.bin", small); t.file("b/m2.bin", small);                           // identical, small
            t.file("a/empty.txt", new byte[0]);
            fx.scan();
            T.eq("unique: only T1's head read", 5000L, fx.faulty.bytesRead.get("a/unique.bin"));
            FileRecord u = fx.f("a/unique.bin");
            T.check("unique: bit 2 done", (u.tiersDone & 4) != 0); T.eq("unique: sha null", null, u.sha256);
            HashRecord h1 = fx.store.hashes().get(fx.f("a/s1.bin").fileId), h2 = fx.store.hashes().get(fx.f("a/s2.bin").fileId);
            T.check("same size: quick hashes differ", h1 != null && h2 != null && !h1.quickHash.equals(h2.quickHash));
            T.eq("same size: no full hash", null, h1.sha256);
            FileRecord d1 = fx.f("a/d1.bin"), d2 = fx.f("b/d2.bin");
            T.eq("identical: sha = MessageDigest", sha(big), d1.sha256);
            T.eq("identical: both", d1.sha256, d2.sha256);
            CRC32 c = new CRC32(); c.update(big);
            T.eq("crc32", c.getValue(), fx.store.hashes().get(d1.fileId).crc32);
            T.eq("group id", "x:" + sha(big), d1.duplicateGroupId);
            T.eq("small identical: sha", sha(small), fx.f("a/m1.bin").sha256);
            T.check("small: quick hash stored", fx.store.hashes().get(fx.f("a/m1.bin").fileId).quickHash != null);
            T.eq("small: quick hash = classic", CoreOracle.quickHash(t.path("a/m1.bin").toString(), 1000), fx.store.hashes().get(fx.f("a/m1.bin").fileId).quickHash);
            T.eq("empty: SHA256_EMPTY", T1MetadataStage.SHA256_EMPTY, fx.f("a/empty.txt").sha256);
            T.eq("empty: no group", null, fx.f("a/empty.txt").duplicateGroupId);
            T.eq("unique: no group", null, u.duplicateGroupId);
            T.eq("random bytes: UNSUPPORTED (type unknown)", ExtractionStatus.UNSUPPORTED, d1.extractionStatus);
            T.eq("tiers 1+2 done", 6, d1.tiersDone);

            // quick hash equals classic for boundary sizes
            for (int n : new int[]{1, 65536, 131072, 131073, 10 << 20}) {
                Path p = t.file("q/q" + n, rnd(n, n));
                com.storageplanner.v2.device.ReadableFile rf = fx.posix.open("primary", "q/q" + n);
                T.eq("quickHash = classic, size " + n, CoreOracle.quickHash(p.toString(), n), QuickHash.of(rf, n));
                String[] full = QuickHash.full(rf, n);
                T.eq("full() quick = classic, size " + n, CoreOracle.quickHash(p.toString(), n), full[2]);
                T.eq("full() sha = classic, size " + n, CoreOracle.fullHash(p.toString()), full[0]);
                rf.close();
                t.delete("q/q" + n);
            }

            // incremental: a new same-size file reads only itself (+ full hashes on match)
            fx.scan(ScanKind.INCREMENTAL, false);
            fx.faulty.bytesRead.clear();
            fx.scan(ScanKind.INCREMENTAL, false);
            T.eq("unchanged incremental: 0 bytes read", 0L, fx.faulty.totalBytesRead());
            fx.faulty.bytesRead.clear();
            t.file("c/u2.bin", rnd(5000, 9));                                               // joins unique.bin's size group
            fx.scan(ScanKind.INCREMENTAL, false);
            T.check("formerly unique now quick-hashed", fx.store.hashes().get(fx.f("a/unique.bin").fileId) != null);
            T.check("s1/s2 not re-read", fx.faulty.bytesRead.get("a/s1.bin") == null && fx.faulty.bytesRead.get("a/s2.bin") == null);
            fx.faulty.bytesRead.clear();
            t.file("c/d3.bin", big);
            fx.scan(ScanKind.INCREMENTAL, false);
            T.check("third copy: only it read", fx.faulty.bytesRead.containsKey("c/d3.bin") && !fx.faulty.bytesRead.containsKey("a/d1.bin"));
            T.eq("third copy grouped", "x:" + sha(big), fx.f("c/d3.bin").duplicateGroupId);
            // changed fingerprint → re-hash
            t.file("b/d2.bin", rnd(300000, 77)); t.mtime("b/d2.bin", t.mtime("a/d1.bin") + 5000);
            fx.scan(ScanKind.INCREMENTAL, false);
            T.check("changed: new sha", !sha(big).equals(fx.f("b/d2.bin").sha256));
            T.eq("changed: left the group", null, fx.f("b/d2.bin").duplicateGroupId);
            // read error mid-file → no hash, bit failed
            t.file("e/x1.bin", big); t.file("e/x2.bin", rnd(300000, 8));
            fx.faulty.failReadAfter("e/x1.bin", 70000);
            fx.scan(ScanKind.INCREMENTAL, false);
            FileRecord x1 = fx.f("e/x1.bin");
            T.check("read error: bit 2 failed", (x1.tiersFailed & 4) != 0 || (x1.tiersFailed & 2) != 0);
            T.eq("read error: no sha", null, x1.sha256);
        }
        try (Fx fx = Fx.create()) {   // policy ALL and byte budget
            fx.maxTier = 2; fx.hashAll = true;
            fx.tree.file("u1.bin", rnd(3000, 1)); fx.tree.file("u2.bin", rnd(4000, 2));
            fx.scan();
            T.eq("ALL: unique hashed", sha(Files.readAllBytes(fx.tree.path("u1.bin"))), fx.f("u1.bin").sha256);
        }
        try (Fx fx = Fx.create()) {
            fx.maxTier = 2;
            for (int i = 0; i < 6; i++) { fx.tree.file("g" + i + "/a.bin", rnd(200000 + i, i)); fx.tree.file("g" + i + "/b.bin", rnd(200000 + i, i)); }
            Budget b = new Budget(); b.maxBytesRead = 700000; fx.budget = b;
            com.storageplanner.v2.pipeline.PipelineResult r = fx.scan();
            T.eq("byte budget → BUDGET_EXHAUSTED", com.storageplanner.v2.stage.StageOutcome.BUDGET_EXHAUSTED, r.outcome);
            int pending = 0; for (FileRecord f : fx.files()) if ((f.tiersPending & 4) != 0) pending++;
            T.check("pending bits left", pending > 0);
            fx.budget = new Budget();
            r = fx.scan(ScanKind.FULL, true);
            T.eq("resume completes", com.storageplanner.v2.stage.StageOutcome.DONE, r.outcome);
            int grouped = 0; for (FileRecord f : fx.files()) if (f.duplicateGroupId != null) grouped++;
            T.eq("all 12 grouped after resume", 12, grouped);
        }
        return T.finish("HashStageTest");
    }
}
