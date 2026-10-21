package com.storageplanner.test.tools;

import com.storageplanner.tools.eyes.EyeState;

import java.awt.image.BufferedImage;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import javax.imageio.ImageIO;

/**
 * Eye open/closed model on held-out real faces (never seen in training).
 *  1) parity: the Java port gives the same probabilities as the training script on identical pixels;
 *  2) accuracy with the JPEG decoded by Java and with eye positions disturbed the way a phone's
 *     face detector may get them wrong (0%, 6%, 12% of the eye distance).
 * usage: EyeStateTest heldout.tsv
 */
public final class EyeStateTest {
    public static void main(String[] a) throws Exception {
        List<String[]> rows = new ArrayList<String[]>();
        for (String line : Files.readAllLines(new File(a[0]).toPath(), StandardCharsets.UTF_8))
            if (!line.isEmpty()) rows.add(line.split("\t"));
        int fails = 0;
        double maxDiff = 0;
        for (String[] r : rows) {
            int[] dims = new int[2];
            int[] l = pgm(r[2], dims);
            double mx = Double.parseDouble(r[3]), my = Double.parseDouble(r[4]), d = Double.parseDouble(r[5]);
            double pa = EyeState.eye(l, dims[0], dims[1], mx - d / 2, my, d, false);
            double pb = EyeState.eye(l, dims[0], dims[1], mx + d / 2, my, d, true);
            maxDiff = Math.max(maxDiff, Math.max(Math.abs(pa - Double.parseDouble(r[6])), Math.abs(pb - Double.parseDouble(r[7]))));
        }
        System.out.printf("parity with training script: max |dp| = %.2e over %d faces%n", maxDiff, rows.size());
        if (maxDiff > 1e-5) { System.out.println("FAIL parity"); fails++; }

        Random rnd = new Random(11);
        for (double jit : new double[]{0, 0.06, 0.12}) {
            double[] p = new double[rows.size()]; int[] y = new int[rows.size()];
            for (int i = 0; i < rows.size(); i++) {
                String[] r = rows.get(i);
                BufferedImage im = ImageIO.read(new File(r[8]));
                int w = im.getWidth(), h = im.getHeight();
                int[] px = im.getRGB(0, 0, w, h, null, 0, w), l = new int[w * h];
                for (int k = 0; k < px.length; k++) { int c = px[k]; l[k] = (299 * ((c >> 16) & 255) + 587 * ((c >> 8) & 255) + 114 * (c & 255)) / 1000; }
                double d = Double.parseDouble(r[5]);
                double mx = Double.parseDouble(r[3]) + rnd.nextGaussian() * jit * d, my = Double.parseDouble(r[4]) + rnd.nextGaussian() * jit * d;
                double dd = d * (1 + rnd.nextGaussian() * jit);
                p[i] = EyeState.openProbability(l, w, h, mx, my, dd);
                y[i] = Integer.parseInt(r[1]);
            }
            int ok = 0, nc = 0, cc = 0, no = 0, oc = 0;
            for (int i = 0; i < p.length; i++) {
                if ((p[i] >= 0.5) == (y[i] == 1)) ok++;
                if (y[i] == 0) { nc++; if (p[i] < 0.5) cc++; } else { no++; if (p[i] >= 0.5) oc++; }
            }
            double auc = auc(p, y), acc = (double) ok / p.length;
            System.out.printf("eye position error %2.0f%%: accuracy %.3f  AUC %.4f  closed found %d/%d  open found %d/%d%n",
                    jit * 100, acc, auc, cc, nc, oc, no);
            double minAcc = jit == 0 ? 0.95 : jit < 0.1 ? 0.92 : 0.85;
            if (acc < minAcc) { System.out.println("FAIL accuracy below " + minAcc); fails++; }
        }
        System.out.println(fails == 0 ? "eye-state checks pass" : fails + " eye-state checks FAILED");
        if (fails > 0) System.exit(1);
    }

    static double auc(double[] p, int[] y) {
        long pos = 0, neg = 0; double s = 0;
        Integer[] idx = new Integer[p.length];
        for (int i = 0; i < idx.length; i++) idx[i] = i;
        final double[] pp = p;
        Arrays.sort(idx, new java.util.Comparator<Integer>() { public int compare(Integer a, Integer b) { return Double.compare(pp[a], pp[b]); } });
        double rank = 0;
        for (int i = 0; i < idx.length; ) {                   // average ranks for ties
            int j = i; while (j < idx.length && pp[idx[j]] == pp[idx[i]]) j++;
            double avg = (i + 1 + j) / 2.0;
            for (int k = i; k < j; k++) if (y[idx[k]] == 1) { s += avg; pos++; } else neg++;
            i = j;
        }
        return (s - pos * (pos + 1) / 2.0) / (pos * (double) neg);
    }

    static int[] pgm(String path, int[] dims) throws Exception {
        DataInputStream in = new DataInputStream(new FileInputStream(path));
        try {
            String magic = token(in); int w = Integer.parseInt(token(in)), h = Integer.parseInt(token(in)); token(in);
            if (!magic.equals("P5")) throw new IllegalArgumentException(path);
            byte[] b = new byte[w * h]; in.readFully(b);
            int[] l = new int[w * h];
            for (int i = 0; i < l.length; i++) l[i] = b[i] & 255;
            dims[0] = w; dims[1] = h;
            return l;
        } finally { in.close(); }
    }

    private static String token(DataInputStream in) throws Exception {
        StringBuilder sb = new StringBuilder(); int c;
        while ((c = in.read()) != -1) { if (Character.isWhitespace(c)) { if (sb.length() > 0) break; } else sb.append((char) c); }
        return sb.toString();
    }
}
