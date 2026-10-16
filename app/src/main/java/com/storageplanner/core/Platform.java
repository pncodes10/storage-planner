package com.storageplanner.core;

import java.util.List;

/** Everything the core needs from the device. Android and the desktop test harness implement it. */
public interface Platform {
    /** Root of shared storage (e.g. /storage/emulated/0). */
    String storageRoot();

    /** Total and free bytes of internal storage. */
    long totalBytes();
    long freeBytes();

    /** Installed apps with sizes and usage. */
    List<AppItem> apps();

    /**
     * Decode an image to 8-bit luma with long edge ~1024 px.
     * dims receives {originalW, originalH, lumaW, lumaH}. Returns null if it cannot be decoded.
     */
    int[] luma(String path, int[] dims);

    /**
     * Decode an image to ARGB with long edge ~longEdge px, upright (EXIF rotation applied).
     * dims receives {originalW, originalH, w, h}. Returns null if it cannot be decoded.
     */
    int[] argb(String path, int longEdge, int[] dims);

    /**
     * Faces in a decoded picture (from argb): 4 floats per face {x between the eyes, y, eye distance,
     * detector confidence 0..1}, in the picture's pixels. Empty array if none; null if detection is unavailable.
     */
    float[] faces(String path, int[] argb, int w, int h);

    /**
     * Words in a decoded picture, read on the device (null when no text reader is available or it failed).
     * The picture is the ARGB array from argb(); path identifies the file.
     */
    TextRules.Words readText(String path, int[] argb, int w, int h);

    /** Image dimensions without decoding pixels: dims receives {w, h}. Returns false if unreadable. */
    boolean imageSize(String path, int[] dims);

    /** Fill vcodec, width, height, bitrate, durationMs. Return false if unreadable. */
    boolean video(FileItem f);

    /** Audio bitrate in bits/s, or 0. */
    long audioBitrate(String path);

    /** {package, versionCode} of an .apk file, or null. */
    String[] apkInfo(String path);

    /** Android API level (for features such as app archiving, API 35+). */
    int sdkInt();

    long now();
}
