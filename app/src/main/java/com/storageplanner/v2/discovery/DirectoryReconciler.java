package com.storageplanner.v2.discovery;

import com.storageplanner.v2.device.EntryKind;
import com.storageplanner.v2.model.*;
import com.storageplanner.v2.store.V2Store;

import java.util.Map;

/** Removal and unverifiability decisions per directory listing (PHASE_1 4.5). */
final class DirectoryReconciler {
    private DirectoryReconciler() {}

    /** names: every name key of the listing (incl. unprocessed) → resolved kind. */
    static void reconcile(V2Store s, Volume v, CapabilityRegistry caps, String dir, Map<String, EntryKind> names, boolean authoritative, long scanId) {
        for (FileRecord f : s.files().childrenOf(v.volumeId, dir)) {
            if (f.presence != Presence.PRESENT && f.presence != Presence.UNVERIFIABLE) continue;
            EntryKind k = names.get(PathNormalizer.name(f.path));
            boolean gone = k == null || k == EntryKind.DIRECTORY || k == EntryKind.SYMLINK;
            if (!gone) continue;
            if (authoritative && caps.authoritativeFor(v.volumeId, dir, f.extension)) {
                f.presence = Presence.REMOVED; f.change = ChangeState.REMOVED; f.presenceReason = "D_NOT_IN_LISTING";
                f.changeScanId = scanId; f.removedScanId = scanId;
                s.files().put(f);
            } else if (f.presence == Presence.PRESENT) {
                f.presence = Presence.UNVERIFIABLE;
                f.presenceReason = authoritative ? "D_MEDIA_ONLY_VISIBILITY" : "D_PARTIAL_LISTING";
                s.files().put(f);
            }
        }
        for (DirRecord d : s.directories().childrenOf(v.volumeId, dir)) {
            EntryKind k = names.get(PathNormalizer.name(d.path));
            if (k == null || k == EntryKind.FILE || k == EntryKind.OTHER) {
                if (authoritative) directoryGone(s, v.volumeId, d.path, scanId);
                else subtreeUnverifiable(s, v.volumeId, d.path, "D_PARTIAL_LISTING", scanId);
            } else if (k == EntryKind.SYMLINK && d.status != DirStatus.OK) {
                subtreeUnverifiable(s, v.volumeId, d.path, "D_SYMLINK", scanId);
            }
        }
    }

    /** The directory is gone: its files REMOVED (D_DIR_REMOVED), its directory records NOT_FOUND. */
    static void directoryGone(V2Store s, String volumeId, String dir, long scanId) {
        for (FileRecord f : s.files().underPrefix(volumeId, dir)) {
            if (f.presence == Presence.REMOVED) continue;
            f.presence = Presence.REMOVED; f.change = ChangeState.REMOVED; f.presenceReason = "D_DIR_REMOVED";
            f.changeScanId = scanId; f.removedScanId = scanId;
            s.files().put(f);
        }
        for (DirRecord d : s.directories().underPrefix(volumeId, dir)) gone(s, d, scanId);
        DirRecord self = s.directories().get(volumeId, dir);
        if (self != null) gone(s, self, scanId);
    }

    private static void gone(V2Store s, DirRecord d, long scanId) {
        if (d.status == DirStatus.NOT_FOUND) return;
        d.status = DirStatus.NOT_FOUND; d.lastVisitedScanId = scanId;
        s.directories().put(d);
    }

    /** PRESENT files under dir → UNVERIFIABLE with reason; already-unverifiable records keep their first reason. */
    static void subtreeUnverifiable(V2Store s, String volumeId, String dir, String reasonCode, long scanId) {
        for (FileRecord f : s.files().underPrefix(volumeId, dir)) {
            if (f.presence != Presence.PRESENT) continue;
            f.presence = Presence.UNVERIFIABLE; f.presenceReason = reasonCode;
            s.files().put(f);
        }
    }
}
