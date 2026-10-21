package com.storageplanner.test;

import com.storageplanner.core.AppItem;
import com.storageplanner.core.FileItem;
import com.storageplanner.core.Platform;
import com.storageplanner.tools.faces.FaceNet;
import com.storageplanner.tools.faces.Faces;
import com.storageplanner.tools.ocr.TextRules;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;
import javax.imageio.ImageIO;

/**
 * Desktop stand-in for Android, pointed at the virtual phone from the research prototype
 * (vp2/device). Mimics what Android exposes: StorageStats-like app sizes, UsageStats last-use.
 */
public final class JvmPlatform implements Platform {
    final File dev;
    long now;
    List<AppItem> apps;

    public JvmPlatform(String deviceDir) { this.dev = new File(deviceDir); }

    public String storageRoot() { return new File(dev, "sdcard").getAbsolutePath(); }
    public long totalBytes() { return du(dev); }      // the virtual phone is "full"
    public long freeBytes() { return 0; }
    public int sdkInt() { return Integer.parseInt(System.getProperty("sdk", "34")); }

    public long now() {
        if (now == 0) apps();
        if (now == 0) now = Long.parseLong(System.getProperty("now", "1792000000000"));   // a phone with no app history
        return now;
    }

    public List<AppItem> apps() {
        if (apps != null) return apps;
        apps = new ArrayList<AppItem>();
        try {
            String js = new String(Files.readAllBytes(new File(dev, "meta/usagestats.json").toPath()), StandardCharsets.UTF_8);
            Matcher m = Pattern.compile("\"([a-zA-Z0-9_.]+)\": \\{\\s*\"label\": \"([^\"]*)\",\\s*\"last_used_epoch\": ([0-9.]+),\\s*\"first_install_epoch\": ([0-9.]+),\\s*\"category\": \"([a-z]+)\"").matcher(js);
            while (m.find()) {
                AppItem a = new AppItem(m.group(1), m.group(2));
                a.lastUsed = (long) (Double.parseDouble(m.group(3)) * 1000);
                a.firstInstall = (long) (Double.parseDouble(m.group(4)) * 1000);
                a.isGame = m.group(5).equals("game");
                a.hasLauncher = true;
                now = Math.max(now, a.lastUsed);
                File dd = new File(dev, "data/data/" + a.pkg), ext = new File(dev, "sdcard/Android/data/" + a.pkg);
                a.cacheBytes = du(new File(dd, "cache")) + du(new File(ext, "cache"));
                a.dataBytes = du(dd) + du(ext) + du(new File(dev, "sdcard/Android/obb/" + a.pkg)) - a.cacheBytes;
                apps.add(a);
            }
            File[] outer = new File(dev, "data/app").listFiles();
            if (outer != null) for (File o : outer) {
                File[] inner = o.listFiles(); if (inner == null) continue;
                for (File d : inner) for (AppItem a : apps) if (d.getName().startsWith(a.pkg + "-")) a.codeBytes += du(d);
            }
        } catch (Exception e) { throw new RuntimeException(e); }
        return apps;
    }

    public int[] luma(String path, int[] dims) {
        try {
            BufferedImage im = ImageIO.read(new File(path));
            if (im == null) return null;
            int W = im.getWidth(), H = im.getHeight();
            double s = 1024.0 / Math.max(W, H);
            int w = Math.max(1, (int) Math.round(W * s)), h = Math.max(1, (int) Math.round(H * s));
            BufferedImage sc = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = sc.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(im, 0, 0, w, h, null); g.dispose();
            int[] px = sc.getRGB(0, 0, w, h, null, 0, w), l = new int[w * h];
            for (int i = 0; i < px.length; i++) {
                int p = px[i];
                l[i] = (299 * ((p >> 16) & 255) + 587 * ((p >> 8) & 255) + 114 * (p & 255)) / 1000;
            }
            dims[0] = W; dims[1] = H; dims[2] = w; dims[3] = h;
            return l;
        } catch (Exception e) { return null; }
    }

    public int[] argb(String path, int longEdge, int[] dims) {
        try {
            BufferedImage im = ImageIO.read(new File(path));
            if (im == null) return null;
            int W = im.getWidth(), H = im.getHeight();
            double s = Math.min(1.0, (double) longEdge / Math.max(W, H));
            int w = Math.max(1, (int) Math.round(W * s)), h = Math.max(1, (int) Math.round(H * s));
            BufferedImage sc = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = sc.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(im, 0, 0, w, h, null); g.dispose();
            dims[0] = W; dims[1] = H; dims[2] = w; dims[3] = h;
            return sc.getRGB(0, 0, w, h, null, 0, w);
        } catch (Exception e) { return null; }
    }

    private static com.storageplanner.tools.faces.FaceNet net;
    private static boolean netTried;

    /** The app's face detector (YuNet, from app/src/main/assets/faces/yunet.bin or -Dfacemodel=...); null if missing. */
    static synchronized com.storageplanner.tools.faces.FaceNet net() {
        if (!netTried) {
            netTried = true;
            File m = new File(System.getProperty("facemodel", "app/src/main/assets/faces/yunet.bin"));
            if (!m.exists()) m = new File(System.getProperty("user.dir"), "../app/src/main/assets/faces/yunet.bin");
            try { if (m.exists()) net = com.storageplanner.tools.faces.FaceNet.load(Files.readAllBytes(m.toPath())); } catch (Exception e) { net = null; }
        }
        return net;
    }

    /**
     * Faces with the same detector the phone runs (YuNet). With -Dfaces=sidecar, or when the model is missing,
     * the "<photo>.faces" sidecars are used instead (see otherFaces).
     */
    public float[] faces(String path, int[] argb, int w, int h) {
        if (!"sidecar".equals(System.getProperty("faces")) && net() != null) return com.storageplanner.tools.faces.Faces.detect(net(), argb, w, h);
        return otherFaces(path, argb, w, h);
    }

    public String otherFacesName() { return "sidecar file (test stand-in)"; }

    /**
     * Faces listed in a "<photo>.faces" sidecar (one face per line: x y eyeDistance confidence, as fractions of the
     * decoded width/height), written by the test that built the photo. No sidecar = no faces.
     */
    public float[] otherFaces(String path, int[] argb, int w, int h) {
        File side = new File(path + ".faces");
        if (!side.exists()) return new float[0];
        try {
            List<String> lines = Files.readAllLines(side.toPath(), StandardCharsets.UTF_8);
            List<float[]> fs = new ArrayList<float[]>();
            for (String line : lines) {
                String[] t = line.trim().split("\\s+");
                if (t.length < 4) continue;
                fs.add(new float[]{Float.parseFloat(t[0]) * w, Float.parseFloat(t[1]) * h, Float.parseFloat(t[2]) * w, Float.parseFloat(t[3])});
            }
            float[] out = new float[fs.size() * 4];
            for (int i = 0; i < fs.size(); i++) System.arraycopy(fs.get(i), 0, out, i * 4, 4);
            return com.storageplanner.tools.faces.Faces.fromMidpoints(out);
        } catch (Exception e) { return new float[0]; }
    }

    /** Stand-in for the phone's text reader: the words recorded from the same Tesseract build ("<picture>.ocr.json"). */
    public com.storageplanner.tools.ocr.TextRules.Words readText(String path, int[] argb, int w, int h) {
        File small = new File(path + ".ocr360.json"), side = Math.max(w, h) <= 360 && small.exists() ? small : new File(path + ".ocr.json");   // small second look at chat images
        if (!side.exists()) return null;
        try { return words(new String(Files.readAllBytes(side.toPath()), StandardCharsets.UTF_8)); } catch (Exception e) { return null; }
    }

    public boolean imageSize(String path, int[] dims) {
        try (javax.imageio.stream.ImageInputStream in = ImageIO.createImageInputStream(new File(path))) {
            java.util.Iterator<javax.imageio.ImageReader> it = ImageIO.getImageReaders(in);
            if (!it.hasNext()) return false;
            javax.imageio.ImageReader r = it.next(); r.setInput(in);
            dims[0] = r.getWidth(0); dims[1] = r.getHeight(0); r.dispose();
            return true;
        } catch (Exception e) { return false; }
    }

    public boolean video(FileItem f) {
        String out = run("ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries",
                "stream=codec_name,width,height,bit_rate:format=duration,bit_rate", "-of", "default=nw=1", f.abs);
        if (out == null) return false;
        for (String line : out.split("\n")) {
            String[] kv = line.split("=", 2); if (kv.length < 2 || kv[1].equals("N/A")) continue;
            if (kv[0].equals("codec_name")) f.vcodec = kv[1].equals("h264") ? "video/avc" : kv[1].equals("hevc") ? "video/hevc" : "video/" + kv[1];
            if (kv[0].equals("width")) f.width = Integer.parseInt(kv[1]);
            if (kv[0].equals("height")) f.height = Integer.parseInt(kv[1]);
            if (kv[0].equals("bit_rate") && f.bitrate == 0) f.bitrate = Long.parseLong(kv[1]);
            if (kv[0].equals("duration")) f.durationMs = (long) (Double.parseDouble(kv[1]) * 1000);
        }
        return f.vcodec != null;
    }

    public long audioBitrate(String path) {
        String out = run("ffprobe", "-v", "error", "-select_streams", "a:0", "-show_entries", "stream=bit_rate", "-of", "csv=p=0", path);
        try { return out == null ? 0 : Long.parseLong(out.trim()); } catch (Exception e) { return 0; }
    }

    public String[] apkInfo(String path) {
        try (ZipFile z = new ZipFile(path)) {
            String m = new String(z.getInputStream(z.getEntry("AndroidManifest.xml")).readAllBytes(), StandardCharsets.UTF_8);
            Matcher mm = Pattern.compile("package='([^']+)'").matcher(m);
            return mm.find() ? new String[]{mm.group(1), "0"} : null;
        } catch (Exception e) { return null; }
    }

    static long du(File f) {
        if (!f.exists()) return 0;
        if (f.isFile()) return f.length();
        long s = 0; File[] k = f.listFiles();
        if (k != null) for (File c : k) s += du(c);
        return s;
    }

    static String run(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line; while ((line = r.readLine()) != null) sb.append(line).append('\n');
            }
            return p.waitFor() == 0 ? sb.toString() : null;
        } catch (Exception e) { return null; }
    }

    /** The "words" array of an .ocr.json file: [["text", conf], ...]. */
    public static TextRules.Words words(String json) {
        TextRules.Words w = new TextRules.Words();
        int i = json.indexOf("\"words\"");
        if (i < 0) return w;
        Matcher m = Pattern.compile("\\[\"((?:[^\"\\\\]|\\\\.)*)\", (-?\\d+)\\]").matcher(json.substring(i));
        while (m.find()) w.add(unescape(m.group(1)), Integer.parseInt(m.group(2)));
        return w;
    }

    static String unescape(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char d = s.charAt(++i);
                if (d == 'u' && i + 4 < s.length()) { b.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16)); i += 4; }
                else if (d == 'n') b.append('\n'); else b.append(d);
            } else b.append(c);
        }
        return b.toString();
    }
}
