package com.storageplanner.v2.features;

import com.storageplanner.v2.model.Unknown;

/** Calendar arithmetic without time-zone APIs (local times are stored as UTC). */
public final class Dates {
    private Dates() {}
    public static final long Y2000 = 946684800000L;
    public static final long DAY = 86400000L;

    static boolean leap(int y) { return (y % 4 == 0 && y % 100 != 0) || y % 400 == 0; }
    static int days(int y, int m) { return m == 2 ? (leap(y) ? 29 : 28) : (m == 4 || m == 6 || m == 9 || m == 11) ? 30 : 31; }

    /** Epoch ms of the given civil date-time read as UTC; Unknown.TIME when any field is invalid. */
    public static long utc(int y, int mo, int d, int h, int mi, int s, int ms) {
        if (y < 1 || mo < 1 || mo > 12 || d < 1 || d > days(y, mo) || h < 0 || h > 23 || mi < 0 || mi > 59 || s < 0 || s > 60 || ms < 0 || ms > 999)
            return Unknown.TIME;
        // days from civil (Howard Hinnant)
        int yy = mo <= 2 ? y - 1 : y;
        long era = (yy >= 0 ? yy : yy - 399) / 400;
        long yoe = yy - era * 400;
        long doy = (153L * (mo + (mo > 2 ? -3 : 9)) + 2) / 5 + d - 1;
        long doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
        long day = era * 146097 + doe - 719468;
        return day * DAY + h * 3600000L + mi * 60000L + s * 1000L + ms;
    }

    public static boolean plausible(long t, long now) { return Unknown.knownTime(t) && t >= Y2000 && t <= now + DAY; }

    static int num(String s, int from, int len) {
        int v = 0;
        for (int i = from; i < from + len; i++) { char c = s.charAt(i); if (c < '0' || c > '9') return -1; v = v * 10 + (c - '0'); }
        return v;
    }

    /** "YYYY:MM:DD HH:MM:SS" (EXIF) or with '-' separators; Unknown.TIME if unparseable or all zero. */
    public static long exif(String s) {
        if (s == null || s.length() < 19) return Unknown.TIME;
        int y = num(s, 0, 4), mo = num(s, 5, 2), d = num(s, 8, 2), h = num(s, 11, 2), mi = num(s, 14, 2), se = num(s, 17, 2);
        if (y <= 0) return Unknown.TIME;
        return utc(y, mo, d, h, mi, se, 0);
    }

    /** "+05:30" / "-0800" → offset minutes; Integer.MIN_VALUE if unparseable. */
    public static int offsetMinutes(String s) {
        if (s == null) return Integer.MIN_VALUE;
        s = s.trim();
        if (s.length() < 5 || (s.charAt(0) != '+' && s.charAt(0) != '-')) return Integer.MIN_VALUE;
        int h = num(s, 1, 2), m = s.charAt(3) == ':' ? (s.length() >= 6 ? num(s, 4, 2) : -1) : num(s, 3, 2);
        if (h < 0 || m < 0 || h > 14 || m > 59) return Integer.MIN_VALUE;
        return (s.charAt(0) == '-' ? -1 : 1) * (h * 60 + m);
    }
}
