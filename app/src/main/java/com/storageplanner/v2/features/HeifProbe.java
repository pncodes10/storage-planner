package com.storageplanner.v2.features;

import com.storageplanner.v2.model.ExifInfo;
import com.storageplanner.v2.model.ImageInfo;
import com.storageplanner.v2.util.Bytes;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** HEIF/HEIC/AVIF: primary item's ispe and irot, Exif item (PHASE_2 3.2). meta ≤ 4 MiB, Exif item ≤ 1 MiB. */
public final class HeifProbe {
    private HeifProbe() {}

    public static void probe(ProbeCtx c) throws IOException {
        ImageInfo img = new ImageInfo(); img.format = c.type; c.fr.image = img;
        long p = 0;
        byte[] meta = null;
        for (int k = 0; k < 64 && p + 8 <= c.size; k++) {
            byte[] h = c.read(p, 16);
            if (h.length < 8) break;
            long len = Bytes.u32be(h, 0);
            String type = Bytes.ascii(h, 4, 4);
            int hdr = 8;
            if (len == 1 && h.length >= 16) { len = Bytes.u64be(h, 8); hdr = 16; } else if (len == 0) len = c.size - p;
            if (len < hdr) { c.problem("heif:BAD_BOX"); break; }
            if (type.equals("meta")) {
                if (len > c.lim.imageProbeBytes) { c.problem("heif:META_LARGE"); break; }
                meta = c.read(p + hdr, (int) (len - hdr));
                break;
            }
            p += len;
        }
        if (meta == null) { c.problem("heif:NO_META"); return; }
        Meta m = new Meta();
        children(meta, 4, meta.length, m, 0, c);   // meta is a full box: skip version/flags
        if (m.primary < 0) { c.problem("heif:NO_PITM"); }
        int[] ispe = null; int irot = -1;
        List<Integer> props = m.assoc.get(m.primary);
        if (props != null) for (int idx : props) {
            if (idx < 1 || idx > m.props.size()) continue;
            Object[] pr = m.props.get(idx - 1);
            if ("ispe".equals(pr[0]) && ispe == null) ispe = (int[]) pr[1];
            if ("irot".equals(pr[0])) irot = (Integer) pr[1];
        }
        if (ispe == null) c.problem("heif:NO_ISPE");
        else { img.width = ispe[0]; img.height = ispe[1]; }
        // irot = anticlockwise rotation in 90° steps → EXIF orientation of the same display transform
        img.orientation = irot <= 0 ? 1 : irot == 1 ? 8 : irot == 2 ? 3 : 6;
        if (m.exifItem >= 0 && m.loc.containsKey(m.exifItem)) {
            long[] l = m.loc.get(m.exifItem);
            if (l[1] > 8 && l[1] <= c.lim.jpegMarkerBytes && l[0] + l[1] <= c.size) {
                byte[] d = c.read(l[0], (int) l[1]);
                long skip = Bytes.u32be(d, 0);
                if (skip >= 0 && 4 + skip < d.length) {
                    ExifInfo e = ExifReader.parse(d, (int) (4 + skip), (int) (d.length - 4 - skip), c);
                    if (e != null) c.fr.exif = e;
                }
            } else c.problem("heif:EXIF_ITEM_RANGE");
        }
        img.derive();
    }

    static final class Meta {
        long primary = -1, exifItem = -1;
        List<Object[]> props = new ArrayList<Object[]>();
        Map<Long, List<Integer>> assoc = new HashMap<Long, List<Integer>>();
        Map<Long, long[]> loc = new HashMap<Long, long[]>();
    }

    static void children(byte[] b, int from, int to, Meta m, int depth, ProbeCtx c) {
        if (depth > 8) { c.problem("heif:DEPTH"); return; }
        int p = from;
        for (int k = 0; k < 10000 && p + 8 <= to; k++) {
            long len = Bytes.u32be(b, p);
            String type = Bytes.ascii(b, p + 4, 4);
            int hdr = 8;
            if (len == 1) { len = Bytes.u64be(b, p + 8); hdr = 16; } else if (len == 0) len = to - p;
            if (len < hdr || p + len > to) { c.problem("heif:BOX_OVERFLOW"); return; }
            int s = p + hdr, e = (int) (p + len);
            if (type.equals("pitm")) m.primary = (b[s] & 0xff) == 0 ? Bytes.u16be(b, s + 4) : Bytes.u32be(b, s + 4);
            else if (type.equals("iinf")) iinf(b, s, e, m, c);
            else if (type.equals("iloc")) iloc(b, s, e, m);
            else if (type.equals("iprp")) children(b, s, e, m, depth + 1, c);
            else if (type.equals("ipco")) ipco(b, s, e, m);
            else if (type.equals("ipma")) ipma(b, s, e, m);
            p = e;
        }
    }

    static void iinf(byte[] b, int s, int e, Meta m, ProbeCtx c) {
        int v = b[s] & 0xff;
        int p = s + 4 + (v == 0 ? 2 : 4);
        for (int k = 0; k < 10000 && p + 8 <= e; k++) {
            long len = Bytes.u32be(b, p);
            if (len < 8 || p + len > e) return;
            if (Bytes.at(b, e, p + 4, "infe")) {
                int iv = b[p + 8] & 0xff, q = p + 12;
                if (iv >= 2) {
                    long id = iv == 2 ? Bytes.u16be(b, q) : Bytes.u32be(b, q);
                    q += (iv == 2 ? 2 : 4) + 2;
                    if (Bytes.at(b, e, q, "Exif")) m.exifItem = id;
                }
            }
            p += len;
        }
    }

    static long uint(byte[] b, int p, int size) { return size == 0 ? 0 : size == 2 ? Bytes.u16be(b, p) : size == 4 ? Bytes.u32be(b, p) : Bytes.u64be(b, p); }

    static void iloc(byte[] b, int s, int e, Meta m) {
        int v = b[s] & 0xff, p = s + 4;
        int os = (b[p] >> 4) & 15, ls = b[p] & 15, bs = (b[p + 1] >> 4) & 15, is = v >= 1 ? b[p + 1] & 15 : 0;
        p += 2;
        long n = v < 2 ? Bytes.u16be(b, p) : Bytes.u32be(b, p); p += v < 2 ? 2 : 4;
        for (long i = 0; i < n && i < 10000 && p < e; i++) {
            long id = v < 2 ? Bytes.u16be(b, p) : Bytes.u32be(b, p); p += v < 2 ? 2 : 4;
            int method = 0;
            if (v >= 1) { method = Bytes.u16be(b, p) & 15; p += 2; }
            p += 2;   // data reference index
            long base = uint(b, p, bs); p += bs;
            int ec = Bytes.u16be(b, p); p += 2;
            long off = -1, len = 0;
            for (int x = 0; x < ec && x < 1000; x++) {
                p += is;
                long eo = uint(b, p, os); p += os;
                long el = uint(b, p, ls); p += ls;
                if (x == 0) { off = base + eo; len = el; }
            }
            if (method == 0 && off >= 0) m.loc.put(id, new long[]{off, len});
        }
    }

    static void ipco(byte[] b, int s, int e, Meta m) {
        int p = s;
        for (int k = 0; k < 10000 && p + 8 <= e; k++) {
            long len = Bytes.u32be(b, p);
            if (len < 8 || p + len > e) return;
            String type = Bytes.ascii(b, p + 4, 4);
            Object val = null;
            if (type.equals("ispe") && len >= 20) val = new int[]{(int) Bytes.u32be(b, p + 12), (int) Bytes.u32be(b, p + 16)};
            else if (type.equals("irot") && len >= 9) val = b[p + 8] & 3;
            m.props.add(new Object[]{type, val});
            p += len;
        }
    }

    static void ipma(byte[] b, int s, int e, Meta m) {
        int v = b[s] & 0xff, flags = b[s + 3] & 1, p = s + 4;
        long n = Bytes.u32be(b, p); p += 4;
        for (long i = 0; i < n && i < 100000 && p < e; i++) {
            long id = v < 1 ? Bytes.u16be(b, p) : Bytes.u32be(b, p); p += v < 1 ? 2 : 4;
            int ac = b[p++] & 0xff;
            List<Integer> l = new ArrayList<Integer>();
            for (int a = 0; a < ac; a++) {
                int idx;
                if (flags == 1) { idx = Bytes.u16be(b, p) & 0x7FFF; p += 2; } else { idx = b[p] & 0x7F; p++; }
                l.add(idx);
            }
            m.assoc.put(id, l);
        }
    }
}
