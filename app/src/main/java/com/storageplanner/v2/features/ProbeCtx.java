package com.storageplanner.v2.features;

import com.storageplanner.v2.device.ReadableFile;
import com.storageplanner.v2.model.FeatureRecord;
import com.storageplanner.v2.model.Integrity;
import com.storageplanner.v2.util.Io;

import java.io.IOException;

/** Inputs and outputs shared by the tier-1 probes of one file. */
public final class ProbeCtx {
    public ReadableFile rf; public byte[] head; public int headLen; public long size; public String type, ext;
    public FeatureRecord fr; public Limits lim = Limits.DEFAULT;
    /** Integrity decided by a probe (null = let IntegrityProbe decide). */
    public Integrity integrity;
    public int probeProblems; public boolean probeException;
    private byte[] tail;

    public void problem(String p) { fr.problem(p); probeProblems++; }

    /** Last min(size, lim.tailBytes) bytes (read once). */
    public byte[] tail() throws IOException {
        if (tail == null) {
            if (size <= headLen) tail = head.length == headLen ? head : java.util.Arrays.copyOf(head, headLen);
            else { int n = (int) Math.min(size, lim.tailBytes); tail = Io.readAt(rf, size - n, n); }
        }
        return tail;
    }
    /** Bytes [pos, pos+len) served from head when possible. len must be capped by the caller. */
    public byte[] read(long pos, int len) throws IOException {
        if (pos >= 0 && pos + len <= headLen) return java.util.Arrays.copyOfRange(head, (int) pos, (int) pos + len);
        return Io.readAt(rf, pos, len);
    }
}
