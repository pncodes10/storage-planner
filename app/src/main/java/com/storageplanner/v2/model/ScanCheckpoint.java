package com.storageplanner.v2.model;

import java.util.ArrayList;
import java.util.List;

/** DISCOVER progress stored inside ScanSession. */
public final class ScanCheckpoint {
    public List<String> volumeQueue = new ArrayList<String>();
    public List<String> frontier = new ArrayList<String>();
    public int commits;
    public ScanCheckpoint copy() {
        ScanCheckpoint c = new ScanCheckpoint();
        c.volumeQueue = new ArrayList<String>(volumeQueue); c.frontier = new ArrayList<String>(frontier); c.commits = commits;
        return c;
    }
}
