package com.storageplanner.app;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.Charset;

/**
 * Mirage, Cactus Compute's 22 MB vision model (scene, objects, text), run by the Cactus engine (Apache 2.0) through
 * the small native bridge in app/src/main/cpp (lib/arm64-v8a/libmirage.so). Used only by the Algorithm Lab.
 * The model file is not in the APK: MirageModel downloads it once into the app's files folder, where it stays across
 * app updates. Everything runs on the phone. 64-bit ARM phones only.
 * One picture at a time: the engine is not shared between threads.
 */
final class MirageEngine {
    static final String NOT_DOWNLOADED = "the Mirage model is not downloaded yet (22 MB, one time): download it at the top of the Algorithm Lab";

    private static boolean tried, loaded;
    private static String note;

    private static native int load(byte[] model);
    private static native String lastError();
    private static native byte[] describe(byte[] rgb, int w, int h, String options);

    private MirageEngine() {}

    /** Loads the library and the model once; false (with why()) if this phone or this build cannot run it. */
    private static boolean ready(Context ctx) {
        if (tried) return loaded;
        byte[] model;
        File f = MirageModel.file(ctx);
        if (!f.isFile()) { note = NOT_DOWNLOADED; return false; }     // not "tried": a download later makes it work
        try {
            if (!MirageModel.MODEL_SHA256.equals(MirageModel.sha256(f))) {
                MirageModel.remove(ctx);                              // a different or damaged file: ask for a fresh download
                note = "the saved Mirage model was not the expected file and was removed; " + NOT_DOWNLOADED;
                return false;
            }
            model = read(f);
        } catch (Throwable t) { note = "the saved Mirage model could not be read: " + t; return false; }
        tried = true;
        try { System.loadLibrary("mirage"); }
        catch (Throwable t) {
            note = "the engine is not available on this phone (" + android.os.Build.SUPPORTED_ABIS[0] + "; only 64-bit ARM is built): " + t.getMessage();
            return false;
        }
        int r;
        try { r = load(model); } catch (Throwable t) { note = "the engine failed to start: " + t; return false; }
        if (r != 0) { note = r == -2 ? "the bundled file is not a Mirage vision model" : "the engine could not load the model: " + lastError(); return false; }
        loaded = true;
        return true;
    }

    /** Forgets an earlier failure, so a model that has just been downloaded (or removed) is picked up. */
    static synchronized void reset() { if (!loaded) tried = false; }

    /** Why describe() gave null, for the Lab. */
    static synchronized String why() { return note; }

    /** Mirage's JSON for an ARGB picture, or null when it cannot run (see why()). */
    static synchronized String describe(Context ctx, int[] argb, int w, int h, String options) {
        if (!ready(ctx)) return null;
        byte[] rgb = new byte[w * h * 3];
        for (int i = 0, j = 0; i < w * h; i++) {
            int p = argb[i];
            rgb[j++] = (byte) (p >> 16); rgb[j++] = (byte) (p >> 8); rgb[j++] = (byte) p;
        }
        byte[] out = describe(rgb, w, h, options);
        if (out == null) { note = "the engine failed on this picture: " + lastError(); return null; }
        note = null;
        return new String(out, Charset.forName("UTF-8"));
    }

    private static byte[] read(File f) throws java.io.IOException {
        InputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream(23 << 20);
            byte[] buf = new byte[1 << 16]; int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toByteArray();
        } finally { in.close(); }
    }
}
