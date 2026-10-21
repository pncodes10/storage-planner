package com.storageplanner.tools;

/**
 * Every tool, with the version of the method or model behind it. Tools only measure; algorithms (see
 * algorithms/Registry) decide from what they measure. Bump a version whenever a tool's output can change.
 */
public final class Tools {
    private Tools() {}

    /** {package, what it measures, version}. */
    public static final String[][] ALL = {
        {"faces", "faces with both eye positions and a score", "YuNet face_detection_yunet_2023mar in plain Java, second look 0.45-0.7 (1.8.1)"},
        {"eyes", "eyes open or closed per face", "EyeState CNN (Closed Eyes In The Wild + open-eye faces) (1.5)"},
        {"ocr", "words in the picture and what kind of text it is", "tesseract.js 7.0.0, tessdata_fast eng; TextRules with low-confidence amounts (1.9)"},
        {"codes", "QR codes and barcodes", "ZXing 3.5.3 (1.6)"},
        {"layout", "text rows, paper, flat screen areas, colour, darkness", "ImageSignals (1.6)"},
        {"sharpness", "focus of the sharpest parts", "90th percentile of 64 px tile Laplacian variance (1.0)"},
        {"similarity", "near-identical pictures", "63-bit DCT perceptual hash (1.0)"},
        {"exposure", "shots with nothing visible", "dark / featureless / shaken; black camera frame (1.9)"},
        {"quality", "which near-identical shot is better", "PhotoQuality: faces, eyes, focus, light (1.8)"},
        {"category", "picture category from all of the above", "PhotoCategory (1.9)"},
    };

    public static String summary() {
        StringBuilder b = new StringBuilder();
        for (String[] t : ALL) b.append(String.format("  %-11s %s%n", t[0], t[2]));
        return b.toString();
    }
}
