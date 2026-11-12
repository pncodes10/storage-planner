package com.storageplanner.v2.graph;

import com.storageplanner.v2.model.*;
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
            Node a = last.byId.get(pr[0]), b = last.byId.get(pr[1]);
            if (a == null || b == null || a.img() == null || b.img() == null) continue;
            Integer ia = idx.get(a.id()), ib = idx.get(b.id());
            Integer src = ia == null || ib == null ? null : last.cg.pairs.get(ia < ib ? ((long) ia << 32) | ib : ((long) ib << 32) | ia);
            int ham = a.f.hasPhash && b.f.hasPhash ? CandidateGenerator.ham(a.f.phash, b.f.phash) : -1;
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
        List<Node> nodes = new ArrayList<Node>();
        for (FileRecord f : st.files().all()) {
            if (f.presence != Presence.PRESENT) continue;
            if (ctx.config != null && ctx.config.volumeIds != null && !ctx.config.volumeIds.contains(f.volumeId)) continue;
            nodes.add(new Node(f, st.features().get(f.fileId), st.hashes().get(f.fileId), vols.get(f.volumeId)));
        }
        Collections.sort(nodes, ExactGrouper.BY_ID);
        for (int i = 0; i < nodes.size(); i++) nodes.get(i).idx = i;
        AliasResolver.resolve(nodes);
        // B. exact
        List<Relationship> edges = new ArrayList<Relationship>();
        List<DuplicateGroup> groups = new ArrayList<DuplicateGroup>();
        ExactGrouper.run(nodes, scanId, edges, groups);
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

    static <R extends Record> void diff(com.storageplanner.v2.store.Table<R> t, Codecs.Codec<R> codec, Map<String, R> want, long keepScan) {
        List<String> drop = new ArrayList<String>();
        for (R r : t.all()) if (!want.containsKey(r.key())) drop.add(r.key());
        for (String k : drop) t.remove(k);
        for (R r : want.values()) {
            R old = t.get(r.key());
            if (old == null || !Codecs.json(codec, old).equals(Codecs.json(codec, withScan(r, old)))) t.put(r);
        }
    }
    /** Keeps createdScanId of an unchanged record (edges are rebuilt, the creation scan is not). */
    static <R extends Record> R withScan(R r, R old) {
        if (r instanceof Relationship) ((Relationship) r).createdScanId = ((Relationship) old).createdScanId;
        if (r instanceof DuplicateGroup) ((DuplicateGroup) r).createdScanId = ((DuplicateGroup) old).createdScanId;
        return r;
    }

    static void write(V2Store st, List<Node> nodes, List<Relationship> edges, List<DuplicateGroup> groups) {
        Map<String, Relationship> we = new TreeMap<String, Relationship>();
        for (Relationship r : edges) {
            Relationship o = we.get(r.edgeId);
            if (o == null || r.confidence > o.confidence) we.put(r.edgeId, r);
        }
        Map<String, DuplicateGroup> wg = new TreeMap<String, DuplicateGroup>();
        for (DuplicateGroup g : groups) wg.put(g.groupId, g);
        diff(st.relationships(), Codecs.RELATIONSHIP, we, 0);
        diff(st.duplicateGroups(), Codecs.DUPLICATE_GROUP, wg, 0);
        for (Node n : nodes) {
            String want = n.alias() ? null : n.exactGroup;
            if (want == null ? n.f.duplicateGroupId != null : !want.equals(n.f.duplicateGroupId)) {
                FileRecord f = st.files().get(n.id());
                f.duplicateGroupId = want;
                st.files().put(f);
            }
        }
    }
}
