package com.storageplanner.test;

import com.storageplanner.tools.FrameScore;
import com.storageplanner.tools.Mp4;
import com.storageplanner.tools.VideoLevel;
import com.storageplanner.tools.VideoSearch;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Desktop tests for the plain-Java parts of the video tool: MP4 reading and dates, refusals, plans, the search. */
public final class VideoTest {
    static int failed = 0, passed = 0;

    static void check(String what, boolean ok) {
        if (ok) passed++; else { failed++; System.out.println("FAIL " + what); }
    }

    public static void main(String[] a) throws Exception {
        mp4();
        refusals();
        plans();
        search();
        scores();
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    // ------------------------------------------------------------------ MP4 boxes
    static byte[] box(String type, byte[]... parts) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int n = 8;
        for (byte[] p : parts) n += p.length;
        b.write(n >>> 24); b.write(n >>> 16); b.write(n >>> 8); b.write(n);
        b.write(type.getBytes(StandardCharsets.ISO_8859_1), 0, 4);
        for (byte[] p : parts) b.write(p, 0, p.length);
        return b.toByteArray();
    }

    /** A full box (mvhd/tkhd/mdhd) with creation and modification times, version 0 or 1, padded to a real size. */
    static byte[] timed(String type, int version, long created, long modified) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(version); b.write(0); b.write(0); b.write(0);
        if (version == 1) { put64(b, created); put64(b, modified); } else { put32(b, created); put32(b, modified); }
        for (int i = 0; i < 80; i++) b.write(0);
        return box(type, b.toByteArray());
    }

    static byte[] hdlr(String handler) {
        byte[] body = new byte[24];
        System.arraycopy(handler.getBytes(StandardCharsets.ISO_8859_1), 0, body, 8, 4);
        return box("hdlr", body);
    }

    static byte[] trak(String handler, int version, long created) {
        return box("trak", timed("tkhd", version, created, created), box("mdia", timed("mdhd", version, created, created), hdlr(handler)));
    }

    /** moov/meta (QuickTime) or moov/udta/meta (ISO, with version and flags) holding com.android.capture.fps = fps. */
    static byte[] captureMeta(float fps, boolean iso) {
        byte[] name = "com.android.capture.fps".getBytes(StandardCharsets.ISO_8859_1);
        ByteArrayOutputStream k = new ByteArrayOutputStream();
        put32(k, 0); put32(k, 2);                                  // version/flags, 2 keys
        byte[] other = "com.android.version".getBytes(StandardCharsets.ISO_8859_1);
        put32(k, 8 + other.length); k.write('m'); k.write('d'); k.write('t'); k.write('a'); k.write(other, 0, other.length);
        put32(k, 8 + name.length); k.write('m'); k.write('d'); k.write('t'); k.write('a'); k.write(name, 0, name.length);
        ByteArrayOutputStream d = new ByteArrayOutputStream();
        put32(d, 23); put32(d, 0); put32(d, Float.floatToIntBits(fps));   // type float32, locale, value
        ByteArrayOutputStream d1 = new ByteArrayOutputStream();
        put32(d1, 1); put32(d1, 0); d1.write('1'); d1.write('4');
        byte[] item1 = box("\0\0\0\1", box("data", d1.toByteArray()));
        byte[] item2 = box("\0\0\0\2", box("data", d.toByteArray()));
        byte[] hd = new byte[24];
        System.arraycopy("mdta".getBytes(StandardCharsets.ISO_8859_1), 0, hd, 8, 4);
        byte[] body = concat(box("hdlr", hd), box("keys", k.toByteArray()), box("ilst", item1, item2));
        return iso ? box("udta", box("meta", new byte[4], body)) : box("meta", body);
    }

    static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (byte[] p : parts) b.write(p, 0, p.length);
        return b.toByteArray();
    }

    static void put32(ByteArrayOutputStream b, long v) { b.write((int) (v >>> 24)); b.write((int) (v >>> 16)); b.write((int) (v >>> 8)); b.write((int) v); }
    static void put64(ByteArrayOutputStream b, long v) { put32(b, v >>> 32); put32(b, v); }

    static File file(byte[]... parts) throws IOException {
        File f = File.createTempFile("videotest", ".mp4");
        f.deleteOnExit();
        FileOutputStream o = new FileOutputStream(f);
        for (byte[] p : parts) o.write(p);
        o.close();
        return f;
    }

    static void mp4() throws Exception {
        long t = 3_800_000_000L;   // 2024 in MP4 seconds
        byte[] ftyp = box("ftyp", "isom\0\0\0\0isommp42".getBytes(StandardCharsets.ISO_8859_1));
        byte[] mdat = box("mdat", new byte[1000]);
        byte[] udta = box("udta", box("smta", new byte[12]), box("©xyz", new byte[20]));
        byte[] moov = box("moov", timed("mvhd", 0, t, t + 5), trak("vide", 0, t), trak("soun", 0, t), udta);
        File f = file(ftyp, mdat, moov);
        Mp4.Info i = Mp4.read(f);
        check("mp4: boxes in order", i.top.toString().equals("[ftyp, mdat, moov]"));
        check("mp4: readable", i.problem == null);
        check("mp4: mvhd times", i.created == t && i.modified == t + 5);
        check("mp4: tracks and handlers", i.tracks.size() == 2 && i.count("vide") == 1 && i.count("soun") == 1);
        check("mp4: user data listed", i.udta.size() == 2 && i.udta.get(0).equals("smta") && i.udta.get(1).equals("©xyz"));
        check("mp4: no trailer", i.trailerBytes == 0 && !i.samsungTrailer);
        check("mp4: creation in unix ms", i.createdMillis() == (t - Mp4.EPOCH_1904) * 1000L);

        // MediaMuxer's copy has "now": write the original back, version 0 and version 1 boxes
        File c = file(ftyp, box("moov", timed("mvhd", 0, 1, 1), trak("vide", 1, 1), trak("soun", 0, 1)), mdat);
        long len = c.length();
        int n = Mp4.writeTimes(c, t, t + 5);
        Mp4.Info ci = Mp4.read(c);
        check("dates: mvhd + 2x(tkhd + mdhd) written", n == 5);
        check("dates: mvhd now original", ci.created == t && ci.modified == t + 5);
        check("dates: version-1 track written", ci.tracks.get(0).created == t && ci.tracks.get(0).modified == t + 5);
        check("dates: version-0 track written", ci.tracks.get(1).created == t);
        check("dates: file size unchanged", c.length() == len);

        // trailers
        byte[] seft = new byte[100_000];
        System.arraycopy("SEFT".getBytes(StandardCharsets.ISO_8859_1), 0, seft, seft.length - 4, 4);
        seft[0] = 1;
        Mp4.Info s = Mp4.read(file(ftyp, moov, mdat, seft));
        check("trailer: Samsung trailer found", s.samsungTrailer && s.trailerBytes == seft.length);
        Mp4.Info z = Mp4.read(file(ftyp, moov, mdat, new byte[5000]));
        check("trailer: zero padding is not data", z.trailerBytes == 0);
        byte[] small = new byte[300]; small[0] = 7;
        check("trailer: small data counted", Mp4.read(file(ftyp, moov, mdat, small)).trailerBytes == 1);

        // capture speed: the key with its value (QuickTime meta: hdlr, keys, ilst), as Android and Samsung write it
        Mp4.Info normal = Mp4.read(file(ftyp, box("moov", timed("mvhd", 0, t, t), trak("vide", 0, t), captureMeta(30f, false)), mdat));
        check("capture speed key found", normal.captureFps);
        check("capture speed 30 read (float)", Math.abs(normal.captureFpsValue - 30) < 1e-6);
        Mp4.Info slowMo = Mp4.read(file(ftyp, box("moov", timed("mvhd", 0, t, t), trak("vide", 0, t), captureMeta(120f, true)), mdat));
        check("capture speed 120 read (ISO meta in udta)", Math.abs(slowMo.captureFpsValue - 120) < 1e-6);
        byte[] bare = box("meta", "....com.android.capture.fps....".getBytes(StandardCharsets.ISO_8859_1));
        Mp4.Info unread = Mp4.read(file(ftyp, box("moov", timed("mvhd", 0, t, t), trak("vide", 0, t), bare), mdat));
        check("capture speed key without a readable value: found, value unknown", unread.captureFps && Double.isNaN(unread.captureFpsValue));
        check("no moov: a problem", Mp4.read(file(ftyp, mdat)).problem != null);
        byte[] cut = new byte[moov.length - 30];
        System.arraycopy(moov, 0, cut, 0, cut.length);
        Mp4.Info ct = Mp4.read(file(ftyp, mdat, cut));
        check("cut-off moov: a problem, no crash", ct.problem != null);
        check("not an MP4", Mp4.read(file("hello, this is text and not a video".getBytes(StandardCharsets.ISO_8859_1))).problem != null);
    }

    // ------------------------------------------------------------------ refusals
    static VideoSearch.Facts facts(int w, int h, double fps, double seconds, long videoBps) {
        VideoSearch.Facts f = new VideoSearch.Facts();
        int n = (int) Math.round(fps * seconds);
        f.frameUs = new long[n];
        f.frameBytes = new int[n];
        for (int i = 0; i < n; i++) { f.frameUs[i] = Math.round(i * 1e6 / fps); f.frameBytes[i] = (int) (videoBps / 8 / fps); }
        f.width = w; f.height = h; f.durationUs = (long) (seconds * 1e6); f.videoBitrate = videoBps;
        f.videoMime = "video/avc"; f.audioMime = "audio/mp4a-latm"; f.audioBitrate = 256_000;
        f.fileBytes = (long) ((videoBps + f.audioBitrate) / 8 * seconds);
        f.mp4 = new Mp4.Info();
        Mp4.Track v = new Mp4.Track(); v.handler = "vide"; f.mp4.tracks.add(v);
        Mp4.Track s = new Mp4.Track(); s.handler = "soun"; f.mp4.tracks.add(s);
        return f;
    }

    static void refusals() {
        check("plain camera video accepted", VideoSearch.refuse(facts(1920, 1080, 30, 60, 17_000_000)) == null);
        VideoSearch.Facts hdr = facts(1920, 1080, 30, 60, 17_000_000); hdr.hdr = "PQ / HDR10";
        check("HDR refused, hard", VideoSearch.refuse(hdr) != null && VideoSearch.refuse(hdr).hard);
        check("120 fps refused", VideoSearch.refuse(facts(1920, 1080, 120, 10, 40_000_000)) != null);
        check("60 fps accepted", VideoSearch.refuse(facts(1920, 1080, 59.94, 10, 27_000_000)) == null);
        VideoSearch.Facts slow = facts(1920, 1080, 30, 10, 17_000_000); slow.mp4.captureFps = true; slow.mp4.captureFpsValue = 120;
        check("captured at 120, played at 30: refused", VideoSearch.refuse(slow) != null && VideoSearch.refuse(slow).why.contains("slow-motion"));
        VideoSearch.Facts samsung = facts(1920, 1080, 29.98, 10, 17_000_000); samsung.mp4.captureFps = true; samsung.mp4.captureFpsValue = 30;
        check("Samsung's capture speed 30 on a 30 fps video: accepted", VideoSearch.refuse(samsung) == null);
        VideoSearch.Facts unknown = facts(1920, 1080, 30, 10, 17_000_000); unknown.mp4.captureFps = true;
        check("capture speed unreadable: refused to be safe", VideoSearch.refuse(unknown) != null);
        VideoSearch.Facts meta = facts(1920, 1080, 30, 10, 17_000_000);
        Mp4.Track m = new Mp4.Track(); m.handler = "meta"; meta.mp4.tracks.add(m);
        check("extra data track refused", VideoSearch.refuse(meta) != null && VideoSearch.refuse(meta).why.contains("'meta'"));
        VideoSearch.Facts two = facts(1920, 1080, 30, 10, 17_000_000);
        Mp4.Track s2 = new Mp4.Track(); s2.handler = "soun"; two.mp4.tracks.add(s2);
        check("second audio track refused", VideoSearch.refuse(two) != null);
        VideoSearch.Facts tr = facts(1920, 1080, 30, 10, 17_000_000); tr.mp4.trailerBytes = 3 << 20; tr.mp4.samsungTrailer = true;
        check("large Samsung trailer refused", VideoSearch.refuse(tr) != null && VideoSearch.refuse(tr).why.contains("Samsung"));
        VideoSearch.Facts sm = facts(1920, 1080, 30, 10, 17_000_000); sm.mp4.trailerBytes = 200;
        check("small trailer accepted", VideoSearch.refuse(sm) == null);
        VideoSearch.Facts none = facts(1920, 1080, 30, 10, 17_000_000); none.frameUs = new long[0]; none.videoMime = null;
        check("no video refused, hard", VideoSearch.refuse(none).hard);
        check("half-second clip refused", VideoSearch.refuse(facts(1920, 1080, 30, 0.5, 17_000_000)) != null);
    }

    // ------------------------------------------------------------------ plans
    static void plans() {
        VideoSearch.Plan p = VideoSearch.plan(facts(3840, 2160, 30, 60, 48_000_000), VideoLevel.BALANCED, false);
        check("4K at Balanced -> 1920x1080", p.width == 1920 && p.height == 1080);
        p = VideoSearch.plan(facts(1080, 1920, 30, 60, 17_000_000), VideoLevel.SAVER, false);
        check("portrait 1080x1920 at Space saver -> 720x1280", p.width == 720 && p.height == 1280);
        p = VideoSearch.plan(facts(1920, 1080, 30, 60, 17_000_000), VideoLevel.MAXIMUM, false);
        check("1080p at Maximum -> 854x480 (even)", p.width == 852 || p.width == 854);
        check("sizes are even", p.width % 2 == 0 && p.height % 2 == 0);
        check("480p copy compared at its own size", p.compareWidth == p.width && p.compareHeight == p.height);
        p = VideoSearch.plan(facts(3840, 2160, 30, 60, 48_000_000), VideoLevel.BALANCED, false);
        check("1080p copy of 4K compared at half the copy's size", p.compareWidth == 960 && p.compareHeight == 540);
        p = VideoSearch.plan(facts(1920, 1080, 30, 60, 17_000_000), VideoLevel.BALANCED, false);
        check("Balanced 1080p compared at half size, like Looks identical", p.compareWidth == 960 && p.compareHeight == 540);
        VideoSearch.Plan trim = VideoSearch.plan(facts(1920, 1080, 30, 60, 17_000_000), VideoLevel.MAXIMUM, false);
        check("resize to the encoder's aligned size: changed", trim.resize(848, 480));
        check("resize: comparison follows the copy's new size", trim.width == 848 && trim.compareWidth == 848 && trim.compareHeight == 480);
        check("resize to the same size: no change", !trim.resize(848, 480));
        VideoSearch.Plan big = VideoSearch.plan(facts(1920, 1080, 30, 60, 17_000_000), VideoLevel.HIGH, false);
        big.resize(1920, 1072);
        check("resize above 720p: compared at half the new size", big.compareWidth == 960 && big.compareHeight == 536);
        p = VideoSearch.plan(facts(1920, 1080, 30, 60, 17_000_000), VideoLevel.IDENTICAL, false);
        check("Looks identical keeps the size", p.width == 1920 && p.height == 1080);
        check("Looks identical compares 1080p at half size", p.compareWidth == 960 && p.compareHeight == 540);
        check("Looks identical keeps every frame", p.keptUs == null && p.keeps(12345));
        check("audio copied at Looks identical", p.audioBps == 0);
        check("search range sane", p.loBps <= p.startBps && p.startBps <= p.hiBps && p.hiBps < 17_000_000);
        p = VideoSearch.plan(facts(1280, 720, 30, 60, 5_000_000), VideoLevel.IDENTICAL, false);
        check("720p compared at full size", p.compareWidth == 1280);

        VideoSearch.Facts f60 = facts(1920, 1080, 60, 10, 27_000_000);
        p = VideoSearch.plan(f60, VideoLevel.BALANCED, false);
        check("60 fps at Balanced -> 30 fps", p.fps == 30 && p.keptUs != null);
        check("60 -> 30 keeps every other frame", p.keptUs.length == 300 && p.keeps(f60.frameUs[0]) && !p.keeps(f60.frameUs[1]) && p.keeps(f60.frameUs[2]));
        VideoSearch.Facts f29 = facts(1920, 1080, 29.97, 10, 17_000_000);
        check("29.97 fps stays as it is", VideoSearch.plan(f29, VideoLevel.BALANCED, false).keptUs == null);
        p = VideoSearch.plan(facts(1920, 1080, 30, 60, 17_000_000), VideoLevel.SAVER, false);
        check("Space saver re-encodes 256 kbps audio to 96", p.audioBps == 96_000);
        VideoSearch.Facts lowAudio = facts(1920, 1080, 30, 60, 17_000_000); lowAudio.audioBitrate = 96_000;
        check("small audio is copied", VideoSearch.plan(lowAudio, VideoLevel.SAVER, false).audioBps == 0);
        VideoSearch.Plan avc = VideoSearch.plan(facts(1920, 1080, 30, 60, 17_000_000), VideoLevel.HIGH, true);
        VideoSearch.Plan hevc = VideoSearch.plan(facts(1920, 1080, 30, 60, 17_000_000), VideoLevel.HIGH, false);
        check("H.264 starts higher than HEVC", avc.startBps > hevc.startBps);
        check("already-small video: empty range", VideoSearch.plan(facts(1920, 1080, 60, 60, 1_000_000), VideoLevel.IDENTICAL, false).hiBps
                < VideoSearch.plan(facts(1920, 1080, 60, 60, 1_000_000), VideoLevel.IDENTICAL, false).loBps);

        VideoSearch.Plan shortP = VideoSearch.plan(facts(1920, 1080, 30, 12, 17_000_000), VideoLevel.HIGH, false);
        check("short video: one sample, the whole video", shortP.samples.size() == 1 && shortP.samples.get(0)[1] == Long.MAX_VALUE);
        VideoSearch.Facts busy = facts(1920, 1080, 30, 120, 17_000_000);
        for (int i = 1500; i < 1560; i++) busy.frameBytes[i] *= 5;     // 50-52 s is the hardest part
        VideoSearch.Plan longP = VideoSearch.plan(busy, VideoLevel.HIGH, false);
        check("long video: up to 5 samples of 2 s", longP.samples.size() >= 4 && longP.samples.size() <= 5);
        check("busiest stretch sampled first", Math.abs(longP.samples.get(0)[0] - 50_000_000L) <= 100_000L);
        boolean disjoint = true;
        for (int i = 0; i < longP.samples.size(); i++) for (int j = i + 1; j < longP.samples.size(); j++)
            if (longP.samples.get(i)[0] < longP.samples.get(j)[1] && longP.samples.get(j)[0] < longP.samples.get(i)[1]) disjoint = false;
        check("samples do not overlap", disjoint);
        boolean inside = true;
        for (int i = 0; i < longP.samples.size(); i++) for (long t : longP.sampleFrames.get(i))
            if (t < longP.samples.get(i)[0] + 500_000L || t >= longP.samples.get(i)[1]) inside = false;
        check("sample frames inside their sample, after its first half second", inside);
        check("verification checks 24 frames of a 2-minute video", longP.verifyUs.length == 24);
        boolean other = true;
        for (long t : longP.verifyUs) for (long[] s : longP.sampleFrames) for (long u : s) if (t == u) other = false;
        check("verification uses other frames than the search", other);
    }

    // ------------------------------------------------------------------ search
    /** A fake encoder: quality rises with bitrate and passes from `needed` bps; sizes follow the bitrate. */
    static final class Fake implements VideoSearch.Engine {
        final int needed; final double wholePenalty; final List<Integer> tried = new ArrayList<Integer>(); int wholes;
        Fake(int needed, double wholePenalty) { this.needed = needed; this.wholePenalty = wholePenalty; }
        public VideoSearch.Copy encode(VideoSearch.Plan p, long from, long to, int bitrate) {
            boolean whole = from == 0 && to == Long.MAX_VALUE;
            if (whole) wholes++; else tried.add(bitrate);
            VideoSearch.Copy c = new VideoSearch.Copy();
            c.path = (whole ? "W" : "S") + bitrate;
            c.bytes = whole ? bitrate / 8L * 60 : bitrate / 8L * 2;
            return c;
        }
        public FrameScore score(VideoSearch.Plan p, VideoSearch.Copy c, long from, long[] us) {
            int b = Integer.parseInt(c.path.substring(1));
            if (c.path.startsWith("W")) b = (int) (b / wholePenalty);
            FrameScore s = new FrameScore();
            s.frames = us.length;
            s.ssim = b >= needed ? p.level.ssimMin + 0.001 : p.level.ssimMin - 0.01;
            s.psnr = 45; s.chromaPsnr = 45; s.shift = 0;
            return s;
        }
        public void discard(VideoSearch.Copy c) {}
    }

    static void search() throws Exception {
        VideoSearch.Facts f = facts(1920, 1080, 30, 60, 17_000_000);
        VideoSearch.Plan p = VideoSearch.plan(f, VideoLevel.HIGH, false);
        Fake e = new Fake(3_000_000, 1.0);
        VideoSearch.Outcome o = VideoSearch.run(f, p, e);
        check("search: copy kept", o.ok && o.copy != null);
        check("search: bitrate at or above the need", o.bitrate >= 3_000_000);
        check("search: within 30% of the need", o.bitrate <= 3_000_000 * 1.3);
        check("search: few rounds", e.tried.size() / p.samples.size() <= 4);
        check("search: one whole encode", e.wholes == 1);

        Fake hard = new Fake(30_000_000, 1.0);
        VideoSearch.Outcome oh = VideoSearch.run(f, p, hard);
        check("search: impossible target -> not compressed", !oh.ok && oh.copy == null && oh.skipped.contains("no bitrate"));

        Fake drift = new Fake(3_000_000, 1.25);       // the whole video needs more than the samples showed
        VideoSearch.Outcome od = VideoSearch.run(f, p, drift);
        check("search: whole video retried once at a higher bitrate", od.ok && drift.wholes == 2);

        VideoSearch.Facts small = facts(1920, 1080, 30, 60, 1_200_000);
        VideoSearch.Plan ps = VideoSearch.plan(small, VideoLevel.IDENTICAL, false);
        VideoSearch.Outcome os = VideoSearch.run(small, ps, new Fake(100_000, 1.0));
        check("search: already small -> not compressed", !os.ok && os.skipped.contains("already small"));

        VideoSearch.Facts tiny = facts(1920, 1080, 30, 60, 17_000_000);
        tiny.fileBytes = 3_000_000 / 8L * 60;          // the copy would be about as big as the file
        VideoSearch.Outcome ot = VideoSearch.run(tiny, VideoSearch.plan(tiny, VideoLevel.HIGH, false), new Fake(3_000_000, 1.0));
        check("search: too little saving -> not kept, copy returned for the Lab", !ot.ok && ot.copy != null && ot.skipped.contains("saves only"));
    }

    static void scores() {
        int w = 64, h = 48;
        int[] a = new int[w * h], b = new int[w * h], c = new int[w * h];
        java.util.Random r = new java.util.Random(1);
        for (int i = 0; i < a.length; i++) {
            int v = 60 + r.nextInt(120);
            a[i] = 0xFF000000 | v << 16 | (v / 2) << 8 | (255 - v);
            b[i] = a[i];
            int v2 = Math.min(255, v + 10);
            c[i] = 0xFF000000 | v2 << 16 | (v / 2) << 8 | (255 - v);
        }
        FrameScore same = FrameScore.of(a, b, w, h);
        check("score: identical frames", same.ssim > 0.9999 && same.psnr == 99 && same.shift == 0);
        FrameScore red = FrameScore.of(a, c, w, h);
        check("score: a red cast is a colour shift", red.shift > 9 && !red.passes(VideoLevel.MAXIMUM));
        // alignment: a copy moved down by one row is found, an aligned one is not
        int W = 96, H = 72;
        int[] img = new int[W * H], down = new int[W * H];
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            int v = (int) (128 + 60 * Math.sin(x * 0.7) * Math.cos(y * 0.9));
            img[y * W + x] = 0xFF000000 | v << 16 | v << 8 | v;
        }
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) down[y * W + x] = img[Math.max(0, y - 1) * W + x];
        String al = FrameScore.alignment(img, img, W, H);
        check("alignment: aligned copy -> (0,0) everywhere", al.contains("top (0,0)") && al.contains("middle (0,0)") && al.contains("bottom (0,0)"));
        String sh = FrameScore.alignment(img, down, W, H);
        check("alignment: copy one row down -> (0,1) found", sh.contains("middle (0,1)") && sh.contains("top (0,1)"));
        int[] half = new int[W * H];
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            int v = ((img[Math.max(0, y - 1) * W + x] & 255) + (img[y * W + x] & 255) + 1) / 2;
            half[y * W + x] = 0xFF000000 | v << 16 | v << 8 | v;
        }
        String hs = FrameScore.alignment(img, half, W, H);
        check("alignment: copy half a row down -> (0,0.5) found", hs.contains("middle (0,0.5)"));
        FrameScore all = new FrameScore();
        all.worst(same, 1000); all.worst(red, 2000);
        check("score: worst kept, and where", all.ssim == red.ssim && all.worstAtUs == 2000 && all.frames == 2);
    }
}
