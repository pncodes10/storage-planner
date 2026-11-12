package com.storageplanner.v2test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * REVIEW_S F-06: a store reset discards every table. That loses nothing re-derivable only while no production code
 * writes {@code feedback()}, {@code preferences()} or {@code operations()}. This test fails if any class under v2/,
 * v2adapter/ or app/ (other than v2/store) touches those tables; the phase that first writes one must first define how
 * it survives a reset, and remove this test only then.
 */
public final class ResetSafetyTest {
    static final Pattern USE = Pattern.compile("\\.(feedback|preferences|operations)\\(\\s*\\)");

    static List<String> scan(Path root) throws IOException {
        List<String> out = new ArrayList<String>();
        if (!Files.isDirectory(root)) return out;
        List<Path> files;
        try (Stream<Path> s = Files.walk(root)) { files = s.filter(p -> p.toString().endsWith(".java")).sorted().collect(Collectors.toList()); }
        for (Path f : files) {
            String rel = root.relativize(f).toString().replace('\\', '/');
            if (rel.startsWith("v2/store/")) continue;
            List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                Matcher m = USE.matcher(lines.get(i));
                if (m.find()) out.add(rel + ":" + (i + 1) + " uses " + m.group(1) + "()");
            }
        }
        return out;
    }

    public static int run(String[] args) throws IOException {
        T.section("ResetSafety");
        Path base = Paths.get(System.getProperty("v2.repo", ".")).resolve("app/src/main/java/com/storageplanner");
        T.check("sources exist", Files.isDirectory(base.resolve("v2")));
        List<String> v = new ArrayList<String>();
        for (String d : new String[]{"v2", "v2adapter", "app"}) for (String x : scan(base)) if (x.startsWith(d + "/")) v.add(x);
        for (String s : v) System.out.println("  user-decision table used outside v2.store: " + s);
        T.eq("no production use of feedback/preferences/operations (F-06)", 0, v.size());
        // self-test
        TempTree t = TempTree.create();
        try {
            t.file("v2/x/A.java", "class A { void f(V2Store s) { s.operations().put(null); } }".getBytes(StandardCharsets.UTF_8));
            t.file("v2/store/B.java", "class B { void f(V2Store s) { s.feedback().size(); } }".getBytes(StandardCharsets.UTF_8));
            List<String> p = scan(t.root());
            T.check("planted use detected, v2/store exempt", p.size() == 1 && p.get(0).startsWith("v2/x/A.java"));
        } finally { t.close(); }
        return T.finish("ResetSafetyTest");
    }
}
