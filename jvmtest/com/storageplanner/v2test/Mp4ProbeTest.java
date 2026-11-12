package com.storageplanner.v2test;

import com.storageplanner.v2.features.Dates;
import com.storageplanner.v2.model.*;

import java.util.Arrays;

/** Mp4Probe on FxMp4-style fixtures (PHASE_2 11, subset: A11 defers MKV/AVI/audio). */
public final class Mp4ProbeTest {
    static byte[] b(String type, byte[]... body) { return FxImg.box(type, body); }
    static byte[] u32(long v) { return FxImg.u32(v); }
    static byte[] z(int n) { return new byte[n]; }

    static byte[] trak(String handler, String fourcc, int w, int h, boolean rot90, long scale, long delta) {
        byte[] matrix = rot90 ? FxImg.cat(u32(0), u32(0x10000), u32(0), u32(0xFFFF0000L), u32(0), u32(0), u32(0), u32(0), u32(0x40000000))
                : FxImg.cat(u32(0x10000), u32(0), u32(0), u32(0), u32(0x10000), u32(0), u32(0), u32(0), u32(0x40000000));
        byte[] tkhd = b("tkhd", FxImg.full(0, 3), z(36), matrix, u32((long) w << 16), u32((long) h << 16));
        byte[] mdhd = b("mdhd", FxImg.full(0, 0), u32(0), u32(0), u32(scale), u32(scale * 10), z(4));
        byte[] hdlr = b("hdlr", FxImg.full(0, 0), u32(0), FxImg.a(handler), z(12), z(1));
        byte[] stsd = b("stsd", FxImg.full(0, 0), u32(1), u32(16), FxImg.a(fourcc), z(8));
        byte[] stts = b("stts", FxImg.full(0, 0), u32(1), u32(300), u32(delta));
        return b("trak", tkhd, b("mdia", mdhd, hdlr, b("minf", b("stbl", stsd, stts))));
    }
    static byte[] moov(long created1904, boolean audioOnly) {
        byte[] mvhd = b("mvhd", FxImg.full(0, 0), u32(created1904), u32(created1904), u32(1000), u32(12345), z(80));
        return audioOnly ? b("moov", mvhd, trak("soun", "mp4a", 0, 0, false, 44100, 1024))
                : b("moov", mvhd, trak("vide", "avc1", 1920, 1080, true, 30000, 1000), trak("soun", "mp4a", 0, 0, false, 44100, 1024));
    }
    static VideoInfo vi(Fx fx, String p) { return fx.store.features().get(fx.f(p).fileId).video; }

    public static int run(String[] args) throws Exception {
        T.section("Mp4Probe");
        long t2023 = Dates.utc(2023, 3, 4, 5, 6, 7, 0);
        long ct = (t2023 + 2082844800000L) / 1000;
        try (Fx fx = Fx.create()) {
            fx.maxTier = 1;
            byte[] ftyp = b("ftyp", FxImg.a("isom"), u32(0), FxImg.a("isomavc1"));
            byte[] mdat = b("mdat", z(5000));
            fx.tree.file("v.mp4", FxImg.cat(ftyp, moov(ct, false), mdat));
            fx.tree.file("end.mp4", FxImg.cat(ftyp, mdat, moov(ct, false)));
            byte[] cut = FxImg.cat(ftyp, moov(ct, false), mdat);
            fx.tree.file("cut.mp4", Arrays.copyOf(cut, cut.length - 1000));
            fx.tree.file("nomoov.mp4", FxImg.cat(ftyp, mdat));
            fx.tree.file("zero.mp4", FxImg.cat(ftyp, moov(0, false), mdat));
            fx.tree.file("a.m4a", FxImg.cat(b("ftyp", FxImg.a("M4A "), u32(0), FxImg.a("isom")), moov(ct, true), mdat));
            fx.tree.file("frag.mp4", FxImg.cat(ftyp, moov(ct, false), b("moof", z(16)), mdat));
            fx.tree.file("bad.mp4", FxImg.cat(ftyp, new byte[]{0, 0, 0, 16, 1, 2, 3, 4}, z(8)));
            fx.scan();
            VideoInfo v = vi(fx, "v.mp4");
            T.eq("duration from mvhd", 12345L, v.durationMs); T.eq("tkhd 1920", 1920, v.width); T.eq("rotation 90", 90, v.rotation);
            T.eq("avc1", "avc1", v.videoCodec); T.eq("mp4a", "mp4a", v.audioCodec); T.eq("hasAudio", Tri.TRUE, v.hasAudio);
            T.check("fps 30.0", Math.abs(v.fps - 30.0) < 0.01);
            T.eq("createdAt container", t2023, v.createdAt); T.eq("createdAt source", TimeSource.CONTAINER, fx.f("v.mp4").createdAtSource);
            T.eq("OK", Integrity.OK, fx.f("v.mp4").integrity); T.eq("moov first", Tri.FALSE, v.moovAtEnd);
            T.eq("moov at end", Tri.TRUE, vi(fx, "end.mp4").moovAtEnd);
            T.eq("truncated mdat → TRUNCATED", Integrity.TRUNCATED, fx.f("cut.mp4").integrity);
            T.eq("no moov → CORRUPT", Integrity.CORRUPT, fx.f("nomoov.mp4").integrity);
            T.eq("1904 zero date rejected", TimeSource.NONE, fx.f("zero.mp4").createdAtSource);
            FeatureRecord a = fx.store.features().get(fx.f("a.m4a").fileId);
            T.check("m4a → AudioInfo", a.audio != null && a.audio.durationMs == 12345 && a.video == null);
            T.eq("moof → fragmented", Tri.TRUE, vi(fx, "frag.mp4").fragmented);
            T.check("non-printable box type → problem", fx.store.features().get(fx.f("bad.mp4").fileId).problems.contains("mp4:BAD_BOX_TYPE"));
        }
        return T.finish("Mp4ProbeTest");
    }
}
