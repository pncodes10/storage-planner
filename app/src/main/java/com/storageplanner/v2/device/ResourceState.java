package com.storageplanner.v2.device;

import com.storageplanner.v2.model.Tri;

public final class ResourceState {
    public long heapFreeBytes = -1, heapMaxBytes = -1, storageFreeBytes = -1;
    public int batteryPercent = -1, cpuCount = -1;
    public Tri charging = Tri.UNKNOWN, userInteractive = Tri.UNKNOWN;
    public Thermal thermal = Thermal.UNKNOWN;
}
