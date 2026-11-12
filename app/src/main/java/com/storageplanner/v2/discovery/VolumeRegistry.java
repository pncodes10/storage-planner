package com.storageplanner.v2.discovery;

import com.storageplanner.v2.device.StorageAccess;
import com.storageplanner.v2.device.VolumeInfo;
import com.storageplanner.v2.model.*;
import com.storageplanner.v2.store.StoreException;
import com.storageplanner.v2.store.V2Store;

import java.util.*;

/** Volume identification and mount-state tracking (PHASE_1 3). */
public final class VolumeRegistry {
    private VolumeRegistry() {}

    static int kindOrder(VolumeKind k) {
        if (k == null) return 9;
        switch (k) {
            case PRIMARY_SHARED: return 0; case SECONDARY_SHARED: return 1; case REMOVABLE: return 2;
            case DIRECTORY: return 3; case SAF_TREE: return 4; case APP_PRIVATE: return 5; default: return 6;
        }
    }

    /** Reconciles sa.volumes() with stored Volume records; writes changes; returns the mounted volumes to walk (sorted by id). */
    public static List<Volume> refresh(V2Store store, StorageAccess sa, long now, ScanSession session, Set<String> onlyIds) throws StoreException {
        List<VolumeInfo> infos = new ArrayList<VolumeInfo>();
        for (VolumeInfo i : sa.volumes()) {
            if (i.volumeId == null || i.volumeId.isEmpty()) { session.event(now, "D_VOLUME_INVALID", null, null, i.root); continue; }
            if (onlyIds != null && !onlyIds.contains(i.volumeId)) continue;
            infos.add(i);
        }
        // alias de-duplication: equal canonical roots
        Map<String, VolumeInfo> byRoot = new TreeMap<String, VolumeInfo>();
        List<VolumeInfo> kept = new ArrayList<VolumeInfo>();
        for (VolumeInfo i : infos) {
            if (i.root == null) { kept.add(i); continue; }
            String c = PosixStorageAccess.canonical(i.root);
            VolumeInfo o = byRoot.get(c);
            if (o == null) { byRoot.put(c, i); continue; }
            boolean iBetter = kindOrder(i.kind) < kindOrder(o.kind) || (kindOrder(i.kind) == kindOrder(o.kind) && i.volumeId.compareTo(o.volumeId) < 0);
            VolumeInfo drop = iBetter ? o : i;
            if (iBetter) byRoot.put(c, i);
            session.event(now, "D_VOLUME_ALIAS", drop.volumeId, null, "alias of " + (iBetter ? i : o).volumeId);
        }
        kept.addAll(byRoot.values());
        Set<String> seen = new HashSet<String>();
        List<Volume> mounted = new ArrayList<Volume>();
        for (VolumeInfo i : kept) {
            seen.add(i.volumeId);
            Volume old = store.volumes().get(i.volumeId);
            Volume v = old == null ? new Volume() : old;
            if (old == null) { v.volumeId = i.volumeId; v.firstSeenAt = now; }
            v.kind = i.kind == null ? VolumeKind.UNKNOWN : i.kind; v.label = i.label; v.root = i.root; v.fsUuid = i.fsUuid;
            v.totalBytes = i.totalBytes; v.freeBytes = i.freeBytes;
            v.caseInsensitive = i.caseInsensitive == null ? Tri.UNKNOWN : i.caseInsensitive;
            v.fileKeyStable = i.fileKeyStable == null ? Tri.UNKNOWN : i.fileKeyStable;
            v.restrictedPrefixes = i.restrictedPrefixes == null ? new ArrayList<String>() : new ArrayList<String>(i.restrictedPrefixes);
            v.lastSeenAt = now;
            String scheme = i.fileKeyStable == Tri.TRUE ? "ino" : "path";
            if (v.identityScheme == null) v.identityScheme = scheme;
            else if (!v.identityScheme.equals(scheme)) session.event(now, "D_IDENTITY_SCHEME_KEPT", v.volumeId, null, "kept " + v.identityScheme + ", reported " + scheme);
            boolean wasMounted = old != null && old.mounted;
            if (i.mounted) {
                if (old != null && !old.mounted) session.event(now, "D_VOLUME_RETURNED", v.volumeId, "", null);
                v.mounted = true;
                store.volumes().put(v);
                mounted.add(v);
            } else {
                v.mounted = false;
                store.volumes().put(v);
                if (wasMounted) session.event(now, "D_VOLUME_ABSENT", v.volumeId, "", null);
                markAbsent(store, v.volumeId);
            }
        }
        for (Volume v : store.volumes().all()) {
            if (seen.contains(v.volumeId)) continue;
            if (onlyIds != null && !onlyIds.contains(v.volumeId)) continue;
            if (v.mounted) {
                v.mounted = false;
                store.volumes().put(v);
                session.event(now, "D_VOLUME_ABSENT", v.volumeId, "", null);
            }
            markAbsent(store, v.volumeId);
        }
        Collections.sort(mounted, new Comparator<Volume>() { public int compare(Volume a, Volume b) { return a.volumeId.compareTo(b.volumeId); } });
        return mounted;
    }

    /** Every PRESENT/UNVERIFIABLE file of the volume → VOLUME_ABSENT (records are not otherwise changed). */
    public static void markAbsent(V2Store store, String volumeId) {
        for (FileRecord f : store.files().byVolume(volumeId)) {
            if (f.presence == Presence.PRESENT || f.presence == Presence.UNVERIFIABLE) {
                f.presence = Presence.VOLUME_ABSENT;
                f.presenceReason = "D_VOLUME_ABSENT";
                store.files().put(f);
            }
        }
    }
}
