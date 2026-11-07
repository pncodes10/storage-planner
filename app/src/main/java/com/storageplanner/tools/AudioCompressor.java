package com.storageplanner.tools;

import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Build;

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Makes one checked, smaller copy of one audio recording (a call recording, a voice note, a lecture, music) at a
 * {@link AudioMath.Level}: Opus in an .ogg file (Android 10+), or AAC in an .m4a file. The original is only read.
 *
 * <p>In one pass the recording is decoded with the phone's own decoder, mixed down to the level's channels,
 * resampled (windowed sinc, never up), encoded and written to a work file. Then the work file is decoded again
 * and checked against the original: the same length, the same loudness envelope, the same loudness. Only a copy
 * that passes and is smaller is renamed to {@code out}; it gets the original's modified time.
 */
public final class AudioCompressor {
    private AudioCompressor() {}

    static final long TIMEOUT_US = 10_000;
    static final int CHUNK = 8_192;                   // frames per step through mix and resampler

    /** What a recording holds, from its container. */
    public static final class Info {
        public String mime;
        public int rate, channels, bitrate;
        public long durationUs, bytes;
        int track = -1;

        public String describe() {
            return String.format(Locale.US, "%s, %d Hz, %d ch, %.1f s, %s", mime, rate, channels, durationUs / 1e6,
                    durationUs > 0 && bytes > 0 ? Math.round(bytes * 8e3 / durationUs) + " kbps" : "? kbps");
        }
    }

    public static final class Outcome {
        public boolean ok;
        public String skipped;
        public Info in;
        public AudioMath.Plan plan;
        public AudioMath.Match match;
        public long before, after, ms;
        public double inSeconds, outSeconds;
        public String encoder;
        public final List<String> trace = new ArrayList<String>();

        Outcome skip(String why) { skipped = why; ok = false; return this; }
    }

    /** Reports how far a long recording is (0..1). */
    public interface Progress { void at(double done); }

    /** Reads the container's description of the first audio track. */
    public static Info probe(FileDescriptor fd, long bytes) throws IOException {
        MediaExtractor x = new MediaExtractor();
        try {
            x.setDataSource(fd);
            return probe(x, bytes);
        } finally { x.release(); }
    }

    static Info probe(MediaExtractor x, long bytes) throws IOException {
        Info i = new Info();
        i.bytes = bytes;
        for (int t = 0; t < x.getTrackCount(); t++) {
            MediaFormat f = x.getTrackFormat(t);
            String m = f.getString(MediaFormat.KEY_MIME);
            if (m != null && m.startsWith("audio/")) {
                i.track = t; i.mime = m;
                i.rate = f.containsKey(MediaFormat.KEY_SAMPLE_RATE) ? f.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 0;
                i.channels = f.containsKey(MediaFormat.KEY_CHANNEL_COUNT) ? f.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 0;
                i.durationUs = f.containsKey(MediaFormat.KEY_DURATION) ? f.getLong(MediaFormat.KEY_DURATION) : -1;
                i.bitrate = f.containsKey(MediaFormat.KEY_BIT_RATE) ? f.getInteger(MediaFormat.KEY_BIT_RATE) : 0;
                return i;
            }
        }
        throw new IOException("no audio track");
    }

    /** Whether this phone can make copies at {@code level}: Opus needs Android 10+ (its encoder and the Ogg writer). */
    public static String cannot(AudioMath.Level level) {
        if (level.opus && Build.VERSION.SDK_INT < 29) return "Opus needs Android 10 or newer; use the Compatible level";
        String mime = level.opus ? MediaFormat.MIMETYPE_AUDIO_OPUS : MediaFormat.MIMETYPE_AUDIO_AAC;
        return encoderFor(mime) == null ? "this phone has no " + (level.opus ? "Opus" : "AAC") + " encoder" : null;
    }

    static String encoderFor(String mime) {
        for (MediaCodecInfo c : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            if (!c.isEncoder()) continue;
            for (String t : c.getSupportedTypes()) if (t.equalsIgnoreCase(mime)) return c.getName();
        }
        return null;
    }

    /**
     * Makes the copy. {@code fd} is the original (read from its start), {@code bytes} its size and {@code modified}
     * its modified time (given to the copy). Never throws: a failure is an outcome with {@code skipped} set.
     */
    public static Outcome compress(FileDescriptor fd, long bytes, long modified, AudioMath.Level level, File out,
                                   AtomicBoolean stop, Progress progress) {
        Outcome o = new Outcome();
        o.before = bytes;
        long t0 = System.nanoTime();
        File work = new File(out.getParentFile(), "." + out.getName() + ".part");
        MediaExtractor x = new MediaExtractor();
        try {
            x.setDataSource(fd);
            o.in = probe(x, bytes);
            o.trace.add("original: " + o.in.describe());
            String no = cannot(level);
            if (no != null) return o.skip(no);
            o.plan = AudioMath.plan(level, bytes, o.in.durationUs, o.in.rate, o.in.channels);
            o.trace.add("plan: " + o.plan.describe() + String.format(Locale.US, ", about %s expected", kb(o.plan.expectedBytes)));
            if (o.plan.skip != null) return o.skip(o.plan.skip);
            if (out.getParentFile() != null && out.getParentFile().getUsableSpace() < o.plan.expectedBytes * 2 + (16 << 20))
                return o.skip("not enough free space for the copy");
            x.selectTrack(o.in.track);
            work.delete();                                      // a leftover from an interrupted run
            Encode e = new Encode(o.plan, work);
            AudioMath.Envelope inEnv;
            try {
                inEnv = decode(x, x.getTrackFormat(o.in.track), e, stop, progress, o.in.durationUs);
                e.finish();
            } finally { e.release(); }
            o.encoder = e.codecName;
            if (stop != null && stop.get()) { work.delete(); return o.skip("stopped"); }
            long encMs = (System.nanoTime() - t0) / 1_000_000;
            o.trace.add(String.format(Locale.US, "encoded with %s: %d packets, %s in %.1f s (%.0f× real time)", e.codecName, e.packets,
                    kb(work.length()), encMs / 1000.0, inEnv.seconds() * 1000 / Math.max(1, encMs)));

            // the check: decode the copy and compare it with the original
            long t1 = System.nanoTime();
            AudioMath.Envelope outEnv;
            MediaExtractor y = new MediaExtractor();
            try {
                y.setDataSource(work.getPath());
                Info wi = probe(y, work.length());
                y.selectTrack(wi.track);
                outEnv = decode(y, y.getTrackFormat(wi.track), null, null, null, 0);
                o.trace.add("copy: " + wi.describe());
            } finally { y.release(); }
            o.inSeconds = inEnv.seconds(); o.outSeconds = outEnv.seconds();
            o.match = AudioMath.match(inEnv.values(), outEnv.values());
            o.trace.add(String.format(Locale.US, "check: %.2f s → %.2f s, %s (%.1f s)", o.inSeconds, o.outSeconds, o.match,
                    (System.nanoTime() - t1) / 1e9));
            String bad = AudioMath.verdict(o.inSeconds, o.outSeconds, o.match);
            if (bad != null) { work.delete(); return o.skip("failed the check: " + bad); }
            o.after = work.length();
            if (o.after >= bytes) { work.delete(); return o.skip("the copy is not smaller (" + kb(o.after) + ")"); }
            if (out.exists()) { work.delete(); return o.skip(out.getName() + " already exists"); }
            if (!work.renameTo(out)) { work.delete(); return o.skip("could not rename the work file"); }
            if (modified > 0) out.setLastModified(modified);
            o.ok = true;
            return o;
        } catch (Throwable t) {
            work.delete();
            return o.skip("failed: " + t);
        } finally {
            x.release();
            o.ms = (System.nanoTime() - t0) / 1_000_000;
        }
    }

    static String kb(long b) { return b >= 1 << 20 ? String.format(Locale.US, "%.2f MB", b / 1048576.0) : String.format(Locale.US, "%.1f KB", b / 1024.0); }

    // ------------------------------------------------------------------ decoding
    /**
     * Decodes the selected track to float frames, recording its loudness envelope, and hands each chunk to
     * {@code sink} (when not null). Returns the envelope.
     */
    static AudioMath.Envelope decode(MediaExtractor x, MediaFormat f, Encode sink, AtomicBoolean stop, Progress progress, long durationUs) throws IOException {
        MediaCodec dec = MediaCodec.createDecoderByType(f.getString(MediaFormat.KEY_MIME));
        AudioMath.Envelope env = null;
        try {
            dec.configure(f, null, null, 0);
            dec.start();
            MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
            int rate = f.containsKey(MediaFormat.KEY_SAMPLE_RATE) ? f.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 0;
            int ch = f.containsKey(MediaFormat.KEY_CHANNEL_COUNT) ? f.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 0;
            boolean isFloat = false, inDone = false;
            float[] fl = new float[CHUNK * 8];
            long lastReport = 0;
            for (;;) {
                if (stop != null && stop.get()) break;
                if (!inDone) {
                    int ix = dec.dequeueInputBuffer(TIMEOUT_US);
                    if (ix >= 0) {
                        int n = x.readSampleData(dec.getInputBuffer(ix), 0);
                        if (n < 0) { dec.queueInputBuffer(ix, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true; }
                        else {
                            long t = x.getSampleTime();
                            dec.queueInputBuffer(ix, 0, n, t, 0);
                            x.advance();
                            if (progress != null && durationUs > 0 && t - lastReport > 500_000) { lastReport = t; progress.at(Math.min(1, (double) t / durationUs)); }
                        }
                    }
                }
                int ox = dec.dequeueOutputBuffer(bi, TIMEOUT_US);
                if (ox == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat of = dec.getOutputFormat();
                    rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    int enc = of.containsKey(MediaFormat.KEY_PCM_ENCODING) ? of.getInteger(MediaFormat.KEY_PCM_ENCODING) : AudioFormat.ENCODING_PCM_16BIT;
                    if (enc != AudioFormat.ENCODING_PCM_16BIT && enc != AudioFormat.ENCODING_PCM_FLOAT)
                        throw new IOException("the decoder gives PCM encoding " + enc);
                    isFloat = enc == AudioFormat.ENCODING_PCM_FLOAT;
                    continue;
                }
                if (ox < 0) continue;
                if (bi.size > 0) {
                    if (rate <= 0 || ch <= 0) throw new IOException("the decoder did not say its sample rate");
                    if (env == null) env = new AudioMath.Envelope(rate);
                    ByteBuffer b = dec.getOutputBuffer(ox);
                    b.position(bi.offset).limit(bi.offset + bi.size);
                    b = b.slice().order(ByteOrder.nativeOrder());
                    int samples = bi.size / (isFloat ? 4 : 2);
                    if (fl.length < samples) fl = new float[samples];
                    if (isFloat) b.asFloatBuffer().get(fl, 0, samples);
                    else { java.nio.ShortBuffer sb = b.asShortBuffer(); for (int i = 0; i < samples; i++) fl[i] = sb.get(i) / 32768f; }
                    int frames = samples / ch;
                    env.add(fl, frames, ch);
                    if (sink != null) sink.write(fl, frames, rate, ch);
                }
                dec.releaseOutputBuffer(ox, false);
                if ((bi.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
            }
            if (env == null) throw new IOException("nothing decoded");
            return env;
        } finally {
            try { dec.stop(); } catch (Throwable ignored) {}
            dec.release();
        }
    }

    // ------------------------------------------------------------------ encoding
    /** Mix, resample, encode and write: fed with decoded float frames. */
    static final class Encode {
        final AudioMath.Plan plan;
        final File file;
        MediaCodec enc;
        MediaMuxer mux;
        int track = -1;
        String codecName;
        AudioMath.Resampler rs;
        float[] mixed = new float[0], res = new float[0];
        byte[] pcm = new byte[0];
        long framesIn, packets;
        final MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
        boolean muxStarted;

        Encode(AudioMath.Plan plan, File file) throws IOException {
            this.plan = plan; this.file = file;
            String mime = plan.level.opus ? MediaFormat.MIMETYPE_AUDIO_OPUS : MediaFormat.MIMETYPE_AUDIO_AAC;
            MediaFormat f = MediaFormat.createAudioFormat(mime, plan.rate, plan.channels);
            f.setInteger(MediaFormat.KEY_BIT_RATE, plan.bps);
            if (!plan.level.opus) f.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1 << 16);
            enc = MediaCodec.createEncoderByType(mime);
            codecName = enc.getName();
            enc.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            enc.start();
            mux = new MediaMuxer(file.getPath(), plan.level.opus ? MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG : MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        }

        void write(float[] in, int frames, int rate, int ch) throws IOException {
            if (rs == null) rs = new AudioMath.Resampler(rate, plan.rate, plan.channels);
            else if (rs.inRate != rate) throw new IOException("the sample rate changed mid-stream");
            for (int off = 0; off < frames; off += CHUNK) {
                int n = Math.min(CHUNK, frames - off);
                if (mixed.length < n * plan.channels) mixed = new float[n * plan.channels];
                float[] part = in;
                if (off > 0) { part = new float[n * ch]; System.arraycopy(in, off * ch, part, 0, n * ch); }
                AudioMath.mix(part, ch, mixed, plan.channels, n);
                int need = rs.maxOut(n) * plan.channels;
                if (res.length < need) res = new float[need];
                feed(rs.process(mixed, 0, n, res));
            }
        }

        void finish() throws IOException {
            if (rs != null) {
                int need = rs.maxOut(2 * rs.half + 4) * plan.channels;
                if (res.length < need) res = new float[need];
                feed(rs.end(res));
            }
            for (;;) {                                           // end of stream
                int ix = enc.dequeueInputBuffer(TIMEOUT_US);
                if (ix >= 0) { enc.queueInputBuffer(ix, 0, 0, pts(), MediaCodec.BUFFER_FLAG_END_OF_STREAM); break; }
                drain(false);
            }
            while (!drain(true)) { /* until the encoder's end of stream */ }
            if (!muxStarted) throw new IOException("the encoder gave no output");
            mux.stop();
        }

        long pts() { return framesIn * 1_000_000L / plan.rate; }

        private void feed(int frames) throws IOException {
            int bytes = frames * plan.channels * 2;
            if (pcm.length < bytes) pcm = new byte[bytes];
            AudioMath.toPcm16(res, frames * plan.channels, pcm);
            int done = 0;
            while (done < bytes) {
                int ix = enc.dequeueInputBuffer(TIMEOUT_US);
                if (ix < 0) { drain(false); continue; }
                ByteBuffer b = enc.getInputBuffer(ix);
                int n = Math.min(b.remaining(), bytes - done);
                n -= n % (2 * plan.channels);
                b.put(pcm, done, n);
                enc.queueInputBuffer(ix, 0, n, pts(), 0);
                framesIn += n / (2 * plan.channels);
                done += n;
                drain(false);
            }
        }

        /** Moves the encoder's output to the muxer; true once the encoder's end of stream came out. */
        private boolean drain(boolean wait) throws IOException {
            for (;;) {
                int ox = enc.dequeueOutputBuffer(bi, wait ? TIMEOUT_US : 0);
                if (ox == MediaCodec.INFO_TRY_AGAIN_LATER) return false;
                if (ox == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (muxStarted) throw new IOException("the encoder changed its format");
                    outFormat = enc.getOutputFormat();
                    continue;
                }
                if (ox < 0) continue;
                ByteBuffer b = enc.getOutputBuffer(ox);
                b.position(bi.offset).limit(bi.offset + bi.size);
                if ((bi.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    config = new byte[bi.size];
                    b.get(config);
                } else if (bi.size > 0) {
                    if (!muxStarted) startMuxer();
                    mux.writeSampleData(track, b, bi);
                    packets++;
                }
                enc.releaseOutputBuffer(ox, false);
                if ((bi.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return true;
            }
        }

        MediaFormat outFormat;
        byte[] config;

        /** Adds the track with the encoder's format, taking the codec headers from its config buffer when the format lacks them. */
        private void startMuxer() throws IOException {
            MediaFormat f = outFormat != null ? outFormat : enc.getOutputFormat();
            if (!f.containsKey("csd-0")) {
                if (config == null) throw new IOException("the encoder gave no codec header");
                if (plan.level.opus) opusHeaders(f, config);
                else f.setByteBuffer("csd-0", ByteBuffer.wrap(config));
            }
            track = mux.addTrack(f);
            mux.start();
            muxStarted = true;
        }

        /**
         * Opus needs three headers in the format: the OpusHead (csd-0), the codec delay (csd-1) and the seek pre-roll
         * (csd-2), both in nanoseconds. Android's encoder gives them in one config buffer, either as marked blocks
         * ("AOPUSHDR", "AOPUSDLY", "AOPUSPRL", each with a 64-bit length) or as a bare OpusHead.
         */
        static void opusHeaders(MediaFormat f, byte[] c) throws IOException {
            ByteBuffer b = ByteBuffer.wrap(c).order(ByteOrder.LITTLE_ENDIAN);
            byte[] head = null; long delayNs = -1, prerollNs = 80_000_000L;
            if (c.length >= 8 && new String(c, 0, 8, java.nio.charset.StandardCharsets.US_ASCII).equals("OpusHead")) {
                head = c;
            } else {
                while (b.remaining() >= 16) {
                    String mark = new String(c, b.position(), 8, java.nio.charset.StandardCharsets.US_ASCII);
                    b.position(b.position() + 8);
                    long len = b.getLong();
                    if (len < 0 || len > b.remaining()) throw new IOException("a broken Opus config block");
                    byte[] v = new byte[(int) len];
                    b.get(v);
                    ByteBuffer vb = ByteBuffer.wrap(v).order(ByteOrder.LITTLE_ENDIAN);
                    if (mark.equals("AOPUSHDR")) head = v;
                    else if (mark.equals("AOPUSDLY") && len == 8) delayNs = vb.getLong();
                    else if (mark.equals("AOPUSPRL") && len == 8) prerollNs = vb.getLong();
                }
            }
            if (head == null || head.length < 19) throw new IOException("no OpusHead in the encoder's config");
            if (delayNs < 0) delayNs = ((head[10] & 255) | (head[11] & 255) << 8) * 1_000_000_000L / 48_000;   // its pre-skip, in 48 kHz samples
            f.setByteBuffer("csd-0", ByteBuffer.wrap(head));
            f.setByteBuffer("csd-1", ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(0, delayNs));
            f.setByteBuffer("csd-2", ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(0, prerollNs));
        }

        void release() {
            try { enc.stop(); } catch (Throwable ignored) {}
            enc.release();
            try { mux.release(); } catch (Throwable ignored) {}
        }
    }
}
