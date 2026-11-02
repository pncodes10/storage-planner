package com.storageplanner.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which recommendations the app can carry out itself by compressing images, which files that
 * covers, and where the compressed copy goes. Platform-free so it can be tested on a desktop.
 */
public final class Compression {
    private Compression() {}

    /** Recommendations the app can carry out: re-encode photos and chat images, lossless screenshots. */
    public static boolean isCompression(String technique) {
        return Techniques.PHOTOS.equals(technique) || Techniques.CHAT_IMAGES.equals(technique) || Techniques.SCREENSHOTS.equals(technique);
    }

    /** Screenshots keep every pixel (lossless WebP); photos and chat images become HEIC or WebP that looks identical. */
    public static boolean lossless(String technique) { return Techniques.SCREENSHOTS.equals(technique); }

    /** Formats a copy can have: the app keeps whichever passes and is smallest (photos), or lossless WebP (screenshots). */
    public static String[] extensions(String technique) { return lossless(technique) ? new String[]{"webp"} : new String[]{"heic", "webp"}; }

    /** The original's path without its extension: the copy is this plus ".heic" or ".webp" (IMG_1.jpg -> IMG_1.heic). */
    public static String base(String path) {
        int slash = path.lastIndexOf('/');
        int dot = path.lastIndexOf('.');
        return dot > slash + 1 ? path.substring(0, dot) : path;
    }

    public static String outputPath(String path, String ext) { return base(path) + "." + ext; }

    /** Work file the encoder writes before the copy is checked; renamed to the output path once it passes. */
    public static String tempPath(String outputPath) {
        int slash = outputPath.lastIndexOf('/');
        return outputPath.substring(0, slash + 1) + ".sp-" + outputPath.substring(slash + 1) + ".tmp";
    }

    /**
     * Files a recommendation compresses: every candidate the user has not kept, except files the plan
     * removes another way (for example, deletes as a duplicate), since compressing those frees nothing.
     */
    public static List<Unit> targets(List<Unit> candidates, String technique, Set<String> kept, Map<Unit, Option> chosen) {
        List<Unit> out = new ArrayList<Unit>();
        if (candidates == null) return out;
        for (Unit u : candidates) {
            if (u.file == null || kept.contains(u.id) || Review.optionFor(u, technique) == null) continue;
            Option c = chosen.get(u);
            if (c != null && !c.technique.equals(technique)) continue;
            out.add(u);
        }
        return out;
    }

    /** "photo" / "photos", "chat image" / ..., "screenshot" / ... */
    public static String noun(String technique, int n) {
        String s = Techniques.SCREENSHOTS.equals(technique) ? "screenshot" : Techniques.CHAT_IMAGES.equals(technique) ? "chat image" : "photo";
        return n == 1 ? s : s + "s";
    }
}
