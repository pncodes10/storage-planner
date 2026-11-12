package com.storageplanner.v2.discovery;

import com.storageplanner.v2.model.*;

import java.util.*;

/** Access modes A–D as per-prefix capabilities (PHASE_1 1.1). */
public final class CapabilityRegistry {
    private final Map<String, List<AccessGrant>> byVolume = new HashMap<String, List<AccessGrant>>();

    public CapabilityRegistry(List<Volume> volumes) {
        for (Volume v : volumes) {
            List<AccessGrant> l = new ArrayList<AccessGrant>();
            if (v.grants != null) for (AccessGrant g : v.grants) if (g.active) l.add(g.copy());
            byVolume.put(v.volumeId, l);
        }
    }

    static int rank(AccessMode m) {
        if (m == null) return 9;
        switch (m) { case B_ALL_FILES: return 0; case C_SAF_TREE: return 1; case A_MEDIASTORE: return 2; default: return 3; }
    }

    /** Active grant with the longest prefix covering path; ties broken B > C > A > D. null = not covered. */
    public AccessGrant best(String volumeId, String path) {
        List<AccessGrant> l = byVolume.get(volumeId);
        if (l == null) return null;
        AccessGrant best = null;
        for (AccessGrant g : l) {
            if (!PathNormalizer.isUnder(path, g.prefix)) continue;
            if (best == null || g.prefix.length() > best.prefix.length()
                    || (g.prefix.length() == best.prefix.length() && rank(g.mode) < rank(best.mode))) best = g;
        }
        return best;
    }

    public boolean canList(String volumeId, String dirPath) {
        AccessGrant g = best(volumeId, dirPath);
        return g != null && g.canList == Tri.TRUE;
    }

    /** Whether a listing of dirPath proves absence of a child file (never under Mode A, F-M2). */
    public boolean authoritativeFor(String volumeId, String dirPath, String extension) {
        AccessGrant g = best(volumeId, dirPath);
        return g != null && g.mode != AccessMode.A_MEDIASTORE && g.canList == Tri.TRUE;
    }

    /** Diff stored grants against current ones, update Volume.grants, return events. */
    public static List<ScanEvent> refresh(List<Volume> volumes, List<AccessGrant> current, long now) {
        List<ScanEvent> ev = new ArrayList<ScanEvent>();
        for (Volume v : volumes) {
            TreeMap<String, AccessGrant> cur = new TreeMap<String, AccessGrant>();
            if (current != null) for (AccessGrant g : current) if (v.volumeId.equals(g.volumeId)) cur.put(g.grantId(), g);
            TreeMap<String, AccessGrant> stored = new TreeMap<String, AccessGrant>();
            if (v.grants == null) v.grants = new ArrayList<AccessGrant>();
            for (AccessGrant g : v.grants) stored.put(g.grantId(), g);
            for (Map.Entry<String, AccessGrant> e : cur.entrySet()) {
                AccessGrant c = e.getValue();
                AccessGrant s = stored.get(e.getKey());
                if (s == null) {
                    AccessGrant n = c.copy();
                    n.active = true; n.firstSeenAt = now; n.lastSeenAt = now; n.revokedAt = Unknown.TIME;
                    stored.put(e.getKey(), n);
                    ev.add(new ScanEvent(now, "D_GRANT_ADDED", v.volumeId, c.prefix, e.getKey()));
                    continue;
                }
                if (!s.active) {
                    s.active = true; s.revokedAt = Unknown.TIME;
                    ev.add(new ScanEvent(now, "D_GRANT_RESTORED", v.volumeId, s.prefix, e.getKey()));
                }
                String diff = diff(s, c);
                if (diff != null) {
                    s.canList = c.canList; s.canReadMetadata = c.canReadMetadata; s.canReadContent = c.canReadContent;
                    s.canWrite = c.canWrite; s.canDelete = c.canDelete;
                    s.mediaTypes = c.mediaTypes == null ? null : new ArrayList<String>(c.mediaTypes);
                    ev.add(new ScanEvent(now, "D_GRANT_CHANGED", v.volumeId, s.prefix, e.getKey() + " " + diff));
                }
                s.lastSeenAt = now;
            }
            for (AccessGrant s : stored.values()) {
                if (s.active && !cur.containsKey(s.grantId())) {
                    s.active = false; s.revokedAt = now;
                    ev.add(new ScanEvent(now, "D_GRANT_REVOKED", v.volumeId, s.prefix, s.grantId()));
                }
            }
            v.grants = new ArrayList<AccessGrant>(stored.values());
        }
        return ev;
    }

    /** Comma-separated names of differing flags, or null if equal. */
    static String diff(AccessGrant a, AccessGrant b) {
        StringBuilder d = new StringBuilder();
        if (a.canList != b.canList) d.append("canList,");
        if (a.canReadMetadata != b.canReadMetadata) d.append("canReadMetadata,");
        if (a.canReadContent != b.canReadContent) d.append("canReadContent,");
        if (a.canWrite != b.canWrite) d.append("canWrite,");
        if (a.canDelete != b.canDelete) d.append("canDelete,");
        if (!sameTypes(a.mediaTypes, b.mediaTypes)) d.append("mediaTypes,");
        return d.length() == 0 ? null : d.substring(0, d.length() - 1);
    }

    static boolean sameTypes(List<String> a, List<String> b) {
        if (a == null || b == null) return a == b;
        return new TreeSet<String>(a).equals(new TreeSet<String>(b));
    }

    /** Same grant ids with the same flags. */
    public static boolean sameGrants(List<AccessGrant> a, List<AccessGrant> b) {
        Map<String, AccessGrant> ma = new HashMap<String, AccessGrant>(), mb = new HashMap<String, AccessGrant>();
        if (a != null) for (AccessGrant g : a) ma.put(g.grantId(), g);
        if (b != null) for (AccessGrant g : b) mb.put(g.grantId(), g);
        if (!ma.keySet().equals(mb.keySet())) return false;
        for (String k : ma.keySet()) if (diff(ma.get(k), mb.get(k)) != null) return false;
        return true;
    }
}
