package com.storageplanner.test;

import com.storageplanner.tools.FrameScore;
import com.storageplanner.tools.Mp4;
import com.storageplanner.tools.VideoLevel;
import com.storageplanner.tools.VideoSearch;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Runs the video tool's decisions and bitrate search ({@link VideoSearch}) on real files on a desktop, with ffmpeg
 * standing in for the phone: libx265 in a "hardware-like" setting (fastest preset, one-pass VBR, no look-ahead, no
 * B-frames), frames compared with the same {@link FrameScore}. Needs ffmpeg and ffprobe on the PATH. Not part of CI.
 *
 * Usage: java com.storageplanner.test.VideoDesktop OUT_DIR video.mp4 [more.mp4 ...] [--levels 1,3,5]
 */
public final class VideoDesktop {
    public static void main(String[] args) throws Exception {
        File out = new File(args[0]);
        out.mkdirs();
        List<VideoLevel> levels = new ArrayList<VideoLevel>(Arrays.asList(VideoLevel.values()));
        List<String> files = new ArrayList<String>();
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("--levels")) {
                levels.clear();
                for (String n : args[++i].split(",")) levels.add(VideoLevel.values()[Integer.parseInt(n.trim()) - 1]);
            } else files.add(args[i]);
        }
        for (String path : files) {
            File src = new File(path);
            VideoSearch.Facts f = facts(src);
            System.out.printf(Locale.US, "%n=== %s: %d KB, %.1f s, %dx%d %.2f fps %s %d kbps, audio %s %d kbps%n", src.getName(), f.fileBytes >> 10,
                    f.durationUs / 1e6, f.width, f.height, f.fps(), f.videoMime, f.videoBitrate / 1000, f.audioMime, f.audioBitrate / 1000);
            System.out.println("  boxes " + f.mp4.top + ", tracks " + handlers(f.mp4) + ", udta " + f.mp4.udta + ", trailer " + f.mp4.trailerBytes + " B");
            VideoSearch.Refusal r = VideoSearch.refuse(f);
            if (r != null) { System.out.println("  refused: " + r.why); if (r.hard) continue; }
            for (VideoLevel l : levels) {
                VideoSearch.Plan p = VideoSearch.plan(f, l, false);
                Ffmpeg e = new Ffmpeg(src, f, new File(out, stem(src) + "-L" + l.number()));
                VideoSearch.Outcome o = VideoSearch.run(f, p, e);
                System.out.printf(Locale.US, "  L%d %-15s ", l.number(), l.label);
                if (o.copy != null) {
                    System.out.printf(Locale.US, "%s %6d KB -> %6d KB (%.0f%% saved) at %d kbps, %s, %.0f s%n", o.ok ? "KEPT   " : "dropped",
                            o.before >> 10, o.after >> 10, 100.0 * (1 - (double) o.after / o.before), o.bitrate / 1000, o.score, o.ms / 1000.0);
                    if (o.ok) { File keep = new File(out, stem(src) + "-L" + l.number() + ".mp4"); new File(o.copy.path).renameTo(keep); }
                    else e.discard(o.copy);
                } else System.out.printf(Locale.US, "not compressed: %s (%.0f s)%n", o.skipped, o.ms / 1000.0);
                if (o.ok) {
                    File keep = new File(out, stem(src) + "-L" + l.number() + ".mp4");
                    if (f.mp4.created > 0) Mp4.writeTimes(keep, f.mp4.created, f.mp4.modified);
                }
                for (String t : o.trace) System.out.println("      " + t);
            }
        }
    }

    static String stem(File f) { String n = f.getName(); int d = n.lastIndexOf('.'); return d > 0 ? n.substring(0, d) : n; }

    static String handlers(Mp4.Info m) { List<String> h = new ArrayList<String>(); for (Mp4.Track t : m.tracks) h.add(t.handler); return h.toString(); }

    static VideoSearch.Facts facts(File src) throws Exception {
        VideoSearch.Facts f = new VideoSearch.Facts();
        f.fileBytes = src.length();
        f.mp4 = Mp4.read(src);
        String v = run("ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries",
                "stream=codec_name,width,height,color_transfer:stream_side_data=rotation:format=duration", "-of", "default=nw=1", src.getPath());
        for (String line : v.split("\n")) {
            String[] kv = line.split("=", 2);
            if (kv.length < 2) continue;
            if (kv[0].equals("codec_name")) f.videoMime = kv[1];
            else if (kv[0].equals("width")) f.width = Integer.parseInt(kv[1]);
            else if (kv[0].equals("height")) f.height = Integer.parseInt(kv[1]);
            else if (kv[0].equals("rotation")) f.rotation = Integer.parseInt(kv[1]);
            else if (kv[0].equals("duration")) f.durationUs = (long) (Double.parseDouble(kv[1]) * 1e6);
            else if (kv[0].equals("color_transfer") && (kv[1].equals("smpte2084") || kv[1].equals("arib-std-b67"))) f.hdr = kv[1];
        }
        String pk = run("ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries", "packet=pts_time,size", "-of", "csv=p=0", src.getPath());
        List<long[]> frames = new ArrayList<long[]>();
        long total = 0;
        for (String line : pk.split("\n")) {
            String[] c = line.split(",");
            if (c.length < 2 || c[0].equals("N/A")) continue;
            long us = Math.round(Double.parseDouble(c[0]) * 1e6);
            int size = Integer.parseInt(c[1].trim());
            frames.add(new long[]{us, size});
            total += size;
        }
        java.util.Collections.sort(frames, (x, y) -> Long.compare(x[0], y[0]));
        f.frameUs = new long[frames.size()];
        f.frameBytes = new int[frames.size()];
        long t0 = frames.isEmpty() ? 0 : frames.get(0)[0];
        for (int i = 0; i < frames.size(); i++) { f.frameUs[i] = frames.get(i)[0] - t0; f.frameBytes[i] = (int) frames.get(i)[1]; }
        f.videoBitrate = f.durationUs > 0 ? total * 8_000_000L / f.durationUs : 0;
        String a = run("ffprobe", "-v", "error", "-select_streams", "a:0", "-show_entries", "stream=codec_name,bit_rate,channels,sample_rate", "-of", "default=nw=1", src.getPath());
        for (String line : a.split("\n")) {
            String[] kv = line.split("=", 2);
            if (kv.length < 2 || kv[1].equals("N/A")) continue;
            if (kv[0].equals("codec_name")) f.audioMime = kv[1];
            else if (kv[0].equals("bit_rate")) f.audioBitrate = Long.parseLong(kv[1]);
            else if (kv[0].equals("channels")) f.channels = Integer.parseInt(kv[1]);
            else if (kv[0].equals("sample_rate")) f.sampleRate = Integer.parseInt(kv[1]);
        }
        return f;
    }

    /** ffmpeg as the phone's encoder and frame grabber. */
    static final class Ffmpeg implements VideoSearch.Engine {
        final File src; final VideoSearch.Facts f; final File base; int n;
        Ffmpeg(File src, VideoSearch.Facts f, File base) { this.src = src; this.f = f; this.base = base; }

        public VideoSearch.Copy encode(VideoSearch.Plan p, long fromUs, long toUs, int bitrate) throws Exception {
            boolean whole = fromUs == 0 && toUs == Long.MAX_VALUE;
            File out = new File(base.getPath() + "-" + (n++) + ".mp4");
            List<String> cmd = new ArrayList<String>(Arrays.asList("ffmpeg", "-v", "error", "-y"));
            if (!whole) { cmd.add("-ss"); cmd.add(sec(fromUs)); cmd.add("-t"); cmd.add(sec(toUs - fromUs)); }
            cmd.add("-i"); cmd.add(src.getPath());
            String vf = "scale=" + p.width + ":" + p.height + ":flags=bilinear";
            if (p.keptUs != null) {
                // the plan's rule: keep a frame when it starts a new 1/fps slot of the source's timeline
                String fr = sec(whole ? 0 : fromUs), fps = String.valueOf((int) Math.round(p.fps));
                String tol = sec((long) (0.1 * 1e6 / f.fps()));
                vf = "select='isnan(prev_selected_t)+gt(floor((t+" + fr + "+" + tol + ")*" + fps + "),floor((prev_selected_t+" + fr + "+" + tol + ")*" + fps + "))'," + vf;
                cmd.add("-fps_mode"); cmd.add("passthrough");
            }
            cmd.addAll(Arrays.asList("-vf", vf, "-c:v", "libx265", "-preset", "ultrafast", "-b:v", String.valueOf(bitrate),
                    "-maxrate", String.valueOf((int) (bitrate * 1.5)), "-bufsize", String.valueOf(bitrate * 2),
                    "-x265-params", "log-level=error:rc-lookahead=0:bframes=0:keyint=" + (int) Math.round(p.fps * 3), "-tag:v", "hvc1"));
            if (whole && f.audioMime != null) {
                if (p.audioBps > 0) { cmd.addAll(Arrays.asList("-c:a", "aac", "-b:a", String.valueOf(p.audioBps))); }
                else { cmd.add("-c:a"); cmd.add("copy"); }
            } else cmd.add("-an");
            cmd.add(out.getPath());
            long t = System.currentTimeMillis();
            run(cmd.toArray(new String[0]));
            VideoSearch.Copy c = new VideoSearch.Copy();
            c.path = out.getPath(); c.bytes = out.length(); c.ms = System.currentTimeMillis() - t;
            return c;
        }

        public FrameScore score(VideoSearch.Plan p, VideoSearch.Copy c, long fromUs, long[] sourceUs) throws Exception {
            FrameScore all = new FrameScore();
            // ffmpeg starts a stretch's timestamps at its first frame, like the phone tool's baseUs
            long base = 0;
            if (fromUs > 0) for (long t : (p.keptUs != null ? p.keptUs : f.frameUs)) if (t >= fromUs) { base = t; break; }
            for (long us : sourceUs) {
                int[] a = frame(src.getPath(), us, p);
                int[] b = frame(c.path, us - base, p);
                all.worst(FrameScore.of(a, b, p.compareWidth, p.compareHeight), us);
            }
            return all;
        }

        public void discard(VideoSearch.Copy c) { new File(c.path).delete(); }

        int[] frame(String file, long us, VideoSearch.Plan p) throws Exception {
            Process pr = new ProcessBuilder("ffmpeg", "-v", "error", "-ss", sec(Math.max(0, us - 1000)), "-i", file, "-frames:v", "1",
                    "-vf", "scale=" + p.compareWidth + ":" + p.compareHeight + ":flags=bilinear", "-f", "rawvideo", "-pix_fmt", "rgb24", "-").start();
            byte[] raw = readAll(pr.getInputStream());
            pr.waitFor();
            int n = p.compareWidth * p.compareHeight;
            if (raw.length < n * 3) throw new IllegalStateException("no frame at " + us + " us in " + file);
            int[] px = new int[n];
            for (int i = 0; i < n; i++) px[i] = 0xFF000000 | (raw[3 * i] & 255) << 16 | (raw[3 * i + 1] & 255) << 8 | (raw[3 * i + 2] & 255);
            return px;
        }
    }

    static String sec(long us) { return String.format(Locale.US, "%.6f", us / 1e6); }

    static String run(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String s = new String(readAll(p.getInputStream()), StandardCharsets.UTF_8);
        if (p.waitFor() != 0) throw new IllegalStateException(String.join(" ", cmd) + " failed: " + s);
        return s;
    }

    static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[1 << 16];
        for (int n; (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
        return b.toByteArray();
    }
}
