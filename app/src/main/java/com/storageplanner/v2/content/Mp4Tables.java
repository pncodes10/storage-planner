package com.storageplanner.v2.content;

import com.storageplanner.v2.device.ReadableFile;
import com.storageplanner.v2.model.ActivityStatus;
import com.storageplanner.v2.util.Bytes;
import com.storageplanner.v2.util.Io;

import java.io.IOException;

/**
 * PHASE_6 8.1: the sample tables of the first video track of an MP4/MOV/3GP file, read without decoding anything.
 * Never throws on malformed input: {@link Tables#status} says why nothing could be read (every status is "activity unknown").
 * All allocations are bounded by the moov size (≤ maxMoovBytes) and by the declared counts checked against the box size first.
 */
public final class Mp4Tables {
    private Mp4Tables() {}

    public static final long DEFAULT_MAX_MOOV = 16L << 20;
    public static final int MAX_SAMPLES = 2000000;
    static final int MAX_TOP_BOXES = 4096, MAX_BOXES = 200000, MAX_DEPTH = 8;
    static final long FIRST_BYTES = 64L << 10;

    /** One track's tables. {@code sizes == null} means every sample has {@code constSize}. {@code stss == null} = box absent. */
    public static final class Track {
        public String handler;
        public long timescale = -1, duration = -1;
        public long[] sttsCount, sttsDelta;
        public boolean hasStts, hasStsz, hasCtts;
        public int sampleCount = -1;
        public long constSize;
        public int[] sizes;
        public int[] stss;                  // 1-based sample numbers, ascending
        public long sizeOf(int i) { return sizes != null ? sizes[i] & 0xFFFFFFFFL : constSize; }
    }

    public static final class Tables {
        /** null = tables read; otherwise the file-level status (never OK). */
        public ActivityStatus status;
        /** First track whose handler is "vide", or null. */
        public Track video;
        public boolean anyVideoTrack() { return video != null; }
    }

    private static final class Bad extends RuntimeException {
        final ActivityStatus st;
        Bad(ActivityStatus st) { super(st.name(), null, false, false); this.st = st; }
    }

    private static Tables fail(ActivityStatus st) { Tables t = new Tables(); t.status = st; return t; }

    public static Tables read(ReadableFile f, long size, long maxMoovBytes) {
        try {
            return read0(f, size, maxMoovBytes);
        } catch (Bad b) {
            return fail(b.st);
        } catch (IOException e) {
            return fail(ActivityStatus.CORRUPT);
        } catch (RuntimeException e) {
            return fail(ActivityStatus.CORRUPT);
        }
    }

    private static Tables read0(ReadableFile f, long size, long maxMoovBytes) throws IOException {
        boolean ftypOrMoovEarly = false, moof = false, corrupt = false;
        long moovPos = -1, moovLen = 0; int moovHdr = 0;
        long p = 0;
        for (int k = 0; k < MAX_TOP_BOXES && p + 8 <= size; k++) {
            byte[] h = Io.readAt(f, p, 16);
            if (h.length < 8) { corrupt = true; break; }
            long len = Bytes.u32be(h, 0); int hdr = 8;
            String type = Bytes.ascii(h, 4, 4);
            if (!printable(type)) { corrupt = true; break; }
            if (len == 1) { if (h.length < 16) { corrupt = true; break; } len = Bytes.u64be(h, 8); hdr = 16; }
            else if (len == 0) len = size - p;
            if (len < hdr || len > size - p) { corrupt = true; if (type.equals("ftyp") && p < FIRST_BYTES) ftypOrMoovEarly = true; break; }
            if (p < FIRST_BYTES && (type.equals("ftyp") || type.equals("moov"))) ftypOrMoovEarly = true;
            if (type.equals("moov") && moovPos < 0) { moovPos = p; moovLen = len; moovHdr = hdr; }
            else if (type.equals("moof")) { moof = true; break; }
            p += len;
        }
        if (!ftypOrMoovEarly && moovPos < 0) return fail(ActivityStatus.UNSUPPORTED);
        if (moof) return fail(ActivityStatus.FRAGMENTED);
        if (corrupt || moovPos < 0) return fail(ActivityStatus.CORRUPT);
        long body = moovLen - moovHdr;
        if (body > maxMoovBytes) return fail(ActivityStatus.TOO_LARGE);
        byte[] moov = Io.readAt(f, moovPos + moovHdr, (int) body);
        if (moov.length != body) return fail(ActivityStatus.CORRUPT);
        Tables t = new Tables();
        int[] boxes = {0};
        walk(moov, 0, moov.length, 1, new Track[]{null}, t, boxes);
        return t;
    }

    static boolean printable(String t) { for (int i = 0; i < 4; i++) { char ch = t.charAt(i); if (ch < 0x20 || ch > 0x7E) return false; } return true; }

    /** Walks the boxes in b[from,to); cur = the track being built while inside a trak. */
    private static void walk(byte[] b, int from, int to, int depth, Track[] cur, Tables out, int[] boxes) {
        if (depth > MAX_DEPTH) throw new Bad(ActivityStatus.CORRUPT);
        int p = from;
        while (p < to) {
            if (to - p < 8) throw new Bad(ActivityStatus.CORRUPT);
            if (++boxes[0] > MAX_BOXES) throw new Bad(ActivityStatus.CORRUPT);
            long len = Bytes.u32be(b, p); int hdr = 8;
            String type = Bytes.ascii(b, p + 4, 4);
            if (len == 1) { if (to - p < 16) throw new Bad(ActivityStatus.CORRUPT); len = Bytes.u64be(b, p + 8); hdr = 16; }
            else if (len == 0) len = to - p;
            if (len < hdr || len > to - p) throw new Bad(ActivityStatus.CORRUPT);
            int s = p + hdr, e = (int) (p + len);
            if (type.equals("trak")) {
                Track t = new Track();
                Track[] c = {t};
                walk(b, s, e, depth + 1, c, out, boxes);
                if ("vide".equals(t.handler) && out.video == null) out.video = t;
            } else if (type.equals("mdia") || type.equals("minf") || type.equals("stbl")) {
                walk(b, s, e, depth + 1, cur, out, boxes);
            } else if (cur[0] != null) {
                leaf(b, type, s, e, cur[0]);
            }
            p = e;
        }
    }

    private static void need(boolean ok) { if (!ok) throw new Bad(ActivityStatus.CORRUPT); }

    private static void leaf(byte[] b, String type, int s, int e, Track t) {
        int n = e - s;
        if (type.equals("mdhd")) {
            need(n >= 4); int ver = b[s] & 0xff;
            if (ver == 1) { need(n >= 32); t.timescale = Bytes.u32be(b, s + 20); t.duration = Bytes.u64be(b, s + 24); }
            else { need(n >= 20); t.timescale = Bytes.u32be(b, s + 12); t.duration = Bytes.u32be(b, s + 16); if (t.duration == 0xFFFFFFFFL) t.duration = -1; }
        } else if (type.equals("hdlr")) {
            need(n >= 12); t.handler = Bytes.ascii(b, s + 8, 4);
        } else if (type.equals("stsz")) {
            need(n >= 12);
            long constSize = Bytes.u32be(b, s + 4), count = Bytes.u32be(b, s + 8);
            if (count > MAX_SAMPLES) throw new Bad(ActivityStatus.TOO_LARGE);                       // before any allocation
            if (constSize == 0) {
                need(12L + 4L * count <= n);                                                         // the count must fit the box
                int[] a = new int[(int) count];
                for (int i = 0; i < a.length; i++) a[i] = (int) Bytes.u32be(b, s + 12 + 4 * i);
                t.sizes = a;
            } else { t.sizes = null; t.constSize = constSize; }
            t.sampleCount = (int) count; t.hasStsz = true;
        } else if (type.equals("stts")) {
            need(n >= 8);
            long count = Bytes.u32be(b, s + 4);
            if (count > MAX_SAMPLES) throw new Bad(ActivityStatus.TOO_LARGE);
            need(8L + 8L * count <= n);
            t.sttsCount = new long[(int) count]; t.sttsDelta = new long[(int) count];
            for (int i = 0; i < count; i++) { t.sttsCount[i] = Bytes.u32be(b, s + 8 + 8 * i); t.sttsDelta[i] = Bytes.u32be(b, s + 12 + 8 * i); }
            t.hasStts = true;
        } else if (type.equals("stss")) {
            need(n >= 8);
            long count = Bytes.u32be(b, s + 4);
            if (count > MAX_SAMPLES) throw new Bad(ActivityStatus.TOO_LARGE);
            need(8L + 4L * count <= n);
            int[] a = new int[(int) count];
            for (int i = 0; i < a.length; i++) a[i] = (int) Bytes.u32be(b, s + 8 + 4 * i);
            t.stss = a;
        } else if (type.equals("ctts")) {
            t.hasCtts = true;
        }
    }
}
