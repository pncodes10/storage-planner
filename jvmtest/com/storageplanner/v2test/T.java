package com.storageplanner.v2test;

import java.util.Objects;

/** Minimal assertion helper: counts passes and failures of the running suite. */
public final class T {
    private static int pass, fail;
    private static String section = "";
    private T() {}

    public static void section(String s) { section = s; }
    public static boolean check(String name, boolean ok) {
        if (ok) pass++;
        else { fail++; System.out.println("  FAIL [" + section + "] " + name); }
        return ok;
    }
    public static boolean eq(String name, Object expected, Object actual) {
        boolean ok = Objects.equals(expected, actual)
                || (expected instanceof Number && actual instanceof Number && ((Number) expected).longValue() == ((Number) actual).longValue()
                    && !(expected instanceof Double) && !(actual instanceof Double));
        if (!ok) { fail++; System.out.println("  FAIL [" + section + "] " + name + ": expected <" + expected + "> but was <" + actual + ">"); }
        else pass++;
        return ok;
    }
    public static int failures() { return fail; }
    public static int passes() { return pass; }
    /** Prints "<suite>: P pass, F fail", resets the counters and returns F. */
    public static int finish(String suite) {
        System.out.println(suite + ": " + pass + " pass, " + fail + " fail");
        int f = fail;
        pass = 0; fail = 0; section = "";
        return f;
    }
}
