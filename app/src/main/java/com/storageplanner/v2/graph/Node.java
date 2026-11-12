package com.storageplanner.v2.graph;

import com.storageplanner.v2.model.*;

/** One PRESENT file with its features, hash and volume (PHASE_3 1, pass A). Read-only during a run. */
final class Node {
    final FileRecord f; final FeatureRecord fr; final HashRecord hr; final Volume vol;
    final Fingerprint fp;
    int idx;                      // index in the sorted input
    String canonical;             // fileId of the canonical member of its alias set (itself if none)
    String exactGroup;            // "x:" + sha when in an exact group
    NameStem.Parsed name;

    Node(FileRecord f, FeatureRecord fr, HashRecord hr, Volume vol) {
        this.f = f; this.fr = fr; this.hr = hr; this.vol = vol; this.fp = f.fingerprint();
        this.name = NameStem.parse(f.name);
    }
    String id() { return f.fileId; }
    boolean alias() { return canonical != null && !canonical.equals(f.fileId); }
    /** sha256 with a current HashRecord (stale or missing → null). */
    String sha() {
        if (f.sha256 == null || f.sizeBytes < 1) return null;
        if (hr == null || hr.sha256 == null || !f.sha256.equals(hr.sha256) || !fp.sameAs(hr.fp)) return null;
        return f.sha256;
    }
    ImageInfo img() { return fr == null ? null : fr.image; }
    long pixels() { ImageInfo i = img(); return i == null || i.uprightWidth <= 0 ? -1 : (long) i.uprightWidth * i.uprightHeight; }
    int longEdge() { ImageInfo i = img(); return i == null ? -1 : Math.max(i.uprightWidth, i.uprightHeight); }
    String lowerPath() { return f.path == null ? "" : f.path.toLowerCase(java.util.Locale.ROOT); }
    boolean trashed() { String p = lowerPath(); return p.contains("/.trashed-") || p.startsWith(".trashed-") || "trash".equals(f.area); }
    /** Capture time with at least second precision, or Unknown.TIME. */
    long captureTime() {
        if (fr != null && fr.exif != null && Unknown.knownTime(fr.exif.dateTimeOriginal)) return fr.exif.dateTimeOriginal;
        if (fr != null && fr.nameDate != null && Unknown.knownTime(fr.nameDate.at) && fr.nameDate.precision != null
                && (fr.nameDate.precision.equals("SECOND") || fr.nameDate.precision.equals("MILLI"))) return fr.nameDate.at;
        return Unknown.TIME;
    }
    boolean thumbLocation() {
        if ("thumbnails".equals(f.area)) return true;
        for (String c : lowerPath().split("/")) if (c.equals(".thumbnails") || c.equals(".thumbs") || c.equals("cache")) return true;
        return false;
    }
    boolean editorFolder() {
        String[] comps = lowerPath().split("/");
        for (int i = 0; i < comps.length - 1; i++) if (GraphConfig.EDITOR_FOLDERS.contains(comps[i])) return true;
        return false;
    }
}
