package com.storageplanner.v2.model;

/** (low, mid, high); all NaN = unknown; low <= mid <= high (ARCHITECTURE 2.9). */
public final class Interval {
    public final double low, mid, high;
    private Interval(double l, double m, double h) { low = l; mid = m; high = h; }
    public static final Interval UNKNOWN = new Interval(Double.NaN, Double.NaN, Double.NaN);
    /** Sorts the three values; NaN may not be mixed with numbers. */
    public static Interval of(double a, double b, double c) {
        int nan = (Double.isNaN(a) ? 1 : 0) + (Double.isNaN(b) ? 1 : 0) + (Double.isNaN(c) ? 1 : 0);
        if (nan == 3) return UNKNOWN;
        if (nan != 0) throw new IllegalArgumentException("NaN mixed with numbers");
        double lo = Math.min(a, Math.min(b, c)), hi = Math.max(a, Math.max(b, c)), mi = a + b + c - lo - hi;
        return new Interval(lo, mi, hi);
    }
    public static Interval point(double v) { return of(v, v, v); }
    public boolean unknown() { return Double.isNaN(mid); }
    /** Component-wise max (raise, never lower). */
    public Interval max(Interval o) { return unknown() ? o : o.unknown() ? this : of(Math.max(low, o.low), Math.max(mid, o.mid), Math.max(high, o.high)); }
    public Interval clamp01() { return of(c(low), c(mid), c(high)); }
    static double c(double v) { return v < 0 ? 0 : v > 1 ? 1 : v; }
    @Override public String toString() { return "(" + low + ", " + mid + ", " + high + ")"; }
}
