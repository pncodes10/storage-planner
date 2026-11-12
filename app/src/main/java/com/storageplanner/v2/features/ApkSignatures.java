package com.storageplanner.v2.features;

import com.storageplanner.v2.device.ReadableFile;
import com.storageplanner.v2.model.ArchiveEntry;
import com.storageplanner.v2.util.Bytes;
import com.storageplanner.v2.util.Hashing;
import com.storageplanner.v2.util.Io;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Signer identity of an APK (PHASE_2 5 step 3). Signatures are NOT verified. */
public final class ApkSignatures {
    private ApkSignatures() {}
    static final long V2 = 0x7109871aL, V3 = 0xf05368c0L, V31 = 0x1b93ad61L;

    public static final class Result { public List<String> certs; public String scheme; public String problem; }

    public static Result read(ReadableFile rf, long size, long cdOffset, List<ArchiveEntry> entries, Limits lim) throws IOException {
        Result r = new Result();
        if (cdOffset >= 24) {
            byte[] f = Io.readAt(rf, cdOffset - 24, 24);
            if (f.length == 24 && Bytes.at(f, 24, 8, "APK Sig Block 42")) {
                long bs = Bytes.u64le(f, 0);
                if (bs < 24 || bs > 16L << 20 || cdOffset - bs - 8 < 0) { r.problem = "APK_SIG_BLOCK_SIZE"; return r; }
                byte[] blk = Io.readAt(rf, cdOffset - bs - 8, (int) (bs + 8));
                if (Bytes.u64le(blk, 0) != bs) { r.problem = "APK_SIG_BLOCK_INCONSISTENT"; return r; }
                boolean v2 = false, v3 = false;
                List<String> certs = null;
                int p = 8, end = blk.length - 24;
                for (int k = 0; k < 1000 && p + 12 <= end; k++) {
                    long len = Bytes.u64le(blk, p);
                    if (len < 4 || p + 8 + len > end) { r.problem = "APK_SIG_PAIR_SIZE"; return r; }
                    long id = Bytes.u32le(blk, p + 8);
                    int vs = p + 12, ve = (int) (p + 8 + len);
                    if (id == V2 || id == V3 || id == V31) {
                        if (id == V2) v2 = true; else v3 = true;
                        List<String> c = signers(blk, vs, ve);
                        if (c != null && (certs == null || id == V2)) certs = c;
                    }
                    p = ve;
                }
                if (v2 || v3) { r.certs = certs; r.scheme = v2 && v3 ? "v2+v3" : v2 ? "v2" : "v3"; if (certs == null) r.problem = "APK_SIG_SIGNERS"; return r; }
            }
        }
        for (ArchiveEntry e : entries) {   // v1
            if (e.depth != 0) continue;
            String u = e.path.toUpperCase(Locale.ROOT);
            if (!(u.startsWith("META-INF/") && (u.endsWith(".RSA") || u.endsWith(".DSA") || u.endsWith(".EC")))) continue;
            byte[] d = ZipProbe.read(rf, size, e, lim.inflateSignature, 200);
            if (d == null) { r.problem = "APK_V1_UNREADABLE"; return r; }
            int[] c = Der.firstCertificate(d);
            r.certs = new ArrayList<String>();
            if (c != null) r.certs.add(Hashing.sha256Hex(java.util.Arrays.copyOfRange(d, c[0], c[0] + c[1])));
            else { r.certs.add(Hashing.sha256Hex(d)); r.problem = "APK_V1_NOT_PKCS7_FILE_HASH"; }
            r.scheme = "v1";
            return r;
        }
        r.certs = new ArrayList<String>(); r.scheme = "none";
        return r;
    }

    /** First certificate of each signer in a v2/v3 value; null if malformed. */
    static List<String> signers(byte[] b, int s, int e) {
        try {
            List<String> out = new ArrayList<String>();
            long seqLen = Bytes.u32le(b, s);
            int p = s + 4, end = (int) Math.min(e, s + 4 + seqLen);
            for (int k = 0; k < 16 && p + 4 <= end; k++) {
                long sl = Bytes.u32le(b, p);
                int ss = p + 4, se = (int) (ss + sl);
                if (se > end) return null;
                long sdl = Bytes.u32le(b, ss);                 // signed data
                int d = ss + 4, de = (int) (d + sdl);
                long dl = Bytes.u32le(b, d);                   // digests
                int c = (int) (d + 4 + dl);
                long cl = Bytes.u32le(b, c);                   // certificates
                if (c + 4 + cl > de) return null;
                long certLen = Bytes.u32le(b, c + 4);
                if (c + 8 + certLen > de) return null;
                out.add(Hashing.sha256Hex(java.util.Arrays.copyOfRange(b, c + 8, (int) (c + 8 + certLen))));
                p = se;
            }
            return out;
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
