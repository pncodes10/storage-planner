package com.storageplanner.v2test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Greps the v2 sources for APIs the APK audit and the architecture forbid. */
public final class ForbiddenApiTest {
    static final String[][] RULES = {
        {"import android", "import\\s+android\\."},
        {"java.awt", "java\\.awt"},
        {"javax.imageio", "javax\\.imageio"},
        {"java.net", "java\\.net\\."},
        {"java.lang.reflect", "java\\.lang\\.reflect"},
        {"Class.forName", "Class\\.forName"},
        {"ClassLoader", "ClassLoader"},
        {"ProcessBuilder", "ProcessBuilder"},
        {"Runtime.exec", "Runtime\\.getRuntime\\(\\)\\s*\\.\\s*exec"},
        {"System.load", "System\\.load"},
        {"File.delete", "\\.delete\\(\\s*\\)"},
        {"renameTo", "\\.renameTo\\("},
        {"Files.move", "Files\\.move"},
        {"Files.delete", "Files\\.delete"},
        {"deleteOnExit", "deleteOnExit"},
        {"currentTimeMillis", "System\\.currentTimeMillis"},
        {"nanoTime", "System\\.nanoTime"},
        {"new Date()", "new\\s+Date\\(\\s*\\)"},
        {"catch Throwable/Error", "catch\\s*\\(\\s*(final\\s+)?(java\\.lang\\.)?(Throwable|Error|OutOfMemoryError)\\b"},
        {"classic core import", "import\\s+com\\.storageplanner\\.core\\.(Scanner|UnitBuilder|Planner|Review|Unit|Option|FileItem|ResultStore|Sorter|\\*)\\s*;"},
    };

    public static int run(String[] args) throws IOException {
        T.section("ForbiddenApi");
        Path src = Paths.get(System.getProperty("v2.repo", ".")).resolve("app/src/main/java/com/storageplanner/v2");
        T.check("v2 sources exist", Files.isDirectory(src));
        List<String> v = scan(src);
        for (String s : v) System.out.println("  violation: " + s);
        T.eq("no forbidden API in v2", 0, v.size());
        // self-test: a planted violation in a temp copy is detected
        TempTree t = TempTree.create();
        try {
            t.file("x/Planted.java", ("package x;\nclass Planted { void f(java.io.File f) { f.delete(); try { } catch (Throwable e) { } long n = System.nanoTime(); } }\n")
                    .getBytes(StandardCharsets.UTF_8));
            t.file("x/Fine.java", "package x;\nclass Fine { void f(StringBuilder b) { b.delete(0, 1); } }\n".getBytes(StandardCharsets.UTF_8));
            List<String> p = scan(t.root());
            T.eq("planted violations detected", 3, p.size());
            boolean fineClean = true;
            for (String s : p) if (s.contains("Fine.java")) fineClean = false;
            T.check("StringBuilder.delete(int,int) allowed", fineClean);
        } finally { t.close(); }
        return T.finish("ForbiddenApiTest");
    }

    static List<String> scan(Path dir) throws IOException {
        List<String> out = new ArrayList<String>();
        List<Path> files;
        try (Stream<Path> s = Files.walk(dir)) { files = s.filter(p -> p.toString().endsWith(".java")).sorted().collect(Collectors.toList()); }
        for (Path f : files) {
            List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++)
                for (String[] r : RULES)
                    if (Pattern.compile(r[1]).matcher(lines.get(i)).find()) out.add(dir.relativize(f) + ":" + (i + 1) + " " + r[0]);
        }
        return out;
    }
}
