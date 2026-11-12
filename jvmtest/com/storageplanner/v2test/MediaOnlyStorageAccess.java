package com.storageplanner.v2test;

import com.storageplanner.v2.device.*;
import com.storageplanner.v2.discovery.MediaFamilies;
import com.storageplanner.v2.discovery.PathNormalizer;
import com.storageplanner.v2.model.AccessGrant;
import com.storageplanner.v2.model.AccessMode;
import com.storageplanner.v2.util.Text;

import java.io.IOException;
import java.util.*;

/** Simulates Mode A (MediaStore): listings show only media files of the granted types; directories stay listed. */
public final class MediaOnlyStorageAccess implements StorageAccess {
    private final StorageAccess inner;
    private final List<String> types;
    private final Set<String> hidden = new HashSet<String>();

    public MediaOnlyStorageAccess(StorageAccess inner, String... types) {
        this.inner = inner;
        this.types = new ArrayList<String>(new TreeSet<String>(Arrays.asList(types)));
    }
    /** Hides one media file (simulating .nomedia, pending items or Android 14 partial photo access). */
    public MediaOnlyStorageAccess hide(String path) { hidden.add(path); return this; }

    public List<VolumeInfo> volumes() { return inner.volumes(); }

    public List<AccessGrant> grants() {
        List<AccessGrant> l = new ArrayList<AccessGrant>();
        for (VolumeInfo v : inner.volumes()) {
            AccessGrant g = AccessGrant.readAll(v.volumeId, "", AccessMode.A_MEDIASTORE);
            g.mediaTypes = new ArrayList<String>(types);
            l.add(g);
        }
        return l;
    }

    private boolean visible(String path, DirEntry e) {
        if (hidden.contains(path)) return false;
        if (e.kind == EntryKind.DIRECTORY) return true;
        String fam = MediaFamilies.of(Text.extension(e.name));
        return fam != null && types.contains(fam) && e.kind != EntryKind.SYMLINK;
    }

    public DirListing list(String volumeId, String dirPath) {
        DirListing l = inner.list(volumeId, dirPath);
        if (l.entries == null) return l;
        List<DirEntry> out = new ArrayList<DirEntry>();
        for (DirEntry e : l.entries) if (visible(PathNormalizer.join(dirPath, e.name), e)) out.add(e);
        l.entries = out;
        return l;
    }

    public FileStat stat(String volumeId, String path) { return inner.stat(volumeId, path); }
    public ReadableFile open(String volumeId, String path) throws IOException { return inner.open(volumeId, path); }
    public String dirIdentity(String volumeId, String dirPath) { return inner.dirIdentity(volumeId, dirPath); }
    public String localPath(String volumeId, String path) { return null; }
    public String resolveLink(String volumeId, String path) { return null; }
}
