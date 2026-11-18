import com.storageplanner.core.*;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Random;

import javax.imageio.ImageIO;

/**
 * Writes the smile model's training rows: for each face in the SMILEs set, the app's own face detector (YuNet) finds
 * the eyes, nose and mouth corners, and Smile.features() turns them into the model's input, exactly as on the phone.
 * Each face is used at its own size and at 2 smaller sizes (eyes 14-60 px apart, found again by the detector at that
 * size, with sensor-like noise), plus a mirrored copy.
 * Usage: java SmileFeatures <SMILEs dir> <yunet.bin> <out.csv>
 * Rows: label (1 smiling), face id, size (eye distance in px), then Smile.NF features.
 */
public final class SmileFeatures {
    public static void main(String[] a) throws Exception {
        FaceNet net = FaceNet.load(Files.readAllBytes(new File(a[1]).toPath()));
        PrintWriter out = new PrintWriter(a[2], "UTF-8");
        Random rnd = new Random(1);
        int id = 0, kept = 0, missed = 0;
        for (String set : new String[]{"positives/positives7", "negatives/negatives7"}) {
            File[] fs = new File(a[0], set).listFiles();
            Arrays.sort(fs);
            int label = set.startsWith("pos") ? 1 : 0;
            for (File f : fs) {
                id++;
                BufferedImage g = ImageIO.read(f);
                if (g == null) continue;
                // the faces are 64 x 64 crops: shown 3x bigger on a plain background, as a face in a photo
                int big = 288, s = 192, off = 48;
                BufferedImage c = new BufferedImage(big, big, BufferedImage.TYPE_INT_RGB);
                Graphics2D gr = c.createGraphics();
                gr.setColor(new java.awt.Color(120, 120, 120)); gr.fillRect(0, 0, big, big);
                gr.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                gr.drawImage(g, off, off, s, s, null); gr.dispose();
                int[] px = c.getRGB(0, 0, big, big, null, 0, big);
                float[] fc = best(net, px, big, big);
                if (fc == null) { missed++; continue; }
                kept++;
                row(out, label, id, px, big, big, fc, false);
                row(out, label, id, px, big, big, fc, true);
                for (int k = 0; k < 2; k++) {
                    double target = 14 + rnd.nextDouble() * 46, sc = Math.min(1, target / fc[2]);
                    int w2 = Math.max(32, (int) Math.round(big * sc));
                    int[] p2 = shrink(px, big, big, w2, w2);
                    double noise = rnd.nextDouble() < 0.5 ? 0 : 2 + rnd.nextDouble() * 6;
                    if (noise > 0) for (int i = 0; i < p2.length; i++) {
                        int v = (int) Math.round((p2[i] & 255) + rnd.nextGaussian() * noise);
                        v = Math.max(0, Math.min(255, v));
                        p2[i] = 0xFF000000 | v << 16 | v << 8 | v;
                    }
                    float[] f2 = best(net, p2, w2, w2);
                    if (f2 != null) row(out, label, id, p2, w2, w2, f2, rnd.nextBoolean());
                }
            }
        }
        out.close();
        System.out.println("faces " + kept + ", detector missed " + missed);
    }

    static float[] best(FaceNet net, int[] px, int w, int h) {
        float[] f = Faces.detect(net, px, w, h);
        float[] b = null;
        for (int i = 0; i + Faces.F <= f.length; i += Faces.F)
            if (f[i + 3] >= 0.6 && Faces.hasMouth(f, i) && (b == null || f[i + 2] > b[2])) b = Arrays.copyOfRange(f, i, i + Faces.F);
        return b;
    }

    static void row(PrintWriter out, int label, int id, int[] px, int w, int h, float[] f, boolean mirror) {
        int[] l = new int[px.length];
        for (int i = 0; i < px.length; i++) { int p = px[i]; l[i] = (299 * ((p >> 16) & 255) + 587 * ((p >> 8) & 255) + 114 * (p & 255)) / 1000; }
        double[] x = Smile.features(l, w, h, f, 0, 0, 0, 1, mirror);
        StringBuilder b = new StringBuilder();
        b.append(label).append(',').append(id).append(',').append(String.format("%.1f", f[2]));
        for (double v : x) b.append(',').append((float) v);
        out.println(b);
    }

    static int[] shrink(int[] px, int w, int h, int sw, int sh) {
        BufferedImage s = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        s.setRGB(0, 0, w, h, px, 0, w);
        BufferedImage d = new BufferedImage(sw, sh, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = d.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(s.getScaledInstance(sw, sh, java.awt.Image.SCALE_AREA_AVERAGING), 0, 0, null); g.dispose();
        return d.getRGB(0, 0, sw, sh, null, 0, sw);
    }
}
