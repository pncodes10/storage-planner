package com.storageplanner.v2test;

import com.storageplanner.v2.model.*;
import com.storageplanner.v2.store.DiskStore;
import com.storageplanner.v2.store.StoreStats;
import com.storageplanner.v2.util.Hex;

import java.util.Locale;
import java.util.Random;

/**
 * S2 exit measurement (PHASE_S_STORE 6, S2): a synthetic N-file DiskStore filled through the API (no file tree): per
 * file a FileRecord, FeatureRecord and HashRecord; relationships for 20 %, pair checks for 10 %, recommendations for 5 %,
 * one directory per 100 files. Prints stats().indexBytesEstimate per file and the measured heap of the open store
 * (after GC, decoded-record cache dropped), plus timings. Not in run_tests.sh (minutes at 100k).
 * Usage: DiskStoreScale [N] [dir]
 */
public final class DiskStoreScale {
    public static void main(String[] args) throws Exception {
        int n = args.length > 0 ? Integer.parseInt(args[0]) : 100000;
        TempTree t = TempTree.create();
        try {
            DirBlobStore b = new DirBlobStore(args.length > 1 ? java.nio.file.Paths.get(args[1]) : t.root());
            FixedClock clock = new FixedClock();
            long base = HeapProfile.used();
            long t0 = System.nanoTime();
            DiskStore s = DiskStore.create(b, -1, clock, null);
            Random r = new Random(1);
            String[] dirs = new String[Math.max(1, n / 100)];
            for (int i = 0; i < dirs.length; i++) dirs[i] = (i % 3 == 0 ? "DCIM/Camera" : i % 3 == 1 ? "WhatsApp/Media/WhatsApp Images" : "Download") + "/d" + i;
            for (int i = 0; i < n; i++) {
                String id = Hex.of16(r.nextLong()) + Hex.of16(r.nextLong());
                FileRecord f = new FileRecord();
                f.fileId = id; f.volumeId = "primary"; String d = dirs[i % dirs.length];
                f.name = String.format(Locale.ROOT, "IMG_2024%04d_%06d.jpg", i % 10000, i);
                f.path = d + "/" + f.name; f.sizeBytes = 1000 + r.nextInt(5000000); f.presence = Presence.PRESENT; f.changeScanId = 1;
                f.sha256 = Hex.of16(r.nextLong()) + Hex.of16(r.nextLong()) + Hex.of16(r.nextLong()) + Hex.of16(r.nextLong());
                s.files().put(f);
                FeatureRecord fe = new FeatureRecord(); fe.fileId = id; fe.extractedScanId = 1; s.features().put(fe);
                HashRecord h = new HashRecord(); h.fileId = id; h.crc32 = r.nextInt(); s.hashes().put(h);
                if (i % 5 == 0) { Relationship e = new Relationship(); e.edgeId = "x:" + id; e.src = id; e.dst = "y" + id; s.relationships().put(e); }
                if (i % 10 == 0) { PairCheck p = new PairCheck(); p.a = id; p.b = "z" + id; p.pairKey = id + "|z" + id; p.verdict = PairVerdict.values()[0]; s.pairChecks().put(p); }
                if (i % 20 == 0) { Recommendation rc = new Recommendation(); rc.recommendationId = "r" + id; rc.fileId = id; s.recommendations().put(rc); }
                if (i < dirs.length) { DirRecord dr = new DirRecord(); dr.volumeId = "primary"; dr.path = dirs[i]; dr.status = DirStatus.OK; s.directories().put(dr); }
                if (i % 2000 == 1999) s.commit();
            }
            s.flush();
            long t1 = System.nanoTime();
            s.debugDropCache();
            StoreStats st = s.stats();
            long used = HeapProfile.used() - base;
            int fileCount = s.files().size();
            long t2 = System.nanoTime();
            int k = 0;
            for (FileRecord f : s.files().all()) { if (f.fileId != null) k++; }
            long t3 = System.nanoTime();
            s.close();
            s = null;
            long t4 = System.nanoTime();
            DiskStore re = DiskStore.open(b, -1, clock, null);
            long t5 = System.nanoTime();
            long usedRe = HeapProfile.used() - base;
            StoreStats st2 = re.stats();
            System.out.println("DISK_SCALE n=" + n + " " + st);
            System.out.printf(Locale.ROOT, "DISK_SCALE indexBytesEstimate/file=%.1f measuredHeap/file=%.1f live/file=%.0f disk/file=%.0f%n",
                    st.indexBytesEstimate / (double) n, used / (double) n, st.liveBytes / (double) n, st.diskBytes / (double) n);
            System.out.printf(Locale.ROOT, "DISK_SCALE fill %.1f s, iterate all files %.1f s (%d), close (index file) %.1f s, reopen from index %.1f s (index used: %s, files %d)%n",
                    (t1 - t0) / 1e9, (t3 - t2) / 1e9, k, (t4 - t3) / 1e9, (t5 - t4) / 1e9, re.loadedFromIndex(), re.files().size());
            System.out.printf(Locale.ROOT, "DISK_SCALE after reopen from the index: indexBytesEstimate/file=%.1f measuredHeap/file=%.1f%n",
                    st2.indexBytesEstimate / (double) n, usedRe / (double) n);
            if (fileCount != n || re.files().size() != n) { System.out.println("DISK_SCALE FAILED: counts"); System.exit(1); }
            re.close();
        } finally { t.close(); }
    }
}
