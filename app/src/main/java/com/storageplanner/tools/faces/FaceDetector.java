package com.storageplanner.tools.faces;

/**
 * Finds faces. On the phone and on the desktop alike this is YuNet (FaceNet + Faces); Platform implements it so a
 * device can supply its own.
 */
public interface FaceDetector {
    /**
     * Faces in a decoded picture (from argb), in the picture's pixels, Faces.F floats per face
     * {x between the eyes, y, eye distance, score 0..1, eye-on-the-left x, y, other eye x, y}.
     * Empty array if none; null if detection is unavailable.
     */
    float[] faces(String path, int[] argb, int w, int h);
}
