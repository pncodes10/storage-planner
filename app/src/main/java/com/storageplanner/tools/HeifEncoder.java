package com.storageplanner.tools;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Writes a bitmap as a HEIC file with the framework only (no AndroidX). Two ways, as AndroidX HeifWriter:
 * <ul>
 * <li><b>HEIC encoder</b> (MIMETYPE_IMAGE_ANDROID_HEIC, e.g. on Samsung and Pixel phones): the whole picture
 *     goes in as one frame; the encoder cuts it into a grid itself, usually at constant quality;</li>
 * <li><b>HEVC tiles</b> (any phone with an HEVC encoder): the picture is cut into 512x512 tiles here, each
 *     one an HEVC key frame.</li>
 * </ul>
 * Either way MediaMuxer stores the tiles as a HEIF image grid (MUXER_OUTPUT_HEIF, Android 9+). Every
 * encoder found is one {@link Way}; {@link ImageCompressor#encoderCheck} tests each on a test picture.
 */
public final class HeifEncoder {
    private HeifEncoder() {}

    static final int TILE = 512;
    private static final String HEVC = MediaFormat.MIMETYPE_VIDEO_HEVC;
    private static final String HEIC = MediaFormat.MIMETYPE_IMAGE_ANDROID_HEIC;
    private static final int FPS = 30;
    private static final long TIMEOUT_US = 10_000;
    /** MediaCodec.BUFFER_FLAG_MUXER_DATA (hidden in older SDKs): MediaMuxer stores the sample as the image's EXIF item. */
    private static final int MUXER_DATA_FLAG = 16;

    /** One way of writing HEIC: a dedicated HEIC encoder, or an HEVC encoder fed 512 px tiles. */
    static final class Way {
        final String codec, key;
        final boolean heicEncoder, hardware, cq;
        final int qMin, qMax, maxBitrate;
        Way(String codec, String key, boolean heicEncoder, boolean hardware, boolean cq, int qMin, int qMax, int maxBitrate) {
            this.codec = codec; this.key = key; this.heicEncoder = heicEncoder; this.hardware = hardware; this.cq = cq;
            this.qMin = qMin; this.qMax = qMax; this.maxBitrate = maxBitrate;
        }
        String label() {
            return "HEIC via " + codec + " (" + (heicEncoder ? "HEIC encoder" : "512 px tiles") + ", "
                    + (cq ? "constant quality" : "variable bitrate") + (hardware ? "" : ", software") + ")";
        }
    }

    private static List<Way> ways;
    private static String survey = "";

    /** Every encoder that can write HEIC here (Android 9+, YUV input; HEVC encoders must take 512x512 frames). */
    static synchronized List<Way> ways() {
        if (ways == null) { ways = new ArrayList<Way>(); if (Build.VERSION.SDK_INT >= 28) pick(); }
        return ways;
    }

    private static void pick() {
        StringBuilder s = new StringBuilder();
        try {
            for (MediaCodecInfo ci : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
                if (!ci.isEncoder()) continue;
                for (String t : ci.getSupportedTypes()) {
                    boolean isHevc = t.equalsIgnoreCase(HEVC), isHeic = t.equalsIgnoreCase(HEIC);
                    if (!isHevc && !isHeic) continue;
                    MediaCodecInfo.CodecCapabilities caps = ci.getCapabilitiesForType(t);
                    boolean flexible = false;
                    for (int c : caps.colorFormats) if (c == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible) flexible = true;
                    MediaCodecInfo.EncoderCapabilities ec = caps.getEncoderCapabilities();
                    boolean cq = ec != null && ec.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ);
                    boolean vbr = ec != null && ec.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR);
                    Range<Integer> qr = cq ? ec.getQualityRange() : null;
                    if (qr != null && qr.getUpper() <= qr.getLower()) { cq = false; qr = null; }
                    MediaCodecInfo.VideoCapabilities vc = caps.getVideoCapabilities();
                    boolean hw = hardware(ci);
                    s.append(String.format(Locale.US, "    %s %s: %s, YUV input %s, modes%s%s%s, max %s%n", ci.getName(), isHeic ? "(HEIC)" : "(HEVC)",
                            hw ? "hardware" : "software", flexible ? "yes" : "no", cq ? " CQ" : "", vbr ? " VBR" : "",
                            qr != null ? " quality " + qr.getLower() + "-" + qr.getUpper() : "",
                            vc != null ? vc.getSupportedWidths().getUpper() + "x" + vc.getSupportedHeights().getUpper() : "?"));
                    if (!flexible || (!cq && !vbr)) continue;
                    if (isHevc && (vc == null || !vc.isSizeSupported(TILE, TILE))) continue;
                    if (isHeic && !cq) continue;
                    String key = isHeic ? "heic" : hw ? "tiles-hw" : "tiles-sw";
                    int n = 1;
                    for (Way w : ways) if (w.key.startsWith(key)) n++;
                    ways.add(new Way(ci.getName(), n == 1 ? key : key + n, isHeic, hw, cq,
                            qr != null ? qr.getLower() : 0, qr != null ? qr.getUpper() : 0,
                            vc != null ? vc.getBitrateRange().getUpper() : 0));
                }
            }
        } catch (Throwable t) {
            s.append("    (could not list encoders: ").append(t).append(")\n");
        }
        survey = s.toString();
    }

    private static boolean hardware(MediaCodecInfo ci) {
        if (Build.VERSION.SDK_INT >= 29) return ci.isHardwareAccelerated();
        String n = ci.getName().toLowerCase(Locale.ROOT);
        return !(n.startsWith("omx.google.") || n.startsWith("c2.android.") || n.contains(".sw."));
    }

    /** For the Lab: every HEVC / HEIC encoder on the phone and what it offers. */
    public static String survey() { ways(); return survey.isEmpty() ? "    (none)\n" : survey; }

    /**
     * Encodes {@code bm} to {@code outPath} with one way. {@code quality} is 0..100 (higher = larger, closer
     * to the original). {@code exif} is an "Exif\0\0" block to store with the image, or null.
     */
    static void encode(Way way, Bitmap bm, int quality, byte[] exif, String outPath) throws IOException {
        boolean heic = way.heicEncoder;
        String name = way.codec;
        final int w = bm.getWidth(), h = bm.getHeight();
        final int cols = (w + TILE - 1) / TILE, rows = (h + TILE - 1) / TILE;

        MediaFormat f;
        if (heic) {
            f = MediaFormat.createVideoFormat(HEIC, w, h);
            f.setInteger(MediaFormat.KEY_TILE_WIDTH, TILE);
            f.setInteger(MediaFormat.KEY_TILE_HEIGHT, TILE);
            f.setInteger(MediaFormat.KEY_GRID_ROWS, rows);
            f.setInteger(MediaFormat.KEY_GRID_COLUMNS, cols);
            f.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ);
            f.setInteger(MediaFormat.KEY_QUALITY, way.qMin + Math.round((way.qMax - way.qMin) * quality / 100f));
        } else {
            f = MediaFormat.createVideoFormat(HEVC, TILE, TILE);
            if (way.cq) {
                f.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ);
                f.setInteger(MediaFormat.KEY_QUALITY, way.qMin + Math.round((way.qMax - way.qMin) * quality / 100f));
            } else {
                f.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR);
                f.setInteger(MediaFormat.KEY_BIT_RATE, bitrate(quality, way.maxBitrate));
            }
        }
        f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
        f.setInteger(MediaFormat.KEY_FRAME_RATE, FPS);
        f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 0);           // every frame / tile is a key frame
        f.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED);
        f.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT601_PAL);
        f.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO);

        final int frames = heic ? 1 : rows * cols, tiles = rows * cols;
        MediaCodec codec = MediaCodec.createByCodecName(name);
        MediaMuxer muxer = null;
        boolean codecStarted = false, muxerStarted = false;
        try {
            codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            codec.start(); codecStarted = true;
            muxer = new MediaMuxer(outPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_HEIF);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int track = -1, fed = 0, written = 0;
            boolean eos = false;
            long deadline = SystemClock.uptimeMillis() + 30_000L + tiles * 2_000L;
            while (!eos) {
                if (SystemClock.uptimeMillis() > deadline) throw new IOException("the encoder stopped responding");
                if (fed < frames) {
                    int ix = codec.dequeueInputBuffer(TIMEOUT_US);
                    if (ix >= 0) {
                        int size = codec.getInputBuffer(ix).capacity();
                        Image img = codec.getInputImage(ix);
                        if (img == null) throw new IOException("the encoder does not accept YUV images");
                        if (heic) {
                            if (img.getWidth() < w || img.getHeight() < h)
                                throw new IOException("the HEIC encoder gave a " + img.getWidth() + "x" + img.getHeight() + " input for a " + w + "x" + h + " picture");
                            fillYuv(bm, 0, 0, w, h, img);
                        } else {
                            fillYuv(bm, (fed % cols) * TILE, (fed / cols) * TILE, TILE, TILE, img);
                        }
                        codec.queueInputBuffer(ix, 0, size, fed * 1_000_000L / FPS,
                                fed == frames - 1 ? MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0);
                        fed++;
                    }
                }
                int ox = codec.dequeueOutputBuffer(info, TIMEOUT_US);
                if (ox == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (muxerStarted) throw new IOException("the encoder changed its output format");
                    MediaFormat of = codec.getOutputFormat();             // keeps the HEVC parameter sets (csd-0)
                    of.setString(MediaFormat.KEY_MIME, HEIC);
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
            if (written != tiles) throw new IOException("the encoder produced " + written + " of " + tiles + " tiles");
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
    private static int bitrate(int quality, int maxBitrate) {
        double q = quality / 100.0;
        double bpp = 0.15 + 4.0 * q * q;
        long b = (long) (bpp * TILE * TILE * FPS);
        return (int) Math.max(100_000, Math.min(maxBitrate > 0 ? maxBitrate : Integer.MAX_VALUE, b));
    }

    /**
     * Copies an {@code ow} x {@code oh} area of the bitmap starting at (sx, sy) into the encoder's YUV 4:2:0
     * image (BT.601, limited range, as the format declares). Pixels past the bitmap's right or bottom edge
     * repeat the last column / row. Works two rows at a time, so a whole photo needs no large buffer.
     */
    private static void fillYuv(Bitmap bm, int sx, int sy, int ow, int oh, Image img) {
        int bw = bm.getWidth(), bh = bm.getHeight();
        int w0 = Math.max(1, Math.min(ow, bw - sx));              // columns that exist in the bitmap
        Image.Plane[] pl = img.getPlanes();
        ByteBuffer yb = pl[0].getBuffer(), ub = pl[1].getBuffer(), vb = pl[2].getBuffer();
        int yRow = pl[0].getRowStride(), yPix = pl[0].getPixelStride();
        int uRow = pl[1].getRowStride(), uPix = pl[1].getPixelStride();
        int vRow = pl[2].getRowStride(), vPix = pl[2].getPixelStride();
        int[] r0 = new int[ow], r1 = new int[ow];
        for (int y = 0; y < oh; y += 2) {
            row(bm, sx, Math.min(sy + y, bh - 1), w0, ow, r0);
            row(bm, sx, Math.min(sy + y + 1, bh - 1), w0, ow, r1);
            boolean second = y + 1 < oh;
            for (int x = 0; x < ow; x++) {
                yb.put(y * yRow + x * yPix, luma(r0[x]));
                if (second) yb.put((y + 1) * yRow + x * yPix, luma(r1[x]));
            }
            int cy = y / 2;
            for (int cx = 0; cx < (ow + 1) / 2; cx++) {
                int x0 = 2 * cx, x1 = Math.min(x0 + 1, ow - 1);
                int p0 = r0[x0], p1 = r0[x1], p2 = r1[x0], p3 = r1[x1];
                int r = (((p0 >> 16) & 255) + ((p1 >> 16) & 255) + ((p2 >> 16) & 255) + ((p3 >> 16) & 255) + 2) >> 2;
                int g = (((p0 >> 8) & 255) + ((p1 >> 8) & 255) + ((p2 >> 8) & 255) + ((p3 >> 8) & 255) + 2) >> 2;
                int b = ((p0 & 255) + (p1 & 255) + (p2 & 255) + (p3 & 255) + 2) >> 2;
                ub.put(cy * uRow + cx * uPix, (byte) ((-38 * r - 74 * g + 112 * b + 128 >> 8) + 128));
                vb.put(cy * vRow + cx * vPix, (byte) ((112 * r - 94 * g - 18 * b + 128 >> 8) + 128));
            }
        }
    }

    /** One row of {@code ow} pixels: {@code w0} from the bitmap, the rest repeating the last one. */
    private static void row(Bitmap bm, int sx, int y, int w0, int ow, int[] out) {
        bm.getPixels(out, 0, ow, sx, y, w0, 1);
        int edge = out[w0 - 1];
        for (int x = w0; x < ow; x++) out[x] = edge;
    }

    private static byte luma(int p) {
        int r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255;
        return (byte) ((66 * r + 129 * g + 25 * b + 128 >> 8) + 16);
    }
}
