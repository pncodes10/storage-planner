package com.storageplanner.v2.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.zip.CRC32;

/** The only digest helpers of v2. */
public final class Hashing {
    private Hashing() {}
    private static final ThreadLocal<MessageDigest> SHA = new ThreadLocal<MessageDigest>() {
        @Override protected MessageDigest initialValue() {
            try { return MessageDigest.getInstance("SHA-256"); } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        }
    };
    /** A per-thread SHA-256 instance, reset. */
    public static MessageDigest sha256() { MessageDigest d = SHA.get(); d.reset(); return d; }
    public static byte[] sha256(byte[] data) { return sha256().digest(data); }
    public static String sha256Hex(byte[] data) { return Hex.of(sha256(data)); }
    public static String sha256Hex(String utf8) { return sha256Hex(utf8.getBytes(StandardCharsets.UTF_8)); }
    public static long crc32(byte[] data, int off, int len) { CRC32 c = new CRC32(); c.update(data, off, len); return c.getValue(); }
}
