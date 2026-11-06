package com.storageplanner.tools;

import android.graphics.Bitmap;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.os.Build;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Compression tool for videos: makes a smaller copy of one video at a {@link VideoLevel}, never touching the original.
 * See README.md in this folder. In short:
 * <ul>
 * <li>Refuses first: HDR, slow motion and high frame rate, extra tracks, Samsung trailers, unreadable files
 *     ({@link VideoSearch#refuse}).</li>
 * <li>HEVC through the phone's hardware encoder (H.264 where there is no HEVC encoder). The bitrate is searched per
 *     video on short samples until the worst sampled frame just reaches the level's target, then the whole video is
 *     encoded and checked on other frames ({@link VideoSearch}).</li>
 * <li>Keeps the rotation, the location and the original dates (MediaMuxer would stamp the time of writing).</li>
 * <li>Keeps the copy only if it passes and saves at least 10%.</li>
 * </ul>
 */
public final class VideoCompressor {
    private VideoCompressor() {}

    public static final String HEVC = MediaFormat.MIMETYPE_VIDEO_HEVC, AVC = MediaFormat.MIMETYPE_VIDEO_AVC;

    public static final class Outcome {
        public boolean ok;
        public String output, skipped;
        /** Lab only: the copy was made anyway, but a real run would have refused the video for this reason. */
        public String wouldSkip;
        public String encoder, audio;
        public VideoLevel level;
        public VideoSearch.Facts facts;
        public VideoSearch.Plan plan;
        public VideoSearch.Outcome search;
        public long before, after, ms;
        public final List<String> trace = new ArrayList<String>();
        /** What a copy does not keep (Samsung's extra boxes, a small trailer). */
        public final List<String> lost = new ArrayList<String>();
    }

    /**
     * Makes the copy at {@code out}, written first to {@code out}'s work file and renamed only after it passed.
     * {@code work} is a private folder for the samples. Returns with ok == false (and nothing at {@code out})
     * when the video is refused or no copy passes.
     */
    public static Outcome compress(File src, VideoLevel level, File out, File work) {
        return make(src, level, out, work, false);
    }

    /** Lab: the same, but a refused video (unless nothing meaningful can be made) is compressed anyway, and noted. */
    public static Outcome compressForLab(File src, VideoLevel level, File out, File work) {
        return make(src, level, out, work, true);
    }

    private static Outcome make(File src, VideoLevel level, File out, File work, boolean lab) {
        long t0 = System.currentTimeMillis();
        Outcome o = new Outcome();
        o.level = level;
        o.before = src.length();
        File tmp = new File(CopyPaths.tempPath(out.getPath()));
        try {
            if (out.exists() || out.getCanonicalPath().equals(src.getCanonicalPath())) { o.skipped = "a file already exists at " + out; return o; }
            work.mkdirs();
            VideoSearch.Facts f = facts(src);
            o.facts = f;
            o.trace.add(describe(f));
            for (String u : f.mp4.udta) if (!u.equals("meta")) o.lost.add("'" + u + "' in the file's user data");
            if (f.mp4.trailerBytes > 0) o.lost.add(f.mp4.trailerBytes + " bytes after the video" + (f.mp4.samsungTrailer ? " (Samsung's trailer)" : ""));
            VideoSearch.Refusal r = VideoSearch.refuse(f);
            if (r != null) {
                if (!lab || r.hard) { o.skipped = r.why; return o; }
                o.wouldSkip = r.why;
                o.trace.add("a real run would not compress this video: " + r.why);
            }
            // the encoder must take the copy's size (levels 3-5 shrink it), as configured: not the source's, not turned
            VideoSearch.Plan size = VideoSearch.plan(f, level, false);
            String[] enc = null;
            boolean avc = false;
            for (int k = 0; k < 4 && enc == null; k++) {     // hardware HEVC, hardware H.264, software HEVC, software H.264
                avc = (k & 1) == 1;
                enc = encoder(avc ? AVC : HEVC, size.width, size.height, k < 2);
            }
            if (enc == null) { o.skipped = "this phone has no video encoder for the copy's size (" + size.width + "x" + size.height + ")"; return o; }
            o.encoder = enc[0] + (avc ? " (H.264)" : " (HEVC)");
            VideoSearch.Plan p = avc ? VideoSearch.plan(f, level, true) : size;
            int pw = p.width, ph = p.height;
            if (p.resize(Integer.parseInt(enc[2]), Integer.parseInt(enc[3])))     // the size the encoder accepts
                o.trace.add("size " + pw + "x" + ph + " trimmed to " + p.width + "x" + p.height + " for the encoder's alignment");
            clampToEncoder(p, enc[0], avc ? AVC : HEVC);
            o.plan = p;
            o.trace.add("encoder " + o.encoder + (enc[1] != null ? ", " + enc[1] : ""));
            Engine e = new Engine(src, f, tmp, work, enc[0], avc ? AVC : HEVC);
            VideoSearch.Outcome s = VideoSearch.run(f, p, e);
            o.search = s;
            o.trace.addAll(s.trace);
            if (e.decoderOutput != null) o.trace.add("decoder output: " + e.decoderOutput);
            o.audio = e.audio;
            if (s.copy == null) { o.skipped = s.skipped; return o; }
            // dates: MediaMuxer writes "now"; write the original's back, and its modified time
            if (f.mp4.created > 0) Mp4.writeTimes(new File(s.copy.path), f.mp4.created, f.mp4.modified > 0 ? f.mp4.modified : f.mp4.created);
            new File(s.copy.path).setLastModified(src.lastModified());
            o.after = new File(s.copy.path).length();
            if (!s.ok && !lab) { o.skipped = s.skipped; new File(s.copy.path).delete(); return o; }
            if (!s.ok) o.wouldSkip = o.wouldSkip != null ? o.wouldSkip : s.skipped;
            if (out.exists()) throw new IOException("a file already exists at " + out + " (a tool never replaces a file)");
            if (!new File(s.copy.path).renameTo(out)) throw new IOException("cannot rename the copy into place");
            out.setLastModified(src.lastModified());
            o.output = out.getPath();
            o.ok = s.ok;
            if (!s.ok) o.skipped = s.skipped;
            return o;
        } catch (Throwable t) {
            o.ok = false;
            o.skipped = "it could not be compressed (" + t + ")";
            return o;
        } finally {
            tmp.delete();
            o.ms = System.currentTimeMillis() - t0;
        }
    }

    // ------------------------------------------------------------------ facts
    static VideoSearch.Facts facts(File src) throws IOException {
        VideoSearch.Facts f = new VideoSearch.Facts();
        f.fileBytes = src.length();
        f.mp4 = Mp4.read(src);
        MediaExtractor x = new MediaExtractor();
        try {
            x.setDataSource(src.getPath());
            int vi = VideoTranscoder.track(x, "video/"), ai = VideoTranscoder.track(x, "audio/");
            if (vi < 0) return f;
            MediaFormat v = x.getTrackFormat(vi);
            f.videoMime = v.getString(MediaFormat.KEY_MIME);
            f.width = v.getInteger(MediaFormat.KEY_WIDTH);
            f.height = v.getInteger(MediaFormat.KEY_HEIGHT);
            if (v.containsKey(MediaFormat.KEY_ROTATION)) f.rotation = v.getInteger(MediaFormat.KEY_ROTATION);
            if (v.containsKey(MediaFormat.KEY_DURATION)) f.durationUs = v.getLong(MediaFormat.KEY_DURATION);
            f.hdr = hdr(v);
            ByteBuffer buf = ByteBuffer.allocateDirect(v.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE) ? Math.max(1 << 16, v.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)) : 8 << 20);
            x.selectTrack(vi);
            List<long[]> frames = new ArrayList<long[]>();
            long total = 0;
            for (;;) {
                long t = x.getSampleTime();
                if (t < 0) break;
                int n = Build.VERSION.SDK_INT >= 28 ? (int) x.getSampleSize() : x.readSampleData(buf, 0);
                if (n < 0) break;
                frames.add(new long[]{t, n});
                total += n;
                x.advance();
            }
            java.util.Collections.sort(frames, new java.util.Comparator<long[]>() {
                public int compare(long[] a, long[] b) { return Long.compare(a[0], b[0]); }
            });
            f.frameUs = new long[frames.size()];
            f.frameBytes = new int[frames.size()];
            for (int i = 0; i < frames.size(); i++) { f.frameUs[i] = frames.get(i)[0]; f.frameBytes[i] = (int) frames.get(i)[1]; }
            if (f.durationUs <= 0 && f.frameUs.length > 1) f.durationUs = f.frameUs[f.frameUs.length - 1] - f.frameUs[0];
            f.videoBitrate = f.durationUs > 0 ? total * 8_000_000L / f.durationUs : 0;
            if (ai >= 0) {
                MediaFormat a = x.getTrackFormat(ai);
                f.audioMime = a.getString(MediaFormat.KEY_MIME);
                if (a.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) f.channels = a.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                if (a.containsKey(MediaFormat.KEY_SAMPLE_RATE)) f.sampleRate = a.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                x.unselectTrack(vi);
                x.selectTrack(ai);
                x.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
                long bytes = 0;
                for (;;) {
                    if (x.getSampleTime() < 0) break;
                    int n = Build.VERSION.SDK_INT >= 28 ? (int) x.getSampleSize() : x.readSampleData(buf, 0);
                    if (n < 0) break;
                    bytes += n;
                    x.advance();
                }
                f.audioBitrate = f.durationUs > 0 ? bytes * 8_000_000L / f.durationUs : 0;
            }
        } finally {
            x.release();
        }
        return f;
    }

    /** Why a track is HDR, or null. */
    static String hdr(MediaFormat v) {
        String mime = v.getString(MediaFormat.KEY_MIME);
        if (MediaFormat.MIMETYPE_VIDEO_DOLBY_VISION.equals(mime)) return "Dolby Vision";
        if (v.containsKey(MediaFormat.KEY_COLOR_TRANSFER)) {
            int t = v.getInteger(MediaFormat.KEY_COLOR_TRANSFER);
            if (t == MediaFormat.COLOR_TRANSFER_ST2084) return "PQ / HDR10";
            if (t == MediaFormat.COLOR_TRANSFER_HLG) return "HLG";
        }
        if (v.containsKey(MediaFormat.KEY_PROFILE) && HEVC.equals(mime)) {
            int p = v.getInteger(MediaFormat.KEY_PROFILE);
            if (p == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10 || p == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus) return "HEVC Main10 HDR10";
            if (p == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10) return "10-bit HEVC";
        }
        if (v.containsKey(MediaFormat.KEY_HDR_STATIC_INFO)) return "HDR metadata";
        return null;
    }

    static String describe(VideoSearch.Facts f) {
        return String.format(Locale.US, "source: %s, %d KB, %.1f s, %dx%d (rotation %d), %.2f fps, %s %d kbps, audio %s %d kbps %d ch; boxes %s, tracks %s, user data %s",
                f.mp4.problem == null ? "MP4" : "unreadable MP4", f.fileBytes >> 10, f.durationUs / 1e6, f.width, f.height, f.rotation, f.fps(), f.videoMime,
                f.videoBitrate / 1000, f.audioMime, f.audioBitrate / 1000, f.channels, f.mp4.top, handlers(f.mp4), f.mp4.udta)
                + (f.mp4.captureFps ? String.format(Locale.US, "; capture speed %.2f fps", f.mp4.captureFpsValue) : "");
    }

    private static String handlers(Mp4.Info m) {
        List<String> h = new ArrayList<String>();
        for (Mp4.Track t : m.tracks) h.add(t.handler);
        return h.toString();
    }

    // ------------------------------------------------------------------ encoder
    /**
     * {name, note}: an encoder for this type that accepts exactly w x h (the size it will be configured with), a hardware
     * one when {@code hardware}, else a software one; null when there is none. The caller tries hardware HEVC, hardware
     * H.264, then software, so a slow software encoder is only a last resort.
     */
    static String[] encoder(String mime, int w, int h, boolean hardware) {
        for (MediaCodecInfo c : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            if (!c.isEncoder() || hardware(c) != hardware || !Arrays.asList(c.getSupportedTypes()).contains(mime)) continue;
            MediaCodecInfo.VideoCapabilities v = c.getCapabilitiesForType(mime).getVideoCapabilities();
            if (v == null) continue;
            int cw = w, ch = h;
            if (!v.isSizeSupported(cw, ch)) {                 // e.g. 852x480 on an encoder that wants multiples of 16
                cw -= cw % v.getWidthAlignment(); ch -= ch % v.getHeightAlignment();
                if (!v.isSizeSupported(cw, ch)) continue;
            }
            return new String[]{c.getName(), (hardware ? "" : "software encoder, ") + modes(c, mime), String.valueOf(cw), String.valueOf(ch)};
        }
        return null;
    }

    static boolean hardware(MediaCodecInfo c) {
        if (Build.VERSION.SDK_INT >= 29) return c.isHardwareAccelerated();
        String n = c.getName().toLowerCase(Locale.US);
        return !n.startsWith("omx.google.") && !n.startsWith("c2.android.");
    }

    private static String modes(MediaCodecInfo c, String mime) {
        MediaCodecInfo.EncoderCapabilities e = c.getCapabilitiesForType(mime).getEncoderCapabilities();
        StringBuilder s = new StringBuilder("rate modes:");
        if (e.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)) s.append(" VBR");
        if (e.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)) s.append(" CBR");
        if (e.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ)) s.append(" CQ");
        return s.toString();
    }

    /** Keeps the bitrate search inside what the encoder accepts (the size was chosen with the encoder). */
    static void clampToEncoder(VideoSearch.Plan p, String name, String mime) {
        try {
            MediaCodecInfo.VideoCapabilities v = null;
            for (MediaCodecInfo c : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos())
                if (c.getName().equals(name)) v = c.getCapabilitiesForType(mime).getVideoCapabilities();
            if (v == null) return;
            int lo = v.getBitrateRange().getLower(), hi = v.getBitrateRange().getUpper();
            p.loBps = Math.max(p.loBps, lo); p.hiBps = Math.min(p.hiBps, hi); p.startBps = Math.max(p.loBps, Math.min(p.hiBps, p.startBps));
        } catch (Throwable ignored) {}
    }

    // ------------------------------------------------------------------ the phone's engine
    static final class Engine implements VideoSearch.Engine {
        final File src, out, work;
        final VideoSearch.Facts f;
        final String encoder, mime;
        final float[] location;
        String audio, decoderOutput;
        int n;
        final java.util.Map<String, Long> base = new java.util.HashMap<String, Long>();

        Engine(File src, VideoSearch.Facts f, File out, File work, String encoder, String mime) {
            this.src = src; this.f = f; this.out = out; this.work = work; this.encoder = encoder; this.mime = mime;
            this.location = location(src);
        }

        public VideoSearch.Copy encode(VideoSearch.Plan p, long fromUs, long toUs, int bitrate) throws Exception {
            VideoTranscoder.Job j = new VideoTranscoder.Job();
            j.src = src; j.plan = p; j.fromUs = fromUs; j.toUs = toUs; j.encoder = encoder; j.mime = mime;
            j.width = p.width; j.height = p.height; j.bitrate = bitrate; j.rotation = f.rotation; j.location = location;
            j.out = j.whole() ? out : new File(work, "sample-" + (n++) + ".mp4");
            VideoTranscoder.Result r;
            try {
                r = VideoTranscoder.run(j);
            } catch (Exception ex) {
                if (!j.whole()) j.out.delete();   // a half-written sample (the whole-video work file is removed by make())
                throw ex;
            }
            if (j.whole()) audio = r.audio;
            if (decoderOutput == null) decoderOutput = r.decoderOutput;
            VideoSearch.Copy c = new VideoSearch.Copy();
            c.path = j.out.getPath(); c.bytes = r.bytes; c.ms = r.ms;
            base.put(c.path, r.baseUs);
            return c;
        }

        public FrameScore score(VideoSearch.Plan p, VideoSearch.Copy c, long fromUs, long[] sourceUs) throws Exception {
            long b = base.containsKey(c.path) ? base.get(c.path) : 0;
            long[] kept = p.keptUs != null ? p.keptUs : f.frameUs;
            MediaMetadataRetriever a = new MediaMetadataRetriever(), m = new MediaMetadataRetriever();
            try {
                a.setDataSource(src.getPath());
                m.setDataSource(c.path);
                int shift = 0;
                FrameScore first = scoreAll(a, m, p, kept, b, sourceUs, 0);
                // the worst frame is where a timeline off by a frame shows most (motion): try its neighbours
                String timing = null;
                if (first.worstAtUs >= 0) {
                    double[] ps = neighbours(a, m, p, kept, b, first.worstAtUs);
                    if (ps != null) {
                        StringBuilder t = new StringBuilder("worst frame vs the copy's frames -2..+2:");
                        int bestD = 0;
                        for (int d = -2; d <= 2; d++) {
                            t.append(Double.isNaN(ps[d + 2]) ? " -" : String.format(Locale.US, " %.1f", ps[d + 2]));
                            if (!Double.isNaN(ps[d + 2]) && ps[d + 2] > ps[2] + 1.0 && ps[d + 2] > ps[bestD + 2]) bestD = d;
                        }
                        timing = t.append(" dB").toString();
                        shift = bestD;
                    }
                }
                if (shift == 0) { first.note = join(timing, first.note); return first; }
                FrameScore again = scoreAll(a, m, p, kept, b, sourceUs, shift);
                again.note = join("copy's timeline is " + shift + " frame(s) off the original's, corrected; " + timing, again.note);
                return again;
            } finally {
                a.release();
                m.release();
            }
        }

        private static String join(String x, String y) { return x == null ? y : y == null ? x : x + "; " + y; }

        /** PSNR of the source frame at us against the copy's frames -2..+2 around it (NaN where there is none). */
        private static double[] neighbours(MediaMetadataRetriever a, MediaMetadataRetriever m, VideoSearch.Plan p, long[] kept, long b, long us) {
            int i = Arrays.binarySearch(kept, us);
            if (i < 0) return null;
            Bitmap x = a.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST);
            if (x == null) return null;
            int[] wh = size(x, p);
            int[] px = pixels(x, wh[0], wh[1]);
            x.recycle();
            double[] r = new double[5];
            for (int d = -2; d <= 2; d++) {
                r[d + 2] = Double.NaN;
                if (i + d < 0 || i + d >= kept.length || kept[i + d] - b < 0) continue;
                Bitmap y = m.getFrameAtTime(kept[i + d] - b, MediaMetadataRetriever.OPTION_CLOSEST);
                if (y == null) continue;
                int[] py = pixels(y, wh[0], wh[1]);
                y.recycle();
                r[d + 2] = FrameScore.of(px, py, wh[0], wh[1]).psnr;
            }
            return r;
        }

        private FrameScore scoreAll(MediaMetadataRetriever a, MediaMetadataRetriever m, VideoSearch.Plan p, long[] kept, long b, long[] sourceUs, int shift) throws IOException {
            FrameScore all = new FrameScore();
            int[] worstA = null, worstB = null;
            int ww = 0, wh = 0;
            {
                for (long us : sourceUs) {
                    Bitmap x = a.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST);
                    Bitmap y = m.getFrameAtTime(copyTime(kept, us, shift, b), MediaMetadataRetriever.OPTION_CLOSEST);
                    if (x == null || y == null) throw new IOException("no frame at " + us / 1000 + " ms");
                    int[] wh2 = size(x, p);
                    int w = wh2[0], h = wh2[1];
                    int[] px = pixels(x, w, h), py = pixels(y, w, h);
                    x.recycle(); y.recycle();   // frames can be 33 MB each at 4K; free them now
                    FrameScore one = FrameScore.of(px, py, w, h);
                    if (worstA == null || one.ssim < all.ssim) { worstA = px; worstB = py; ww = w; wh = h; }
                    all.worst(one, us);
                }
                all.note = worstA != null ? FrameScore.alignment(worstA, worstB, ww, wh) : null;
            }
            return all;
        }

        /** The copy's time for the source frame shown at us, moved by `shift` frames. */
        private static long copyTime(long[] kept, long us, int shift, long b) {
            int i = java.util.Arrays.binarySearch(kept, us);
            if (i < 0 || shift == 0 || i + shift < 0 || i + shift >= kept.length) return us - b;
            return kept[i + shift] - b;
        }

        /** The retriever may turn frames upright: compare both at the same orientation. */
        private static int[] size(Bitmap x, VideoSearch.Plan p) {
            boolean wide = x.getWidth() >= x.getHeight();
            int w = wide == (p.compareWidth >= p.compareHeight) ? p.compareWidth : p.compareHeight;
            int h = w == p.compareWidth ? p.compareHeight : p.compareWidth;
            return new int[]{w, h};
        }

        public void discard(VideoSearch.Copy c) { new File(c.path).delete(); base.remove(c.path); }

        private static int[] pixels(Bitmap b, int w, int h) {
            Bitmap s = b.getWidth() == w && b.getHeight() == h && b.getConfig() == Bitmap.Config.ARGB_8888 ? b
                    : Bitmap.createScaledBitmap(b.getConfig() == Bitmap.Config.ARGB_8888 ? b : b.copy(Bitmap.Config.ARGB_8888, false), w, h, true);
            int[] px = new int[w * h];
            s.getPixels(px, 0, w, 0, 0, w, h);
            if (s != b) s.recycle();
            return px;
        }
    }

    private static final Pattern ISO6709 = Pattern.compile("([+-]\\d+(?:\\.\\d+)?)([+-]\\d+(?:\\.\\d+)?)");

    /** {lat, lon} from the video's location tag, or null. */
    static float[] location(File src) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(src.getPath());
            String s = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION);
            if (s == null) return null;
            Matcher m = ISO6709.matcher(s);
            return m.find() ? new float[]{Float.parseFloat(m.group(1)), Float.parseFloat(m.group(2))} : null;
        } catch (Throwable t) {
            return null;
        } finally {
            try { r.release(); } catch (Throwable ignored) {}
        }
    }
}
