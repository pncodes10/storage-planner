package com.storageplanner.tools;

import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Re-encodes one video (or a stretch of it) with the phone's own codecs, Android framework only:
 * MediaExtractor -> decoder -> Surface -> OpenGL ES (scaling) -> the encoder's input Surface -> MediaMuxer.
 * Every frame keeps its own timestamp (phone videos often have a variable frame rate); frames the plan drops (a
 * lower frame rate) are simply not drawn. Audio is copied sample by sample, or re-encoded to AAC when the plan asks.
 * The rotation is kept as a flag in the file (the pixels are not turned), like the camera writes it.
 */
final class VideoTranscoder {
    private VideoTranscoder() {}

    static final long TIMEOUT_US = 10_000;
    /** Keyframe every 3 s instead of the camera's 1 s: a free size gain, and seeking stays quick. */
    static final int KEYFRAME_SECONDS = 3;

    static final class Job {
        File src, out;
        long fromUs, toUs = Long.MAX_VALUE;
        String encoder, mime;
        int width, height, bitrate, rotation;
        float[] location;       // {lat, lon} or null
        VideoSearch.Plan plan;
        boolean whole() { return fromUs == 0 && toUs == Long.MAX_VALUE; }
    }

    static final class Result {
        long ms, bytes;
        int frames;
        /** Source time of the copy's time 0 (stretches start at their first drawn frame). */
        long baseUs;
        String audio = "none";
        /** The decoder's output buffer: size and crop (a 1088-row buffer cropped to 1080 is common), for the Lab. */
        String decoderOutput;
    }

    static Result run(Job j) throws IOException {
        long t0 = System.currentTimeMillis();
        Result r = new Result();
        MediaExtractor vx = new MediaExtractor();
        MediaCodec dec = null, enc = null;
        MediaMuxer mux = null;
        Gl gl = null;
        Frames frames = null;
        Audio audio = null;
        Surface input = null;
        boolean muxStarted = false;
        try {
            vx.setDataSource(j.src.getPath());
            int vi = track(vx, "video/");
            if (vi < 0) throw new IOException("no video track");
            vx.selectTrack(vi);
            MediaFormat in = vx.getTrackFormat(vi);
            if (in.containsKey(MediaFormat.KEY_ROTATION)) in.setInteger(MediaFormat.KEY_ROTATION, 0);   // keep the pixels as stored

            MediaFormat ef = MediaFormat.createVideoFormat(j.mime, j.width, j.height);
            ef.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            ef.setInteger(MediaFormat.KEY_BIT_RATE, j.bitrate);
            ef.setInteger(MediaFormat.KEY_FRAME_RATE, Math.max(1, (int) Math.round(j.plan.fps)));
            ef.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, KEYFRAME_SECONDS);
            ef.setInteger(MediaFormat.KEY_PRIORITY, 1);   // not real time: the encoder may take its time
            for (String k : new String[]{MediaFormat.KEY_COLOR_STANDARD, MediaFormat.KEY_COLOR_RANGE, MediaFormat.KEY_COLOR_TRANSFER})
                if (in.containsKey(k)) ef.setInteger(k, in.getInteger(k));
            enc = MediaCodec.createByCodecName(j.encoder);
            if (enc.getCodecInfo().getCapabilitiesForType(j.mime).getEncoderCapabilities().isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR))
                ef.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR);
            enc.configure(ef, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            input = enc.createInputSurface();
            gl = new Gl(input, j.width, j.height, in.getInteger(MediaFormat.KEY_WIDTH), in.getInteger(MediaFormat.KEY_HEIGHT));
            enc.start();
            frames = new Frames(gl.texture);
            dec = MediaCodec.createDecoderByType(in.getString(MediaFormat.KEY_MIME));
            dec.configure(in, frames.surface, null, 0);
            dec.start();

            mux = new MediaMuxer(j.out.getPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            if (j.rotation != 0) mux.setOrientationHint(j.rotation);
            if (j.location != null) try { mux.setLocation(j.location[0], j.location[1]); } catch (IllegalArgumentException ignored) {}
            audio = j.whole() ? Audio.open(j) : null;
            r.audio = audio == null ? "none" : audio.what;

            if (!j.whole()) vx.seekTo(j.fromUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            r.baseUs = j.whole() ? 0 : -1;
            long feedUntil = j.toUs == Long.MAX_VALUE ? Long.MAX_VALUE : j.toUs + 1_000_000L;   // decode order runs ahead of display order
            MediaCodec.BufferInfo di = new MediaCodec.BufferInfo(), eo = new MediaCodec.BufferInfo();
            boolean inDone = false, decDone = false, encDone = false, trimNoted = false;
            Trim trim = null;
            int vt = -1, at = -1;
            long lastVideoUs = 0;
            while (!encDone) {
                if (!inDone) {
                    int ix = dec.dequeueInputBuffer(TIMEOUT_US);
                    if (ix >= 0) {
                        ByteBuffer b = dec.getInputBuffer(ix);
                        int n = vx.readSampleData(b, 0);
                        long t = vx.getSampleTime();
                        if (n < 0 || t > feedUntil) { dec.queueInputBuffer(ix, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true; }
                        else { dec.queueInputBuffer(ix, 0, n, t, 0); vx.advance(); }
                    }
                }
                if (!decDone) {
                    int ox = dec.dequeueOutputBuffer(di, TIMEOUT_US);
                    if (ox == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        MediaFormat of = dec.getOutputFormat();
                        r.decoderOutput = describe(of);
                        trim = Trim.of(of);
                    }
                    if (ox >= 0) {
                        long pts = di.presentationTimeUs;
                        boolean draw = di.size > 0 && pts >= j.fromUs && pts < j.toUs && j.plan.keeps(pts);
                        dec.releaseOutputBuffer(ox, draw);
                        if (draw) {
                            frames.await();
                            if (r.baseUs < 0) r.baseUs = pts;
                            float[] st = frames.texture();
                            if (trim != null && trim.undo(st) && !trimNoted) { r.decoderOutput += ", texture trim undone (" + trim + ")"; trimNoted = true; }
                            gl.draw(st);
                            gl.present((pts - r.baseUs) * 1000L);
                            r.frames++;
                        }
                        if ((di.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) { enc.signalEndOfInputStream(); decDone = true; }
                    }
                }
                for (;;) {
                    int ex = enc.dequeueOutputBuffer(eo, decDone ? TIMEOUT_US : 0);
                    if (ex == MediaCodec.INFO_TRY_AGAIN_LATER) break;
                    if (ex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        vt = mux.addTrack(enc.getOutputFormat());
                        if (audio != null) at = mux.addTrack(audio.format);
                        mux.start();
                        muxStarted = true;
                        continue;
                    }
                    if (ex < 0) continue;
                    ByteBuffer b = enc.getOutputBuffer(ex);
                    if ((eo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) eo.size = 0;
                    if (eo.size > 0 && muxStarted) {
                        b.position(eo.offset).limit(eo.offset + eo.size);
                        mux.writeSampleData(vt, b, eo);
                        lastVideoUs = Math.max(lastVideoUs, eo.presentationTimeUs);
                        if (audio != null) audio.writeUpTo(mux, at, lastVideoUs);
                    }
                    enc.releaseOutputBuffer(ex, false);
                    if ((eo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) { encDone = true; break; }
                }
            }
            if (!muxStarted) throw new IOException("the encoder produced no output");
            if (audio != null) audio.writeUpTo(mux, at, Long.MAX_VALUE);
            mux.stop();
        } finally {
            vx.release();
            if (dec != null) try { dec.stop(); } catch (Throwable ignored) {} finally { dec.release(); }
            if (enc != null) try { enc.stop(); } catch (Throwable ignored) {} finally { enc.release(); }
            if (mux != null) try { mux.release(); } catch (Throwable ignored) {}
            if (frames != null) frames.release();
            if (gl != null) gl.release();
            else if (input != null) input.release();      // Gl was never made: its Surface is still ours
            if (audio != null) audio.close();
        }
        r.ms = System.currentTimeMillis() - t0;
        r.bytes = j.out.length();
        return r;
    }

    /**
     * Android's SurfaceTexture, when the decoder's buffer is larger than the picture (1920x1088 holding 1920x1080),
     * pulls the texture coordinates in by a texel on each cropped side so that filtering never reads the padding
     * (GLConsumer's "shrink"). Drawn at the picture's size, that stretches it by 2 px: the copy sits up to a pixel off
     * the original at the edges, which caps its PSNR at any bitrate (measured on the S23 FE: 34.2 dB at 6-14 Mbps).
     * This puts the exact crop back. It only changes a matrix whose scale shows the trim (0.25-1.25 texels a side),
     * so a phone that does not trim is left alone.
     */
    static final class Trim {
        final int bufW, bufH, cropW, cropH;
        float doneX, doneY;
        Trim(int bufW, int bufH, int cropW, int cropH) { this.bufW = bufW; this.bufH = bufH; this.cropW = cropW; this.cropH = cropH; }

        static Trim of(MediaFormat f) {
            if (!f.containsKey(MediaFormat.KEY_WIDTH) || !f.containsKey(MediaFormat.KEY_HEIGHT) || !f.containsKey("crop-right") || !f.containsKey("crop-bottom")) return null;
            int bw = f.getInteger(MediaFormat.KEY_WIDTH), bh = f.getInteger(MediaFormat.KEY_HEIGHT);
            int cw = f.getInteger("crop-right") - (f.containsKey("crop-left") ? f.getInteger("crop-left") : 0) + 1;
            int ch = f.getInteger("crop-bottom") - (f.containsKey("crop-top") ? f.getInteger("crop-top") : 0) + 1;
            if (cw >= bw && ch >= bh) return null;
            return new Trim(bw, bh, cw, ch);
        }

        /** Corrects a SurfaceTexture matrix in place; true when it changed something. */
        boolean undo(float[] m) {
            if (Math.abs(m[1]) > 1e-6 || Math.abs(m[4]) > 1e-6) return false;   // rotated: leave it
            boolean x = fix(m, 0, 12, bufW, cropW, true), y = fix(m, 5, 13, bufH, cropH, false);
            return x || y;
        }

        private boolean fix(float[] m, int scale, int offset, int buf, int crop, boolean isX) {
            if (crop >= buf) return false;
            double s = Math.abs(m[scale]) * buf;            // texels the picture spans now
            double trimmed = (crop - s) / 2;                // texels taken off each side
            if (trimmed < 0.25 || trimmed > 1.25) return false;
            double centre = m[offset] + m[scale] * 0.5;
            float ns = (float) Math.copySign((double) crop / buf, m[scale]);
            m[scale] = ns;
            m[offset] = (float) (centre - ns * 0.5);
            if (isX) doneX = (float) trimmed; else doneY = (float) trimmed;
            return true;
        }

        @Override public String toString() {
            return String.format(java.util.Locale.US, "%dx%d in %dx%d, %.1f / %.1f texels a side", cropW, cropH, bufW, bufH, doneX, doneY);
        }
    }

    static String describe(MediaFormat f) {
        StringBuilder s = new StringBuilder();
        s.append(f.containsKey(MediaFormat.KEY_WIDTH) ? f.getInteger(MediaFormat.KEY_WIDTH) : -1).append('x')
         .append(f.containsKey(MediaFormat.KEY_HEIGHT) ? f.getInteger(MediaFormat.KEY_HEIGHT) : -1);
        if (f.containsKey("crop-left") && f.containsKey("crop-bottom"))
            s.append(" crop ").append(f.getInteger("crop-left")).append(',').append(f.getInteger("crop-top")).append('-')
             .append(f.getInteger("crop-right")).append(',').append(f.getInteger("crop-bottom"));
        if (f.containsKey(MediaFormat.KEY_COLOR_RANGE)) s.append(", range ").append(f.getInteger(MediaFormat.KEY_COLOR_RANGE) == MediaFormat.COLOR_RANGE_FULL ? "full" : "limited");
        if (f.containsKey(MediaFormat.KEY_COLOR_STANDARD)) s.append(", standard ").append(f.getInteger(MediaFormat.KEY_COLOR_STANDARD));
        return s.toString();
    }

    static int track(MediaExtractor x, String prefix) {
        for (int i = 0; i < x.getTrackCount(); i++) {
            String m = x.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (m != null && m.startsWith(prefix)) return i;
        }
        return -1;
    }

    // ------------------------------------------------------------------ audio
    /** Audio samples to write, interleaved with the video by time: copied from the source, or re-encoded AAC. */
    static final class Audio {
        MediaFormat format;
        String what;
        MediaExtractor x;              // copy
        ByteBuffer buf;
        List<byte[]> data;             // re-encoded
        List<long[]> info;             // {pts, flags}
        int next;

        static Audio open(Job j) throws IOException {
            MediaExtractor x = extractor(j.src);
            int ai = track(x, "audio/");
            if (ai < 0) { x.release(); return null; }
            MediaFormat f = x.getTrackFormat(ai);
            Audio a = new Audio();
            if (j.plan.audioBps > 0) {
                try {
                    x.selectTrack(ai);
                    a.reencode(x, f, j.plan.audioBps);
                    x.release();
                    a.what = "AAC " + j.plan.audioBps / 1000 + " kbps";
                    return a;
                } catch (Throwable t) {
                    x.release();
                    x = extractor(j.src);
                    a.what = "copied (re-encoding failed: " + t.getMessage() + ")";
                }
            } else a.what = "copied";
            x.selectTrack(ai);
            a.x = x;
            a.format = f;
            a.buf = ByteBuffer.allocateDirect(f.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE) ? Math.max(64 * 1024, f.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)) : 1 << 20);
            return a;
        }

        /** An extractor on the file, released again if opening it fails. */
        static MediaExtractor extractor(File f) throws IOException {
            MediaExtractor x = new MediaExtractor();
            try { x.setDataSource(f.getPath()); }
            catch (IOException | RuntimeException e) { x.release(); throw e; }
            return x;
        }

        void writeUpTo(MediaMuxer mux, int track, long us) {
            MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
            if (x != null) {
                for (;;) {
                    long t = x.getSampleTime();
                    if (t < 0 || t > us) return;
                    int n = x.readSampleData(buf, 0);
                    if (n < 0) return;
                    bi.set(0, n, t, (x.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0 ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
                    mux.writeSampleData(track, buf, bi);
                    x.advance();
                }
            }
            while (next < data.size() && info.get(next)[0] <= us) {
                byte[] d = data.get(next);
                bi.set(0, d.length, info.get(next)[0], (int) info.get(next)[1]);
                mux.writeSampleData(track, ByteBuffer.wrap(d), bi);
                next++;
            }
        }

        void close() { if (x != null) x.release(); }

        /** Decodes the whole audio track to PCM and encodes it as AAC-LC at bps, kept in memory (audio is small). */
        void reencode(MediaExtractor x, MediaFormat f, int bps) throws IOException {
            data = new ArrayList<byte[]>();
            info = new ArrayList<long[]>();
            MediaCodec dec = MediaCodec.createDecoderByType(f.getString(MediaFormat.KEY_MIME)), enc = null;
            try {
                dec.configure(f, null, null, 0);
                dec.start();
                MediaCodec.BufferInfo di = new MediaCodec.BufferInfo(), eo = new MediaCodec.BufferInfo();
                boolean inDone = false, decDone = false, encDone = false;
                int rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE), ch = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                long samples = 0, firstUs = -1;
                ByteBuffer pending = null;
                while (!encDone) {
                    if (!inDone) {
                        int ix = dec.dequeueInputBuffer(TIMEOUT_US);
                        if (ix >= 0) {
                            int n = x.readSampleData(dec.getInputBuffer(ix), 0);
                            if (n < 0) { dec.queueInputBuffer(ix, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true; }
                            else { dec.queueInputBuffer(ix, 0, n, x.getSampleTime(), 0); x.advance(); }
                        }
                    }
                    if (pending == null && !decDone) {
                        int ox = dec.dequeueOutputBuffer(di, TIMEOUT_US);
                        if (ox == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            MediaFormat of = dec.getOutputFormat();
                            if (of.containsKey(MediaFormat.KEY_PCM_ENCODING) && of.getInteger(MediaFormat.KEY_PCM_ENCODING) != android.media.AudioFormat.ENCODING_PCM_16BIT)
                                throw new IOException("the audio decoder gives non-16-bit samples");
                            rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE); ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                        } else if (ox >= 0) {
                            if (enc == null) {
                                MediaFormat af = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, ch);
                                af.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
                                af.setInteger(MediaFormat.KEY_BIT_RATE, bps);
                                af.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1 << 16);
                                enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
                                enc.configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                                enc.start();
                            }
                            if (firstUs < 0 && di.size > 0) firstUs = di.presentationTimeUs;
                            ByteBuffer o = dec.getOutputBuffer(ox);
                            o.position(di.offset).limit(di.offset + di.size);
                            pending = ByteBuffer.allocate(di.size);
                            pending.put(o).flip();
                            if ((di.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) decDone = true;
                            dec.releaseOutputBuffer(ox, false);
                        }
                    }
                    if (enc == null) { if (decDone) throw new IOException("no audio decoded"); continue; }
                    if (pending != null) {
                        int ix = enc.dequeueInputBuffer(TIMEOUT_US);
                        if (ix >= 0) {
                            ByteBuffer b = enc.getInputBuffer(ix);
                            int n = Math.min(b.remaining(), pending.remaining());
                            n -= n % (2 * ch);
                            ByteBuffer part = pending.duplicate();
                            part.limit(pending.position() + n);
                            b.put(part);
                            pending.position(pending.position() + n);
                            long pts = Math.max(0, firstUs) + samples * 1_000_000L / rate;
                            samples += n / (2 * ch);
                            boolean last = decDone && !pending.hasRemaining();
                            enc.queueInputBuffer(ix, 0, n, pts, last ? MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0);
                            if (!pending.hasRemaining()) pending = null;
                        }
                    }
                    for (;;) {
                        int ex = enc.dequeueOutputBuffer(eo, 0);
                        if (ex == MediaCodec.INFO_TRY_AGAIN_LATER) break;
                        if (ex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { format = enc.getOutputFormat(); continue; }
                        if (ex < 0) continue;
                        if ((eo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && eo.size > 0) {
                            ByteBuffer b = enc.getOutputBuffer(ex);
                            b.position(eo.offset).limit(eo.offset + eo.size);
                            byte[] d = new byte[eo.size];
                            b.get(d);
                            data.add(d);
                            info.add(new long[]{eo.presentationTimeUs, MediaCodec.BUFFER_FLAG_KEY_FRAME});
                        }
                        enc.releaseOutputBuffer(ex, false);
                        if ((eo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) { encDone = true; break; }
                    }
                }
                if (format == null || data.isEmpty()) throw new IOException("the AAC encoder produced nothing");
            } finally {
                try { dec.stop(); } catch (Throwable ignored) {}
                dec.release();
                if (enc != null) { try { enc.stop(); } catch (Throwable ignored) {} enc.release(); }
            }
        }
    }

    // ------------------------------------------------------------------ frames from the decoder
    /** The decoder draws into this SurfaceTexture; its "frame available" callback arrives on a thread of its own. */
    static final class Frames {
        final SurfaceTexture st;
        final Surface surface;
        final HandlerThread thread = new HandlerThread("video-frames");
        private final Object lock = new Object();
        private boolean available;

        Frames(int texture) {
            thread.start();
            st = new SurfaceTexture(texture);
            st.setOnFrameAvailableListener(new SurfaceTexture.OnFrameAvailableListener() {
                public void onFrameAvailable(SurfaceTexture t) { synchronized (lock) { available = true; lock.notifyAll(); } }
            }, new Handler(thread.getLooper()));
            surface = new Surface(st);
        }

        void await() throws IOException {
            synchronized (lock) {
                long until = System.currentTimeMillis() + 5000;
                while (!available) {
                    long left = until - System.currentTimeMillis();
                    if (left <= 0) throw new IOException("the decoder did not deliver a frame");
                    try { lock.wait(left); } catch (InterruptedException e) { throw new IOException("interrupted"); }
                }
                available = false;
            }
            st.updateTexImage();
        }

        float[] texture() { float[] m = new float[16]; st.getTransformMatrix(m); return m; }

        void release() { surface.release(); st.release(); thread.quitSafely(); }
    }

    // ------------------------------------------------------------------ OpenGL: draw the frame at the copy's size
    static final class Gl {
        private static final int EGL_RECORDABLE_ANDROID = 0x3142;
        final Surface encoderSurface;
        EGLDisplay display = EGL14.EGL_NO_DISPLAY;
        EGLContext context = EGL14.EGL_NO_CONTEXT;
        EGLSurface surface = EGL14.EGL_NO_SURFACE;
        int texture, program, aPos, aTex, uSt, uStep;
        final int width, height;
        final float stepX, stepY;
        final FloatBuffer quad;

        Gl(Surface s, int width, int height, int srcW, int srcH) throws IOException {
            encoderSurface = s; this.width = width; this.height = height;
            // a 4-tap average over each output pixel's footprint when shrinking (plain bilinear would alias)
            stepX = srcW > width * 1.2f ? 0.5f / width : 0; stepY = srcH > height * 1.2f ? 0.5f / height : 0;
            float[] q = {-1, -1, 0, 0, 1, -1, 1, 0, -1, 1, 0, 1, 1, 1, 1, 1};   // x, y, s, t
            quad = ByteBuffer.allocateDirect(q.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
            quad.put(q).position(0);
            // a failure part-way must not leave an EGL context current on this thread or the encoder's Surface open
            try { setUp(s); } catch (IOException | RuntimeException e) { release(); throw e; }
        }

        private void setUp(Surface s) throws IOException {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            int[] v = new int[2];
            if (!EGL14.eglInitialize(display, v, 0, v, 1)) throw new IOException("EGL init failed");
            int[] attrs = {EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL_RECORDABLE_ANDROID, 1, EGL14.EGL_NONE};
            EGLConfig[] cfg = new EGLConfig[1];
            int[] n = new int[1];
            if (!EGL14.eglChooseConfig(display, attrs, 0, cfg, 0, 1, n, 0) || n[0] == 0) throw new IOException("no EGL config");
            context = EGL14.eglCreateContext(display, cfg[0], EGL14.EGL_NO_CONTEXT, new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
            surface = EGL14.eglCreateWindowSurface(display, cfg[0], s, new int[]{EGL14.EGL_NONE}, 0);
            if (context == EGL14.EGL_NO_CONTEXT || surface == EGL14.EGL_NO_SURFACE) throw new IOException("EGL surface failed");
            if (!EGL14.eglMakeCurrent(display, surface, surface, context)) throw new IOException("eglMakeCurrent failed");
            program = program(
                    "attribute vec4 aPos; attribute vec4 aTex; uniform mat4 uSt; varying vec2 v;\n"
                            + "void main() { gl_Position = aPos; v = (uSt * aTex).xy; }",
                    "#extension GL_OES_EGL_image_external : require\nprecision mediump float; varying vec2 v; uniform samplerExternalOES s; uniform vec2 uStep;\n"
                            + "void main() { gl_FragColor = 0.25 * (texture2D(s, v + vec2(-uStep.x, -uStep.y)) + texture2D(s, v + vec2(uStep.x, -uStep.y))\n"
                            + "  + texture2D(s, v + vec2(-uStep.x, uStep.y)) + texture2D(s, v + vec2(uStep.x, uStep.y))); }");
            aPos = GLES20.glGetAttribLocation(program, "aPos");
            aTex = GLES20.glGetAttribLocation(program, "aTex");
            uSt = GLES20.glGetUniformLocation(program, "uSt");
            uStep = GLES20.glGetUniformLocation(program, "uStep");
            int[] t = new int[1];
            GLES20.glGenTextures(1, t, 0);
            texture = t[0];
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        }

        void draw(float[] st) {
            GLES20.glViewport(0, 0, width, height);
            GLES20.glUseProgram(program);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture);
            quad.position(0);
            GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, quad);
            GLES20.glEnableVertexAttribArray(aPos);
            quad.position(2);
            GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 16, quad);
            GLES20.glEnableVertexAttribArray(aTex);
            GLES20.glUniformMatrix4fv(uSt, 1, false, st, 0);
            GLES20.glUniform2f(uStep, stepX, stepY);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        }

        void present(long ns) {
            EGLExt.eglPresentationTimeANDROID(display, surface, ns);
            EGL14.eglSwapBuffers(display, surface);
        }

        void release() {
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface);
                if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context);
                EGL14.eglReleaseThread();
                EGL14.eglTerminate(display);
            }
            encoderSurface.release();
        }

        private static int program(String vs, String fs) throws IOException {
            int p = GLES20.glCreateProgram();
            GLES20.glAttachShader(p, shader(GLES20.GL_VERTEX_SHADER, vs));
            GLES20.glAttachShader(p, shader(GLES20.GL_FRAGMENT_SHADER, fs));
            GLES20.glLinkProgram(p);
            int[] ok = new int[1];
            GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
            if (ok[0] == 0) throw new IOException("GL link: " + GLES20.glGetProgramInfoLog(p));
            return p;
        }

        private static int shader(int type, String src) throws IOException {
            int s = GLES20.glCreateShader(type);
            GLES20.glShaderSource(s, src);
            GLES20.glCompileShader(s);
            int[] ok = new int[1];
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
            if (ok[0] == 0) throw new IOException("GL shader: " + GLES20.glGetShaderInfoLog(s));
            return s;
        }
    }
}
