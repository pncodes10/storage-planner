package com.storageplanner.v2.graph;

import com.storageplanner.v2.model.*;

/**
 * One PRESENT file (PHASE_3 1, pass A) as a "lite" node (PHASE_S_STORE 4 item 3): the id, the volume, the few values every
 * pass needs for all files (size, pHash, flags computed from the records once) and the group fields. The records themselves
 * (FileRecord, FeatureRecord, HashRecord) are loaded through a bounded {@link GraphRecords} cache when a pass needs them; they
 * are read-only during a run (GRAPH writes no record a later read in the stage depends on), so a reload equals the snapshot.
 */
final class Node {
    static final int HAS_PHASH = 1, HAS_FR = 2, HAS_HR = 4, HAS_IMAGE = 8, ELIGIBLE = 16, SHA = 32, ARCHIVE = 64, COMPANION = 128,
            HAS_PK = 256;
    /** perceptual status found in FeatureRecord.extra at snapshot: 0 absent, 1 TRUE, 2 FALSE, 3 UNKNOWN, 4 other text. */
    static final int PSTAT_SHIFT = 12;

    final String id;
    final Volume vol;
    final GraphRecords rec;
    final long size, phash;
    final int flags;
    final String oldGroup;        // FileRecord.duplicateGroupId at snapshot (null for most files)
    int idx;                      // index in the sorted input
    String canonical;             // fileId of the canonical member of its alias set; null = itself
    String exactGroup;            // "x:" + sha when in an exact group

    Node(FileRecord f, FeatureRecord fr, HashRecord hr, Volume vol, GraphRecords rec) {
        this.id = f.fileId; this.vol = vol; this.rec = rec;
        this.size = f.sizeBytes; this.phash = f.phash; this.oldGroup = f.duplicateGroupId;
        int fl = 0;
        if (f.hasPhash) fl |= HAS_PHASH;
        if (fr != null) fl |= HAS_FR;
        if (hr != null) fl |= HAS_HR;
        if (fr != null && fr.image != null) fl |= HAS_IMAGE;
        if (Perceptual.eligible(f, fr)) fl |= ELIGIBLE;
        if (sha(f, hr) != null) fl |= SHA;
        ArchiveInfo ai = fr == null ? null : fr.archive;
        if (ai != null && ai.entries != null && "OK".equals(ai.listingStatus) && !(fr.fp != null && !fr.fp.sameAs(f.fingerprint()))) fl |= ARCHIVE;
        if (CompanionLinker.candidate(f, fr)) fl |= COMPANION;
        if (f.physicalKey != null) fl |= HAS_PK;
        String ps = fr == null ? null : fr.extra.get(Perceptual.PSTAT);
        int pc = ps == null ? 0 : ps.equals("TRUE") ? 1 : ps.equals("FALSE") ? 2 : ps.equals("UNKNOWN") ? 3 : 4;
        this.flags = fl | pc << PSTAT_SHIFT;
    }

    boolean is(int flag) { return (flags & flag) != 0; }
    int pstat() { return flags >>> PSTAT_SHIFT & 7; }

    String id() { return id; }
    String canonical() { return canonical == null ? id : canonical; }
    boolean alias() { return canonical != null && !canonical.equals(id); }

    FileRecord f() { return rec.get(this).f; }
    FeatureRecord fr() { return rec.get(this).fr; }
    HashRecord hr() { return rec.get(this).hr; }
    Fingerprint fp() { return rec.get(this).fp; }
    NameStem.Parsed name() { return rec.get(this).name; }

    /** sha256 with a current HashRecord (stale or missing → null). */
    static String sha(FileRecord f, HashRecord hr) {
        if (f.sha256 == null || f.sizeBytes < 1) return null;
        if (hr == null || hr.sha256 == null || !f.sha256.equals(hr.sha256) || !f.fingerprint().sameAs(hr.fp)) return null;
        return f.sha256;
    }
    String sha() { return is(SHA) ? f().sha256 : null; }
    ImageInfo img() { FeatureRecord fr = fr(); return fr == null ? null : fr.image; }
    long pixels() { ImageInfo i = img(); return i == null || i.uprightWidth <= 0 ? -1 : (long) i.uprightWidth * i.uprightHeight; }
    int longEdge() { ImageInfo i = img(); return i == null ? -1 : Math.max(i.uprightWidth, i.uprightHeight); }
    String lowerPath() { FileRecord f = f(); return f.path == null ? "" : f.path.toLowerCase(java.util.Locale.ROOT); }
    boolean trashed() { String p = lowerPath(); return p.contains("/.trashed-") || p.startsWith(".trashed-") || "trash".equals(f().area); }
    /** Capture time with at least second precision, or Unknown.TIME. */
    long captureTime() { return captureTime(fr()); }
    static long captureTime(FeatureRecord fr) {
        if (fr != null && fr.exif != null && Unknown.knownTime(fr.exif.dateTimeOriginal)) return fr.exif.dateTimeOriginal;
        if (fr != null && fr.nameDate != null && Unknown.knownTime(fr.nameDate.at) && fr.nameDate.precision != null
                && (fr.nameDate.precision.equals("SECOND") || fr.nameDate.precision.equals("MILLI"))) return fr.nameDate.at;
        return Unknown.TIME;
    }
    boolean thumbLocation() {
        if ("thumbnails".equals(f().area)) return true;
        for (String c : lowerPath().split("/")) if (c.equals(".thumbnails") || c.equals(".thumbs") || c.equals("cache")) return true;
        return false;
    }
    boolean editorFolder() {
        String[] comps = lowerPath().split("/");
        for (int i = 0; i < comps.length - 1; i++) if (GraphConfig.EDITOR_FOLDERS.contains(comps[i])) return true;
        return false;
    }
}
