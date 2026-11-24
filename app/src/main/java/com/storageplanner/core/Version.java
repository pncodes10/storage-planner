package com.storageplanner.core;

/** Compares release versions such as "1.8.1", "v1.9.0" or a build's "1.8.1-4e84791". Pure Java. */
public final class Version {
    private Version() {}

    /** The leading numbers of a version ("v1.8.1-abc" gives 1, 8, 1), or null when there are none. */
    public static int[] parse(String s) {
        if (s == null) return null;
        s = s.trim();
        if (s.startsWith("v") || s.startsWith("V")) s = s.substring(1);
        int end = 0;
        while (end < s.length() && (Character.isDigit(s.charAt(end)) || s.charAt(end) == '.')) end++;
        String[] parts = s.substring(0, end).split("\\.");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].isEmpty() || parts[i].length() > 6) return null;
            out[i] = Integer.parseInt(parts[i]);
        }
        return out;
    }

    /** True when a release tag is a newer version than the installed one. Anything unreadable is not newer. */
    public static boolean isNewer(String candidate, String installed) {
        int[] a = parse(candidate), b = parse(installed);
        if (a == null || b == null) return false;
        // a suffix such as "-beta" marks a pre-release: never offered as an update
        String c = candidate.trim();
        if (c.matches("(?i)v?[0-9.]+[^0-9.].*")) return false;
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? a[i] : 0, y = i < b.length ? b[i] : 0;
            if (x != y) return x > y;
        }
        return false;
    }
}
