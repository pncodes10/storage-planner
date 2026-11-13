package com.storageplanner.v2.graph;

import com.storageplanner.v2.model.*;

import java.util.*;

/** Keeper eligibility and the exact-group keeper order (PHASE_3 8.1, lead ruling on REVIEW_2 F-06). */
final class KeeperSelector {
    private KeeperSelector() {}

    static final Set<String> USER_ORGANISED = new HashSet<String>(Arrays.asList("camera", "screenshot", "document", "music", "movies", "pictures"));
    static final Set<String> TEMPISH = new HashSet<String>(Arrays.asList("temp", "ext_cache", "thumbnails", "trash", "wa_status"));

    static boolean eligible(Node n, boolean needHash) {
        FileRecord f = n.f();
        if (f.presence != Presence.PRESENT || f.readable == Tri.FALSE || f.extractionStatus == ExtractionStatus.UNREADABLE) return false;
        if (n.alias() || n.vol == null || !n.vol.mounted) return false;
        if (n.fr() != null && n.fr().fp != null && !n.fr().fp.sameAs(n.fp())) return false;
        return !needHash || n.sha() != null;
    }

    static int locRank(Node n) {
        String a = n.f().area == null ? "" : n.f().area, p = n.lowerPath();
        if (TEMPISH.contains(a) || n.trashed() || p.contains("/.statuses/") || p.contains("/.trashed-")) return 4;
        if (a.startsWith("wa_") || a.equals("telegram_media")) return 3;
        if (a.equals("download")) return 2;
        if (USER_ORGANISED.contains(a) || p.startsWith("documents/")) return 0;
        return 1;
    }

    static int nameRank(Node n) { return NameStem.generic(n.f().name) ? 1 : 0; }

    static final Comparator<Node> EXACT_ORDER = new Comparator<Node>() {
        public int compare(Node x, Node y) {
            int c = Boolean.compare(y.f().protection != Protection.NONE, x.f().protection != Protection.NONE); if (c != 0) return c;
            c = Boolean.compare(x.trashed(), y.trashed()); if (c != 0) return c;
            c = Integer.compare(locRank(x), locRank(y)); if (c != 0) return c;
            c = Integer.compare(nameRank(x), nameRank(y)); if (c != 0) return c;
            boolean kx = Unknown.knownTime(x.f().modifiedAt), ky = Unknown.knownTime(y.f().modifiedAt);
            if (kx != ky) return kx ? -1 : 1;
            if (kx && x.f().modifiedAt != y.f().modifiedAt) return x.f().modifiedAt < y.f().modifiedAt ? -1 : 1;
            c = com.storageplanner.v2.util.Text.nfc(x.f().path).compareTo(com.storageplanner.v2.util.Text.nfc(y.f().path)); if (c != 0) return c;
            return x.id().compareTo(y.id());
        }
    };

    /** Sets keeperId / keeperReasons / keeperFallback of an exact group. */
    static void exact(DuplicateGroup g, List<Node> members) {
        List<Node> el = new ArrayList<Node>();
        for (Node n : members) if (eligible(n, true)) el.add(n);
        g.keeperReasons = new ArrayList<String>();
        if (el.isEmpty()) { g.keeperId = null; g.keeperFallback = true; return; }
        Collections.sort(el, EXACT_ORDER);
        Node k = el.get(0);
        g.keeperId = k.id(); g.keeperFallback = false;
        if (el.size() > 1) {
            Node r = el.get(1);
            if ((k.f().protection != Protection.NONE) != (r.f().protection != Protection.NONE)) g.keeperReasons.add("G_KEEPER_PROTECTED");
            else if (locRank(k) != locRank(r) || k.trashed() != r.trashed()) g.keeperReasons.add("G_KEEPER_CANONICAL");
            else if (nameRank(k) != nameRank(r)) g.keeperReasons.add("G_KEEPER_NAME");
            else if (Unknown.knownTime(k.f().modifiedAt) && k.f().modifiedAt != r.f().modifiedAt) g.keeperReasons.add("G_KEEPER_OLDEST");
            else g.keeperReasons.add("G_KEEPER_TIEBREAK");
        }
    }
}
