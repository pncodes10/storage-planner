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
                case Extractors.JPEG: ImageProbes.jpeg(c); break;
                case Extractors.PNG: ImageProbes.png(c); break;
                case Extractors.GIF: ImageProbes.gif(c); break;
                case Extractors.WEBP: ImageProbes.webp(c); break;
                case Extractors.BMP: ImageProbes.bmp(c); break;
                case Extractors.EXIF: ImageProbes.tiff(c); break;
                case Extractors.HEIF: HeifProbe.probe(c); break;
                case Extractors.PDF: PdfProbe.probe(c); break;
                case Extractors.MP4: Mp4Probe.probe(c); break;
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
