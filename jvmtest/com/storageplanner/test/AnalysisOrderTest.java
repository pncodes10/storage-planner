package com.storageplanner.test;

import com.storageplanner.core.Estimates;
import com.storageplanner.core.FileItem;
import com.storageplanner.core.ResultStore;
import com.storageplanner.core.Scanner;
import com.storageplanner.core.Unit;
import com.storageplanner.core.UnitBuilder;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The analysis one batch at a time, biggest first, that can be paused and carried on (Scanner.analyse): the order files
 * are looked at in, similar shots pulled forward, what a pause leaves (pending files get no suggestion, a group of similar
 * shots missing a photo waits), saving the pending files, and carrying on from there. Invented pictures only.
 */
public final class AnalysisOrderTest {
    static int checks, fails;

    static void check(String what, boolean ok) {
        checks++;
        if (!ok) { fails++; System.out.println("FAIL " + what); }
    }

    /** A 320 x 240 picture: a pattern from the seed, plus noise (more noise, bigger file; the fingerprint stays). */
    static byte[] jpeg(int pattern, int noise, int seed) throws IOException {
        BufferedImage img = new BufferedImage(320, 240, BufferedImage.TYPE_INT_RGB);
        Random p = new Random(pattern), n = new Random(seed);
        int cx = 60 + p.nextInt(200), cy = 40 + p.nextInt(160), r = 30 + p.nextInt(60), tone = p.nextInt(120);
        for (int y = 0; y < 240; y++)
            for (int x = 0; x < 320; x++) {
                boolean in = (x - cx) * (x - cx) + (y - cy) * (y - cy) < r * r;
                int v = in ? 220 - tone / 2 : 30 + tone + (pattern % 2 == 0 ? x / 4 : y / 3);
                v = Math.max(0, Math.min(255, v + (noise > 0 ? n.nextInt(2 * noise + 1) - noise : 0)));
                img.setRGB(x, y, v << 16 | v << 8 | v);
            }
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", o);
        return o.toByteArray();
    }

    static File put(File sd, String rel, byte[] b, long mtime) throws IOException {
        File f = new File(sd, rel);
        f.getParentFile().mkdirs();
        Files.write(f.toPath(), b);
        f.setLastModified(mtime);
        return f;
    }

    /** Records the order of the batches and pauses before batch number pauseAt (0 = never). */
    static final class Watch implements Scanner.Progress, Scanner.Live {
        final List<String> looked = new ArrayList<String>();
        final int pauseAt;
        int checkpoints; long lastDone = -1, lastTotal = -1; boolean sane = true;
        Watch(int pauseAt) { this.pauseAt = pauseAt; }
        public void update(String s, int d, int t) {}
        public boolean cancelled() { return pauseAt > 0 && looked.size() >= pauseAt; }
        public void looking(FileItem f) { looked.add(f.rel); }
        public void checkpoint(Scanner.Result r, int filesDone, int filesTotal, long bytesDone, long bytesTotal) {
            checkpoints++;
            int pending = 0; long pendingBytes = 0;
            for (FileItem f : r.files) if (f.pending) { pending++; pendingBytes += f.size; }
            boolean ok = filesTotal == r.files.size() && filesDone == filesTotal - pending && bytesDone == bytesTotal - pendingBytes
                    && filesDone >= lastDone;
            if (!ok) System.out.println("  checkpoint: " + filesDone + "/" + filesTotal + " files, " + bytesDone + "/" + bytesTotal
                    + " bytes; pending " + pending + " files, " + pendingBytes + " bytes, " + r.files.size() + " listed");
            sane &= ok;
            lastDone = filesDone; lastTotal = filesTotal;
        }
    }

    static Scanner.Options options(Watch w, Map<String, FileItem> previous) {
        Scanner.Options o = new Scanner.Options();
        o.sortPictures = false;
        o.batch = 1;
        o.checkpointMs = 0;
        o.live = w;
        o.previous = previous;
        return o;
    }

    static Map<String, FileItem> byRel(List<FileItem> l) {
        Map<String, FileItem> m = new HashMap<String, FileItem>();
        for (FileItem f : l) m.put(f.rel, f);
        return m;
    }

    static boolean unit(UnitBuilder ub, String rel) {
        for (Unit u : ub.units) if (u.id.equals(rel)) return true;
        return false;
    }

    public static void main(String[] args) throws Exception {
        File dev = Files.createTempDirectory("order").toFile(), sd = new File(dev, "sdcard");
        new File(dev, "meta").mkdirs();
        Files.write(new File(dev, "meta/usagestats.json").toPath(), "{}".getBytes());
        long t = 1780000000000L;
        // three shots of one moment (a, a2, b: same picture, a minute apart), one unrelated photo c, a download
        String A = "DCIM/Camera/IMG_A.jpg", A2 = "DCIM/Camera/IMG_A2.jpg", B = "DCIM/Camera/IMG_B.jpg", C = "DCIM/Camera/IMG_C.jpg", Z = "Download/notes.zip";
        File fa = put(sd, A, jpeg(4, 40, 1), t);
        File fa2 = put(sd, A2, jpeg(4, 4, 2), t + 30000);
        File fb = put(sd, B, jpeg(4, 2, 3), t + 60000);
        File fc = put(sd, C, jpeg(7, 20, 4), t + 86400000L);
        byte[] zip = new byte[5000]; zip[0] = 'P'; zip[1] = 'K'; zip[2] = 3; zip[3] = 4;
        put(sd, Z, zip, t);
        check("set-up: the first shot is the biggest file, the unrelated photo next, then the other two shots ("
                        + fa.length() + ", " + fc.length() + ", " + fa2.length() + ", " + fb.length() + ")",
                fa.length() > fc.length() && fc.length() > fa2.length() && fc.length() > fb.length());
        JvmPlatform pf = new JvmPlatform(dev.getPath());

        // ---------------------------------------------------------------- the whole analysis
        Watch all = new Watch(0);
        Scanner.Result full = Scanner.scan(pf, all, options(all, null));
        check("every file looked inside once, the download never (" + all.looked + ")", all.looked.size() == 4 && !all.looked.contains(Z));
        check("the biggest file first", all.looked.size() > 0 && all.looked.get(0).equals(A));
        check("then the shots taken within minutes of it, before the bigger unrelated photo",
                all.looked.size() == 4 && all.looked.subList(1, 3).contains(A2) && all.looked.subList(1, 3).contains(B) && all.looked.get(3).equals(C));
        boolean none = true;
        for (FileItem f : full.files) none &= !f.pending;
        check("nothing pending after a whole analysis", none && full.prepared);
        check("progress checkpoints add up (files and bytes done = total - pending, never going back)", all.checkpoints >= 4 && all.sane);
        UnitBuilder ubFull = new UnitBuilder(full, new Estimates(), 34);
        check("the three shots of one moment are one group, decided", ubFull.det.burstGroups == 1 && ubFull.det.waiting.isEmpty()
                && ubFull.det.burstExtraOf.size() == 2);
        boolean judged = true;
        for (FileItem f : full.files) if (f.rel.startsWith("DCIM/Camera/IMG_A") || f.rel.equals(B)) judged &= f.quality != null;
        check("the shots of the group were compared (eyes, faces, light)", judged);

        // ---------------------------------------------------------------- paused after two files
        Watch two = new Watch(3);
        Scanner.Result part = Scanner.scan(pf, two, options(two, null));
        Map<String, FileItem> p = byRel(part.files);
        check("paused: the list is complete", part.prepared && part.files.size() == full.files.size());
        check("paused: the first two files are done (" + two.looked + ")", !p.get(A).pending && !p.get(two.looked.get(1)).pending);
        String missing = two.looked.get(1).equals(A2) ? B : A2;
        check("paused: the file being looked at and the rest are pending", p.get(missing).pending && p.get(C).pending);
        check("paused: a file decided from the list (the download) is not pending", !p.get(Z).pending);
        UnitBuilder ub = new UnitBuilder(part, new Estimates(), 34);
        check("pending files get no suggestion", !unit(ub, missing) && !unit(ub, C));
        check("the two shots done wait for the third: undecided, nothing removed", ub.det.burstGroups == 0 && ub.det.burstExtraOf.isEmpty()
                && ub.det.waiting.size() == 1 && ub.det.waiting.get(0).size() == 2 && ub.det.waitingMissing.get(0) == 1);
        check("a photo with a shot of its moment still pending is not called blurry", !ub.det.blurry.contains(A) && !ub.det.blurry.contains(two.looked.get(1)));

        // ---------------------------------------------------------------- saved and loaded
        ResultStore.Saved sv = new ResultStore.Saved();
        sv.result = part; sv.est = new Estimates();
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        ResultStore.write(o, sv, ResultStore.VERSION);
        Map<String, FileItem> back = byRel(ResultStore.read(new ByteArrayInputStream(o.toByteArray())).result.files);
        check("saved: the pending files come back pending, the others done", back.get(missing).pending && back.get(C).pending
                && !back.get(A).pending && !back.get(Z).pending);
        ByteArrayOutputStream o13 = new ByteArrayOutputStream();
        ResultStore.write(o13, sv, 13);
        boolean old = true;
        for (FileItem f : ResultStore.read(new ByteArrayInputStream(o13.toByteArray())).result.files) old &= !f.pending;
        check("a format 13 file (before pausing) loads as all done", old);

        // ---------------------------------------------------------------- carrying on
        Watch on = new Watch(0);
        Scanner.Result done = Scanner.scan(pf, on, options(on, back));
        check("carrying on: a file done before comes first, the missing shot of its moment before the unrelated photo ("
                + on.looked + ")", on.looked.size() == 4 && on.looked.get(0).equals(A) && on.looked.indexOf(missing) < on.looked.indexOf(C) && on.looked.get(3).equals(C));
        check("carrying on: 2 files taken over as they were, none gone", done.resumed == 2 && done.gone == 0);
        UnitBuilder ub2 = new UnitBuilder(done, new Estimates(), 34);
        check("carrying on: the group is decided now, as in a whole analysis", ub2.det.burstGroups == 1 && ub2.det.waiting.isEmpty()
                && ub2.det.burstExtraOf.equals(ubFull.det.burstExtraOf));
        check("carrying on: every file has the suggestions of a whole analysis", ub2.units.size() == ubFull.units.size());

        // ---------------------------------------------------------------- a file removed while paused
        fc.delete();
        Watch gone = new Watch(0);
        Scanner.Result after = Scanner.scan(pf, gone, options(gone, back));
        check("a file removed while paused is gone from the list and counted", after.gone == 1 && !byRel(after.files).containsKey(C)
                && !gone.looked.contains(C));

        System.out.println("AnalysisOrderTest: " + (checks - fails) + "/" + checks + " checks passed");
        if (fails > 0) System.exit(1);
    }
}
