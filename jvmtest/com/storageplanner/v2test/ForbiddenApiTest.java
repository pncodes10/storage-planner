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
        // java.lang name clashes (CI APK javac vs android-34.jar, where java.lang.Record exists)
        List<String> c = langClashes(src);
        for (String x : c) System.out.println("  violation: " + x);
        T.eq("no ambiguous java.lang name in v2 (single-type import required)", 0, c.size());
        TempTree t2 = TempTree.create();
        try {
            t2.file("a/m/Record.java", "package a.m;\npublic interface Record { }\n".getBytes(StandardCharsets.UTF_8));
            t2.file("a/s/Bad.java", "package a.s;\nimport a.m.*;\nclass Bad<R extends Record> { }\n".getBytes(StandardCharsets.UTF_8));
            t2.file("a/s/Good.java", "package a.s;\nimport a.m.*;\nimport a.m.Record;\nclass Good<R extends Record> { }\n".getBytes(StandardCharsets.UTF_8));
            t2.file("a/m/Same.java", "package a.m;\nclass Same<R extends Record> { }\n".getBytes(StandardCharsets.UTF_8));
            List<String> pc = langClashes(t2.root());
            T.eq("planted java.lang clash detected (only Bad.java)", 1, pc.size());
            T.check("clash report names Bad.java", pc.size() == 1 && pc.get(0).contains("Bad.java"));
        } finally { t2.close(); }
        return T.finish("ForbiddenApiTest");
    }

    /** java.lang types added after Java 8 that android-34.jar (libcore ~ OpenJDK 17) or a newer JDK may have. A v2 type
     *  with one of these simple names, reached through an on-demand (wildcard) import, is ambiguous there even though it
     *  compiles with --release 8. Same-package use is fine (package scope shadows java.lang). */
    static final String[] NEWER_JAVA_LANG = {"Record", "Module", "ModuleLayer", "ProcessHandle", "StackWalker", "ScopedValue",
            "MatchException", "WrongThreadException", "StringTemplate"};

    static List<String> langClashes(Path dir) throws IOException {
        List<String> out = new ArrayList<String>();
        List<Path> files;
        try (Stream<Path> s = Files.walk(dir)) { files = s.filter(p -> p.toString().endsWith(".java")).sorted().collect(Collectors.toList()); }
        List<String[]> clashing = new ArrayList<String[]>();        // {package, simpleName}
        for (Path f : files) {
            String n = f.getFileName().toString().replace(".java", "");
            for (String l : NEWER_JAVA_LANG) if (l.equals(n)) clashing.add(new String[]{pkg(f), n});
        }
        for (Path f : files) {
            String text = new String(Files.readAllBytes(f), StandardCharsets.UTF_8);
            String pf = pkg(f);
            for (String[] c : clashing) {
                if (c[0].equals(pf)) continue;
                String q = Pattern.quote(c[0]);
                if (!Pattern.compile("(?m)^\\s*import\\s+" + q + "\\.\\*\\s*;").matcher(text).find()) continue;
                if (Pattern.compile("(?m)^\\s*import\\s+" + q + "\\." + c[1] + "\\s*;").matcher(text).find()) continue;
                String body = text.replaceAll("(?m)^\\s*(import|package)\\s.*$", "");
                if (Pattern.compile("\\b" + c[1] + "\\b").matcher(body).find())
                    out.add(dir.relativize(f) + " uses " + c[1] + " via import " + c[0] + ".* (ambiguous with java.lang." + c[1] + ")");
            }
        }
        return out;
    }

    private static String pkg(Path f) throws IOException {
        java.util.regex.Matcher m = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;")
                .matcher(new String(Files.readAllBytes(f), StandardCharsets.UTF_8));
        return m.find() ? m.group(1) : "";
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
