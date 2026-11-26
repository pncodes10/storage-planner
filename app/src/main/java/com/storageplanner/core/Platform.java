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
     * What the last argb() or luma() call had to fall back on or what went wrong inside it, in one line for the Algorithm Lab
     * (for example "the usual decoder could not open it, the second one did"); null when there is nothing to say.
     */
    String decodeNote();

    /**
     * Faces in a decoded picture (from argb), in the picture's pixels, Faces.F floats per face
     * {x between the eyes, y, eye distance, score 0..1, eye-on-the-left x, y, other eye x, y, nose x, y, mouth corners}
     * (see Faces).
     * Empty array if none; null if detection is unavailable.
     */
    float[] faces(String path, int[] argb, int w, int h);

    /** The scene network (assets/scene/scene.bin), loaded once; null when it is not available. */
    SceneNet sceneNet();

    /** A second face detector for comparison in the Algorithm Lab (same format as faces), or null if there is none. */
    float[] otherFaces(String path, int[] argb, int w, int h);

    /** Name of the detector behind otherFaces, for the Lab. */
    String otherFacesName();

    /**
     * Words in a decoded picture, read on the device (null when no text reader is available or it failed).
     * The picture is the ARGB array from argb(); path identifies the file.
     */
    TextRules.Words readText(String path, int[] argb, int w, int h);

    /**
     * Mirage (Cactus Compute's vision model, Algorithm Lab only) on a decoded ARGB picture: its JSON answer (scene,
     * objects, text lines), or null when it cannot run here (see mirageNote()). options: the engine's JSON options.
     */
    String mirage(int[] argb, int w, int h, String options);

    /** Why mirage() gave null, for the Lab; null when it worked. */
    String mirageNote();

    /** Image dimensions without decoding pixels: dims receives {w, h}. Returns false if unreadable. */
    boolean imageSize(String path, int[] dims);

    /**
     * Frames of a video at these times (microseconds), ARGB with long edge ~longEdge px, upright, all the same size:
     * dims receives {w, h}. A frame that cannot be decoded is null; returns null when the video cannot be opened.
     */
    int[][] videoFrames(String path, long[] timesUs, int longEdge, int[] dims);
    /** Fill vcodec, width, height (as shown, rotation applied), fps, bitrate, durationMs. Return false if unreadable. */
    boolean video(FileItem f);

    /** Audio bitrate in bits/s, or 0. */
    long audioBitrate(String path);

    /** {package, versionCode} of an .apk file, or null. */
    String[] apkInfo(String path);

    /**
     * Absolute paths of the pictures and videos marked as favourites in the gallery (Android 11+), or null when the phone
     * cannot say (older Android, or the gallery index could not be read). An empty set means none is a favourite.
     */
    java.util.Set<String> favourites();

    /** Android API level (for features such as app archiving, API 35+). */
    int sdkInt();

    long now();
}
