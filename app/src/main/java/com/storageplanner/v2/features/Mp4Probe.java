package com.storageplanner.v2.features;

import com.storageplanner.v2.model.AudioInfo;
import com.storageplanner.v2.model.Integrity;
import com.storageplanner.v2.model.Tri;
import com.storageplanner.v2.model.Unknown;
import com.storageplanner.v2.model.VideoInfo;
import com.storageplanner.v2.util.Bytes;

import java.io.IOException;

/**
 * ISO-BMFF (mp4/mov/3gp/m4a) basics by seeking over boxes (PHASE_2 3.2): mvhd, tkhd, mdhd, hdlr, stsd, first stts entry.
 * moov is never loaded whole; ≤ 1 MiB of box payloads read, else problem MOOV_LARGE.
 */
public final class Mp4Probe {
    private Mp4Probe() {}
    static final long EPOCH_1904 = -2082844800000L;
    static final java.util.Set<String> CONTAINERS = new java.util.HashSet<String>(java.util.Arrays.asList("moov", "trak", "mdia", "minf", "stbl"));

    static final class St { long read; int boxes; long mvhdScale = -1, mvhdDur = -1; String handler; long trackScale = -1, sampleDelta = -1;
        int tw = -1, th = -1, rot = -1; boolean videoSeen, audioSeen; int v, a; }

    public static void probe(ProbeCtx c) throws IOException {
        VideoInfo v = new VideoInfo();
        v.container = c.type;
        St s = new St();
        long p = 0;
        boolean moov = false, moof = false, pastEof = false, mdatBeforeMoov = false, mdatSeen = false;
        long lastEnd = 0;
        for (int k = 0; k < c.lim.maxBoxes && p + 8 <= c.size; k++) {
            byte[] h = c.read(p, 16);
            if (h.length < 8) break;
            long len = Bytes.u32be(h, 0); int hdr = 8;
            String type = Bytes.ascii(h, 4, 4);
            if (!printable(type)) { v.problem = "BAD_BOX_TYPE"; break; }
            if (len == 1) { if (h.length < 16) break; len = Bytes.u64be(h, 8); hdr = 16; } else if (len == 0) len = c.size - p;
            if (len < hdr) { v.problem = "BAD_BOX_SIZE"; break; }
            if (p + len > c.size) pastEof = true;
            if (type.equals("moov")) { moov = true; if (mdatSeen) mdatBeforeMoov = true; walk(c, p + hdr, Math.min(p + len, c.size), s, v, 1); }
            else if (type.equals("moof")) moof = true;
            else if (type.equals("mdat")) mdatSeen = true;
            p += len; lastEnd = Math.min(p, c.size);
            if (pastEof) break;
        }
        v.fragmented = Tri.of(moof); v.moovAtEnd = Tri.of(mdatBeforeMoov);
        v.trailerBytes = c.size > lastEnd ? c.size - lastEnd : 0;
        if (s.read > c.lim.mp4Bytes) { v.problem = "MOOV_LARGE"; c.problem("mp4:MOOV_LARGE"); }
        if (s.mvhdScale > 0 && s.mvhdDur >= 0) v.durationMs = s.mvhdDur * 1000 / s.mvhdScale;
        if (v.durationMs > 0) v.bitrate = c.size * 8000 / v.durationMs;
        v.width = s.tw; v.height = s.th; v.rotation = s.rot;
        v.videoTracks = s.v; v.audioTracks = s.a; v.hasAudio = Tri.of(s.a > 0);
        if (v.problem != null && !"MOOV_LARGE".equals(v.problem)) c.problem("mp4:" + v.problem);
        c.integrity = !moov && !moof ? Integrity.CORRUPT : pastEof ? Integrity.TRUNCATED : Integrity.OK;
        if (s.v == 0 && s.a > 0) {
            AudioInfo a = new AudioInfo(); a.container = c.type; a.codec = v.audioCodec; a.durationMs = v.durationMs;
            a.durationConfidence = v.durationMs >= 0 ? 0.95 : Double.NaN; a.bitrate = v.bitrate;
            c.fr.audio = a;
            if (c.type.equals("m4a")) return;
        }
        c.fr.video = v;
    }

    static boolean printable(String t) { for (int i = 0; i < 4; i++) { char ch = t.charAt(i); if (ch < 0x20 || ch > 0x7E) return false; } return true; }

    static void walk(ProbeCtx c, long from, long to, St s, VideoInfo v, int depth) throws IOException {
        if (depth > 8) return;
        long p = from;
        while (p + 8 <= to && s.boxes++ < c.lim.maxBoxes && s.read <= c.lim.mp4Bytes) {
            byte[] h = c.read(p, 16); s.read += 16;
            if (h.length < 8) return;
            long len = Bytes.u32be(h, 0); int hdr = 8;
            String type = Bytes.ascii(h, 4, 4);
            if (len == 1 && h.length >= 16) { len = Bytes.u64be(h, 8); hdr = 16; } else if (len == 0) len = to - p;
            if (len < hdr || p + len > to) return;
            long body = p + hdr; int bl = (int) Math.min(len - hdr, 4096);
            if (CONTAINERS.contains(type)) {
                if (type.equals("trak")) { s.handler = null; s.trackScale = -1; }
                walk(c, body, p + len, s, v, depth + 1);
            } else if (type.equals("mvhd") || type.equals("tkhd") || type.equals("mdhd") || type.equals("hdlr") || type.equals("stsd") || type.equals("stts")) {
                byte[] b = c.read(body, bl); s.read += b.length;
                box(type, b, s, v);
            }
            p += len;
        }
    }

    static void box(String type, byte[] b, St s, VideoInfo v) {
        int ver = b.length > 0 ? b[0] & 0xff : 0;
        if (type.equals("mvhd")) {
            long ct = ver == 1 ? Bytes.u64be(b, 4) : Bytes.u32be(b, 4);
            s.mvhdScale = ver == 1 ? Bytes.u32be(b, 20) : Bytes.u32be(b, 12);
            s.mvhdDur = ver == 1 ? Bytes.u64be(b, 24) : Bytes.u32be(b, 16);
            v.createdAt = ct == 0 ? Unknown.TIME : EPOCH_1904 + ct * 1000;
        } else if (type.equals("tkhd")) {
            int m = ver == 1 ? 52 : 40;   // matrix offset
            if (b.length >= m + 44) {
                int a = (int) Bytes.u32be(b, m), bb = (int) Bytes.u32be(b, m + 4);
                long w = Bytes.u32be(b, m + 36) >> 16, h = Bytes.u32be(b, m + 40) >> 16;
                if (w > 0 && h > 0 && s.tw < 0) {
                    s.tw = (int) w; s.th = (int) h;
                    s.rot = a == 0 && bb == 0x10000 ? 90 : a == 0 && bb == 0xFFFF0000 ? 270 : a == 0xFFFF0000 ? 180 : 0;
                }
            }
        } else if (type.equals("mdhd")) {
            s.trackScale = ver == 1 ? Bytes.u32be(b, 20) : Bytes.u32be(b, 12);
        } else if (type.equals("hdlr")) {
            s.handler = Bytes.ascii(b, 8, 4);
            if (s.handler.equals("vide")) { s.v++; v.videoTracks = s.v; } else if (s.handler.equals("soun")) s.a++;
        } else if (type.equals("stsd") && b.length >= 16) {
            String fourcc = Bytes.ascii(b, 12, 4).trim().toLowerCase(java.util.Locale.ROOT);
            if ("vide".equals(s.handler) && v.videoCodec == null) v.videoCodec = fourcc;
            if ("soun".equals(s.handler) && v.audioCodec == null) v.audioCodec = fourcc;
        } else if (type.equals("stts") && "vide".equals(s.handler) && b.length >= 16 && Double.isNaN(v.fps)) {
            long delta = Bytes.u32be(b, 12);
            if (delta > 0 && s.trackScale > 0) v.fps = Math.round(s.trackScale * 1000.0 / delta) / 1000.0;
        }
    }
}
