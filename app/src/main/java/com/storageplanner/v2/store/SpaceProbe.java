package com.storageplanner.v2.store;

/** Free bytes on the store's partition (PHASE_S_STORE 3.12); −1 = unknown (no space checks). */
public interface SpaceProbe {
    long freeBytes();
}
