package com.storageplanner.v2.graph;

import com.storageplanner.v2.device.Clock;
import com.storageplanner.v2.device.DecodedImage;
import com.storageplanner.v2.features.Limits;
import com.storageplanner.v2.model.*;
import com.storageplanner.v2.stage.StageContext;
import com.storageplanner.v2.stage.StageOutcome;
import com.storageplanner.v2.store.StoreException;
import com.storageplanner.v2.store.V2Store;
import com.storageplanner.v2.util.Hashing;

import java.util.*;

/** Stages 2-4 (candidates, gate, structural checks) and their assembly: EdgeTyper, near groups, keepers (PHASE_3 4, 5, 8.2). */
final class Perceptual {
    static final String PSTAT = "graph.perceptual";
    final StageContext ctx; final List<Node> nodes; final Map<String, Long> cov;
    final List<Node> el = new ArrayList<Node>();
    CandidateGenerator cg;
    final List<Pair> gated = new ArrayList<Pair>();
    final Map<String, PairCheck> checks = new TreeMap<String, PairCheck>();   // pairKey -> current check
    final Map<String, PairCheck> byIds = new HashMap<String, PairCheck>();     // a\0b -> current check
    final Map<String, Tri> status = new HashMap<String, Tri>();
    int decodes;
    /** PHASE_S_STORE S7: decodes this run may spend on pending pair checks; reaching it ends the checks, not the stage. */
    int decodeBudget = GraphConfig.MAX_DECODES_PER_RUN;
    /** S7: by node index (Node.idx), true = the node still has a gated pair without a finished check after this run. */
    boolean[] pendingNode;
    /** S7: eligible images with an unfinished check (their perceptual relationships are unknown until a later run). */
    long pendingImages;
    int cacheSize = GraphConfig.SIG_CACHE;
    final LinkedHashMap<String, StructSig> cache = new LinkedHashMap<String, StructSig>(64, 0.75f, true) {
        protected boolean removeEldestEntry(Map.Entry<String, StructSig> e) { return size() > cacheSize; }
    };

    static final class Pair { Node a, b; int sources, ham; String key; }

    Perceptual(StageContext ctx, List<Node> nodes, List<DuplicateGroup> exactGroups, Map<String, Long> cov) {
        this.ctx = ctx; this.nodes = nodes; this.cov = cov;
        Map<String, String> rep = new HashMap<String, String>();
        for (DuplicateGroup g : exactGroups) rep.put(g.groupId, g.keeperId != null ? g.keeperId : g.members.get(0));
        for (Node n : nodes) {
            if (!n.is(Node.HAS_IMAGE) || n.alias()) continue;
            if (n.exactGroup != null && !n.id().equals(rep.get(n.exactGroup))) continue;
            if (n.is(Node.ELIGIBLE)) el.add(n); else { status.put(n.id(), Tri.UNKNOWN); inc("ineligibleImages"); }
        }
    }

    void inc(String k) { inc(k, 1); }
    void inc(String k, long d) { Long o = cov.get(k); cov.put(k, (o == null ? 0 : o) + d); }

    /** Node lookup by fileId (nodes are in ascending fileId; PHASE_S 4 item 3 dropped the id map). */
    Node byId(String id) { return ArchiveMatcher.find(nodes, id); }

    static boolean eligible(FileRecord f, FeatureRecord fr) {
        ImageInfo i = fr == null ? null : fr.image;
        if (i == null || !f.hasPhash || i.decodeStatus != DecodeStatus.OK || i.uprightWidth <= 0 || i.uprightHeight <= 0) return false;
        return !(i.contrast < GraphConfig.MIN_CONTRAST);
    }

    static String pairKey(Node a, Node b) {
        return Hashing.sha256Hex("pc1\u0000" + a.id() + "\u0000" + a.fp() + "\u0000" + b.id() + "\u0000" + b.fp() + "\u0000" + GraphConfig.MATCHER_VERSION).substring(0, 32);
    }

    /** null = passes; else the reject reason (PHASE_3 4.3). */
    static String gate(Node a, Node b, int sources, int ham) {
        if (a.canonical().equals(b.canonical())) return "SAME_FILE";
        if (a.exactGroup != null && a.exactGroup.equals(b.exactGroup)) return "EXACT";
        boolean stem = (sources & CandidateSource.NAME_STEM.bit()) != 0, time = (sources & CandidateSource.TIME_WINDOW.bit()) != 0;
        double ar = a.img().aspect / b.img().aspect;
        if (Math.abs(ar - 1) > GraphConfig.ASPECT_TOL && !stem) return "ASPECT";
        if (!stem && ham > (time ? GraphConfig.T_BURST : GraphConfig.T_CAND)) return "HAMMING";
        if (a.longEdge() < GraphConfig.MIN_LONG_EDGE || b.longEdge() < GraphConfig.MIN_LONG_EDGE) return "TOO_SMALL";
        return null;
    }

    StageOutcome run() throws StoreException {
        int n = el.size();
        long[] h = new long[n], t = new long[n]; String[] s = new String[n];
        for (int i = 0; i < n; i++) { Node x = el.get(i); h[i] = x.phash; t[i] = x.captureTime(); s[i] = x.name().stem; }
        cg = new CandidateGenerator(h, t, s).run();
        inc("eligibleImages", n); inc("candidatePairs", cg.pairCount()); inc("bucketOverflow", cg.bucketOverflow);
        for (int q = 0; q < cg.pairCount(); q++) {
            long key = cg.keys[q]; int src = cg.sources[q];
            Node a = el.get((int) (key >>> 32)), b = el.get((int) key);
            int ham = CandidateGenerator.ham(a.phash, b.phash);
            String r = gate(a, b, src, ham);
            if (r != null) { inc("rejected" + r); continue; }
            Pair p = new Pair(); p.a = a; p.b = b; p.sources = src; p.ham = ham; p.key = pairKey(a, b);
            gated.add(p);
        }
        inc("gatedPairs", gated.size());
        V2Store st = ctx.store;
        List<Pair> pending = new ArrayList<Pair>();
        for (Pair p : gated) {
            PairCheck pc = st.pairChecks().get(p.key);
            if (pc != null && pc.matcherVersion == GraphConfig.MATCHER_VERSION) put(pc); else pending.add(p);
        }
        orderForCache(pending, nodes.size());
        Clock clock = ctx.device == null ? null : ctx.device.clock();
        StageOutcome out = StageOutcome.DONE;
        int sinceCommit = 0;
        for (Pair p : pending) {
            // the run's work budget (time/bytes, PipelineConfig.budget) pauses the stage; the decode budget only ends the checks
            // of this run (S7): GRAPH completes with the unchecked images marked UNKNOWN and the next run continues
            if (ctx.budget != null && clock != null && ctx.budget.exhausted(clock)) { out = StageOutcome.BUDGET_EXHAUSTED; break; }
            if (decodes >= decodeBudget) break;
            PairCheck pc = check(p.a, p.b, p.sources, p.ham, (p.sources & CandidateSource.NAME_STEM.bit()) != 0);
            pc.pairKey = p.key;
            st.pairChecks().put(pc);
            put(pc);
            if (++sinceCommit >= 200) { st.commit(); sinceCommit = 0; }
        }
        // stale checks (pairs no longer gated or fingerprints changed)
        Set<String> want = new HashSet<String>();
        for (Pair p : gated) want.add(p.key);
        List<String> drop = new ArrayList<String>();
        for (String k : st.pairChecks().keys()) if (!want.contains(k)) drop.add(k);   // key = pairKey
        for (String k : drop) st.pairChecks().remove(k);
        inc("decodes", decodes);
        // perceptual status (F-01: truncated search or pending checks -> UNKNOWN)
        Map<String, Boolean> hasPair = new HashMap<String, Boolean>(), hasPending = new HashMap<String, Boolean>();
        for (Pair p : gated) {
            boolean pend = !checks.containsKey(p.key);
            for (Node x : new Node[]{p.a, p.b}) { hasPair.put(x.id(), true); if (pend) hasPending.put(x.id(), true); }
        }
        long pend = 0;
        pendingNode = new boolean[nodes.size()];
        for (Pair p : gated) if (!checks.containsKey(p.key)) { pend++; pendingNode[p.a.idx] = true; pendingNode[p.b.idx] = true; }
        inc("pending", pend);
        if (pend > 0) {     // keys only in a run that left checks for the next one (an unbudgeted result is unchanged)
            for (boolean b : pendingNode) if (b) pendingImages++;
            inc("pendingImages", pendingImages);
        }
        for (int i = 0; i < n; i++) {
            Node x = el.get(i);
            Tri v = cg.truncated[i] || hasPending.containsKey(x.id()) ? Tri.UNKNOWN : hasPair.containsKey(x.id()) ? Tri.TRUE : Tri.FALSE;
            if (cg.truncated[i]) inc("candidateTruncated");
            status.put(x.id(), v);
        }
        return out;
    }

    /**
     * S7 (REVIEW_S F-13 item 1): pending checks ordered for signature-cache reuse. Components of the pending-pair graph in order
     * of their smallest pair key; inside a component by (smaller node id, pair key), so each node's pairs come together and a
     * small component (a burst) is decoded about once per node. Deterministic; the order changes only how often the cache misses.
     */
    static void orderForCache(List<Pair> pending, int nNodes) {
        if (pending.size() < 2) return;
        final int[] parent = new int[nNodes];
        for (int i = 0; i < nNodes; i++) parent[i] = i;
        for (Pair p : pending) {
            int x = root(parent, p.a.idx), y = root(parent, p.b.idx);
            if (x != y) parent[Math.max(x, y)] = Math.min(x, y);
        }
        final Map<Integer, String> minKey = new HashMap<Integer, String>();
        for (Pair p : pending) {
            Integer r = root(parent, p.a.idx);
            String k = minKey.get(r);
            if (k == null || p.key.compareTo(k) < 0) minKey.put(r, p.key);
        }
        Collections.sort(pending, new Comparator<Pair>() {
            public int compare(Pair x, Pair y) {
                int c = minKey.get(root(parent, x.a.idx)).compareTo(minKey.get(root(parent, y.a.idx)));
                if (c != 0) return c;
                c = smallerId(x).compareTo(smallerId(y));
                return c != 0 ? c : x.key.compareTo(y.key);
            }
        });
    }
    static int root(int[] parent, int x) {
        while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x]; }
        return x;
    }
    static String smallerId(Pair p) { return p.a.id().compareTo(p.b.id()) < 0 ? p.a.id() : p.b.id(); }

    void put(PairCheck pc) { checks.put(pc.pairKey, pc); byIds.put(pc.a + "\u0000" + pc.b, pc); }
    PairCheck between(String x, String y) { return x.compareTo(y) < 0 ? byIds.get(x + "\u0000" + y) : byIds.get(y + "\u0000" + x); }

    StructSig sig(Node n) {
        StructSig s = cache.get(n.id());
        if (s != null) return s;
        DecodedImage d;
        try { d = ctx.device.media().decode(n.f().volumeId, n.f().accessPath(), GraphConfig.SIG_DECODE_EDGE, new Limits().maxPixels, true); }
        catch (RuntimeException e) { d = DecodedImage.failed(DecodeStatus.CORRUPT, "ERROR:" + e.getClass().getSimpleName()); }
        decodes++;
        if (ctx.budget != null) ctx.budget.chargeDecode();
        s = StructSig.of(d);
        cache.put(n.id(), s);
        return s;
    }

    /** Stage 4 for one pair; allTransforms for NAME_STEM pairs (4.3b). */
    PairCheck check(Node x, Node y, int sources, int ham, boolean allTransforms) {
        Node a = x.id().compareTo(y.id()) < 0 ? x : y, b = a == x ? y : x;
        PairCheck pc = new PairCheck();
        pc.a = a.id(); pc.b = b.id(); pc.fpA = a.fp(); pc.fpB = b.fp(); pc.matcherVersion = GraphConfig.MATCHER_VERSION; pc.sources = sources;
        pc.hamming = ham; pc.checkedScanId = ctx.session == null ? Unknown.LONG : ctx.session.scanId;
        pc.pairKey = pairKey(a, b);
        Node ref = a.pixels() >= b.pixels() ? a : b, oth = ref == a ? b : a;
        try {
            StructSig sr = sig(ref), so = sig(oth);
            if (sr.status != DecodeStatus.OK || so.status != DecodeStatus.OK) {
                pc.verdict = PairVerdict.NOT_COMPARABLE; pc.note = "DECODE:" + (sr.status != DecodeStatus.OK ? sr.status : so.status); return pc;
            }
            boolean bandA = a.longEdge() >= GraphConfig.BAND_A_MIN && b.longEdge() >= GraphConfig.BAND_A_MIN;
            PairMetrics best = null; int bestT = 0;
            for (int t = 0; t < (allTransforms ? 8 : 1); t++) {
                PairMetrics m = StructSig.compare(sr, so, t, bandA, GraphConfig.ASPECT_TOL);
                if (m != null && !Double.isNaN(m.ssim64Mean) && (best == null || m.ssim64Mean > best.ssim64Mean + 1e-9)) { best = m; bestT = t; }
            }
            if (best == null) { pc.verdict = PairVerdict.NOT_COMPARABLE; pc.note = "ASPECT"; return pc; }
            pc.metrics = best; pc.transform = StructSig.TRANSFORMS[bestT];
            long ta = a.captureTime(), tb = b.captureTime();
            boolean distinct = Unknown.knownTime(ta) && Unknown.knownTime(tb) && ta / 1000 != tb / 1000;
            pc.verdict = VerdictRules.verdict(best, ham, pc.transform, sources, oth.thumbLocation(), distinct);
        } catch (RuntimeException e) {
            pc.verdict = PairVerdict.NOT_COMPARABLE; pc.note = "ERROR:" + e.getClass().getSimpleName();
        }
        return pc;
    }

    // ------------------------------------------------------------------ assembly (E)

    static final Set<String> CHAT = new HashSet<String>(Arrays.asList("wa_image", "wa_sent", "wa_doc", "telegram_media"));

    static boolean cameraNative(Node n) { return "camera".equals(n.f().area) && ("jpeg".equals(n.f().type) || "heic".equals(n.f().type) || "dng".equals(n.f().type)); }

    /** Direction of a SAME_CONTENT pair: the original (5.2). */
    static final Comparator<Node> ORIGINAL_FIRST = new Comparator<Node>() {
        public int compare(Node x, Node y) {
            int c = Long.compare(y.pixels(), x.pixels()); if (c != 0) return c;
            c = Boolean.compare(cameraNative(y), cameraNative(x)); if (c != 0) return c;   // before bytes/pixel (PHASE_3_NOTES D-7)
            double bx = x.f().sizeBytes / (double) Math.max(1, x.pixels()), by = y.f().sizeBytes / (double) Math.max(1, y.pixels());
            if (bx != by) return bx > by ? -1 : 1;
            c = Boolean.compare(cameraNative(y), cameraNative(x)); if (c != 0) return c;
            c = earlier(x, y); if (c != 0) return c;
            return x.id().compareTo(y.id());
        }
    };
    static int earlier(Node x, Node y) {
        boolean kx = Unknown.knownTime(x.f().createdAt), ky = Unknown.knownTime(y.f().createdAt);
        if (kx != ky) return kx ? -1 : 1;
        return kx ? Long.compare(x.f().createdAt, y.f().createdAt) : 0;
    }

    Relationship typeSame(Node a, Node b, PairCheck pc, long scanId) {
        Node o = ORIGINAL_FIRST.compare(a, b) <= 0 ? a : b, c = o == a ? b : a;
        RelType t; String ev; Object val = null;
        double pr = c.pixels() / (double) Math.max(1, o.pixels()), br = c.f().sizeBytes / (double) Math.max(1, o.f().sizeBytes);
        if (c.thumbLocation() || (c.longEdge() <= GraphConfig.THUMB_MAX_EDGE && c.longEdge() <= GraphConfig.THUMB_EDGE_RATIO * o.longEdge())) { t = RelType.THUMBNAIL_OF; ev = "PIXELS_RATIO"; val = pr; }
        else if (CHAT.contains(c.f().area) || NameStem.WA.matcher(c.name().stem).matches()) { t = RelType.COMPRESSED_FROM; ev = CHAT.contains(c.f().area) ? "AREA_PAIR" : "NAME_PATTERN_WA"; val = c.f().area; }
        else if (!String.valueOf(c.f().type).equals(o.f().type) && Math.abs(pr - 1) <= 0.01) { t = RelType.VERSION_OF; ev = "FORMAT_PAIR"; val = c.f().type + ">" + o.f().type; }
        else if (pr <= 0.81 || br <= 0.8) { t = RelType.COMPRESSED_FROM; ev = pr <= 0.81 ? "PIXELS_RATIO" : "BYTES_RATIO"; val = pr <= 0.81 ? pr : br; }
        else { t = RelType.VERSION_OF; ev = "BYTES_RATIO"; val = br; }
        Relationship r = Edges.make(c.id(), o.id(), t, VerdictRules.sameConfidence(pc.metrics), Verification.VERIFIED, c.fp(), o.fp(), scanId);
        r.verifiedAt = ctx.device == null ? Unknown.TIME : ctx.device.clock().nowMillis();
        Edges.ev(r, ev, val instanceof Double ? StructSig.r6((Double) val) : val);
        metricsEvidence(r, pc);
        return r;
    }
    static void metricsEvidence(Relationship r, PairCheck pc) {
        Edges.ev(r, "PHASH_DIST", pc.hamming);
        PairMetrics m = pc.metrics;
        if (m == null) return;
        Edges.ev(r, "SSIM64_MEAN", m.ssim64Mean); Edges.ev(r, "SSIM64_MIN", m.ssim64Min); Edges.ev(r, "DIFF64_MAX", m.diff64Max);
        if (!Double.isNaN(m.ssimFineMin)) { Edges.ev(r, "SSIM_FINE_MIN", m.ssimFineMin); Edges.ev(r, "DIFF_FINE_MAX", m.diffFineMax); }
        Edges.ev(r, "CHROMA16", m.chroma16);
        if (pc.transform != null) Edges.ev(r, "TRANSFORM", pc.transform);
    }

    static long dto(Node n) { return n.fr() != null && n.fr().exif != null ? n.fr().exif.dateTimeOriginal : Unknown.TIME; }
    static boolean sameSecond(long x, long y) { return Unknown.knownTime(x) && Unknown.knownTime(y) && x / 1000 == y / 1000; }
    static boolean editorSoftware(Node n) {
        String s = n.fr() != null && n.fr().exif != null ? n.fr().exif.software : null;
        if (s == null) return false;
        s = s.toLowerCase(Locale.ROOT);
        for (String e : GraphConfig.EDITOR_SOFTWARE) if (s.contains(e)) return true;
        return false;
    }

    /** Provenance evidence codes (5.3). */
    static List<String> provenance(Node a, Node b, PairCheck pc) {
        List<String> l = new ArrayList<String>();
        if (stemProvenance(a, b)) l.add("NAME_STEM_EQUAL");
        long na = a.fr().nameDate == null ? Unknown.TIME : a.fr().nameDate.at, nb = b.fr().nameDate == null ? Unknown.TIME : b.fr().nameDate.at;
        boolean namesDisagree = Unknown.knownTime(na) && Unknown.knownTime(nb) && na / 86400000L != nb / 86400000L;
        if (sameSecond(dto(a), dto(b)) && !namesDisagree) l.add("EXIF_DTO_EQUAL");
        if ((a.editorFolder() || b.editorFolder()) && pc.metrics != null && pc.metrics.ssim64Mean >= GraphConfig.EDITOR_FOLDER_SSIM) l.add("EDITOR_FOLDER");
        if ((editorSoftware(a) || editorSoftware(b)) && sameSecond(a.captureTime(), b.captureTime())) l.add("EXIF_SOFTWARE");
        return l;
    }

    static final java.util.regex.Pattern STAMP = java.util.regex.Pattern.compile("\\d{6}");
    /** Name-stem provenance: equal stems carrying a 6+ digit time stamp (IMG_0001 in two folders is not provenance). */
    static boolean stemProvenance(Node a, Node b) {
        return a.name().stem.length() >= GraphConfig.STEM_MIN_LEN && a.name().stem.equals(b.name().stem) && STAMP.matcher(a.name().stem).find();
    }
    static boolean derivedName(Node n) { return n.name().editSuffix || n.name().copySuffix; }
    /** No EDITED_FROM between two derived members (siblings) or from/to a thumbnail-location image (PHASE_3_NOTES D-8). */
    static boolean editPairAllowed(Node a, Node b) {
        if (a.thumbLocation() || b.thumbLocation()) return false;
        return !(derivedName(a) && derivedName(b)) && !(a.editorFolder() && b.editorFolder());
    }

    static Node[] editDirection(Node a, Node b) {
        int c = Boolean.compare(derivedName(a) || a.editorFolder(), derivedName(b) || b.editorFolder());
        if (c == 0) c = earlier(a, b);
        if (c == 0) c = a.id().compareTo(b.id());
        return c <= 0 ? new Node[]{b, a} : new Node[]{a, b};   // {edit, original}
    }

    void assemble(List<Relationship> edges, List<DuplicateGroup> groups, long scanId) throws StoreException {
        List<Relationship> same = new ArrayList<Relationship>(), edits = new ArrayList<Relationship>();
        List<PairCheck> scenes = new ArrayList<PairCheck>();
        for (PairCheck pc : checks.values()) {
            Node a = byId(pc.a), b = byId(pc.b);
            if (a == null || b == null) continue;
            // S7: an image with an unfinished check takes part in no perceptual edge or group yet, so a partial result never
            // groups, types or removes it on half its comparisons; its finished checks stay saved and are used once it is complete
            if (pendingNode != null && (pendingNode[a.idx] || pendingNode[b.idx])) { inc("checksHeldBack"); continue; }
            inc("verdict" + pc.verdict);
            if (pc.note != null && pc.note.startsWith("ERROR:")) inc("comparatorErrors");
            switch (pc.verdict) {
                case SAME_CONTENT: same.add(typeSame(a, b, pc, scanId)); break;
                case LOCAL_EDIT: case COLOR_EDIT: case GEOMETRIC_EDIT: {
                    List<String> prov = editPairAllowed(a, b) ? provenance(a, b, pc) : new ArrayList<String>();
                    if (prov.isEmpty()) { inc("editWithoutProvenance"); break; }
                    Node[] d = editDirection(a, b);
                    Relationship r = Edges.make(d[0].id(), d[1].id(), RelType.EDITED_FROM, Math.min(0.9, 0.6 + 0.1 * prov.size()), Verification.VERIFIED, d[0].fp(), d[1].fp(), scanId);
                    for (String p : prov) Edges.ev(r, p, null);
                    metricsEvidence(r, pc);
                    edits.add(r); break;
                }
                case NOT_COMPARABLE: {
                    if (!"ASPECT".equals(pc.note) || (pc.sources & CandidateSource.NAME_STEM.bit()) == 0 || !editPairAllowed(a, b) || !stemProvenance(a, b)) break;
                    boolean nd = a.fr().nameDate != null && b.fr().nameDate != null && sameSecond(a.fr().nameDate.at, b.fr().nameDate.at)
                            && "SECOND".equals(a.fr().nameDate.precision);
                    if (!sameSecond(dto(a), dto(b)) && !nd) break;
                    Node[] d = editDirection(a, b);
                    Relationship r = Edges.make(d[0].id(), d[1].id(), RelType.EDITED_FROM, 0.6, Verification.UNVERIFIED, d[0].fp(), d[1].fp(), scanId);
                    Edges.ev(r, "CROP_OR_RECOMPOSE", null); Edges.ev(r, "NAME_STEM_EQUAL", null);
                    Edges.ev(r, sameSecond(dto(a), dto(b)) ? "EXIF_DTO_EQUAL" : "CAPTURE_DT_MS", sameSecond(dto(a), dto(b)) ? null : "0");
                    edits.add(r); break;
                }
                case NEAR_SCENE: scenes.add(pc); break;
                default: break;
            }
        }
        // CONTENT_EQUIVALENT: components of SAME_CONTENT edges are seeds; like bursts (F-02) a group keeps only the nodes with
        // their own SAME_CONTENT check against the keeper (sink rule), and its edges form a star to the keeper. A band-B thumbnail
        // that matches both an original and its annotated edit no longer chains them together (PHASE_3_NOTES D-10).
        long now = ctx.device == null ? Unknown.TIME : ctx.device.clock().nowMillis();
        UnionFind uf = new UnionFind();
        final Set<String> hasOut = new HashSet<String>();
        Map<String, Relationship> sameByPair = new HashMap<String, Relationship>();
        for (Relationship r : same) { uf.union(r.src, r.dst); hasOut.add(r.src); sameByPair.put(r.src.compareTo(r.dst) < 0 ? r.src + "\u0000" + r.dst : r.dst + "\u0000" + r.src, r); }
        Comparator<Node> sinkFirst = new Comparator<Node>() {
            public int compare(Node x, Node y) {
                int c = Boolean.compare(hasOut.contains(x.id()), hasOut.contains(y.id())); if (c != 0) return c;
                c = ORIGINAL_FIRST.compare(x, y); if (c != 0) return c;
                return Integer.compare(KeeperSelector.locRank(x), KeeperSelector.locRank(y));
            }
        };
        for (List<String> comp : uf.components()) {
            TreeSet<String> rem = new TreeSet<String>(comp);
            while (rem.size() >= 2) {
                List<Node> order = nodesOf(new ArrayList<String>(rem));
                Collections.sort(order, sinkFirst);
                Node k = pickEligible(order);
                if (k == null) k = order.get(0);
                List<Node> mem = new ArrayList<Node>(); mem.add(k);
                for (String id : rem) {
                    if (id.equals(k.id())) continue;
                    String key = id.compareTo(k.id()) < 0 ? id + "\u0000" + k.id() : k.id() + "\u0000" + id;
                    Relationship r = sameByPair.get(key);
                    if (r != null) { mem.add(byId(id)); edges.add(r); }
                }
                for (Node m : mem) rem.remove(m.id());
                if (mem.size() < 2) continue;
                Collections.sort(mem, ExactGrouper.BY_ID);
                groups.add(group("c", NearGroupKind.CONTENT_EQUIVALENT, mem, KeeperSelector.eligible(k, false) ? k : null, scanId, "G_KEEPER_QUALITY"));
            }
        }
        long kept = 0; for (Relationship r : edges) if (r.type == RelType.COMPRESSED_FROM || r.type == RelType.THUMBNAIL_OF || r.type == RelType.VERSION_OF) kept++;
        inc("sameEdgesNotOnStar", same.size() - kept);
        // 8.2 conflict rule: an edit of a redundant CONTENT_EQUIVALENT member is re-pointed to that group's keeper
        Map<String, String> ceKeeper = new HashMap<String, String>();
        for (DuplicateGroup g : groups) if (g.kind == NearGroupKind.CONTENT_EQUIVALENT && g.keeperId != null) for (String m : g.members) ceKeeper.put(m, g.keeperId);
        Map<String, Relationship> rp = new TreeMap<String, Relationship>();
        for (Relationship r : edits) {
            String k = ceKeeper.get(r.dst);
            if (k != null && !k.equals(r.dst) && !k.equals(r.src)) {
                Relationship x = Edges.make(r.src, k, RelType.EDITED_FROM, r.confidence, r.verification, r.srcFp, byId(k).fp(), scanId);
                x.evidence = r.evidence; r = x;
            }
            if (!r.src.equals(r.dst) && !(ceKeeper.containsKey(r.src) && ceKeeper.get(r.src).equals(ceKeeper.get(r.dst)))) {
                Relationship o = rp.get(r.edgeId);
                if (o == null || r.confidence > o.confidence) rp.put(r.edgeId, r);
            }
        }
        edits = new ArrayList<Relationship>(rp.values());
        edges.addAll(edits);
        // EDIT_FAMILY: original + its edits
        TreeMap<String, List<String>> fam = new TreeMap<String, List<String>>();
        for (Relationship r : edits) { List<String> l = fam.get(r.dst); if (l == null) fam.put(r.dst, l = new ArrayList<String>()); l.add(r.src); }
        for (Map.Entry<String, List<String>> e : fam.entrySet()) {
            List<String> ids = new ArrayList<String>(e.getValue()); ids.add(e.getKey());
            Node orig = byId(e.getKey());
            groups.add(group("e", NearGroupKind.EDIT_FAMILY, nodesOf(ids), KeeperSelector.eligible(orig, false) ? orig : null, scanId, "G_KEEPER_ORIGINAL"));
        }
        // BURST (F-02): seeds = NEAR_SCENE components, split by complete linkage to the keeper
        UnionFind su = new UnionFind();
        for (PairCheck pc : scenes) su.union(pc.a, pc.b);
        for (List<String> comp : su.components()) {
            TreeSet<String> rem = new TreeSet<String>(comp);
            while (rem.size() >= 2) {
                Node k = burstKeeper(nodesOf(new ArrayList<String>(rem)));
                List<Node> mem = new ArrayList<Node>(); mem.add(k);
                for (String id : rem) {
                    if (id.equals(k.id())) continue;
                    PairCheck pc = between(id, k.id());
                    Node m = byId(id);
                    if (pc != null && pc.verdict == PairVerdict.NEAR_SCENE && dt(m, k) <= GraphConfig.TIME_WINDOW_MS) mem.add(m);
                }
                for (Node m : mem) rem.remove(m.id());
                if (mem.size() < 2) continue;
                DuplicateGroup g = group("b", NearGroupKind.BURST, mem, KeeperSelector.eligible(k, false) ? k : null, scanId, "G_KEEPER_BURST");
                groups.add(g);
                for (Node m : mem) {
                    if (m == k) continue;
                    long d = dt(m, k);
                    Relationship r = Edges.make(m.id(), k.id(), RelType.VISUALLY_SIMILAR, 0.6 + 0.3 * Math.max(0, Math.min(1, (GraphConfig.TIME_WINDOW_MS - d) / (double) GraphConfig.TIME_WINDOW_MS)),
                            Verification.VERIFIED, m.fp(), k.fp(), scanId);
                    r.verifiedAt = now;
                    Edges.ev(r, "CAPTURE_DT_MS", d);
                    metricsEvidence(r, between(m.id(), k.id()));
                    edges.add(r);
                }
            }
        }
        writeStatus();
    }

    static long dt(Node a, Node b) {
        long x = a.captureTime(), y = b.captureTime();
        return Unknown.knownTime(x) && Unknown.knownTime(y) ? Math.abs(x - y) : Long.MAX_VALUE;
    }

    Node burstKeeper(List<Node> ms) {
        List<Node> s = new ArrayList<Node>(ms);
        Collections.sort(s, ExactGrouper.BY_ID);
        for (Node n : s) { String l = n.f().name.toLowerCase(Locale.ROOT); if (l.contains("_cover") || l.contains("_burst001")) return n; }
        Node best = null, second = null;
        for (Node n : s) {
            double sh = n.img() == null ? Double.NaN : n.img().sharpness;
            if (Double.isNaN(sh)) continue;
            if (best == null || sh > best.img().sharpness) { second = best; best = n; }
            else if (second == null || sh > second.img().sharpness) second = n;
        }
        if (best != null && second != null && best.img().sharpness >= 1.1 * second.img().sharpness) return best;
        Node e = null;
        for (Node n : s) {
            long t = n.captureTime();
            if (e == null || (Unknown.knownTime(t) && (!Unknown.knownTime(e.captureTime()) || t < e.captureTime()))) e = n;
        }
        return e;
    }

    Node pickEligible(List<Node> order) { for (Node n : order) if (KeeperSelector.eligible(n, false)) return n; return null; }

    List<Node> nodesOf(Collection<String> ids) { List<Node> l = new ArrayList<Node>(); for (String id : ids) l.add(byId(id)); Collections.sort(l, ExactGrouper.BY_ID); return l; }

    static DuplicateGroup group(String letter, NearGroupKind kind, List<Node> ms, Node keeper, long scanId, String reason) {
        DuplicateGroup g = new DuplicateGroup();
        g.groupId = "n:" + letter + ":" + ms.get(0).id(); g.kind = kind; g.exact = false; g.createdScanId = scanId;
        long b = 0;
        for (Node n : ms) { g.members.add(n.id()); b += n.size; }
        g.memberBytes = b;
        g.keeperId = keeper == null ? null : keeper.id(); g.keeperFallback = keeper == null;
        if (keeper != null) g.keeperReasons.add(reason);
        g.verification = kind == NearGroupKind.EDIT_FAMILY ? Verification.UNVERIFIED : Verification.VERIFIED;
        return g;
    }

    /** perceptualStatus into FeatureRecord.extra (exact twins share their representative's status); written only on change. */
    void writeStatus() throws StoreException {
        Map<String, Tri> st = new HashMap<String, Tri>(status);
        Map<String, String> repOf = new HashMap<String, String>();
        for (Node n : el) if (n.exactGroup != null) repOf.put(n.exactGroup, n.id());
        long unknown = 0;
        for (Node n : nodes) {
            if (!n.is(Node.HAS_IMAGE)) continue;
            Tri v = st.get(n.id());
            if (v == null && n.exactGroup != null && repOf.containsKey(n.exactGroup)) v = st.get(repOf.get(n.exactGroup));
            if (v == null) v = Tri.UNKNOWN;
            if (v == Tri.UNKNOWN) unknown++;
            String want = v.name();
            if (n.pstat() != v.ordinal() + 1) {   // stored value at snapshot != want (codes: Node.PSTAT_SHIFT)
                FeatureRecord fr = ctx.store.features().get(n.id());
                fr.extra.put(PSTAT, want);
                ctx.store.features().put(fr);
            }
        }
        inc("relationshipsUnknownImages", unknown);
    }

    static final class UnionFind {
        final Map<String, String> p = new TreeMap<String, String>();
        String find(String x) { if (!p.containsKey(x)) p.put(x, x); String r = x; while (!p.get(r).equals(r)) r = p.get(r); p.put(x, r); return r; }
        void union(String a, String b) { String x = find(a), y = find(b); if (!x.equals(y)) { if (x.compareTo(y) < 0) p.put(y, x); else p.put(x, y); } }
        List<List<String>> components() {
            TreeMap<String, List<String>> m = new TreeMap<String, List<String>>();
            for (String k : new ArrayList<String>(p.keySet())) { String r = find(k); List<String> l = m.get(r); if (l == null) m.put(r, l = new ArrayList<String>()); l.add(k); }
            return new ArrayList<List<String>>(m.values());
        }
    }
}
