package com.storageplanner.v2.discovery;

import com.storageplanner.v2.device.*;
import com.storageplanner.v2.model.*;
import com.storageplanner.v2.stage.CommitHook;
import com.storageplanner.v2.stage.StageOutcome;
import com.storageplanner.v2.stage.Throttle;
import com.storageplanner.v2.store.StoreException;
import com.storageplanner.v2.store.V2Store;
import com.storageplanner.v2.util.Text;

import java.util.*;

/**
 * Depth-first directory walker of one volume (PHASE_1 4.3). Resumable: its only durable state is
 * session.checkpoint.frontier plus the records it writes; a directory on top of the frontier is re-processed
 * from its start after a kill.
 */
public final class Walker {
    private static final long PROBE_EVERY_NANOS = 2000L * 1000000L;
    private static final int MAX_PATH_EVENTS = 100;

    private final StorageAccess sa;
    private final V2Store store;
    private final Volume vol;
    private CapabilityRegistry caps;
    private final WalkLimits lim;
    private final Clock clock;
    private final ScanControl control;
    private final Throttle throttle;
    private final ScanSession session;
    private final CommitHook commit;
    private final Set<String> installed;
    private final List<String> frontier;
    private final Set<String> visited = new HashSet<String>();
    private final long scan;
    private final boolean ino;
    private long entriesThisWalk;
    private int sinceCommit;
    private long lastCommitNanos;
    private long lastProbeNanos = Long.MIN_VALUE;
    private boolean complete = true;
    private long dirsDone;
    // per-directory buffers, merged into the session when the directory is finished (exact counts after a resume)
    private final TreeMap<String, Long> dirCounters = new TreeMap<String, Long>();
    private final List<ScanEvent> dirEvents = new ArrayList<ScanEvent>();

    public Walker(StorageAccess sa, V2Store store, Volume vol, CapabilityRegistry caps, WalkLimits lim,
                  Clock clock, ScanControl control, Throttle throttle, ScanSession session, CommitHook commit,
                  Set<String> installedPackages) {
        this.sa = sa; this.store = store; this.vol = vol; this.caps = caps; this.lim = lim; this.clock = clock;
        this.control = control; this.throttle = throttle; this.session = session; this.commit = commit;
        this.installed = installedPackages;
        if (session.checkpoint == null) session.checkpoint = new ScanCheckpoint();
        this.frontier = session.checkpoint.frontier;
        this.scan = session.scanId;
        this.ino = "ino".equals(vol.identityScheme);
        this.lastCommitNanos = clock.monotonicNanos();
        String top = frontier.isEmpty() ? null : frontier.get(frontier.size() - 1);
        List<DirRecord> mine = new ArrayList<DirRecord>();
        DirRecord root = store.directories().get(vol.volumeId, "");
        if (root != null) mine.add(root);
        for (DirRecord d : store.directories().underPrefix(vol.volumeId, "")) mine.add(d);
        for (DirRecord d : mine) {
            if (d.lastVisitedScanId != scan || d.path.equals(top) || d.status == DirStatus.NOT_FOUND) continue;
            if (d.identity != null) visited.add(d.identity);
            if (d.entries > 0) entriesThisWalk += d.entries;
        }
    }

    /** False when the walk was cut by maxTotalEntries or the volume vanished. */
    public boolean complete() { return complete; }
    /** Current capabilities (rebuilt when a permission probe found a change). */
    public CapabilityRegistry caps() { return caps; }

    /** Walks vol from session.checkpoint.frontier. Returns DONE (frontier empty), PAUSED, CANCELLED. */
    public StageOutcome walk() throws StoreException {
        while (!frontier.isEmpty()) {
            if (control != null && control.cancelRequested()) { commitNow(); return StageOutcome.CANCELLED; }
            boolean userPause = control != null && control.pauseRequested();
            if (userPause || (throttle != null && throttle.shouldPause())) {
                session.pauseReason = userPause ? "PL_PAUSED_USER" : throttle.pauseReason;
                commitNow();
                return StageOutcome.PAUSED;
            }
            String dir = frontier.get(frontier.size() - 1);
            List<String> kids = processDirectory(dir);
            if (!frontier.isEmpty() && frontier.get(frontier.size() - 1).equals(dir)) frontier.remove(frontier.size() - 1);
            for (int i = kids.size() - 1; i >= 0; i--) frontier.add(kids.get(i));
            flushDirBuffers();
            dirsDone++;
            if (control != null) control.progress("DISCOVER", dirsDone, -1);
            commitNow();
        }
        return StageOutcome.DONE;
    }

    private void commitNow() throws StoreException {
        session.checkpoint.commits++;
        commit.commit();
        sinceCommit = 0;
        lastCommitNanos = clock.monotonicNanos();
    }

    private void count(String name) { Long v = dirCounters.get(name); dirCounters.put(name, (v == null ? 0 : v) + 1); }
    private long counted(String name) { Long v = dirCounters.get(name); return session.counter(name) + (v == null ? 0 : v); }
    private void dirEvent(String code, String path, String detail) { dirEvents.add(new ScanEvent(clock.nowMillis(), code, vol.volumeId, path, detail)); }

    private void flushDirBuffers() {
        for (Map.Entry<String, Long> e : dirCounters.entrySet()) session.count(e.getKey(), e.getValue());
        for (ScanEvent e : dirEvents) session.event(e);
        dirCounters.clear();
        dirEvents.clear();
    }

    private void subtree(String dir, String reason) { DirectoryReconciler.subtreeUnverifiable(store, vol.volumeId, dir, reason, scan); }

    private DirRecord dirRecord(String path) {
        DirRecord d = store.directories().get(vol.volumeId, path);
        if (d == null) { d = new DirRecord(); d.volumeId = vol.volumeId; d.path = path; }
        return d;
    }

    /** A child (or frontier) directory that is recorded but not listed. */
    private void special(String path, String rawPath, DirStatus status, String error) {
        DirRecord d = dirRecord(path);
        if (rawPath != null) d.rawPath = rawPath.equals(path) ? null : rawPath;
        d.status = status; d.lastVisitedScanId = scan; d.identity = null; d.error = error;
        store.directories().put(d);
    }

    private boolean isRestricted(String path) {
        if (vol.restrictedPrefixes == null) return false;
        for (String p : vol.restrictedPrefixes) if (!p.isEmpty() && PathNormalizer.isUnder(path, p)) return true;
        return false;
    }

    private List<String> processDirectory(String dir) throws StoreException {
        List<String> kids = new ArrayList<String>();
        DirRecord rec = dirRecord(dir);
        String raw = rec.rawPath != null ? rec.rawPath : dir;
        if (!caps.canList(vol.volumeId, dir)) {
            special(dir, null, DirStatus.NOT_GRANTED, null);
            subtree(dir, "D_NOT_GRANTED");
            return kids;
        }
        int depth = PathNormalizer.depth(dir);
        String id = sa.dirIdentity(vol.volumeId, raw);
        if (id != null && visited.contains(id)) {
            rec.status = DirStatus.LOOP; rec.lastVisitedScanId = scan; rec.identity = id; rec.error = null;
            store.directories().put(rec);
            dirEvent("D_LOOP", dir, id);
            subtree(dir, "D_LOOP");
            return kids;
        }
        if (id != null) visited.add(id);
        rec.identity = id;
        long lastOk = rec.lastListedOkScanId;
        DirListing listing = sa.list(vol.volumeId, raw);
        IoStatus ls = listing.status == null ? IoStatus.IO_ERROR : listing.status;
        if (ls == IoStatus.NOT_FOUND) {
            rec.status = DirStatus.NOT_FOUND; rec.lastVisitedScanId = scan; rec.error = listing.error;
            store.directories().put(rec);
            subtree(dir, "D_VANISHED_DURING_SCAN");
            if (dir.isEmpty()) volumeAbsent();
            return kids;
        }
        if (ls != IoStatus.OK && ls != IoStatus.PARTIAL_IO_ERROR) {
            DirStatus ds = ls == IoStatus.PERMISSION_DENIED ? DirStatus.PERMISSION_DENIED
                    : ls == IoStatus.NOT_A_DIRECTORY ? DirStatus.NOT_A_DIRECTORY : DirStatus.READ_ERROR;
            if (ds == DirStatus.READ_ERROR) count("readErrors");
            String reason = "D_PARENT_UNREADABLE";
            if (Unknown.knownCount(lastOk) && permissionProbe(dir)) reason = "D_PERMISSION_REVOKED";
            rec.status = ds; rec.lastVisitedScanId = scan; rec.error = listing.error;
            store.directories().put(rec);
            subtree(dir, reason);
            return kids;
        }

        // names: NFC keys; a non-NFC twin keeps its raw name as key
        TreeMap<String, DirEntry> map = new TreeMap<String, DirEntry>();
        Set<String> suspect = new HashSet<String>();
        for (DirEntry e : listing.entries) {
            String n;
            try { n = PathNormalizer.normalizeName(e.name); }
            catch (IllegalArgumentException x) { count("invalidNames"); continue; }
            String key = n;
            if (map.containsKey(n)) {
                DirEntry other = map.get(n);
                if (!e.name.equals(n)) key = e.name;
                else { map.remove(n); map.put(other.name, other); if (n.indexOf('�') >= 0) { suspect.remove(n); suspect.add(other.name); } }
                count("nameCollisions");
                dirEvent("D_NAME_COLLISION", dir, Text.escape(e.name));
                if (map.containsKey(key)) continue;
            }
            map.put(key, e);
            if (key.indexOf('�') >= 0) suspect.add(key);
        }
        List<String> keys = new ArrayList<String>(map.keySet());
        DirStatus status = null;
        int skipped = 0, limit = keys.size();
        if (keys.size() > lim.maxEntriesPerDir) { limit = lim.maxEntriesPerDir; skipped = keys.size() - limit; status = DirStatus.TRUNCATED_ENTRY_LIMIT; }
        Map<String, EntryKind> names = new HashMap<String, EntryKind>();
        for (String k : keys) names.put(k, map.get(k).kind);
        boolean listingAuthoritative = ls == IoStatus.OK;
        long now = clock.nowMillis();

        for (int i = 0; i < limit; i++) {
            if (sinceCommit >= lim.commitEvery || (clock.monotonicNanos() - lastCommitNanos) / 1000000L >= lim.commitEveryMs) commitNow();
            sinceCommit++;
            String n = keys.get(i);
            DirEntry e = map.get(n);
            String child = PathNormalizer.join(dir, n);
            String childRaw = PathNormalizer.join(raw, e.name);
            if (++entriesThisWalk > lim.maxTotalEntries) {
                status = DirStatus.TOTAL_LIMIT;
                totalLimit(dir, kids, keys, map, i);
                break;
            }
            FileStat st = null;
            EntryKind kind = e.kind == null ? EntryKind.UNKNOWN : e.kind;
            long mtime = e.mtime, inode = e.inode;
            if (kind == EntryKind.UNKNOWN) {
                st = sa.stat(vol.volumeId, childRaw);
                if (st.status == IoStatus.OK) { kind = st.kind; mtime = st.mtime; inode = st.inode; }
            }
            names.put(n, kind);
            if (lim.excluded.contains(child)) {
                if (kind == EntryKind.DIRECTORY || kind == EntryKind.SYMLINK) { special(child, childRaw, DirStatus.EXCLUDED, null); subtree(child, "D_EXCLUDED"); }
                else { count("excludedFiles"); fileUnverifiable(child, "D_EXCLUDED"); }
                continue;
            }
            if (child.length() > lim.maxPathChars) {
                count("pathTooLong");
                if (counted("pathTooLong") <= MAX_PATH_EVENTS) dirEvent("D_PATH_TOO_LONG", dir, Text.truncate(n, 200));
                if (kind == EntryKind.DIRECTORY) { special(child, childRaw, DirStatus.PATH_TOO_LONG, null); subtree(child, "D_PATH_TOO_LONG"); }
                else fileUnverifiable(child, "D_PATH_TOO_LONG");
                continue;
            }
            switch (kind) {
                case DIRECTORY:
                    directory(child, childRaw, mtime, inode, depth, kids);
                    break;
                case SYMLINK:
                    symlink(child, childRaw, st, depth, kids);
                    break;
                case FILE:
                    if (st == null && (e.size < 0 || (ino && e.inode < 0))) st = sa.stat(vol.volumeId, childRaw);
                    if (st != null && st.status == IoStatus.NOT_FOUND) vanished(dir, child, n, e, suspect, listingAuthoritative);
                    else upsert(child, childRaw, e, st, now);
                    break;
                case OTHER:
                    count("specialFilesSkipped");
                    break;
                default:   // UNKNOWN: stat failed
                    if (st != null && st.status == IoStatus.NOT_FOUND) vanished(dir, child, n, e, suspect, listingAuthoritative);
                    else {
                        DirRecord known = store.directories().get(vol.volumeId, child);
                        if (known != null && known.status != DirStatus.NOT_FOUND) { count("statFailed"); subtree(child, "D_STAT_FAILED"); }
                        else upsert(child, childRaw, e, st, now);
                    }
            }
        }
        if (ls == IoStatus.PARTIAL_IO_ERROR && status != DirStatus.TOTAL_LIMIT) status = DirStatus.PARTIAL_IO_ERROR;
        else if (status == null) status = DirStatus.OK;
        boolean authoritative = status == DirStatus.OK || status == DirStatus.TRUNCATED_ENTRY_LIMIT;
        DirectoryReconciler.reconcile(store, vol, caps, dir, names, authoritative, scan);
        rec.status = status; rec.lastVisitedScanId = scan;
        if (status == DirStatus.OK) rec.lastListedOkScanId = scan;
        rec.entries = map.size(); rec.entriesSkipped = skipped;
        rec.error = ls == IoStatus.PARTIAL_IO_ERROR ? listing.error : null;
        if (dir.isEmpty()) {
            FileStat rs = sa.stat(vol.volumeId, raw);
            if (rs.status == IoStatus.OK) { rec.mtime = rs.mtime; rec.inode = rs.inode; }
        }
        store.directories().put(rec);
        return kids;
    }

    private void upsert(String child, String childRaw, DirEntry e, FileStat st, long now) throws StoreException {
        if (FileUpsert.apply(store, vol, caps, child, childRaw, e, st, scan, now, installed) == FileUpsert.STAT_FAILED) count("statFailed");
    }

    private void vanished(String dir, String child, String n, DirEntry e, Set<String> suspect, boolean authoritative) {
        if (suspect.contains(n)) {
            count("undecodableNames");
            dirEvent("D_NAME_UNDECODABLE", dir, Text.escape(e.name));
        } else {
            count("vanishedDuringScan");
            FileUpsert.vanished(store, caps, vol.volumeId, child, authoritative, scan);
        }
    }

    private void fileUnverifiable(String path, String reason) {
        FileRecord f = store.files().byPath(vol.volumeId, path);
        if (f != null && f.presence == Presence.PRESENT) { f.presence = Presence.UNVERIFIABLE; f.presenceReason = reason; store.files().put(f); }
    }

    private void directory(String child, String childRaw, long mtime, long inode, int depth, List<String> kids) {
        if (depth + 1 > lim.maxDepth) { special(child, childRaw, DirStatus.DEPTH_LIMIT, null); subtree(child, "D_DEPTH_LIMIT"); return; }
        if (isRestricted(child)) { special(child, childRaw, DirStatus.RESTRICTED, null); subtree(child, "D_OS_RESTRICTED"); return; }
        if (!caps.canList(vol.volumeId, child)) { special(child, childRaw, DirStatus.NOT_GRANTED, null); subtree(child, "D_NOT_GRANTED"); return; }
        kids.add(child);
        DirRecord d = dirRecord(child);
        d.rawPath = childRaw.equals(child) ? null : childRaw;
        if (Unknown.knownTime(mtime)) d.mtime = mtime;
        if (inode >= 0) d.inode = inode;
        store.directories().put(d);
    }

    private void symlink(String child, String childRaw, FileStat st, int depth, List<String> kids) {
        if (!lim.followSymlinks) {
            FileStat ls = st != null ? st : sa.stat(vol.volumeId, childRaw);
            special(child, childRaw, DirStatus.SYMLINK_SKIPPED, ls.status == IoStatus.OK ? ls.linkTarget : null);
            count("symlinksSkipped");
            return;
        }
        String target = sa.resolveLink(vol.volumeId, childRaw);
        if (target == null) { special(child, childRaw, DirStatus.OUTSIDE_ROOT, null); subtree(child, "D_SYMLINK"); return; }
        FileStat ts = sa.stat(vol.volumeId, target);
        if (ts.status != IoStatus.OK || ts.kind != EntryKind.DIRECTORY) {
            special(child, childRaw, DirStatus.SYMLINK_SKIPPED, target);
            count("symlinksSkipped");
            return;
        }
        String id = sa.dirIdentity(vol.volumeId, childRaw);
        if (id != null && visited.contains(id)) {
            special(child, childRaw, DirStatus.LOOP, id);
            dirEvent("D_LOOP", child, id);
            subtree(child, "D_LOOP");
            return;
        }
        directory(child, childRaw, ts.mtime, ts.inode, depth, kids);
    }

    /** maxTotalEntries reached inside dir: everything not yet walked is recorded TOTAL_LIMIT. */
    private void totalLimit(String dir, List<String> kids, List<String> keys, TreeMap<String, DirEntry> map, int from) {
        complete = false;
        List<String> rest = new ArrayList<String>();
        for (String p : frontier) if (!p.equals(dir)) rest.add(p);
        rest.addAll(kids);
        for (int i = from; i < keys.size(); i++) {
            DirEntry e = map.get(keys.get(i));
            if (e.kind == EntryKind.DIRECTORY) rest.add(PathNormalizer.join(dir, keys.get(i)));
        }
        for (String p : rest) { special(p, null, DirStatus.TOTAL_LIMIT, null); subtree(p, "D_TOTAL_LIMIT"); }
        kids.clear();
        frontier.clear();
    }

    private void volumeAbsent() {
        Volume v = store.volumes().get(vol.volumeId);
        if (v == null) v = vol;
        v.mounted = false;
        vol.mounted = false;
        store.volumes().put(v);
        dirEvent("D_VOLUME_ABSENT", "", "root listing NOT_FOUND");
        VolumeRegistry.markAbsent(store, vol.volumeId);
        frontier.clear();
        complete = false;
    }

    /** Re-reads grants after a listing that worked before now fails; true if a grant covering dir was revoked. */
    private boolean permissionProbe(String dir) {
        long t = clock.monotonicNanos();
        if (lastProbeNanos != Long.MIN_VALUE && t - lastProbeNanos < PROBE_EVERY_NANOS) return false;
        lastProbeNanos = t;
        long now = clock.nowMillis();
        List<Volume> vols = new ArrayList<Volume>();
        for (Volume v : store.volumes().all()) if (v.mounted) vols.add(v);
        List<ScanEvent> ev = CapabilityRegistry.refresh(vols, sa.grants(), now);
        if (ev.isEmpty()) return false;
        for (ScanEvent e : ev) session.event(e);
        session.event(now, "D_GRANTS_CHANGED_DURING_SCAN", vol.volumeId, dir, null);
        for (Volume v : vols) {
            store.volumes().put(v);
            if (v.volumeId.equals(vol.volumeId)) vol.grants = v.grants;
        }
        caps = new CapabilityRegistry(vols);
        for (ScanEvent e : ev)
            if ("D_GRANT_REVOKED".equals(e.code) && vol.volumeId.equals(e.volumeId) && PathNormalizer.isUnder(dir, e.path)) return true;
        return false;
    }
}
