package com.storageplanner.v2.features;

import com.storageplanner.v2.model.FeatureRecord;

/** Extractor ids and versions (PHASE_2 9). Bump a version to force re-extraction by that extractor only. */
public final class Extractors {
    private Extractors() {}
    public static final int TYPE = 0, EXIF = 1, JPEG = 2, PNG = 3, WEBP = 4, GIF = 5, HEIF = 6, MP4 = 7, MKV = 8, AUDIO = 9, PDF = 10,
            ZIP = 11, TAR = 12, APK = 13, NAMEDATE = 14, INTEGRITY = 15, IMAGE_BASICS = 16, HASH = 17, BMP = 18, AVI = 19;
    public static final int COUNT = FeatureRecord.EXTRACTORS;
    private static final int[] VERSION = new int[COUNT];
    static { for (int i = 0; i < COUNT; i++) VERSION[i] = 1; }

    public static int version(int id) { return VERSION[id]; }
    /** Test hook (IncrementalExtractionTest). */
    public static void overrideVersionForTest(int id, int v) { VERSION[id] = v; }
    public static void resetVersionsForTest() { for (int i = 0; i < COUNT; i++) VERSION[i] = 1; }

    /** Tier-1 extractor ids for a type (TYPE, NAMEDATE, INTEGRITY always; then the format probe). */
    public static int[] tier1(String type) {
        int p = probe(type);
        return p < 0 ? new int[]{TYPE, NAMEDATE, INTEGRITY} : p == JPEG || p == WEBP || p == HEIF ? new int[]{TYPE, NAMEDATE, INTEGRITY, p, EXIF}
                : new int[]{TYPE, NAMEDATE, INTEGRITY, p};
    }
    /** Format probe id of tier 1, -1 if none (A11: mkv/avi/tar/ico/extra audio deferred to Phase 6). */
    public static int probe(String t) {
        if (t.equals("jpeg")) return JPEG;
        if (t.equals("png")) return PNG;
        if (t.equals("webp")) return WEBP;
        if (t.equals("gif")) return GIF;
        if (t.equals("bmp")) return BMP;
        if (t.equals("heic") || t.equals("heif") || t.equals("avif")) return HEIF;
        if (t.equals("tiff") || t.equals("dng")) return EXIF;
        if (t.equals("mp4") || t.equals("mov") || t.equals("3gp") || t.equals("m4a")) return MP4;
        if (t.equals("mp3") || t.equals("wav") || t.equals("flac") || t.equals("ogg") || t.equals("opus") || t.equals("aac")) return AUDIO;
        if (t.equals("pdf")) return PDF;
        return -1;
    }
    public static boolean current(FeatureRecord fr, int[] ids) {
        for (int id : ids) if (fr.versions[id] != VERSION[id]) return false;
        return true;
    }
    public static void stamp(FeatureRecord fr, int[] ids) { for (int id : ids) fr.versions[id] = VERSION[id]; }
}
