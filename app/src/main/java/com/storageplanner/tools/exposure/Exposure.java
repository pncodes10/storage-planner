package com.storageplanner.tools.exposure;

import com.storageplanner.tools.layout.ImageSignals;

/** Shots in which nothing is visible: dark, covered, featureless or shaken; from the layout measurements. */
public final class Exposure {
    private Exposure() {}

    /** Dark (lens covered, pocket), almost featureless, or so shaken that nothing is sharp anywhere; never with text. */
    public static boolean accidental(ImageSignals.S s) {
        if (s.textBlobs >= 20 || s.flat >= 0.3) return false;
        if (s.contrast < 16) return true;                                  // nothing in the picture
        if (s.meanLuma < 25 && s.contrast < 30) return true;               // dark
        return s.sharp >= 0 && s.sharp < 0.12 && s.meanLuma > 25;          // shaken: no sharp detail anywhere
    }

    /**
     * A camera frame that is black from edge to edge (lens covered, in a pocket). JPEG squeezes it into a few
     * levels, so it looks "flat" like a screen and accidental() leaves it alone; from the camera it can only be this.
     */
    public static boolean blackFrame(ImageSignals.S s) {
        return s.darkFrac > 0.97 && s.meanLuma < 15 && s.contrast < 6 && s.textBlobs < 5;
    }
}
