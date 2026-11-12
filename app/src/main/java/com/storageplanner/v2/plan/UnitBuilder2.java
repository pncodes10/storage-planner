package com.storageplanner.v2.plan;

import com.storageplanner.v2.model.*;
import com.storageplanner.v2.risk.ExpectedLoss;

import java.util.*;

/**
 * PHASE_5 3: recommendations -> plan units with their mutual exclusion and dependency constraints.
 * One unit per subject (file, bundle, cache:pkg, app:pkg); a file's removal and transform recs are exclusive options of one unit;
 * keepers, REVIEW and PROTECTED never get an option; hashPending deletes and unknown-byte options are dropped (counted);
 * bundles are all-or-nothing; app-vs-file overlaps are resolved so no byte is counted twice.
 */
public final class UnitBuilder2 {
    private UnitBuilder2() {}

    public static final class Input {
        public List<Recommendation> recs = new ArrayList<Recommendation>();
        public Map<String, FileRecord> files = new HashMap<String, FileRecord>();
        /** Keepers of exact / content groups (never removed, PHASE_4 HC13). */
        public Set<String> keepers = new HashSet<String>();
        /** Bundle primary -> all member fileIds (incl. the primary): split APK sets, primary + sidecars. */
        public Map<String, List<String>> bundles = new TreeMap<String, List<String>>();
    }
    public static final class Built {
        public List<PlanUnit> units = new ArrayList<PlanUnit>();
        public List<PlanUnit> displayOnly = new ArrayList<PlanUnit>();
        public int hashPending, unknownBytes, keeperOptionsDropped, bundlesDropped;
        public Map<String, Recommendation> recById = new HashMap<String, Recommendation>();
    }

    public static boolean plannableTier(Tier t) { return t == Tier.SAFE || t == Tier.NO_LOSS || t == Tier.YOUR_CALL; }
    static boolean plannableAction(Action a) {
        return a == Action.DELETE || a == Action.COMPRESS || a == Action.CLEAR_APP_CACHE || a == Action.ARCHIVE_APP || a == Action.UNINSTALL_APP;
    }
    public static boolean hashPending(Recommendation r) { return "true".equals(r.reasonParams.get("hashPending")) || "pending".equals(r.reasonParams.get("hash")); }
    public static double cost(Recommendation r) {
        double el = (Double.isNaN(r.expectedLoss) ? 0 : r.expectedLoss) + (Double.isNaN(r.uncertainty) ? 0 : r.uncertainty);
        return el + ExpectedLoss.executionCost(r.proposedAction, r.expectedSavingBytes);
    }
    public static double utility(Recommendation r) { return ExpectedLoss.bn(r.expectedSavingBytes) - cost(r); }

    public static Built build(Input in) {
        Built out = new Built();
        Map<String, List<Recommendation>> bySubject = new TreeMap<String, List<Recommendation>>();
        for (Recommendation r : in.recs) {
            out.recById.put(r.recommendationId, r);
            if (!plannableTier(r.tier) || !plannableAction(r.proposedAction)) continue;
            if (r.proposedAction == Action.DELETE && hashPending(r)) { out.hashPending++; continue; }
            if (r.expectedSavingBytes <= 0) { out.unknownBytes++; continue; }
            if (r.tier == Tier.YOUR_CALL && Double.isNaN(r.expectedLoss)) { out.unknownBytes++; continue; }
            if (in.keepers.contains(r.fileId) && r.proposedAction.removal()) { out.keeperOptionsDropped++; continue; }   // HC13 re-check
            List<Recommendation> l = bySubject.get(r.fileId);
            if (l == null) bySubject.put(r.fileId, l = new ArrayList<Recommendation>());
            l.add(r);
        }
        // bundles: one DELETE for all members together; dropped entirely if any member lacks a plannable DELETE
        Set<String> inBundle = new HashSet<String>();
        List<PlanUnit> bundleUnits = new ArrayList<PlanUnit>();
        for (Map.Entry<String, List<String>> b : in.bundles.entrySet()) {
            inBundle.addAll(b.getValue());
            Tier t = Tier.SAFE; long bytes = 0; double cost = 0; Recommendation prim = null; boolean ok = true;
            Set<String> req = new TreeSet<String>();
            for (String m : b.getValue()) {
                Recommendation d = null;
                List<Recommendation> l = bySubject.get(m);
                if (l != null) for (Recommendation r : l) if (r.proposedAction == Action.DELETE) d = r;
                if (d == null) { ok = false; break; }
                if (Tier.rank(d.tier) > Tier.rank(t)) t = d.tier;
                bytes += d.expectedSavingBytes; cost += cost(d); req.addAll(d.dependencyIds);
                if (m.equals(b.getKey())) prim = d;
            }
            if (!ok || prim == null) { out.bundlesDropped++; continue; }
            PlanUnit u = new PlanUnit(); u.id = "bundle:" + b.getKey(); u.subject = subjectOf(in, b.getKey());
            u.fileIds.addAll(b.getValue()); Collections.sort(u.fileIds);
            req.removeAll(u.fileIds); u.requires.addAll(req);
            u.add(Action.DELETE, t, bytes, cost, ExpectedLoss.bn(bytes) - cost, prim.recommendationId, prim.reasonCodes.isEmpty() ? null : prim.reasonCodes.get(0));
            bundleUnits.add(u);
        }
        for (Map.Entry<String, List<Recommendation>> e : bySubject.entrySet()) {
            if (inBundle.contains(e.getKey())) continue;
            PlanUnit u = new PlanUnit(); u.id = e.getKey(); u.subject = subjectOf(in, e.getKey());
            if (!e.getKey().startsWith("cache:") && !e.getKey().startsWith("app:")) u.fileIds.add(e.getKey());
            for (Recommendation r : e.getValue()) {
                u.add(r.proposedAction, r.tier, r.expectedSavingBytes, cost(r), utility(r), r.recommendationId, r.reasonCodes.isEmpty() ? null : r.reasonCodes.get(0));
                if (r.proposedAction.removal()) u.requires.addAll(r.dependencyIds);
            }
            u.requires.removeAll(u.fileIds);
            out.units.add(u);
        }
        out.units.addAll(bundleUnits);
        resolveAppConflicts(out);
        Collections.sort(out.units, new Comparator<PlanUnit>() { public int compare(PlanUnit a, PlanUnit b) { return a.id.compareTo(b.id); } });
        return out;
    }
    static String subjectOf(Input in, String id) {
        if (id.startsWith("cache:") || id.startsWith("app:")) return id;
        FileRecord f = in.files.get(id); return f == null || f.path == null ? id : f.path;
    }
    static Tier best(PlanUnit u) { Tier t = null; for (PlanUnit.Option2 o : u.options) if (t == null || Tier.rank(o.tier) < Tier.rank(t)) t = o.tier; return t; }

    /** PHASE_5 3 "app vs file subjects": the app unit wins at equal-or-lower tier (file unit display-only); otherwise both stay, mutually exclusive. */
    static void resolveAppConflicts(Built out) {
        List<PlanUnit> fileUnits = new ArrayList<PlanUnit>(), apps = new ArrayList<PlanUnit>();
        for (PlanUnit u : out.units) (u.id.startsWith("cache:") || u.id.startsWith("app:") ? apps : fileUnits).add(u);
        Set<PlanUnit> losers = new HashSet<PlanUnit>();
        for (PlanUnit a : apps) {
            boolean cache = a.id.startsWith("cache:"); String pkg = a.id.substring(a.id.indexOf(':') + 1);
            for (PlanUnit.Option2 o : a.options) {
                if (!(cache ? o.action == Action.CLEAR_APP_CACHE : o.action == Action.UNINSTALL_APP)) continue;
                for (PlanUnit f : fileUnits) {
                    if (losers.contains(f) || !overlaps(cache, pkg, f)) continue;
                    Tier ft = best(f);
                    if (Tier.rank(o.tier) <= Tier.rank(ft)) { losers.add(f); f.includedIn = a.id; }
                    else { o.conflicts.add(f.id); for (PlanUnit.Option2 fo : f.options) fo.conflicts.add(a.id); }
                }
            }
        }
        out.units.removeAll(losers); out.displayOnly.addAll(losers);
    }
    static boolean overlaps(boolean cache, String pkg, PlanUnit f) {
        String p = f.subject == null ? "" : f.subject.toLowerCase(Locale.ROOT), k = pkg.toLowerCase(Locale.ROOT);
        if (cache) {
            if (!p.startsWith("android/data/" + k + "/cache/")) return false;
            for (PlanUnit.Option2 o : f.options) if ("S_APP_CACHE_FILE".equals(o.code)) return true;
            return false;
        }
        return p.startsWith("android/data/" + k + "/") || p.startsWith("android/media/" + k + "/") || p.startsWith("android/obb/" + k + "/");
    }
}
