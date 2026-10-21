package com.storageplanner.core;

import com.storageplanner.tools.quality.PhotoQuality;

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

    public PhotoQuality.Q quality;  // measured for near-duplicate shots only (eyes, faces, light); else null

    // ---- picture sorting (1.6)
    public String category;       // PhotoCategory id; null = not a sorted picture
    public String code;           // what a QR code / barcode in it is for
    public long catDate;          // a date read in the picture (ticket, receipt), 0 if none
    public String text;           // words read in the picture (lower case); never kept for ID documents
    public boolean textPending;   // worth reading, but not read yet (time limit or no reader)
    public int faces = -1;        // faces found (-1 = not checked)

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
