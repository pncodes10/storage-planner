package com.storageplanner.test;

import com.storageplanner.core.FileItem;
import com.storageplanner.core.ImageSignals;
import com.storageplanner.core.PhotoCategory;
import com.storageplanner.core.Scanner;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import javax.imageio.ImageIO;

/**
 * Photos of a page of paper (handwritten or printed), also in dim light, without reading any text: plain smooth
 * paper that is bright for its own light, no colour, and marks spread over it. Synthetic pictures only (576 x 1024,
 * like the sorter's decode): pages that must match, and walls, sky, scenes, a dark room, a lone dark object and rough
 * surfaces that must not. Then a scan of a small synthetic phone: a picture sorted by an older version is measured
 * and sorted again once; one sorted by the current version is left alone.
 * usage: PageTest
 */
public final class PageTest {
    static int checks, fails;
    static final int W = 576, H = 1024;

    static void check(String what, boolean ok) {
        checks++;
        if (!ok) { fails++; System.out.println("FAIL " + what); }
    }

    // ------------------------------------------------------------------ synthetic pictures
    static int rgb(double r, double g, double b) {
        return 0xFF000000 | clamp(r) << 16 | clamp(g) << 8 | clamp(b);
    }

    static int clamp(double v) { return v < 0 ? 0 : v > 255 ? 255 : (int) Math.round(v); }

    /** Paper of the given brightness, slightly warm like room light, with a little camera noise. */
    static int[] paper(double light, double noise, long seed) {
        Random rnd = new Random(seed);
        int[] px = new int[W * H];
        for (int i = 0; i < px.length; i++) {
            double n = rnd.nextGaussian() * noise;
            px[i] = rgb(light + 3 + n, light + 2 + n, light - 4 + n);
        }
        return px;
    }

    static void dot(int[] px, int x, int y, int rad, int c) {
        for (int dy = -rad; dy <= rad; dy++)
            for (int dx = -rad; dx <= rad; dx++) {
                int xx = x + dx, yy = y + dy;
                if (xx >= 0 && yy >= 0 && xx < W && yy < H && dx * dx + dy * dy <= rad * rad + 1) px[yy * W + xx] = c;
            }
    }

    /** Joined-up handwriting: wavy words under a headline, lines all down the page. */
    static void handwriting(int[] px, double light, int lines, long seed) {
        Random rnd = new Random(seed);
        int ink = rgb(light * 0.4, light * 0.45, light * 0.62);       // blue pen, as soft as a real one after the picture is shrunk
        for (int i = 0; i < lines; i++) {
            int y0 = 70 + i * (H - 140) / Math.max(1, lines - 1);
            int x = 40 + rnd.nextInt(20);
            int start = x;
            while (x < W - 50) {
                int len = 40 + rnd.nextInt(60);
                for (int k = 0; k < len && x + k < W - 40; k++) { int yy = y0 + (int) (7 * Math.sin(k * 0.4 + i)) + rnd.nextInt(2); px[yy * W + x + k] = ink; px[(yy + 1) * W + x + k] = ink; }
                x += len + 14 + rnd.nextInt(10);
            }
            for (int k = start; k < x - 14; k++) { px[(y0 - 16) * W + k] = ink; px[(y0 - 15) * W + k] = ink; }      // the headline joining the words
        }
    }

    /** Printed lines: rows of small dark letters. */
    static void print(int[] px, double light, long seed) {
        Random rnd = new Random(seed);
        int ink = rgb(light * 0.2, light * 0.2, light * 0.22);
        for (int y = 60; y < H - 60; y += 28)
            for (int x = 50; x < W - 60; x += 9) {
                if (rnd.nextInt(7) == 0) continue;                         // word gap
                for (int dy = 0; dy < 9; dy++) for (int dx = 0; dx < 4; dx++) px[(y + dy) * W + x + dx] = ink;
            }
    }

    static int[] wall(double light, double noise, long seed) { return paper(light, noise, seed); }

    static int[] sky(long seed) {
        Random rnd = new Random(seed);
        int[] px = new int[W * H];
        for (int y = 0; y < H; y++) {
            double t = y / (double) H;
            for (int x = 0; x < W; x++) {
                double n = rnd.nextGaussian() * 1.2;
                px[y * W + x] = rgb(165 + 50 * t + n, 192 + 36 * t + n, 235 + 10 * t + n);
            }
        }
        return px;
    }

    static int[] colourfulScene(long seed) {
        Random rnd = new Random(seed);
        int[] px = new int[W * H];
        for (int by = 0; by < H; by += 64)
            for (int bx = 0; bx < W; bx += 64) {
                int r = rnd.nextInt(256), g = rnd.nextInt(256), b = rnd.nextInt(256);
                for (int y = by; y < Math.min(H, by + 64); y++) for (int x = bx; x < Math.min(W, bx + 64); x++) { int n = rnd.nextInt(7) - 3; px[y * W + x] = rgb(r + n, g + n, b + n); }
            }
        return px;
    }

    static int[] oneObject(long seed) {
        int[] px = paper(220, 2, seed);
        int c = rgb(40, 40, 45);
        for (int y = 300; y < 480; y++) for (int x = 380; x < 540; x++) px[y * W + x] = c;
        return px;
    }

    static int[] rough(double light, long seed) {                         // concrete, gravel, cloth: grey with strong texture
        Random rnd = new Random(seed);
        int[] px = new int[W * H];
        for (int i = 0; i < px.length; i++) { double n = rnd.nextGaussian() * 14; px[i] = rgb(light + n, light + n, light + n); }
        return px;
    }

    static ImageSignals.S measure(int[] px) { return ImageSignals.measure(px, W, H); }

    static FileItem camera() {
        FileItem f = new FileItem("DCIM/Camera/x.jpg", "/sdcard/DCIM/Camera/x.jpg", 1, 1);
        f.area = "camera"; f.type = "jpeg";
        return f;
    }

    static void expect(String name, int[] px, boolean page) {
        ImageSignals.S s = measure(px);
        String cat = PhotoCategory.classify(camera(), s, null, null, -1).category;
        System.out.printf("  %-40s white %3.0f paper %3.0f%% smooth %3.0f%% ink %4.1f%% bands %d colour %4.1f -> %s%s%n", name, s.whiteLevel, 100 * s.pagePaper,
                100 * Math.max(0, s.smooth), 100 * Math.max(0, s.ink), s.inkBands, s.colorful, s.page() ? "PAGE" : "no", s.page() ? "" : " (" + s.pageFail() + ")");
        check(name + ": page() is " + page, s.page() == page);
        check(name + (page ? ": Documents & text" : ": not Documents (" + cat + ")"), page == PhotoCategory.DOCUMENT.equals(cat));
        if (page) check(name + ": says why", "Looks like a handwritten or printed page".equals(PhotoCategory.classify(camera(), s, null, null, -1).why) || s.wordy());
    }

    public static void main(String[] args) throws Exception {
        System.out.println("pictures that are a page:");
        int[] dim = paper(150, 2.0, 1); handwriting(dim, 150, 14, 2);
        expect("grey page, handwriting, dim light", dim, true);
        int[] bright = paper(235, 2.0, 3); handwriting(bright, 235, 14, 4);
        expect("white page, handwriting, bright light", bright, true);
        int[] few = paper(150, 2.0, 5); handwriting(few, 150, 7, 6);
        expect("grey page, only seven lines", few, true);
        int[] printed = paper(160, 2.0, 7); print(printed, 160, 8);
        expect("printed page, dim light", printed, true);
        int[] printedBright = paper(230, 1.5, 9); print(printedBright, 230, 10);
        expect("printed page, bright light", printedBright, true);

        System.out.println("pictures that are not:");
        expect("blank wall with noise", wall(200, 2.5, 11), false);
        expect("blank dim wall with noise", wall(150, 2.5, 12), false);
        expect("cloudy sky, slightly blue gradient", sky(13), false);
        expect("colourful scene", colourfulScene(14), false);
        expect("white surface with one dark object", oneObject(15), false);
        int[] darkRoom = paper(40, 2.0, 16); handwriting(darkRoom, 40, 14, 17);
        expect("dark room, writing but no light", darkRoom, false);
        int[] roughPx = rough(170, 18);
        expect("rough grey surface (gravel, concrete)", roughPx, false);
        int[] blue = paper(150, 2.0, 19); handwriting(blue, 150, 14, 20);
        for (int i = 0; i < blue.length; i++) { int p = blue[i]; blue[i] = rgb(((p >> 16) & 255) * 0.5, ((p >> 8) & 255) * 0.8, (p & 255) * 1.3); }
        expect("the same page under strong blue light", blue, false);
        int[] top = paper(190, 2.0, 21);                                    // writing in the top band only: marks not spread over the page
        for (int i = 0; i < 5; i++) for (int k = 40; k < 540; k++) dot(top, k, 25 + i * 18 + (int) (4 * Math.sin(k * 0.3)), 2, rgb(60, 60, 90));
        ImageSignals.S ss = measure(top);
        System.out.printf("  %-40s ink %4.1f%% bands %d -> %s%n", "writing in the top band only", 100 * ss.ink, ss.inkBands, ss.page() ? "PAGE" : "no (" + ss.pageFail() + ")");
        check("writing in one band only is not a page, and has some ink", !ss.page() && ss.ink >= ImageSignals.INK_MIN && ss.inkBands < ImageSignals.INK_BANDS_MIN);
        int[] door = paper(220, 2.0, 24);                                   // a tall dark door on a white wall
        for (int y = 100; y < 900; y++) for (int x = 200; x < 300; x++) door[y * W + x] = rgb(50, 40, 35);
        expect("white wall with a tall dark door", door, false);
        int[] sparse = paper(220, 2.0, 25);                                 // one thin cable across a white wall
        for (int k = 0; k < W; k++) dot(sparse, k, 100 + k / 2, 1, rgb(40, 40, 40));
        expect("white wall with one thin cable", sparse, false);

        // ---- the face decision: someone holding up a page is a photo of a person
        ImageSignals.S ps = measure(dim);
        check("page, no faces looked for: Documents", PhotoCategory.DOCUMENT.equals(PhotoCategory.classify(camera(), ps, null, null, -1).category));
        check("page, no face found: Documents", PhotoCategory.DOCUMENT.equals(PhotoCategory.classify(camera(), ps, null, null, 0).category));
        check("page with a face: People", PhotoCategory.PEOPLE.equals(PhotoCategory.classify(camera(), ps, null, null, 1).category));

        // ---- other categories keep priority: a screenshot, a picture with a code, an ID, a text verdict
        FileItem shot = camera(); shot.area = "screenshot";
        check("a screenshot stays a screenshot", PhotoCategory.SCREENSHOT.equals(PhotoCategory.classify(shot, ps, null, null, -1).category));
        check("a code stays a code", PhotoCategory.QR.equals(PhotoCategory.classify(camera(), ps, "https://example.org", null, -1).category));
        com.storageplanner.core.TextRules.Verdict v = new com.storageplanner.core.TextRules.Verdict();
        v.kind = PhotoCategory.ID; v.why = "x";
        check("an ID verdict from the text stays an ID", PhotoCategory.ID.equals(PhotoCategory.classify(camera(), ps, null, v, -1).category));

        // ---- worth reading, and not accidental
        check("a page is worth reading (printed pages in dim light get read)", PhotoCategory.worthReading(camera(), ps));
        check("a wall is not worth reading", !PhotoCategory.worthReading(camera(), measure(wall(200, 2.5, 11))));
        check("a sky is not worth reading", !PhotoCategory.worthReading(camera(), measure(sky(13))));
        check("a colourful scene is not worth reading", !PhotoCategory.worthReading(camera(), measure(colourfulScene(14))));
        int[] faint = paper(200, 1.0, 22);                                  // lightly written page: low contrast overall
        for (int i = 0; i < 6; i++) { int y0 = 150 + i * 140; for (int k = 60; k < 500; k++) dot(faint, k, y0 + (int) (6 * Math.sin(k * 0.3)), 1, rgb(120, 125, 150)); }
        ImageSignals.S fs = measure(faint);
        System.out.printf("  lightly written page: contrast %.1f ink %.1f%% bands %d -> %s%n", fs.contrast, 100 * fs.ink, fs.inkBands, fs.page() ? "PAGE" : "no (" + fs.pageFail() + ")");
        check("a lightly written page is not an accidental shot", !PhotoCategory.accidental(fs) || !fs.page());
        check("a blank wall can still be an accidental shot (nothing in it)", PhotoCategory.accidental(measure(wall(200, 1.0, 23))));

        // ---- cost
        for (int i = 0; i < 10; i++) measure(dim);
        long t0 = System.nanoTime();
        for (int i = 0; i < 30; i++) measure(dim);
        double withInk = (System.nanoTime() - t0) / 30 / 1e6;
        int[] w2 = wall(200, 2.5, 11);
        t0 = System.nanoTime();
        for (int i = 0; i < 30; i++) measure(w2);
        double wallMs = (System.nanoTime() - t0) / 30 / 1e6;
        System.out.printf("  measure at %d x %d: %.1f ms for a page, %.1f ms for a wall (the whole signal set, desktop)%n", W, H, withInk, wallMs);

        resort();

        System.out.println(checks - fails + "/" + checks + " page checks pass");
        if (fails > 0) System.exit(1);
    }

    // ------------------------------------------------------------------ scanning: pictures sorted by an older version are sorted again once
    static void write(File dir, String name, int[] px) throws Exception {
        BufferedImage im = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        im.setRGB(0, 0, W, H, px, 0, W);
        dir.mkdirs();
        ImageIO.write(im, "jpg", new File(dir, name));
    }

    static void resort() throws Exception {
        File dev = java.nio.file.Files.createTempDirectory("pagetest").toFile();
        try {
            File cam = new File(dev, "sdcard/DCIM/Camera");
            int[] page = paper(150, 2.0, 31); handwriting(page, 150, 14, 32);
            write(cam, "IMG_page.jpg", page);
            write(cam, "IMG_wall.jpg", wall(200, 2.5, 33));
            new File(dev, "meta").mkdirs();
            java.nio.file.Files.write(new File(dev, "meta/usagestats.json").toPath(), "{}".getBytes("UTF-8"));
            JvmPlatform pf = new JvmPlatform(dev.getAbsolutePath());
            Scanner.Progress quiet = new Scanner.Progress() { public void update(String s, int d, int t) {} public boolean cancelled() { return false; } };
            Scanner.Result first = Scanner.scan(pf, quiet);
            Map<String, FileItem> byRel = new HashMap<String, FileItem>();
            for (FileItem f : first.files) byRel.put(f.rel, f);
            FileItem p1 = byRel.get("DCIM/Camera/IMG_page.jpg"), w1 = byRel.get("DCIM/Camera/IMG_wall.jpg");
            check("scan: the page picture is Documents", p1 != null && PhotoCategory.DOCUMENT.equals(p1.category));
            check("scan: the wall is not Documents", w1 != null && !PhotoCategory.DOCUMENT.equals(w1.category));
            check("scan: measured by the current version, with page measurements", p1 != null && p1.sortVersion > 0 && p1.signals != null && p1.signals.pagePaper >= 0 && p1.signals.page());

            // as saved by the previous app version: sorted as a photo, no page measurements, sort version 0
            p1.category = PhotoCategory.PHOTO; p1.sortVersion = 0; p1.textPending = false;
            p1.signals = new ImageSignals.S(); p1.signals.meanLuma = 150;
            w1.sortVersion = 0;
            Scanner.Options again = new Scanner.Options();
            again.previous = byRel;
            Scanner.Result second = Scanner.scan(pf, quiet, again);
            FileItem p2 = null, w2 = null;
            for (FileItem f : second.files) { if (f.rel.endsWith("IMG_page.jpg")) p2 = f; if (f.rel.endsWith("IMG_wall.jpg")) w2 = f; }
            check("older sort version: the page is measured and sorted again (now Documents)", p2 != null && PhotoCategory.DOCUMENT.equals(p2.category) && p2.signals.pagePaper >= 0 && p2.sortVersion > 0);
            check("older sort version: the wall is sorted again, same result as before", w2 != null && w2.category.equals(w1.category) && w2.sortVersion > 0 && w2.signals.pagePaper >= 0);

            // sorted by the current version: kept exactly, nothing measured again
            FileItem keep = new FileItem(p2.rel, p2.abs, p2.size, p2.mtime);
            keep.area = p2.area; keep.type = p2.type; keep.category = PhotoCategory.PHOTO; keep.sortVersion = p2.sortVersion; keep.signals = p2.signals;
            Map<String, FileItem> prev2 = new HashMap<String, FileItem>();
            prev2.put(keep.rel, keep);
            again.previous = prev2;
            Scanner.Result third = Scanner.scan(pf, quiet, again);
            FileItem p3 = null;
            for (FileItem f : third.files) if (f.rel.endsWith("IMG_page.jpg")) p3 = f;
            check("current sort version: an unchanged picture keeps its result (not measured again)", p3 != null && PhotoCategory.PHOTO.equals(p3.category) && p3.signals == keep.signals);

            // a verdict that came from a text or a code is never undone by the new measurements
            p2.category = PhotoCategory.RECEIPT; p2.sortVersion = 0; p2.text = "total 120"; p2.textPending = false;
            Map<String, FileItem> prev3 = new HashMap<String, FileItem>();
            prev3.put(p2.rel, p2);
            again.previous = prev3;
            Scanner.Result fourth = Scanner.scan(pf, quiet, again);
            FileItem p4 = null;
            for (FileItem f : fourth.files) if (f.rel.endsWith("IMG_page.jpg")) p4 = f;
            check("older sort version: a receipt stays a receipt, with its text, and gets the new measurements", p4 != null && PhotoCategory.RECEIPT.equals(p4.category)
                    && "total 120".equals(p4.text) && p4.signals != null && p4.signals.pagePaper >= 0 && p4.sortVersion > 0);
        } finally {
            deleteAll(dev);
        }
    }

    static void deleteAll(File f) {
        File[] k = f.listFiles();
        if (k != null) for (File x : k) deleteAll(x);
        f.delete();
    }
}
