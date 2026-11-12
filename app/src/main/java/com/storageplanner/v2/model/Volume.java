package com.storageplanner.v2.model;

import java.util.ArrayList;
import java.util.List;

/** Table storage_volumes. */
public final class Volume implements Record {
    public String volumeId;
    public VolumeKind kind = VolumeKind.UNKNOWN;
    public String label, root, fsUuid;
    public long totalBytes = Unknown.LONG, freeBytes = Unknown.LONG;
    public boolean mounted;
    public Tri fileKeyStable = Tri.UNKNOWN;
    public Tri caseInsensitive = Tri.UNKNOWN;
    public Tri atimeReliable = Tri.FALSE;
    public String identityScheme;
    public List<String> restrictedPrefixes = new ArrayList<String>();
    public List<AccessGrant> grants = new ArrayList<AccessGrant>();
    public long firstSeenAt = Unknown.TIME, lastSeenAt = Unknown.TIME;
    public long lastFullScanId = Unknown.LONG;

    public String key() { return volumeId; }

    public Volume copy() {
        Volume v = new Volume();
        v.volumeId = volumeId; v.kind = kind; v.label = label; v.root = root; v.fsUuid = fsUuid;
        v.totalBytes = totalBytes; v.freeBytes = freeBytes; v.mounted = mounted; v.fileKeyStable = fileKeyStable;
        v.caseInsensitive = caseInsensitive; v.atimeReliable = atimeReliable; v.identityScheme = identityScheme;
        v.restrictedPrefixes = restrictedPrefixes == null ? null : new ArrayList<String>(restrictedPrefixes);
        if (grants == null) v.grants = null;
        else { v.grants = new ArrayList<AccessGrant>(); for (AccessGrant g : grants) v.grants.add(g.copy()); }
        v.firstSeenAt = firstSeenAt; v.lastSeenAt = lastSeenAt; v.lastFullScanId = lastFullScanId;
        return v;
    }
}
