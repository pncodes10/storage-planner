package com.storageplanner.v2.graph;

import com.storageplanner.v2.model.*;

import java.util.*;

/** G2 (PHASE_3 2.3): physical distinctness and alias sets. */
public final class AliasResolver {
    private AliasResolver() {}

    public static Distinctness distinct(FileRecord x, FileRecord y, Volume vx, Volume vy) {
        if (x.physicalKey != null && y.physicalKey != null) return x.physicalKey.equals(y.physicalKey) ? Distinctness.SAME_FILE : Distinctness.PROVEN_DISTINCT;
        if (vx != null && vy != null && !vx.volumeId.equals(vy.volumeId) && vx.fsUuid != null && vy.fsUuid != null && !vx.fsUuid.equals(vy.fsUuid)
                && !overlapKind(vx.kind) && !overlapKind(vy.kind)) return Distinctness.PROVEN_DISTINCT;
        return Distinctness.UNKNOWN;
    }
    static boolean overlapKind(VolumeKind k) { return k == VolumeKind.SAF_TREE || k == VolumeKind.DIRECTORY; }

    static int modeRank(AccessMode m) {
        if (m == null) return 4;
        switch (m) { case B_ALL_FILES: return 0; case D_APP_PRIVATE: return 1; case A_MEDIASTORE: return 2; default: return 3; }
    }

    /**
     * Sets n.canonical for every node in an alias set (null = itself). {@code pk} = [hash64(physicalKey), node index] pairs of
     * the nodes that have a physicalKey (PHASE_S_STORE 4 item 3: no per-file key strings are held); equal hashes are confirmed
     * by loading the records and grouping by the real key. Returns canonical fileId -> alias fileIds (non-canonical).
     */
    static Map<String, List<String>> resolve(List<Node> nodes, long[] pk, int npk) {
        com.storageplanner.v2.util.PairSort.pairs(pk, npk);
        Map<String, List<String>> out = new TreeMap<String, List<String>>();
        for (int i = 0; i < npk; ) {
            int j = i + 1;
            while (j < npk && pk[2 * j] == pk[2 * i]) j++;
            if (j - i >= 2) {
                Map<String, List<Node>> byKey = new TreeMap<String, List<Node>>();
                for (int q = i; q < j; q++) {      // ascending node index = ascending fileId, as the old pass added them
                    Node n = nodes.get((int) pk[2 * q + 1]);
                    String k = n.f().physicalKey;
                    List<Node> l = byKey.get(k);
                    if (l == null) byKey.put(k, l = new ArrayList<Node>());
                    l.add(n);
                }
                for (List<Node> l : byKey.values()) group(l, out);
            }
            i = j;
        }
        return out;
    }

    private static void group(List<Node> l, Map<String, List<String>> out) {
        if (l.size() < 2) return;
        Node best = null;
        for (Node n : l) {
            if (best == null) { best = n; continue; }
            int c = Integer.compare(modeRank(n.f().accessMode), modeRank(best.f().accessMode));
            if (c < 0 || (c == 0 && n.id().compareTo(best.id()) < 0)) best = n;
        }
        List<String> al = new ArrayList<String>();
        for (Node n : l) { n.canonical = best.id().equals(n.id()) ? null : best.id(); if (n != best) al.add(n.id()); }
        Collections.sort(al);
        out.put(best.id(), al);
    }
}
