package com.storageplanner.v2test;

import com.storageplanner.v2.device.ScanControl;

/** ScanControl for tests: cancel or pause after n progress reports; optional hook per progress. */
public final class TestControl implements ScanControl {
    private int cancelAfter = -1, pauseAfter = -1, progress;
    private boolean cancel, pause;
    private int hookAt = -1; private Runnable hook;

    public TestControl cancelAfterProgress(int n) { cancelAfter = n; return this; }
    public TestControl pauseAfterProgress(int n) { pauseAfter = n; return this; }
    public TestControl onProgress(int n, Runnable r) { hookAt = n; hook = r; return this; }
    public int progressCalls() { return progress; }

    public boolean cancelRequested() { return cancel; }
    public boolean pauseRequested() { return pause; }
    public void progress(String stage, long done, long total) {
        progress++;
        if (progress == cancelAfter) cancel = true;
        if (progress == pauseAfter) pause = true;
        if (progress == hookAt && hook != null) hook.run();
    }
}
