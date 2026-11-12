package com.storageplanner.v2test;

import com.storageplanner.v2.model.*;
import com.storageplanner.v2.plan.*;

import java.util.*;

/** PHASE_5 10: UnitBuilder2 cases. */
public final class UnitBuilder2Test {
    static final long MB = 1000000L;
    public static int run(String[] args) {
        T.section("exclusive options");
        PlanFx x = new PlanFx();
        FileRecord w = x.file("Recordings/a.wav", 50 * MB);
        x.rec(w.fileId, Tier.NO_LOSS, Action.COMPRESS, "N_LOSSLESS_SMALLER", 20 * MB, 0);
        x.del(w, Tier.YOUR_CALL, "Y_LOW_QUALITY", 0.01);
        UnitBuilder2.Built b = UnitBuilder2.build(x.in);
        T.eq("one unit for both recs", 1, b.units.size());
        T.eq("two options", 2, b.units.get(0).options.size());
        ActionPlan p = x.plan(60 * MB);
        T.eq("never both actions on one file", 1, p.items.size());

        T.section("bundle");
        x = new PlanFx();
        FileRecord base = x.file("Download/app/base.apk", 30 * MB), s1 = x.file("Download/app/split1.apk", 5 * MB), s2 = x.file("Download/app/split2.apk", 5 * MB);
        x.del(base, Tier.NO_LOSS, "N_INSTALLER_INSTALLED", 0); x.del(s1, Tier.NO_LOSS, "N_INSTALLER_INSTALLED", 0); x.del(s2, Tier.YOUR_CALL, "Y_INSTALLER_UNVERIFIED", 0.001);
        x.in.bundles.put(base.fileId, Arrays.asList(base.fileId, s1.fileId, s2.fileId));
        b = UnitBuilder2.build(x.in);
        T.eq("bundle is one unit", 1, b.units.size());
        T.eq("bundle bytes", 40 * MB, b.units.get(0).options.get(0).bytes);
        T.eq("bundle tier = max of members", Tier.YOUR_CALL, b.units.get(0).options.get(0).tier);
        x.in.recs.remove(x.in.recs.size() - 1);
        b = UnitBuilder2.build(x.in);
        T.eq("bundle dropped if one split ineligible", 0, b.units.size());
        T.eq("bundle drop counted", 1, b.bundlesDropped);

        T.section("app vs files");
        x = new PlanFx();
        FileRecord cf = x.file("Android/data/com.x/cache/c.bin", 3 * MB), mf = x.file("Android/media/com.y/v.mp4", 9 * MB);
        x.del(cf, Tier.SAFE, "S_APP_CACHE_FILE", 0);
        x.rec("cache:com.x", Tier.SAFE, Action.CLEAR_APP_CACHE, "S_APP_CACHE", 10 * MB, 0);
        x.del(mf, Tier.SAFE, "S_APP_CACHE_FILE", 0);
        x.rec("app:com.y", Tier.YOUR_CALL, Action.UNINSTALL_APP, "Y_IDLE_APP", 500 * MB, 0.05);
        b = UnitBuilder2.build(x.in);
        T.check("cache file is included in the app cache (display only)", b.displayOnly.size() == 1 && "cache:com.x".equals(b.displayOnly.get(0).includedIn));
        p = x.plan(ActionPlan.MAX_SAFE);
        T.eq("max safe: cache unit (cache file inside it) + SAFE media file", 19 * MB, p.achievableBytes);
        p = x.plan(400 * MB);
        T.check("uninstall and its media file never together", !(PlanFx.has(p, "app:com.y") && PlanFx.has(p, mf.fileId)));
        T.check("lower-tier file unit wins over a YOUR_CALL uninstall", !PlanFx.has(p, "app:com.y") && !p.reachesTarget);
        FileRecord zf = x.file("Android/media/com.z/v.mp4", 9 * MB);
        x.del(zf, Tier.YOUR_CALL, "Y_LEFTOVER_MEDIA", 0.01);
        x.rec("app:com.z", Tier.YOUR_CALL, Action.UNINSTALL_APP, "Y_IDLE_APP", 500 * MB, 0.05);
        b = UnitBuilder2.build(x.in);
        T.check("same tier: app unit wins, file display only", b.displayOnly.size() == 2);
        p = x.plan(400 * MB);
        T.check("uninstall chosen at equal tier", PlanFx.has(p, "app:com.z") && !PlanFx.has(p, zf.fileId));
        long sum = 0; for (ActionPlan.PlanItem i : p.items) sum += i.bytes;
        T.eq("bytes counted once", sum, p.withYourCallBytes);

        T.section("alias / keeper / hash");
        x = new PlanFx();
        FileRecord al = x.file("x/alias.jpg", 5 * MB), k = x.file("DCIM/k.jpg", 5 * MB), c = x.file("Download/k.jpg", 5 * MB), hp = x.file("Download/h.jpg", 7 * MB);
        x.rec(al.fileId, Tier.NO_LOSS, Action.DELETE, "N_EXACT_DUPLICATE", 0, 0);      // aliases free 0 bytes
        x.del(c, Tier.NO_LOSS, "N_EXACT_DUPLICATE", 0, k.fileId); x.del(k, Tier.NO_LOSS, "N_EXACT_DUPLICATE", 0, c.fileId);
        x.in.keepers.add(k.fileId);
        x.del(hp, Tier.NO_LOSS, "N_EXACT_DUPLICATE", 0, k.fileId).reasonParams.put("hashPending", "true");
        b = UnitBuilder2.build(x.in);
        T.eq("alias -> no unit, keeper -> no unit, hashPending -> no unit", Collections.singletonList(c.fileId), ids(b.units));
        T.eq("hashPending counted", 1, b.hashPending);
        p = x.plan(ActionPlan.MAX_SAFE);
        T.check("PL_HASH_PENDING reported", p.reasonCodes.contains("PL_HASH_PENDING"));
        return T.finish("UnitBuilder2Test");
    }
    static List<String> ids(List<PlanUnit> us) { List<String> l = new ArrayList<String>(); for (PlanUnit u : us) l.add(u.id); return l; }
}
