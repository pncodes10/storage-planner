package com.storageplanner.v2test;

import com.storageplanner.v2.graph.CandidateGenerator;
import com.storageplanner.v2.graph.VerdictRules;
import com.storageplanner.v2.model.*;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.*;

/** PHASE_3 12: CandidateGenerator, PerceptualGate, StructuralComparator, VerdictRules, EdgeTyper, NearGrouper (fixture level). */
public final class PerceptualTest {
    static BufferedImage scene(int w, int h, long seed) {
        BufferedImage b = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = b.createGraphics();
        Random r = new Random(seed);
        g.setPaint(new java.awt.GradientPaint(0, 0, new Color(r.nextInt(256), r.nextInt(256), r.nextInt(256)), w, h, new Color(r.nextInt(256), r.nextInt(256), r.nextInt(256))));
        g.fillRect(0, 0, w, h);
        for (int i = 0; i < 25; i++) {
            g.setColor(new Color(r.nextInt(256), r.nextInt(256), r.nextInt(256)));
            int x = r.nextInt(w), y = r.nextInt(h), s = 20 + r.nextInt(w / 4);
            if (i % 2 == 0) g.fillOval(x, y, s, s); else g.fillRect(x, y, s, s / 2);
        }
        g.dispose();
        return scaled(scaled(b, w / 4, h / 4), w, h);
    }
    static BufferedImage scaled(BufferedImage src, int w, int h) {
        BufferedImage b = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = b.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, w, h, null); g.dispose();
        return b;
    }
    static BufferedImage rot90(BufferedImage s) {
        BufferedImage b = new BufferedImage(s.getHeight(), s.getWidth(), BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < s.getHeight(); y++) for (int x = 0; x < s.getWidth(); x++) b.setRGB(s.getHeight() - 1 - y, x, s.getRGB(x, y));
        return b;
    }
    static BufferedImage copy(BufferedImage s) { BufferedImage b = new BufferedImage(s.getWidth(), s.getHeight(), BufferedImage.TYPE_INT_RGB); b.getGraphics().drawImage(s, 0, 0, null); return b; }

    static Relationship edge(Fx fx, String src, String dst) {
        String a = fx.f(src).fileId, b = fx.f(dst).fileId;
        for (Relationship r : fx.store.relationships().all()) if ((r.src.equals(a) && r.dst.equals(b)) || (r.src.equals(b) && r.dst.equals(a))) return r;
        return null;
    }
    static String pstat(Fx fx, String p) { return fx.store.features().get(fx.f(p).fileId).extra.get("graph.perceptual"); }

    public static int run(String[] args) throws Exception {
        T.section("CandidateGenerator");
        Random r = new Random(5);
        int n = 2000;
        long[] h = new long[n], t = new long[n]; String[] s = new String[n];
        for (int i = 0; i < n; i++) { h[i] = r.nextLong() & 0x7fffffffffffffffL; t[i] = Unknown.TIME; }
        for (int d = 0; d <= 7; d++) { long x = h[10 * d]; for (int k = 0; k < d; k++) x ^= 1L << (k * 9); h[10 * d + 1] = x; }
        CandidateGenerator cg = new CandidateGenerator(h, t, s).run();
        for (int d = 0; d <= 7; d++) T.check("planted pair at distance " + d + " found", cg.pairs.containsKey(((long) (10 * d)) << 32 | (10 * d + 1)));
        T.check("candidate pairs <= 64 n", cg.pairs.size() <= 64L * n);
        CandidateGenerator cg2 = new CandidateGenerator(h, t, s).run();
        T.eq("deterministic", cg.pairs, cg2.pairs);
        long[] same = new long[1000]; Arrays.fill(same, 12345L); long[] tt = new long[1000]; Arrays.fill(tt, Unknown.TIME);
        CandidateGenerator deg = new CandidateGenerator(same, tt, new String[1000]).run();
        boolean all = true; for (boolean b : deg.truncated) all &= b;
        T.check("1000 identical hashes: all truncated (perceptualStatus UNKNOWN)", all);
        T.check("1000 identical hashes: bounded work", deg.work <= 1000L * 4 * 17 * 512);
        long[] h3 = {1L, 1L, 1L}, t3 = {0, 119000, 240001};
        CandidateGenerator tw = new CandidateGenerator(new long[]{1L << 40, 3L << 40, 7L << 50}, t3, new String[3]);
        tw.hash[0] = 5; tw.hash[1] = 5 ^ (0x3fffL << 20); tw.hash[2] = 5 ^ (0x3fffL << 40);
        tw.run();
        T.check("time window: 119 s pair found (Hamming 14)", tw.pairs.containsKey(1L));
        T.check("time window: 121 s apart not found", !tw.pairs.containsKey((1L << 32) | 2));

        T.section("VerdictRules");
        PairMetrics m = new PairMetrics(); m.gridW = 64; m.gridH = 48; m.band = 1;
        m.ssim64Mean = 0.99; m.ssim64Min = 0.95; m.diff64Max = 3; m.chroma16 = 1; m.fracBlocksSame = 0.9; m.ssimFineMin = 0.95; m.diffFineMax = 5;
        T.eq("V2 inside", PairVerdict.SAME_CONTENT, VerdictRules.verdict(m, 2, null, 1, false));
        m.ssimFineMin = 0.80; T.eq("fine fails far -> LOCAL_EDIT", PairVerdict.LOCAL_EDIT, VerdictRules.verdict(m, 2, null, 1, false));
        m.ssimFineMin = 0.90; T.eq("fine fails within band -> AMBIGUOUS", PairVerdict.AMBIGUOUS, VerdictRules.verdict(m, 2, null, 1, false));
        m.ssimFineMin = 0.95; m.chroma16 = 6; T.eq("chroma -> COLOR_EDIT", PairVerdict.COLOR_EDIT, VerdictRules.verdict(m, 2, null, 1, false));
        m.chroma16 = 1; T.eq("rot90 -> GEOMETRIC_EDIT", PairVerdict.GEOMETRIC_EDIT, VerdictRules.verdict(m, 30, "rot90", 4, false));
        m.band = 2; T.eq("band B outside thumbs -> AMBIGUOUS", PairVerdict.AMBIGUOUS, VerdictRules.verdict(m, 2, null, 1, false));
        T.eq("band B in thumbs -> SAME", PairVerdict.SAME_CONTENT, VerdictRules.verdict(m, 2, null, 1, true));
        m.band = 1; m.ssim64Mean = 0.4; m.fracBlocksSame = 0.1; m.ssim64Min = 0.1; m.diff64Max = 30;
        T.eq("scene + time window", PairVerdict.NEAR_SCENE, VerdictRules.verdict(m, 10, null, 2, false));
        T.eq("scene without window", PairVerdict.DIFFERENT, VerdictRules.verdict(m, 10, null, 1, false));
        m.gridW = 4; T.eq("tiny grid", PairVerdict.NOT_COMPARABLE, VerdictRules.verdict(m, 1, null, 1, false));

        T.section("Perceptual fixtures");
        try (Fx fx = Fx.create()) {
            fx.maxTier = 3; fx.graph = true;
            TempTree tr = fx.tree;
            BufferedImage o = scene(1200, 900, 1);
            tr.file("DCIM/Camera/IMG_20240101_101010.jpg", FxImg.jpeg(o, 0.95f));
            tr.file("WhatsApp/Media/WhatsApp Images/IMG-20240101-WA0001.jpg", FxImg.jpeg(scaled(o, 800, 600), 0.6f));
            tr.file("DCIM/.thumbnails/1.jpg", FxImg.jpeg(scaled(o, 200, 150), 0.8f));
            tr.file("DCIM/Camera/IMG_20240101_101010_rotated.jpg", FxImg.jpeg(rot90(o), 0.9f));
            BufferedImage col = copy(o);
            for (int y = 0; y < 900; y++) for (int x = 0; x < 1200; x++) { int p = col.getRGB(x, y); col.setRGB(x, y, (p & 0xff00) | ((p >> 16) & 255) | ((p & 255) << 16)); }
            tr.file("DCIM/Camera/IMG_20240101_101010_filtered.jpg", FxImg.jpeg(col, 0.9f));
            BufferedImage lay = copy(o); Graphics2D g = lay.createGraphics(); g.setColor(Color.RED); g.fillRect(300, 300, 120, 120); g.dispose();
            tr.file("Pictures/lookalike.jpg", FxImg.jpeg(lay, 0.9f));
            tr.file("Pictures/other.jpg", FxImg.jpeg(scene(1200, 900, 99), 0.9f));
            tr.file("Pictures/broken.jpg", Arrays.copyOf(FxImg.jpeg(scene(1200, 900, 7), 0.9f), 600));
            fx.scan();
            if (System.getenv("PDEBUG") != null) { for (PairCheck pc : fx.store.pairChecks().all()) System.out.println(fx.store.files().get(pc.a).path + " | " + fx.store.files().get(pc.b).path + " " + pc.verdict + " h=" + pc.hamming + " " + pc.transform + " " + pc.note + " " + com.storageplanner.v2.store.GraphCodecs.PAIR_CHECK.table() + " " + com.storageplanner.v2.store.Codecs.json(com.storageplanner.v2.store.Codecs.PAIR_CHECK, pc)); System.out.println(fx.graphStage.coverage); }
            Relationship wa = edge(fx, "WhatsApp/Media/WhatsApp Images/IMG-20240101-WA0001.jpg", "DCIM/Camera/IMG_20240101_101010.jpg");
            T.check("WA copy: COMPRESSED_FROM VERIFIED -> original", wa != null && wa.type == RelType.COMPRESSED_FROM && wa.verification == Verification.VERIFIED
                    && wa.dst.equals(fx.f("DCIM/Camera/IMG_20240101_101010.jpg").fileId));
            Relationship th = edge(fx, "DCIM/.thumbnails/1.jpg", "DCIM/Camera/IMG_20240101_101010.jpg");
            T.check("thumbnail: THUMBNAIL_OF", th != null && th.type == RelType.THUMBNAIL_OF);
            Relationship rot = edge(fx, "DCIM/Camera/IMG_20240101_101010_rotated.jpg", "DCIM/Camera/IMG_20240101_101010.jpg");
            T.check("rotated with stem: EDITED_FROM", rot != null && rot.type == RelType.EDITED_FROM);
            Relationship fil = edge(fx, "DCIM/Camera/IMG_20240101_101010_filtered.jpg", "DCIM/Camera/IMG_20240101_101010.jpg");
            T.check("colour filter with stem: EDITED_FROM", fil != null && fil.type == RelType.EDITED_FROM);
            T.eq("look-alike without provenance: no edge", null, edge(fx, "Pictures/lookalike.jpg", "DCIM/Camera/IMG_20240101_101010.jpg"));
            T.eq("other scene: no edge", null, edge(fx, "Pictures/other.jpg", "DCIM/Camera/IMG_20240101_101010.jpg"));
            T.eq("broken: perceptual UNKNOWN", "UNKNOWN", pstat(fx, "Pictures/broken.jpg"));
            T.eq("original: perceptual TRUE", "TRUE", pstat(fx, "DCIM/Camera/IMG_20240101_101010.jpg"));
            DuplicateGroup ce = null;
            for (DuplicateGroup d : fx.store.duplicateGroups().all()) if (d.kind == NearGroupKind.CONTENT_EQUIVALENT) ce = d;
            T.check("content-equivalent group keeper = original", ce != null && fx.f("DCIM/Camera/IMG_20240101_101010.jpg").fileId.equals(ce.keeperId) && ce.members.size() == 3);
            int checks = fx.store.pairChecks().size();
            String before = snapshot(fx);
            fx.scan(ScanKind.INCREMENTAL, false);
            T.eq("re-run: same pair checks", checks, fx.store.pairChecks().size());
            T.eq("re-run: identical edges and groups", before, snapshot(fx));
            T.eq("re-run: no decodes", 0L, fx.graphStage.coverage.get("decodes"));
        }
        return T.finish("PerceptualTest");
    }
    static String snapshot(Fx fx) {
        StringBuilder b = new StringBuilder();
        for (Relationship r : fx.store.relationships().all()) b.append(com.storageplanner.v2.store.Codecs.json(com.storageplanner.v2.store.Codecs.RELATIONSHIP, r)).append('\n');
        for (DuplicateGroup g : fx.store.duplicateGroups().all()) b.append(com.storageplanner.v2.store.Codecs.json(com.storageplanner.v2.store.Codecs.DUPLICATE_GROUP, g)).append('\n');
        return b.toString().replaceAll("\"verifiedAt\":[0-9]+", "");
    }
}
