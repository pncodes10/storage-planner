package com.storageplanner.test;

import com.storageplanner.core.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Looking inside videos (VideoCheck): frames measured for darkness, flatness, motion and faces; what that means
 * (accidental clip, forwarded-looking video); WhatsApp copies of camera videos; the three video rules in the plan,
 * never for a clip with a face in it; and the measurements surviving a save. Frames are drawn here, no real videos.
 */
public final class VideoCheckTest {
    static int checks, fails;
    static final long NOW = 1795000000000L, DAY = 86400000L;
    static final int W = 320, H = 180;

    static void check(String what, boolean ok) {
        checks++;
        if (!ok) { fails++; System.out.println("FAIL " + what); }
    }

    /** A frame of one grey level. */
    static int[] flat(int g) { int[] p = new int[W * H]; Arrays.fill(p, 0xFF000000 | g << 16 | g << 8 | g); return p; }

    /** A busy frame (squares of random grey, a new pattern for each seed: a scene that changes). */
    static int[] busy(long seed) {
        Random r = new Random(seed);
        int[] p = new int[W * H];
        for (int by = 0; by < H; by += 10)
            for (int bx = 0; bx < W; bx += 10) {
                int g = 30 + r.nextInt(200);
                for (int y = by; y < Math.min(H, by + 10); y++) for (int x = bx; x < Math.min(W, bx + 10); x++) p[y * W + x] = 0xFF000000 | g << 16 | g << 8 | g;
            }
        return p;
    }

    static FileItem video(String rel, String area, long durMs, int w, int h, double ageDays) {
        FileItem f = new FileItem(rel, "/sd/" + rel, 30000000, NOW - (long) (ageDays * DAY));
        f.area = area; f.type = "mp4"; f.vcodec = "video/avc"; f.width = w; f.height = h; f.durationMs = durMs; f.bitrate = 8000000;
        return f;
    }

    static void looked(FileItem f, int dark, double motion, int people, int words, long hash) {
        f.videoVersion = VideoCheck.VERSION; f.vFrames = 5; f.vDark = dark; f.vMotion = motion; f.vSharpMax = 60; f.vPeople = people; f.vWords = words; f.vHash = hash; f.vHashed = true;
    }

    static String del(UnitBuilder ub, FileItem f) {
        for (Unit u : ub.units) if (u.file == f) for (Option o : u.options) if ("delete".equals(o.action)) return o.technique;
        return null;
    }

    public static void main(String[] a) throws Exception {
        // ---- measuring frames
        List<int[]> dark = new ArrayList<int[]>();
        for (int i = 0; i < 5; i++) dark.add(flat(12));
        VideoCheck.Result rd = VideoCheck.measure(dark, W, H, new int[]{0, 0, 0, 0, 0});
        check("a black clip: every frame dark, nothing moves, no faces", rd.frames == 5 && rd.dark == 5 && rd.motion == 0 && rd.people == 0);
        List<int[]> grey = new ArrayList<int[]>();
        for (int i = 0; i < 5; i++) grey.add(flat(140));
        List<int[]> night = new ArrayList<int[]>();                       // lights in the dark: low mean, wide spread
        for (int i = 0; i < 5; i++) {
            int[] p = flat(8);
            Random rr = new Random(40 + i);
            for (int k = 0; k < 600; k++) { int x = rr.nextInt(W - 20), y = rr.nextInt(H - 10); for (int dy = 0; dy < 6; dy++) for (int dx = 0; dx < 14; dx++) p[(y + dy) * W + x + dx] = 0xFFF0E080; }
            night.add(p);
        }
        VideoCheck.Result rn = VideoCheck.measure(night, W, H, new int[5]);
        check("a night clip with lights in it is not dark (only a pocket or a covered lens is)", rn.dark == 0);
        check("a covered lens (bright but flat) counts as dark", VideoCheck.measure(grey, W, H, new int[5]).dark == 5);
        List<int[]> moving = new ArrayList<int[]>();
        for (int i = 0; i < 5; i++) moving.add(busy(i));
        VideoCheck.Result rm = VideoCheck.measure(moving, W, H, new int[]{0, 2, 1, 0, 0});
        check("a changing scene: not dark, moves, the most faces in one frame", rm.dark == 0 && rm.motion > VideoCheck.STILL_BITS && rm.people == 2 && rm.sharpMax > 0);
        List<int[]> gaps = new ArrayList<int[]>(moving); gaps.set(1, null); gaps.set(3, null);
        VideoCheck.Result rg = VideoCheck.measure(gaps, W, H, new int[]{0, -1, 0, -1, 0});
        check("frames that could not be decoded are skipped", rg.frames == 3 && rg.people == 0);
        check("faces unknown in a decoded frame: people unknown", VideoCheck.measure(moving, W, H, new int[]{0, 0, -1, 0, 0}).people == -1
                && VideoCheck.measure(moving, W, H, null).people == -1);
        check("the fingerprint is the middle frame's", rm.hash == ImageFeatures.phash(VideoCheck.luma(moving.get(2)), W, H));

        // ---- what it means
        double[] camSharp = {40, 50, 60, 70, 80};
        FileItem acc = video("DCIM/Camera/VID_1.mp4", "camera", 20000, 1920, 1080, 10);
        check("not looked at: unknown, never matched", VideoCheck.accidental(acc, camSharp) == null && VideoCheck.forwardLike(acc) == null && VideoCheck.dark(acc) == null);
        looked(acc, 5, 0.5, 0, 0, 1);
        check("dark and still, nobody in it: accidental", VideoCheck.accidental(acc, camSharp) && VideoCheck.dark(acc) && VideoCheck.still(acc));
        acc.vPeople = 1;
        check("a face in it: never accidental", !VideoCheck.accidental(acc, camSharp));
        acc.vPeople = -1;
        check("faces unknown: not decided", VideoCheck.accidental(acc, camSharp) == null);
        FileItem shortClip = video("DCIM/Camera/VID_2.mp4", "camera", 1200, 1920, 1080, 10);
        looked(shortClip, 0, 20, 0, 0, 2);
        check("a clip of 1.2 s: accidental", VideoCheck.accidental(shortClip, camSharp));
        FileItem good = video("DCIM/Camera/VID_3.mp4", "camera", 40000, 1920, 1080, 10);
        looked(good, 0, 20, 0, 0, 3);
        check("a normal clip: not accidental", !VideoCheck.accidental(good, camSharp));
        good.vSharpMax = 5;
        check("blurred all through alone is not enough (+30 of the 60 needed)", VideoCheck.blurry(good, camSharp) && !VideoCheck.accidental(good, camSharp)
                && VideoCheck.junkScore(good, camSharp) == VideoCheck.P_BLURRY);
        good.vMotion = 1; good.vIndexed = true; good.vAudio = 0;
        check("blurred, still and without sound: 60, taken by mistake", VideoCheck.junkScore(good, camSharp) == 60 && VideoCheck.accidental(good, camSharp));
        good.vMotion = 20; good.vIndexed = false; good.vAudio = -1;
        check("blur needs enough camera photos to compare with", VideoCheck.blurry(good, new double[]{50}) == null);
        good.vSharpMax = 60;
        FileItem reel = video("WhatsApp/Media/WhatsApp Video/VID-1.mp4", "wa_video", 25000, 720, 1280, 60);
        looked(reel, 0, 20, 0, 0, 4);
        check("an upright 25 s chat clip looks like a forward", VideoCheck.forwardLike(reel));
        FileItem captioned = video("WhatsApp/Media/WhatsApp Video/VID-2.mp4", "wa_video", 40000, 1280, 720, 60);
        looked(captioned, 0, 20, 0, 6, 5);
        check("a wide clip with a caption looks like a forward", VideoCheck.forwardLike(captioned));
        FileItem longOne = video("WhatsApp/Media/WhatsApp Video/VID-3.mp4", "wa_video", 600000, 720, 1280, 60);
        looked(longOne, 0, 20, 0, 0, 6);
        check("a 10-minute video is not a forward", !VideoCheck.forwardLike(longOne));

        // ---- WhatsApp copies of camera videos
        FileItem cam = video("DCIM/Camera/VID_4.mp4", "camera", 30000, 1920, 1080, 20);
        looked(cam, 0, 20, 1, 0, 0x123456789L);
        FileItem sent = video("WhatsApp/Media/WhatsApp Video/Sent/VID-4.mp4", "wa_sent_video", 30400, 848, 480, 19);
        looked(sent, 0, 20, 1, 0, 0x123456789L ^ 0xFFL);
        FileItem other = video("WhatsApp/Media/WhatsApp Video/Sent/VID-5.mp4", "wa_sent_video", 30000, 848, 480, 19);
        looked(other, 0, 20, 0, 0, ~0x123456789L);
        FileItem upright = video("WhatsApp/Media/WhatsApp Video/Sent/VID-6.mp4", "wa_sent_video", 30000, 480, 848, 19);
        looked(upright, 0, 20, 0, 0, 0x123456789L);
        Map<String, String> m = new HashMap<String, String>();
        VideoCheck.sentCopies(Arrays.asList(cam, sent, other, upright), m);
        check("the sent copy is found; a different video and a turned one are not", cam.rel.equals(m.get(sent.rel)) && !m.containsKey(other.rel) && !m.containsKey(upright.rel));

        // ---- the rules in the plan
        FileItem oldReel = video("WhatsApp/Media/WhatsApp Video/VID-7.mp4", "wa_video", 25000, 720, 1280, 60);
        looked(oldReel, 0, 20, 0, 0, 7);
        FileItem newReel = video("WhatsApp/Media/WhatsApp Video/VID-8.mp4", "wa_video", 25000, 720, 1280, 10);
        looked(newReel, 0, 20, 0, 0, 8);
        FileItem faceReel = video("WhatsApp/Media/WhatsApp Video/VID-9.mp4", "wa_video", 25000, 720, 1280, 60);
        looked(faceReel, 0, 20, 1, 0, 9);
        FileItem pocket = video("DCIM/Camera/VID_10.mp4", "camera", 20000, 1920, 1080, 5);
        looked(pocket, 5, 0.5, 0, 0, 10);
        Scanner.Result r = new Scanner.Result();
        r.now = NOW;
        r.files.addAll(Arrays.asList(cam, sent, oldReel, newReel, faceReel, pocket, good));
        for (int i = 0; i < 6; i++) {                                     // camera photos, so blur has something to compare with
            FileItem p = new FileItem("DCIM/Camera/IMG_" + i + ".jpg", "/sd/IMG_" + i + ".jpg", 3000000, NOW - 5 * DAY);
            p.area = "camera"; p.type = "jpeg"; p.width = 4000; p.height = 3000; p.sharp = 50 + i;
            r.files.add(p);
        }
        UnitBuilder ub = new UnitBuilder(r, new Estimates(), 34, RuleSet.defaults());
        check("sent copy of a camera video: removed, the original kept", Techniques.SENT.equals(del(ub, sent)) && del(ub, cam) == null);
        check("no forwarded-video rule any more: old chat clips are kept", del(ub, oldReel) == null && del(ub, newReel) == null && del(ub, faceReel) == null);
        check("a pocket recording: removed", Techniques.ACCIDENTAL_CLIPS.equals(del(ub, pocket)));
        check("a normal camera clip: not removed", del(ub, good) == null);
        Tags.T t = Tags.of(pocket, Tags.context(r, ub.det), NOW);
        check("tags", t.videoLooked && t.videoDark && t.accidentalClip && t.videoSeconds == 20 && t.videoPeople == 0);
        List<FinalPlan.Entry> plan = FinalPlan.build(r.files, unitMap(ub), new HashMap<String, String>(), new HashSet<String>(), new HashMap<String, String>());
        for (FinalPlan.Entry e : plan) {
            if (e.file == pocket) check("in the Blurry and accidental pile, said plainly", Piles.BLURRY.equals(Piles.of(e)) && Piles.why(e).contains("by mistake"));
        }

        // ---- real frame rate in the compression estimate
        FileItem f60 = video("DCIM/Camera/VID_11.mp4", "camera", 20000, 1920, 1080, 5);
        f60.bitrate = 10000000;                              // 0.16 bits per pixel per frame at 30 fps, 0.08 at 60
        double at30 = new Estimates().video(f60);
        f60.fps = 60;
        check("60 fps: the same bitrate is half the bits per frame", at30 == 0.45 && new Estimates().video(f60) == 0.25);

        // ---- saved and read back
        ResultStore.Saved s = new ResultStore.Saved();
        s.result = r; s.est = new Estimates();
        pocket.fps = 29.97f; pocket.vIndexed = true; pocket.vKeyframes = 20; pocket.vLowDetail = 1; pocket.vStill = 0.95; pocket.vAudio = 1; pocket.vQuiet = 0.92;
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        ResultStore.write(bo, s);
        ResultStore.Saved back = ResultStore.read(new ByteArrayInputStream(bo.toByteArray()));
        FileItem p2 = null;
        for (FileItem f : back.result.files) if (f.rel.equals(pocket.rel)) p2 = f;
        check("video measurements survive a save", p2 != null && p2.videoVersion == VideoCheck.VERSION && p2.vDark == 5 && p2.vMotion == 0.5
                && p2.vPeople == 0 && p2.vHash == 10 && Math.abs(p2.fps - 29.97f) < 1e-4
                && p2.vHashed && p2.vIndexed && p2.vKeyframes == 20 && p2.vStill == 0.95 && p2.vAudio == 1 && p2.vQuiet == 0.92);

        // ---- the Algorithm Lab's video verdict on one video (a platform that serves dark, still frames and no faces)
        Platform fake = (Platform) java.lang.reflect.Proxy.newProxyInstance(Platform.class.getClassLoader(), new Class<?>[]{Platform.class},
                new java.lang.reflect.InvocationHandler() {
                    public Object invoke(Object proxy, java.lang.reflect.Method m, Object[] args) {
                        String n = m.getName();
                        if (n.equals("videoFrames")) { int[] d = (int[]) args[3]; d[0] = W; d[1] = H; int[][] fr = new int[5][]; for (int i = 0; i < 5; i++) fr[i] = flat(10); return fr; }
                        if (n.equals("video")) { FileItem f = (FileItem) args[0]; f.vcodec = "video/avc"; f.width = 1920; f.height = 1080; f.durationMs = 15000; f.fps = 30; f.bitrate = 9000000; return true; }
                        if (n.equals("faces")) return new float[0];
                        Class<?> r = m.getReturnType();
                        return r == boolean.class ? Boolean.FALSE : r == int.class ? 0 : r == long.class ? 0L : null;
                    }
                });
        final StringBuilder log = new StringBuilder();
        FileItem lv = Lab.videoItem("/sd/x.mp4", "VID_20261101.mp4", 25000000, NOW - 3 * DAY, "camera");
        Lab.videoVerdict(fake, lv, r.files, RuleSet.defaults(), NOW, new Lab.Out() { public void line(String x) { log.append(x).append('\n'); } });
        if (a.length > 0) System.out.println(log);
        check("Lab video verdict: frames, tags, the matching rule and the verdict", log.indexOf("no usable index") > 0 && log.indexOf("frame at  13.8 s") > 0 && (log.indexOf("DARK") > 0 || log.indexOf("FLAT") > 0)
                && log.indexOf("taken by mistake YES") > 0 && log.indexOf("rules: \"Video clip taken by mistake\"") > 0 && log.indexOf("VERDICT: SUGGEST DELETING") > 0
                && lv.type.equals("mp4") && lv.rel.startsWith("DCIM/Camera/"));

        // ---- the index: a pocket recording, a normal clip and an encoder that pads every keyframe
        java.io.File dir = java.nio.file.Files.createTempDirectory("vidx").toFile();
        java.io.File pocketMp4 = mp4(dir, "pocket.mp4", 5000, 200, 8), normalMp4 = mp4(dir, "normal.mp4", 200000, 40000, 300),
                paddedMp4 = mp4(dir, "padded.mp4", 100000, 100000, 300);
        VideoIndex.Stats sp = VideoIndex.stats(pocketMp4.getPath(), 1920, 1080), sn = VideoIndex.stats(normalMp4.getPath(), 1920, 1080),
                sf = VideoIndex.stats(paddedMp4.getPath(), 1920, 1080);
        check("index of a pocket recording: keyframes empty, nothing moves, quiet", sp.ok && sp.frames == 600 && sp.keyframes == 20 && Math.abs(sp.seconds - 20) < 0.01
                && sp.lowDetail == 1 && sp.still == 1 && sp.audio == 1 && sp.quiet == 1);
        check("index of a normal clip: detail, movement, sound", sn.ok && sn.lowDetail == 0 && sn.still == 0 && sn.quiet == 0 && sn.richestUs.length == 3
                && sn.medianKeyBpp > 0.5 && !sn.flat);
        check("the richest keyframes are found, a second or more apart", sn.richestUs[0] == 19000000L && Math.abs(sn.richestUs[1] - sn.richestUs[0]) >= 1000000L);
        check("an encoder that pads keyframes: the sizes tell nothing", sf.ok && sf.flat);
        check("no index: not usable", !VideoIndex.stats(dir.getPath() + "/missing.mp4", 1920, 1080).ok
                && !VideoIndex.stats(write(dir, "junk.mp4", new byte[100]).getPath(), 1920, 1080).ok);

        // ---- the check with the index: frames are decoded only when the index finds something
        final int[] decodes = {0};
        final boolean[] darkFrames = {false};
        Platform frames = (Platform) java.lang.reflect.Proxy.newProxyInstance(Platform.class.getClassLoader(), new Class<?>[]{Platform.class},
                new java.lang.reflect.InvocationHandler() {
                    public Object invoke(Object proxy, java.lang.reflect.Method m, Object[] args) {
                        String n = m.getName();
                        if (n.equals("videoFrames")) {
                            long[] at = (long[]) args[1]; decodes[0] += at.length;
                            int[] d = (int[]) args[3]; d[0] = W; d[1] = H;
                            int[][] fr = new int[at.length][];
                            for (int i = 0; i < at.length; i++) fr[i] = darkFrames[0] ? flat(6) : busy(at[i]);
                            return fr;
                        }
                        if (n.equals("faces")) return new float[0];
                        Class<?> r = m.getReturnType();
                        return r == boolean.class ? Boolean.FALSE : r == int.class ? 0 : r == long.class ? 0L : null;
                    }
                });
        FileItem vn = Lab.videoItem(normalMp4.getPath(), "vn.mp4", 30000000, NOW - 5 * DAY, "camera"); vn.durationMs = 20000; vn.width = 1920; vn.height = 1080;
        long[] un = VideoCheck.look(frames, vn, null);
        check("a normal clip: the index is enough, no frame decoded, not junk", un != null && un.length == 0 && decodes[0] == 0 && VideoCheck.indexClean(vn)
                && !VideoCheck.accidental(vn, camSharp) && VideoCheck.junkScore(vn, camSharp) == 0);
        FileItem vp = Lab.videoItem(pocketMp4.getPath(), "vp.mp4", 30000000, NOW - 5 * DAY, "camera"); vp.durationMs = 20000; vp.width = 1920; vp.height = 1080;
        darkFrames[0] = true;
        long[] up = VideoCheck.look(frames, vp, null);
        StringBuilder jw = new StringBuilder();
        int js = VideoCheck.junk(vp, camSharp, jw);
        check("a pocket recording: the 3 richest keyframes (+ the middle one) decoded, all dark: junk", up != null && up.length == 3 && decodes[0] == 4
                && vp.vDark == 3 && VideoCheck.accidental(vp, camSharp) && js == 90 && jw.toString().contains("dark or covered frames 3 of 3 +60")
                && jw.toString().contains("nothing moves +20") && jw.toString().contains("quiet all through +10") && vp.vHashed);
        darkFrames[0] = false;
        FileItem vq = Lab.videoItem(pocketMp4.getPath(), "vq.mp4", 30000000, NOW - 5 * DAY, "camera"); vq.durationMs = 20000; vq.width = 1920; vq.height = 1080;
        VideoCheck.look(frames, vq, null);
        check("the index looked bad but the richest frames show things: kept", vq.vDark == 0 && !VideoCheck.accidental(vq, camSharp) && VideoCheck.junkScore(vq, camSharp) == 30);

        System.out.println("VideoCheckTest: " + (checks - fails) + "/" + checks + " checks passed");
        if (fails > 0) System.exit(1);
    }

    // ---- a minimal MP4 index: ftyp, moov with a 30 fps 1920x1080 video track (keyframe every 30 frames) and an AAC track
    static byte[] u32(long v) { return new byte[]{(byte) (v >> 24), (byte) (v >> 16), (byte) (v >> 8), (byte) v}; }
    static byte[] cat(byte[]... parts) { ByteArrayOutputStream o = new ByteArrayOutputStream(); for (byte[] p : parts) o.write(p, 0, p.length); return o.toByteArray(); }
    static byte[] box(String type, byte[]... parts) { byte[] c = cat(parts); return cat(u32(8 + c.length), type.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1), c); }
    static byte[] full(String type, byte[]... parts) { return box(type, cat(u32(0), cat(parts))); }

    static byte[] trak(String handler, long timescale, long delta, int[] sizes, int keyEvery) {
        byte[] mdhd = full("mdhd", u32(0), u32(0), u32(timescale), u32(delta * sizes.length), u32(0));
        byte[] hdlr = full("hdlr", u32(0), handler.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1), u32(0), u32(0), u32(0), new byte[]{0});
        byte[] stts = full("stts", u32(1), u32(sizes.length), u32(delta));
        ByteArrayOutputStream sz = new ByteArrayOutputStream();
        for (int x : sizes) sz.write(u32(x), 0, 4);
        byte[] stsz = full("stsz", u32(0), u32(sizes.length), sz.toByteArray());
        byte[] stss = new byte[0];
        if (keyEvery > 0) {
            ByteArrayOutputStream k = new ByteArrayOutputStream(); int n = 0;
            for (int i = 0; i < sizes.length; i += keyEvery) { k.write(u32(i + 1), 0, 4); n++; }
            stss = full("stss", u32(n), k.toByteArray());
        }
        return box("trak", box("mdia", mdhd, hdlr, box("minf", box("stbl", stts, stss, stsz))));
    }

    /** A 20 s video: keyframes of key bytes (the last one a little bigger), other frames of p bytes, sound chunks of snd bytes. */
    static java.io.File mp4(java.io.File dir, String name, int key, int p, int snd) throws java.io.IOException {
        int[] v = new int[600];
        for (int i = 0; i < v.length; i++) v[i] = i % 30 == 0 ? (key == p ? key : key * (8 + (i / 30) % 5) / 10) : p;   // keyframes vary, unless padded
        if (key != p) v[570] = key * 2;
        int[] au = new int[938];
        Arrays.fill(au, snd);
        byte[] file = cat(box("ftyp", "isom".getBytes(), u32(0)), box("moov", trak("vide", 30000, 1000, v, 30), trak("soun", 48000, 1024, au, 0)));
        return write(dir, name, file);
    }

    static java.io.File write(java.io.File dir, String name, byte[] b) throws java.io.IOException {
        java.io.File f = new java.io.File(dir, name);
        java.nio.file.Files.write(f.toPath(), b);
        return f;
    }

    static Map<String, Unit> unitMap(UnitBuilder ub) { Map<String, Unit> m = new HashMap<String, Unit>(); for (Unit u : ub.units) m.put(u.id, u); return m; }
}
