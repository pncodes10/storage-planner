package com.storageplanner.v2.model;

import java.util.ArrayList;
import java.util.List;

/** One capability: what an access mode lets us do under a path prefix of one volume. */
public final class AccessGrant {
    public String volumeId;
    public String prefix = "";
    public AccessMode mode;
    public Tri canList = Tri.UNKNOWN, canReadMetadata = Tri.UNKNOWN, canReadContent = Tri.UNKNOWN,
               canWrite = Tri.FALSE, canDelete = Tri.FALSE;
    public List<String> mediaTypes;   // Mode A only; null = not restricted by media type
    public long firstSeenAt = Unknown.TIME, lastSeenAt = Unknown.TIME, revokedAt = Unknown.TIME;
    public boolean active;

    public String grantId() { return mode + ":" + volumeId + ":" + prefix; }

    public AccessGrant copy() {
        AccessGrant g = new AccessGrant();
        g.volumeId = volumeId; g.prefix = prefix; g.mode = mode;
        g.canList = canList; g.canReadMetadata = canReadMetadata; g.canReadContent = canReadContent;
        g.canWrite = canWrite; g.canDelete = canDelete;
        g.mediaTypes = mediaTypes == null ? null : new ArrayList<String>(mediaTypes);
        g.firstSeenAt = firstSeenAt; g.lastSeenAt = lastSeenAt; g.revokedAt = revokedAt; g.active = active;
        return g;
    }

    /** Convenience: an active grant with all read flags TRUE, write/delete FALSE. */
    public static AccessGrant readAll(String volumeId, String prefix, AccessMode mode) {
        AccessGrant g = new AccessGrant();
        g.volumeId = volumeId; g.prefix = prefix; g.mode = mode;
        g.canList = Tri.TRUE; g.canReadMetadata = Tri.TRUE; g.canReadContent = Tri.TRUE; g.active = true;
        return g;
    }
}
