package com.storageplanner.v2.device;

import com.storageplanner.v2.model.Tri;
import com.storageplanner.v2.model.VolumeKind;
import java.util.List;

public final class VolumeInfo {
    public String volumeId; public VolumeKind kind; public String label, root, fsUuid;
    public long totalBytes = -1, freeBytes = -1; public boolean mounted = true;
    public Tri fileKeyStable = Tri.UNKNOWN, caseInsensitive = Tri.UNKNOWN;
    public List<String> restrictedPrefixes;
}
