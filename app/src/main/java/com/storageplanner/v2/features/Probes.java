package com.storageplanner.v2.features;

import java.io.IOException;

/** Tier-1 format probe dispatch (PHASE_2 3.2 step 5). RuntimeExceptions become "<probe>:EXCEPTION:<class>". */
public final class Probes {
    private Probes() {}

    public static void run(ProbeCtx c) throws IOException {
        int p = Extractors.probe(c.type);
        if (p < 0) return;
        String name = name(p);
        try {
            switch (p) {
                default: break;
            }
        } catch (RuntimeException e) {
            c.problem(name + ":EXCEPTION:" + e.getClass().getSimpleName());
            c.probeException = true;
        }
    }

    static String name(int p) {
        switch (p) {
            case Extractors.JPEG: return "jpeg"; case Extractors.PNG: return "png"; case Extractors.WEBP: return "webp";
            case Extractors.GIF: return "gif"; case Extractors.BMP: return "bmp"; case Extractors.HEIF: return "heif";
            case Extractors.EXIF: return "exif"; case Extractors.MP4: return "mp4"; case Extractors.AUDIO: return "audio";
            case Extractors.PDF: return "pdf"; default: return "probe";
        }
    }
}
