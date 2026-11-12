package com.storageplanner.v2.plan;

import com.storageplanner.v2.device.InstalledApp;
import com.storageplanner.v2.model.*;

import java.util.*;

/**
 * PHASE_5 7: a plan is STALE when any item's file (or any file it requires) changed identity (fingerprint, presence), its rec would
 * change tier or primary reason, or an app subject's versionCode changed or its cache/data bytes moved by more than 20 %.
 * A STALE plan is never executed (V1). Writes {@code state = "STALE"} and the reasons.
 */
public final class PlanStaleness {
    private PlanStaleness() {}

    /** Records fingerprints of every item file and required file (call when the plan is made). */
    public static void stamp(ActionPlan p, Map<String, FileRecord> files, Map<String, Recommendation> recs) {
        for (ActionPlan.PlanItem it : p.items) {
            for (String id : it.fileIds) { FileRecord f = files.get(id); if (f != null) it.fps.put(id, f.fingerprint()); }
            for (String id : it.requires) { FileRecord f = files.get(id); if (f != null) it.fps.put(id, f.fingerprint()); }
            Recommendation r = recs == null ? null : recs.get(it.recId);
            if (r != null && r.reasonParams.containsKey("appStats")) it.appStats = r.reasonParams.get("appStats");
        }
    }
    /**
     * @param current current file records by fileId (all volumes)
     * @param currentRecs current recommendations keyed {@link #key}; null skips the tier/reason check
     * @param apps current installed apps by package; null skips app checks
     */
    public static List<String> check(ActionPlan p, Map<String, FileRecord> current, Map<String, Recommendation> currentRecs, Map<String, InstalledApp> apps) {
        List<String> why = new ArrayList<String>();
        for (ActionPlan.PlanItem it : p.items) {
            for (Map.Entry<String, Fingerprint> e : it.fps.entrySet()) {
                FileRecord f = current.get(e.getKey());
                if (f == null || f.presence != Presence.PRESENT) why.add(it.unitId + ": file " + e.getKey() + " gone or unverifiable");
                else if (!e.getValue().sameAs(f.fingerprint())) why.add(it.unitId + ": file " + (f.path == null ? e.getKey() : f.path) + " changed");
            }
            if (currentRecs != null) {
                Recommendation r = currentRecs.get(key(it.unitId.startsWith("bundle:") ? it.unitId.substring(7) : it.unitId, it.action));
                if (r == null || r.tier != it.tier || r.reasonCodes.isEmpty() || !r.reasonCodes.get(0).equals(it.code))
                    why.add(it.unitId + ": recommendation changed");
            }
            if (apps != null && it.appStats != null) {
                String pkg = it.unitId.substring(it.unitId.indexOf(':') + 1);
                InstalledApp a = apps.get(pkg);
                if (a == null) why.add(it.unitId + ": app gone");
                else {
                    Map<String, Long> old = parse(it.appStats);
                    if (!Long.valueOf(a.versionCode).equals(old.get("v"))) why.add(it.unitId + ": app version changed");
                    else if (moved(old.get("c"), a.cacheBytes) || moved(old.get("d"), a.dataBytes)) why.add(it.unitId + ": app storage moved > 20 %");
                }
            }
        }
        if (!why.isEmpty()) { p.state = "STALE"; p.staleReasons.clear(); p.staleReasons.addAll(why); }
        return why;
    }
    public static String key(String subject, Action a) { return subject + "|" + a; }
    static boolean moved(Long was, long now) {
        if (was == null) return true;
        if (was < 0 || now < 0) return was != now;
        return Math.abs(now - was) > 0.2 * Math.max(1, was);
    }
    static Map<String, Long> parse(String s) {
        Map<String, Long> m = new HashMap<String, Long>();
        for (String kv : s.split(",")) { int i = kv.indexOf('='); if (i > 0) try { m.put(kv.substring(0, i), Long.parseLong(kv.substring(i + 1))); } catch (NumberFormatException e) { /* ignore */ } }
        return m;
    }
}
