package com.storageplanner.v2.content;

/** PHASE_6 5.2: the device-specific parts the content stage may use. */
public final class ContentProviders {
    public final TextReader text;          // never null: UnavailableTextReader when there is none
    public final FaceDetector faces;       // null = faces NOT_RUN (unknown)
    public ContentProviders(TextReader text, FaceDetector faces) { this.text = text == null ? new UnavailableTextReader() : text; this.faces = faces; }
}
