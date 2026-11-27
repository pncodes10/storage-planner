package com.storageplanner.core;

/** One file on shared storage plus everything the analyzer learned about it. */
public final class FileItem {
    public final String rel;      // path relative to the storage root, '/' separated
    public final String abs;      // absolute path
    public final long size;
    public final long mtime;      // epoch ms
    public String area;           // storage area (camera, wa_image, download, ...)
    public String pkg;            // owning package for Android/* paths, else null
    public String type;           // type from magic bytes (jpeg, png, mp4, ...)

    public String sha256;         // set only for files that share a size with another file
    public boolean hasPhash;
    public long phash;
    public double sharp = -1;     // 90th percentile of local Laplacian variance (1024 px scale)
    public int width, height;     // image or video dimensions

    public String vcodec;         // video codec mime (video/avc, video/hevc, ...)
    public long bitrate;          // bits per second (video or audio)
    public long durationMs;
    public float fps;             // frames per second from the video track; 0 = unknown
    // ---- what the frames of a video showed (VideoCheck); only when videoVersion > 0
    public int videoVersion;      // VideoCheck.VERSION when its frames were looked at; 0 = not looked at
    public int vFrames;           // frames decoded
    public int vDark;             // of those, dark or flat (covered lens, pocket)
    public double vMotion = -1;   // mean fingerprint bits changing between neighbouring frames; -1 = unknown
    public double vSharpMax = -1; // sharpness of its sharpest frame (ImageFeatures scale); -1 = unknown
    public int vPeople = -1;      // most faces in one frame; -1 = could not be checked
    public int vWords = -1;       // words read in the middle frame; -1 = not read
    public long vHash;            // fingerprint of the middle frame (when vHashed)
    public boolean vHashed;       // vHash was measured
    // ---- what the video's index said (VideoIndex), no decoding
    public boolean vIndexed;      // the index could be read
    public int vKeyframes;        // keyframes in it
    public double vLowDetail = -1;// share of keyframes that show next to nothing
    public double vStill = -1;    // share of the other frames that change next to nothing; -1 = unknown
    public int vAudio = -1;       // 1 = has a sound track, 0 = none, -1 = unknown
    public double vQuiet = -1;    // share of quiet seconds; -1 = unknown or no sound

    public PhotoQuality.Q quality;  // measured for near-duplicate shots only (eyes, faces, light); else null

    // ---- picture sorting (1.6)
    public String category;       // PhotoCategory id; null = not a sorted picture
    public String code;           // what a QR code / barcode in it is for
    public long catDate;          // a date read in the picture (ticket, receipt), 0 if none
    public String text;           // words read in the picture (lower case); never kept for ID documents
    public boolean textPending;   // worth reading, but not read yet (time limit or no reader)
    public int faces = -1;        // faces found (-1 = not checked)
    public int eyeFaces = -1;     // faces whose eyes were looked at (big enough, not far in the background); -1 = not looked at
    public int eyesOpen = -1;     // of those, faces with eyes open; -1 = not looked at
    /** Picture measurements (brightness, text, paper, screens, ...) kept from sorting; null = not measured. */
    public ImageSignals.S signals;
    public int sortVersion;       // Sorter.VERSION in force when this picture was sorted; 0 = sorted by an older app (measure and sort again once)

    // ---- from the file and the gallery (1.9)
    public Boolean favourite;     // marked as a favourite in the gallery (Android 11+); null = not known
    public long taken = -1;       // when the photo was taken (EXIF), ms; 0 = the file has no such date; -1 = not read
    public long motionBytes = -1; // the short video inside a Motion Photo, bytes; 0 = not a Motion Photo; -1 = not checked

    // ---- what a photo shows, and the people in it (1.10)
    public float[] scene;         // SceneNet: shares for Scene.GROUPS (food, nature, pet, vehicle), then the sky share; null = not looked at
    public float aesthetic = -1;  // learned aesthetic score, 1..10; -1 = not looked at
    public int sceneVersion;      // Sorter.SCENE_VERSION when scene and aesthetic were measured; 0 = not measured
    public float faceSize = -1;   // the biggest judged face: eye distance / the picture's short side; -1 = no face or not looked at
    public int smiling = -1;      // of the judged faces (as for eyes), how many smile; -1 = not looked at
    public int looking = -1;      // of the judged faces, how many look at the camera (head turned to it, eyes open); -1 = not looked at

    // ---- what Mirage (AlgorithmV3) saw (1.11): only when it looked (mirageVersion > 0)
    public int mirageVersion;     // AlgorithmV3.VERSION when Mirage looked at this picture; 0 = not looked at
    public String mirageStyle;    // photograph, drawn, collage, screenshot or unspecified (what Mirage said the picture is)
    public int miragePeople = -1; // people Mirage named with a good score (AlgorithmV3.PERSON_SCORE); -1 = not looked at
    public String mirageObjects;  // the things it named, comma-separated (no "text"); "" = none
    public String mirageScene;    // its scene words ("setting outdoor festival; lighting night"); "" = none
    public boolean mirageReference;  // a photo taken to remember something (a sign, price tag, screen...): no people, not drawn
    public boolean mirageSees;    // it clearly recognised something: people, an object with a good score, or 3+ words of text
    public String v3Category;     // the category v3 gives (AlgorithmV3); the scan's own category stays v2's
    public long v3Date;           // a date v3 read in the picture, 0 if none

    public String apkPackage;     // for .apk files: the package inside
    public long apkVersion;

    public FileItem(String rel, String abs, long size, long mtime) {
        this.rel = rel; this.abs = abs; this.size = size; this.mtime = mtime;
    }

    public String name() {
        int i = rel.lastIndexOf('/');
        return i < 0 ? rel : rel.substring(i + 1);
    }

    public double ageDays(long now) { return (now - mtime) / 86400000.0; }
}
