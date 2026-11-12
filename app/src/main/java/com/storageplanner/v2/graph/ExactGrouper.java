package com.storageplanner.v2.graph;

import com.storageplanner.v2.model.*;

import java.util.*;

/** Stage 1 (PHASE_3 3): exact groups from current SHA-256, G2 verification, star above 64 members. */
final class ExactGrouper {
    private ExactGrouper() {}

    static int hashUnknown;

    static void run(List<Node> nodes, long scanId, List<Relationship> edges, List<DuplicateGroup> groups) {
        Map<String, List<Node>> bySha = new TreeMap<String, List<Node>>();
        for (Node n : nodes) {
            if (n.alias()) continue;
            String s = n.sha();
            if (s == null) continue;
            List<Node> l = bySha.get(s);
            if (l == null) bySha.put(s, l = new ArrayList<Node>());
            l.add(n);
        }
        for (Map.Entry<String, List<Node>> e : bySha.entrySet()) {
            List<Node> m = e.getValue();
            if (m.size() < 2) continue;
            Collections.sort(m, BY_ID);
            DuplicateGroup g = new DuplicateGroup();
            g.groupId = "x:" + e.getKey(); g.exact = true; g.kind = NearGroupKind.EXACT; g.createdScanId = scanId;
            long bytes = 0;
            for (Node n : m) { g.members.add(n.id()); bytes += n.f.sizeBytes; n.exactGroup = g.groupId; }
            g.memberBytes = bytes;
            KeeperSelector.exact(g, m);
            boolean allVerified = true;
            boolean star = m.size() > GraphConfig.MAX_EXACT_EDGES_GROUP;
            Node hub = null;
            if (star) for (Node n : m) if (n.id().equals(g.keeperId)) hub = n;
            if (star && hub == null) hub = m.get(0);
            for (int i = 0; i < m.size(); i++) for (int j = i + 1; j < m.size(); j++) {
                Node x = m.get(i), y = m.get(j);
                Distinctness d = AliasResolver.distinct(x.f, y.f, x.vol, y.vol);
                boolean ver = d == Distinctness.PROVEN_DISTINCT;
                if (!ver) allVerified = false;
                if (star && x != hub && y != hub) continue;
                Relationship r = Edges.make(x.id(), y.id(), RelType.EXACT_DUPLICATE, ver ? 1.0 : 0.9,
                        ver ? Verification.VERIFIED : Verification.UNVERIFIED, x.fp, y.fp, scanId);
                Edges.ev(r, "SHA256_EQUAL", e.getKey().substring(0, 16));
                Edges.ev(r, x.f.physicalKey != null && y.f.physicalKey != null ? "PHYSKEY_DISTINCT" : ver ? "VOLUMES_DISJOINT" : "PHYSKEY_UNKNOWN", null);
                if (star) Edges.ev(r, "STAR", null);
                edges.add(r);
            }
            g.verification = allVerified ? Verification.VERIFIED : Verification.UNVERIFIED;
            groups.add(g);
        }
    }

    static final Comparator<Node> BY_ID = new Comparator<Node>() { public int compare(Node a, Node b) { return a.id().compareTo(b.id()); } };
}
