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

    /**
     * zlib-wrapped inflation (PDF FlateDecode) of comp[off, off+len) with an output cap. Returns the bytes, or null when the output
     * would exceed maxOut, the data is invalid or needs a preset dictionary. Truncated input returns what was inflated so far.
     */
    public static byte[] inflateZlib(byte[] comp, int off, int len, int maxOut) {
        if (len <= 0 || off < 0 || off + len > comp.length) return new byte[0];
        Inflater inf = new Inflater(false);
        try {
            inf.setInput(comp, off, len);
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(maxOut, Math.max(64, len * 4)));
            byte[] buf = new byte[8192];
            int guard = 0;
            while (!inf.finished()) {
                int n = inf.inflate(buf);
                if (n == 0) {
                    if (inf.needsDictionary()) return null;
                    if (inf.needsInput() || ++guard > 4) break;
                } else guard = 0;
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
