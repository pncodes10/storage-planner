package com.storageplanner.core;

/**
 * A look inside a video without playing it: a few frames spread over its length. From them: is it black,
 * is there anything in it, does anything move, are there people. Also a fingerprint of each frame, to find
 * the copy WhatsApp made when the video was sent.
 */
public final class VideoLook {
    private VideoLook() {}

    /** Frames looked at, at these points of the video's length. */
    static final double[] AT = {0.1, 0.3, 0.5, 0.7, 0.9};
    static final int EDGE = 320, FACE_EDGE = 512;

    /** Fills f.vFrames, vDark, vFlat, vFaces, vHashes. Needs f.durationMs. Leaves vFrames at 0 if no frame could be read. */
    public static void look(Platform pf, FileItem f) {
        f.vFrames = 0; f.vDark = 0; f.vFlat = 0; f.vFaces = -1; f.vHashes = null;
        if (f.durationMs <= 0) return;
        long[] hashes = new long[AT.length];
        int n = 0, dark = 0, flat = 0;
        int[] dims = new int[2];
        for (double at : AT) {
            long ms = (long) (f.durationMs * at);
            int[] px;
            try { px = pf.videoFrame(f.abs, ms, EDGE, dims); } catch (Throwable t) { px = null; }
            if (px == null || dims[0] <= 0 || dims[1] <= 0) continue;
            int w = dims[0], h = dims[1];
            int[] l = new int[w * h];
            long sum = 0;
            for (int i = 0; i < l.length; i++) {
                int p = px[i];
                l[i] = (299 * ((p >> 16) & 255) + 587 * ((p >> 8) & 255) + 114 * (p & 255)) / 1000;
                sum += l[i];
            }
            double mean = (double) sum / l.length, var = 0;
            for (int v : l) var += (v - mean) * (v - mean);
            double sd = Math.sqrt(var / l.length);
            if (mean < 22 && sd < 14) dark++;                // black, allowing for sensor noise
            else if (sd < 7) flat++;                         // one flat colour
            hashes[n++] = ImageFeatures.phash(l, w, h);
        }
        f.vFrames = n;
        if (n == 0) return;
        f.vDark = (float) dark / n; f.vFlat = (float) (dark + flat) / n;
        f.vHashes = new long[n];
        System.arraycopy(hashes, 0, f.vHashes, 0, n);
        // people: only worth the time when there is something to see
        if (f.vFlat < 0.8f) {
            int most = 0; boolean checked = false;
            for (double at : new double[]{0.3, 0.7}) {
                int[] px;
                try { px = pf.videoFrame(f.abs, (long) (f.durationMs * at), FACE_EDGE, dims); } catch (Throwable t) { px = null; }
                if (px == null) continue;
                float[] faces;
                try { faces = pf.faces(f.abs, px, dims[0], dims[1]); } catch (Throwable t) { faces = null; }
                if (faces == null) continue;
                checked = true;
                most = Math.max(most, Faces.count(faces));
            }
            if (checked) f.vFaces = most;
        }
    }

    /**
     * Is copy a re-encoded copy of orig? Same length within a second, same shape, and the frames at the same
     * points look the same. Returns how many frame pairs matched closely, or -1 if it is not a copy.
     */
    public static int sameVideo(FileItem orig, FileItem copy, int maxBits) {
        if (orig.vHashes == null || copy.vHashes == null || orig.vHashes.length != copy.vHashes.length || orig.vHashes.length < 3) return -1;
        if (Math.abs(orig.durationMs - copy.durationMs) > 1000) return -1;
        if (orig.width <= 0 || orig.height <= 0 || copy.width <= 0 || copy.height <= 0) return -1;
        double a = (double) Math.max(orig.width, orig.height) / Math.min(orig.width, orig.height);
        double b = (double) Math.max(copy.width, copy.height) / Math.min(copy.width, copy.height);
        if (Math.abs(a - b) > 0.03) return -1;
        int close = 0;
        for (int i = 0; i < orig.vHashes.length; i++) {
            int d = ImageFeatures.hamming(orig.vHashes[i], copy.vHashes[i]);
            if (d > maxBits) return -1;
            if (d <= 4) close++;
        }
        return close;
    }
}
