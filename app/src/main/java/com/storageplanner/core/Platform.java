package com.storageplanner.core;

import com.storageplanner.tools.faces.FaceDetector;
import com.storageplanner.tools.ocr.TextReader;

import java.util.List;

/**
 * Everything the core needs from the device. Android and the desktop test harness implement it, including the
 * tools that run differently on each (FaceDetector, TextReader).
 */
public interface Platform extends FaceDetector, TextReader {
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

    /** A second face detector for comparison in the Algorithm Lab (same format as faces), or null if there is none. */
    float[] otherFaces(String path, int[] argb, int w, int h);

    /** Name of the detector behind otherFaces, for the Lab. */
    String otherFacesName();

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
