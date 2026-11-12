package com.storageplanner.v2.features;

import com.storageplanner.v2.model.Integrity;
import com.storageplanner.v2.model.Tri;
import com.storageplanner.v2.util.Bytes;

import java.io.IOException;

/** Heuristic integrity (PHASE_2 3.3). Used for confidence and REVIEW routing only (H4b). */
public final class IntegrityProbe {
    private IntegrityProbe() {}

    public static Integrity check(ProbeCtx c) throws IOException {
        if (c.integrity != null) return c.integrity;
        String t = c.type;
        if (t.equals("empty")) return Integrity.OK;
        if (t.equals("jpeg")) return jpeg(c);
        if (t.equals("png")) {
            if (c.fr.image == null || c.fr.image.width < 0) return Integrity.CORRUPT;
            byte[] tl = c.tail();
            return tl.length >= 12 && Bytes.at(tl, tl.length, tl.length - 8, "IEND") ? Integrity.OK : Integrity.TRUNCATED;
        }
        if (t.equals("gif")) {
            if (c.fr.image == null || c.fr.image.width < 0) return Integrity.CORRUPT;
            byte[] tl = c.tail();
            return tl.length > 0 && (tl[tl.length - 1] & 0xff) == 0x3B ? Integrity.OK : Integrity.TRUNCATED;
        }
        if (t.equals("pdf")) {
            if (c.fr.pdf != null && c.fr.pdf.encrypted == Tri.TRUE) return Integrity.ENCRYPTED;
            if (Bytes.indexOf(c.head, 0, Math.min(c.headLen, 1024), "%PDF-") < 0) return Integrity.CORRUPT;
            byte[] tl = c.tail();
            return Bytes.indexOf(tl, Math.max(0, tl.length - 1024), tl.length, "%%EOF") >= 0 ? Integrity.OK : Integrity.TRUNCATED;
        }
        return Integrity.UNKNOWN;
    }

    static Integrity jpeg(ProbeCtx c) throws IOException {
        if (c.fr.problems.contains("jpeg:NO_SOF")) return Integrity.CORRUPT;
        if (c.fr.image != null && c.fr.image.motionPhoto == Tri.TRUE) return Integrity.OK;
        byte[] tl = c.tail();
        int end = tl.length;
        while (end > 0 && tl[end - 1] == 0) end--;
        if (end >= 2 && (tl[end - 2] & 0xff) == 0xFF && (tl[end - 1] & 0xff) == 0xD9) return Integrity.OK;
        int eoi = -1;
        for (int i = end - 2; i >= 0; i--) if ((tl[i] & 0xff) == 0xFF && (tl[i + 1] & 0xff) == 0xD9) { eoi = i; break; }
        if (eoi >= 0) {
            if (Bytes.indexOf(tl, eoi, tl.length, "ftyp") >= 0) return Integrity.OK;
            if (Bytes.indexOf(tl, Math.max(eoi, tl.length - 4096), tl.length, "SEFT") >= 0
                    || Bytes.indexOf(tl, Math.max(eoi, tl.length - 4096), tl.length, "SEFH") >= 0) return Integrity.OK;
            c.fr.problem("jpeg:TRAILING_DATA");
            return Integrity.UNKNOWN;
        }
        c.fr.problem("jpeg:NO_EOI_IN_TAIL");
        return Integrity.UNKNOWN;
    }
}
