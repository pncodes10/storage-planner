package com.storageplanner.v2.device;

/** Supplied by the caller (service / CLI / test). */
public interface ScanControl {
    boolean cancelRequested();
    boolean pauseRequested();
    void progress(String stage, long done, long total);
}
