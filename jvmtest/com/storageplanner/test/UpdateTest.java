package com.storageplanner.test;

import com.storageplanner.core.Version;

/** Desktop tests for the update check's version comparison. */
public final class UpdateTest {
    static int failed = 0, passed = 0;

    static void check(String what, boolean ok) {
        if (ok) passed++; else { failed++; System.out.println("FAIL " + what); }
    }

    public static void main(String[] a) {
        check("patch is newer", Version.isNewer("v1.8.2", "1.8.1"));
        check("minor is newer", Version.isNewer("v1.9.0", "1.8.1"));
        check("major is newer", Version.isNewer("2.0.0", "1.99.99"));
        check("10 is newer than 9 (numbers, not text)", Version.isNewer("v1.10.0", "1.9.0"));
        check("same is not newer", !Version.isNewer("v1.8.1", "1.8.1"));
        check("older is not newer", !Version.isNewer("v1.8.0", "1.8.1"));
        check("missing parts are zero", !Version.isNewer("v1.8", "1.8.0") && Version.isNewer("v1.8.1", "1.8"));
        check("a CI build of the same version is not older", !Version.isNewer("v1.8.1", "1.8.1-4e84791"));
        check("a CI build of an older version is updated", Version.isNewer("v1.9.0", "1.8.1-4e84791"));
        check("pre-release tags are never offered", !Version.isNewer("v2.0.0-beta1", "1.8.1"));
        check("garbage is not newer", !Version.isNewer("latest", "1.8.1") && !Version.isNewer(null, "1.8.1")
                && !Version.isNewer("v1.9.0", "dev") && !Version.isNewer("v1..0", "1.8.1") && !Version.isNewer("", "1.0"));
        check("huge numbers are refused, not crashed on", !Version.isNewer("v99999999999.0", "1.0"));
        System.out.println("UpdateTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
