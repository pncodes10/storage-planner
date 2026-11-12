package com.storageplanner.v2.features;

import com.storageplanner.v2.device.ReadableFile;
import com.storageplanner.v2.model.ArchiveEntry;
import com.storageplanner.v2.model.ArchiveInfo;
import com.storageplanner.v2.model.Tri;
import com.storageplanner.v2.model.Unknown;
import com.storageplanner.v2.util.Bytes;
import com.storageplanner.v2.util.Io;
import com.storageplanner.v2.util.Text;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** ZIP central-directory listing with safety limits (PHASE_2 7). No entry data is inflated here. */
public final class ZipProbe {
    private ZipProbe() {}

    /** Listing result; entries are always kept here (the caller decides whether to store them). */
    public static final class Listing { public ArchiveInfo info = new ArchiveInfo(); public List<ArchiveEntry> entries = new ArrayList<ArchiveEntry>(); public long cdOffset = -1, eocdOffset = -1, prefix; }

    static final List<String> NESTED = java.util.Arrays.asList("zip", "apk", "jar", "apks", "xapk", "7z", "rar", "tar", "gz", "tgz");

    public static Listing list(ReadableFile rf, long size, Limits lim) throws IOException {
        Listing l = new Listing();
        ArchiveInfo a = l.info;
        a.format = "zip"; a.pathTraversal = Tri.FALSE; a.absolutePaths = Tri.FALSE; a.encryptedEntries = Tri.FALSE; a.bombSuspect = Tri.FALSE;
        a.nameEncodingUncertain = Tri.FALSE; a.nestedArchives = 0;
        int[] budget = {lim.archiveMaxEntries};
        listInto(rf, size, lim, l, null, 0, budget);
        if (a.listingStatus == null) a.listingStatus = "OK";
        long tu = 0, tc = 0; double mr = 0; boolean known = true;
        for (ArchiveEntry e : l.entries) {
            if (e.depth != 0 || e.dir) continue;
            if (e.size < 0 || e.compressedSize < 0) { known = false; continue; }
            tu += e.size; tc += e.compressedSize;
            double r = e.compressedSize > 0 ? e.size / (double) e.compressedSize : (e.size > 0 ? e.size : 0);
            if (r > mr) mr = r;
            if (r > lim.bombEntryRatio && e.size > lim.bombEntryMinSize) a.bombSuspect = Tri.TRUE;
        }
        if (!l.entries.isEmpty()) {
            a.totalUncompressed = known ? tu : -1; a.totalCompressed = known ? tc : -1; a.maxRatio = mr;
            if (tu > lim.bombTotalFactor * Math.max(1, size) || tu > lim.bombTotalAbs) a.bombSuspect = Tri.TRUE;
        }
        a.listedEntries = l.entries.size();
        return l;
    }

    /** Lists one archive level into l. */
    static void listInto(ReadableFile rf, long size, Limits lim, Listing l, String container, int depth, int[] budget) throws IOException {
        ArchiveInfo a = l.info;
        int tail = (int) Math.min(size, lim.zipEocdSearch);
        byte[] t = Io.readAt(rf, size - tail, tail);
        int e = -1;
        for (int i = t.length - 22; i >= 0; i--) if (t[i] == 'P' && t[i + 1] == 'K' && t[i + 2] == 5 && t[i + 3] == 6) { e = i; break; }
        if (e < 0) {
            if (depth == 0) {
                byte[] h = Io.readAt(rf, 0, 4);
                a.listingStatus = h.length == 4 && h[0] == 'P' && h[1] == 'K' && h[2] == 3 && h[3] == 4 ? "TRUNCATED" : "CORRUPT";
                a.problem = "zip:NO_EOCD";
            }
            return;
        }
        long eocd = size - tail + e;
        long count = Bytes.u16le(t, e + 10), cdSize = Bytes.u32le(t, e + 12), cdOff = Bytes.u32le(t, e + 16);
        if (e >= 20 && Bytes.at(t, t.length, e - 20, 'P', 'K', 6, 7)) {
            long z64 = Bytes.u64le(t, e - 20 + 8);
            if (z64 >= 0 && z64 + 56 <= size) {
                byte[] z = Io.readAt(rf, z64, 56);
                if (z.length == 56 && Bytes.at(z, 56, 0, 'P', 'K', 6, 6)) { count = Bytes.u64le(z, 32); cdSize = Bytes.u64le(z, 40); cdOff = Bytes.u64le(z, 48); }
            }
        }
        if (depth == 0) { a.declaredEntries = count; l.eocdOffset = eocd; }
        long prefix = 0;
        if (cdOff + cdSize > eocd) {
            if (eocd - cdSize >= 0) { prefix = eocd - cdSize - cdOff; cdOff = eocd - cdSize; }
            else { if (depth == 0) { a.listingStatus = "CORRUPT"; a.problem = "zip:BAD_CD_OFFSET"; } return; }
        } else if (cdOff + cdSize < eocd && depth == 0 && eocd - cdSize - cdOff > 0 && count > 0) {
            // archive with a prefix (self-extracting): CD sits right before the EOCD (or its ZIP64 records)
            byte[] probe = Io.readAt(rf, cdOff, 4);
            if (!(probe.length == 4 && Bytes.at(probe, 4, 0, 'P', 'K', 1, 2))) { long alt = eocd - cdSize; prefix = alt - cdOff; cdOff = alt; }
        }
        if (cdSize > lim.zipMaxCdBytes) { if (depth == 0) { a.listingStatus = "LIMIT"; a.problem = "zip:CD_TOO_LARGE"; } return; }
        if (depth == 0) { l.cdOffset = cdOff; l.prefix = prefix; }
        byte[] cd = Io.readAt(rf, cdOff, (int) cdSize);
        int p = 0;
        for (long i = 0; i < count && p + 46 <= cd.length; i++) {
            if (!Bytes.at(cd, cd.length, p, 'P', 'K', 1, 2)) { if (depth == 0 && a.listingStatus == null) { a.listingStatus = "CORRUPT"; a.problem = "zip:BAD_CD_ENTRY"; } return; }
            if (budget[0] <= 0) { a.listingStatus = "TRUNCATED"; return; }
            int flags = Bytes.u16le(cd, p + 8), method = Bytes.u16le(cd, p + 10), time = Bytes.u16le(cd, p + 12), date = Bytes.u16le(cd, p + 14);
            long crc = Bytes.u32le(cd, p + 16), csize = Bytes.u32le(cd, p + 20), usize = Bytes.u32le(cd, p + 24);
            int nl = Bytes.u16le(cd, p + 28), xl = Bytes.u16le(cd, p + 30), cl = Bytes.u16le(cd, p + 32);
            long loff = Bytes.u32le(cd, p + 42);
            if (p + 46L + nl + xl + cl > cd.length) { if (depth == 0) { a.listingStatus = "CORRUPT"; a.problem = "zip:CD_ENTRY_PAST_END"; } return; }
            // ZIP64 extra
            int x = p + 46 + nl, xe = x + xl;
            while (x + 4 <= xe) {
                int id = Bytes.u16le(cd, x), sz = Bytes.u16le(cd, x + 2);
                if (x + 4 + sz > xe) break;
                if (id == 1) {
                    int q = x + 4;
                    if (usize == 0xFFFFFFFFL && q + 8 <= x + 4 + sz) { usize = Bytes.u64le(cd, q); q += 8; }
                    if (csize == 0xFFFFFFFFL && q + 8 <= x + 4 + sz) { csize = Bytes.u64le(cd, q); q += 8; }
                    if (loff == 0xFFFFFFFFL && q + 8 <= x + 4 + sz) { loff = Bytes.u64le(cd, q); }
                }
                x += 4 + sz;
            }
            String raw = name(cd, p + 46, nl, (flags & 0x800) != 0, a);
            ArchiveEntry en = new ArchiveEntry();
            String norm = Text.nfc(raw.replace('\\', '/'));
            while (norm.startsWith("./")) norm = norm.substring(2);
            en.path = norm; if (!norm.equals(raw)) en.rawName = raw;
            en.container = container; en.depth = depth; en.size = usize; en.compressedSize = csize; en.crc32 = crc; en.method = method;
            en.mtime = dos(date, time); en.dir = raw.endsWith("/") || raw.endsWith("\\");
            en.encrypted = Tri.of((flags & 1) != 0); en.localOffset = loff + prefix;
            if ((flags & 1) != 0) a.encryptedEntries = Tri.TRUE;
            if (raw.startsWith("/") || raw.startsWith("\\") || raw.matches("^[A-Za-z]:.*")) a.absolutePaths = Tri.TRUE;
            for (String c : raw.split("[/\\\\]")) if (c.equals("..")) a.pathTraversal = Tri.TRUE;
            if (raw.indexOf(92) >= 0) a.pathTraversal = Tri.TRUE;   // backslash (PHASE_2 7 step 6)
            l.entries.add(en);
            budget[0]--;
            String ext = Text.extension(norm).toLowerCase(Locale.ROOT);
            if (!en.dir && NESTED.contains(ext)) {
                a.nestedArchives++;
                if (method == 0 && depth + 1 <= lim.maxNestedDepth && usize > 22 && usize == csize) {
                    long data = dataOffset(rf, en.localOffset);
                    if (data > 0 && data + usize <= size && (ext.equals("zip") || ext.equals("apk") || ext.equals("jar") || ext.equals("apks") || ext.equals("xapk"))) {
                        int before = l.entries.size();
                        listInto(new Io.SliceReadableFile(rf, data, usize), usize, lim, l, (container == null ? "" : container + "!/") + norm, depth + 1, budget);
                        if (l.entries.size() > before) a.nestedListed++;
                    }
                }
            }
            p += 46 + nl + xl + cl;
        }
    }

    /** Offset of an entry's data, from its local header; -1 if invalid. */
    public static long dataOffset(ReadableFile rf, long localOffset) throws IOException {
        byte[] h = Io.readAt(rf, localOffset, 30);
        if (h.length < 30 || !Bytes.at(h, 30, 0, 'P', 'K', 3, 4)) return -1;
        return localOffset + 30 + Bytes.u16le(h, 26) + Bytes.u16le(h, 28);
    }

    static String name(byte[] b, int off, int n, boolean utf8, ArchiveInfo a) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(b, off, n)).toString();
        } catch (CharacterCodingException e) {
            if (!utf8) a.nameEncodingUncertain = Tri.TRUE;
            return new String(b, off, n, StandardCharsets.UTF_8);
        }
    }

    static long dos(int date, int time) {
        if (date == 0) return Unknown.TIME;
        return Dates.utc(1980 + (date >> 9), (date >> 5) & 15, date & 31, time >> 11, (time >> 5) & 63, Math.min(59, (time & 31) * 2), 0);
    }

    /** Bytes of a STORED or DEFLATED entry (≤ maxOut), or null. */
    public static byte[] read(ReadableFile rf, long size, ArchiveEntry e, int maxOut, int maxRatio) throws IOException {
        if (e.size < 0 || e.size > maxOut || e.compressedSize < 0) return null;
        long data = dataOffset(rf, e.localOffset);
        if (data < 0 || data + e.compressedSize > size) return null;
        if (e.method == 0) return e.compressedSize == e.size ? Io.readAt(rf, data, (int) e.size) : null;
        if (e.method != 8 || e.compressedSize > maxOut) return null;
        if (e.compressedSize > 0 && e.size / (double) e.compressedSize > maxRatio && e.size > 4096) return null;
        byte[] comp = Io.readAt(rf, data, (int) e.compressedSize);
        return com.storageplanner.v2.util.BoundedInflater.inflate(comp, maxOut);
    }
}
