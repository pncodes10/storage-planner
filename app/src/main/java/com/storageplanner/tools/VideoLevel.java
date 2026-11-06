package com.storageplanner.tools;

/**
 * How far a video is compressed. Each level is a quality target (worst checked frame) plus a size policy; the
 * bitrate that reaches the target is searched for each video (see {@link VideoSearch}), so the saving is what the
 * video allows, not a fixed percentage. Plain Java. Thresholds and starting points come from
 * docs/video-compression-research.md and are to be calibrated in the Lab on a phone.
 */
public enum VideoLevel {
    //          label               maxShort maxFps  ssim   psnr  chroma  bpp    audioKbps
    IDENTICAL ("Looks identical",   0,       0,      0.985, 40.0, 38.0,   0.100, 0),
    HIGH      ("High",              0,       0,      0.970, 37.0, 36.0,   0.070, 0),
    BALANCED  ("Balanced",          1080,    30,     0.950, 34.0, 34.0,   0.050, 0),
    SAVER     ("Space saver",       720,     30,     0.930, 32.0, 32.0,   0.045, 96),
    MAXIMUM   ("Maximum",           480,     30,     0.900, 29.0, 30.0,   0.035, 64);

    /** No video bitrate below this is tried at any level (the quality target decides above it). */
    public static final int FLOOR_BPS = 100_000;

    public final String label;
    /** Largest short side of the copy in pixels (0 = keep the size). 1080 means at most 1080p. */
    public final int maxShortSide;
    /** Highest frame rate of the copy (0 = keep). */
    public final int maxFps;
    /** The worst checked frame must reach these (Y SSIM, Y PSNR dB, Cb/Cr PSNR dB). */
    public final double ssimMin, psnrMin, chromaPsnrMin;
    /** Where the bitrate search starts, in bits per pixel per frame of the copy (HEVC; H.264 needs ~1.4x). */
    public final double startBpp;
    /** Audio is re-encoded to AAC at this bitrate when that is smaller than the original's (0 = always copy it). */
    public final int audioKbps;

    VideoLevel(String label, int maxShortSide, int maxFps, double ssimMin, double psnrMin, double chromaPsnrMin,
               double startBpp, int audioKbps) {
        this.label = label; this.maxShortSide = maxShortSide; this.maxFps = maxFps;
        this.ssimMin = ssimMin; this.psnrMin = psnrMin; this.chromaPsnrMin = chromaPsnrMin;
        this.startBpp = startBpp; this.audioKbps = audioKbps;
    }

    /** Levels 1-2 keep the resolution and are compared at half size (grain no encoder keeps averages out there). */
    public boolean keepsSize() { return maxShortSide == 0; }

    public int number() { return ordinal() + 1; }
}
