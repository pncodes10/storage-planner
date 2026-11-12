package com.storageplanner.v2test;

import com.storageplanner.v2.discovery.PathNormalizer;

import java.nio.charset.StandardCharsets;

public final class PathNormalizerTest {
    static boolean rejects(String name) { try { PathNormalizer.normalizeName(name); return false; } catch (IllegalArgumentException e) { return true; } }
    static boolean rejectsRel(String p) { try { PathNormalizer.normalizeRelative(p); return false; } catch (IllegalArgumentException e) { return true; } }

    public static int run(String[] args) {
        T.section("PathNormalizer");
        T.eq("NFD e-acute → NFC", "é", PathNormalizer.normalizeName("é"));
        T.check("empty rejected", rejects(""));
        T.check(". rejected", rejects("."));
        T.check(".. rejected", rejects(".."));
        T.check("a/b as a name rejected", rejects("a/b"));
        T.check("NUL rejected", rejects("a\u0000b"));
        T.eq("normalizeRelative a//b/", "a/b", PathNormalizer.normalizeRelative("a//b/"));
        T.eq("normalizeRelative empty", "", PathNormalizer.normalizeRelative(""));
        T.check("a/../b rejected", rejectsRel("a/../b"));
        T.eq("join root", "x", PathNormalizer.join("", "x"));
        T.eq("join", "a/x", PathNormalizer.join("a", "x"));
        T.eq("parent a/b", "a", PathNormalizer.parent("a/b"));
        T.eq("parent a", "", PathNormalizer.parent("a"));
        T.eq("parent root", null, PathNormalizer.parent(""));
        T.eq("name", "c.jpg", PathNormalizer.name("a/b/c.jpg"));
        T.eq("name root", "", PathNormalizer.name(""));
        T.eq("depth root", 0, PathNormalizer.depth(""));
        T.eq("depth a", 1, PathNormalizer.depth("a"));
        T.eq("depth a/b", 2, PathNormalizer.depth("a/b"));
        T.check("isUnder(ab,a) false", !PathNormalizer.isUnder("ab", "a"));
        T.check("isUnder(a/b,a) true", PathNormalizer.isUnder("a/b", "a"));
        T.check("isUnder(a,a) true", PathNormalizer.isUnder("a", "a"));
        T.check("isUnder(x,\"\") true", PathNormalizer.isUnder("x/y", ""));
        String[] kept = {"back\\slash", " lead", "trail ", "emoji 😀", "हिन्दी", "العربية"};
        for (String k : kept) T.eq("preserved " + k, java.text.Normalizer.normalize(k, java.text.Normalizer.Form.NFC), PathNormalizer.normalizeName(k));
        T.eq("relative keeps spaces", " a/b ", PathNormalizer.normalizeRelative(" a/b "));
        StringBuilder b = new StringBuilder(); for (int i = 0; i < 127; i++) b.append('é'); b.append('x');
        String n255 = b.toString();
        T.eq("255-byte name length", 255, n255.getBytes(StandardCharsets.UTF_8).length);
        T.eq("255-byte name accepted", n255, PathNormalizer.normalizeName(n255));
        return T.finish("PathNormalizerTest");
    }
}
