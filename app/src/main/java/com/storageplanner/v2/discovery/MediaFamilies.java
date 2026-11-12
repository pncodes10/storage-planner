package com.storageplanner.v2.discovery;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Extension → media family as MediaStore would index it. */
public final class MediaFamilies {
    private MediaFamilies() {}
    private static final Set<String> IMAGE = new HashSet<String>(Arrays.asList("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "avif", "bmp", "dng"));
    private static final Set<String> VIDEO = new HashSet<String>(Arrays.asList("mp4", "m4v", "3gp", "3g2", "mkv", "webm", "mov", "avi", "ts"));
    private static final Set<String> AUDIO = new HashSet<String>(Arrays.asList("mp3", "m4a", "aac", "ogg", "oga", "opus", "flac", "wav", "amr", "awb", "mid", "midi"));

    /** "image" | "video" | "audio" | null. */
    public static String of(String extension) {
        if (extension == null) return null;
        String e = extension.toLowerCase(Locale.ROOT);
        if (IMAGE.contains(e)) return "image";
        if (VIDEO.contains(e)) return "video";
        if (AUDIO.contains(e)) return "audio";
        return null;
    }
}
