package com.storageplanner.v2.plan;

import com.storageplanner.v2.device.Availability;
import com.storageplanner.v2.device.InstalledApp;
import com.storageplanner.v2.device.InstalledApps;
import com.storageplanner.v2.model.*;
import com.storageplanner.v2.util.Hashing;

import java.util.*;

/**
 * PHASE_5 6.1 (A14): app caches and idle apps from the installed-apps interface, with the classic UnitBuilder thresholds.
 * lastUsed is a measured usage fact (UsageStatsManager), not a file age (H10). Any unknown input -> no recommendation.
 * {@code sdk} is the platform SDK (-1 unknown); passed in rather than added to InstalledApps (PHASE_5_NOTES D-3).
 */
public final class AppSubjects {
    private AppSubjects() {}
    public static final long DAY = 86400000L, ARCHIVE_IDLE = 60 * DAY, UNINSTALL_IDLE = 120 * DAY;
    /** Uncalibrated: expected loss of uninstalling (app data is removed with the app; PHASE_4 13). */
    public static final double UNINSTALL_EL_MID = 0.05, UNINSTALL_UNCERTAINTY = 0.05;

    public static String stats(InstalledApp a) { return "v=" + a.versionCode + ",c=" + a.cacheBytes + ",d=" + a.dataBytes; }
    static String idle(long ms) { long d = ms / DAY; return d < 60 ? d + " days" : d < 730 ? (d / 30) + " months" : (d / 365) + " years"; }

    public static List<Recommendation> recs(InstalledApps apps, long now, int sdk, long scanId, int planVersion) {
        List<Recommendation> out = new ArrayList<Recommendation>();
        if (apps == null || apps.availability() == Availability.UNAVAILABLE) return out;
        List<InstalledApp> l = new ArrayList<InstalledApp>(apps.list());
        Collections.sort(l, new Comparator<InstalledApp>() { public int compare(InstalledApp a, InstalledApp b) { return a.packageName.compareTo(b.packageName); } });
        for (InstalledApp a : l) {
            String name = a.label != null ? a.label : a.packageName;
            if (a.cacheBytes > 0) {
                Recommendation r = rec("cache:" + a.packageName, Action.CLEAR_APP_CACHE, Tier.SAFE, "S_APP_CACHE", a.cacheBytes, 0, 0, scanId, planVersion);
                r.reasonParams.put("app", name); r.reasonParams.put("bytes", Fmt.bytes(a.cacheBytes)); r.reasonParams.put("appStats", stats(a));
                out.add(r);
            }
            boolean base = a.system == Tri.FALSE && a.hasLauncher == Tri.TRUE && a.essential != Tri.TRUE && a.lastUsed != Long.MIN_VALUE && sdk >= 0;
            if (!base) continue;
            long idle = now - a.lastUsed;
            if (sdk >= 35 && idle >= ARCHIVE_IDLE && a.codeBytes > 0) {
                Recommendation r = rec("app:" + a.packageName, Action.ARCHIVE_APP, Tier.NO_LOSS, "N_APP_ARCHIVE", a.codeBytes, 0, 0, scanId, planVersion);
                r.reasonParams.put("app", name); r.reasonParams.put("idle", idle(idle)); r.reasonParams.put("appStats", stats(a));
                out.add(r);
            }
            if (idle >= UNINSTALL_IDLE && (a.game == Tri.TRUE || sdk < 35) && a.codeBytes >= 0 && a.dataBytes >= 0 && a.codeBytes + a.dataBytes > 0) {
                long b = a.codeBytes + a.dataBytes;
                Recommendation r = rec("app:" + a.packageName, Action.UNINSTALL_APP, Tier.YOUR_CALL, "Y_IDLE_APP", b, UNINSTALL_EL_MID, UNINSTALL_UNCERTAINTY, scanId, planVersion);
                r.reasonParams.put("app", name); r.reasonParams.put("idle", idle(idle)); r.reasonParams.put("bytes", Fmt.bytes(b)); r.reasonParams.put("appStats", stats(a));
                out.add(r);
            }
        }
        return out;
    }
    static Recommendation rec(String subject, Action act, Tier t, String code, long bytes, double el, double unc, long scanId, int planVersion) {
        Recommendation r = new Recommendation();
        r.fileId = subject; r.proposedAction = act; r.tier = t; r.reasonCodes.add(code); r.expectedSavingBytes = bytes;
        r.expectedLoss = el; r.uncertainty = unc; r.confidence = 1.0; r.planVersion = planVersion; r.createdScanId = scanId;
        r.recommendationId = Hashing.sha256Hex(subject + "\0" + act.name() + "\0" + planVersion).substring(0, 24);
        r.evidenceRefs.add("app:" + subject.substring(subject.indexOf(':') + 1));
        return r;
    }
}
