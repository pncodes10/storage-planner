package com.storageplanner.test;

import com.storageplanner.core.FaceNet;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * The pure-Java YuNet gives the same faces as OpenCV's FaceDetectorYN (recorded by
 * tools/facemodel/expected_faces.py): same number of faces, boxes and landmarks within 1 px, scores within 0.01.
 * usage: FaceNetTest <yunet.bin> <dir with expected.tsv and the pictures>
 */
public final class FaceNetTest {
    public static void main(String[] a) throws Exception {
        FaceNet net = FaceNet.load(Files.readAllBytes(new File(a[0]).toPath()));
        File dir = new File(a[1]);
        int fails = 0, faces = 0; double worstPx = 0, worstScore = 0; long ms = 0; long px = 0;
        for (String line : Files.readAllLines(new File(dir, "expected.tsv").toPath(), StandardCharsets.UTF_8)) {
            String[] t = line.split("\t", -1);
            BufferedImage im = ImageIO.read(new File(dir, t[0]));
            int w = im.getWidth(), h = im.getHeight();
            int[] argb = im.getRGB(0, 0, w, h, null, 0, w);
            long t0 = System.nanoTime();
            List<float[]> got = net.detect(argb, w, h, 0.5f, 0.3f);
            ms += (System.nanoTime() - t0) / 1000000; px += (long) w * h;
            int want = Integer.parseInt(t[1]);
            if (got.size() != want) { fails++; System.out.println("FAIL " + t[0] + ": " + got.size() + " faces, OpenCV " + want); continue; }
            if (want == 0) continue;
            String[] ex = t[2].split(";");
            boolean[] used = new boolean[got.size()];
            for (String e : ex) {
                String[] v = e.split(",");
                double best = 1e9; int bi = -1;
                for (int k = 0; k < got.size(); k++) {
                    if (used[k]) continue;
                    double d = 0;
                    for (int j = 0; j < 14; j++) d = Math.max(d, Math.abs(got.get(k)[j] - Double.parseDouble(v[j])));
                    if (d < best) { best = d; bi = k; }
                }
                used[bi] = true; faces++;
                double ds = Math.abs(got.get(bi)[14] - Double.parseDouble(v[14]));
                worstPx = Math.max(worstPx, best); worstScore = Math.max(worstScore, ds);
                if (best > 1.0 || ds > 0.01) { fails++; System.out.printf("FAIL %s: face off by %.2f px, score by %.4f%n", t[0], best, ds); }
            }
        }
        System.out.printf("%d faces match OpenCV: worst landmark/box difference %.3f px, worst score difference %.5f; %.0f ms per megapixel%n",
                faces, worstPx, worstScore, ms / (px / 1e6));
        System.out.println(fails == 0 ? "face detector checks pass" : fails + " face detector checks FAILED");
        if (fails > 0) System.exit(1);
    }
}
