package com.storageplanner.v2test;

import com.storageplanner.v2.device.InstalledApp;
import com.storageplanner.v2.model.*;
import com.storageplanner.v2.plan.*;
import com.storageplanner.v2.store.V2Store;
import com.storageplanner.v2.util.Hex;
import com.storageplanner.v2.util.Json;
import com.storageplanner.v2.util.JsonWriter;

import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.*;

/** PHASE_5 8: the CLI's PLAN step (hash on demand, app subjects, Planner2, versioning, staleness check) and the plan JSON. */
final class PlanJson {
    private PlanJson() {}
    static int sdk = 34;
    static String extApps, checkPlan;
    static Tier maxTier;
    static boolean allowYourCall;
    /** --sample: no app recommendations (idle apps, app caches), as in the app's quick test. */
    static boolean sample;
    static long hashBudget = Long.MAX_VALUE;
    static ActionPlan plan;
    static List<String> staleness;
    static HashOnDemand.Result hashRes;

    static long parseTarget(String v) {
        String u = v.toUpperCase(Locale.ROOT);
        if (u.equals("MAX")) return ActionPlan.MAX_SAFE;
        if (u.endsWith("GB")) return Long.parseLong(u.substring(0, u.length() - 2)) * 1000000000L;
        if (u.endsWith("MB")) return Long.parseLong(u.substring(0, u.length() - 2)) * 1000000L;
        return Long.parseLong(v);
    }
    static String sha256(Path p) {
        try (InputStream in = Files.newInputStream(p)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256"); byte[] b = new byte[1 << 16]; int n;
            while ((n = in.read(b)) > 0) md.update(b, 0, n);
            return Hex.of(md.digest());
        } catch (Exception e) { return null; }
    }

    static String make(V2Store store, JvmInstalledApps apps, String appsPath, final String root, Map<String, FileRecord> fm, Long target, long now, long scanId, PrintStream err) {
        hashRes = HashOnDemand.run(AssessJson.recs, fm, new HashOnDemand.Hasher() {
            public String sha256(FileRecord f) { return f.path == null ? null : PlanJson.sha256(Paths.get(root, f.path)); }
        }, hashBudget);
        JvmInstalledApps ext = new JvmInstalledApps(appsPath);
        if (extApps != null) ext.mergeExt(extApps);
        if (!sample) AssessJson.recs.addAll(AppSubjects.recs(ext, now, sdk, scanId, ProvisionalRecommendations.PLAN_VERSION));
        UnitBuilder2.Input in = new UnitBuilder2.Input();
        in.recs = AssessJson.recs; in.files = fm; in.keepers = Plans.keepers(store);
        if (target != null) {
            Plans.Made m = Plans.make(in, target, maxTier, allowYourCall);
            for (String p : m.problems) err.println("plan validator: " + p);
            plan = m.plan;
            plan.mode = AssessJson.mode == null ? "standard" : AssessJson.mode;
            Plans.version(store, plan, now, scanId);
            try { store.flush(); } catch (Exception e) { return "plan commit: " + e; }
        }
        if (checkPlan != null) {
            ActionPlan old;
            try { old = read(checkPlan); } catch (Exception e) { return "--check-plan " + checkPlan + ": " + e; }
            Map<String, Recommendation> cur = new HashMap<String, Recommendation>();
            for (Recommendation r : AssessJson.recs) cur.put(PlanStaleness.key(r.fileId, r.proposedAction), r);
            Map<String, InstalledApp> am = new HashMap<String, InstalledApp>();
            for (InstalledApp a : ext.list()) am.put(a.packageName, a);
            staleness = PlanStaleness.check(old, fm, cur, am, new HashOnDemand.Hasher() {
                public String sha256(FileRecord f) { return f.path == null ? null : PlanJson.sha256(Paths.get(root, f.path)); }
            });
            if (plan == null) plan = old;
            else if (!staleness.isEmpty()) plan.staleReasons.addAll(staleness);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    static ActionPlan read(String resultJson) throws Exception {
        Map<String, Object> r = (Map<String, Object>) Json.parse(new String(Files.readAllBytes(Paths.get(resultJson)), StandardCharsets.UTF_8));
        Map<String, Object> pm = (Map<String, Object>) r.get("plan");
        if (pm == null) throw new IllegalArgumentException("no plan in result");
        ActionPlan p = new ActionPlan(); p.planId = Json.str(pm, "planId"); p.planVersion = ActionPlan.versionOf(p.planId);
        for (Object o : Json.list(pm, "items")) {
            Map<String, Object> m = (Map<String, Object>) o;
            ActionPlan.PlanItem it = new ActionPlan.PlanItem();
            it.unitId = Json.str(m, "unitId"); it.recId = Json.str(m, "recommendationId"); it.code = Json.str(m, "reasonCode");
            it.action = Action.valueOf(Json.str(m, "action")); it.tier = Tier.valueOf(Json.str(m, "tier")); it.bytes = Json.lng(m, "bytes", 0);
            it.appStats = Json.str(m, "appStats");
            Map<String, Object> hs = (Map<String, Object>) m.get("hashes");
            if (hs != null) for (Map.Entry<String, Object> e : hs.entrySet()) it.hashes.put(e.getKey(), (String) e.getValue());
            for (Object f : Json.list(m, "fileIds")) it.fileIds.add((String) f);
            for (Object f : Json.list(m, "requires")) it.requires.add((String) f);
            Map<String, Object> fps = (Map<String, Object>) m.get("fps");
            if (fps != null) for (Map.Entry<String, Object> e : fps.entrySet()) {
                List<Object> v = (List<Object>) e.getValue();
                it.fps.put(e.getKey(), new Fingerprint(((Number) v.get(0)).longValue(), ((Number) v.get(1)).longValue(), ((Number) v.get(2)).longValue()));
            }
            p.items.add(it);
        }
        return p;
    }

    static void write(JsonWriter w) {
        if (plan == null) { w.nullValue(); return; }
        ActionPlan p = plan;
        w.beginObject().name("planId").value(p.planId).name("planVersion").value(p.planVersion).name("state").value(p.state).name("mode").value(p.mode)
         .name("targetBytes").value(p.targetBytes).name("maxTier").value(p.maxTier).name("allowYourCall").value(p.allowYourCall).name("yourCallExtraBytes").value(p.yourCallExtraBytes)
         .name("achievableBytes").value(p.achievableBytes).name("achievableExactBytes").value(p.achievableExactBytes)
         .name("withYourCallBytes").value(p.withYourCallBytes).name("reachesTarget").value(p.reachesTarget).name("highestTier").value(p.highestTier)
         .name("hashPending").value(p.hashPending).name("validatorDropped").value(p.validatorDropped)
         .name("reasonCodes").strings(p.reasonCodes).name("reasons").beginArray();
        for (String c : p.reasonCodes) w.value(ReasonCatalog.render(c, p.reasonParams));
        w.endArray().name("reasonParams").beginObject();
        for (Map.Entry<String, String> e : p.reasonParams.entrySet()) w.name(e.getKey()).value(e.getValue());
        w.endObject().name("staleReasons").strings(p.staleReasons);
        if (hashRes != null) w.name("hashOnDemand").beginObject().name("hashed").value(hashRes.hashed).name("pending").value(hashRes.pending)
                .name("bytesRead").value(hashRes.bytesRead).endObject();
        w.name("items").beginArray();
        for (ActionPlan.PlanItem it : p.items) {
            w.beginObject().name("unitId").value(it.unitId).name("subject").value(it.subject).name("recommendationId").value(it.recId)
             .name("reasonCode").value(it.code).name("action").value(it.action).name("tier").value(it.tier).name("bytes").value(it.bytes)
             .name("exact").value(it.exact).name("priorityClass").value(it.priorityClass).name("cost").value(AssessJson.r(it.cost))
             .name("utility").value(AssessJson.r(it.utility)).name("fileIds").strings(it.fileIds).name("requires").strings(it.requires);
            if (it.appStats != null) w.name("appStats").value(it.appStats);
            w.name("hashes").beginObject(); for (Map.Entry<String, String> e : it.hashes.entrySet()) w.name(e.getKey()).value(e.getValue()); w.endObject();
            w.name("fps").beginObject();
            for (Map.Entry<String, Fingerprint> e : it.fps.entrySet()) w.name(e.getKey()).beginArray().value(e.getValue().size).value(e.getValue().mtime).value(e.getValue().inode).endArray();
            w.endObject().endObject();
        }
        w.endArray().endObject();
    }
}
