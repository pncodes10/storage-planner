package com.storageplanner.v2.graph;

import com.storageplanner.v2.model.*;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** PHASE_3 7 as cut by REVIEW_2 F-15: CompanionLinker C2 (split APK) and C3 (OBB), WhatsApp chat-backup VERSION_OF. */
final class CompanionLinker {
    private CompanionLinker() {}
    static final Pattern SPLIT = Pattern.compile("^(split_.*|config\\..*)\\.apk$", Pattern.CASE_INSENSITIVE);
    static final Pattern OBB = Pattern.compile("^android/obb/([^/]+)/[^/]+\\.obb$");
    static final Pattern MSG = Pattern.compile("^msgstore-(\\d{4}-\\d{2}-\\d{2})\\.\\d+\\.db\\.(crypt\\d+)$");

    static String dir(String p) { int i = p.lastIndexOf('/'); return i < 0 ? "" : p.substring(0, i); }
    static String pkg(Node n) { return n.fr == null || n.fr.apk == null ? null : n.fr.apk.packageName; }

    static void run(List<Node> nodes, List<Relationship> edges, long scanId) {
        Map<String, List<Node>> basesByPkg = new TreeMap<String, List<Node>>();
        Map<String, Node> byPath = new HashMap<String, Node>();
        for (Node n : nodes) {
            if (n.alias()) continue;
            byPath.put(n.f.volumeId + ":" + n.f.path, n);
            String p = pkg(n);
            if (p != null && !SPLIT.matcher(n.f.name).matches()) {
                List<Node> l = basesByPkg.get(p);
                if (l == null) basesByPkg.put(p, l = new ArrayList<Node>());
                l.add(n);
            }
        }
        for (Node n : nodes) {
            if (n.alias()) continue;
            String p = pkg(n);
            if (p != null && SPLIT.matcher(n.f.name).matches() && basesByPkg.containsKey(p)) {
                for (Node b : basesByPkg.get(p)) if (dir(b.f.path).equals(dir(n.f.path))) {
                    Relationship r = Edges.make(n.id(), b.id(), RelType.COMPANION_OF, 0.95, Verification.VERIFIED, n.fp, b.fp, scanId);
                    Edges.ev(r, "COMPANION_RULE", "C2"); Edges.ev(r, "APK_PKG", p); edges.add(r);
                }
            }
            Matcher m = OBB.matcher(n.f.path.toLowerCase(Locale.ROOT));
            if (m.matches() && basesByPkg.containsKey(n.f.path.split("/")[2])) {
                for (Node b : basesByPkg.get(n.f.path.split("/")[2])) {
                    Relationship r = Edges.make(n.id(), b.id(), RelType.COMPANION_OF, 0.95, Verification.VERIFIED, n.fp, b.fp, scanId);
                    Edges.ev(r, "COMPANION_RULE", "C3"); Edges.ev(r, "APK_PKG", b.fr.apk.packageName); edges.add(r);
                }
            }
            Matcher w = MSG.matcher(n.f.name.toLowerCase(Locale.ROOT));
            if (w.matches()) {
                String cur = dir(n.f.path) + (dir(n.f.path).isEmpty() ? "" : "/") + "msgstore.db." + w.group(2);
                Node c = byPath.get(n.f.volumeId + ":" + cur);
                if (c != null) {
                    Relationship r = Edges.make(n.id(), c.id(), RelType.VERSION_OF, 0.8, Verification.UNVERIFIED, n.fp, c.fp, scanId);
                    Edges.ev(r, "BACKUP_NAME_DATE", w.group(1)); edges.add(r);
                }
            }
        }
    }
}
