package com.storageplanner.v2.graph;

import com.storageplanner.v2.model.*;
import com.storageplanner.v2.model.Record;   // single-type import: java.lang.Record exists in android-34.jar
import com.storageplanner.v2.stage.Stage;
import com.storageplanner.v2.stage.StageContext;
import com.storageplanner.v2.stage.StageOutcome;
import com.storageplanner.v2.store.Codecs;
import com.storageplanner.v2.store.StoreException;
import com.storageplanner.v2.store.V2Store;

import java.util.*;

/** GRAPH stage (PHASE_3 1): exact groups, perceptual candidates and checks, edge typing, near groups, archive backups. */
public final class GraphStage implements Stage {
    public String id() { return "GRAPH"; }

    /** Coverage counters of the last run (GraphCoverage). */
    public final Map<String, Long> coverage = new TreeMap<String, Long>();
    Perceptual last;
    /** StructSig cache size used by probe() (calibration runs only). */
    public int probeCache = 3000;

    /** Calibration probe (V2Cli pairs): stage-4 metrics for arbitrary pairs of the last run, all transforms. {pairCheck, generated} */
    public List<Object[]> probe(List<String[]> pairs) {
        List<Object[]> out = new ArrayList<Object[]>();
        if (last == null) return out;
        last.cacheSize = probeCache;
        Map<String, Integer> idx = new HashMap<String, Integer>();
        for (int i = 0; i < last.el.size(); i++) idx.put(last.el.get(i).id(), i);
        for (String[] pr : pairs) {
            Node a = last.byId(pr[0]), b = last.byId(pr[1]);
            if (a == null || b == null || a.img() == null || b.img() == null) continue;
            Integer ia = idx.get(a.id()), ib = idx.get(b.id());
            int sv = ia == null || ib == null ? -1 : last.cg.sourcesOf(ia < ib ? ((long) ia << 32) | ib : ((long) ib << 32) | ia);
            Integer src = sv < 0 ? null : sv;
            int ham = a.f().hasPhash && b.f().hasPhash ? CandidateGenerator.ham(a.f().phash, b.f().phash) : -1;
            PairCheck pc = last.check(a, b, src == null ? 0 : src, ham, true);
            out.add(new Object[]{pc, src != null});
        }
        return out;
    }

    public StageOutcome run(StageContext ctx) throws StoreException {
        V2Store st = ctx.store;
        long scanId = ctx.session == null ? 0 : ctx.session.scanId;
        coverage.clear();
        // A. snapshot
        Map<String, Volume> vols = new HashMap<String, Volume>();
        for (Volume v : st.volumes().all()) vols.put(v.volumeId, v);
        // lite nodes (PHASE_S_STORE 4 item 3): one streaming pass; records are re-read through a bounded cache when needed
        GraphRecords rec = new GraphRecords(st);
        List<Node> nodes = new ArrayList<Node>();
        long[] pk = new long[64], sha = new long[64];
        int npk = 0, nsha = 0;
        for (FileRecord f : st.files().all()) {
            if (f.presence != Presence.PRESENT) continue;
            if (ctx.config != null && ctx.config.volumeIds != null && !ctx.config.volumeIds.contains(f.volumeId)) continue;
            FeatureRecord fr = st.features().get(f.fileId);
            HashRecord hr = st.hashes().get(f.fileId);
            Node n = new Node(f, fr, hr, vols.get(f.volumeId), rec);
            rec.offer(n, f, fr, hr);
            if (f.physicalKey != null) {
                if (2 * npk + 2 > pk.length) pk = Arrays.copyOf(pk, pk.length + (pk.length >> 1) & ~1);
                pk[2 * npk] = hash64(f.physicalKey); pk[2 * npk + 1] = nodes.size(); npk++;
            }
            if (n.is(Node.SHA)) {
                if (2 * nsha + 2 > sha.length) sha = Arrays.copyOf(sha, sha.length + (sha.length >> 1) & ~1);
                sha[2 * nsha] = hash64(f.sha256); sha[2 * nsha + 1] = nodes.size(); nsha++;
            }
            nodes.add(n);
        }
        // files().all() is in ascending fileId (String order, both stores), the order BY_ID gives; pk holds those indexes
        for (int i = 0; i < nodes.size(); i++) {
            if (i > 0 && nodes.get(i - 1).id.compareTo(nodes.get(i).id) >= 0) throw new IllegalStateException("files().all() not in key order");
            nodes.get(i).idx = i;
        }
        AliasResolver.resolve(nodes, pk, npk);
        pk = null;
        // B. exact
        List<Relationship> edges = new ArrayList<Relationship>();
        List<DuplicateGroup> groups = new ArrayList<DuplicateGroup>();
        ExactGrouper.run(nodes, sha, nsha, scanId, edges, groups);
        sha = null;
        StageOutcome outcome = StageOutcome.DONE;
        // C-D. perceptual
        Perceptual p = new Perceptual(ctx, nodes, groups, coverage);
        last = p;
        outcome = p.run();
        // E. assemble
        p.assemble(edges, groups, scanId);
        ArchiveMatcher.run(ctx, nodes, edges, coverage);
        CompanionLinker.run(nodes, edges, scanId);
        write(st, nodes, edges, groups);
        st.commit();
        return outcome;
    }

    /** FNV-1a 64 over the chars (bucketing only; equal hashes are confirmed on the strings). */
    static long hash64(String s) {
        long x = 0xcbf29ce484222325L;
        for (int i = 0; i < s.length(); i++) { x ^= s.charAt(i); x *= 0x100000001b3L; }
        return x;
    }

    static <R extends Record> void diff(com.storageplanner.v2.store.Table<R> t, Codecs.Codec<R> codec, Map<String, R> want, long keepScan) throws StoreException {
        diff(t, codec, want, keepScan, null);
    }

    /** Commits after every {@link #COMMIT_EVERY} writes when {@code st} is given (PHASE_S_STORE 4 item 3: bounded undo). */
    static final int COMMIT_EVERY = 2000;
    static final class Committer {
        final V2Store st; int n;
        Committer(V2Store st) { this.st = st; }
        void tick() throws StoreException { if (++n >= COMMIT_EVERY) { st.commit(); n = 0; } }
    }

    static <R extends Record> void diff(com.storageplanner.v2.store.Table<R> t, Codecs.Codec<R> codec, Map<String, R> want, long keepScan, Committer c) throws StoreException {
        List<String> drop = new ArrayList<String>();
        for (String k : t.keys()) if (!want.containsKey(k)) drop.add(k);
        for (String k : drop) { t.remove(k); if (c != null) c.tick(); }
        for (R r : want.values()) {
            R old = t.get(r.key());
            if (old == null || !Codecs.json(codec, old).equals(Codecs.json(codec, withScan(r, old)))) { t.put(r); if (c != null) c.tick(); }
        }
    }

    /** Keeps createdScanId of an unchanged record (edges are rebuilt, the creation scan is not). */
    static <R extends Record> R withScan(R r, R old) {
        if (r instanceof Relationship) ((Relationship) r).createdScanId = ((Relationship) old).createdScanId;
        if (r instanceof DuplicateGroup) ((DuplicateGroup) r).createdScanId = ((DuplicateGroup) old).createdScanId;
        return r;
    }

    static void write(V2Store st, List<Node> nodes, List<Relationship> edges, List<DuplicateGroup> groups) throws StoreException {
        Map<String, Relationship> we = new TreeMap<String, Relationship>();
        for (Relationship r : edges) {
            Relationship o = we.get(r.edgeId);
            if (o == null || r.confidence > o.confidence) we.put(r.edgeId, r);
        }
        Map<String, DuplicateGroup> wg = new TreeMap<String, DuplicateGroup>();
        for (DuplicateGroup g : groups) wg.put(g.groupId, g);
        Committer c = new Committer(st);
        diff(st.relationships(), Codecs.RELATIONSHIP, we, 0, c);
        diff(st.duplicateGroups(), Codecs.DUPLICATE_GROUP, wg, 0, c);
        for (Node n : nodes) {
            String want = n.alias() ? null : n.exactGroup;
            if (want == null ? n.oldGroup != null : !want.equals(n.oldGroup)) {
                FileRecord f = st.files().get(n.id());
                f.duplicateGroupId = want;
                st.files().put(f);
                c.tick();
            }
        }
    }
}
