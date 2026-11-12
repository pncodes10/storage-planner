package com.storageplanner.v2test;

import com.storageplanner.v2.device.BlobStore;
import com.storageplanner.v2.model.FileRecord;
import com.storageplanner.v2.store.DiskStore;
import com.storageplanner.v2.store.StoreCorruption;
import com.storageplanner.v2.store.StoreException;
import com.storageplanner.v2.store.V2Store;
import com.storageplanner.v2.store.disk.TableIds;
import com.storageplanner.v2.util.Json;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * PHASE_S_STORE S3, corruption on the pipeline (3.11, F-05, F-07), on the DiskKillTest tree with the CLI in process:
 * <ul>
 *   <li>a flipped byte in a features / pair-checks frame (store reopened from its index, so the damage is met on
 *   read) ⇒ the next run re-extracts, equals MemoryStore's second run, and the session has {@code D_STORE_DAMAGED};</li>
 *   <li>a flipped byte in a files / hashes / relationships frame (hashes moved to fail-closed, see F-07) ⇒ the run fails with StoreCorruption, the store refuses to open,
 *   the next run starts fresh ({@code D_STORE_RESET}) and equals the clean run; with the CLI's automatic retry the same
 *   happens inside one invocation;</li>
 *   <li>a damaged or truncated index file ⇒ rebuilt, result identical; garbage after durable ⇒ ignored; a damaged manifest
 *   ⇒ fresh store;</li>
 *   <li>F-07: features damaged after T3 and after GRAPH, in the same run ⇒ the run completes, its removal recommendations
 *   and plan items are a subset of the clean run's, no file gets NO_VERIFIED_BACKUP or perceptual FALSE that the clean run
 *   did not give it, the damage is counted, and the next run equals a clean one. A hash damaged after T3 broke that rule
 *   (an exact copy lost its edge and became NO_VERIFIED_BACKUP), so hashes fail closed: the same injection now ends in the
 *   automatic retry on a fresh store, whose result equals clean.</li>
 * </ul>
 */
public final class DiskCorruptionTest {
    static final FixedClock CLOCK = new FixedClock(1792000000000L);

    static final class Res { int rc; String err; String json; }

    static Res cli(Path tree, Path out, Path state, String store, BlobStore hook) throws Exception {
        List<String> a = new ArrayList<String>(Arrays.asList("scan", tree.toString(), "--out", out.toString(), "--now", DiskKillTest.NOW,
                "--target-bytes", "max", "--snapshot-every-ms", "0", "--store", store, "--state-dir", state.toString()));
        final BlobStore hs = hook;
        V2Cli.blobsHook = hs == null ? null : new java.util.function.Function<BlobStore, BlobStore>() { public BlobStore apply(BlobStore b) { return hs; } };
        ByteArrayOutputStream eb = new ByteArrayOutputStream();
        PrintStream sink = new PrintStream(new ByteArrayOutputStream(), true, "UTF-8");
        PrintStream err = new PrintStream(eb, true, "UTF-8");
        Res r = new Res();
        Files.deleteIfExists(out);
        try { r.rc = V2Cli.run(a.toArray(new String[0]), sink, err); }
        finally { V2Cli.blobsHook = null; }
        r.err = new String(eb.toByteArray(), StandardCharsets.UTF_8);
        r.json = Files.exists(out) ? new String(Files.readAllBytes(out), StandardCharsets.UTF_8) : null;
        return r;
    }

    /** Codes of coverage.events, with their details. */
    @SuppressWarnings("unchecked")
    static Map<String, String> events(String json) throws Exception {
        Map<String, String> m = new TreeMap<String, String>();
        Map<String, Object> cov = (Map<String, Object>) ((Map<String, Object>) Json.parse(json)).get("coverage");
        for (Object o : (List<Object>) cov.get("events")) {
            Map<String, Object> e = (Map<String, Object>) o;
            m.put(String.valueOf(e.get("code")), String.valueOf(e.get("detail")));
        }
        return m;
    }

    static com.storageplanner.v2.store.Table<?> table(DiskStore s, int id) { return StoreOracleTest.table(s, id); }

    /** First key of a table in an index-loaded copy, its frame's segment and the byte to flip; the copy is closed (index written). */
    static Object[] target(PowerLossBlobStore b, int id, String wantKey) throws Exception {
        DiskStore d = DiskStore.open(b, 0, CLOCK);
        String key = wantKey;
        if (key == null) for (String k : table(d, id).keys()) { key = k; break; }
        long[] l = d.debugLocator(id, key);
        String seg = d.debugSegmentName(l[0]);
        long pos = (l[0] & 0xFFFFFFFFFFFFL) + l[2] / 2;
        d.close();
        return new Object[]{key, seg, pos};
    }

    static boolean opens(PowerLossBlobStore b) {
        try { DiskStore.open(b, 0, CLOCK); return true; } catch (StoreException e) { return false; }
    }

    static String idxName(PowerLossBlobStore b) { for (String n : b.names()) if (n.endsWith(".idx") && b.bytes(n).length > 0) return n; return null; }

    public static int run(String[] args) throws Exception {
        T.section("DiskCorruption");
        TempTree t = TempTree.create();
        TempTree w = TempTree.create();
        try {
            DiskKillTest.tree(t);
            Path tr = t.root();
            Object clean = DiskKillTest.norm(DiskKillTest.run(tr, w.path("clean.json"), null, "memory", false, null));
            Path ms = w.dir("mstate");
            DiskKillTest.run(tr, w.path("m1.json"), ms, "memory", false, null);
            Object m2 = DiskKillTest.norm(DiskKillTest.run(tr, w.path("m2.json"), ms, "memory", false, null));
            PowerLossBlobStore base = new PowerLossBlobStore();
            Res first = cli(tr, w.path("d1.json"), w.dir("d1"), "disk", base);
            T.check("first disk run equals clean", first.rc == 0 && clean.equals(DiskKillTest.norm(first.json)));

            // ---- derived tables: damaged ⇒ absent, counted, re-extracted; result equals the undamaged second run
            for (int id : new int[]{TableIds.FEATURES, TableIds.PAIR_CHECKS}) {
                String name = TableIds.name(id);
                PowerLossBlobStore c = DiskStoreTest.copy(base);
                Object[] tg = target(c, id, null);
                c.flip((String) tg[1], (Long) tg[2], 0x04);
                Res r = cli(tr, w.path("x.json"), w.dir("x"), "disk", c);
                Map<String, String> ev = r.rc == 0 ? events(r.json) : Collections.<String, String>emptyMap();
                T.check(name + " flip: run completes, equals MemoryStore's second run", r.rc == 0 && m2.equals(DiskKillTest.norm(r.json)));
                T.check(name + " flip: D_STORE_DAMAGED counted (1), no reset", "1".equals(ev.get("D_STORE_DAMAGED")) && !ev.containsKey("D_STORE_RESET"));
                DiskStore after = DiskStore.open(c, 0, CLOCK);
                boolean rewritten = table(after, id).get((String) tg[0]) != null;
                // a pair check whose pair is still a candidate is re-checked; features and hashes are re-extracted
                T.check(name + " flip: the damaged record was re-derived and reads again", rewritten);
            }

            // ---- fail-closed tables: StoreCorruption, store refuses to open, next run starts fresh and equals clean
            for (int id : new int[]{TableIds.FILES, TableIds.HASHES, TableIds.RELATIONSHIPS}) {
                String name = TableIds.name(id);
                PowerLossBlobStore c = DiskStoreTest.copy(base);
                Object[] tg = target(c, id, null);
                c.flip((String) tg[1], (Long) tg[2], 0x04);
                PowerLossBlobStore c2 = DiskStoreTest.copy(c);
                boolean threw = false;
                V2Cli.retryOnCorruption = false;
                try { cli(tr, w.path("y.json"), w.dir("y"), "disk", c); }
                catch (StoreCorruption e) { threw = true; }
                finally { V2Cli.retryOnCorruption = true; }
                T.check(name + " flip: run fails with StoreCorruption", threw);
                T.check(name + " flip: the failed run left a store that refuses to open", !opens(c));
                Res r = cli(tr, w.path("y.json"), w.dir("y"), "disk", c);
                Map<String, String> ev = r.rc == 0 ? events(r.json) : Collections.<String, String>emptyMap();
                T.check(name + " flip: next run starts fresh (D_STORE_RESET) and equals clean",
                        r.rc == 0 && ev.containsKey("D_STORE_RESET") && r.err.contains("store unreadable") && clean.equals(DiskKillTest.norm(r.json)));
                Res r2 = cli(tr, w.path("z.json"), w.dir("z"), "disk", c2);
                Map<String, String> ev2 = r2.rc == 0 ? events(r2.json) : Collections.<String, String>emptyMap();
                T.check(name + " flip: automatic retry in the same run (D_STORE_RESET), equals clean",
                        r2.rc == 0 && ev2.containsKey("D_STORE_RESET") && r2.err.contains("retrying once with a fresh store") && clean.equals(DiskKillTest.norm(r2.json)));
            }

            // ---- index file damaged or cut: rebuilt from the segments, identical
            {
                PowerLossBlobStore c = DiskStoreTest.copy(base);
                DiskStore.open(c, 0, CLOCK).close();          // the CLI never closes the store: write an index file
                String idx = idxName(c);
                T.check("index file written at close", idx != null);
                for (int mode = 0; mode < 2; mode++) {
                    PowerLossBlobStore d = DiskStoreTest.copy(c);
                    byte[] ib = d.bytes(idx);
                    if (mode == 0) { ib[ib.length / 2] ^= 0x10; d.setBytes(idx, ib); } else d.setBytes(idx, Arrays.copyOf(ib, ib.length / 3));
                    DiskStore probe = DiskStore.open(DiskStoreTest.copy(d), 0, CLOCK);
                    T.check((mode == 0 ? "flipped" : "truncated") + " index refused at open (rebuilt by scan)", !probe.loadedFromIndex());
                    Res r = cli(tr, w.path("i.json"), w.dir("i"), "disk", d);
                    Map<String, String> ev = r.rc == 0 ? events(r.json) : Collections.<String, String>emptyMap();
                    T.check((mode == 0 ? "flipped" : "truncated") + " index: result identical to MemoryStore's second run, no reset",
                            r.rc == 0 && m2.equals(DiskKillTest.norm(r.json)) && !ev.containsKey("D_STORE_RESET") && !ev.containsKey("D_STORE_DAMAGED"));
                }
            }

            // ---- garbage after durable: ignored
            {
                PowerLossBlobStore c = DiskStoreTest.copy(base);
                Random rnd = new Random(5);
                for (String n : c.names()) if (n.endsWith(".seg")) {
                    byte[] x = c.bytes(n); byte[] y = Arrays.copyOf(x, x.length + 1000 + rnd.nextInt(3000));
                    for (int i = x.length; i < y.length; i++) y[i] = (byte) rnd.nextInt(256);
                    c.setBytes(n, y);
                }
                Res r = cli(tr, w.path("g.json"), w.dir("g"), "disk", c);
                Map<String, String> ev = r.rc == 0 ? events(r.json) : Collections.<String, String>emptyMap();
                T.check("garbage after durable: ignored, equals MemoryStore's second run", r.rc == 0 && m2.equals(DiskKillTest.norm(r.json)) && !ev.containsKey("D_STORE_RESET"));
            }

            // ---- damaged manifest: fresh store
            {
                PowerLossBlobStore c = DiskStoreTest.copy(base);
                byte[] mb = c.bytes(DiskStore.MANIFEST); mb[mb.length / 2] ^= 0x01; c.setBytes(DiskStore.MANIFEST, mb);
                Res r = cli(tr, w.path("mf.json"), w.dir("mf"), "disk", c);
                Map<String, String> ev = r.rc == 0 ? events(r.json) : Collections.<String, String>emptyMap();
                T.check("damaged manifest: fresh store (D_STORE_RESET), equals clean", r.rc == 0 && ev.containsKey("D_STORE_RESET") && clean.equals(DiskKillTest.norm(r.json)));
            }

            f07(tr, w, clean);
        } finally { t.close(); w.close(); V2Cli.blobsHook = null; V2Cli.afterStage = null; V2Cli.retryOnCorruption = true; }
        return T.finish("DiskCorruptionTest");
    }

    static String fileIdOf(V2Store s, String pathSuffix) {
        for (FileRecord f : s.files().all()) if (f.path.endsWith(pathSuffix)) return f.fileId;
        throw new IllegalStateException("no file " + pathSuffix);
    }

    static void damage(PowerLossBlobStore b, V2Store s, int id, String key) {
        DiskStore d = (DiskStore) s;
        try { d.flush(); } catch (StoreException e) { throw new IllegalStateException(e); }
        long[] l = d.debugLocator(id, key);
        if (l == null) throw new IllegalStateException("no " + TableIds.name(id) + " record " + key);
        b.flip(d.debugSegmentName(l[0]), (l[0] & 0xFFFFFFFFFFFFL) + l[2] / 2, 0x04);
        d.debugDropCache();
    }

    @SuppressWarnings("unchecked")
    static void f07(Path tr, TempTree w, Object clean) throws Exception {
        T.section("F-07");
        final PowerLossBlobStore b = new PowerLossBlobStore();
        final int[] done = {0};
        V2Cli.afterStage = new java.util.function.BiConsumer<String, V2Store>() {
            public void accept(String stage, V2Store s) {
                if ("T3_IMAGE".equals(stage) && done[0] == 0) {
                    // an image with an exact copy and a JPEG near-duplicate
                    damage(b, s, TableIds.FEATURES, fileIdOf(s, "DCIM/Camera/IMG_20240000_000000.png"));
                    damage(b, s, TableIds.FEATURES, fileIdOf(s, "Download/copy é 4.png"));
                    done[0]++;
                } else if ("GRAPH".equals(stage) && done[0] == 1) {
                    damage(b, s, TableIds.FEATURES, fileIdOf(s, "DCIM/Camera/IMG_20240008_000056.png"));
                    done[0]++;
                }
            }
        };
        Res r;
        try { r = cli(tr, w.path("f7.json"), w.dir("f7"), "disk", b); }
        finally { V2Cli.afterStage = null; }
        T.check("F-07: damage injected after T3 and after GRAPH", done[0] == 2);
        T.check("F-07: the damaged run completes", r.rc == 0 && r.json != null);
        if (r.rc != 0) { System.out.println(r.err); return; }
        Map<String, String> ev = events(r.json);
        T.check("F-07: D_STORE_DAMAGED counts all three records (" + ev.get("D_STORE_DAMAGED") + ")", "3".equals(ev.get("D_STORE_DAMAGED")));
        Map<String, Object> dj = (Map<String, Object>) Json.parse(r.json);
        Map<String, Object> cj = (Map<String, Object>) Json.parse(new String(Files.readAllBytes(w.path("clean.json")), StandardCharsets.UTF_8));
        T.check("F-07: removal recommendations ⊆ clean's", cj.size() > 0 && removals(cj).containsAll(removals(dj)));
        T.check("F-07: plan items ⊆ clean's", planFiles(cj).containsAll(planFiles(dj)));
        Map<String, String[]> cf = perFile(cj), df = perFile(dj);
        int bad = 0;
        for (Map.Entry<String, String[]> e : df.entrySet()) {
            String[] c = cf.get(e.getKey()), d = e.getValue();
            if (c == null) { bad++; continue; }
            if ("NO_VERIFIED_BACKUP".equals(d[0]) && !"NO_VERIFIED_BACKUP".equals(c[0])
                    || "FALSE".equals(d[1]) && !"FALSE".equals(c[1])) {
                bad++;
                System.out.println("  F-07: " + d[2] + ": recoverability " + c[0] + " -> " + d[0] + ", perceptual " + c[1] + " -> " + d[1]);
            }
        }
        T.eq("F-07: no file gets NO_VERIFIED_BACKUP or perceptual FALSE that the clean run did not", 0, bad);
        T.check("F-07: the damage changed the result (the test bites)", !clean.equals(DiskKillTest.norm(r.json)));
        // next run: the damaged frames lie below durable with no index file, so the open fails closed (F-05) and starts fresh
        Res n = cli(tr, w.path("f7b.json"), w.dir("f7"), "disk", b);
        Map<String, String> ev2 = n.rc == 0 ? events(n.json) : Collections.<String, String>emptyMap();
        T.check("F-07: next run (fresh store after F-05) equals clean", n.rc == 0 && ev2.containsKey("D_STORE_RESET") && clean.equals(DiskKillTest.norm(n.json)));

        // a hash damaged after T3 (hashes fail closed): GRAPH's read throws, the CLI retries once on a fresh store
        final PowerLossBlobStore hb = new PowerLossBlobStore();
        final int[] once = {0};
        V2Cli.afterStage = new java.util.function.BiConsumer<String, V2Store>() {
            public void accept(String stage, V2Store s) {
                if ("T3_IMAGE".equals(stage) && once[0]++ == 0) damage(hb, s, TableIds.HASHES, fileIdOf(s, "Download/copy é 4.png"));
            }
        };
        Res h;
        try { h = cli(tr, w.path("f7h.json"), w.dir("f7h"), "disk", hb); }
        finally { V2Cli.afterStage = null; }
        Map<String, String> ev3 = h.rc == 0 ? events(h.json) : Collections.<String, String>emptyMap();
        T.check("F-07: hash damaged after T3 ⇒ StoreCorruption, automatic retry on a fresh store, equals clean",
                once[0] >= 2 && h.rc == 0 && h.err.contains("retrying once") && ev3.containsKey("D_STORE_RESET") && clean.equals(DiskKillTest.norm(h.json)));
    }

    @SuppressWarnings("unchecked")
    static Set<String> removals(Map<String, Object> j) {
        Set<String> s = new TreeSet<String>();
        for (Object o : (List<Object>) j.get("recommendations")) {
            Map<String, Object> r = (Map<String, Object>) o;
            String a = String.valueOf(r.get("action"));
            if (!"KEEP".equals(a) && !"REVIEW".equals(a)) s.add(r.get("fileId") + " " + a);
        }
        return s;
    }

    @SuppressWarnings("unchecked")
    static Set<String> planFiles(Map<String, Object> j) {
        Set<String> s = new TreeSet<String>();
        Map<String, Object> p = (Map<String, Object>) j.get("plan");
        if (p == null) return s;
        for (Object o : (List<Object>) p.get("items")) {
            Map<String, Object> it = (Map<String, Object>) o;
            for (Object f : (List<Object>) it.get("fileIds")) s.add(it.get("action") + " " + f);
        }
        return s;
    }

    @SuppressWarnings("unchecked")
    static Map<String, String[]> perFile(Map<String, Object> j) {
        Map<String, String[]> m = new TreeMap<String, String[]>();
        for (Object o : (List<Object>) j.get("files")) {
            Map<String, Object> f = (Map<String, Object>) o;
            Map<String, Object> a = (Map<String, Object>) f.get("assessment");
            m.put(String.valueOf(f.get("fileId")), new String[]{a == null ? null : String.valueOf(a.get("recoverability")), String.valueOf(f.get("perceptualStatus")), String.valueOf(f.get("path"))});
        }
        return m;
    }

    public static void main(String[] args) throws Exception { System.exit(run(args) == 0 ? 0 : 1); }
}
