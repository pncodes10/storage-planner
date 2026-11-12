package com.storageplanner.v2.discovery;

import com.storageplanner.v2.device.StorageAccess;
import com.storageplanner.v2.model.*;
import com.storageplanner.v2.stage.*;
import com.storageplanner.v2.store.StoreException;
import com.storageplanner.v2.store.V2Store;

import java.util.*;

/** Stage DISCOVER (PHASE_1 7). */
public final class DiscoveryEngine implements Stage {
    public static final String ID = "DISCOVER";
    private final WalkLimits limits;
    private final Set<String> installed;

    /** Default limits; installed packages unknown (Phase 2 takes them from ctx.device.apps()). */
    public DiscoveryEngine() { this(new WalkLimits(), null); }

    /**
     * @param installedPackages the complete installed-package set, or null when it is not known completely
     *                          (then "residue" is never inferred, REVIEW_1 F-M3)
     */
    public DiscoveryEngine(WalkLimits limits, Set<String> installedPackages) {
        this.limits = limits == null ? new WalkLimits() : limits;
        this.installed = installedPackages == null ? null : new TreeSet<String>(installedPackages);
    }

    public String id() { return ID; }

    public StageOutcome run(final StageContext ctx) throws StoreException {
        final V2Store store = ctx.store;
        final ScanSession session = ctx.session;
        StorageAccess sa = ctx.device.storage();
        long now = ctx.device.clock().nowMillis();

        List<Volume> volumes = VolumeRegistry.refresh(store, sa, now, session, ctx.config.volumeIds);
        List<AccessGrant> current = sa.grants();
        for (ScanEvent e : CapabilityRegistry.refresh(volumes, current, now)) session.event(e);
        for (Volume v : volumes) store.volumes().put(v);
        if (session.grantsAtStart == null) {
            session.grantsAtStart = new ArrayList<AccessGrant>();
            for (AccessGrant g : current) session.grantsAtStart.add(g.copy());
        }
        CommitHook hook = new CommitHook() {
            public void commit() throws StoreException { store.scanSessions().put(session); store.commit(); }
        };
        if (session.checkpoint == null) {
            session.checkpoint = new ScanCheckpoint();
            for (Volume v : volumes) session.checkpoint.volumeQueue.add(v.volumeId);
            session.checkpoint.frontier.add("");
        }
        hook.commit();

        while (!session.checkpoint.volumeQueue.isEmpty()) {
            String vid = session.checkpoint.volumeQueue.get(0);
            Volume vol = store.volumes().get(vid);
            if (vol != null && vol.mounted) {
                Walker w = new Walker(sa, store, vol, new CapabilityRegistry(mounted(store)), limits, ctx.device.clock(), ctx.control,
                        ctx.throttle, session, hook, installed);
                StageOutcome o = w.walk();
                if (o != StageOutcome.DONE) return o;
                Volume after = store.volumes().get(vid);
                if (w.complete() && after != null && after.mounted) { after.lastFullScanId = session.scanId; store.volumes().put(after); }
            }
            session.checkpoint.volumeQueue.remove(0);
            session.checkpoint.frontier.clear();
            session.checkpoint.frontier.add("");
            hook.commit();
        }

        // end of walk: grants re-read (4.6)
        now = ctx.device.clock().nowMillis();
        List<AccessGrant> end = sa.grants();
        if (!CapabilityRegistry.sameGrants(session.grantsAtStart, end)) {
            session.counters.put("grantsChangedDuringScan", 1L);
            List<Volume> vols = mounted(store);
            List<ScanEvent> ev = CapabilityRegistry.refresh(vols, end, now);
            for (ScanEvent e : ev) session.event(e);
            for (Volume v : vols) store.volumes().put(v);
        }
        rewriteRevoked(store, session);
        rederiveAreas(store);
        deriveCounters(store, session);
        session.checkpoint = null;
        if (!session.stagesDone.contains(ID)) session.stagesDone.add(ID);
        store.scanSessions().put(session);
        store.flush();
        return StageOutcome.DONE;
    }

    static List<Volume> mounted(V2Store store) {
        List<Volume> l = new ArrayList<Volume>();
        for (Volume v : store.volumes().all()) if (v.mounted) l.add(v);
        return l;
    }

    /** Subtrees made unverifiable by a grant revoked in this scan get D_PERMISSION_REVOKED. */
    static void rewriteRevoked(V2Store store, ScanSession session) {
        for (Volume v : store.volumes().all()) {
            if (v.grants == null) continue;
            for (AccessGrant g : v.grants) {
                if (g.active || !Unknown.knownTime(g.revokedAt) || g.revokedAt < session.startedAt) continue;
                for (FileRecord f : store.files().underPrefix(v.volumeId, g.prefix)) {
                    if (f.presence != Presence.UNVERIFIABLE) continue;
                    if (!"D_NOT_GRANTED".equals(f.presenceReason) && !"D_PARENT_UNREADABLE".equals(f.presenceReason)) continue;
                    f.presenceReason = "D_PERMISSION_REVOKED";
                    store.files().put(f);
                }
            }
        }
    }

    /** Area of records with an owner package follows the current installed set (write only on change). */
    void rederiveAreas(V2Store store) {
        Map<String, Volume> vols = new HashMap<String, Volume>();
        for (Volume v : store.volumes().all()) vols.put(v.volumeId, v);
        for (FileRecord f : store.files().all()) {
            if (f.presence != Presence.PRESENT || f.ownerPackage == null) continue;
            Volume v = vols.get(f.volumeId);
            if (v == null) continue;
            String[] a = FileUpsert.area(v, f.path, installed);
            if (!a[0].equals(f.area)) { f.area = a[0]; f.ownerPackage = a[1]; store.files().put(f); }
        }
    }

    /** Counters derived from records (exact after resumes). */
    static void deriveCounters(V2Store store, ScanSession session) {
        long added = 0, changed = 0, removed = 0, replaced = 0;
        for (FileRecord f : store.files().all()) {
            if (f.changeScanId != session.scanId || f.change == null) continue;
            switch (f.change) {
                case ADDED: added++; break; case CHANGED: changed++; break;
                case REMOVED: removed++; break; case REPLACED: replaced++; break;
                default: break;
            }
        }
        session.counters.put("added", added); session.counters.put("changed", changed);
        session.counters.put("removed", removed); session.counters.put("replaced", replaced);
        Map<String, Long> dirs = new TreeMap<String, Long>();
        for (DirRecord d : store.directories().all()) {
            if (d.lastVisitedScanId != session.scanId || d.status == null) continue;
            String k = "dirs." + d.status.name();
            Long c = dirs.get(k); dirs.put(k, (c == null ? 0 : c) + 1);
        }
        for (Iterator<String> it = session.counters.keySet().iterator(); it.hasNext(); ) if (it.next().startsWith("dirs.")) it.remove();
        session.counters.putAll(dirs);
    }
}
