package com.storageplanner.v2.discovery;

import com.storageplanner.v2.model.*;
import com.storageplanner.v2.store.Codecs;
import com.storageplanner.v2.store.V2Store;
import com.storageplanner.v2.util.JsonWriter;

import java.util.*;

/** Coverage report of one scan (PHASE_1 6). */
public final class CoverageBuilder {
    private CoverageBuilder() {}

    public static final String[] ANOMALIES = {"undecodableNames", "nameCollisions", "invalidNames", "vanishedDuringScan",
            "specialFilesSkipped", "pathTooLong", "symlinksSkipped", "excludedFiles", "readErrors", "statFailed"};

    public static CoverageReport build(V2Store store, long scanId, int maxListed) {
        CoverageReport r = new CoverageReport();
        ScanSession s = store.scanSessions().get(ScanSession.keyOf(scanId));
        if (s == null) { s = new ScanSession(); s.scanId = scanId; }
        r.scanId = scanId; r.kind = s.kind; r.state = s.state; r.resumedCount = s.resumedCount;
        r.grantsChangedDuringScan = s.counter("grantsChangedDuringScan") > 0;
        r.startedAt = s.startedAt; r.endedAt = s.endedAt;
        r.events = s.events; r.eventsDropped = s.eventsDropped;
        r.anomalies = new LinkedHashMap<String, Long>();
        for (String a : ANOMALIES) r.anomalies.put(a, s.counter(a));
        r.volumes = new ArrayList<VolumeCoverage>();
        List<InaccessibleLocation> inacc = new ArrayList<InaccessibleLocation>();
        TreeMap<String, AreaCoverage> areas = new TreeMap<String, AreaCoverage>();
        boolean totalLimited = false;
        List<Volume> vols = new ArrayList<Volume>();
        for (Volume v : store.volumes().all()) vols.add(v);
        CapabilityRegistry caps = new CapabilityRegistry(vols);

        for (Volume v : vols) {
            VolumeCoverage c = new VolumeCoverage();
            c.volumeId = v.volumeId; c.label = v.label; c.kind = v.kind; c.mounted = v.mounted; c.identityScheme = v.identityScheme;
            TreeSet<AccessMode> modes = new TreeSet<AccessMode>();
            if (v.grants != null) for (AccessGrant g : v.grants) if (g.active && g.mode != null) modes.add(g.mode);
            c.modes = new ArrayList<AccessMode>(modes);
            if (v.kind != VolumeKind.DIRECTORY) { c.totalBytes = v.totalBytes; c.freeBytes = v.freeBytes; }
            if (c.totalBytes >= 0 && c.freeBytes >= 0) c.usedBytes = c.totalBytes - c.freeBytes;
            for (FileRecord f : store.files().byVolume(v.volumeId)) {
                if (f.presence == Presence.PRESENT) {
                    c.discoveredFiles++;
                    String areaName = f.area == null ? "unknown" : f.area;
                    String k = v.volumeId + "\u0000" + areaName;
                    AreaCoverage a = areas.get(k);
                    if (a == null) { a = new AreaCoverage(); a.volumeId = v.volumeId; a.area = areaName; areas.put(k, a); }
                    a.files++;
                    if (f.sizeBytes >= 0) { c.discoveredBytes += f.sizeBytes; a.bytes += f.sizeBytes; } else c.unknownSizeFiles++;
                } else if (f.presence == Presence.UNVERIFIABLE) c.filesUnverifiable++;
                else if (f.presence == Presence.VOLUME_ABSENT) c.filesVolumeAbsent++;
                if (f.changeScanId == scanId && f.change != null) {
                    switch (f.change) {
                        case ADDED: c.added++; break; case CHANGED: c.changed++; break;
                        case REMOVED: c.removed++; break; case REPLACED: c.replaced++; break;
                        default: break;
                    }
                }
            }
            if (c.usedBytes >= 0) {
                c.unaccountedBytes = c.usedBytes - c.discoveredBytes;
                if (c.usedBytes > 0) c.coverageRatio = (double) c.discoveredBytes / c.usedBytes;
            }
            List<DirRecord> dirs = new ArrayList<DirRecord>();
            DirRecord root = store.directories().get(v.volumeId, "");
            if (root != null) dirs.add(root);
            for (DirRecord d : store.directories().underPrefix(v.volumeId, "")) dirs.add(d);
            Set<String> restrictedListed = new HashSet<String>();
            for (DirRecord d : dirs) {
                if (d.lastVisitedScanId != scanId || d.status == null) continue;
                c.dirsVisited++;
                switch (d.status) {
                    case OK: c.dirsListedOk++; break;
                    case PARTIAL_IO_ERROR: c.dirsPartial++; break;
                    case PERMISSION_DENIED: c.dirsDenied++; break;
                    case READ_ERROR: case NOT_A_DIRECTORY: c.dirsReadError++; break;
                    case RESTRICTED: c.dirsRestricted++; restrictedListed.add(d.path); break;
                    case NOT_GRANTED: c.dirsNotGranted++; break;
                    case DEPTH_LIMIT: c.dirsDepthLimited++; break;
                    case TRUNCATED_ENTRY_LIMIT: c.dirsTruncated++; break;
                    case TOTAL_LIMIT: c.dirsTotalLimited++; totalLimited = true; break;
                    case LOOP: c.dirsLoop++; break;
                    case SYMLINK_SKIPPED: c.dirsSymlinkSkipped++; break;
                    case OUTSIDE_ROOT: c.dirsOutsideRoot++; break;
                    case EXCLUDED: c.dirsExcluded++; break;
                    default: break;
                }
                if (d.status == DirStatus.OK || d.status == DirStatus.NOT_FOUND) continue;
                InaccessibleLocation l = loc(store, caps, v.volumeId, d.path, reasonOf(d.status), d.error);
                if (d.status == DirStatus.NOT_GRANTED && revoked(store, v.volumeId, d.path)) { l.reason = InaccessReason.PERMISSION_DENIED; l.detail = "revoked"; }
                if (d.status == DirStatus.TRUNCATED_ENTRY_LIMIT) l.detail = "entriesSkipped=" + d.entriesSkipped;
                inacc.add(l);
            }
            if (!v.mounted) inacc.add(loc(store, caps, v.volumeId, "", InaccessReason.VOLUME_ABSENT, null));
            else if (v.restrictedPrefixes != null) {
                for (String p : v.restrictedPrefixes) if (!restrictedListed.contains(p)) inacc.add(loc(store, caps, v.volumeId, p, InaccessReason.OS_RESTRICTED, "not reached"));
            }
            if (v.mounted && v.grants != null) for (AccessGrant g : v.grants) {
                if (!g.active || g.mediaTypes == null) continue;
                InaccessibleLocation l = loc(store, caps, v.volumeId, g.prefix, InaccessReason.MEDIA_ONLY_VISIBILITY, join(g.mediaTypes));
                l.mode = g.mode;
                inacc.add(l);
            }
            r.volumes.add(c);
        }
        Set<String> undecodable = new TreeSet<String>();
        for (ScanEvent e : s.events) if ("D_NAME_UNDECODABLE".equals(e.code)) undecodable.add(e.volumeId + "\u0000" + e.path);
        for (String k : undecodable) {
            int i = k.indexOf('\u0000');
            inacc.add(loc(store, caps, k.substring(0, i), k.substring(i + 1), InaccessReason.UNDECODABLE_NAMES, null));
        }
        Collections.sort(inacc, new Comparator<InaccessibleLocation>() {
            public int compare(InaccessibleLocation a, InaccessibleLocation b) {
                int c = a.volumeId.compareTo(b.volumeId); if (c != 0) return c;
                c = a.path.compareTo(b.path); if (c != 0) return c;
                return a.reason.compareTo(b.reason);
            }
        });
        r.inaccessibleTotal = inacc.size();
        r.inaccessible = new ArrayList<InaccessibleLocation>(inacc.subList(0, Math.min(maxListed, inacc.size())));
        r.areas = new ArrayList<AreaCoverage>(areas.values());
        r.complete = s.state == ScanState.COMPLETED && !totalLimited;
        return r;
    }

    static String join(List<String> l) {
        StringBuilder b = new StringBuilder();
        for (String x : l) { if (b.length() > 0) b.append(','); b.append(x); }
        return b.toString();
    }

    static InaccessReason reasonOf(DirStatus s) {
        switch (s) {
            case PERMISSION_DENIED: return InaccessReason.PERMISSION_DENIED;
            case READ_ERROR: case NOT_A_DIRECTORY: return InaccessReason.READ_ERROR;
            case PARTIAL_IO_ERROR: return InaccessReason.PARTIAL_READ_ERROR;
            case RESTRICTED: return InaccessReason.OS_RESTRICTED;
            case NOT_GRANTED: return InaccessReason.NOT_GRANTED;
            case DEPTH_LIMIT: return InaccessReason.DEPTH_LIMIT;
            case TRUNCATED_ENTRY_LIMIT: return InaccessReason.ENTRY_LIMIT;
            case TOTAL_LIMIT: return InaccessReason.TOTAL_LIMIT;
            case PATH_TOO_LONG: return InaccessReason.PATH_TOO_LONG;
            case LOOP: return InaccessReason.LOOP;
            case SYMLINK_SKIPPED: return InaccessReason.SYMLINK_NOT_FOLLOWED;
            case OUTSIDE_ROOT: return InaccessReason.OUTSIDE_ROOT;
            case EXCLUDED: return InaccessReason.EXCLUDED_BY_USER;
            default: return InaccessReason.NOT_FOUND;
        }
    }

    private static boolean revoked(V2Store store, String volumeId, String path) {
        for (FileRecord f : store.files().underPrefix(volumeId, path)) if ("D_PERMISSION_REVOKED".equals(f.presenceReason)) return true;
        return false;
    }

    private static InaccessibleLocation loc(V2Store store, CapabilityRegistry caps, String volumeId, String path, InaccessReason reason, String detail) {
        InaccessibleLocation l = new InaccessibleLocation();
        l.volumeId = volumeId; l.path = path; l.reason = reason; l.detail = detail;
        AccessGrant g = caps.best(volumeId, path);
        l.mode = g == null ? null : g.mode;
        long n = 0, bytes = 0;
        for (FileRecord f : store.files().underPrefix(volumeId, path)) {
            if (f.presence == Presence.REMOVED) continue;
            n++;
            if (f.sizeBytes >= 0) bytes += f.sizeBytes;
        }
        if (n > 0) { l.knownFiles = n; l.knownBytes = bytes; }
        return l;
    }

    public static void write(JsonWriter w, CoverageReport r) {
        w.beginObject();
        w.name("scanId").value(r.scanId).name("kind").value(r.kind).name("state").value(r.state).name("complete").value(r.complete)
         .name("resumedCount").value(r.resumedCount).name("grantsChangedDuringScan").value(r.grantsChangedDuringScan)
         .name("startedAt").valueTime(r.startedAt).name("endedAt").valueTime(r.endedAt);
        w.name("volumes").beginArray();
        for (VolumeCoverage c : r.volumes) {
            w.beginObject().name("volumeId").value(c.volumeId).name("label").value(c.label).name("kind").value(c.kind)
             .name("mounted").value(c.mounted).name("modes").beginArray();
            for (AccessMode m : c.modes) w.value(m);
            w.endArray();
            w.name("totalBytes").valueCount(c.totalBytes).name("freeBytes").valueCount(c.freeBytes).name("usedBytes").valueCount(c.usedBytes)
             .name("discoveredFiles").value(c.discoveredFiles).name("discoveredBytes").value(c.discoveredBytes)
             .name("unknownSizeFiles").value(c.unknownSizeFiles).name("unaccountedBytes");
            if (c.usedBytes >= 0) w.value(c.unaccountedBytes); else w.nullValue();
            w.name("coverageRatio").value(c.coverageRatio)
             .name("filesUnverifiable").value(c.filesUnverifiable).name("filesVolumeAbsent").value(c.filesVolumeAbsent)
             .name("added").value(c.added).name("changed").value(c.changed).name("removed").value(c.removed).name("replaced").value(c.replaced)
             .name("dirsVisited").value(c.dirsVisited).name("dirsListedOk").value(c.dirsListedOk).name("dirsPartial").value(c.dirsPartial)
             .name("dirsDenied").value(c.dirsDenied).name("dirsReadError").value(c.dirsReadError).name("dirsRestricted").value(c.dirsRestricted)
             .name("dirsNotGranted").value(c.dirsNotGranted).name("dirsDepthLimited").value(c.dirsDepthLimited)
             .name("dirsTruncated").value(c.dirsTruncated).name("dirsTotalLimited").value(c.dirsTotalLimited).name("dirsLoop").value(c.dirsLoop)
             .name("dirsSymlinkSkipped").value(c.dirsSymlinkSkipped).name("dirsOutsideRoot").value(c.dirsOutsideRoot)
             .name("dirsExcluded").value(c.dirsExcluded).endObject();
        }
        w.endArray();
        w.name("inaccessible").beginArray();
        for (InaccessibleLocation l : r.inaccessible) {
            w.beginObject().name("volumeId").value(l.volumeId).name("path").value(l.path).name("reason").value(l.reason).name("mode").value(l.mode)
             .name("knownFiles").valueCount(l.knownFiles).name("knownBytes").valueCount(l.knownBytes).name("detail").value(l.detail).endObject();
        }
        w.endArray();
        w.name("inaccessibleTotal").value(r.inaccessibleTotal);
        w.name("events").beginArray();
        for (ScanEvent e : r.events) Codecs.writeEvent(w, e);
        w.endArray();
        w.name("eventsDropped").value(r.eventsDropped);
        w.name("anomalies").any(r.anomalies);
        w.name("areas").beginArray();
        for (AreaCoverage a : r.areas) w.beginObject().name("volumeId").value(a.volumeId).name("area").value(a.area).name("files").value(a.files).name("bytes").value(a.bytes).endObject();
        w.endArray();
        w.name("extraction").any(r.extraction);
        w.endObject();
    }
}
