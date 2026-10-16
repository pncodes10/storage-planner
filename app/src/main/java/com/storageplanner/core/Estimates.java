package com.storageplanner.core;

/**
 * Savings fractions for re-encoding. Defaults come from the research prototype, made
 * conservative for phone hardware encoders. The Android app replaces the photo and
 * screenshot values with numbers MEASURED on a sample of the user's own files.
 */
public final class Estimates {
    // fraction of size saved
    public double cameraPhoto = 0.45;     // prototype: 62% median with x265 HEIC on real photos
    public double chatImage = 0.25;       // prototype: 41% (WhatsApp images, already q~75)
    public double screenshotLossless = 0.25; // prototype: 28% lossless WebP
    public String photoSource = "default estimate";
    public String chatSource = "default estimate";
    public String screenshotSource = "default estimate";

    /** Video: H.264 at high bits-per-pixel re-encodes well; already-efficient codecs do not. */
    public double video(FileItem f) {
        if (f.vcodec == null || f.bitrate <= 0 || f.width <= 0 || f.height <= 0) return 0;
        String c = f.vcodec.toLowerCase();
        if (!(c.contains("avc") || c.contains("h264") || c.contains("mp4v") || c.contains("3gpp"))) return 0;
        double bppf = f.bitrate / ((double) f.width * f.height * 30.0);   // bits per pixel per frame @30 fps
        if (bppf >= 0.12) return 0.45;   // phone camera H.264 (~17 Mbps @1080p = 0.27)
        if (bppf >= 0.06) return 0.25;
        return 0;                        // already low bitrate (chat video, streaming downloads)
    }

    /** Music above 256 kbps -> 128 kbps Opus/AAC. */
    public double music(FileItem f) {
        if (f.bitrate < 256000) return 0;
        return 1.0 - 128000.0 / f.bitrate;
    }

    /** Only re-encode photos that are not already heavily compressed (bits per pixel). */
    public static double bpp(FileItem f) {
        if (f.width <= 0 || f.height <= 0) return 0;
        return f.size * 8.0 / ((double) f.width * f.height);
    }
}
