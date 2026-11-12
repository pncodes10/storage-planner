package com.storageplanner.v2test;

import com.storageplanner.v2.device.Availability;
import com.storageplanner.v2.device.InstalledApp;
import com.storageplanner.v2.features.*;
import com.storageplanner.v2.model.*;
import com.storageplanner.v2.util.Hashing;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** ZipProbe safety limits, subtypes, AXML (binary and text stand-in), APK signer and install state (PHASE_2 5, 7). */
public final class ZipApkTest {
    static byte[] zip(Object... nameData) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ZipOutputStream z = new ZipOutputStream(bos);
        for (int i = 0; i < nameData.length; i += 2) {
            ZipEntry e = new ZipEntry((String) nameData[i]);
            byte[] d = nameData[i + 1] instanceof String ? ((String) nameData[i + 1]).getBytes(StandardCharsets.UTF_8) : (byte[]) nameData[i + 1];
            if (((String) nameData[i]).endsWith(".zip") || ((String) nameData[i]).equals("mimetype")) {
                e.setMethod(ZipEntry.STORED); e.setSize(d.length); e.setCompressedSize(d.length);
                CRC32 c = new CRC32(); c.update(d); e.setCrc(c.getValue());
            }
            z.putNextEntry(e); z.write(d); z.closeEntry();
        }
        z.close();
        return bos.toByteArray();
    }
    static byte[] u16(int v) { return new byte[]{(byte) v, (byte) (v >> 8)}; }
    static byte[] u32(long v) { return new byte[]{(byte) v, (byte) (v >> 8), (byte) (v >> 16), (byte) (v >> 24)}; }

    /** Raw single-entry zip with a crafted central directory (declared sizes, flags, name). */
    static byte[] raw(String name, long csize, long usize, int flags) {
        byte[] n = name.getBytes(StandardCharsets.ISO_8859_1);
        byte[] data = new byte[(int) Math.min(csize, 64)];
        byte[] lh = FxImg.cat(new byte[]{'P', 'K', 3, 4}, u16(20), u16(flags), u16(8), u16(0), u16(0x21), u32(0), u32(csize), u32(usize), u16(n.length), u16(0), n, data);
        byte[] cd = FxImg.cat(new byte[]{'P', 'K', 1, 2}, u16(20), u16(20), u16(flags), u16(8), u16(0), u16(0x21), u32(0), u32(csize), u32(usize),
                u16(n.length), u16(0), u16(0), u16(0), u16(0), u32(0), u32(0), n);
        byte[] eocd = FxImg.cat(new byte[]{'P', 'K', 5, 6}, u16(0), u16(0), u16(1), u16(1), u32(cd.length), u32(lh.length), u16(0));
        return FxImg.cat(lh, cd, eocd);
    }

    /** Binary AXML manifest: string pool (UTF-16 or UTF-8), resource map, <manifest package versionCode versionName>. */
    static byte[] axml(boolean utf8, String pkg, long vc, String vn, boolean obfuscate) {
        String[] pool = {obfuscate ? "" : "versionCode", obfuscate ? "" : "versionName", "manifest", "package", pkg, vn, "http://schemas.android.com/apk/res/android"};
        ByteArrayOutputStream sp = new ByteArrayOutputStream();
        List<Integer> offs = new ArrayList<Integer>();
        for (String s : pool) {
            offs.add(sp.size());
            if (utf8) { byte[] b = s.getBytes(StandardCharsets.UTF_8); sp.write(s.length()); sp.write(b.length); sp.write(b, 0, b.length); sp.write(0); }
            else { byte[] b = s.getBytes(StandardCharsets.UTF_16LE); sp.write(s.length()); sp.write(0); sp.write(b, 0, b.length); sp.write(0); sp.write(0); }
        }
        while (sp.size() % 4 != 0) sp.write(0);
        int hs = 28, strStart = hs + 4 * pool.length;
        ByteArrayOutputStream ch = new ByteArrayOutputStream();
        byte[] offsets = new byte[0]; for (int o : offs) offsets = FxImg.cat(offsets, u32(o));
        byte[] spc = FxImg.cat(u16(1), u16(hs), u32(strStart + sp.size()), u32(pool.length), u32(0), u32(utf8 ? 0x100 : 0), u32(strStart), u32(0), offsets, sp.toByteArray());
        byte[] rmap = FxImg.cat(u16(0x180), u16(8), u32(16), u32(0x0101021b), u32(0x0101021c));
        byte[] attrs = FxImg.cat(
                u32(6), u32(0), u32(0xFFFFFFFFL), u16(8), new byte[]{0, 0x10}, u32(vc),          // versionCode INT_DEC
                u32(6), u32(1), u32(5), u16(8), new byte[]{0, 0x03}, u32(5),                     // versionName STRING
                u32(0xFFFFFFFFL), u32(3), u32(4), u16(8), new byte[]{0, 0x03}, u32(4));          // package
        byte[] el = FxImg.cat(u16(0x102), u16(16), u32(16 + 20 + attrs.length), u32(1), u32(0xFFFFFFFFL),
                u32(0xFFFFFFFFL), u32(2), u16(20), u16(20), u16(3), u16(0), u16(0), u16(0), attrs);
        byte[] body = FxImg.cat(spc, rmap, el);
        return FxImg.cat(u16(3), u16(8), u32(8 + body.length), body);
    }

    /** APK Signing Block with one v2 (and optionally v3) signer carrying certificate bytes cert, placed before the CD. */
    static byte[] signedApk(byte[] plainZip, byte[] cert, boolean v3) {
        byte[] certs = FxImg.cat(u32(cert.length + 4), u32(cert.length), cert);
        byte[] signed = FxImg.cat(u32(0) /* digests */, certs, u32(0) /* attrs */);
        byte[] signer = FxImg.cat(u32(signed.length), signed, u32(0), u32(0));
        byte[] value = FxImg.cat(u32(signer.length + 4), u32(signer.length), signer);
        byte[] pairs = FxImg.cat(u64(value.length + 4), u32(0x7109871aL), value);
        if (v3) pairs = FxImg.cat(pairs, u64(value.length + 4), u32(0xf05368c0L), value);
        long bs = pairs.length + 8 + 16;
        byte[] block = FxImg.cat(u64(bs), pairs, u64(bs), "APK Sig Block 42".getBytes(StandardCharsets.US_ASCII));
        int eocd = -1; for (int i = plainZip.length - 22; i >= 0; i--) if (plainZip[i] == 'P' && plainZip[i + 1] == 'K' && plainZip[i + 2] == 5 && plainZip[i + 3] == 6) { eocd = i; break; }
        int cdOff = (int) (plainZip[eocd + 16] & 0xff | (plainZip[eocd + 17] & 0xff) << 8 | (plainZip[eocd + 18] & 0xff) << 16 | (plainZip[eocd + 19] & 0xff) << 24);
        byte[] out = FxImg.cat(Arrays.copyOfRange(plainZip, 0, cdOff), block, Arrays.copyOfRange(plainZip, cdOff, plainZip.length));
        int ne = eocd + block.length + 16;
        byte[] no = u32(cdOff + block.length); System.arraycopy(no, 0, out, ne, 4);
        return out;
    }
    static byte[] u64(long v) { return FxImg.cat(u32(v), u32(v >>> 32)); }

    static InstalledApp app(String pkg, long vc, String cert) {
        InstalledApp a = new InstalledApp(); a.packageName = pkg; a.versionCode = vc; a.signingCertSha256 = Collections.singletonList(cert); return a;
    }
    static ArchiveInfo ar(Fx fx, String p) { return fx.store.features().get(fx.f(p).fileId).archive; }
    static ApkInfo apk(Fx fx, String p) { return fx.store.features().get(fx.f(p).fileId).apk; }

    public static int run(String[] args) throws Exception {
        T.section("ZipProbe");
        byte[] cert = "CERTIFICATE-BYTES-C".getBytes(StandardCharsets.US_ASCII);
        String certHash = Hashing.sha256Hex(cert);
        try (Fx fx = Fx.create()) {
            fx.maxTier = 2;
            fx.apps = new JvmInstalledApps(Availability.FULL, app("com.same", 10, certHash), app("com.newer", 20, certHash), app("com.older", 5, "ffff"));
            TempTree t = fx.tree;
            byte[] normal = zip("a.txt", "hello", "dir/b.bin", new byte[3000], "c.jpg", "x");
            t.file("Download/normal.zip", normal);
            Object[] many = new Object[240]; for (int i = 0; i < 120; i++) { many[2 * i] = "f" + i; many[2 * i + 1] = "x" + i; }
            t.file("Download/many.zip", zip(many));
            t.file("Download/trav.zip", zip("../evil.txt", "x"));
            t.file("Download/abs.zip", zip("/etc/x", "x"));
            t.file("Download/drive.zip", zip("C:\\x", "x"));
            t.file("Download/bomb.zip", raw("big.bin", 40, 10L * (1L << 30) - 1, 0));
            t.file("Download/enc.zip", raw("secret.txt", 40, 100, 1));
            byte[] commented = Arrays.copyOf(normal, normal.length + 65535);
            commented[normal.length - 2] = (byte) 0xFF; commented[normal.length - 1] = (byte) 0xFF;
            t.file("Download/comment.zip", commented);
            t.file("Download/cut.zip", Arrays.copyOf(normal, normal.length / 2));
            byte[] inner2 = zip("deep.txt", "d"), inner1 = zip("inner2.zip", inner2, "i.txt", "i");
            t.file("Download/nested.zip", zip("inner1.zip", inner1));
            t.file("Download/sfx.zip", FxImg.cat(new byte[777], normal));
            t.file("Download/doc.docx", zip("[Content_Types].xml", "<x/>", "word/document.xml", "<w/>"));
            t.file("Download/sheet.xlsx", zip("[Content_Types].xml", "<x/>", "xl/workbook.xml", "<w/>"));
            t.file("Download/slides.pptx", zip("[Content_Types].xml", "<x/>", "ppt/p.xml", "<w/>"));
            t.file("Download/text.odt", zip("mimetype", "application/vnd.oasis.opendocument.text", "content.xml", "<x/>"));
            t.file("Download/book.epub", zip("mimetype", "application/epub+zip", "OEBPS/a.xhtml", "<x/>"));
            t.file("Download/lib.jar", zip("META-INF/MANIFEST.MF", "Manifest-Version: 1.0", "a/B.class", "x"));
            byte[] same = signedApk(zip("AndroidManifest.xml", axml(false, "com.same", 10, "1.0", false), "classes.dex", "dex"), cert, true);
            t.file("Download/same.apk", same);
            t.file("Download/newer.apk", zip("AndroidManifest.xml", axml(true, "com.newer", 15, "1.5", true), "classes.dex", "dex"));
            t.file("Download/older.apk", signedApk(zip("AndroidManifest.xml", axml(false, "com.older", 9, "0.9", false), "resources.arsc", "r"), cert, false));
            t.file("Download/absent.apk", zip("AndroidManifest.xml", "<manifest package=\"com.absent\" android:versionCode=\"3\" android:versionName=\"3\"/>", "classes.dex", "d"));
            t.file("Download/notapk.apk", zip("x.txt", "x"));
            t.file("Download/bundle.apks", zip("base.apk", "x", "toc.pb", "y"));
            t.file("Download/bundle.xapk", zip("base.apk", "x", "manifest.json", "{}"));
            fx.scan();

            ArchiveInfo n = ar(fx, "Download/normal.zip");
            T.eq("normal OK", "OK", n.listingStatus); T.eq("normal 3 entries", 3, n.entries.size());
            java.util.zip.ZipFile zf = new java.util.zip.ZipFile(t.path("Download/normal.zip").toFile());
            ZipEntry je = zf.getEntry("dir/b.bin"); zf.close();
            ArchiveEntry be = null; for (ArchiveEntry e : n.entries) if (e.path.equals("dir/b.bin")) be = e;
            T.check("size/crc equal java.util.zip", be != null && be.size == je.getSize() && be.crc32 == je.getCrc() && be.compressedSize == je.getCompressedSize());
            T.eq("normal integrity OK", Integrity.OK, fx.f("Download/normal.zip").integrity);
            ArchiveInfo m = ar(fx, "Download/many.zip");
            T.eq("many: declared 120", 120L, m.declaredEntries); T.eq("many: listed 120 (limit 10000)", 120, m.listedEntries);
            T.eq("../ → pathTraversal", Tri.TRUE, ar(fx, "Download/trav.zip").pathTraversal);
            T.eq("/etc/x → absolutePaths", Tri.TRUE, ar(fx, "Download/abs.zip").absolutePaths);
            T.eq("C:\\x → absolutePaths", Tri.TRUE, ar(fx, "Download/drive.zip").absolutePaths);
            T.eq("10 GiB declared in tiny file → bomb", Tri.TRUE, ar(fx, "Download/bomb.zip").bombSuspect);
            T.eq("encrypted flag", Tri.TRUE, ar(fx, "Download/enc.zip").encryptedEntries);
            T.eq("all encrypted → ENCRYPTED", Integrity.ENCRYPTED, fx.f("Download/enc.zip").integrity);
            T.eq("65535-byte comment: listed", "OK", ar(fx, "Download/comment.zip").listingStatus);
            T.eq("cut in half → TRUNCATED", "TRUNCATED", ar(fx, "Download/cut.zip").listingStatus);
            ArchiveInfo ne = ar(fx, "Download/nested.zip");
            boolean d1 = false, d2 = false;
            for (ArchiveEntry e : ne.entries) { if (e.depth == 1 && "inner1.zip".equals(e.container)) d1 = true; if (e.depth == 2 && e.path.equals("deep.txt")) d2 = true; }
            T.check("stored nested → depth 1 listed", d1); T.check("depth 2 listed", d2); T.eq("nested counted", 2, ne.nestedArchives);
            T.eq("SFX prefix → listed", 3, ar(fx, "Download/sfx.zip").listedEntries);
            T.eq("docx", "ooxml-docx", fx.f("Download/doc.docx").type); T.eq("xlsx", "ooxml-xlsx", fx.f("Download/sheet.xlsx").type);
            T.eq("pptx", "ooxml-pptx", fx.f("Download/slides.pptx").type); T.eq("odt", "odf", fx.f("Download/text.odt").type);
            T.eq("epub", "epub", fx.f("Download/book.epub").type); T.eq("jar", "jar", fx.f("Download/lib.jar").type);
            T.eq("apks", "apks", fx.f("Download/bundle.apks").type); T.eq("xapk", "xapk", fx.f("Download/bundle.xapk").type);
            T.eq("docx: entries not stored (F-M12)", null, ar(fx, "Download/doc.docx").entries);
            T.check("docx: counts kept", ar(fx, "Download/doc.docx").listedEntries == 2);

            T.section("Apk");
            ApkInfo s = apk(fx, "Download/same.apk");
            T.eq("same: package (UTF-16 pool)", "com.same", s.packageName); T.eq("same: versionCode", 10L, s.versionCode);
            T.eq("same: versionName", "1.0", s.versionName); T.eq("same: v2+v3", "v2+v3", s.signatureScheme);
            T.eq("same: cert hash", Collections.singletonList(certHash), s.signerCertSha256);
            T.eq("same: SAME_VERSION", InstallState.SAME_VERSION, s.installState); T.eq("same: sameSigner", Tri.TRUE, s.sameSigner);
            T.check("SIGNATURE_UNVERIFIED problem", fx.store.features().get(fx.f("Download/same.apk").fileId).problems.contains("apk:SIGNATURE_UNVERIFIED"));
            ApkInfo nw = apk(fx, "Download/newer.apk");
            T.eq("obfuscated names (UTF-8 pool) → by resource id", 15L, nw.versionCode);
            T.eq("installed newer", InstallState.INSTALLED_NEWER, nw.installState); T.eq("unsigned → none", "none", nw.signatureScheme);
            ApkInfo ol = apk(fx, "Download/older.apk");
            T.eq("installed older", InstallState.INSTALLED_OLDER, ol.installState); T.eq("different cert → sameSigner FALSE", Tri.FALSE, ol.sameSigner);
            ApkInfo ab = apk(fx, "Download/absent.apk");
            T.eq("text stand-in manifest", "com.absent", ab.packageName); T.eq("text source", "ZIP_TEXT", ab.parseSource);
            T.eq("absent + FULL → NOT_INSTALLED", InstallState.NOT_INSTALLED, ab.installState);
            T.eq("zip named .apk → zip", "zip", fx.f("Download/notapk.apk").type);
            T.eq("zip named .apk → BENIGN", ExtMismatch.BENIGN, fx.f("Download/notapk.apk").extMismatch);
            T.eq("zip named .apk → no apk info", null, apk(fx, "Download/notapk.apk"));
            ApkInfo pi = new ApkInfo(); pi.packageName = "com.x"; pi.versionCode = 1;
            ApkProbe.installState(pi, new JvmInstalledApps(Availability.PARTIAL)); T.eq("absent + PARTIAL → UNKNOWN", InstallState.UNKNOWN, pi.installState);
            ApkProbe.installState(pi, new JvmInstalledApps((String) null)); T.eq("UNAVAILABLE → UNKNOWN", InstallState.UNKNOWN, pi.installState);
        }
        T.section("ZipLimits");
        try (Fx fx = Fx.create()) {
            Limits lim = new Limits(); lim.archiveMaxEntries = 50;
            Object[] many = new Object[240]; for (int i = 0; i < 120; i++) { many[2 * i] = "f" + i; many[2 * i + 1] = "x"; }
            fx.tree.file("m.zip", zip(many));
            com.storageplanner.v2.device.ReadableFile rf = fx.posix.open("primary", "m.zip");
            ZipProbe.Listing l = ZipProbe.list(rf, rf.size(), lim);
            T.eq("limit 50 → TRUNCATED", "TRUNCATED", l.info.listingStatus); T.eq("declared 120", 120L, l.info.declaredEntries); T.eq("listed 50", 50, l.info.listedEntries);
            lim.zipMaxCdBytes = 100;
            T.eq("CD > limit → LIMIT", "LIMIT", ZipProbe.list(rf, rf.size(), lim).info.listingStatus);
            rf.close();
            T.eq("AXML chunk past buffer → problem", "AXML_CHUNK_SIZE", AxmlParser.parse(Arrays.copyOf(axml(true, "p", 1, "v", false), 60)).problem);
        }
        return T.finish("ZipApkTest");
    }
}
