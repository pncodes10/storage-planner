package com.storageplanner.v2test;

import com.storageplanner.v2.device.DeviceProfile;
import com.storageplanner.v2.device.ResourceMonitor;
import com.storageplanner.v2.device.ResourceState;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;

/** Heap from Runtime; battery/thermal unknown; scriptable states for throttle tests. */
public final class JvmResourceMonitor implements ResourceMonitor {
    private final Deque<ResourceState> script = new ArrayDeque<ResourceState>();
    private ResourceState lastScripted;

    public synchronized void script(ResourceState... states) { script.addAll(Arrays.asList(states)); }

    public synchronized ResourceState sample() {
        if (!script.isEmpty()) lastScripted = script.poll();
        if (lastScripted != null) return lastScripted;
        Runtime r = Runtime.getRuntime();
        ResourceState s = new ResourceState();
        s.heapMaxBytes = r.maxMemory();
        s.heapFreeBytes = r.maxMemory() - (r.totalMemory() - r.freeMemory());
        s.cpuCount = r.availableProcessors();
        return s;
    }

    public DeviceProfile profile() {
        String p = System.getProperty("v2.profile");
        try { return p == null ? DeviceProfile.BALANCED : DeviceProfile.valueOf(p.toUpperCase()); }
        catch (IllegalArgumentException e) { return DeviceProfile.BALANCED; }
    }
}
