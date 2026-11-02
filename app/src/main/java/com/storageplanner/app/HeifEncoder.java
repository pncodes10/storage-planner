package com.storageplanner.app;

import android.graphics.Bitmap;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Build;
import android.os.SystemClock;
import android.util.Range;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Writes a bitmap as a HEIC file with the framework only (no AndroidX): the image is cut into
 * 512x512 tiles, each tile is one HEVC key frame from the phone's video encoder, and MediaMuxer
 * stores them as a HEIF image grid (MUXER_OUTPUT_HEIF, Android 9+). This is the same layout
 * AndroidX HeifWriter produces when the phone has no dedicated HEIC encoder.
 */
final class HeifEncoder {
    private HeifEncoder() {}

    static final int TILE = 512;
    private static final String HEVC = MediaFormat.MIMETYPE_VIDEO_HEVC;
    private static final int FPS = 30;
    private static final long TIMEOUT_US = 10_000;
    /** MediaCodec.BUFFER_FLAG_MUXER_DATA (hidden in the SDK): MediaMuxer stores the sample as the image's EXIF item. */
    private static final int MUXER_DATA_FLAG = 16;

    private static Boolean supported;
    private static String codecName;
    private static boolean cq;                    // constant-quality mode, else variable bitrate
    private static int qMin, qMax, maxBitrate;

    /** Android 9+ with an HEVC encoder that takes 512x512 YUV frames. */
    static synchronized boolean supported() {
        if (supported == null) supported = Build.VERSION.SDK_INT >= 28 && pick();
        return supported;
    }

    private static boolean pick() {
        try {
            MediaCodecInfo best = null; boolean bestHw = false;
            for (MediaCodecInfo ci : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
                if (!ci.isEncoder()) continue;
                boolean hevc = false;
                for (String t : ci.getSupportedTypes()) if (t.equalsIgnoreCase(HEVC)) hevc = true;
                if (!hevc) continue;
                MediaCodecInfo.CodecCapabilities caps = ci.getCapabilitiesForType(HEVC);
                MediaCodecInfo.VideoCapabilities vc = caps.getVideoCapabilities();
                if (vc == null || !vc.isSizeSupported(TILE, TILE)) continue;
                boolean flexible = false;
                for (int c : caps.colorFormats) if (c == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible) flexible = true;
                if (!flexible) continue;
                boolean hw = hardware(ci);
                if (best == null || (hw && !bestHw)) { best = ci; bestHw = hw; }
            }
            if (best == null) return false;
            codecName = best.getName();
            MediaCodecInfo.CodecCapabilities caps = best.getCapabilitiesForType(HEVC);
            maxBitrate = caps.getVideoCapabilities().getBitrateRange().getUpper();
            MediaCodecInfo.EncoderCapabilities ec = caps.getEncoderCapabilities();
            cq = ec != null && ec.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ);
            if (cq) {
                Range<Integer> r = ec.getQualityRange();
                qMin = r.getLower(); qMax = r.getUpper();
                if (qMax <= qMin) cq = false;
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean hardware(MediaCodecInfo ci) {
        if (Build.VERSION.SDK_INT >= 29) return ci.isHardwareAccelerated();
        String n = ci.getName().toLowerCase(java.util.Locale.ROOT);
        return !(n.startsWith("omx.google.") || n.startsWith("c2.android.") || n.contains(".sw."));
    }

    /** For the log: which encoder and rate control is used. */
    static String describe() {
        return supported() ? codecName + (cq ? " (constant quality " + qMin + "-" + qMax + ")" : " (variable bitrate)") : "none";
    }

    /**
     * Encodes {@code bm} to {@code outPath}. {@code quality} is 0..100 (higher = larger, closer to the
     * original). {@code exif} is an "Exif\0\0" block to store with the image, or null.
     */
    static void encode(Bitmap bm, int quality, byte[] exif, String outPath) throws IOException {
        if (!supported()) throw new IOException("HEIC encoding is not available on this phone");
        int w = bm.getWidth(), h = bm.getHeight();
        int cols = (w + TILE - 1) / TILE, rows = (h + TILE - 1) / TILE, n = cols * rows;

        MediaFormat f = MediaFormat.createVideoFormat(HEVC, TILE, TILE);
        f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
        f.setInteger(MediaFormat.KEY_FRAME_RATE, FPS);
        f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 0);           // every tile is a key frame
        f.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED);
        f.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT601_PAL);
        f.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO);
        if (cq) {
            f.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ);
            f.setInteger(MediaFormat.KEY_QUALITY, qMin + Math.round((qMax - qMin) * quality / 100f));
        } else {
            f.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR);
            f.setInteger(MediaFormat.KEY_BIT_RATE, bitrate(quality));
        }

        MediaCodec codec = MediaCodec.createByCodecName(codecName);
        MediaMuxer muxer = null;
        boolean codecStarted = false, muxerStarted = false;
        try {
            codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            codec.start(); codecStarted = true;
            muxer = new MediaMuxer(outPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_HEIF);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int[] argb = new int[TILE * TILE];
            int track = -1, fed = 0, written = 0;
            boolean eos = false;
            long deadline = SystemClock.uptimeMillis() + 30_000L + n * 2_000L;
            while (!eos) {
                if (SystemClock.uptimeMillis() > deadline) throw new IOException("the encoder stopped responding");
                if (fed < n) {
                    int ix = codec.dequeueInputBuffer(TIMEOUT_US);
                    if (ix >= 0) {
                        int size = codec.getInputBuffer(ix).capacity();
                        Image img = codec.getInputImage(ix);
                        if (img == null) throw new IOException("the encoder does not accept YUV images");
                        fillTile(bm, (fed % cols) * TILE, (fed / cols) * TILE, img, argb);
                        codec.queueInputBuffer(ix, 0, size, fed * 1_000_000L / FPS,
                                fed == n - 1 ? MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0);
                        fed++;
                    }
                }
                int ox = codec.dequeueOutputBuffer(info, TIMEOUT_US);
                if (ox == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (muxerStarted) throw new IOException("the encoder changed its output format");
                    MediaFormat of = codec.getOutputFormat();             // keeps the HEVC parameter sets (csd-0)
                    of.setString(MediaFormat.KEY_MIME, MediaFormat.MIMETYPE_IMAGE_ANDROID_HEIC);
                    of.setInteger(MediaFormat.KEY_WIDTH, w);
                    of.setInteger(MediaFormat.KEY_HEIGHT, h);
                    of.setInteger(MediaFormat.KEY_TILE_WIDTH, TILE);
                    of.setInteger(MediaFormat.KEY_TILE_HEIGHT, TILE);
                    of.setInteger(MediaFormat.KEY_GRID_ROWS, rows);
                    of.setInteger(MediaFormat.KEY_GRID_COLUMNS, cols);
                    track = muxer.addTrack(of);
                    muxer.start(); muxerStarted = true;
                    if (exif != null) writeExif(muxer, track, exif);
                } else if (ox >= 0) {
                    ByteBuffer ob = codec.getOutputBuffer(ox);
                    boolean config = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;   // already in the track format
                    if (!config && info.size > 0 && ob != null) {
                        if (!muxerStarted) throw new IOException("the encoder gave no output format");
                        ob.position(info.offset); ob.limit(info.offset + info.size);
                        muxer.writeSampleData(track, ob, info);
                        written++;
                    }
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) eos = true;
                    codec.releaseOutputBuffer(ox, false);
                }
            }
            if (written != n) throw new IOException("the encoder produced " + written + " of " + n + " tiles");
            muxer.stop(); muxerStarted = false;
        } finally {
            if (codecStarted) try { codec.stop(); } catch (Throwable ignored) {}
            try { codec.release(); } catch (Throwable ignored) {}
            if (muxer != null) {
                if (muxerStarted) try { muxer.stop(); } catch (Throwable ignored) {}
                try { muxer.release(); } catch (Throwable ignored) {}
            }
        }
    }

    /** Stores the EXIF block (date taken, camera, location). A phone that refuses it still gets the image. */
    private static void writeExif(MediaMuxer muxer, int track, byte[] exif) {
        try {
            ByteBuffer b = ByteBuffer.allocateDirect(exif.length);
            b.put(exif); b.flip();
            MediaCodec.BufferInfo ei = new MediaCodec.BufferInfo();
            ei.set(0, exif.length, 0, MUXER_DATA_FLAG);
            muxer.writeSampleData(track, b, ei);
        } catch (Throwable ignored) {
        }
    }

    /** Variable-bitrate fallback: quality 0..100 -> 0.15..4.15 bits per pixel for each tile. */
    private static int bitrate(int quality) {
        double q = quality / 100.0;
        double bpp = 0.15 + 4.0 * q * q;
        long b = (long) (bpp * TILE * TILE * FPS);
        return (int) Math.max(100_000, Math.min(maxBitrate > 0 ? maxBitrate : Integer.MAX_VALUE, b));
    }

    /**
     * Copies one tile into the encoder's YUV 4:2:0 image (BT.601, limited range, which is what the
     * format above declares). Pixels past the right or bottom edge repeat the last column / row.
     */
    private static void fillTile(Bitmap bm, int tx, int ty, Image img, int[] argb) {
        int w0 = Math.min(TILE, bm.getWidth() - tx), h0 = Math.min(TILE, bm.getHeight() - ty);
        bm.getPixels(argb, 0, TILE, tx, ty, w0, h0);
        for (int y = 0; y < h0; y++) {
            int edge = argb[y * TILE + w0 - 1];
            for (int x = w0; x < TILE; x++) argb[y * TILE + x] = edge;
        }
        for (int y = h0; y < TILE; y++) System.arraycopy(argb, (h0 - 1) * TILE, argb, y * TILE, TILE);

        Image.Plane[] pl = img.getPlanes();
        ByteBuffer yb = pl[0].getBuffer(), ub = pl[1].getBuffer(), vb = pl[2].getBuffer();
        int yRow = pl[0].getRowStride(), yPix = pl[0].getPixelStride();
        int uRow = pl[1].getRowStride(), uPix = pl[1].getPixelStride();
        int vRow = pl[2].getRowStride(), vPix = pl[2].getPixelStride();
        for (int y = 0; y < TILE; y++) {
            int base = y * yRow;
            for (int x = 0; x < TILE; x++) {
                int p = argb[y * TILE + x];
                int r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255;
                yb.put(base + x * yPix, (byte) ((66 * r + 129 * g + 25 * b + 128 >> 8) + 16));
            }
        }
        for (int y = 0; y < TILE / 2; y++) {
            for (int x = 0; x < TILE / 2; x++) {
                int i = 2 * y * TILE + 2 * x;
                int p0 = argb[i], p1 = argb[i + 1], p2 = argb[i + TILE], p3 = argb[i + TILE + 1];
                int r = (((p0 >> 16) & 255) + ((p1 >> 16) & 255) + ((p2 >> 16) & 255) + ((p3 >> 16) & 255) + 2) >> 2;
                int g = (((p0 >> 8) & 255) + ((p1 >> 8) & 255) + ((p2 >> 8) & 255) + ((p3 >> 8) & 255) + 2) >> 2;
                int b = ((p0 & 255) + (p1 & 255) + (p2 & 255) + (p3 & 255) + 2) >> 2;
                ub.put(y * uRow + x * uPix, (byte) ((-38 * r - 74 * g + 112 * b + 128 >> 8) + 128));
                vb.put(y * vRow + x * vPix, (byte) ((112 * r - 94 * g - 18 * b + 128 >> 8) + 128));
            }
        }
    }
}
