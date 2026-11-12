package com.storageplanner.v2.discovery;

import java.text.Normalizer;

/** Pure path functions on normalized relative paths ('/' separators, NFC, "" = volume root). */
public final class PathNormalizer {
    private PathNormalizer() {}

    /** NFC; IllegalArgumentException for "", ".", "..", or a name containing '/' or NUL. */
    public static String normalizeName(String raw) {
        if (raw == null || raw.isEmpty() || raw.equals(".") || raw.equals("..")) throw new IllegalArgumentException("invalid name: " + raw);
        if (raw.indexOf('/') >= 0 || raw.indexOf('\u0000') >= 0) throw new IllegalArgumentException("invalid name");
        return Normalizer.normalize(raw, Normalizer.Form.NFC);
    }

    /** Split on '/', drop empty parts, normalizeName each, join. "" stays "". */
    public static String normalizeRelative(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        StringBuilder b = new StringBuilder();
        for (String part : raw.split("/", -1)) {
            if (part.isEmpty()) continue;
            if (b.length() > 0) b.append('/');
            b.append(normalizeName(part));
        }
        return b.toString();
    }

    public static String join(String dir, String name) { return dir.isEmpty() ? name : dir + "/" + name; }

    /** "a/b" → "a"; "a" → ""; "" → null. */
    public static String parent(String path) {
        if (path.isEmpty()) return null;
        int i = path.lastIndexOf('/');
        return i < 0 ? "" : path.substring(0, i);
    }

    public static String name(String path) { int i = path.lastIndexOf('/'); return i < 0 ? path : path.substring(i + 1); }

    public static int depth(String path) {
        if (path.isEmpty()) return 0;
        int d = 1;
        for (int i = 0; i < path.length(); i++) if (path.charAt(i) == '/') d++;
        return d;
    }

    /** prefix "" → true; else equals(prefix) or startsWith(prefix + "/"). */
    public static boolean isUnder(String path, String prefix) {
        if (prefix.isEmpty()) return true;
        return path.equals(prefix) || (path.length() > prefix.length() && path.startsWith(prefix) && path.charAt(prefix.length()) == '/');
    }
}
