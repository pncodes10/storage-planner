package com.storageplanner.v2.model;

import java.util.List;
import java.util.Map;

public final class CoverageReport {
    public long scanId; public ScanKind kind; public ScanState state; public boolean complete; public int resumedCount;
    public boolean grantsChangedDuringScan; public long startedAt = Unknown.TIME, endedAt = Unknown.TIME;
    public List<VolumeCoverage> volumes;
    public List<InaccessibleLocation> inaccessible; public int inaccessibleTotal;
    public List<ScanEvent> events; public int eventsDropped;
    public Map<String, Long> anomalies;
    public List<AreaCoverage> areas;
    public Object extraction;
}
