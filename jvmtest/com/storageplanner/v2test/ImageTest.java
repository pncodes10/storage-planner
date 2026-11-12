package com.storageplanner.v2test;

import com.storageplanner.core.ImageFeatures;
import com.storageplanner.v2.device.DecodedImage;
import com.storageplanner.v2.features.Dates;
import com.storageplanner.v2.features.ImageBasics;
import com.storageplanner.v2.model.*;
import com.storageplanner.v2.store.Codecs;

import java.awt.image.BufferedImage;
import java.util.Arrays;

/** Image probes, EXIF, HEIF, ImageBasics, JvmMediaDecoder, T3 and unknown handling (PHASE_2 11, A9). */
public final class ImageTest {
    static FeatureRecord fr(Fx fx, String p) { return fx.store.features().get(fx.f(p).fileId); }

    public static int run(String[] args) throws Exception {
        T.section("ImageProbes");
        try (Fx fx = Fx.create()) {
            fx.maxTier = 3;
            TempTree t = fx.tree;
            BufferedImage pic = FxImg.pattern(320, 240, 1);
            byte[] q50 = FxImg.jpeg(pic, 0.50f), q75 = FxImg.jpeg(pic, 0.75f), q95 = FxImg.jpeg(pic, 0.95f);
            byte[] exifLE = FxImg.tiff(true, "Acme\0\0", 6, "2023:05:06 07:08:09", "+05:30", true);
            t.file("DCIM/Camera/q50.jpg", q50); t.file("DCIM/Camera/q75.jpg", q75); t.file("DCIM/Camera/q95.jpg", q95);
            t.file("DCIM/Camera/exif6.jpg", FxImg.withSegment(q75, 0xE1, FxImg.exifApp1(exifLE)));
            t.file("DCIM/Camera/exifBE.jpg", FxImg.withSegment(q75, 0xE1, FxImg.exifApp1(FxImg.tiff(false, "Bigger", 1, "2022:01:02 03:04:05", null, false))));
            byte[] big = new byte[60000];
            byte[] withApps = q75;
            for (int i = 0; i < 3; i++) withApps = FxImg.withSegment(withApps, 0xE2, big);
            t.file("DCIM/Camera/apps.jpg", withApps);
            t.file("DCIM/Camera/noeoi.jpg", Arrays.copyOf(q75, q75.length - 2));
            byte[] trunc = Arrays.copyOf(q75, 400);
            t.file("DCIM/Camera/cut.jpg", trunc);
            t.file("DCIM/Camera/sef.jpg", FxImg.cat(q75, new byte[70000], FxImg.a("SEFHxxxxSEFT")));
            String xmp = "http://ns.adobe.com/xap/1.0/\0<x:xmpmeta><rdf:Description GCamera:MotionPhoto=\"1\" GCamera:MicroVideoOffset=\"40\"/></x:xmpmeta>";
            t.file("DCIM/Camera/MVIMG_20230101_101010.jpg", FxImg.cat(FxImg.withSegment(q75, 0xE1, FxImg.a(xmp)), FxImg.a("\0\0\0\u0018ftypmp42"), new byte[16]));
            t.file("DCIM/Camera/nosof.jpg", new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xDA, 0, 4, 0, 0, (byte) 0xFF, (byte) 0xD9});
            t.file("Pictures/p.png", FxImg.encode(pic, "png"));
            byte[] png = FxImg.encode(pic, "png");
            t.file("Pictures/cut.png", Arrays.copyOf(png, png.length - 12));
            t.file("Pictures/g.gif", FxImg.encode(pic, "gif"));
            t.file("Pictures/b.bmp", FxImg.encode(pic, "bmp"));
            t.file("Pictures/w.webp", FxImg.webpVp8x(1000, 700, true));
            t.file("DCIM/Camera/IMG_0001.HEIC", FxImg.heic(4032, 3024, 3, FxImg.tiff(true, "Apple", 6, "2024:06:27 17:51:14", null, false)));
            t.file("DCIM/Camera/noispe.heic", FxImg.box("ftyp", FxImg.a("heic"), FxImg.u32(0), FxImg.a("mif1")));
            byte[] h = FxImg.heic(10, 10, 0, null); h[h.length - 30] = (byte) 0x7F;   // corrupt a size inside meta
            t.file("DCIM/Camera/badbox.heic", h);
            byte[] junk = new byte[5000]; for (int i = 0; i < junk.length; i++) junk[i] = (byte) (i * 7 + 1 + (i >> 3)); junk[0] = 1; junk[1] = (byte) 0x80;
            t.file("Download/fake.jpg", junk);
            fx.tree.mtime("DCIM/Camera/exif6.jpg", Dates.utc(2030, 1, 1, 0, 0, 0, 0));
            fx.scan();

            for (String[] q : new String[][]{{"q50.jpg", "50"}, {"q75.jpg", "75"}, {"q95.jpg", "95"}}) {
                int est = fr(fx, "DCIM/Camera/" + q[0]).image.jpegQuality;
                T.check("DQT quality " + q[1] + " ±5 (got " + est + ")", Math.abs(est - Integer.parseInt(q[1])) <= 5);
            }
            ImageInfo i6 = fr(fx, "DCIM/Camera/exif6.jpg").image;
            ExifInfo e6 = fr(fx, "DCIM/Camera/exif6.jpg").exif;
            T.eq("SOF dims", 320, i6.width); T.eq("orientation 6", 6, i6.orientation); T.eq("upright w", 240, i6.uprightWidth);
            T.eq("exif make trimmed", "Acme", e6.make);
            T.eq("DTO with +05:30 → UTC", Dates.utc(2023, 5, 6, 7, 8, 9, 0) - 330 * 60000L, e6.dateTimeOriginal);
            T.eq("tzKnown", Tri.TRUE, e6.tzKnown); T.eq("hasGps", Tri.TRUE, e6.hasGps);
            FileRecord f6 = fx.f("DCIM/Camera/exif6.jpg");
            T.eq("createdAt EXIF (not the 2030 mtime)", TimeSource.EXIF, f6.createdAtSource);
            T.check("decoded upright: analysed 240 wide", fr(fx, "DCIM/Camera/exif6.jpg").image.analysedWidth == 240);
            ExifInfo eb = fr(fx, "DCIM/Camera/exifBE.jpg").exif;
            T.eq("BE make", "Bigger", eb.make); T.eq("BE without offset: local-as-UTC", Dates.utc(2022, 1, 2, 3, 4, 5, 0), eb.dateTimeOriginal);
            T.eq("BE tzKnown FALSE", Tri.FALSE, eb.tzKnown);
            T.eq("SOF after 3×60 KiB APP segments", 320, fr(fx, "DCIM/Camera/apps.jpg").image.width);
            T.eq("missing EOI but decodable → UNKNOWN", Integrity.UNKNOWN, fx.f("DCIM/Camera/noeoi.jpg").integrity);
            T.eq("cut + undecodable → TRUNCATED", Integrity.TRUNCATED, fx.f("DCIM/Camera/cut.jpg").integrity);
            T.check("cut: pixel features unknown", Double.isNaN(fr(fx, "DCIM/Camera/cut.jpg").image.sharpness) && !fx.f("DCIM/Camera/cut.jpg").hasPhash);
            T.check("Samsung trailer > 64 KiB → never TRUNCATED", fx.f("DCIM/Camera/sef.jpg").integrity != Integrity.TRUNCATED);
            T.eq("motion photo", Tri.TRUE, fr(fx, "DCIM/Camera/MVIMG_20230101_101010.jpg").image.motionPhoto);
            T.eq("motion photo integrity OK", Integrity.OK, fx.f("DCIM/Camera/MVIMG_20230101_101010.jpg").integrity);
            T.eq("motion photo createdAt from name", TimeSource.FILENAME, fx.f("DCIM/Camera/MVIMG_20230101_101010.jpg").createdAtSource);
            T.eq("no SOF → CORRUPT", Integrity.CORRUPT, fx.f("DCIM/Camera/nosof.jpg").integrity);
            ImageInfo pi = fr(fx, "Pictures/p.png").image;
            T.eq("png dims", 240, pi.height); T.eq("png bit depth", 8, pi.bitDepth); T.eq("png animated", Tri.FALSE, pi.animated);
            T.eq("png OK", Integrity.OK, fx.f("Pictures/p.png").integrity);
            T.eq("png missing IEND → TRUNCATED", Integrity.TRUNCATED, fx.f("Pictures/cut.png").integrity);
            T.eq("gif dims", 320, fr(fx, "Pictures/g.gif").image.width);
            T.eq("bmp dims", 240, fr(fx, "Pictures/b.bmp").image.height);
            ImageInfo wi = fr(fx, "Pictures/w.webp").image;
            T.eq("webp VP8X dims", 1000, wi.width); T.eq("webp animated", Tri.TRUE, wi.animated);
            T.eq("webp NO_DECODER", DecodeStatus.NO_DECODER, wi.decodeStatus);
            FileRecord hf = fx.f("DCIM/Camera/IMG_0001.HEIC");
            ImageInfo hi = fr(fx, "DCIM/Camera/IMG_0001.HEIC").image;
            T.eq("heic type", "heic", hf.type); T.eq("heic ispe", 4032, hi.width); T.eq("heic irot 3 → orientation 6", 6, hi.orientation);
            T.eq("heic upright 3024×4032", 3024, hi.uprightWidth);
            T.eq("heic EXIF item DTO", Dates.utc(2024, 6, 27, 17, 51, 14, 0), fr(fx, "DCIM/Camera/IMG_0001.HEIC").exif.dateTimeOriginal);
            T.eq("heic NO_DECODER", DecodeStatus.NO_DECODER, hi.decodeStatus);
            T.eq("heic PARTIAL", ExtractionStatus.PARTIAL, hf.extractionStatus);
            T.check("heic no phash", !hf.hasPhash);
            T.check("heic confidence ≤ 0.7", hf.extractionConfidence <= 0.7);
            T.eq("missing ispe → dims unknown", -1, fr(fx, "DCIM/Camera/noispe.heic").image.width);
            T.check("missing meta → problem", fr(fx, "DCIM/Camera/noispe.heic").problems.contains("heif:NO_META"));
            T.check("box overflow: no exception", fx.f("DCIM/Camera/badbox.heic").extractionStatus != ExtractionStatus.FAILED);
            T.eq("random .jpg: type by extension only", TypeSource.EXTENSION, fx.f("Download/fake.jpg").typeSource);
            T.check("random .jpg: not decoded", fr(fx, "Download/fake.jpg").image.decodeStatus != DecodeStatus.OK);

            T.section("UnknownHandling");
            int checked = 0;
            for (FileRecord f : fx.files()) {
                FeatureRecord r = fx.store.features().get(f.fileId);
                if (r == null || r.image == null || r.image.decodeStatus == null || r.image.decodeStatus == DecodeStatus.OK) continue;
                checked++;
                ImageInfo x = r.image;
                T.check(f.path + ": pixel fields NaN", Double.isNaN(x.sharpness) && Double.isNaN(x.brightness) && Double.isNaN(x.contrast)
                        && Double.isNaN(x.exposure) && Double.isNaN(x.colorfulness) && x.lumaHist16 == null);
                T.check(f.path + ": no phash", !f.hasPhash);
                T.check(f.path + ": not COMPLETE", f.extractionStatus != ExtractionStatus.COMPLETE);
                T.check(f.path + ": confidence ≤ 0.7", f.extractionConfidence <= 0.7);
                String js = Codecs.json(Codecs.FEATURE, r);
                T.check(f.path + ": JSON null not 0", js.contains("\"sharpness\":null") && js.contains("\"brightness\":null"));
            }
            T.check("undecoded images checked", checked >= 4);
            FileRecord q = fx.f("DCIM/Camera/q75.jpg");
            T.check("decoded jpeg: phash + COMPLETE", q.hasPhash && q.extractionStatus == ExtractionStatus.COMPLETE);
        }

        T.section("ImageBasics");
        DecodedImage u = img(128, 128, 128);
        ImageInfo iu = new ImageInfo(); long phU = ImageBasics.measure(u, iu);
        T.check("uniform: contrast 0", iu.contrast == 0); T.check("uniform: brightness 128", iu.brightness == 128);
        T.check("uniform: exposure 0", iu.exposure == 0);
        DecodedImage cb = img(128, 128, -1);
        ImageInfo ic = new ImageInfo(); long phC = ImageBasics.measure(cb, ic);
        T.check("checkerboard sharper", ic.sharpness > 10 * Math.max(1e-9, iu.sharpness));
        DecodedImage blk = img(64, 64, 0);
        ImageInfo ib = new ImageInfo(); ImageBasics.measure(blk, ib);
        T.check("black clipLow 1.0", ib.clipLow == 1.0);
        T.check("gray colorfulness ≈ 0", iu.colorfulness < 1e-9);
        DecodedImage col = img(64, 64, 0); for (int i = 0; i < col.argb.length; i++) col.argb[i] = (i & 1) == 0 ? 0xFF0000 : 0x0000FF; col.luma = null;
        ImageInfo icol = new ImageInfo(); ImageBasics.measure(col, icol);
        T.check("saturated colourfulness > 50", icol.colorfulness > 50);
        int hs = 0; for (int v : ic.lumaHist16) hs += v; T.eq("histogram sums to 10000", 10000, hs);
        T.eq("phash = ImageFeatures.phash", ImageFeatures.phash(cb.luma, cb.w, cb.h), phC);
        T.check("small image: sharpness NaN", Double.isNaN(measureSmall()));

        T.section("JvmMediaDecoder");
        try (Fx fx = Fx.create()) {
            BufferedImage pic = FxImg.pattern(200, 120, 2);
            fx.tree.file("s.png", FxImg.encode(pic, "png"));
            byte[] png = FxImg.encode(FxImg.pattern(64, 64, 3), "png");
            // claim 30000×30000 in IHDR (CRC is not checked by the header read)
            png[16] = 0; png[17] = 0; png[18] = 0x75; png[19] = 0x30; png[20] = 0; png[21] = 0; png[22] = 0x75; png[23] = 0x30;
            fx.tree.file("huge.png", png);
            fx.tree.file("x.jpg", new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1, 2, 3, 4, 5, 6, 7, 8, 9});
            JvmMediaDecoder dec = new JvmMediaDecoder(fx.posix);
            DecodedImage d = dec.decode("primary", "s.png", 1024, 100000000L, false);
            T.eq("never upscaled", 200, d.w);
            Runtime rt = Runtime.getRuntime(); long before = rt.totalMemory() - rt.freeMemory();
            DecodedImage tl = dec.decode("primary", "huge.png", 1024, 100000000L, false);
            T.eq("30000² → TOO_LARGE", DecodeStatus.TOO_LARGE, tl.status);
            T.check("TOO_LARGE before allocation", (rt.totalMemory() - rt.freeMemory()) - before < 200L << 20);
            T.eq("random bytes .jpg → CORRUPT", DecodeStatus.CORRUPT, dec.decode("primary", "x.jpg", 1024, 100000000L, false).status);
            T.check("heic not decodable", !dec.decodableTypes().contains("heic"));
            fx.tree.file("big.jpg", FxImg.jpeg(FxImg.pattern(3000, 1500, 4), 0.8f));
            DecodedImage bd = dec.decode("primary", "big.jpg", 1024, 100000000L, false);
            T.eq("long edge ≤ 1024", 1024, Math.max(bd.w, bd.h));
        }
        return T.finish("ImageTest");
    }

    static DecodedImage img(int w, int h, int v) {
        DecodedImage d = new DecodedImage(); d.status = DecodeStatus.OK; d.w = w; d.h = h; d.luma = new int[w * h]; d.argb = new int[w * h];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int l = v >= 0 ? v : (((x + y) & 1) == 0 ? 0 : 255);
            d.luma[y * w + x] = l; d.argb[y * w + x] = (l << 16) | (l << 8) | l;
        }
        return d;
    }
    static double measureSmall() { ImageInfo i = new ImageInfo(); ImageBasics.measure(img(32, 32, -1), i); return i.sharpness; }
}
