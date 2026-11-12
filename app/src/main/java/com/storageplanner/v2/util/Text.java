package com.storageplanner.v2.util;

import java.text.Normalizer;
import java.util.Locale;

public final class Text {
    private Text() {}
    public static String nfc(String s) { return Normalizer.normalize(s, Normalizer.Form.NFC); }
    public static boolean isNfc(String s) { return Normalizer.isNormalized(s, Normalizer.Form.NFC); }
    /** Lower-case text after the last '.'; "" for no dot or a leading-dot-only name (".nomedia"). */
    public static String extension(String name) {
        int i = name.lastIndexOf('.');
        if (i <= 0 || i == name.length() - 1) return "";
        return name.substring(i + 1).toLowerCase(Locale.ROOT);
    }
    /** At most max chars, never splitting a surrogate pair. */
    public static String truncate(String s, int max) {
        if (s == null || s.length() <= max) return s;
        int end = max;
        if (end > 0 && Character.isHighSurrogate(s.charAt(end - 1))) end--;
        return s.substring(0, end);
    }
    /** Escapes non-printable and non-ASCII characters as backslash-u escapes (for event details about undecodable names). */
    public static String escape(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x20 && c < 0x7f && c != '\\') b.append(c);
            else b.append(String.format("\\u%04x", (int) c));
        }
        return b.toString();
    }
}
