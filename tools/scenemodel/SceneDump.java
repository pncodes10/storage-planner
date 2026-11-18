import com.storageplanner.core.SceneNet;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;

/**
 * Runs SceneNet on many pictures and writes, per picture, the network's input (to compare with Keras on exactly the
 * same numbers) or its 1024 features and 1000 logits (to train the heads on what the phone computes).
 * Usage: java SceneDump scene.bin list.txt out.bin [input]
 *   list.txt: one picture path per line. out.bin: per picture float32 1024 features + 1000 logits
 *   (with "input": float32 3x224x224 inputs instead).
 */
public final class SceneDump {
    public static void main(String[] a) throws Exception {
        SceneNet net = SceneNet.load(Files.readAllBytes(new File(a[0]).toPath()));
        boolean inputs = a.length > 3 && a[3].equals("input");
        BufferedReader r = new BufferedReader(new FileReader(a[1]));
        DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(a[2]), 1 << 20));
        String line; int n = 0; long t0 = System.nanoTime();
        while ((line = r.readLine()) != null) {
            java.awt.image.BufferedImage im;
            try { im = javax.imageio.ImageIO.read(new File(line)); } catch (Exception e) { im = null; }
            int w = im == null ? 1 : im.getWidth(), h = im == null ? 1 : im.getHeight();
            int[] px = im == null ? new int[]{0xFF000000} : im.getRGB(0, 0, w, h, null, 0, w);
            float[] x = SceneNet.input(px, w, h);
            float[] v;
            if (inputs) v = x;
            else {
                SceneNet.Out o = net.runInput(x);
                v = new float[SceneNet.FEATURES + SceneNet.CLASSES];
                System.arraycopy(o.features, 0, v, 0, SceneNet.FEATURES);
                System.arraycopy(o.logits, 0, v, SceneNet.FEATURES, SceneNet.CLASSES);
            }
            ByteBuffer b = ByteBuffer.allocate(4 * v.length).order(ByteOrder.LITTLE_ENDIAN);
            for (float f : v) b.putFloat(f);
            out.write(b.array());
            if (++n % 500 == 0) System.err.println(n + " pictures, " + (System.nanoTime() - t0) / 1000000 / n + " ms each");
        }
        out.close();
        System.err.println(n + " pictures, " + (System.nanoTime() - t0) / 1000000 / Math.max(1, n) + " ms each");
    }
}
