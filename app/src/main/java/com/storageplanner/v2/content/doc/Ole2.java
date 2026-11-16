package com.storageplanner.v2.content.doc;

import com.storageplanner.v2.model.DocStatus;

/**
 * OLE2 compound files (PHASE_6 7.3, simplified): no sector chains are parsed. The first 4 MiB are searched for the UTF-16LE names
 * {@code EncryptedPackage} / {@code EncryptionInfo} (a password-protected OOXML document); legacy .doc/.xls/.ppt text is not
 * extracted. Both outcomes are unknown, so a false hit only errs on the safe side.
 */
public final class Ole2 {
    private Ole2() {}

    public static final int MAX_BYTES = 4 << 20;
    static final int[] MAGIC = {0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1};

    public static boolean isOle2(byte[] b, int len) {
        if (len < 8) return false;
        for (int i = 0; i < 8; i++) if ((b[i] & 0xFF) != MAGIC[i]) return false;
        return true;
    }

    /** @param b first bytes of the file, len valid bytes (at most {@link #MAX_BYTES} are looked at) */
    public static OoxmlText.Result check(byte[] b, int len) {
        OoxmlText.Result r = new OoxmlText.Result();
        r.format = "ole2";
        try {
            len = Math.min(len, Math.min(b.length, MAX_BYTES));
            if (!isOle2(b, len)) { r.status = DocStatus.UNSUPPORTED; return r; }
            if (contains16(b, len, "EncryptedPackage") || contains16(b, len, "EncryptionInfo")) r.status = DocStatus.ENCRYPTED;
            else r.status = DocStatus.UNSUPPORTED;
        } catch (RuntimeException e) {
            r.status = DocStatus.UNSUPPORTED;
        }
        return r;
    }

    static boolean contains16(byte[] b, int len, String s) {
        int n = s.length();
        int last = len - 2 * n;
        byte f = (byte) s.charAt(0);
        for (int i = 0; i <= last; i++) {
            if (b[i] != f || b[i + 1] != 0) continue;
            int j = 1;
            while (j < n && b[i + 2 * j] == (byte) s.charAt(j) && b[i + 2 * j + 1] == 0) j++;
            if (j == n) return true;
        }
        return false;
    }
}
