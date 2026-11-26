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
        f.videoVersion = VideoCheck.VERSION; f.vFrames = 5; f.vDark = dark; f.vMotion = motion; f.vSharpMax = 60; f.vPeople = people; f.vWords = words; f.vHash = hash;
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
        check("blurred all through (sharpest frame far softer than your photos): accidental", VideoCheck.blurry(good, camSharp) && VideoCheck.accidental(good, camSharp));
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
        check("old upright chat clip with nobody in it: removed", Techniques.FORWARD_VIDEOS.equals(del(ub, oldReel)));
        check("the same clip only 10 days old: kept", del(ub, newReel) == null);
        check("a forward-looking clip with a face: kept", del(ub, faceReel) == null);
        check("a pocket recording: removed", Techniques.ACCIDENTAL_CLIPS.equals(del(ub, pocket)));
        check("a normal camera clip: not removed", del(ub, good) == null);
        Tags.T t = Tags.of(pocket, Tags.context(r, ub.det), NOW);
        check("tags", t.videoLooked && t.videoDark && t.accidentalClip && t.videoSeconds == 20 && t.videoPeople == 0);
        List<FinalPlan.Entry> plan = FinalPlan.build(r.files, unitMap(ub), new HashMap<String, String>(), new HashSet<String>(), new HashMap<String, String>());
        for (FinalPlan.Entry e : plan) {
            if (e.file == pocket) check("in the Blurry and accidental pile, said plainly", Piles.BLURRY.equals(Piles.of(e)) && Piles.why(e).contains("by mistake"));
            if (e.file == oldReel) check("in the Memes and forwards pile", Piles.FORWARDS.equals(Piles.of(e)) && Piles.why(e).contains("forwarded"));
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
        pocket.fps = 29.97f;
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        ResultStore.write(bo, s);
        ResultStore.Saved back = ResultStore.read(new ByteArrayInputStream(bo.toByteArray()));
        FileItem p2 = null;
        for (FileItem f : back.result.files) if (f.rel.equals(pocket.rel)) p2 = f;
        check("video measurements survive a save", p2 != null && p2.videoVersion == VideoCheck.VERSION && p2.vDark == 5 && p2.vMotion == 0.5
                && p2.vPeople == 0 && p2.vHash == 10 && Math.abs(p2.fps - 29.97f) < 1e-4);

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
        check("Lab video verdict: frames, tags, the matching rule and the verdict", log.indexOf("frame 5 at") > 0 && log.indexOf("DARK") > 0
                && log.indexOf("taken by mistake YES") > 0 && log.indexOf("rules: \"Video clip taken by mistake\"") > 0 && log.indexOf("VERDICT: SUGGEST DELETING") > 0
                && lv.type.equals("mp4") && lv.rel.startsWith("DCIM/Camera/"));

        System.out.println("VideoCheckTest: " + (checks - fails) + "/" + checks + " checks passed");
        if (fails > 0) System.exit(1);
    }

    static Map<String, Unit> unitMap(UnitBuilder ub) { Map<String, Unit> m = new HashMap<String, Unit>(); for (Unit u : ub.units) m.put(u.id, u); return m; }
}
