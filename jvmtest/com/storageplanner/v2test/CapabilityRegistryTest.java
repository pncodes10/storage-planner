package com.storageplanner.v2test;

import com.storageplanner.v2.discovery.CapabilityRegistry;
import com.storageplanner.v2.model.*;

import java.util.*;

public final class CapabilityRegistryTest {
    static Volume vol(AccessGrant... g) { Volume v = new Volume(); v.volumeId = "primary"; v.grants = new ArrayList<AccessGrant>(Arrays.asList(g)); return v; }
    static AccessGrant g(String prefix, AccessMode m) { return AccessGrant.readAll("primary", prefix, m); }
    static List<String> codes(List<ScanEvent> ev) { List<String> l = new ArrayList<String>(); for (ScanEvent e : ev) l.add(e.code); return l; }

    public static int run(String[] args) {
        T.section("CapabilityRegistry");
        CapabilityRegistry c = new CapabilityRegistry(Collections.singletonList(vol(g("", AccessMode.B_ALL_FILES), g("DCIM", AccessMode.C_SAF_TREE))));
        T.eq("longest prefix wins", AccessMode.C_SAF_TREE, c.best("primary", "DCIM/Camera").mode);
        T.eq("root covered by B", AccessMode.B_ALL_FILES, c.best("primary", "Download").mode);
        T.check("DCIMX not under DCIM", c.best("primary", "DCIMX").mode == AccessMode.B_ALL_FILES);
        c = new CapabilityRegistry(Collections.singletonList(vol(g("", AccessMode.D_APP_PRIVATE), g("", AccessMode.A_MEDIASTORE), g("", AccessMode.C_SAF_TREE), g("", AccessMode.B_ALL_FILES))));
        T.eq("tie: B first", AccessMode.B_ALL_FILES, c.best("primary", "x").mode);
        c = new CapabilityRegistry(Collections.singletonList(vol(g("", AccessMode.D_APP_PRIVATE), g("", AccessMode.A_MEDIASTORE), g("", AccessMode.C_SAF_TREE))));
        T.eq("tie: C over A", AccessMode.C_SAF_TREE, c.best("primary", "x").mode);
        c = new CapabilityRegistry(Collections.singletonList(vol(g("", AccessMode.D_APP_PRIVATE), g("", AccessMode.A_MEDIASTORE))));
        T.eq("tie: A over D", AccessMode.A_MEDIASTORE, c.best("primary", "x").mode);
        AccessGrant inactive = g("Docs", AccessMode.C_SAF_TREE); inactive.active = false;
        c = new CapabilityRegistry(Collections.singletonList(vol(inactive)));
        T.check("inactive grant ignored", c.best("primary", "Docs/a") == null && !c.canList("primary", "Docs"));
        T.check("unknown volume", new CapabilityRegistry(new ArrayList<Volume>()).best("x", "") == null);

        // refresh events
        Volume v = vol();
        List<ScanEvent> ev = CapabilityRegistry.refresh(Collections.singletonList(v), Collections.singletonList(g("", AccessMode.B_ALL_FILES)), 100);
        T.eq("added", Collections.singletonList("D_GRANT_ADDED"), codes(ev));
        T.eq("firstSeenAt", 100L, v.grants.get(0).firstSeenAt);
        ev = CapabilityRegistry.refresh(Collections.singletonList(v), Collections.singletonList(g("", AccessMode.B_ALL_FILES)), 150);
        T.eq("no change → no events", 0, ev.size());
        ev = CapabilityRegistry.refresh(Collections.singletonList(v), new ArrayList<AccessGrant>(), 200);
        T.eq("revoked", Collections.singletonList("D_GRANT_REVOKED"), codes(ev));
        T.eq("revokedAt", 200L, v.grants.get(0).revokedAt);
        T.check("inactive after revoke", !v.grants.get(0).active);
        ev = CapabilityRegistry.refresh(Collections.singletonList(v), new ArrayList<AccessGrant>(), 250);
        T.eq("revoked once", 0, ev.size());
        ev = CapabilityRegistry.refresh(Collections.singletonList(v), Collections.singletonList(g("", AccessMode.B_ALL_FILES)), 300);
        T.eq("restored", Collections.singletonList("D_GRANT_RESTORED"), codes(ev));
        T.check("active after restore", v.grants.get(0).active && v.grants.get(0).revokedAt == Unknown.TIME);
        AccessGrant changed = g("", AccessMode.B_ALL_FILES); changed.canReadContent = Tri.FALSE;
        ev = CapabilityRegistry.refresh(Collections.singletonList(v), Collections.singletonList(changed), 400);
        T.eq("changed", Collections.singletonList("D_GRANT_CHANGED"), codes(ev));
        T.check("changed detail lists field", ev.get(0).detail.contains("canReadContent"));
        T.eq("flag updated", Tri.FALSE, v.grants.get(0).canReadContent);

        // authoritativeFor
        c = new CapabilityRegistry(Collections.singletonList(vol(g("", AccessMode.B_ALL_FILES))));
        T.check("unrestricted grant authoritative", c.authoritativeFor("primary", "Download", "pdf"));
        AccessGrant a = g("", AccessMode.A_MEDIASTORE); a.mediaTypes = Collections.singletonList("image");
        c = new CapabilityRegistry(Collections.singletonList(vol(a)));
        // REVIEW_1 F-M2 (binding) overrides the case list: Mode A listings are never authoritative for absence.
        T.check("image-only grant: .jpg not authoritative (F-M2)", !c.authoritativeFor("primary", "DCIM", "jpg"));
        T.check("image-only grant: .pdf not authoritative", !c.authoritativeFor("primary", "DCIM", "pdf"));
        T.check("image-only grant lists", c.canList("primary", "DCIM"));
        return T.finish("CapabilityRegistryTest");
    }
}
