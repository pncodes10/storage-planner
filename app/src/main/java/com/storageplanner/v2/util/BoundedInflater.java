package com.storageplanner.v2.util;

import java.io.ByteArrayOutputStream;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/** Raw-deflate inflation into memory with an output cap. */
public final class BoundedInflater {
    private BoundedInflater() {}
    /** Inflated bytes, or null when the output would exceed maxOut or the data is invalid. */
    public static byte[] inflate(byte[] comp, int maxOut) {
        Inflater inf = new Inflater(true);
        try {
            inf.setInput(comp);
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(maxOut, Math.max(64, comp.length * 4)));
            byte[] buf = new byte[8192];
            while (!inf.finished()) {
                int n = inf.inflate(buf);
                if (n == 0) { if (inf.needsInput() || inf.needsDictionary()) break; }
                if (out.size() + n > maxOut) return null;
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } catch (DataFormatException e) {
            return null;
        } finally {
            inf.end();
        }
    }
}
