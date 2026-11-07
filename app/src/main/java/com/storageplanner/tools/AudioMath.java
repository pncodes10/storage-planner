package com.storageplanner.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The plain-Java parts of the audio tool (tested on a desktop JVM, see jvmtest/AudioTest): the levels and what each
 * one makes of a recording ({@link Plan}), a streaming resampler, and the loudness envelope used to check that a
 * compressed copy still holds the same sound. The codecs themselves are Android's (see {@link AudioCompressor}).
 */
public final class AudioMath {
    private AudioMath() {}

    // ------------------------------------------------------------------ levels
    /**
     * A level: codec, bitrate, channels and the highest sample rate it uses. Opus is the best lossy codec for speech
     * at low bitrates (wideband speech from about 12 kbps); AAC is the fallback that every player and phone reads.
     */
    public enum Level {
        VOICE_SMALL("Voice small", true, 12_000, 1, 16_000, "Opus 12 kbps mono: clear speech, the smallest"),
        VOICE("Voice", true, 16_000, 1, 16_000, "Opus 16 kbps mono: calls and voice notes (recommended)"),
        VOICE_CLEAR("Voice clear", true, 24_000, 1, 24_000, "Opus 24 kbps mono: speech with more air, lectures, meetings"),
        MUSIC("Music", true, 96_000, 2, 48_000, "Opus 96 kbps, channels kept: music"),
        COMPATIBLE("Compatible", false, 32_000, 1, 16_000, "AAC 32 kbps mono (.m4a): plays on every phone and computer");

        public final String label, about;
        public final boolean opus;
        public final int bps, channels, maxRate;

        Level(String label, boolean opus, int bps, int channels, int maxRate, String about) {
            this.label = label; this.opus = opus; this.bps = bps; this.channels = channels; this.maxRate = maxRate; this.about = about;
        }

        public String extension() { return opus ? ".ogg" : ".m4a"; }
    }

    /** The sample rates the Opus encoder takes. */
    static final int[] OPUS_RATES = {8_000, 12_000, 16_000, 24_000, 48_000};

    /** What a level makes of a recording, or why it is left alone. */
    public static final class Plan {
        public Level level;
        public int rate, channels, bps;
        public long expectedBytes;
        /** Non-null when the recording is left alone. */
        public String skip;

        public String describe() {
            return String.format(Locale.US, "%s %d kbps, %d ch, %d Hz", level.opus ? "Opus" : "AAC", bps / 1000, channels, rate);
        }
    }

    /** Below this share of the original the copy is worth making (a copy that saves under 20% is left alone). */
    public static final double WORTH = 0.80;

    /**
     * Plans {@code level} for a recording of {@code bytes} bytes and {@code durationUs} at {@code inRate} Hz with
     * {@code inChannels} channels. Never resamples up: an 8 kHz phone-line recording stays 8 kHz.
     */
    public static Plan plan(Level level, long bytes, long durationUs, int inRate, int inChannels) {
        Plan p = new Plan();
        p.level = level;
        p.channels = Math.max(1, Math.min(level.channels, inChannels));
        p.bps = level.bps;
        if (level.channels > 1 && p.channels == 1) p.bps = level.bps * 2 / 3;   // a mono source at the music level
        int want = Math.min(level.maxRate, inRate);
        if (level.opus) {
            p.rate = OPUS_RATES[OPUS_RATES.length - 1];
            for (int r : OPUS_RATES) if (r >= want) { p.rate = r; break; }
        } else {
            p.rate = Math.max(8_000, want);
        }
        double seconds = durationUs / 1e6;
        // container overhead: Ogg pages about 0.6%, MP4 tables about 1%, plus headers
        p.expectedBytes = (long) (seconds * p.bps / 8 * 1.01) + 4_096;
        if (durationUs <= 0) p.skip = "the recording's length is unknown";
        else if (bytes > 0 && p.expectedBytes > bytes * WORTH)
            p.skip = String.format(Locale.US, "already small: about %d kbps now, a copy at %d kbps would save under %.0f%%",
                    Math.round(bytes * 8 / seconds / 1000), p.bps / 1000, 100 * (1 - WORTH));
        return p;
    }

    // ------------------------------------------------------------------ resampling
    /**
     * A streaming windowed-sinc resampler on interleaved float frames. When it reduces the rate it filters out
     * everything above the new Nyquist frequency first, so no aliasing is folded into the speech band.
     */
    public static final class Resampler {
        static final int ZEROS = 16;          // sinc zero crossings on each side
        static final int PHASES = 256;        // kernel table resolution per input sample (linearly interpolated)
        final int inRate, outRate, ch;
        final double step, fc;
        final int half;                       // kernel half-width in input frames
        final float[] table;
        float[] buf;                          // interleaved, frames [0, frames)
        int frames;
        double pos;                           // position of the next output, in input frames of buf
        long inTotal, outTotal;
        boolean ended;

        public Resampler(int inRate, int outRate, int channels) {
            this.inRate = inRate; this.outRate = outRate; this.ch = channels;
            step = (double) inRate / outRate;
            fc = Math.min(1.0, (double) outRate / inRate) * 0.94;
            half = (int) Math.ceil(ZEROS / fc);
            table = new float[half * PHASES + 2];
            for (int i = 0; i < table.length; i++) {
                double x = (double) i / PHASES;                     // input frames from the centre
                double w = x >= half ? 0 : 0.42 + 0.5 * Math.cos(Math.PI * x / half) + 0.08 * Math.cos(2 * Math.PI * x / half);   // Blackman
                double s = x == 0 ? 1 : Math.sin(Math.PI * fc * x) / (Math.PI * fc * x);
                table[i] = (float) (fc * s * w);
            }
            buf = new float[(4 * half + 4096) * ch];
            frames = half;                                          // silence before the start, so output 0 is input 0
            pos = half;
        }

        public boolean passthrough() { return inRate == outRate; }

        /** Output frames that {@code inFrames} more input can give at most (size the output array with this). */
        public int maxOut(int inFrames) { return (int) Math.ceil((inFrames + 2 * half + 2) / step) + 2; }

        /** Adds {@code n} interleaved input frames and writes the output frames it can now give; returns how many. */
        public int process(float[] in, int off, int n, float[] out) {
            if (ended) throw new IllegalStateException("ended");
            append(in, off, n);
            inTotal += n;
            return drain(out, false);
        }

        /** Ends the input and writes the remaining output frames; returns how many. */
        public int end(float[] out) {
            ended = true;
            append(new float[(2 * half + 4) * ch], 0, 2 * half + 4);
            return drain(out, true);
        }

        private void append(float[] in, int off, int n) {
            if ((frames + n) * ch > buf.length) {
                int drop = Math.max(0, (int) Math.floor(pos) - half - 1);    // frames no output needs any more
                if (drop > 0) {
                    System.arraycopy(buf, drop * ch, buf, 0, (frames - drop) * ch);
                    frames -= drop; pos -= drop;
                }
                if ((frames + n) * ch > buf.length) {
                    float[] b = new float[Math.max(buf.length * 2, (frames + n) * ch)];
                    System.arraycopy(buf, 0, b, 0, frames * ch);
                    buf = b;
                }
            }
            System.arraycopy(in, off * ch, buf, frames * ch, n * ch);
            frames += n;
        }

        private int drain(float[] out, boolean last) {
            long limit = last ? Math.round(inTotal / step) : Long.MAX_VALUE;  // exactly as long as the input
            int o = 0;
            if (passthrough()) {
                int avail = (int) Math.min(frames - (long) pos, limit - outTotal);
                for (int i = 0; i < avail; i++, o++) System.arraycopy(buf, ((int) pos + i) * ch, out, o * ch, ch);
                pos += avail; outTotal += avail;
                return o;
            }
            while (pos + half + 1 < frames && outTotal < limit) {
                int c = (int) Math.floor(pos);
                for (int k = 0; k < ch; k++) {
                    double sum = 0;
                    for (int i = c - half + 1; i <= c + half; i++) {
                        double x = Math.abs(i - pos) * PHASES;
                        int xi = (int) x;
                        if (xi >= table.length - 1) continue;
                        float w = table[xi] + (float) (x - xi) * (table[xi + 1] - table[xi]);
                        sum += w * buf[i * ch + k];
                    }
                    out[o * ch + k] = (float) sum;
                }
                o++; outTotal++;
                pos += step;
            }
            return o;
        }
    }

    /** Mixes interleaved frames down to fewer channels (averaging), or copies them when the counts match. */
    public static void mix(float[] in, int inCh, float[] out, int outCh, int frames) {
        if (inCh == outCh) { System.arraycopy(in, 0, out, 0, frames * inCh); return; }
        for (int f = 0; f < frames; f++) {
            if (outCh == 1) {
                float s = 0;
                for (int c = 0; c < inCh; c++) s += in[f * inCh + c];
                out[f] = s / inCh;
            } else {
                for (int c = 0; c < outCh; c++) out[f * outCh + c] = in[f * inCh + Math.min(c, inCh - 1)];
            }
        }
    }

    /** Float samples to 16-bit PCM with clipping, written little-endian. */
    public static void toPcm16(float[] in, int n, byte[] out) {
        for (int i = 0; i < n; i++) {
            float v = in[i] * 32767f;
            int s = v >= 32767f ? 32767 : v <= -32768f ? -32768 : Math.round(v);
            out[2 * i] = (byte) s; out[2 * i + 1] = (byte) (s >> 8);
        }
    }

    // ------------------------------------------------------------------ the check
    /**
     * Loudness over time: the level in dB of each 20 ms of the (mono mix of the) sound. Two recordings of the same
     * speech have nearly the same envelope even when a low-bitrate codec changes the waveform itself, so comparing
     * envelopes checks that the copy holds the same words, pauses and volume, and not silence, noise or another file.
     */
    public static final class Envelope {
        public static final double FLOOR_DB = -70;
        final int frameLen;
        final List<Float> db = new ArrayList<Float>();
        double acc; int n; long samples;
        public final int rate;

        public Envelope(int rate) { this.rate = rate; frameLen = Math.max(1, rate / 50); }

        /** Adds interleaved frames (mixed to mono for the envelope). */
        public void add(float[] in, int frames, int ch) {
            for (int f = 0; f < frames; f++) {
                float s = 0;
                for (int c = 0; c < ch; c++) s += in[f * ch + c];
                s /= ch;
                acc += s * s;
                if (++n == frameLen) { push(); }
            }
            samples += frames;
        }

        private void push() {
            double ms = acc / n;
            db.add((float) Math.max(FLOOR_DB, 10 * Math.log10(ms + 1e-12)));
            acc = 0; n = 0;
        }

        public double seconds() { return (double) samples / rate; }

        public float[] values() {
            float[] v = new float[db.size()];
            for (int i = 0; i < v.length; i++) v[i] = db.get(i);
            return v;
        }
    }

    /** How alike two envelopes are: the best correlation over small time shifts (codec delay), and the loudness gap. */
    public static final class Match {
        public double correlation, loudnessGapDb;
        public int shiftMs;

        public String toString() {
            return String.format(Locale.US, "loudness envelope correlation %.3f (shift %d ms), loudness %+.1f dB", correlation, shiftMs, loudnessGapDb);
        }
    }

    public static Match match(float[] a, float[] b) {
        Match m = new Match();
        m.correlation = -2;
        int maxShift = 15;                                      // ±300 ms
        for (int s = -maxShift; s <= maxShift; s++) {
            double sa = 0, sb = 0, saa = 0, sbb = 0, sab = 0; int n = 0;
            for (int i = 0; i < a.length; i++) {
                int j = i + s;
                if (j < 0 || j >= b.length) continue;
                double x = a[i], y = b[j];
                sa += x; sb += y; saa += x * x; sbb += y * y; sab += x * y; n++;
            }
            if (n < 10) continue;
            double va = saa - sa * sa / n, vb = sbb - sb * sb / n;
            double r = va <= 1e-9 || vb <= 1e-9 ? (va <= 1e-9 && vb <= 1e-9 ? 1 : 0) : (sab - sa * sb / n) / Math.sqrt(va * vb);
            if (r > m.correlation) { m.correlation = r; m.shiftMs = s * 20; }
        }
        m.loudnessGapDb = loudness(b) - loudness(a);
        return m;
    }

    /**
     * The level of the speech, not of the pauses: the average power of the frames within 25 dB of the loud end
     * (the 95th percentile), so background noise a codec adds in the pauses does not count.
     */
    static double loudness(float[] e) {
        if (e.length == 0) return Envelope.FLOOR_DB;
        float[] s = e.clone();
        java.util.Arrays.sort(s);
        double top = s[(int) Math.min(s.length - 1, Math.floor(s.length * 0.95))];
        if (top <= Envelope.FLOOR_DB + 1) return Envelope.FLOOR_DB;
        double p = 0; int n = 0;
        for (float v : e) if (v >= top - 25) { p += Math.pow(10, v / 10); n++; }
        return 10 * Math.log10(p / n);
    }

    /** The check a copy must pass: the same length, the same envelope, the same loudness. Null when it passes. */
    public static String verdict(double inSeconds, double outSeconds, Match m) {
        double gap = Math.abs(outSeconds - inSeconds);
        if (gap > 0.15 + inSeconds * 0.005)
            return String.format(Locale.US, "the copy lasts %.2f s, the original %.2f s", outSeconds, inSeconds);
        if (m.correlation < MIN_CORRELATION)
            return String.format(Locale.US, "the copy does not sound like the original (envelope correlation %.3f, need %.2f)", m.correlation, MIN_CORRELATION);
        if (Math.abs(m.loudnessGapDb) > 2.0)
            return String.format(Locale.US, "the copy's loudness differs by %+.1f dB", m.loudnessGapDb);
        return null;
    }

    public static final double MIN_CORRELATION = 0.90;
}
