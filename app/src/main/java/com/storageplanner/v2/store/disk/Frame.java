package com.storageplanner.v2.store.disk;

import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

/**
 * One log frame (PHASE_S_STORE 3.2). Big-endian integers; varint = unsigned LEB128.
 * <pre>
 * frame := magic:u8=0xD5 type:u8 table:u8 flags:u8 txn:u64 keyLen:varint key valLen:u32 val crc:u32
 * crc   := CRC32 of every preceding byte of the frame
 * </pre>
 * Parsing is strict: any field outside its domain, a CRC mismatch or a structurally impossible control frame is
 * {@link FrameException#damaged}; a frame that does not fit in the bytes given is {@link FrameException#truncated} (with
 * the number of bytes needed, when known).
 */
public final class Frame {
    public static final int MAGIC = 0xD5;
    public static final int PUT = 1, DEL = 2, COMMIT = 3, HEADER = 4;
    /** Tables 1..17 (ARCHITECTURE 4.1 order, files = 1 … directories = 17); 0 = control frame. */
    public static final int MAX_TABLE = 17;
    public static final String HEADER_KEY = "sp-v2-ds";
    static final int FIXED = 12;                 // magic, type, table, flags, txn
    /** Smallest possible frame: fixed part, 1-byte varint 0, valLen, crc. */
    public static final int MIN_FRAME = FIXED + 1 + 4 + 4;
    public static final int MAX_KEY = 1 << 16;
    public static final int MAX_VAL = 1 << 26;   // 64 MiB; a record is a few KB, a big ActionPlan a few hundred KB

    public final int type, table, flags;
    public final long txn;
    public final byte[] key, val;
    /** Encoded length of the whole frame. */
    public final int length;

    Frame(int type, int table, int flags, long txn, byte[] key, byte[] val, int length) {
        this.type = type; this.table = table; this.flags = flags; this.txn = txn; this.key = key; this.val = val; this.length = length;
    }

    public String keyString() { return new String(key, StandardCharsets.UTF_8); }

    // ------------------------------------------------------------------ encode

    public static byte[] put(int table, long txn, String key, byte[] val) { return encode(PUT, table, txn, utf8(key), val); }
    public static byte[] del(int table, long txn, String key) { return encode(DEL, table, txn, utf8(key), new byte[0]); }
    public static byte[] commit(long txn, long commitSeq) { byte[] v = new byte[8]; putLong(v, 0, commitSeq); return encode(COMMIT, 0, txn, new byte[0], v); }
    public static byte[] header(String json) { return encode(HEADER, 0, 0, utf8(HEADER_KEY), utf8(json)); }

    public static byte[] utf8(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    public static byte[] encode(int type, int table, long txn, byte[] key, byte[] val) {
        if (type < PUT || type > HEADER) throw new IllegalArgumentException("frame type " + type);
        if (table < 0 || table > MAX_TABLE) throw new IllegalArgumentException("table " + table);
        if (key.length > MAX_KEY) throw new IllegalArgumentException("key too long: " + key.length);
        if (val.length > MAX_VAL) throw new IllegalArgumentException("value too long: " + val.length);
        int vl = varintLen(key.length);
        int n = FIXED + vl + key.length + 4 + val.length + 4;
        byte[] b = new byte[n];
        b[0] = (byte) MAGIC; b[1] = (byte) type; b[2] = (byte) table; b[3] = 0;
        putLong(b, 4, txn);
        int p = FIXED;
        int k = key.length;
        while (true) {
            if ((k & ~0x7f) == 0) { b[p++] = (byte) k; break; }
            b[p++] = (byte) ((k & 0x7f) | 0x80);
            k >>>= 7;
        }
        System.arraycopy(key, 0, b, p, key.length); p += key.length;
        putInt(b, p, val.length); p += 4;
        System.arraycopy(val, 0, b, p, val.length); p += val.length;
        CRC32 c = new CRC32();
        c.update(b, 0, p);
        putInt(b, p, (int) c.getValue());
        return b;
    }

    static int varintLen(int v) { int n = 1; while ((v & ~0x7f) != 0) { v >>>= 7; n++; } return n; }

    // ------------------------------------------------------------------ decode

    /** Parses the frame starting at b[off], with avail bytes available from off. */
    public static Frame parse(byte[] b, int off, int avail) throws FrameException {
        if (avail < 1) throw FrameException.truncated("empty", MIN_FRAME);
        if ((b[off] & 0xff) != MAGIC) throw FrameException.damaged("bad magic");
        if (avail < FIXED + 1) throw FrameException.truncated("short header", MIN_FRAME);
        int type = b[off + 1] & 0xff, table = b[off + 2] & 0xff, flags = b[off + 3] & 0xff;
        if (type < PUT || type > HEADER) throw FrameException.damaged("bad type " + type);
        if (table > MAX_TABLE) throw FrameException.damaged("bad table " + table);
        if (flags != 0) throw FrameException.damaged("unknown flags " + flags);
        long txn = getLong(b, off + 4);
        int p = off + FIXED;
        int keyLen = 0, shift = 0;
        while (true) {
            if (p - off >= avail) throw FrameException.truncated("short key length", Math.max(MIN_FRAME, p - off + 9));
            int x = b[p++] & 0xff;
            if (shift == 28 && (x & 0xf0) != 0) throw FrameException.damaged("key length overflow");
            keyLen |= (x & 0x7f) << shift;
            if ((x & 0x80) == 0) break;
            shift += 7;
            if (shift > 28) throw FrameException.damaged("key length varint too long");
        }
        if (keyLen > MAX_KEY) throw FrameException.damaged("key length " + keyLen);
        int vl = p - (off + FIXED);
        if (vl != varintLen(keyLen)) throw FrameException.damaged("non-canonical varint");
        int head = FIXED + vl + keyLen + 4;
        if (head > avail) throw FrameException.truncated("short key", head + 4);
        int valLen = getInt(b, off + FIXED + vl + keyLen);
        if (valLen < 0 || valLen > MAX_VAL) throw FrameException.damaged("value length " + valLen);
        int total = head + valLen + 4;
        if (total > avail) throw FrameException.truncated("short frame", total);
        CRC32 c = new CRC32();
        c.update(b, off, total - 4);
        if ((int) c.getValue() != getInt(b, off + total - 4)) throw FrameException.damaged("CRC mismatch");
        // structure of control and data frames (checked after the CRC: a valid CRC with impossible structure is a writer bug)
        boolean ok;
        switch (type) {
            case PUT: ok = table >= 1; break;
            case DEL: ok = table >= 1 && valLen == 0; break;
            case COMMIT: ok = table == 0 && keyLen == 0 && valLen == 8; break;
            default: ok = table == 0 && keyLen == HEADER_KEY.length(); break;
        }
        if (!ok) throw FrameException.damaged("impossible frame structure (type " + type + ", table " + table + ")");
        byte[] key = new byte[keyLen];
        System.arraycopy(b, off + FIXED + vl, key, 0, keyLen);
        byte[] val = new byte[valLen];
        System.arraycopy(b, off + head, val, 0, valLen);
        return new Frame(type, table, flags, txn, key, val, total);
    }

    /** COMMIT frame's commitSeq. */
    public long commitSeq() { return getLong(val, 0); }

    // ------------------------------------------------------------------ big-endian helpers

    static void putInt(byte[] b, int p, int v) { b[p] = (byte) (v >>> 24); b[p + 1] = (byte) (v >>> 16); b[p + 2] = (byte) (v >>> 8); b[p + 3] = (byte) v; }
    static void putLong(byte[] b, int p, long v) { putInt(b, p, (int) (v >>> 32)); putInt(b, p + 4, (int) v); }
    static int getInt(byte[] b, int p) { return ((b[p] & 0xff) << 24) | ((b[p + 1] & 0xff) << 16) | ((b[p + 2] & 0xff) << 8) | (b[p + 3] & 0xff); }
    static long getLong(byte[] b, int p) { return ((long) getInt(b, p) << 32) | (getInt(b, p + 4) & 0xffffffffL); }
}
