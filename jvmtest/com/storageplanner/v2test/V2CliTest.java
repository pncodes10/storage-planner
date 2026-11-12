package com.storageplanner.v2test;

import com.storageplanner.v2.util.Json;
import com.storageplanner.v2.util.JsonWriter;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

public final class V2CliTest {
    static final List<String> TOP = Arrays.asList("schema", "schemaVersion", "algorithmVersion", "scanVersion", "phase", "generatedAt",
            "root", "volumeId", "options", "scan", "volumes", "files", "relationships", "duplicateGroups", "recommendations", "plan",
            "coverage", "execution", "timings");

    static int cli(String... args) {
        ByteArrayOutputStream o = new ByteArrayOutputStream(), e = new ByteArrayOutputStream();
        int rc = V2Cli.run(args, new PrintStream(o), new PrintStream(e));
        if (rc != 0) System.out.println("  cli stderr: " + new String(e.toByteArray(), StandardCharsets.UTF_8));
        return rc;
    }

    static Map<String, Object> read(java.nio.file.Path p) throws Exception {
        return Json.parseObject(new String(Files.readAllBytes(p), StandardCharsets.UTF_8));
    }

    static String withoutTimings(Map<String, Object> m) {
        Map<String, Object> c = new LinkedHashMap<String, Object>(m);
        c.remove("timings");
        StringWriter w = new StringWriter(); new JsonWriter(w).any(c); return w.toString();
    }

    @SuppressWarnings("unchecked")
    public static int run(String[] args) throws Exception {
        T.section("V2Cli");
        TempTree data = TempTree.create(), out = TempTree.create();
        try {
            data.file("DCIM/Camera/IMG_1.jpg", 100, 1); data.file("Download/é doc.pdf", 50, 2); data.file("Android/data/x/y.bin", 5, 3);
            data.file("manifest.json", "{}".getBytes(StandardCharsets.UTF_8));
            java.nio.file.Path f1 = out.path("r1.json"), f2 = out.path("r2.json");
            T.eq("exit 0", 0, cli("scan", data.root().toString(), "--out", f1.toString(), "--now", "1792000000000", "--exclude", "manifest.json"));
            Map<String, Object> j = read(f1);
            T.eq("top-level keys in order", TOP, new ArrayList<String>(j.keySet()));
            T.eq("schema", "sp-v2-result", j.get("schema"));
            T.eq("relationships empty", new ArrayList<Object>(), j.get("relationships"));
            T.eq("duplicateGroups empty", new ArrayList<Object>(), j.get("duplicateGroups"));
            T.eq("recommendations empty", new ArrayList<Object>(), j.get("recommendations"));
            T.check("plan null", j.containsKey("plan") && j.get("plan") == null);
            T.check("execution null", j.containsKey("execution") && j.get("execution") == null);
            List<Object> files = (List<Object>) j.get("files");
            T.eq("two files (restricted and excluded skipped)", 2, files.size());
            Map<String, Object> f0 = (Map<String, Object>) files.get(0);
            T.eq("sorted by path", "DCIM/Camera/IMG_1.jpg", f0.get("path"));
            T.eq("area", "camera", f0.get("area"));
            T.check("coverage extraction null", ((Map<String, Object>) j.get("coverage")).containsKey("extraction"));
            T.eq("exit 0 (second run)", 0, cli(data.root().toString(), f2.toString(), "--now", "1792000000000", "--exclude", "manifest.json"));
            T.eq("two runs identical without timings", withoutTimings(j), withoutTimings(read(f2)));
            // persistent state + incremental
            String state = out.path("state").toString();
            T.eq("state run 1", 0, cli("scan", data.root().toString(), "--out", f1.toString(), "--state-dir", state, "--now", "1792000000000"));
            data.file("DCIM/Camera/IMG_2.jpg", 120, 9);
            data.delete("Download");
            T.eq("state run 2", 0, cli("scan", data.root().toString(), "--out", f2.toString(), "--state-dir", state, "--kind", "incremental", "--now", "1792000100000"));
            Map<String, Object> j2 = read(f2);
            Map<String, String> change = new TreeMap<String, String>();
            for (Object o : (List<Object>) j2.get("files")) { Map<String, Object> m = (Map<String, Object>) o; change.put((String) m.get("path"), (String) m.get("change")); }
            T.eq("incremental: added", "ADDED", change.get("DCIM/Camera/IMG_2.jpg"));
            T.eq("incremental: removed", "REMOVED", change.get("Download/é doc.pdf"));
            T.eq("incremental: unchanged", null, change.get("DCIM/Camera/IMG_1.jpg"));
            T.eq("scanId 2", 2L, ((Map<String, Object>) j2.get("scan")).get("scanId"));
            T.eq("options.kind", "incremental", ((Map<String, Object>) j2.get("options")).get("kind"));
            T.eq("usage error", 2, V2Cli.run(new String[]{"scan"}, new PrintStream(new ByteArrayOutputStream()), new PrintStream(new ByteArrayOutputStream())));
        } finally { data.close(); out.close(); }
        return T.finish("V2CliTest");
    }
}
