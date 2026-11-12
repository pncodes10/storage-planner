package com.storageplanner.v2.device;

/** Pixel decoder (ARCHITECTURE 3.2). Pixels are upright; never upscaled; errors are statuses, never exceptions. */
public interface MediaDecoder {
    /** Decodes to 8-bit luma (and ARGB if wanted), long edge about longEdge. TOO_LARGE is decided from the header. */
    DecodedImage decode(String volumeId, String path, int longEdge, long maxPixels, boolean wantArgb);
    /** Lower-case FileType ids this decoder can decode. */
    java.util.Set<String> decodableTypes();
    /** Phase 6; the JVM returns status UNSUPPORTED_FORMAT. */
    DecodedImage videoFrame(String volumeId, String path, long timeMs, int longEdge);
}
