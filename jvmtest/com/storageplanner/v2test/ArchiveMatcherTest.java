package com.storageplanner.v2test;

import com.storageplanner.v2.model.*;

import java.io.ByteArrayOutputStream;
import java.util.Random;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** PHASE_3 12 ArchiveMatcherTest (core cases). */
public final class ArchiveMatcherTest {
    static byte[] rnd(int n, long seed) { byte[] b = new byte[n]; new Random(seed).nextBytes(b); return b; }
    static void stored(ZipOutputStream z, String name, byte[] d) throws Exception {
        ZipEntry e = new ZipEntry(name); e.setMethod(ZipEntry.STORED); e.setSize(d.length); e.setCompressedSize(d.length);
        CRC32 c = new CRC32(); c.update(d); e.setCrc(c.getValue()); z.putNextEntry(e); z.write(d); z.closeEntry();
    }
    static int indexOf(byte[] h, byte[] n) { outer: for (int i = 0; i + n.length <= h.length; i++) { for (int j = 0; j < n.length; j++) if (h[i + j] != n[j]) continue outer; return i; } return -1; }

    public static int run(String[] args) throws Exception {
        T.section("ArchiveMatcher");
        try (Fx fx = Fx.create()) {
            fx.maxTier = 2; fx.graph = true;
            byte[] a = rnd(5000, 1), b = rnd(6000, 2), c = rnd(7000, 3);
            fx.tree.file("DCIM/Camera/a.jpg", a); fx.tree.file("DCIM/Camera/b.jpg", b); fx.tree.file("DCIM/Camera/c.jpg", c);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (ZipOutputStream z = new ZipOutputStream(bos)) {
                ZipEntry e = new ZipEntry("DCIM/Camera/a.jpg"); z.putNextEntry(e); z.write(a); z.closeEntry();   // deflated, intact
                stored(z, "DCIM/Camera/b.jpg", b);                                                               // corrupted below
            }
            byte[] zip = bos.toByteArray();
            int at = indexOf(zip, java.util.Arrays.copyOfRange(b, 100, 132));
            zip[at] ^= 0x55;
            fx.tree.file("Backups/g.zip", zip);
            fx.scan();
            String za = fx.f("Backups/g.zip").fileId;
            Relationship ok = null, bad = null; int contained = 0;
            for (Relationship r : fx.store.relationships().all()) {
                if (r.type == RelType.CONTAINED_IN && r.dst.equals(za)) contained++;
                if (r.type == RelType.BACKED_UP_BY && r.src.equals(fx.f("DCIM/Camera/a.jpg").fileId)) ok = r;
                if (r.type == RelType.BACKED_UP_BY && r.src.equals(fx.f("DCIM/Camera/b.jpg").fileId)) bad = r;
            }
            T.eq("2 CONTAINED_IN edges", 2, contained);
            T.check("intact member: VERIFIED BACKED_UP_BY", ok != null && ok.verification == Verification.VERIFIED && ok.dst.equals(za + "!DCIM/Camera/a.jpg"));
            T.eq("bad CRC member: no edge", null, bad);
            int corrupt = 0, verified = 0;
            for (BackupRecord r : fx.store.backups().all()) { if (r.state == BackupState.CORRUPT) corrupt++; if (r.state == BackupState.VERIFIED) verified++; }
            T.eq("records: 1 VERIFIED", 1, verified); T.eq("records: 1 CORRUPT", 1, corrupt);
            T.eq("absent member c: no record", 2, fx.store.backups().size());
            fx.tree.file("DCIM/Camera/a.jpg", rnd(5000, 9));
            fx.scan(ScanKind.INCREMENTAL, false);
            boolean still = false;
            for (Relationship r : fx.store.relationships().all()) if (r.type == RelType.BACKED_UP_BY && r.verification == Verification.VERIFIED) still = true;
            T.check("file changed after verification: no VERIFIED edge", !still);
        }
        return T.finish("ArchiveMatcherTest");
    }
}
