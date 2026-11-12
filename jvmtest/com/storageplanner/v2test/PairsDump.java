package com.storageplanner.v2test;

import com.storageplanner.v2.graph.GraphStage;
import com.storageplanner.v2.model.*;
import com.storageplanner.v2.store.V2Store;
import com.storageplanner.v2.util.Json;
import com.storageplanner.v2.util.JsonWriter;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

/** V2Cli --pairs-out (PHASE_3 11.1): stage-4 metrics for generated, labelled, hard-negative and random pairs, one JSON line each. */
final class PairsDump {
    private PairsDump() {}

    @SuppressWarnings("unchecked")
    static void write(V2Store store, GraphStage gs, String manifest, int random, String out, PrintStream err) throws IOException {
        Map<String, FileRecord> byPath = new HashMap<String, FileRecord>();
        Map<String, FileRecord> byId = new HashMap<String, FileRecord>();
        for (FileRecord f : store.files().all()) if (f.presence == Presence.PRESENT) { byPath.put(com.storageplanner.v2.util.Text.nfc(f.path), f); byId.put(f.fileId, f); }
        TreeMap<String, String[]> pairs = new TreeMap<String, String[]>();   // a\0b -> {a, b, label, hn, root}
        if (manifest != null) {
            Map<String, Object> m;
            try { m = Json.parseObject(new String(Files.readAllBytes(Paths.get(manifest)), StandardCharsets.UTF_8)); }
            catch (Exception e) { throw new IOException("manifest: " + e.getMessage()); }
            Map<String, List<String>> hn = new TreeMap<String, List<String>>();
            for (Object o : Json.list(m, "files")) {
                Map<String, Object> e = (Map<String, Object>) o;
                FileRecord f = byPath.get(com.storageplanner.v2.util.Text.nfc(String.valueOf(e.get("path"))));
                if (f == null) continue;
                String g = Json.str(e, "hard_negative_group");
                if (g != null) { List<String> l = hn.get(g); if (l == null) hn.put(g, l = new ArrayList<String>()); l.add(f.fileId); }
                List<Object> rels = Json.list(e, "relationships");
                if (rels != null) for (Object r : rels) {
                    Map<String, Object> rr = (Map<String, Object>) r;
                    FileRecord t = byPath.get(com.storageplanner.v2.util.Text.nfc(String.valueOf(rr.get("target"))));
                    if (t != null) add(pairs, f.fileId, t.fileId, Json.str(rr, "type"), null, Json.str(rr, "target"));
                }
            }
            for (Map.Entry<String, List<String>> g : hn.entrySet()) {
                List<String> l = g.getValue();
                for (int i = 0; i < l.size(); i++) for (int j = i + 1; j < l.size(); j++) add(pairs, l.get(i), l.get(j), null, g.getKey(), g.getKey());
            }
        }
        for (PairCheck pc : store.pairChecks().all()) add(pairs, pc.a, pc.b, null, null, null);
        List<FileRecord> imgs = new ArrayList<FileRecord>();
        for (FileRecord f : byId.values()) { FeatureRecord fr = store.features().get(f.fileId); if (f.hasPhash && fr != null && fr.image != null && fr.image.aspect > 0) imgs.add(f); }
        Collections.sort(imgs, new Comparator<FileRecord>() { public int compare(FileRecord x, FileRecord y) { return x.fileId.compareTo(y.fileId); } });
        Random r = new Random(7);
        for (int k = 0, tries = 0; k < random && imgs.size() > 1 && tries < random * 50; tries++) {
            FileRecord x = imgs.get(r.nextInt(imgs.size())), y = imgs.get(r.nextInt(imgs.size()));
            if (x == y) continue;
            double ar = store.features().get(x.fileId).image.aspect / store.features().get(y.fileId).image.aspect;
            if (Math.abs(ar - 1) > 0.02) continue;
            String key = x.fileId.compareTo(y.fileId) < 0 ? x.fileId + "\u0000" + y.fileId : y.fileId + "\u0000" + x.fileId;
            if (pairs.containsKey(key)) continue;
            add(pairs, x.fileId, y.fileId, "RANDOM", null, null); k++;
        }
        List<String[]> probe = new ArrayList<String[]>();
        List<String[]> meta = new ArrayList<String[]>(pairs.values());
        Collections.sort(meta, new Comparator<String[]>() { public int compare(String[] x, String[] y) { int c = x[0].compareTo(y[0]); return c != 0 ? c : x[1].compareTo(y[1]); } });
        for (String[] p : meta) probe.add(new String[]{p[0], p[1]});
        long t0 = System.nanoTime();
        List<Object[]> res = gs.probe(probe);
        Map<String, Object[]> rb = new HashMap<String, Object[]>();
        for (Object[] o : res) { PairCheck pc = (PairCheck) o[0]; rb.put(pc.a + "\u0000" + pc.b, o); }
        int n = 0;
        try (Writer w = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(out), StandardCharsets.UTF_8))) {
            for (String[] p : meta) {
                Object[] o = rb.get(p[0] + "\u0000" + p[1]);
                if (o == null) continue;
                PairCheck pc = (PairCheck) o[0];
                FileRecord a = byId.get(pc.a), b = byId.get(pc.b);
                FeatureRecord fa = store.features().get(a.fileId), fb = store.features().get(b.fileId);
                JsonWriter j = new JsonWriter(w);
                j.beginObject().name("a").value(a.path).name("b").value(b.path).name("label").value(p[2]).name("hnGroup").value(p[3])
                 .name("root").value(p[4]).name("sources").value(pc.sources).name("generated").value((Boolean) o[1]).name("hamming").value(pc.hamming)
                 .name("aspectDiff").value(Math.abs(fa.image.aspect / fb.image.aspect - 1)).name("verdict").value(pc.verdict).name("note").value(pc.note)
                 .name("metrics"); com.storageplanner.v2.store.GraphCodecs.writeMetrics(j, pc.metrics);
                j.name("transform").value(pc.transform).name("areaA").value(a.area).name("areaB").value(b.area).name("thumbA").value(thumb(a)).name("thumbB").value(thumb(b))
                 .name("pixelsA").value(px(fa)).name("pixelsB").value(px(fb)).endObject();
                w.write('\n'); n++;
            }
        }
        err.println("pairs: " + n + " lines, " + (System.nanoTime() - t0) / 1000000L + " ms");
    }
    static long px(FeatureRecord f) { return f.image == null || f.image.uprightWidth < 0 ? -1 : (long) f.image.uprightWidth * f.image.uprightHeight; }
    static boolean thumb(FileRecord f) {
        if ("thumbnails".equals(f.area)) return true;
        for (String c : f.path.toLowerCase(Locale.ROOT).split("/")) if (c.equals(".thumbnails") || c.equals(".thumbs") || c.equals("cache")) return true;
        return false;
    }
    static void add(TreeMap<String, String[]> m, String a, String b, String label, String hn, String root) {
        if (a.equals(b)) return;
        String x = a.compareTo(b) < 0 ? a : b, y = x == a ? b : a;
        String k = x + "\u0000" + y;
        String[] o = m.get(k);
        if (o == null) m.put(k, new String[]{x, y, label, hn, root});
        else { if (o[2] == null || "RANDOM".equals(o[2])) o[2] = label; if (o[3] == null) o[3] = hn; if (o[4] == null) o[4] = root; }
    }
}
