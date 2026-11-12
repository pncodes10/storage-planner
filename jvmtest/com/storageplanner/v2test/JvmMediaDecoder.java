package com.storageplanner.v2test;

import com.storageplanner.v2.device.*;
import com.storageplanner.v2.features.ExifReader;
import com.storageplanner.v2.model.DecodeStatus;
import com.storageplanner.v2.util.Io;

import javax.imageio.IIOException;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * JVM decoder (ARCHITECTURE 3.2): ImageIO for jpeg/png/gif/bmp. Header dims first (TOO_LARGE before allocation), source
 * subsampling to about 2x the target, bilinear scale to the long edge, EXIF orientation applied for JPEG. HEIC/AVIF/WebP: NO_DECODER.
 */
public final class JvmMediaDecoder implements MediaDecoder {
    private static final Set<String> TYPES = Collections.unmodifiableSet(new TreeSet<String>(Arrays.asList("jpeg", "png", "gif", "bmp")));
    private final StorageAccess storage;
    public JvmMediaDecoder(StorageAccess storage) { this.storage = storage; }
    public Set<String> decodableTypes() { return TYPES; }
    public DecodedImage videoFrame(String v, String p, long t, int e) { return DecodedImage.failed(DecodeStatus.UNSUPPORTED_FORMAT, "no video decoder on the JVM"); }

    public DecodedImage decode(String volumeId, String path, int longEdge, long maxPixels, boolean wantArgb) {
        String local = storage.localPath(volumeId, path);
        if (local == null) return DecodedImage.failed(DecodeStatus.IO_ERROR, "not path-addressable");
        ImageInputStream in = null;
        ImageReader r = null;
        try {
            int orientation = 1;
            ReadableFile rf = storage.open(volumeId, path);
            try {
                byte[] head = Io.readAt(rf, 0, (int) Math.min(rf.size(), 256 * 1024));
                if (head.length > 3 && (head[0] & 0xff) == 0xFF && (head[1] & 0xff) == 0xD8) orientation = ExifReader.jpegOrientation(head, head.length);
            } finally { rf.close(); }
            in = ImageIO.createImageInputStream(new File(local));
            if (in == null) return DecodedImage.failed(DecodeStatus.IO_ERROR, "cannot open");
            Iterator<ImageReader> it = ImageIO.getImageReaders(in);
            if (!it.hasNext()) return DecodedImage.failed(DecodeStatus.UNSUPPORTED_FORMAT, "no ImageIO reader");
            r = it.next();
            r.setInput(in, true, true);
            int w = r.getWidth(0), h = r.getHeight(0);
            if (w <= 0 || h <= 0) return DecodedImage.failed(DecodeStatus.CORRUPT, "bad dimensions");
            if ((long) w * h > maxPixels) { DecodedImage d = DecodedImage.failed(DecodeStatus.TOO_LARGE, w + "x" + h); d.origW = w; d.origH = h; return d; }
            ImageReadParam prm = r.getDefaultReadParam();
            int sub = (int) Math.max(1, Math.round(Math.max(w, h) / (2.0 * longEdge)));
            if (sub > 1) prm.setSourceSubsampling(sub, sub, 0, 0);
            BufferedImage bi = r.read(0, prm);
            int sw = bi.getWidth(), sh = bi.getHeight();
            double scale = Math.min(1.0, longEdge / (double) Math.max(sw, sh));
            int tw = Math.max(1, (int) Math.round(sw * scale)), th = Math.max(1, (int) Math.round(sh * scale));
            boolean swap = orientation >= 5 && orientation <= 8;
            int ow = swap ? th : tw, oh = swap ? tw : th;
            BufferedImage out = new BufferedImage(ow, oh, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = out.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setColor(Color.WHITE); g.fillRect(0, 0, ow, oh);
            g.transform(orient(orientation, tw, th));
            g.drawImage(bi, 0, 0, tw, th, null);
            g.dispose();
            int[] argb = out.getRGB(0, 0, ow, oh, null, 0, ow);
            DecodedImage d = new DecodedImage();
            d.status = DecodeStatus.OK; d.w = ow; d.h = oh; d.origW = w; d.origH = h; d.orientation = orientation;
            d.luma = new int[argb.length];
            for (int i = 0; i < argb.length; i++) { int c = argb[i]; d.luma[i] = (299 * ((c >> 16) & 255) + 587 * ((c >> 8) & 255) + 114 * (c & 255)) / 1000; }
            d.argb = wantArgb ? argb : null;
            return d;
        } catch (IIOException e) {
            String m = String.valueOf(e.getMessage());
            return DecodedImage.failed(m.contains("Unsupported Image Type") ? DecodeStatus.UNSUPPORTED_FORMAT : DecodeStatus.CORRUPT, m);
        } catch (IOException e) {
            return DecodedImage.failed(DecodeStatus.IO_ERROR, String.valueOf(e.getMessage()));
        } catch (OutOfMemoryError e) {
            return DecodedImage.failed(DecodeStatus.OUT_OF_MEMORY, "OOM");
        } catch (RuntimeException e) {
            return DecodedImage.failed(DecodeStatus.CORRUPT, e.toString());
        } finally {
            if (r != null) r.dispose();
            if (in != null) try { in.close(); } catch (IOException ignored) { /* nothing */ }
        }
    }

    /** Transform drawing a tw x th image upright for EXIF orientation o. */
    static java.awt.geom.AffineTransform orient(int o, int w, int h) {
        java.awt.geom.AffineTransform t = new java.awt.geom.AffineTransform();
        switch (o) {
            case 2: t.translate(w, 0); t.scale(-1, 1); break;
            case 3: t.translate(w, h); t.rotate(Math.PI); break;
            case 4: t.translate(0, h); t.scale(1, -1); break;
            case 5: t.rotate(Math.PI / 2); t.scale(1, -1); break;
            case 6: t.translate(h, 0); t.rotate(Math.PI / 2); break;
            case 7: t.scale(-1, 1); t.translate(-h, 0); t.translate(0, w); t.rotate(3 * Math.PI / 2); break;
            case 8: t.translate(0, w); t.rotate(3 * Math.PI / 2); break;
            default: break;
        }
        return t;
    }
}
