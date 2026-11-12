package com.storageplanner.v2.graph;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Phase 3 constants (PHASE_3). Thresholds: calibrated by tools/v2eval/calibrate.py (fit on fold 0, reported on fold 1);
 * see docs/v2/PHASE_3_NOTES.md. Bump MATCHER_VERSION whenever a threshold or a metric changes.
 */
public final class GraphConfig {
    private GraphConfig() {}
    public static final int MATCHER_VERSION = 3;

    public static final int MAX_EXACT_EDGES_GROUP = 64;
    // candidate generation (4.2, F-01)
    public static final int T_CAND = 7, T_BURST = 14;
    public static final int MIH_BUCKET_CAP = 512, CAP_PHASH = 64, CAP_TIME = 32, CAP_STEM = 32, STEM_GROUP_MAX = 32, STEM_MIN_LEN = 6;
    public static final long TIME_WINDOW_MS = 120000L;
    public static final double MIN_CONTRAST = 4.0, ASPECT_TOL = 0.02;
    public static final int MIN_LONG_EDGE = 64;
    // stage 4 (4.4)
    public static final int SIG_DECODE_EDGE = 1024, SIG_LONG_EDGE = 256, SIG_CACHE = 256, BAND_A_MIN = 512;
    public static final int MAX_DECODES_PER_RUN = 20000;
    // V2 SAME_CONTENT
    // calibrate.py fold-0 fit, report sha256 dfb08fc7906f (PHASE_3_NOTES "Calibration"); initial values were 6/.985/.90/6.0/2.5/.93/9.5
    public static double T_SAME_HAM = 6, V2_SSIM_MEAN = 0.958629, V2_SSIM_MIN = 0.816365, V2_DIFF_MAX = 11.5, V2_CHROMA = 2.5,
            V2_FINE_SSIM_MIN = 0.907909, V2_FINE_DIFF_MAX = 17.25;
    // V6 ambiguity bands
    public static double AMB_SSIM_MEAN = 0.975, AMB_SSIM_MIN = 0.85, AMB_DIFF_MAX = 8, AMB_CHROMA = 3.5, AMB_FINE_SSIM_MIN = 0.85,
            AMB_FINE_DIFF_MAX = 12;
    // V3-V5, V7
    public static double GEO_SSIM_MEAN = 0.95, GEO_SSIM_MIN = 0.70, COLOR_SSIM_MEAN = 0.95, COLOR_FRAC = 0.50, LOCAL_SSIM_MEAN = 0.75,
            LOCAL_FRAC = 0.60, SCENE_SSIM_MEAN = 0.25, EDITOR_FOLDER_SSIM = 0.95, BLOCK_SAME_SSIM = 0.95;
    // edge typing (5.2)
    public static final int THUMB_MAX_EDGE = 400;
    public static final double THUMB_EDGE_RATIO = 0.35;
    public static final int MAX_BACKUP_MATCHES = 8;
    public static final long VERIFY_MAX_ENTRY = 64L << 20;

    public static final List<String> EVIDENCE_CODES = Collections.unmodifiableList(Arrays.asList(
            "SHA256_EQUAL", "PHYSKEY_DISTINCT", "PHYSKEY_UNKNOWN", "VOLUMES_DISJOINT", "PHASH_DIST", "ASPECT_DIFF", "DIMS", "SSIM64_MEAN",
            "SSIM64_MIN", "DIFF64_MAX", "SSIM_FINE_MIN", "DIFF_FINE_MAX", "CHROMA16", "FRAC_SAME_BLOCKS", "TRANSFORM", "NAME_STEM_EQUAL",
            "NAME_PATTERN_WA", "AREA_PAIR", "CAPTURE_DT_MS", "EXIF_DTO_EQUAL", "EXIF_SOFTWARE", "EDITOR_FOLDER", "BYTES_RATIO",
            "FORMAT_PAIR", "PIXELS_RATIO", "ZIP_SIZE_CRC_EQUAL", "ENTRY_NAME_EQUAL", "ENTRY_SHA256_EQUAL", "ENTRY_CRC_MISMATCH",
            "APK_PKG_VER_SIGNER", "APK_PKG", "COMPANION_RULE", "DOC_STEM_RULE", "BACKUP_NAME_DATE", "STAR", "CROP_OR_RECOMPOSE",
            "ARCHIVE_LISTING", "APP_INSTALLED"));
    static final java.util.Set<String> STRUCTURAL = new java.util.HashSet<String>(Arrays.asList("SHA256_EQUAL", "PHYSKEY_DISTINCT",
            "PHYSKEY_UNKNOWN", "VOLUMES_DISJOINT", "ZIP_SIZE_CRC_EQUAL", "ENTRY_NAME_EQUAL", "ENTRY_SHA256_EQUAL", "ENTRY_CRC_MISMATCH",
            "APK_PKG_VER_SIGNER", "APK_PKG", "ARCHIVE_LISTING", "STAR"));

    public static final List<String> EDITOR_FOLDERS = Arrays.asList("edited", "snapseed", "markup", "collage", "editor", "photoeditor",
            "picsart", "photoshop express", "lightroom");
    public static final List<String> EDITOR_SOFTWARE = Arrays.asList("snapseed", "photoshop", "lightroom", "picsart", "gimp", "editor",
            "markup", "vsco", "canva");
}
