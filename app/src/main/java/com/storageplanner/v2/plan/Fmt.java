package com.storageplanner.v2.plan;

import java.util.Locale;

/** Decimal byte formatting for reason sentences (decimal GB, as the UI and classic show; PHASE_5 5). */
public final class Fmt {
    private Fmt() {}
    public static String bytes(long b) {
        if (b == Long.MAX_VALUE) return "maximum safe recovery";
        if (b < 1000) return b + " B";
        if (b < 1000000) return String.format(Locale.ROOT, "%.0f KB", b / 1e3);
        if (b < 1000000000) return String.format(Locale.ROOT, "%.1f MB", b / 1e6);
        return String.format(Locale.ROOT, "%.2f GB", b / 1e9);
    }
}
