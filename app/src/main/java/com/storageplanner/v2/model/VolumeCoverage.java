package com.storageplanner.v2.model;

import java.util.List;

public final class VolumeCoverage {
    public String volumeId, label; public VolumeKind kind; public boolean mounted; public List<AccessMode> modes;
    public String identityScheme;
    public long totalBytes = -1, freeBytes = -1, usedBytes = -1;
    public long discoveredFiles, discoveredBytes;
    public long unknownSizeFiles;
    public long unaccountedBytes = -1;
    public double coverageRatio = Double.NaN;
    public long filesUnverifiable, filesVolumeAbsent;
    public long added, changed, removed, replaced;
    public long dirsVisited, dirsListedOk, dirsPartial, dirsDenied, dirsReadError, dirsRestricted, dirsNotGranted,
                dirsDepthLimited, dirsTruncated, dirsTotalLimited, dirsLoop, dirsSymlinkSkipped, dirsOutsideRoot, dirsExcluded;
}
