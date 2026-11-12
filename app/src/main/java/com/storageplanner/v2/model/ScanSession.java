package com.storageplanner.v2.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Table scan_sessions. Key = scanId as 12 digits. */
public final class ScanSession implements Record {
    public static final int MAX_EVENTS = 1000;
    public long scanId;
    public ScanKind kind; public ScanState state;
    public long startedAt = Unknown.TIME, endedAt = Unknown.TIME;
    public int resumedCount;
    public String stage;
    public List<String> stagesDone = new ArrayList<String>();
    public String algorithmVersion; public int scanVersion = Unknown.INT;
    public ScanCheckpoint checkpoint;
    public List<ScanEvent> events = new ArrayList<ScanEvent>();
    public int eventsDropped;
    public Map<String, Long> counters = new TreeMap<String, Long>();
    public String pauseReason;
    public String lastError;
    public List<AccessGrant> grantsAtStart;

    public static String keyOf(long scanId) { return String.format("%012d", scanId); }
    public String key() { return keyOf(scanId); }

    /** Appends an event, respecting the cap of MAX_EVENTS. */
    public void event(ScanEvent e) { if (events.size() < MAX_EVENTS) events.add(e); else eventsDropped++; }
    public void event(long at, String code, String volumeId, String path, String detail) { event(new ScanEvent(at, code, volumeId, path, detail)); }
    public void count(String name, long delta) { Long v = counters.get(name); counters.put(name, (v == null ? 0 : v) + delta); }
    public long counter(String name) { Long v = counters.get(name); return v == null ? 0 : v; }

    public ScanSession copy() {
        ScanSession s = new ScanSession();
        s.scanId = scanId; s.kind = kind; s.state = state; s.startedAt = startedAt; s.endedAt = endedAt;
        s.resumedCount = resumedCount; s.stage = stage; s.stagesDone = new ArrayList<String>(stagesDone);
        s.algorithmVersion = algorithmVersion; s.scanVersion = scanVersion;
        s.checkpoint = checkpoint == null ? null : checkpoint.copy();
        s.events = new ArrayList<ScanEvent>(); for (ScanEvent e : events) s.events.add(e.copy());
        s.eventsDropped = eventsDropped; s.counters = new TreeMap<String, Long>(counters);
        s.pauseReason = pauseReason; s.lastError = lastError;
        if (grantsAtStart != null) { s.grantsAtStart = new ArrayList<AccessGrant>(); for (AccessGrant g : grantsAtStart) s.grantsAtStart.add(g.copy()); }
        return s;
    }
}
