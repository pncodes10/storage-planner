package com.storageplanner.v2test;

import com.storageplanner.v2.device.*;
import com.storageplanner.v2.model.*;
import com.storageplanner.v2.plan.*;

import java.util.*;

/** Ruling A25 (slice 0) + PHASE_5 HashOnDemandTest / PlanStalenessTest / AppSubjects cases. */
public final class YourCallEligibilityTest {
    static final long MB = 1024L * 1024;
    public static int run(String[] args) {
        for (PresentationPolicy pol : new PresentationPolicy[]{PresentationPolicy.STANDARD, PresentationPolicy.AGGRESSIVE}) {
            T.section("A25 " + pol.name);
            String prev = null;
            for (long size : new long[]{2000, 40000, 150000, 2 * MB, 30 * MB, 900 * MB}) {     // size never decides eligibility
                RiskFx x = new RiskFx();
                FileRecord o = x.add("DCIM/Camera/IMG_1.jpg", "camera", "jpeg", size * 3), c = x.add("WhatsApp/Media/WhatsApp Images/IMG-20210101-WA0003.jpg", "wa_image", "jpeg", size);
                x.ce(o, c, RelType.COMPRESSED_FROM, 0.95);
                Verdict v = x.v(c, pol);
                String sig = v.tier + "/" + RiskFx.code(v);
                T.eq("chat copy of " + size + " B is YOUR_CALL", "YOUR_CALL/Y_CHAT_COPY", sig);
                if (size <= 150000) T.check("small copy has utility <= 0 yet is recommended", v.assessment.utility.get(Action.DELETE) <= 0);
                if (prev != null) T.eq("same tier at every size", prev, sig);
                prev = sig;
            }
            RiskFx x = new RiskFx();
            FileRecord a2 = x.file("Download/old.apk", "download", "apk", 900000); x.apk(a2, "com.inst", InstallState.INSTALLED_NEWER, Tri.TRUE); x.add(a2);
            T.eq("small older installer (same signer) YOUR_CALL", "Y_OLDER_INSTALLER", RiskFx.code(x.v(a2, pol)));
            x = new RiskFx();
            FileRecord o = x.add("DCIM/Camera/IMG_2.jpg", "camera", "jpeg", 3 * MB), c = x.file("WhatsApp/Media/WhatsApp Images/IMG-20200101-WA0001.jpg", "wa_image", "jpeg", 100000);
            c.modifiedAt = o.modifiedAt - 30 * RiskFx.DAY; x.add(c);
            x.ce(o, c, RelType.COMPRESSED_FROM, 0.95);
            Verdict v = x.v(c, pol);
            T.check("copy older than its original is never YOUR_CALL", v.tier != Tier.YOUR_CALL && v.tier != Tier.SAFE && v.tier != Tier.NO_LOSS);
            if (v.tier != null) T.eq("... it is REVIEW R_UNCERTAIN_COPY", "R_UNCERTAIN_COPY", RiskFx.code(v));
            x = new RiskFx();
            FileRecord e1 = x.add("Pictures/Snapseed/IMG_3_text.jpg", "pictures", "jpeg", 3 * MB), e2 = x.add("Pictures/Edited/IMG_3_filtered.jpg", "pictures", "jpeg", 3 * MB);
            x.edge(e1.fileId, e2.fileId, RelType.VISUALLY_SIMILAR, Verification.VERIFIED, 0.8); x.group(NearGroupKind.BURST, e1, e2);
            T.check("burst of two edits is never YOUR_CALL", x.v(e2, pol).tier != Tier.YOUR_CALL);
        }

        T.section("A25 plan inclusion by utility");
        PlanFx px = new PlanFx();
        Recommendation small = px.del(px.file("WhatsApp/a.jpg", 100000), Tier.YOUR_CALL, "Y_CHAT_COPY", 0.03);
        ActionPlan p = px.plan(50 * 1000000L);
        T.check("negative-utility YOUR_CALL rec exists but is not planned", UnitBuilder2.utility(small) <= 0 && PlanFx.tierBytes(p, Tier.YOUR_CALL) == 0 && !p.reachesTarget);

        T.section("hash on demand");
        PlanFx h = new PlanFx();
        FileRecord f1 = h.file("Download/a.bin", 10), f2 = h.file("Download/b.bin", 1000), f3 = h.file("Download/c.bin", 20);
        f1.sha256 = null; f2.sha256 = null;
        Recommendation r1 = h.del(f1, Tier.NO_LOSS, "N_EXACT_DUPLICATE", 0), r2 = h.del(f2, Tier.YOUR_CALL, "Y_CHAT_COPY", 0.001), r3 = h.del(f3, Tier.SAFE, "S_TEMP_FILE", 0);
        r1.reasonParams.put("hashPending", "true"); r2.reasonParams.put("hashPending", "true");
        HashOnDemand.Result hr = HashOnDemand.run(h.in.recs, h.in.files, new HashOnDemand.Hasher() { public String sha256(FileRecord f) { return "h" + f.fileId; } }, 100);
        T.eq("budget: one hashed, one pending", 1, hr.hashed); T.eq("pending", 1, hr.pending);
        T.check("hashed rec has sha256 evidence and is plannable", r1.evidenceRefs.contains("sha256:h" + f1.fileId) && !UnitBuilder2.hashPending(r1));
        T.check("existing hash goes into evidence", r3.evidenceRefs.contains("sha256:" + f3.sha256));
        T.check("over-budget rec stays pending and is not planned", UnitBuilder2.hashPending(r2) && !PlanFx.has(h.plan(ActionPlan.MAX_SAFE), f2.fileId));
        f3.sha256 = "old"; r3.reasonParams.put("hashPending", "true");
        HashOnDemand.run(h.in.recs, h.in.files, new HashOnDemand.Hasher() { public String sha256(FileRecord f) { return "new"; } }, 1 << 20);
        T.check("stale stored hash -> recomputed, mismatch keeps it pending", UnitBuilder2.hashPending(r3));

        T.section("staleness");
        PlanFx s = new PlanFx();
        FileRecord k = s.file("DCIM/k.jpg", 5000000), cp = s.file("Download/k.jpg", 5000000), tmp = s.file("tmp/x.tmp", 1000000);
        s.del(cp, Tier.NO_LOSS, "N_EXACT_DUPLICATE", 0, k.fileId).evidenceRefs.add("sha256:abc");
        s.del(tmp, Tier.SAFE, "S_TEMP_FILE", 0);
        s.rec("cache:com.a", Tier.SAFE, Action.CLEAR_APP_CACHE, "S_APP_CACHE", 9000000, 0).reasonParams.put("appStats", "v=5,c=9000000,d=100");
        ActionPlan plan = s.plan(ActionPlan.MAX_SAFE);
        Map<String, FileRecord> cur = new HashMap<String, FileRecord>(s.in.files);
        Map<String, InstalledApp> apps = new HashMap<String, InstalledApp>();
        InstalledApp ia = new InstalledApp(); ia.packageName = "com.a"; ia.versionCode = 5; ia.cacheBytes = 9500000; ia.dataBytes = 100; apps.put("com.a", ia);
        T.eq("fresh plan not stale", 0, PlanStaleness.check(plan.copy(), cur, null, apps).size());
        T.eq("plan state DRAFT", "DRAFT", plan.state);
        FileRecord changed = copy(cp); changed.sizeBytes += 7; cur.put(cp.fileId, changed);
        ActionPlan p2 = plan.copy(); T.check("member file changed -> STALE", !PlanStaleness.check(p2, cur, null, apps).isEmpty() && "STALE".equals(p2.state));
        cur.put(cp.fileId, cp);
        FileRecord kc = copy(k); kc.modifiedAt += 1000; cur.put(k.fileId, kc);
        T.check("required keeper changed -> STALE", !PlanStaleness.check(plan.copy(), cur, null, apps).isEmpty());
        cur.put(k.fileId, k); cur.remove(tmp.fileId);
        T.check("member removed -> STALE", !PlanStaleness.check(plan.copy(), cur, null, apps).isEmpty());
        cur.put(tmp.fileId, tmp);
        T.check("same-size same-mtime overwrite caught by hash", !PlanStaleness.check(plan.copy(), cur, null, apps, new HashOnDemand.Hasher() { public String sha256(FileRecord f) { return "zzz"; } }).isEmpty());
        ia.versionCode = 6; T.check("app version changed -> STALE", !PlanStaleness.check(plan.copy(), cur, null, apps).isEmpty());
        ia.versionCode = 5; ia.cacheBytes = 2000000; T.check("app cache moved > 20 % -> STALE", !PlanStaleness.check(plan.copy(), cur, null, apps).isEmpty());
        ia.cacheBytes = 9000000;
        Map<String, Recommendation> recs = new HashMap<String, Recommendation>();
        for (Recommendation r : s.in.recs) recs.put(PlanStaleness.key(r.fileId, r.proposedAction), r);
        T.eq("current recs unchanged -> fresh", 0, PlanStaleness.check(plan.copy(), cur, recs, apps).size());
        recs.get(PlanStaleness.key(tmp.fileId, Action.DELETE)).tier = Tier.REVIEW;
        T.check("tier would change -> STALE", !PlanStaleness.check(plan.copy(), cur, recs, apps).isEmpty());

        T.section("app subjects");
        InstalledApp g = app("com.g", 400, 300, 50, RiskFx.NOW - 200 * RiskFx.DAY); g.game = Tri.TRUE;
        InstalledApp sys = app("com.sys", 10, 1, 5, RiskFx.NOW - 400 * RiskFx.DAY); sys.system = Tri.TRUE;
        InstalledApp unk = app("com.u", -1, -1, -1, Long.MIN_VALUE);
        InstalledApp fresh = app("com.f", 50, 5, 7, RiskFx.NOW - RiskFx.DAY);
        JvmInstalledApps ja = new JvmInstalledApps(Availability.FULL, g, sys, unk, fresh);
        List<Recommendation> ar = AppSubjects.recs(ja, RiskFx.NOW, 35, 1, 0);
        Set<String> got = new TreeSet<String>(); for (Recommendation r : ar) got.add(r.fileId + " " + r.proposedAction + " " + r.tier);
        T.eq("app recs (sdk 35)", new TreeSet<String>(Arrays.asList("cache:com.f CLEAR_APP_CACHE SAFE", "cache:com.g CLEAR_APP_CACHE SAFE",
                "app:com.g ARCHIVE_APP NO_LOSS", "app:com.g UNINSTALL_APP YOUR_CALL", "cache:com.sys CLEAR_APP_CACHE SAFE")), got);
        got.clear(); for (Recommendation r : AppSubjects.recs(ja, RiskFx.NOW, -1, 1, 0)) got.add(r.fileId + " " + r.proposedAction);
        T.check("unknown sdk -> caches only", got.size() == 3);
        return T.finish("YourCallEligibilityTest");
    }
    static InstalledApp app(String pkg, long code, long data, long cache, long lastUsed) {
        InstalledApp a = new InstalledApp(); a.packageName = pkg; a.codeBytes = code * 1000000; a.dataBytes = data < 0 ? -1 : data * 1000000; a.cacheBytes = cache < 0 ? -1 : cache * 1000000;
        if (code < 0) a.codeBytes = -1;
        a.lastUsed = lastUsed; a.system = Tri.FALSE; a.hasLauncher = Tri.TRUE; a.versionCode = 1; return a;
    }
    static FileRecord copy(FileRecord f) { FileRecord c = new FileRecord(); c.fileId = f.fileId; c.path = f.path; c.sizeBytes = f.sizeBytes; c.modifiedAt = f.modifiedAt; c.inode = f.inode; c.presence = f.presence; return c; }
}
