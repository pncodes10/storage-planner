package com.storageplanner.v2.discovery;

import com.storageplanner.core.Classifier;
import com.storageplanner.v2.V2;
import com.storageplanner.v2.device.DirEntry;
import com.storageplanner.v2.device.FileStat;
import com.storageplanner.v2.device.IoStatus;
import com.storageplanner.v2.model.*;
import com.storageplanner.v2.store.StoreException;
import com.storageplanner.v2.store.V2Store;
import com.storageplanner.v2.util.Text;

import java.util.Collections;
import java.util.Set;

/** Creates or updates the FileRecord of a listed file (PHASE_1 4.5). Idempotent; writes nothing when nothing changed. */
final class FileUpsert {
    private FileUpsert() {}

    static final int OK = 0, STAT_FAILED = 1;

    static int apply(V2Store store, Volume vol, CapabilityRegistry caps, String path, String rawPathOrNull,
                     DirEntry e, FileStat st, long scanId, long now, Set<String> installed) throws StoreException {
        long size = e.size, mtime = e.mtime, inode = e.inode;
        String physicalKey = e.physicalKey;
        Tri readable = Tri.UNKNOWN, writable = Tri.UNKNOWN;
        String lastError = null;
        if (st != null && st.status == IoStatus.OK) {
            size = st.size; mtime = st.mtime; inode = st.inode;
            if (st.physicalKey != null) physicalKey = st.physicalKey;
            readable = st.readable == null ? Tri.UNKNOWN : st.readable;
            writable = st.writable == null ? Tri.UNKNOWN : st.writable;
        } else if (st != null) {
            readable = Tri.FALSE;
            lastError = st.status + ": " + st.error;
        }
        if (size == 0 && e.uri != null) size = -1;   // providers report 0 for pending/virtual documents
        boolean ino = "ino".equals(vol.identityScheme);
        // identity guard (REVIEW_1 F-M1): no id without an inode on an ino volume
        if (ino && inode < 0) {
            FileRecord old = store.files().byPath(vol.volumeId, path);
            if (old != null && old.presence != Presence.REMOVED) {
                if (old.presence == Presence.PRESENT) {
                    old.presence = Presence.UNVERIFIABLE; old.presenceReason = "D_STAT_FAILED";
                }
                if (lastError != null) old.lastError = Text.truncate(lastError, 200);
                store.files().put(old);
            }
            return STAT_FAILED;
        }
        String token = ino ? "ino:" + inode : "";
        String id = FileIds.of(vol.volumeId, path, token);
        FileRecord old = store.files().byPath(vol.volumeId, path);
        if (old != null && old.presence != Presence.REMOVED && !old.fileId.equals(id)) {
            old.presence = Presence.REMOVED; old.change = ChangeState.REPLACED; old.changeScanId = scanId;
            old.removedScanId = scanId; old.presenceReason = "D_REPLACED";
            store.files().put(old);
        }
        FileRecord cur = store.files().get(id);
        if (cur == null) {
            FileRecord r = new FileRecord();
            r.fileId = id; r.volumeId = vol.volumeId; r.path = path;
            r.rawPath = rawPathOrNull != null && !rawPathOrNull.equals(path) ? rawPathOrNull : null;
            r.uri = e.uri;
            r.name = PathNormalizer.name(path);
            r.extension = Text.extension(r.name);
            r.sizeBytes = size; r.modifiedAt = mtime; r.inode = inode; r.physicalKey = physicalKey;
            if (vol.atimeReliable == Tri.TRUE && st != null) r.lastAccessedAt = st.atime;
            r.presence = Presence.PRESENT; r.change = ChangeState.ADDED;
            r.firstSeenScanId = scanId; r.changeScanId = scanId;
            r.readable = readable; r.writable = writable;
            String parent = PathNormalizer.parent(path);
            AccessGrant g = caps.best(vol.volumeId, parent == null ? "" : parent);
            r.accessMode = g == null ? null : g.mode;
            String[] a = area(vol, path, installed);
            r.area = a[0]; r.ownerPackage = a[1];
            r.hidden = hidden(path);
            r.scanVersion = V2.SCAN_VERSION;
            r.lastError = lastError == null ? null : Text.truncate(lastError, 200);
            store.files().put(r);
            return OK;
        }
        boolean addedNow = false;
        if (cur.presence != Presence.PRESENT) {
            boolean wasRemoved = cur.presence == Presence.REMOVED;
            cur.presence = Presence.PRESENT; cur.presenceReason = null; cur.removedScanId = Unknown.LONG;
            if (wasRemoved) { cur.change = ChangeState.ADDED; cur.changeScanId = scanId; addedNow = true; }
        }
        if (cur.sizeBytes != size || cur.modifiedAt != mtime || cur.inode != inode) {
            cur.sizeBytes = size; cur.modifiedAt = mtime; cur.inode = inode;
            if (!addedNow && !(cur.change == ChangeState.ADDED && cur.changeScanId == scanId)) { cur.change = ChangeState.CHANGED; cur.changeScanId = scanId; }
            cur.scanVersion = V2.SCAN_VERSION;
            int t = (1 << 1) | (1 << 2) | (1 << 3);   // Phase 2: content changed, tiers 1-3 must run again
            if (((cur.tiersDone | cur.tiersFailed) & t) != 0) { cur.tiersPending |= (cur.tiersDone | cur.tiersFailed) & t; cur.tiersDone &= ~t; cur.tiersFailed &= ~t; }
        }
        String rp = rawPathOrNull != null && !rawPathOrNull.equals(path) ? rawPathOrNull : null;
        cur.rawPath = rp;
        if (physicalKey != null) cur.physicalKey = physicalKey;
        if (readable != Tri.UNKNOWN) cur.readable = readable;
        if (writable != Tri.UNKNOWN) cur.writable = writable;
        cur.lastError = lastError == null ? null : Text.truncate(lastError, 200);
        store.files().put(cur);   // the store ignores puts that change nothing
        return OK;
    }

    /** A listed file whose stat returned NOT_FOUND: confirmed absent only under an authoritative listing. */
    static void vanished(V2Store store, CapabilityRegistry caps, String volumeId, String path, boolean authoritativeListing, long scanId) {
        FileRecord f = store.files().byPath(volumeId, path);
        if (f == null || f.presence == Presence.REMOVED) return;
        String parent = PathNormalizer.parent(path);
        if (authoritativeListing && caps.authoritativeFor(volumeId, parent == null ? "" : parent, f.extension)) {
            f.presence = Presence.REMOVED; f.change = ChangeState.REMOVED; f.presenceReason = "D_VANISHED";
            f.changeScanId = scanId; f.removedScanId = scanId;
        } else {
            if (f.presence != Presence.PRESENT) return;
            f.presence = Presence.UNVERIFIABLE; f.presenceReason = "D_STAT_FAILED";
        }
        store.files().put(f);
    }

    /** {area, ownerPackage}; residue with an incomplete installed set → app_owner_unknown (REVIEW_1 F-M3). */
    static String[] area(Volume vol, String path, Set<String> installed) {
        if (vol.kind == VolumeKind.SAF_TREE || vol.kind == VolumeKind.APP_PRIVATE) return new String[]{"user_other", null};
        Set<String> set = installed == null ? Collections.<String>emptySet() : installed;
        String[] r = Classifier.classify(path, set);
        if ("residue".equals(r[0]) && installed == null) return new String[]{"app_owner_unknown", r[1]};
        return r;
    }

    static boolean hidden(String path) {
        for (String p : path.split("/")) if (p.startsWith(".")) return true;
        return false;
    }
}
