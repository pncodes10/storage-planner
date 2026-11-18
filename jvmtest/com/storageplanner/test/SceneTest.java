package com.storageplanner.test;

import com.storageplanner.core.*;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import javax.imageio.ImageIO;

/**
 * The scene network, the scene, the aesthetic score, smiles and faces turned to the camera:
 *  - SceneNet gives Keras's numbers for the same weights (tools/scenemodel/parity.py) on a made-up input;
 *  - which ImageNet classes count for which scene, and how a scene is decided (documents, selfies, sky, groups);
 *  - the sky measure on drawn pictures;
 *  - head turn from the face points; smile is unknown without mouth positions;
 *  - a scan measures scene and aesthetic score on photos only, keeps them for unchanged photos, and they survive saving;
 *  - the four new tags.
 * With arguments it also checks the trained models on held-out data kept outside the repository:
 *   SceneTest smile_heldout.csv aes_heldout.tsv
 * (smile: Java gives Python's probabilities, and the accuracy; aesthetic: Java's score on each picture is Python's, and how
 * closely it follows NIMA).
 */
public final class SceneTest {
    static int checks, fails;

    static void check(String what, boolean ok) {
        checks++;
        if (!ok) { fails++; System.out.println("FAIL " + what); }
    }

    /** The made-up input of tools/scenemodel/parity.py (RGB, -1..1). */
    static float[] pattern() {
        int n = SceneNet.SIZE * SceneNet.SIZE;
        float[] x = new float[3 * n];
        for (int c = 0; c < 3; c++)
            for (int y = 0; y < SceneNet.SIZE; y++)
                for (int i = 0; i < SceneNet.SIZE; i++)
                    x[c * n + y * SceneNet.SIZE + i] = (float) (Math.sin(0.05 * i * (c + 1) + 0.3 * c) * Math.cos(0.07 * y - 0.2 * c) * 0.9);
        return x;
    }

    public static void main(String[] args) throws Exception {
        SceneNet net = new JvmPlatform(".").sceneNet();
        check("the scene network loads (app/src/main/assets/scene/scene.bin)", net != null);
        if (net == null) { System.out.println((checks - fails) + "/" + checks + " scene checks pass"); System.exit(1); }

        // ---- the same numbers as Keras with the same weights
        SceneNet.Out o = net.runInput(pattern());
        int[] top = top(o.logits, 5);
        check("top-5 classes as Keras: " + Arrays.toString(top), Arrays.equals(top, new int[]{723, 599, 398, 392, 987}));
        float[] wantLogits = {6.5685f, 4.8282f, 4.4508f, 4.3422f, 4.2699f};
        float[] wantFeat = {0f, 0f, 0.8199f, 0.6563f, 1.5853f, -0.3648f, -0.3572f, -0.2616f};
        double worst = 0;
        for (int i = 0; i < 5; i++) worst = Math.max(worst, Math.abs(o.logits[top[i]] - wantLogits[i]));
        for (int i = 0; i < 8; i++) worst = Math.max(worst, Math.abs(o.features[i] - wantFeat[i]));
        check("logits and features as Keras (worst difference " + worst + ")", worst < 2e-3);
        float[] p = SceneNet.probabilities(o.logits);
        double sum = 0; for (float v : p) sum += v;
        check("probabilities add up to 1", Math.abs(sum - 1) < 1e-4);
        // the same picture twice: the same answer (no state kept between pictures)
        SceneNet.Out o2 = net.runInput(pattern());
        check("running twice gives the same numbers", Arrays.equals(o.logits, o2.logits) && Arrays.equals(o.features, o2.features));
        int[] px = drawn(320, 240, 1);
        long t0 = System.nanoTime();
        SceneNet.Out o3 = net.run(px, 320, 240);
        long ms = (System.nanoTime() - t0) / 1000000;
        check("runs on a picture of any size (" + ms + " ms here)", o3.features.length == SceneNet.FEATURES && !Float.isNaN(o3.logits[0]));
        check("a tiny picture works too", net.run(new int[]{0xFF336699, 0xFF336699, 0xFF336699, 0xFF336699}, 2, 2).logits.length == 1000);

        // ---- scene groups
        int[] owner = new int[1000];
        boolean twice = false;
        for (int g = 0; g < Scene.GROUPS.length; g++) {
            float[] one = new float[1000];
            for (int c = 0; c < 1000; c++) {
                Arrays.fill(one, 0); one[c] = 1;
                if (Scene.shares(one)[g] > 0) { if (owner[c] != 0) twice = true; owner[c] = g + 1; }
            }
        }
        check("no ImageNet class counts for two scenes", !twice);
        check("dogs and cats are pets", owner[207] == 3 && owner[281] == 3 && owner[285] == 3);   // golden retriever, tabby, Egyptian cat
        check("pizza, plate and fruit are food; hay is not", owner[963] == 1 && owner[923] == 1 && owner[954] == 1 && owner[958] == 0);
        check("cars, buses, planes, boats are vehicles", owner[817] == 4 && owner[779] == 4 && owner[404] == 4 && owner[814] == 4);
        check("landscapes, birds, flowers are nature; a lion is not a pet", owner[970] == 2 && owner[978] == 2 && owner[985] == 2 && owner[15] == 2 && owner[291] == 2);
        check("a laptop and a church are nothing", owner[620] == 0 && owner[497] == 0);

        FileItem f = new FileItem("DCIM/Camera/a.jpg", "/x/a.jpg", 1, 1);
        f.category = PhotoCategory.PHOTO;
        check("not looked at yet: scene unknown", Scene.of(f) == null);
        f.scene = new float[]{0.05f, 0.1f, 0.6f, 0.05f, 0f};
        check("a group with 60%: pet", Scene.PET.equals(Scene.of(f)));
        f.scene = new float[]{0.3f, 0.35f, 0.1f, 0.05f, 0.2f};
        check("no group reaches 40%: other", Scene.OTHER.equals(Scene.of(f)));
        f.scene = new float[]{0.1f, 0.2f, 0f, 0f, 0.6f};
        check("60% sky: sky", Scene.SKY.equals(Scene.of(f)));
        f.scene = new float[]{0.1f, 0.5f, 0f, 0f, 0.6f};
        check("a landscape with sky: nature first", Scene.NATURE.equals(Scene.of(f)));
        f.faceSize = 0.15f;
        check("a face close to the camera: selfie", Scene.SELFIE.equals(Scene.of(f)));
        f.faceSize = 0.03f;
        check("a small face: not a selfie", Scene.NATURE.equals(Scene.of(f)));
        f.category = PhotoCategory.RECEIPT; f.scene = null;
        check("receipts are documents without the network", Scene.DOCUMENT.equals(Scene.of(f)));
        f.category = PhotoCategory.ID;
        check("ID cards are documents", Scene.DOCUMENT.equals(Scene.of(f)));

        // ---- sky
        check("blue sky over a textured field: about half sky (" + f2(Scene.sky(sky(96, 72, true, 0x4A8FD8), 96, 72)) + ")",
                Math.abs(Scene.sky(sky(96, 72, true, 0x4A8FD8), 96, 72) - 0.5) < 0.12);
        check("a sunset sky counts", Scene.sky(sky(96, 72, true, 0xF09050), 96, 72) > 0.35);
        check("a plain white wall is not sky", Scene.sky(flat(96, 72, 0xF2F2F0), 96, 72) == 0);
        check("blue at the bottom only is not sky", Scene.sky(sky(96, 72, false, 0x4A8FD8), 96, 72) == 0);
        check("a textured blue cloth is not sky", Scene.sky(noisy(96, 72, 0x3050A0, 40), 96, 72) < 0.1);

        // ---- aesthetic score
        double a1 = Aesthetic.score(o3.features);
        check("aesthetic score is between 1 and 10 (" + f2(a1) + ")", a1 >= 1 && a1 <= 10);
        check("words for scores", Aesthetic.words(3).equals("poor") && Aesthetic.words(4.6).equals("average") && Aesthetic.words(6.5).equals("very good"));

        // ---- head turned to the camera, smile unknown without mouth positions
        float[] face = face(100, 100, 40, 0, 0.55);
        check("a face looking straight ahead is turned to the camera", Smile.facing(face, 0));
        check("nose a third of the eye distance to the side: turned away", !Smile.facing(face(100, 100, 40, 0.33, 0.55), 0));
        check("head tipped far back (nose close to the eyes): not facing", !Smile.facing(face(100, 100, 40, 0, 0.15), 0));
        check("a tilted head looking ahead still faces the camera", Smile.facing(rotate(face, 100, 100, 0.5), 0));
        float[] noMouth = Faces.fromMidpoints(new float[]{100, 100, 40, 0.9f});
        check("Android's own detector gives no mouth: smile and turn not known", !Faces.hasMouth(noMouth, 0) && Double.isNaN(Smile.probability(new int[200 * 200], 200, 200, noMouth, 0)) && !Smile.facing(noMouth, 0));
        double sp = Smile.probability(gray(200, 200), 200, 200, face, 0);
        check("smile probability is a probability (" + f2(sp) + ")", sp >= 0 && sp <= 1);
        check("Faces.F carries nose and mouth", Faces.F == 14);

        // ---- a scan: photos get a scene and a score, are not measured again, and the numbers survive saving
        scan();

        // ---- tags
        FileItem t = new FileItem("DCIM/Camera/b.jpg", "/x/b.jpg", 1000, 1);
        t.area = "camera"; t.type = "jpeg"; t.category = PhotoCategory.PEOPLE; t.faces = 2; t.eyeFaces = 2; t.eyesOpen = 2; t.smiling = 1; t.looking = 2;
        t.scene = new float[]{0f, 0.7f, 0f, 0f, 0f}; t.aesthetic = 5.46f; t.faceSize = 0.04f;
        Tags.T tg = Tags.of(t, Tags.context(new Scanner.Result(), new UnitBuilder.Detections()), 2);
        check("scene tag", Scene.NATURE.equals(tg.textTag(Tags.SCENE)));
        check("aesthetic tag (one decimal)", tg.numTag(Tags.AESTHETIC) == 5.5);
        check("smiling and looking tags", tg.numTag(Tags.SMILING) == 1 && tg.numTag(Tags.LOOKING) == 2);
        t.eyeFaces = 0; t.smiling = -1; t.looking = -1; t.aesthetic = -1; t.scene = null;
        tg = Tags.of(t, Tags.context(new Scanner.Result(), new UnitBuilder.Detections()), 2);
        check("unknown when not measured", tg.textTag(Tags.SCENE) == null && tg.numTag(Tags.AESTHETIC) == Tags.UNKNOWN && tg.numTag(Tags.SMILING) == Tags.UNKNOWN);
        check("scene choices for rules", Tags.choices(Tags.SCENE).size() == Scene.ALL.length);

        if (args.length > 0) smileHeldOut(args[0]);
        if (args.length > 1) aestheticHeldOut(net, args[1]);
        System.out.println((checks - fails) + "/" + checks + " scene checks pass");
        if (fails > 0) System.exit(1);
    }

    // ------------------------------------------------------------------ a scan on a made-up phone
    static void scan() throws Exception {
        File dev = Files.createTempDirectory("scene").toFile(), sd = new File(dev, "sdcard");
        Files.createDirectories(new File(dev, "meta").toPath());
        Files.write(new File(dev, "meta/usagestats.json").toPath(), "{}".getBytes(StandardCharsets.UTF_8));
        for (int i = 0; i < 3; i++) write(new File(sd, "DCIM/Camera/IMG_" + i + ".jpg"), drawn(640, 480, i));
        write(new File(sd, "Pictures/Screenshots/Screenshot_1.png"), flat(540, 1200, 0xFFFFFFFF));
        JvmPlatform pf = new JvmPlatform(dev.getPath());
        Scanner.Progress quiet = new Scanner.Progress() { public void update(String s, int d, int tt) {} public boolean cancelled() { return false; } };
        Scanner.Result r = Scanner.scan(pf, quiet, new Scanner.Options());
        int measured = 0, shots = 0;
        for (FileItem f : r.files) {
            if (f.area.equals("camera") && f.sceneVersion > 0 && f.scene != null && f.scene.length == 5 && f.aesthetic >= 1 && f.aesthetic <= 10) measured++;
            if (f.area.equals("screenshot") && f.scene != null) shots++;
        }
        check("a scan measures every camera photo (" + measured + " of 3)", measured == 3);
        check("screenshots are not looked at by the scene network", shots == 0);
        // saved and read back
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        ResultStore.Saved sv = new ResultStore.Saved(); sv.result = r; sv.est = new Estimates();
        ResultStore.write(bo, sv);
        Scanner.Result back = ResultStore.read(new ByteArrayInputStream(bo.toByteArray())).result;
        boolean same = back.files.size() == r.files.size();
        for (int i = 0; same && i < r.files.size(); i++) {
            FileItem a = r.files.get(i), b = back.files.get(i);
            same = Arrays.equals(a.scene, b.scene) && a.aesthetic == b.aesthetic && a.sceneVersion == b.sceneVersion
                    && a.smiling == b.smiling && a.looking == b.looking && a.faceSize == b.faceSize;
        }
        check("scene, score, smiles and face size survive saving", same);
        // unchanged photos keep their numbers and are not measured again
        Scanner.Options opt = new Scanner.Options();
        opt.previous = new java.util.HashMap<String, FileItem>();
        for (FileItem f : back.files) opt.previous.put(f.rel, f);
        final List<String> steps = new ArrayList<String>();
        Scanner.Progress watch = new Scanner.Progress() { public void update(String s, int d, int tt) { steps.add(s); } public boolean cancelled() { return false; } };
        Scanner.Result again = Scanner.scan(pf, watch, opt);
        boolean kept = true;
        for (FileItem f : again.files) if (f.area.equals("camera")) kept &= f.scene != null && f.sceneVersion > 0;
        check("unchanged photos keep scene and score", kept);
        check("and are not looked at again", !steps.contains("Looking at what photos show"));
        Scanner.Options none = new Scanner.Options();
        none.sceneBudgetMs = 0;
        Scanner.Result skip = Scanner.scan(pf, quiet, none);
        boolean noScene = true;
        for (FileItem f : skip.files) noScene &= f.scene == null;
        check("no time for scenes: left for the next scan", noScene);
    }

    // ------------------------------------------------------------------ held-out data (outside the repository)
    static void smileHeldOut(String path) throws Exception {
        List<String> lines = Files.readAllLines(new File(path).toPath(), StandardCharsets.UTF_8);
        int n = 0, right = 0; double worst = 0;
        java.lang.reflect.Method pr = Class.forName("com.storageplanner.core.Smile").getDeclaredMethod("predict", double[].class);
        pr.setAccessible(true);
        for (String l : lines) {
            String[] c = l.split(",");
            double[] x = new double[c.length - 3];
            for (int i = 0; i < x.length; i++) x[i] = Double.parseDouble(c[i + 3]);
            double pj = (Double) pr.invoke(null, (Object) x), py = Double.parseDouble(c[2]);
            worst = Math.max(worst, Math.abs(pj - py));
            if ((pj >= Smile.SMILING) == c[0].equals("1")) right++;
            n++;
        }
        System.out.println(String.format("smile, held-out faces: %d, accuracy %.1f%%, Java vs Python worst difference %.1e", n, 100.0 * right / n, worst));
        check("smile: Java computes Python's probabilities", worst < 1e-4);
        check("smile: at least 88% right on held-out faces", right >= 0.88 * n);
    }

    static void aestheticHeldOut(SceneNet net, String path) throws Exception {
        List<String> lines = Files.readAllLines(new File(path).toPath(), StandardCharsets.UTF_8);
        double worst = 0; int n = 0;
        double[] ours = new double[lines.size()], nima = new double[lines.size()];
        for (String l : lines) {
            String[] c = l.split("\t");
            BufferedImage im = ImageIO.read(new File(c[0]));
            if (im == null) continue;
            int w = im.getWidth(), h = im.getHeight();
            double s = Aesthetic.score(net.run(im.getRGB(0, 0, w, h, null, 0, w), w, h).features);
            worst = Math.max(worst, Math.abs(s - Math.max(1, Math.min(10, Double.parseDouble(c[1])))));
            ours[n] = s; nima[n] = Double.parseDouble(c[2]); n++;
        }
        double r = pearson(Arrays.copyOf(ours, n), Arrays.copyOf(nima, n));
        System.out.println(String.format("aesthetic, held-out pictures: %d, Java vs Python worst difference %.1e, Pearson with NIMA %.2f", n, worst, r));
        check("aesthetic: Java gives Python's score", worst < 1e-3);
        check("aesthetic: follows NIMA (Pearson >= 0.7)", r >= 0.7);
    }

    // ------------------------------------------------------------------ helpers
    static int[] top(float[] v, int k) {
        Integer[] i = new Integer[v.length];
        for (int j = 0; j < v.length; j++) i[j] = j;
        final float[] vv = v;
        Arrays.sort(i, new java.util.Comparator<Integer>() { public int compare(Integer a, Integer b) { return Float.compare(vv[b], vv[a]); } });
        int[] o = new int[k];
        for (int j = 0; j < k; j++) o[j] = i[j];
        return o;
    }

    /** A face at (x, y) with eye distance d, nose `side` eye distances to the side and `down` of the way to the mouth (Faces layout). */
    static float[] face(float x, float y, float d, double side, double down) {
        float mouth = 0.95f * d;
        float[] f = new float[Faces.F];
        f[0] = x; f[1] = y; f[2] = d; f[3] = 0.95f;
        f[4] = x - d / 2; f[5] = y; f[6] = x + d / 2; f[7] = y;
        f[8] = (float) (x + side * d); f[9] = (float) (y + down * mouth);
        f[10] = x - 0.4f * d; f[11] = y + mouth; f[12] = x + 0.4f * d; f[13] = y + mouth;
        return f;
    }

    static float[] rotate(float[] f, float cx, float cy, double a) {
        float[] g = f.clone();
        for (int k = 4; k < Faces.F; k += 2) {
            double dx = f[k] - cx, dy = f[k + 1] - cy;
            g[k] = (float) (cx + dx * Math.cos(a) - dy * Math.sin(a)); g[k + 1] = (float) (cy + dx * Math.sin(a) + dy * Math.cos(a));
        }
        return g;
    }

    static int[] gray(int w, int h) { int[] l = new int[w * h]; Random r = new Random(5); for (int i = 0; i < l.length; i++) l[i] = 90 + r.nextInt(60); return l; }

    static int[] flat(int w, int h, int rgb) { int[] p = new int[w * h]; Arrays.fill(p, 0xFF000000 | rgb); return p; }

    static int[] noisy(int w, int h, int rgb, int amp) {
        Random r = new Random(9);
        int[] p = new int[w * h];
        for (int i = 0; i < p.length; i++) {
            int d = r.nextInt(2 * amp + 1) - amp;
            int R = clamp(((rgb >> 16) & 255) + d), G = clamp(((rgb >> 8) & 255) + d), B = clamp((rgb & 255) + d);
            p[i] = 0xFF000000 | R << 16 | G << 8 | B;
        }
        return p;
    }

    /** Smooth sky (a gentle gradient) in one half, textured green field in the other; skyOnTop puts the sky at the top. */
    static int[] sky(int w, int h, boolean skyOnTop, int rgb) {
        int[] p = noisy(w, h, 0x407030, 45);
        for (int y = 0; y < h / 2; y++)
            for (int x = 0; x < w; x++) {
                int yy = skyOnTop ? y : h - 1 - y, k = y / 6;
                p[yy * w + x] = 0xFF000000 | clamp(((rgb >> 16) & 255) + k) << 16 | clamp(((rgb >> 8) & 255) + k) << 8 | clamp((rgb & 255) + k);
            }
        return p;
    }

    /** A colourful made-up photo: soft shapes on a gradient. */
    static int[] drawn(int w, int h, int seed) {
        Random r = new Random(seed);
        int[] p = new int[w * h];
        double cx = r.nextDouble() * w, cy = r.nextDouble() * h, rad = 40 + r.nextDouble() * 80;
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                double d = Math.hypot(x - cx, y - cy);
                int R = clamp((int) (60 + 150.0 * y / h + (d < rad ? 80 : 0) + r.nextInt(12)));
                int G = clamp((int) (90 + 100.0 * x / w - (d < rad ? 50 : 0) + r.nextInt(12)));
                int B = clamp((int) (160 - 80.0 * y / h + r.nextInt(12)));
                p[y * w + x] = 0xFF000000 | R << 16 | G << 8 | B;
            }
        return p;
    }

    static void write(File f, int[] px) throws Exception {
        f.getParentFile().mkdirs();
        int w = px.length == 540 * 1200 ? 540 : 640, h = px.length / w;
        BufferedImage b = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        b.setRGB(0, 0, w, h, px, 0, w);
        ImageIO.write(b, f.getName().endsWith(".png") ? "png" : "jpg", f);
    }

    static int clamp(int v) { return Math.max(0, Math.min(255, v)); }

    static double pearson(double[] a, double[] b) {
        double ma = 0, mb = 0; for (int i = 0; i < a.length; i++) { ma += a[i]; mb += b[i]; } ma /= a.length; mb /= b.length;
        double sab = 0, saa = 0, sbb = 0;
        for (int i = 0; i < a.length; i++) { sab += (a[i] - ma) * (b[i] - mb); saa += (a[i] - ma) * (a[i] - ma); sbb += (b[i] - mb) * (b[i] - mb); }
        return sab / Math.sqrt(saa * sbb);
    }

    static String f2(double v) { return String.format("%.2f", v); }
}
