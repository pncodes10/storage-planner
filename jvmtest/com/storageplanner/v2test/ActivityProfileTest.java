package com.storageplanner.v2test;

import com.storageplanner.v2.content.*;
import com.storageplanner.v2.device.ReadableFile;
import com.storageplanner.v2.features.Extractors;
import com.storageplanner.v2.model.*;

import java.io.IOException;
import java.util.Arrays;

/** Phase 6 S2 (PHASE_6 8.1): Mp4Tables + ActivityProfile on synthetic MP4s, and the stage's selection and failure handling. */
public final class ActivityProfileTest {
    static byte[] b(String type, byte[]... body) { return FxImg.box(type, body); }
    static byte[] u32(long v) { return FxImg.u32(v); }
    static byte[] z(int n) { return new byte[n]; }

    /** In-memory ReadableFile that counts the bytes it hands out. */
    static final class MemFile implements ReadableFile {
        final byte[] d; long bytes;
        MemFile(byte[] d) { this.d = d; }
        public long size() { return d.length; }
        public int read(long pos, byte[] buf, int off, int len) {
            if (pos >= d.length) return -1;
            int n = (int) Math.min(len, d.length - pos); System.arraycopy(d, (int) pos, buf, off, n); bytes += n; return n;
        }
        public void close() {}
    }

    /** Sizes of the samples, 0-based index; keys = 0-based indexes of sync samples (null = no stss box). */
    static byte[] trak(String handler, int[] sizes, int[] keys, long timescale, long delta, long durationTicks, boolean stsz, boolean stts) {
        int n = sizes == null ? 0 : sizes.length;
        byte[] mdhd = b("mdhd", FxImg.full(0, 0), u32(0), u32(0), u32(timescale), u32(durationTicks), z(4));
        byte[] hdlr = b("hdlr", FxImg.full(0, 0), u32(0), FxImg.a(handler), z(12), z(1));
        java.util.List<byte[]> st = new java.util.ArrayList<byte[]>();
        st.add(b("stsd", FxImg.full(0, 0), u32(1), u32(16), FxImg.a("avc1"), z(8)));
        if (stts) st.add(b("stts", FxImg.full(0, 0), u32(1), u32(n), u32(delta)));
        if (stsz) {
            byte[][] parts = new byte[n + 3][];
            parts[0] = FxImg.full(0, 0); parts[1] = u32(0); parts[2] = u32(n);
            for (int i = 0; i < n; i++) parts[3 + i] = u32(sizes[i]);
            st.add(b("stsz", parts));
        }
        if (keys != null) {
            byte[][] parts = new byte[keys.length + 2][];
            parts[0] = FxImg.full(0, 0); parts[1] = u32(keys.length);
            for (int i = 0; i < keys.length; i++) parts[2 + i] = u32(keys[i] + 1);
            st.add(b("stss", parts));
        }
        return b("trak", b("mdia", mdhd, hdlr, b("minf", b("stbl", st.toArray(new byte[0][])))));
    }

    static byte[] mp4(byte[]... traks) {
        return FxImg.cat(b("ftyp", FxImg.a("isom"), u32(0), FxImg.a("isomavc1")), b("moov", traks), b("mdat", z(64)));
    }

    /** A camera-like clip: {@code n} samples at 10 fps, a key every {@code gop} samples (key 5000 B), non-key {@code nk} B; hook may change sizes. */
    interface Hook { void apply(int[] sizes, boolean[] key); }
    static byte[] clip(int n, int gop, int nk, Hook hook, int... extraKeys) {
        int[] sizes = new int[n]; boolean[] key = new boolean[n];
        for (int i = 0; i < n; i++) { key[i] = i % gop == 0; sizes[i] = key[i] ? 5000 : nk; }
        for (int k : extraKeys) { key[k] = true; sizes[k] = 5000; }
        if (hook != null) hook.apply(sizes, key);
        int c = 0; for (boolean k : key) if (k) c++;
        int[] keys = new int[c]; c = 0; for (int i = 0; i < n; i++) if (key[i]) keys[c++] = i;
        return mp4(trak("vide", sizes, keys, 1000, 100, n * 100L, true, true));
    }

    static ActivityInfo profile(byte[] file) {
        return ActivityProfile.video(Mp4Tables.read(new MemFile(file), file.length, Mp4Tables.DEFAULT_MAX_MOOV));
    }

    static boolean overlaps(ActivityInfo a, long fromMs, long toMs) {
        if (a == null || a.peaks == null) return false;
        for (int i = 0; i + 1 < a.peaks.length; i += 2) if (a.peaks[i] < toMs && a.peaks[i + 1] > fromMs) return true;
        return false;
    }

    public static int run(String[] args) throws Exception {
        T.section("ActivityProfile");
        // 1: 600 s, 10 fps, key every 100, non-key 25 B, frames 4550..4650 = 800 B
        ActivityInfo a = profile(clip(6000, 100, 25, new Hook() { public void apply(int[] s, boolean[] k) { for (int i = 4550; i <= 4650; i++) if (!k[i]) s[i] = 800; } }));
        T.eq("1 OK", ActivityStatus.OK, a.videoStatus);
        T.eq("1 one interval", 2, a.peaks == null ? -1 : a.peaks.length);
        T.check("1 interval covers 455-465 s +-1 window", a.peaks != null && a.peaks.length == 2 && Math.abs(a.peaks[0] - 455000) <= 1000 && Math.abs(a.peaks[1] - 465000) <= 1000);
        T.eq("1 irregularSync 0", 0, a.irregularSync);
        T.eq("1 samples", 6000, a.videoSamples); T.eq("1 duration", 600000L, a.durationMs);
        T.eq("1 peakWindows", 10, a.peakWindows);
        T.check("1 median 250 / threshold 2298", a.medianWindowBytes == 250.0 && a.peakThreshold == 2298.0);
        // 2: nothing happens
        a = profile(clip(3000, 100, 51, null));
        T.check("2 OK, 0 peaks", a.videoStatus == ActivityStatus.OK && a.peaks != null && a.peaks.length == 0 && a.peakWindows == 0);
        // 3: CBR
        a = profile(clip(3000, 100, 3000, null));
        T.check("3 CBR: OK, 0 peaks", a.videoStatus == ActivityStatus.OK && a.peaks != null && a.peaks.length == 0 && a.peakWindows == 0);
        // 4: no stss
        byte[] f4 = mp4(trak("vide", new int[]{100, 100, 100, 100, 100}, null, 1000, 100, 500, true, true));
        a = profile(f4);
        T.eq("4 ALL_KEYFRAMES", ActivityStatus.ALL_KEYFRAMES, a.videoStatus); T.check("4 peaks null", a.peaks == null);
        a = profile(mp4(trak("vide", new int[]{9, 9, 9}, new int[]{0, 1, 2}, 1000, 100, 300, true, true)));
        T.eq("4b stss lists every sample -> ALL_KEYFRAMES", ActivityStatus.ALL_KEYFRAMES, a.videoStatus);
        // 5: 5 s clip with one 5 KB non-key frame
        a = profile(clip(50, 100, 25, new Hook() { public void apply(int[] s, boolean[] k) { s[23] = 5000; } }));
        T.eq("5 SHORT", ActivityStatus.SHORT, a.videoStatus); T.eq("5 one peak window", 1, a.peakWindows);
        T.check("5 peak interval [2000,3000)", a.peaks != null && Arrays.equals(new long[]{2000, 3000}, a.peaks));
        // 6: moof
        byte[] f6 = FxImg.cat(b("ftyp", FxImg.a("isom"), u32(0), FxImg.a("isom")), b("moov", trak("vide", new int[]{1, 1}, new int[]{0}, 1000, 100, 200, true, true)), b("moof", z(16)), b("mdat", z(8)));
        a = profile(f6);
        T.eq("6 FRAGMENTED", ActivityStatus.FRAGMENTED, a.videoStatus); T.check("6 no peaks", a.peaks == null && a.peakWindows == -1);
        // 7: stsz claims 3,000,000 samples in a tiny box -> TOO_LARGE, quickly, nothing allocated
        byte[] lying = FxImg.cat(b("ftyp", FxImg.a("isom"), u32(0), FxImg.a("isom")),
                b("moov", b("trak", b("mdia", b("mdhd", FxImg.full(0, 0), u32(0), u32(0), u32(1000), u32(1000), z(4)), b("hdlr", FxImg.full(0, 0), u32(0), FxImg.a("vide"), z(12), z(1)),
                        b("minf", b("stbl", b("stsz", FxImg.full(0, 0), u32(0), u32(3000000))))))), b("mdat", z(8)));
        long t0 = System.nanoTime();
        a = profile(lying);
        T.eq("7 TOO_LARGE", ActivityStatus.TOO_LARGE, a.videoStatus); T.check("7 fast (< 50 ms)", (System.nanoTime() - t0) / 1000000L < 50);
        byte[] lying2 = FxImg.cat(b("ftyp", FxImg.a("isom"), u32(0), FxImg.a("isom")),
                b("moov", b("trak", b("mdia", b("mdhd", FxImg.full(0, 0), u32(0), u32(0), u32(1000), u32(1000), z(4)), b("hdlr", FxImg.full(0, 0), u32(0), FxImg.a("vide"), z(12), z(1)),
                        b("minf", b("stbl", b("stsz", FxImg.full(0, 0), u32(0), u32(100000), u32(1), u32(2)))))), b("mdat", z(8))));
        a = profile(lying2);
        T.eq("7b stsz count larger than its box -> CORRUPT", ActivityStatus.CORRUPT, a.videoStatus);
        // 8: 40 separate peaks, 10 s apart
        a = profile(clip(4100, 100, 25, new Hook() { public void apply(int[] s, boolean[] k) { for (int p = 0; p < 40; p++) for (int i = 0; i < 10; i++) { int idx = (10 * p + 5) * 10 + i; if (!k[idx]) s[idx] = 5000; } } }));
        T.eq("8 peaks.length 64", 64, a.peaks == null ? -1 : a.peaks.length); T.eq("8 peakWindows 40", 40, a.peakWindows);
        T.eq("8 first interval", 5000L, a.peaks[0]); T.eq("8 32nd interval", 315000L, a.peaks[62]);
        // 9: an extra keyframe inside the event, and the regular key after it is not counted as early
        a = profile(clip(6000, 100, 25, null, 4555));
        T.eq("9 irregularSync 1", 1, a.irregularSync); T.eq("9 one peak window", 1, a.peakWindows);
        T.check("9 interval [455000,456000)", a.peaks != null && Arrays.equals(new long[]{455000, 456000}, a.peaks));
        // 10: Matroska named .mp4
        byte[] mkv = FxImg.cat(new byte[]{0x1A, 0x45, (byte) 0xDF, (byte) 0xA3, (byte) 0x9F, 0x42, (byte) 0x86, (byte) 0x81, 0x01}, z(200));
        a = profile(mkv);
        T.eq("10 Matroska -> UNSUPPORTED", ActivityStatus.UNSUPPORTED, a.videoStatus); T.check("10 no peaks", a.peaks == null);
        T.eq("10b empty file -> UNSUPPORTED", ActivityStatus.UNSUPPORTED, profile(new byte[0]).videoStatus);

        // other statuses and bounds
        byte[] noVideo = mp4(trak("soun", new int[]{10, 10, 10}, new int[]{0}, 44100, 1024, 3072, true, true));
        a = profile(noVideo);
        T.check("no video track -> NOT_RUN, nothing else", a.videoStatus == ActivityStatus.NOT_RUN && a.peaks == null && a.peakWindows == -1);
        a = profile(mp4(trak("vide", null, new int[]{0}, 1000, 100, 100, false, true)));
        T.eq("no stsz -> NO_SAMPLE_TABLE", ActivityStatus.NO_SAMPLE_TABLE, a.videoStatus);
        a = profile(mp4(trak("vide", new int[]{5, 5}, new int[]{0}, 1000, 100, 200, true, false)));
        T.eq("no stts -> NO_SAMPLE_TABLE", ActivityStatus.NO_SAMPLE_TABLE, a.videoStatus);
        byte[] okFile = clip(300, 100, 25, null);
        a = ActivityProfile.video(Mp4Tables.read(new MemFile(okFile), okFile.length, 100));
        T.eq("moov above the cap -> TOO_LARGE", ActivityStatus.TOO_LARGE, a.videoStatus);
        byte[] noMoov = FxImg.cat(b("ftyp", FxImg.a("isom"), u32(0), FxImg.a("isom")), b("mdat", z(500)));
        T.eq("ftyp but no moov -> CORRUPT", ActivityStatus.CORRUPT, profile(noMoov).videoStatus);
        byte[] cut = Arrays.copyOf(okFile, okFile.length - 10);
        T.eq("truncated mdat -> CORRUPT (box past end of file)", ActivityStatus.CORRUPT, profile(cut).videoStatus);
        byte[] zeroSize = FxImg.cat(b("ftyp", FxImg.a("isom"), u32(0), FxImg.a("isom")), new byte[]{0, 0, 0, 4, 'm', 'o', 'o', 'v'}, z(20));
        T.eq("box size < 8 -> CORRUPT", ActivityStatus.CORRUPT, profile(zeroSize).videoStatus);
        // stts that does not cover the samples
        byte[] bad = mp4(trak("vide", new int[]{5, 5, 5, 5}, new int[]{0}, 1000, 100, 400, true, true));
        bad = patchStttsCount(bad, 3);
        T.eq("stts count differs from stsz count -> CORRUPT", ActivityStatus.CORRUPT, profile(bad).videoStatus);
        // unsorted stss
        byte[] uns = mp4(trak("vide", new int[]{5, 5, 5, 5}, new int[]{2, 0}, 1000, 100, 400, true, true));
        T.eq("unsorted stss -> CORRUPT", ActivityStatus.CORRUPT, profile(uns).videoStatus);
        // bounded reads: only the box headers of mdat are read, the moov once
        MemFile mf = new MemFile(okFile);
        Mp4Tables.read(mf, okFile.length, Mp4Tables.DEFAULT_MAX_MOOV);
        T.check("bytes read are about moov + headers", mf.bytes < okFile.length && mf.bytes < 2L * (okFile.length - 64 - 8) + 200);
        // truncations of a valid file and flips never throw
        int thrown = 0;
        byte[] base = clip(300, 100, 25, null);
        for (int len = 0; len <= base.length; len += 7) {
            try { ActivityInfo x = profile(Arrays.copyOf(base, len)); if (x == null || x.videoStatus == null) thrown++; } catch (Throwable t) { thrown++; }
        }
        java.util.Random rnd = new java.util.Random(7);
        for (int it = 0; it < 400; it++) {
            byte[] c = base.clone();
            for (int k = 0; k < 3; k++) c[rnd.nextInt(c.length)] = (byte) rnd.nextInt(256);
            try { ActivityInfo x = profile(c); if (x == null || x.videoStatus == null) thrown++; } catch (Throwable t) { thrown++; }
        }
        T.eq("truncations and random byte flips: never throw, always a status", 0, thrown);
        // determinism and the stored record round trip through the codec
        ActivityInfo p1 = profile(base), p2 = profile(base);
        T.check("deterministic", ContentCodecTest.json(w -> com.storageplanner.v2.store.ContentCodec.write(w, p1)).equals(ContentCodecTest.json(w -> com.storageplanner.v2.store.ContentCodec.write(w, p2))));

        stageTests();
        datasetTest();
        return T.finish("ActivityProfileTest");
    }

    /** Rewrites the stts entry count (the sample count of the first entry) of the first stts box found. */
    static byte[] patchStttsCount(byte[] f, int count) {
        byte[] r = f.clone();
        for (int i = 0; i + 16 < r.length; i++)
            if (r[i] == 's' && r[i + 1] == 't' && r[i + 2] == 't' && r[i + 3] == 's') { r[i + 4 + 4 + 4 + 3] = (byte) count; return r; }
        throw new IllegalStateException("no stts");
    }

    // ---- stage level: selection, stamp, failure handling, unknown-not-static ----
    static void stageTests() throws Exception {
        try (Fx fx = Fx.create()) {
            fx.maxTier = 1;
            ContentStage cs = new ContentStage(new ContentProviders(null, null), new ContentConfig());
            fx.extraStages.add(cs);
            byte[] good = clip(1200, 100, 25, new Hook() { public void apply(int[] s, boolean[] k) { for (int i = 600; i < 700; i++) if (!k[i]) s[i] = 900; } });
            fx.tree.file("DCIM/Camera/a.mp4", good);
            fx.tree.file("Download/d.mp4", good);
            fx.tree.file("WhatsApp/Media/WhatsApp Video/w.mp4", good);
            fx.tree.file("Documents/doc.mp4", good);                       // usage OTHER: not selected
            fx.tree.file("Recordings/rec.m4a", good);                       // audio type: not selected
            fx.tree.file("DCIM/Camera/mkv.mp4", FxImg.cat(new byte[]{0x1A, 0x45, (byte) 0xDF, (byte) 0xA3, (byte) 0x9F, 0x42, (byte) 0x86, (byte) 0x81, 0x01}, z(300)));
            fx.tree.file("DCIM/Camera/frag.mp4", f6());
            fx.tree.file("DCIM/Camera/trunc.mp4", Arrays.copyOf(good, good.length - 20));
            fx.tree.file("DCIM/Camera/empty.mp4", new byte[0]);
            fx.scan();
            FeatureRecord a = fx.store.features().get(fx.f("DCIM/Camera/a.mp4").fileId);
            T.check("camera video has activity", a.activity != null && a.activity.videoStatus == ActivityStatus.OK);
            T.eq("version stamped", Extractors.version(Extractors.ACTIVITY), a.versions[Extractors.ACTIVITY]);
            T.check("camera video: event interval found", overlaps(a.activity, 60000, 70000));
            T.check("download video selected", fx.store.features().get(fx.f("Download/d.mp4").fileId).activity != null);
            T.check("chat video selected", fx.store.features().get(fx.f("WhatsApp/Media/WhatsApp Video/w.mp4").fileId).activity != null);
            FeatureRecord o = fx.store.features().get(fx.f("Documents/doc.mp4").fileId);
            T.check("other-usage video not selected", o.activity == null && o.versions[Extractors.ACTIVITY] == 0);
            T.check("audio type not selected", fx.store.features().get(fx.f("Recordings/rec.m4a").fileId).activity == null);
            T.check("matroska named .mp4 not selected (type is not mp4)", fx.store.features().get(fx.f("DCIM/Camera/mkv.mp4").fileId).activity == null);
            T.eq("fragmented -> FRAGMENTED, no peaks", ActivityStatus.FRAGMENTED, fx.store.features().get(fx.f("DCIM/Camera/frag.mp4").fileId).activity.videoStatus);
            FeatureRecord tr = fx.store.features().get(fx.f("DCIM/Camera/trunc.mp4").fileId);
            T.check("truncated -> unknown status, no peaks", tr.activity != null && tr.activity.videoStatus != ActivityStatus.OK && tr.activity.peaks == null && tr.activity.peakWindows == -1);
            T.check("empty file not selected", fx.store.features().get(fx.f("DCIM/Camera/empty.mp4").fileId).activity == null);
            T.check("no C_CONTENT_INCOMPLETE when everything was reached", !Fx.hasEvent(fx.session(1), ContentStage.INCOMPLETE));
            long done = fx.session(1).counters.get(ContentStage.COUNT_ACTIVITY);
            fx.scan();
            Long again = fx.session(2).counters.get(ContentStage.COUNT_ACTIVITY);
            T.check("second FULL run: nothing re-read (carry-over)", again == null || again == 0);
            T.eq("first run read 5 files", 5L, done);
            // a version bump selects them again
            Extractors.overrideVersionForTest(Extractors.ACTIVITY, 2);
            try { fx.scan(); } finally { Extractors.resetVersionsForTest(); }
            T.eq("version bump: 5 files again", 5L, fx.session(3).counters.get(ContentStage.COUNT_ACTIVITY));
            // an unreadable file: status CORRUPT (unknown), problem recorded, stage still DONE
            fx.faulty.denyOpen("DCIM/Camera/a.mp4");
            Extractors.overrideVersionForTest(Extractors.ACTIVITY, 3);
            try { fx.scan(); } finally { Extractors.resetVersionsForTest(); }
            FeatureRecord fa = fx.store.features().get(fx.f("DCIM/Camera/a.mp4").fileId);
            T.check("open failure -> CORRUPT unknown, never static", fa.activity != null && fa.activity.videoStatus == ActivityStatus.CORRUPT && fa.activity.peaks == null);
            T.check("problem content:ACTIVITY:IOException", fa.problems.contains("content:ACTIVITY:IOException"));
        }
    }

    static byte[] f6() {
        return FxImg.cat(b("ftyp", FxImg.a("isom"), u32(0), FxImg.a("isom")), b("moov", trak("vide", new int[]{1, 1}, new int[]{0}, 1000, 100, 200, true, true)), b("moof", z(16)), b("mdat", z(8)));
    }

    // ---- dataset S08 (skipped without the dataset) ----
    static void datasetTest() throws Exception {
        java.nio.file.Path root = java.nio.file.Paths.get(System.getProperty("p6.dataset", "/home/user/sp-dataset"));
        if (!java.nio.file.Files.isDirectory(root.resolve("DCIM/Camera"))) { System.out.println("  (dataset not present: scenario 8 check skipped)"); return; }
        String[] names = {"VID_20211218_072048", "VID_20221001_124052", "VID_20221108_181137", "VID_20240926_121949"};
        long[][] win = {{455, 465}, {211, 217}, {377, 385}, {302, 307}};
        for (int i = 0; i < names.length; i++) {
            ActivityInfo a = fromFile(root.resolve("DCIM/Camera/" + names[i] + ".mp4"));
            T.check("S08 " + names[i] + " has an interval overlapping " + win[i][0] + "-" + win[i][1] + " s", overlaps(a, win[i][0] * 1000, win[i][1] * 1000));
        }
        ActivityInfo c = fromFile(root.resolve("DCIM/Camera/VID_20210313_082140.mp4"));
        T.check("S08 control VID_20210313_082140: 0 peaks", c != null && c.videoStatus == ActivityStatus.OK && c.peakWindows == 0);
    }

    static ActivityInfo fromFile(java.nio.file.Path p) throws IOException {
        byte[] d = java.nio.file.Files.readAllBytes(p);
        return profile(d);
    }
}
