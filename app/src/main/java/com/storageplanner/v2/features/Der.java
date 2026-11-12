package com.storageplanner.v2.features;

/** Minimal DER TLV walker (depth ≤ 16) used to find the first certificate of a PKCS#7 SignedData. */
public final class Der {
    private Der() {}
    /** {tag, contentStart, contentLength, totalLength} of the TLV at p, or null if malformed. */
    public static int[] tlv(byte[] b, int p, int end) {
        if (p + 2 > end) return null;
        int tag = b[p] & 0xff, l = b[p + 1] & 0xff, q = p + 2;
        long len;
        if (l < 0x80) len = l;
        else {
            int n = l & 0x7F;
            if (n == 0 || n > 4 || q + n > end) return null;
            len = 0; for (int i = 0; i < n; i++) len = (len << 8) | (b[q + i] & 0xff);
            q += n;
        }
        if (q + len > end) return null;
        return new int[]{tag, q, (int) len, (int) (q + len - p)};
    }

    static final byte[] SIGNED_DATA = {0x2A, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xF7, 0x0D, 0x01, 0x07, 0x02};

    /** DER bytes [start, start+len) of the first certificate in a PKCS#7 ContentInfo; null if not found. */
    public static int[] firstCertificate(byte[] b) {
        int[] ci = tlv(b, 0, b.length);                       // ContentInfo SEQUENCE
        if (ci == null || ci[0] != 0x30) return null;
        int[] oid = tlv(b, ci[1], ci[1] + ci[2]);
        if (oid == null || oid[0] != 0x06 || oid[2] != SIGNED_DATA.length) return null;
        for (int i = 0; i < SIGNED_DATA.length; i++) if (b[oid[1] + i] != SIGNED_DATA[i]) return null;
        int[] ctx = tlv(b, oid[1] + oid[2], ci[1] + ci[2]);  // [0] EXPLICIT
        if (ctx == null || ctx[0] != 0xA0) return null;
        int[] sd = tlv(b, ctx[1], ctx[1] + ctx[2]);           // SignedData SEQUENCE
        if (sd == null || sd[0] != 0x30) return null;
        int p = sd[1], end = sd[1] + sd[2];
        for (int k = 0; k < 16 && p < end; k++) {               // version, digestAlgorithms, contentInfo, [0] certificates
            int[] t = tlv(b, p, end);
            if (t == null) return null;
            if (t[0] == 0xA0) {
                int[] cert = tlv(b, t[1], t[1] + t[2]);
                return cert == null || cert[0] != 0x30 ? null : new int[]{t[1], cert[3]};
            }
            p += t[3];
        }
        return null;
    }
}
