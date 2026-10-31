package com.storageplanner.test.tools;

import com.storageplanner.core.Estimates;
import com.storageplanner.core.FileItem;
import com.storageplanner.core.Option;
import com.storageplanner.core.Scanner;
import com.storageplanner.core.Unit;
import com.storageplanner.core.UnitBuilder;
import com.storageplanner.test.JvmPlatform;
import com.storageplanner.tools.faces.Faces;
import com.storageplanner.tools.quality.PhotoQuality;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import javax.imageio.ImageIO;

/**
 * Picking the better of near-identical shots, on real photos and real faces.
 *  A) lighting and focus: each real phone photo vs. a blurred, an over- and an under-exposed copy of itself;
 *  B) eyes: "bursts" of the same scene (real photo background, 1-3 real held-out faces) where one shot has a
 *     closed-eye face, sometimes made the sharper shot on purpose; faces are passed with detector-like error;
 *  C) end to end: those bursts saved as camera photos, scanned and planned; the kept shot must be the one with
 *     everyone's eyes open, and the reason must say so.
 * usage: PhotoQualityTest <folder of real photos> <heldout.tsv> <work dir>
 */
public final class PhotoQualityTest {
    static int checks, fails;
    static void check(String what, boolean ok) { checks++; if (!ok) { fails++; if (fails <= 25) System.out.println("FAIL " + what); } }

    public static void main(String[] a) throws Exception {
        List<File> photos = new ArrayList<File>();
        collect(new File(a[0]), photos);
        List<String[]> faces = new ArrayList<String[]>();
        for (String line : Files.readAllLines(new File(a[1]).toPath(), StandardCharsets.UTF_8)) if (!line.isEmpty()) faces.add(line.split("\t"));
        List<String[]> open = new ArrayList<String[]>(), closed = new ArrayList<String[]>();
        for (String[] f : faces) (f[1].equals("1") ? open : closed).add(f);
        File work = new File(a[2]);
        Random rnd = new Random(5);

        // ---------------- A) lighting and focus
        int[] ok = new int[3], n = new int[3]; int skippedLight = 0;
        String[] names = {"blurred copy", "over-exposed copy", "under-exposed copy"};
        for (File p : photos) {
            BufferedImage im = load(p, Scanner.QUALITY_EDGE);
            if (im == null) continue;
            PhotoQuality.Q base = q(im, null);
            BufferedImage[] v = {blur(im, 2), gain(im, 1.7, 1.0), gain(im, 0.45, 1.25)};
            // lighting is only judged against originals that are themselves normally exposed (mid brightness,
            // under 3% blown or black); a dark night shot may really look better brightened
            boolean normal = base.meanLuma >= 80 && base.meanLuma <= 170 && base.clipHi < 0.03 && base.clipLo < 0.03;
            if (!normal) skippedLight++;
            for (int k = 0; k < 3; k++) {
                if (k > 0 && !normal) continue;
                PhotoQuality.Q o = q(v[k], null);
                int best = PhotoQuality.best(Arrays.asList(o, base));
                n[k]++;
                if (best == 1) ok[k]++;
                else System.out.println("  note: " + p.getName() + " — " + names[k] + " ranked higher");
            }
        }
        for (int k = 0; k < 3; k++) {
            System.out.printf(Locale.ROOT, "A) original beats its %s: %d/%d%n", names[k], ok[k], n[k]);
            check("A " + names[k], ok[k] >= Math.ceil(0.9 * n[k]));
        }
        System.out.println("A) " + skippedLight + " originals not normally exposed: used for the focus check only");
        check("A has photos", n[0] >= 20 && n[1] >= 20);

        // ---------------- B) eyes open vs closed in otherwise identical bursts
        int bOk = 0, bN = 0, bSharpOk = 0, bSharpN = 0, bReason = 0, b3ok = 0, b3n = 0;
        for (int t = 0; t < 240; t++) {
            File bgFile = photos.get(rnd.nextInt(photos.size()));
            BufferedImage bg = load(bgFile, Scanner.QUALITY_EDGE);
            if (bg == null) continue;
            int nf = 1 + rnd.nextInt(3);
            Scene s = new Scene(bg, nf, open, rnd);
            int swap = rnd.nextInt(nf);
            String[] cl = closed.get(rnd.nextInt(closed.size()));
            BufferedImage good = s.render(-1, null), bad = s.render(swap, cl);
            boolean badSharper = t % 3 == 0;                 // the blinking shot is the crisper one
            if (badSharper) good = blur(good, 1);
            float[] fGood = s.detector(rnd, -1), fBad = s.detector(rnd, swap);
            PhotoQuality.Q qg = q(good, fGood), qb = q(bad, fBad);
            boolean win = PhotoQuality.best(Arrays.asList(qb, qg)) == 1;
            bN++; if (win) bOk++;
            if (badSharper) { bSharpN++; if (win) bSharpOk++; }
            if (win) for (String r : PhotoQuality.reasons(qg, qb)) if (r.startsWith("Eyes")) { bReason++; break; }
            if (nf >= 2 && t % 2 == 0) {                     // three shots: 0, 1 and 2 people blinking
                int other = (swap + 1) % nf;
                String[] cl2 = closed.get(rnd.nextInt(closed.size()));
                BufferedImage worse = s.render2(swap, cl, other, cl2);
                PhotoQuality.Q qw = q(worse, s.detector(rnd, -2));
                b3n++;
                if (PhotoQuality.best(Arrays.asList(qw, qb, qg)) == 2) b3ok++;
            }
        }
        System.out.printf(Locale.ROOT, "B) shot with everyone's eyes open kept: %d/%d (%.1f%%); when the blinking shot is sharper: %d/%d; reason says \"Eyes open\": %d/%d%n",
                bOk, bN, 100.0 * bOk / bN, bSharpOk, bSharpN, bReason, bOk);
        System.out.printf(Locale.ROOT, "B) three shots (0, 1, 2 people blinking): best kept %d/%d%n", b3ok, b3n);
        check("B eyes-open shot kept >= 90%", bOk >= 0.9 * bN);
        check("B also when the blinking shot is sharper >= 85%", bSharpOk >= 0.85 * bSharpN);
        check("B reasons mention eyes", bReason >= 0.9 * bOk);
        check("B three-shot groups >= 85%", b3ok >= 0.85 * b3n);

        // ---------------- C) end to end: scan + plan a phone with such bursts
        File dev = new File(work, "phone"), cam = new File(dev, "sdcard/DCIM/Camera");
        deleteTree(dev);
        cam.mkdirs(); new File(dev, "meta").mkdirs();
        Files.write(new File(dev, "meta/usagestats.json").toPath(), "{}".getBytes(StandardCharsets.UTF_8));
        long t0 = 1700000000000L;
        List<String[]> expect = new ArrayList<String[]>();
        int bursts = 0;
        for (int g = 0; g < 30; g++) {
            BufferedImage bg = load(photos.get(g % photos.size()), Scanner.QUALITY_EDGE);
            if (bg == null) continue;
            int nf = 1 + rnd.nextInt(2);
            Scene s = new Scene(bg, nf, open, rnd);
            int swap = rnd.nextInt(nf);
            String[] cl = closed.get(rnd.nextInt(closed.size()));
            String goodName = String.format(Locale.ROOT, "IMG_%04d_2.jpg", g), badName = String.format(Locale.ROOT, "IMG_%04d_1.jpg", g);
            File gf = new File(cam, goodName), bf = new File(cam, badName);
            ImageIO.write(s.render(swap, cl), "jpg", bf);
            ImageIO.write(s.render(-1, null), "jpg", gf);
            s.sidecar(bf, rnd, swap); s.sidecar(gf, rnd, -1);
            long tt = t0 + g * 3600_000L;                    // groups an hour apart, shots 2 s apart
            bf.setLastModified(tt); gf.setLastModified(tt + 2000);
            expect.add(new String[]{"DCIM/Camera/" + badName, "DCIM/Camera/" + goodName});
            bursts++;
        }
        JvmPlatform pf = new JvmPlatform(dev.getAbsolutePath());
        Scanner.Result r = Scanner.scan(pf, new Scanner.Progress() { public void update(String s, int d, int t) {} public boolean cancelled() { return false; } });
        UnitBuilder ub = new UnitBuilder(r, new Estimates(), 34);
        int found = 0, right = 0, why = 0;
        for (String[] e : expect) {
            String k = ub.det.burstExtraOf.get(e[0]), k2 = ub.det.burstExtraOf.get(e[1]);
            if (k != null || k2 != null) found++;
            if (e[1].equals(k)) {
                right++;
                for (com.storageplanner.core.Unit u : ub.units) if (u.id.equals(e[0]))
                    for (com.storageplanner.core.Option o : u.options) {
                        if (o.why.contains("eyes open")) { why++; break; }                       // "eyes open" or "eyes open wider"
                        if (o.action.equals("delete")) System.out.println("  note: kept the right shot, but the reason was: " + o.why);
                    }
            }
        }
        System.out.printf(Locale.ROOT, "C) end to end: %d/%d bursts found, eyes-open shot kept in %d, reason says \"eyes open\" in %d%n", found, bursts, right, why);
        int measured = 0; for (FileItem f : r.files) if (f.quality != null) measured++;
        check("C quality measured for every shot of a found burst", measured == 2 * found);
        check("C bursts found >= 90%", found >= 0.9 * bursts);
        check("C eyes-open shot kept >= 90% of found bursts", right >= 0.9 * found);
        check("C reason names eyes >= 85%", why >= 0.85 * right);    // misses = a closed eye the model took for open; the right shot was still kept

        System.out.println((checks - fails) + "/" + checks + " photo quality checks pass");
        if (fails > 0) System.exit(1);
    }

    /** A background with faces pasted at fixed places; any face can be swapped for a closed-eye face. */
    static final class Scene {
        final BufferedImage bg; final int nf;
        final String[][] who; final double[] x, y, scale;      // face crop placement
        Scene(BufferedImage bg, int nf, List<String[]> open, Random rnd) {
            this.bg = bg; this.nf = nf; who = new String[nf][]; x = new double[nf]; y = new double[nf]; scale = new double[nf];
            for (int i = 0; i < nf; i++) {
                who[i] = open.get(rnd.nextInt(open.size()));
                double ed = 28 + rnd.nextDouble() * 40;                      // eye distance in the photo, px
                scale[i] = ed / Double.parseDouble(who[i][5]);
                x[i] = (i + 0.5) * bg.getWidth() / nf; y[i] = bg.getHeight() * (0.35 + 0.2 * rnd.nextDouble());
            }
        }
        BufferedImage render(int swap, String[] cl) { return render2(swap, cl, -1, null); }
        BufferedImage render2(int s1, String[] c1, int s2, String[] c2) {
            BufferedImage out = new BufferedImage(bg.getWidth(), bg.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = out.createGraphics();
            g.drawImage(bg, 0, 0, null);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            for (int i = 0; i < nf; i++) {
                String[] f = i == s1 ? c1 : i == s2 ? c2 : who[i];
                try {
                    BufferedImage fi = ImageIO.read(new File(f[8]));
                    double sc = (scale[i] * Double.parseDouble(who[i][5])) / Double.parseDouble(f[5]);   // same eye distance as the original face
                    double fx = Double.parseDouble(f[3]), fy = Double.parseDouble(f[4]);
                    int dx = (int) Math.round(x[i] - fx * sc), dy = (int) Math.round(y[i] - fy * sc);
                    g.drawImage(fi, dx, dy, (int) Math.round(fi.getWidth() * sc), (int) Math.round(fi.getHeight() * sc), null);
                } catch (Exception e) { throw new RuntimeException(e); }
            }
            g.dispose();
            return out;
        }
        /** What a detector would report: eye midpoint and distance with ~6% error, score 0.75-0.95 (Faces.F layout). */
        float[] detector(Random rnd, int unused) { return com.storageplanner.tools.faces.Faces.fromMidpoints(midpoints(rnd)); }
        float[] midpoints(Random rnd) {
            float[] o = new float[nf * 4];
            for (int i = 0; i < nf; i++) {
                double d = scale[i] * Double.parseDouble(who[i][5]);
                o[i * 4] = (float) (x[i] + rnd.nextGaussian() * 0.06 * d);
                o[i * 4 + 1] = (float) (y[i] + rnd.nextGaussian() * 0.06 * d);
                o[i * 4 + 2] = (float) (d * (1 + rnd.nextGaussian() * 0.06));
                o[i * 4 + 3] = (float) (0.75 + 0.2 * rnd.nextDouble());
            }
            return o;
        }
        void sidecar(File photo, Random rnd, int unused) throws Exception {
            float[] f = midpoints(rnd);
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < nf; i++)
                b.append(f[i * 4] / bg.getWidth()).append(' ').append(f[i * 4 + 1] / bg.getHeight()).append(' ')
                 .append(f[i * 4 + 2] / bg.getWidth()).append(' ').append(f[i * 4 + 3]).append('\n');
            Files.write(new File(photo.getPath() + ".faces").toPath(), b.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    static PhotoQuality.Q q(BufferedImage im, float[] faces) {
        int w = im.getWidth(), h = im.getHeight();
        int[] px = im.getRGB(0, 0, w, h, null, 0, w), l = new int[w * h];
        for (int i = 0; i < px.length; i++) { int p = px[i]; l[i] = (299 * ((p >> 16) & 255) + 587 * ((p >> 8) & 255) + 114 * (p & 255)) / 1000; }
        return PhotoQuality.analyse(l, w, h, faces);
    }

    static BufferedImage load(File f, int edge) {
        try {
            BufferedImage im = ImageIO.read(f);
            if (im == null) return null;
            double s = Math.min(1.0, (double) edge / Math.max(im.getWidth(), im.getHeight()));
            int w = (int) Math.round(im.getWidth() * s), h = (int) Math.round(im.getHeight() * s);
            BufferedImage o = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = o.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(im, 0, 0, w, h, null); g.dispose();
            return o;
        } catch (Exception e) { return null; }
    }

    /** Box blur of the given radius (separable), like a slightly missed focus or hand shake. */
    static BufferedImage blur(BufferedImage im, int r) {
        int w = im.getWidth(), h = im.getHeight();
        int[] p = im.getRGB(0, 0, w, h, null, 0, w), t = new int[p.length];
        for (int pass = 0; pass < 2; pass++) {
            int[] src = pass == 0 ? p : t, dst = pass == 0 ? t : p;
            for (int y = 0; y < h; y++)
                for (int x = 0; x < w; x++) {
                    int rs = 0, gs = 0, bs = 0, n = 0;
                    for (int k = -r; k <= r; k++) {
                        int xx = pass == 0 ? Math.min(w - 1, Math.max(0, x + k)) : x, yy = pass == 0 ? y : Math.min(h - 1, Math.max(0, y + k));
                        int c = src[yy * w + xx]; rs += (c >> 16) & 255; gs += (c >> 8) & 255; bs += c & 255; n++;
                    }
                    dst[y * w + x] = 0xFF000000 | (rs / n << 16) | (gs / n << 8) | (bs / n);
                }
        }
        BufferedImage o = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        o.setRGB(0, 0, w, h, p, 0, w);
        return o;
    }

    /** Exposure change: linear gain, then a gamma (>1 darkens mid-tones), clipped like a sensor. */
    static BufferedImage gain(BufferedImage im, double k, double gamma) {
        int w = im.getWidth(), h = im.getHeight();
        int[] p = im.getRGB(0, 0, w, h, null, 0, w);
        int[] lut = new int[256];
        for (int i = 0; i < 256; i++) lut[i] = (int) Math.max(0, Math.min(255, Math.round(255 * Math.pow(Math.min(1.0, i * k / 255.0), gamma))));
        for (int i = 0; i < p.length; i++) { int c = p[i]; p[i] = 0xFF000000 | (lut[(c >> 16) & 255] << 16) | (lut[(c >> 8) & 255] << 8) | lut[c & 255]; }
        BufferedImage o = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        o.setRGB(0, 0, w, h, p, 0, w);
        return o;
    }

    static void collect(File d, List<File> out) {
        File[] fs = d.listFiles(); if (fs == null) return;
        Arrays.sort(fs);
        for (File f : fs) if (f.isDirectory()) collect(f, out); else if (f.getName().toLowerCase(Locale.ROOT).endsWith(".jpg")) out.add(f);
    }

    static void deleteTree(File f) {
        File[] k = f.listFiles();
        if (k != null) for (File x : k) deleteTree(x);
        if (f.exists() && !f.delete()) throw new RuntimeException("cannot clean " + f);
    }
}
