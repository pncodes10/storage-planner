package com.storageplanner.tools;

/**
 * Where a compressed copy goes. Plain Java, so it can be tested on a desktop.
 * The copy sits next to the original with the new extension (IMG_1.jpg -> IMG_1.heic or IMG_1.webp), and is
 * first written to a hidden work file in the same folder, renamed into place only after it passed every check.
 */
public final class CopyPaths {
    private CopyPaths() {}

    /** The path without its extension: IMG_1.jpg -> IMG_1 (a name without a dot, or only a leading dot, is kept). */
    public static String base(String path) {
        int slash = path.lastIndexOf('/');
        int dot = path.lastIndexOf('.');
        return dot > slash + 1 ? path.substring(0, dot) : path;
    }

    public static String outputPath(String path, String ext) { return base(path) + "." + ext; }

    /** The hidden work file for an output path: DCIM/IMG_1.heic -> DCIM/.sp-IMG_1.heic.tmp */
    public static String tempPath(String outputPath) {
        int slash = outputPath.lastIndexOf('/');
        return outputPath.substring(0, slash + 1) + ".sp-" + outputPath.substring(slash + 1) + ".tmp";
    }
}
