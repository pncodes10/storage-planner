package com.storageplanner.v2test;

import com.storageplanner.v2.device.*;
import com.storageplanner.v2.discovery.PathNormalizer;
import com.storageplanner.v2.model.AccessGrant;
import com.storageplanner.v2.model.Tri;

import java.io.IOException;
import java.util.*;

/** Fault-injecting StorageAccess decorator (tests run as root, so chmod cannot make anything unreadable). */
public final class FaultyStorageAccess implements StorageAccess {
    private final StorageAccess inner;
    private final Set<String> deny = new HashSet<String>(), ioError = new HashSet<String>(), vanish = new HashSet<String>(),
            statFail = new HashSet<String>(), listNotFound = new HashSet<String>();
    private final Map<String, Integer> partial = new HashMap<String, Integer>();
    private final Map<String, List<DirEntry>> inject = new HashMap<String, List<DirEntry>>();
    private final Map<String, String> alias = new HashMap<String, String>();
    private final Map<String, VolumeInfo> volOverride = new HashMap<String, VolumeInfo>();
    private List<AccessGrant> grants;
    private int revokeAfter = -1; private String revokeId;
    private int killOn = -1;
    private int listCalls;
    private final List<String> listed = new ArrayList<String>();

    public FaultyStorageAccess(StorageAccess inner) { this.inner = inner; }

    public FaultyStorageAccess denyList(String path) { deny.add(path); return this; }
    public FaultyStorageAccess ioErrorList(String path) { ioError.add(path); return this; }
    public FaultyStorageAccess partialList(String path, int entriesBeforeError) { partial.put(path, entriesBeforeError); return this; }
    public FaultyStorageAccess vanishOnStat(String path) { vanish.add(path); return this; }
    public FaultyStorageAccess statFails(String path) { statFail.add(path); return this; }
    public FaultyStorageAccess listNotFound(String path) { listNotFound.add(path); return this; }
    public FaultyStorageAccess injectEntry(String dir, DirEntry e) {
        List<DirEntry> l = inject.get(dir); if (l == null) inject.put(dir, l = new ArrayList<DirEntry>()); l.add(e); return this;
    }
    public FaultyStorageAccess aliasIdentity(String path, String identity) { alias.put(path, identity); return this; }
    public FaultyStorageAccess setGrants(List<AccessGrant> g) { grants = g == null ? null : new ArrayList<AccessGrant>(g); return this; }
    public FaultyStorageAccess revokeAfterListCalls(int n, String grantId) { revokeAfter = n; revokeId = grantId; return this; }
    public FaultyStorageAccess killOnListCall(int n) { killOn = n; return this; }
    public FaultyStorageAccess volumeOverrides(VolumeInfo v) { volOverride.put(v.volumeId, v); return this; }
    public FaultyStorageAccess clearRules() {
        deny.clear(); ioError.clear(); vanish.clear(); statFail.clear(); listNotFound.clear(); partial.clear(); inject.clear(); alias.clear();
        revokeAfter = -1; killOn = -1; return this;
    }
    public int listCalls() { return listCalls; }
    public List<String> listedPaths() { return listed; }

    public List<VolumeInfo> volumes() {
        List<VolumeInfo> l = new ArrayList<VolumeInfo>();
        for (VolumeInfo v : inner.volumes()) {
            VolumeInfo o = volOverride.get(v.volumeId);
            if (o != null) { v.totalBytes = o.totalBytes; v.freeBytes = o.freeBytes; v.mounted = o.mounted; if (o.kind != null) v.kind = o.kind; }
            l.add(v);
        }
        return l;
    }

    public List<AccessGrant> grants() {
        List<AccessGrant> base = grants != null ? new ArrayList<AccessGrant>() : inner.grants();
        if (grants != null) for (AccessGrant g : grants) base.add(g.copy());
        if (revokeAfter >= 0 && listCalls >= revokeAfter) {
            for (Iterator<AccessGrant> it = base.iterator(); it.hasNext(); ) if (it.next().grantId().equals(revokeId)) it.remove();
        }
        return base;
    }

    private boolean granted(String volumeId, String path) {
        AccessGrant best = null;
        for (AccessGrant g : grants()) {
            if (!volumeId.equals(g.volumeId) || !PathNormalizer.isUnder(path, g.prefix)) continue;
            if (best == null || g.prefix.length() > best.prefix.length()) best = g;
        }
        return best != null && best.canList == Tri.TRUE;
    }

    public DirListing list(String volumeId, String dirPath) {
        listCalls++;
        listed.add(dirPath);
        if (listCalls == killOn) throw new SimulatedKill("kill on list call " + listCalls);
        if (listNotFound.contains(dirPath)) return DirListing.failed(IoStatus.NOT_FOUND, "injected NOT_FOUND");
        if (deny.contains(dirPath)) return DirListing.failed(IoStatus.PERMISSION_DENIED, "injected permission denied");
        if (ioError.contains(dirPath)) return DirListing.failed(IoStatus.IO_ERROR, "injected I/O error");
        if (!granted(volumeId, dirPath)) return DirListing.failed(IoStatus.PERMISSION_DENIED, "no grant");
        DirListing l = inner.list(volumeId, dirPath);
        if (l.status != IoStatus.OK && l.status != IoStatus.PARTIAL_IO_ERROR) return l;
        List<DirEntry> out = new ArrayList<DirEntry>();
        for (DirEntry e : l.entries) {
            String p = PathNormalizer.join(dirPath, e.name);
            if (vanish.contains(p) || statFail.contains(p)) { e.kind = EntryKind.UNKNOWN; e.size = -1; e.inode = -1; e.mtime = Long.MIN_VALUE; e.physicalKey = null; }
            out.add(e);
        }
        List<DirEntry> inj = inject.get(dirPath);
        if (inj != null) for (DirEntry e : inj) out.add(e.copy());
        Collections.sort(out, new Comparator<DirEntry>() { public int compare(DirEntry a, DirEntry b) { return a.name.compareTo(b.name); } });
        l.entries = out;
        Integer n = partial.get(dirPath);
        if (n != null && n < out.size()) {
            l.entries = new ArrayList<DirEntry>(out.subList(0, n));
            l.status = IoStatus.PARTIAL_IO_ERROR;
            l.error = "injected partial listing";
        }
        return l;
    }

    public FileStat stat(String volumeId, String path) {
        if (vanish.contains(path)) return FileStat.failed(IoStatus.NOT_FOUND, "injected vanish");
        if (statFail.contains(path)) return FileStat.failed(IoStatus.IO_ERROR, "injected stat failure");
        return inner.stat(volumeId, path);
    }

    // Phase 2 open/read hooks
    private final Set<String> denyOpen = new HashSet<String>();
    private final Map<String, Long> failReadAfter = new HashMap<String, Long>();
    private final Map<String, Runnable> mutateAfterOpen = new HashMap<String, Runnable>();
    /** Bytes read through open() files, per path (tests of "not read"). */
    public final Map<String, Long> bytesRead = new HashMap<String, Long>();
    public int opens;
    public FaultyStorageAccess denyOpen(String path) { denyOpen.add(path); return this; }
    public FaultyStorageAccess failReadAfter(String path, long bytes) { failReadAfter.put(path, bytes); return this; }
    public FaultyStorageAccess mutateAfterOpen(String path, Runnable r) { mutateAfterOpen.put(path, r); return this; }
    public long totalBytesRead() { long n = 0; for (long v : bytesRead.values()) n += v; return n; }

    public ReadableFile open(String volumeId, final String path) throws IOException {
        if (denyOpen.contains(path)) throw new java.nio.file.AccessDeniedException(path);
        final ReadableFile f = inner.open(volumeId, path);
        opens++;
        Runnable m = mutateAfterOpen.remove(path);
        if (m != null) m.run();
        final Long limit = failReadAfter.get(path);
        return new ReadableFile() {
            long done;
            public long size() throws IOException { return f.size(); }
            public int read(long position, byte[] buf, int off, int len) throws IOException {
                if (limit != null && done + len > limit) throw new IOException("injected read error after " + limit + " bytes");
                int n = f.read(position, buf, off, len);
                if (n > 0) { done += n; Long v = bytesRead.get(path); bytesRead.put(path, (v == null ? 0 : v) + n); }
                return n;
            }
            public void close() throws IOException { f.close(); }
        };
    }

    public String dirIdentity(String volumeId, String dirPath) {
        String a = alias.get(dirPath);
        return a != null ? a : inner.dirIdentity(volumeId, dirPath);
    }

    public String localPath(String volumeId, String path) { return inner.localPath(volumeId, path); }
    public String resolveLink(String volumeId, String path) { return inner.resolveLink(volumeId, path); }
}
