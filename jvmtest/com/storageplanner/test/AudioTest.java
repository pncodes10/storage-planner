package com.storageplanner.test;

import com.storageplanner.tools.AudioMath;
import com.storageplanner.tools.AudioMath.Level;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.Locale;
import java.util.Random;

/**
 * Desktop tests for tools/AudioMath: the levels' plans, the resampler (length, level, frequency, filtering,
 * streaming in any chunk sizes), and the envelope check that a compressed copy must pass. With a WAV file as the
 * argument it also reports the check on that file against itself, delayed, and against silence.
 */
public final class AudioTest {
    static int failed = 0, passed = 0;

    static void check(String what, boolean ok) {
        if (ok) passed++; else { failed++; System.out.println("FAIL " + what); }
    }

    public static void main(String[] a) throws Exception {
        plans();
        resampling();
        streaming();
        mixing();
        envelopes();
        if (a.length > 0) wav(new File(a[0]));
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    // ------------------------------------------------------------------ plans
    static void plans() {
        // a typical call recording: AAC 128 kbps, 48 kHz stereo, 5 minutes
        long us = 300_000_000L, bytes = 128_000L / 8 * 300;
        AudioMath.Plan p = AudioMath.plan(Level.VOICE, bytes, us, 48_000, 2);
        check("voice: mono", p.channels == 1);
        check("voice: 16 kHz", p.rate == 16_000);
        check("voice: 16 kbps", p.bps == 16_000);
        check("voice: made", p.skip == null);
        check("voice: about 1/8 of the size", p.expectedBytes > bytes / 9 && p.expectedBytes < bytes / 7);

        AudioMath.Plan n = AudioMath.plan(Level.VOICE, 8_000 * 2 * 60, 60_000_000L, 8_000, 1);     // 8 kHz WAV
        check("phone-line WAV stays 8 kHz", n.rate == 8_000);
        AudioMath.Plan s = AudioMath.plan(Level.VOICE_SMALL, 1_000_000, 60_000_000L, 11_025, 1);
        check("11.025 kHz goes to the next Opus rate (12 kHz)", s.rate == 12_000);
        AudioMath.Plan c = AudioMath.plan(Level.VOICE_CLEAR, bytes, us, 44_100, 2);
        check("voice clear: 24 kHz", c.rate == 24_000 && c.channels == 1 && c.bps == 24_000);

        AudioMath.Plan amr = AudioMath.plan(Level.VOICE, 12_200L / 8 * 60, 60_000_000L, 8_000, 1);   // AMR-NB 12.2 kbps
        check("AMR at 12 kbps is already small", amr.skip != null && amr.skip.startsWith("already small"));
        AudioMath.Plan opus = AudioMath.plan(Level.VOICE, 20_000L / 8 * 60, 60_000_000L, 48_000, 1);  // a voice note
        check("a 20 kbps voice note is already small", opus.skip != null);

        AudioMath.Plan m = AudioMath.plan(Level.MUSIC, 320_000L / 8 * 200, 200_000_000L, 44_100, 2);
        check("music: stereo kept, 48 kHz, 96 kbps", m.channels == 2 && m.rate == 48_000 && m.bps == 96_000 && m.skip == null);
        AudioMath.Plan mm = AudioMath.plan(Level.MUSIC, 320_000L / 8 * 200, 200_000_000L, 44_100, 1);
        check("music on a mono source: mono, 64 kbps", mm.channels == 1 && mm.bps == 64_000);

        AudioMath.Plan aac = AudioMath.plan(Level.COMPATIBLE, bytes, us, 48_000, 2);
        check("compatible: AAC 32 kbps mono 16 kHz, .m4a", !aac.level.opus && aac.rate == 16_000 && aac.channels == 1 && aac.bps == 32_000
                && Level.COMPATIBLE.extension().equals(".m4a") && Level.VOICE.extension().equals(".ogg"));
        AudioMath.Plan aac8 = AudioMath.plan(Level.COMPATIBLE, bytes, us, 8_000, 1);
        check("compatible never resamples up", aac8.rate == 8_000);
        check("unknown length is left alone", AudioMath.plan(Level.VOICE, bytes, -1, 48_000, 2).skip != null);
        check("unknown size is planned", AudioMath.plan(Level.VOICE, -1, us, 48_000, 2).skip == null);
    }

    // ------------------------------------------------------------------ resampling
    static float[] sine(int rate, int ch, double hz, double seconds, double amp) {
        int n = (int) Math.round(rate * seconds);
        float[] f = new float[n * ch];
        for (int i = 0; i < n; i++) for (int c = 0; c < ch; c++) f[i * ch + c] = (float) (amp * Math.sin(2 * Math.PI * hz * i / rate + c));
        return f;
    }

    static float[] run(AudioMath.Resampler r, float[] in, int ch, int[] chunks, long seed) {
        int frames = in.length / ch;
        float[] out = new float[r.maxOut(frames) * ch + r.maxOut(1000) * ch];
        float[] tmp = new float[r.maxOut(Math.max(frames, 1000)) * ch];
        int o = 0, off = 0;
        Random rnd = new Random(seed);
        while (off < frames) {
            int n = Math.min(frames - off, chunks == null ? frames : chunks[rnd.nextInt(chunks.length)]);
            int got = r.process(in, off, n, tmp);
            System.arraycopy(tmp, 0, out, o * ch, got * ch);
            o += got; off += n;
        }
        int got = r.end(tmp);
        System.arraycopy(tmp, 0, out, o * ch, got * ch);
        o += got;
        float[] res = new float[o * ch];
        System.arraycopy(out, 0, res, 0, o * ch);
        return res;
    }

    /** RMS of one channel, skipping {@code edge} frames at both ends. */
    static double rms(float[] f, int ch, int c, int edge) {
        double s = 0; int n = 0;
        for (int i = edge; i < f.length / ch - edge; i++) { s += f[i * ch + c] * f[i * ch + c]; n++; }
        return Math.sqrt(s / Math.max(1, n));
    }

    /** The frequency of a sine from its zero crossings. */
    static double freq(float[] f, int ch, int rate, int edge) {
        int cross = 0, first = -1, last = -1;
        for (int i = edge + 1; i < f.length / ch - edge; i++)
            if (f[(i - 1) * ch] < 0 && f[i * ch] >= 0) { cross++; if (first < 0) first = i; last = i; }
        return cross < 2 ? 0 : (cross - 1) * (double) rate / (last - first);
    }

    static void resampling() {
        int[][] pairs = {{48_000, 16_000}, {44_100, 16_000}, {44_100, 48_000}, {16_000, 48_000}, {8_000, 8_000}, {48_000, 24_000}, {22_050, 12_000}};
        for (int[] p : pairs) {
            AudioMath.Resampler r = new AudioMath.Resampler(p[0], p[1], 1);
            float[] in = sine(p[0], 1, 1000, 2.0, 0.5);
            float[] out = run(r, in, 1, null, 1);
            String w = p[0] + "→" + p[1];
            long want = Math.round(in.length * (double) p[1] / p[0]);
            check(w + ": length " + out.length + " = " + want, out.length == want);
            double level = rms(out, 1, 0, p[1] / 20) / (0.5 / Math.sqrt(2));
            check(w + String.format(Locale.US, ": 1 kHz level kept (%.4f)", level), Math.abs(level - 1) < 0.01);
            double hz = freq(out, 1, p[1], p[1] / 20);
            check(w + String.format(Locale.US, ": still 1 kHz (%.2f)", hz), Math.abs(hz - 1000) < 1);
            check(w + ": starts in time (no shift)", Math.abs(out[p[1] / 4] - (float) (0.5 * Math.sin(2 * Math.PI * 1000 * (p[1] / 4) / (double) p[1]))) < 0.02);
        }
        // 8 kHz audio cannot exist at 16 kHz: it must be removed, not folded down to 8 kHz as aliasing
        AudioMath.Resampler r = new AudioMath.Resampler(48_000, 16_000, 1);
        float[] out = run(r, sine(48_000, 1, 10_000, 1.0, 0.5), 1, null, 2);
        check(String.format(Locale.US, "10 kHz is filtered out at 16 kHz (rms %.5f)", rms(out, 1, 0, 800)), rms(out, 1, 0, 800) < 0.002);
        for (int hz : new int[]{3_400, 6_000, 6_800, 7_200}) {
            float[] out2 = run(new AudioMath.Resampler(44_100, 16_000, 1), sine(44_100, 1, hz, 1.0, 0.5), 1, null, 3);
            double kept = rms(out2, 1, 0, 800) / (0.5 / Math.sqrt(2));
            check(String.format(Locale.US, "%d Hz is kept at 16 kHz (%.3f)", hz, kept), Math.abs(kept - 1) < (hz <= 6_000 ? 0.01 : 0.3));
        }
        // passthrough is exact
        float[] in = sine(8_000, 1, 440, 0.5, 0.3);
        float[] same = run(new AudioMath.Resampler(8_000, 8_000, 1), in, 1, new int[]{1, 7, 160, 999}, 4);
        boolean exact = same.length == in.length;
        for (int i = 0; exact && i < in.length; i++) exact = same[i] == in[i];
        check("same rate: an exact copy", exact);
    }

    static void streaming() {
        float[] in = sine(44_100, 2, 700, 1.5, 0.4);
        float[] whole = run(new AudioMath.Resampler(44_100, 16_000, 2), in, 2, null, 5);
        float[] bits = run(new AudioMath.Resampler(44_100, 16_000, 2), in, 2, new int[]{1, 3, 64, 441, 1024, 8192}, 6);
        boolean same = whole.length == bits.length;
        for (int i = 0; same && i < whole.length; i++) same = Math.abs(whole[i] - bits[i]) < 1e-6;
        check("any chunk sizes give the same output", same);
        check("stereo: both channels kept", Math.abs(rms(whole, 2, 1, 800) - rms(whole, 2, 0, 800)) < 0.005 && rms(whole, 2, 1, 800) > 0.25);
        float[] tiny = run(new AudioMath.Resampler(48_000, 16_000, 1), new float[3], 1, null, 7);
        check("3 frames → 1 frame", tiny.length == 1);
        float[] none = run(new AudioMath.Resampler(48_000, 16_000, 1), new float[0], 1, null, 8);
        check("nothing → nothing", none.length == 0);
    }

    static void mixing() {
        float[] st = {1f, 0f, 0.5f, -0.5f};
        float[] mono = new float[2];
        AudioMath.mix(st, 2, mono, 1, 2);
        check("stereo → mono averages", mono[0] == 0.5f && mono[1] == 0f);
        float[] up = new float[4];
        AudioMath.mix(new float[]{0.25f, -1f}, 1, up, 2, 2);
        check("mono → stereo copies", up[0] == 0.25f && up[1] == 0.25f && up[2] == -1f && up[3] == -1f);
        byte[] pcm = new byte[8];
        AudioMath.toPcm16(new float[]{1.5f, -2f, 0.5f, 0f}, 4, pcm);
        check("PCM clips at full scale", (short) ((pcm[1] << 8) | (pcm[0] & 255)) == 32767 && (short) ((pcm[3] << 8) | (pcm[2] & 255)) == -32768);
        check("PCM half scale", (short) ((pcm[5] << 8) | (pcm[4] & 255)) == 16384);
    }

    // ------------------------------------------------------------------ the check
    /** Speech-like: bursts of shaped noise (words) with pauses between them, at 16 kHz. */
    static float[] speech(long seed, double seconds, double amp) {
        Random r = new Random(seed);
        int rate = 16_000, n = (int) (rate * seconds);
        float[] f = new float[n];
        int i = 0;
        while (i < n) {
            int word = rate / 10 + r.nextInt(rate / 3), pause = rate / 20 + r.nextInt(rate / 4);
            double pitch = 100 + r.nextInt(150);
            for (int k = 0; k < word && i < n; k++, i++) {
                double env = Math.sin(Math.PI * k / word);
                f[i] = (float) (amp * env * (0.6 * Math.sin(2 * Math.PI * pitch * i / rate) + 0.3 * (r.nextDouble() - 0.5)));
            }
            for (int k = 0; k < pause && i < n; k++, i++) f[i] = (float) (0.001 * (r.nextDouble() - 0.5));
        }
        return f;
    }

    static float[] env(float[] f, int rate) {
        AudioMath.Envelope e = new AudioMath.Envelope(rate);
        e.add(f, f.length, 1);
        return e.values();
    }

    static void envelopes() {
        float[] a = speech(1, 20, 0.3);
        float[] e = env(a, 16_000);
        check("20 ms frames", e.length == 1000);
        // a lossy copy: small noise added, 40 ms later, at 48 kHz
        Random r = new Random(9);
        float[] copy = new float[a.length * 3 + 1920];
        for (int i = 0; i < a.length * 3; i++) copy[i + 1920] = a[i / 3] + (float) (0.01 * (r.nextDouble() - 0.5));
        AudioMath.Match m = AudioMath.match(e, env(copy, 48_000));
        check("a noisy, delayed copy matches: " + m, m.correlation > 0.97 && m.shiftMs == 40 && Math.abs(m.loudnessGapDb) < 0.5);
        check("... and passes", AudioMath.verdict(20, 20.04, m) == null);
        AudioMath.Match other = AudioMath.match(e, env(speech(2, 20, 0.3), 16_000));
        check("other speech does not match: " + other, other.correlation < AudioMath.MIN_CORRELATION && AudioMath.verdict(20, 20, other) != null);
        AudioMath.Match silence = AudioMath.match(e, env(new float[a.length], 16_000));
        check("silence does not pass", AudioMath.verdict(20, 20, silence) != null);
        float[] quiet = new float[a.length];
        for (int i = 0; i < a.length; i++) quiet[i] = a[i] * 0.5f;
        AudioMath.Match q = AudioMath.match(e, env(quiet, 16_000));
        check("6 dB quieter: same shape, but fails on loudness (" + q + ")", q.correlation > 0.99 && Math.abs(q.loudnessGapDb + 6.02) < 0.1
                && AudioMath.verdict(20, 20, q).contains("loudness"));
        check("a cut copy fails on length", AudioMath.verdict(20, 18.5, m).contains("lasts"));
        check("within 0.15 s passes", AudioMath.verdict(20, 20.14, m) == null);
    }

    // ------------------------------------------------------------------ a real recording (optional)
    static void wav(File f) throws Exception {
        RandomAccessFile r = new RandomAccessFile(f, "r");
        byte[] b = new byte[(int) r.length()];
        r.readFully(b);
        r.close();
        int ch = b[22] & 255, rate = (b[24] & 255) | (b[25] & 255) << 8 | (b[26] & 255) << 16;
        int p = 12;
        while (!(b[p] == 'd' && b[p + 1] == 'a' && b[p + 2] == 't' && b[p + 3] == 'a')) p += 8 + ((b[p + 4] & 255) | (b[p + 5] & 255) << 8 | (b[p + 6] & 255) << 16);
        int n = ((b.length - p - 8) / 2 / ch);
        float[] s = new float[n * ch];
        for (int i = 0; i < n * ch; i++) s[i] = (short) ((b[p + 8 + 2 * i] & 255) | b[p + 9 + 2 * i] << 8) / 32768f;
        AudioMath.Envelope e = new AudioMath.Envelope(rate);
        e.add(s, n, ch);
        long t0 = System.nanoTime();
        float[] res = run(new AudioMath.Resampler(rate, 8_000, ch), s, ch, null, 1);
        System.out.printf(Locale.US, "%s: %d Hz, %d ch, %.1f s; resampled to 8 kHz in %.0f ms%n", f.getName(), rate, ch, e.seconds(), (System.nanoTime() - t0) / 1e6);
        AudioMath.Envelope e8 = new AudioMath.Envelope(8_000);
        e8.add(res, res.length / ch, ch);
        AudioMath.Match m = AudioMath.match(e.values(), e8.values());
        System.out.println("  vs itself at 8 kHz: " + m + " → " + (AudioMath.verdict(e.seconds(), e8.seconds(), m) == null ? "passes" : "fails"));
        check(f.getName() + " at 8 kHz passes", AudioMath.verdict(e.seconds(), e8.seconds(), m) == null);
        AudioMath.Match z = AudioMath.match(e.values(), new AudioMath.Envelope(rate).values());
        check(f.getName() + " vs nothing fails", AudioMath.verdict(e.seconds(), 0, z) != null);
    }
}
