package com.storageplanner.tools;

import java.io.EOFException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;

/**
 * Removes the short video from a Motion Photo and keeps the photo exactly as it was. Plain Java, so it runs the same
 * on the phone and on a desktop, and is tested there.
 *
 * <p>A Motion Photo is a normal photo with a 1-3 second video stored after the picture's own bytes. Phones and
 * computers that do not know the format show just the photo; galleries that do play the clip. Removing the clip
 * keeps every byte of the photo (its pixels, EXIF, XMP, Ultra HDR gain map, depth map) and changes only:
 * <ul>
 * <li>the video's bytes, which are left out;</li>
 * <li>the XMP flags that say "this is a Motion Photo" ({@code MotionPhoto="1"} and {@code MicroVideo="1"} become
 *     "0", and the video's entry in the XMP container list is blanked with spaces), edited in place at the same
 *     length so nothing else in the file moves;</li>
 * <li>for Samsung, the index at the end of the file (SEFH ... SEFT), rewritten without the video's record.</li>
 * </ul>
 *
 * <p>Formats it knows:
 * <ul>
 * <li>Google Motion Photo 1.0, JPEG: the video after the picture, located by {@code Item:Length} in the XMP
 *     {@code Container:Directory} (Pixel and many others);</li>
 * <li>Google Motion Photo 1.0, HEIC/AVIF: the video in a top-level {@code mpvd} box after the image's boxes;</li>
 * <li>Google MicroVideo (older Pixels): located by {@code MicroVideoOffset}, bytes from the end;</li>
 * <li>Samsung: the video in the {@code MotionPhoto_Data} record of the SEF trailer (JPEG and HEIC).</li>
 * </ul>
 * What is removed must be a well-formed MP4/MOV (an {@code ftyp} box first, a {@code moov} box among them) that
 * starts after the picture ends; anything the tool does not understand makes it refuse, never guess.
 *
 * <p>The original is only read. The copy is first written to a hidden work file next to {@code out}, read back and
 * checked (its size and CRC-32 match what was meant to be written, it holds no video and no Motion Photo flag, the
 * picture ends at the same byte), and only then renamed to {@code out}. An existing file is never replaced.
 */
public final class MotionPhoto {
    private MotionPhoto() {}

    /** Room kept free besides the copy and the video. */
    public static final long SPARE_BYTES = 16L << 20;
    /** Largest SEF index (SEFH ... SEFT) read; real ones are a few hundred bytes. */
    static final int MAX_SEF_INDEX = 1 << 20;

    private static final byte[] XMP_ID = "http://ns.adobe.com/xap/1.0/\0".getBytes(StandardCharsets.US_ASCII);
    private static final String[] IMAGE_BRANDS = {"heic", "heix", "heim", "heis", "hevc", "hevx", "mif1", "mif2", "msf1", "avif", "avis"};

    /** One same-length edit: {@code bytes} replace the original bytes starting at {@code pos}. */
    public static final class Patch {
        public final long pos;
        public final byte[] bytes;
        public final String what;
        Patch(long pos, byte[] bytes, String what) { this.pos = pos; this.bytes = bytes; this.what = what; }
    }

    /** What {@link #find} learned about a file. */
    public static final class Found {
        /** True when a video was found and can be removed. */
        public boolean motion;
        /** Why not, when {@link #motion} is false: null for an ordinary photo, else what was wrong. */
        public String why;
        /** "JPEG", "HEIC", or null when neither. */
        public String format;
        /** How the video is stored ("Google Motion Photo", "Samsung Motion Photo", ...). */
        public String kind;
        /** True when the XMP says it is a Motion Photo (MotionPhoto="1" or MicroVideo="1"). */
        public boolean flagged;
        public long size;
        /** Where the picture's own bytes end (JPEG: after its end-of-image marker; HEIC: after its last box). */
        public long imageEnd = -1;
        /** The videos' bytes, {start, end} each (Samsung may hold more than one). */
        public final List<long[]> videos = new ArrayList<long[]>();
        /** The parts of the original that the copy keeps, {start, end} each, in order. */
        public final List<long[]> keep = new ArrayList<long[]>();
        /** Same-length edits inside the kept parts (the XMP flags). */
        public final List<Patch> patches = new ArrayList<Patch>();
        /** Written after the kept parts (Samsung's rewritten index), or null. */
        public byte[] tail;
        /** How many records the rewritten Samsung index lists. */
        int tailRecords;
        public final List<String> notes = new ArrayList<String>();

        public long videoBytes() { long n = 0; for (long[] v : videos) n += v[1] - v[0]; return n; }

        /** Size of the copy. */
        public long newSize() {
            long n = tail == null ? 0 : tail.length;
            for (long[] k : keep) n += k[1] - k[0];
            return n;
        }

        long firstRemoved() { return keep.isEmpty() ? 0 : keep.get(0)[1]; }
    }

    /** What {@link #strip} did. */
    public static final class Outcome {
        /** The copy is at {@link #output} and passed every check. */
        public boolean ok;
        public String output, video, skipped;
        public Found found;
        /** The original's size and the copy's size, in bytes. */
        public long before, after;
        public long ms;
        public final List<String> trace = new ArrayList<String>();

        public long saved() { return ok ? before - after : 0; }
    }

    // ------------------------------------------------------------------ finding the video
    /** {@link #find(FileChannel)} on a file. */
    public static Found find(File f) throws IOException {
        RandomAccessFile r = new RandomAccessFile(f, "r");
        try { return find(r.getChannel()); } finally { r.close(); }
    }

    /** Reads only what it needs (headers, the XMP, the trailer, the MP4 boxes' headers); never changes the file. */
    public static Found find(FileChannel ch) throws IOException {
        Found f = new Found();
        f.size = ch.size();
        if (f.size < 12) return f;
        byte[] head = read(ch, 0, 12);
        List<long[]> secondary = new ArrayList<long[]>();      // gain maps, depth maps (JPEG MPF)
        List<Xmp> xmps = new ArrayList<Xmp>();
        long mpvd = -1, mpvdEnd = -1;
        if ((head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8) {
            f.format = "JPEG";
            String bad = jpeg(ch, f, xmps, secondary);
            if (bad != null) { f.why = bad; return f; }
        } else if (type(head, 4).equals("ftyp")) {
            if (!imageBrand(ch)) return f;                      // an MP4 video or something else: not ours
            f.format = "HEIC";
            long p = 0;
            while (p + 8 <= f.size) {
                long[] box = box(ch, p, f.size);
                if (box == null) break;
                String t = type(read(ch, p + 4, 4), 0);
                if (p > 0 && t.equals("ftyp")) break;           // a second file (an appended video) starts here
                if (t.equals("mpvd")) { mpvd = p; mpvdEnd = p + box[0]; break; }
                p += box[0];
            }
            f.imageEnd = mpvd >= 0 ? mpvd : p;
            Xmp x = heicXmp(ch, f.imageEnd);
            if (x != null) xmps.add(x);
        } else {
            return f;
        }

        XmpInfo info = new XmpInfo();
        for (Xmp x : xmps) info.read(x);
        f.flagged = info.flagged;
        Sef sef = Sef.read(ch, f.size);
        long end = sef != null ? sef.firstStart : f.size;      // where a video appended to the picture must end

        if (mpvd >= 0) {                                        // HEIC Motion Photo 1.0
            long[] b = box(ch, mpvd, f.size);
            long start = mpvd + b[1];
            long e = mp4End(ch, start, mpvdEnd);
            if (e < 0) { f.why = "its mpvd box does not hold an MP4 video"; return f; }
            if (!zeros(ch, e, mpvdEnd)) { f.why = "its mpvd box holds data after the video"; return f; }
            if (end < mpvdEnd) { f.why = "Samsung's records overlap the video (mpvd) box"; return f; }
            if (mpvdEnd != end && !zeros(ch, mpvdEnd, end)) {
                f.why = "boxes follow the video (mpvd) box; removing it would move them"; return f;
            }
            f.kind = "Google Motion Photo (HEIC)";
            f.videos.add(new long[]{start, e});
            f.keep.add(new long[]{0, mpvd});
            if (end < f.size) f.keep.add(new long[]{end, f.size});
        } else if (sef != null && sef.hasVideo(ch)) {           // Samsung
            String bad = sef.plan(ch, f);
            if (bad != null) { f.why = bad; return f; }
            f.kind = "Samsung Motion Photo";
        } else {                                                // Google JPEG (Motion Photo 1.0 or MicroVideo)
            List<long[]> cands = new ArrayList<long[]>();      // {start, how}
            for (long l : info.videoLengths) { cands.add(new long[]{f.size - l, 0}); if (end != f.size) cands.add(new long[]{end - l, 0}); }
            if (info.videoAfter >= 0) cands.add(new long[]{f.imageEnd + info.videoAfter, 0});
            for (long o : info.microOffsets) { cands.add(new long[]{f.size - o, 1}); if (end != f.size) cands.add(new long[]{end - o, 1}); }
            long start = -1, e = -1, how = 0;
            for (long[] c : cands) {
                if (c[0] < f.imageEnd || c[0] >= end) continue;
                long x = mp4End(ch, c[0], end);
                if (x > 0) { start = c[0]; e = x; how = c[1]; break; }
            }
            if (start < 0 && (info.flagged || info.videoLengths.size() > 0)) {   // the numbers are off: look for it
                for (long p = indexOf(ch, f.imageEnd + 4, end, "ftyp"); p >= 0; p = indexOf(ch, p + 1, end, "ftyp")) {
                    long x = mp4End(ch, p - 4, end);
                    if (x > 0) { start = p - 4; e = x; how = 2; break; }
                }
            }
            if (start < 0) {
                if (info.flagged) f.why = "marked as a Motion Photo, but no video is stored in it (already removed?)";
                return f;
            }
            if (!zeros(ch, e, end)) {
                f.why = String.format(Locale.US, "%d bytes this tool does not know follow the video", end - e); return f;
            }
            f.kind = how == 0 ? "Google Motion Photo" : how == 1 ? "Google Motion Photo (MicroVideo)" : "Motion Photo (video found after the picture)";
            f.videos.add(new long[]{start, e});
            f.keep.add(new long[]{0, start});
            if (end < f.size) f.keep.add(new long[]{end, f.size});
        }

        long first = f.firstRemoved();
        if (first < f.imageEnd) { f.videos.clear(); f.keep.clear(); f.tail = null; f.why = "the video would overlap the picture"; return f; }
        for (long[] s : secondary)
            if (s[1] > first) {
                f.videos.clear(); f.keep.clear(); f.tail = null;
                f.why = "a second picture (gain map or depth map) is stored after the video; removing the video would move it";
                return f;
            }
        if (!secondary.isEmpty()) f.notes.add(secondary.size() + " more picture(s) (gain map, depth map) kept as they are");
        info.patch(f, first);
        f.motion = true;
        return f;
    }

    // ------------------------------------------------------------------ stripping
    /** Removes the video from {@code in} into the new file {@code out}; with {@code videoOut}, also saves the video there. */
    public static Outcome strip(File in, File out, File videoOut) {
        RandomAccessFile r = null;
        try {
            r = new RandomAccessFile(in, "r");
            if (in.getCanonicalPath().equals(out.getCanonicalPath())
                    || (videoOut != null && in.getCanonicalPath().equals(videoOut.getCanonicalPath()))) {
                Outcome o = new Outcome();
                o.skipped = "the copy would overwrite the original: " + in;
                return o;
            }
            long mod = in.lastModified();
            Outcome o = strip(r.getChannel(), mod, out, videoOut);
            if (o.ok && in.lastModified() != mod) {                 // changed meanwhile: do not keep a copy of a mix
                new File(o.output).delete();
                if (o.video != null) new File(o.video).delete();
                o.ok = false; o.output = null; o.video = null;
                o.skipped = "the original changed while it was copied";
                o.trace.add("not made: " + o.skipped);
            }
            return o;
        } catch (IOException e) {
            Outcome o = new Outcome();
            o.skipped = "cannot read " + in + ": " + e.getMessage();
            return o;
        } finally {
            if (r != null) try { r.close(); } catch (IOException ignored) {}
        }
    }

    /**
     * Blocking: call it off the main thread. {@code in} stays open (the caller closes it); {@code modified} (ms since
     * 1970, 0 = leave it) becomes the copy's and the video's last-modified time, so they sort with the original.
     */
    public static Outcome strip(FileChannel in, long modified, File out, File videoOut) {
        long t0 = System.nanoTime();
        Outcome o = new Outcome();
        File tmp = null, tmpVideo = null;
        try {
            String why = refuse(out, videoOut);
            if (why != null) { o.skipped = why; return o; }
            Found f = find(in);
            o.found = f;
            o.before = f.size;
            if (!f.motion) { o.skipped = f.why != null ? f.why : "not a Motion Photo: there is no video to remove"; return o; }
            long newSize = f.newSize();
            o.trace.add(String.format(Locale.US, "%s %s: %d bytes; picture ends at %d; video %d bytes; copy %d bytes",
                    f.kind, f.format, f.size, f.imageEnd, f.videoBytes(), newSize));
            for (String n : f.notes) o.trace.add("  " + n);
            for (Patch p : f.patches) o.trace.add(String.format(Locale.US, "  XMP at %d: %s", p.pos, p.what));
            long need = newSize + (videoOut != null ? f.videos.get(0)[1] - f.videos.get(0)[0] : 0) + SPARE_BYTES;
            long free = out.getAbsoluteFile().getParentFile().getUsableSpace();
            if (free < need) { o.skipped = String.format(Locale.US, "not enough free space: %d bytes free, need about %d", free, need); return o; }

            tmp = new File(CopyPaths.tempPath(out.getAbsolutePath()));
            if (tmp.exists() && !tmp.delete()) { o.skipped = "cannot remove an old work file " + tmp; tmp = null; return o; }
            long crc = write(in, f, tmp);
            check(tmp, f, newSize, crc);
            if (videoOut != null) {
                long[] v = f.videos.get(0);
                tmpVideo = new File(CopyPaths.tempPath(videoOut.getAbsolutePath()));
                if (tmpVideo.exists() && !tmpVideo.delete()) throw new Stop("cannot remove an old work file " + tmpVideo);
                long vcrc = copy(in, v[0], v[1], tmpVideo);
                checkVideo(tmpVideo, v[1] - v[0], vcrc);
            }
            if (in.size() != f.size) throw new Stop("the original changed while it was copied");
            if (out.exists() || (videoOut != null && videoOut.exists())) throw new Stop("a file appeared at " + out + " meanwhile");
            if (modified > 0) { tmp.setLastModified(modified); if (tmpVideo != null) tmpVideo.setLastModified(modified); }
            if (!tmp.renameTo(out)) throw new Stop("cannot rename the work file to " + out);
            tmp = null;
            o.output = out.getPath();
            if (tmpVideo != null) {
                if (!tmpVideo.renameTo(videoOut)) throw new Stop("cannot rename the video's work file to " + videoOut);
                tmpVideo = null;
                o.video = videoOut.getPath();
            }
            o.after = out.length();
            o.ok = true;
            o.trace.add(String.format(Locale.US, "checked: %d -> %d bytes (%.1f%% saved), the picture is byte for byte the same",
                    o.before, o.after, 100.0 * (o.before - o.after) / Math.max(1, o.before)));
        } catch (Stop e) {
            o.skipped = e.getMessage();
        } catch (Throwable t) {
            o.skipped = "failed: " + t;
        } finally {
            if (!o.ok && o.output != null) { new File(o.output).delete(); o.output = null; }   // the video failed: keep nothing
            if (tmp != null && tmp.exists() && !tmp.delete()) o.trace.add("could not remove the work file " + tmp);
            if (tmpVideo != null && tmpVideo.exists() && !tmpVideo.delete()) o.trace.add("could not remove the work file " + tmpVideo);
            o.ms = (System.nanoTime() - t0) / 1_000_000;
            if (!o.ok) o.trace.add("not made: " + o.skipped);
        }
        return o;
    }

    private static String refuse(File out, File videoOut) throws IOException {
        if (out.exists()) return "the copy already exists: " + out + " (a tool never replaces a file)";
        File dir = out.getAbsoluteFile().getParentFile();
        if (dir == null || !dir.isDirectory()) return "the copy's folder does not exist: " + dir;
        if (videoOut != null) {
            if (videoOut.exists()) return "the video file already exists: " + videoOut;
            File vd = videoOut.getAbsoluteFile().getParentFile();
            if (vd == null || !vd.isDirectory()) return "the video's folder does not exist: " + vd;
            if (videoOut.getCanonicalPath().equals(out.getCanonicalPath())) return "the copy and the video would be the same file";
        }
        return null;
    }

    /** Writes the kept parts (with the XMP edits) and the tail; returns the CRC-32 of what was written. */
    private static long write(FileChannel in, Found f, File tmp) throws IOException {
        CRC32 crc = new CRC32();
        FileOutputStream os = new FileOutputStream(tmp);
        try {
            byte[] buf = new byte[1 << 16];
            for (long[] k : f.keep) {
                for (long p = k[0]; p < k[1]; ) {
                    int n = (int) Math.min(buf.length, k[1] - p);
                    readInto(in, p, buf, n);
                    for (Patch pt : f.patches)
                        for (int i = 0; i < pt.bytes.length; i++) {
                            long at = pt.pos + i - p;
                            if (at >= 0 && at < n) buf[(int) at] = pt.bytes[i];
                        }
                    os.write(buf, 0, n);
                    crc.update(buf, 0, n);
                    p += n;
                }
            }
            if (f.tail != null) { os.write(f.tail); crc.update(f.tail, 0, f.tail.length); }
            os.getFD().sync();
        } finally { os.close(); }
        return crc.getValue();
    }

    private static long copy(FileChannel in, long from, long to, File dst) throws IOException {
        CRC32 crc = new CRC32();
        FileOutputStream os = new FileOutputStream(dst);
        try {
            byte[] buf = new byte[1 << 16];
            for (long p = from; p < to; ) {
                int n = (int) Math.min(buf.length, to - p);
                readInto(in, p, buf, n);
                os.write(buf, 0, n);
                crc.update(buf, 0, n);
                p += n;
            }
            os.getFD().sync();
        } finally { os.close(); }
        return crc.getValue();
    }

    /** Reads the copy back: size and CRC as written, no video left, no Motion Photo flag, the picture ends where it did. */
    private static void check(File tmp, Found f, long size, long crc) throws IOException {
        if (tmp.length() != size) throw new Stop("check failed: the copy is " + tmp.length() + " bytes, not " + size);
        if (crc(tmp) != crc) throw new Stop("check failed: the copy reads back different from what was written");
        Found g = find(tmp);
        if (g.motion || g.flagged) throw new Stop("check failed: the copy still reads as a Motion Photo");
        if (g.why != null) throw new Stop("check failed: " + g.why);
        if (!f.format.equals(g.format) || g.imageEnd != f.imageEnd)
            throw new Stop("check failed: the copy's picture ends at " + g.imageEnd + ", not " + f.imageEnd);
        if (f.tail != null) {
            RandomAccessFile r = new RandomAccessFile(tmp, "r");
            try {
                Sef s = Sef.read(r.getChannel(), size);
                if (s == null || s.records.size() != f.tailRecords) throw new Stop("check failed: the copy's Samsung index does not read back");
                for (Sef.Record rec : s.records) if (!rec.valid) throw new Stop("check failed: Samsung record " + rec.name + " does not read back");
            } finally { r.close(); }
        }
    }

    private static void checkVideo(File v, long size, long crc) throws IOException {
        if (v.length() != size || crc(v) != crc) throw new Stop("check failed: the video reads back different from what was written");
        RandomAccessFile r = new RandomAccessFile(v, "r");
        try { if (mp4End(r.getChannel(), 0, size) != size) throw new Stop("check failed: the saved video is not a whole MP4"); }
        finally { r.close(); }
    }

    private static long crc(File f) throws IOException {
        CRC32 c = new CRC32();
        java.io.FileInputStream in = new java.io.FileInputStream(f);
        try {
            byte[] buf = new byte[1 << 16];
            int k;
            while ((k = in.read(buf)) > 0) c.update(buf, 0, k);
        } finally { in.close(); }
        return c.getValue();
    }

    // ------------------------------------------------------------------ JPEG
    /** Walks the JPEG's segments to its end-of-image marker; collects the XMP and the MPF pictures. Null, or what is wrong. */
    private static String jpeg(FileChannel ch, Found f, List<Xmp> xmps, List<long[]> secondary) throws IOException {
        Reader in = new Reader(ch);
        in.pos = 2;
        boolean scan = false;                                   // inside entropy-coded data
        while (true) {
            int b = in.read();
            if (b < 0) return "the picture is cut off (no end-of-image marker)";
            if (b != 0xFF) { if (scan) continue; return "not a valid JPEG (bad segment at " + (in.pos - 1) + ")"; }
            int m = in.read();
            while (m == 0xFF) m = in.read();                    // fill bytes
            if (m < 0) return "the picture is cut off (no end-of-image marker)";
            if (m == 0x00 || m == 0x01 || (m >= 0xD0 && m <= 0xD7)) continue;   // stuffed byte, restart markers
            if (m == 0xD9) { f.imageEnd = in.pos; return null; }
            if (m == 0xD8) return "not a valid JPEG (a second start marker inside the picture)";
            long segStart = in.pos - 2;
            int hi = in.read(), lo = in.read();
            if (lo < 0) return "the picture is cut off";
            int len = (hi << 8) | lo;
            if (len < 2) return "not a valid JPEG (bad segment length at " + segStart + ")";
            long payload = segStart + 4, next = segStart + 2 + len;
            if (next > f.size) return "the picture is cut off";
            if (!scan && m == 0xE1 && len - 2 > XMP_ID.length) {
                byte[] seg = read(ch, payload, len - 2);
                if (startsWith(seg, XMP_ID)) xmps.add(new Xmp(payload + XMP_ID.length, seg, XMP_ID.length));
            }
            if (!scan && m == 0xE2 && len - 2 >= 8) {
                byte[] seg = read(ch, payload, len - 2);
                if (seg[0] == 'M' && seg[1] == 'P' && seg[2] == 'F' && seg[3] == 0) {
                    String bad = mpf(seg, payload + 4, secondary);
                    if (bad != null) return bad;
                }
            }
            if (m == 0xDA) scan = true;                         // entropy data follows (and later scans, for progressive)
            in.pos = next;
        }
    }

    /** The pictures an MPF segment lists after the first, as {start, end} in the file. */
    private static String mpf(byte[] seg, long tiff, List<long[]> out) {
        try {
            int t = 4;
            boolean le = seg[t] == 'I';
            int ifd = (int) u32(seg, t + 4, le);
            int n = u16(seg, t + ifd, le);
            for (int i = 0; i < n; i++) {
                int e = t + ifd + 2 + 12 * i;
                if (u16(seg, e, le) != 0xB002) continue;       // MPEntry
                int count = (int) u32(seg, e + 4, le), at = (int) u32(seg, e + 8, le);
                for (int k = 0; k + 16 <= count; k += 16) {
                    long size = u32(seg, t + at + k + 4, le), off = u32(seg, t + at + k + 8, le);
                    if (off != 0 && size > 0) out.add(new long[]{tiff + off, tiff + off + size});
                }
            }
            return null;
        } catch (ArrayIndexOutOfBoundsException e) {
            return "its MPF segment (the list of pictures in the file) cannot be read";
        }
    }

    // ------------------------------------------------------------------ HEIC
    private static boolean imageBrand(FileChannel ch) throws IOException {
        long[] b = box(ch, 0, ch.size());
        if (b == null || b[0] < 16 || b[0] > 4096) return false;
        byte[] ftyp = read(ch, b[1], (int) b[0] - (int) b[1]);
        for (int p = 0; p + 4 <= ftyp.length; p += 4) {
            if (p == 4) continue;                               // minor version
            String brand = type(ftyp, p);
            for (String s : IMAGE_BRANDS) if (s.equals(brand)) return true;
        }
        return false;
    }

    /** The XMP packet of a HEIC (an item somewhere before {@code end}), or null. */
    private static Xmp heicXmp(FileChannel ch, long end) throws IOException {
        long p = indexOf(ch, 0, end, "<x:xmpmeta");
        if (p < 0) return null;
        long q = indexOf(ch, p, Math.min(end, p + (4 << 20)), "</x:xmpmeta>");
        if (q < 0) return null;
        q += "</x:xmpmeta>".length();
        return new Xmp(p, read(ch, p, (int) (q - p)), 0);
    }

    // ------------------------------------------------------------------ XMP
    private static final class Xmp {
        final long pos;          // file position of text[0]
        final String text;       // ISO-8859-1, so text index i is byte pos + i
        Xmp(long pos, byte[] seg, int from) { this.pos = pos; this.text = new String(seg, from, seg.length - from, StandardCharsets.ISO_8859_1); }
    }

    private static final Pattern FLAG_ATTR = Pattern.compile("\\b[A-Za-z][\\w.-]*:(MotionPhoto|MicroVideo)\\s*=\\s*([\"'])\\s*(-?\\d+)\\s*\\2");
    private static final Pattern FLAG_ELEM = Pattern.compile("<([A-Za-z][\\w.-]*):(MotionPhoto|MicroVideo)>\\s*(-?\\d+)\\s*</\\1:\\2>");
    private static final Pattern OFFSET = Pattern.compile("(?<![/\\w.-])[A-Za-z][\\w.-]*:MicroVideoOffset(?:\\s*=\\s*[\"']\\s*(\\d+)\\s*[\"']|>\\s*(\\d+)\\s*<)");
    private static final Pattern DIRECTORY = Pattern.compile("<([A-Za-z][\\w.-]*):Directory\\b.*?</\\1:Directory>", Pattern.DOTALL);
    private static final Pattern LI = Pattern.compile("<rdf:li\\b(?:[^>]*/>|.*?</rdf:li>)", Pattern.DOTALL);
    private static final Pattern FIELD = Pattern.compile("(?<![/\\w.-])[A-Za-z][\\w.-]*:(Semantic|Length|Padding|Mime)(?:\\s*=\\s*[\"']([^\"']*)[\"']|>([^<]*)<)");

    /** The Motion Photo facts in a file's XMP, and where its flags are. */
    private static final class XmpInfo {
        boolean flagged;
        final List<Long> videoLengths = new ArrayList<Long>(), microOffsets = new ArrayList<Long>();
        /** Offset of the video from the picture's end by the container list (Padding + earlier items), or -1. */
        long videoAfter = -1;
        final List<Patch> flags = new ArrayList<Patch>(), items = new ArrayList<Patch>();

        void read(Xmp x) {
            Matcher m = FLAG_ATTR.matcher(x.text);
            while (m.find()) flag(x, m.group(1), m.group(3), m.start(3), m.end(3));
            m = FLAG_ELEM.matcher(x.text);
            while (m.find()) flag(x, m.group(2), m.group(3), m.start(3), m.end(3));
            m = OFFSET.matcher(x.text);
            while (m.find()) {
                String v = m.group(1) != null ? m.group(1) : m.group(2);
                try { long o = Long.parseLong(v); if (o > 0) microOffsets.add(o); } catch (NumberFormatException ignored) {}
            }
            Matcher d = DIRECTORY.matcher(x.text);
            while (d.find()) {
                Matcher li = LI.matcher(d.group());
                long after = 0;
                boolean primary = true, ok = true;
                while (li.find()) {
                    String semantic = null; long length = -1, padding = 0;
                    Matcher fm = FIELD.matcher(li.group());
                    while (fm.find()) {
                        String v = (fm.group(2) != null ? fm.group(2) : fm.group(3)).trim();
                        try {
                            if (fm.group(1).equals("Semantic")) semantic = v;
                            else if (fm.group(1).equals("Length")) length = Long.parseLong(v);
                            else if (fm.group(1).equals("Padding")) padding = Long.parseLong(v);
                        } catch (NumberFormatException e) { ok = false; }
                    }
                    if (primary) { after = padding; primary = false; continue; }
                    if ("MotionPhoto".equals(semantic)) {
                        if (length > 0) videoLengths.add(length);
                        if (ok && length > 0) videoAfter = after;
                        int s = d.start() + li.start();
                        items.add(new Patch(x.pos + s, spaces(li.end() - li.start()), "the video's entry in the container list blanked"));
                    } else if (length > 0) {
                        after += length;
                    }
                }
            }
        }

        private void flag(Xmp x, String name, String value, int s, int e) {
            if (!value.equals("1")) return;
            flagged = true;
            byte[] zero = spaces(e - s);
            zero[0] = '0';
            flags.add(new Patch(x.pos + s, zero, name + "=\"1\" -> \"0\""));
        }

        /** Adds the XMP edits to {@code f}; all of them lie before {@code first}, the first byte removed. */
        void patch(Found f, long first) {
            for (Patch p : flags) if (p.pos + p.bytes.length <= first) f.patches.add(p);
            for (Patch p : items) if (p.pos + p.bytes.length <= first) f.patches.add(p);
        }
    }

    private static byte[] spaces(int n) { byte[] b = new byte[n]; java.util.Arrays.fill(b, (byte) ' '); return b; }

    // ------------------------------------------------------------------ Samsung trailer
    /**
     * Samsung's trailer: records, then an index "SEFH" version count {pad16 type16 offset32 size32}*count, then
     * size32 "SEFT" (all little-endian). A record starts {offset} bytes before "SEFH" and holds pad16 type16
     * nameLength32 name data; MotionPhoto_Data's data is the MP4.
     */
    private static final class Sef {
        static final class Record {
            long start, size;
            String name = "";
            byte[] entry;               // its 12-byte index entry
            boolean video, valid;
        }

        long sefh, firstStart;
        byte[] index;                   // SEFH ... up to (not including) the footer
        int count;
        final List<Record> records = new ArrayList<Record>();

        static Sef read(FileChannel ch, long size) throws IOException {
            if (size < 20) return null;
            byte[] foot = MotionPhoto.read(ch, size - 8, 8);
            if (!type(foot, 4).equals("SEFT")) return null;
            long len = le32(foot, 0);
            if (len < 12 || len > MAX_SEF_INDEX || len > size - 8) return null;
            Sef s = new Sef();
            s.sefh = size - 8 - len;
            s.index = MotionPhoto.read(ch, s.sefh, (int) len);
            if (!type(s.index, 0).equals("SEFH")) return null;
            long count = le32(s.index, 8);
            if (count > (len - 12) / 12) return null;
            s.count = (int) count;
            s.firstStart = s.sefh;
            for (int i = 0; i < s.count; i++) {
                int e = 12 + 12 * i;
                Record r = new Record();
                r.entry = java.util.Arrays.copyOfRange(s.index, e, e + 12);
                long off = le32(s.index, e + 4);
                r.size = le32(s.index, e + 8);
                r.start = s.sefh - off;
                if (r.start >= 0 && r.size >= 8 && r.start + r.size <= s.sefh) {
                    byte[] h = MotionPhoto.read(ch, r.start, 8);
                    long nl = le32(h, 4);
                    if (nl <= 256 && 8 + nl <= r.size && le16(h, 2) == le16(s.index, e + 2)) {
                        r.name = new String(MotionPhoto.read(ch, r.start + 8, (int) nl), StandardCharsets.ISO_8859_1);
                        r.valid = true;
                    }
                }
                s.firstStart = Math.min(s.firstStart, Math.max(0, r.start));
                s.records.add(r);
            }
            return s;
        }

        /** Marks the records whose data is an MP4 video; true if there is one. */
        boolean hasVideo(FileChannel ch) throws IOException {
            boolean any = false;
            for (Record r : records) {
                if (!r.valid) continue;
                long data = r.start + 8 + r.name.length();
                if (data + 8 <= r.start + r.size && mp4End(ch, data, r.start + r.size) > 0) { r.video = true; any = true; }
            }
            return any;
        }

        /** Keep everything but the video records; rewrite the index with the records' new offsets. */
        String plan(FileChannel ch, Found f) throws IOException {
            for (Record r : records) if (!r.valid) return "Samsung's index lists a record that cannot be read";
            List<Record> gone = new ArrayList<Record>(), kept = new ArrayList<Record>();
            for (Record r : records) (r.video ? gone : kept).add(r);
            java.util.Collections.sort(gone, new java.util.Comparator<Record>() {
                public int compare(Record a, Record b) { return Long.compare(a.start, b.start); }
            });
            for (int i = 0; i < gone.size(); i++) {
                for (int j = i + 1; j < gone.size(); j++) if (gone.get(j).start < gone.get(i).start + gone.get(i).size) return "Samsung's video records overlap";
                for (Record k : kept)
                    if (k.start < gone.get(i).start + gone.get(i).size && gone.get(i).start < k.start + k.size)
                        return "Samsung's records overlap the video";
            }
            long p = 0, removed = 0;
            for (Record g : gone) {
                long data = g.start + 8 + g.name.length();
                f.videos.add(new long[]{data, mp4End(ch, data, g.start + g.size)});
                f.keep.add(new long[]{p, g.start});
                p = g.start + g.size;
                removed += g.size;
            }
            f.keep.add(new long[]{p, sefh});
            for (int i = f.keep.size() - 1; i >= 0; i--) if (f.keep.get(i)[1] <= f.keep.get(i)[0]) f.keep.remove(i);
            long newSefh = sefh - removed;
            int extra = index.length - 12 - 12 * count;         // bytes after the entries, kept as they are
            byte[] t = new byte[12 + 12 * kept.size() + extra + 8];
            System.arraycopy(index, 0, t, 0, 8);                // SEFH, version
            putLe32(t, 8, kept.size());
            for (int i = 0; i < kept.size(); i++) {
                Record r = kept.get(i);
                long shift = 0;
                for (Record g : gone) if (g.start < r.start) shift += g.size;
                System.arraycopy(r.entry, 0, t, 12 + 12 * i, 12);
                putLe32(t, 12 + 12 * i + 4, newSefh - (r.start - shift));
            }
            System.arraycopy(index, 12 + 12 * count, t, 12 + 12 * kept.size(), extra);
            putLe32(t, t.length - 8, t.length - 8);
            t[t.length - 4] = 'S'; t[t.length - 3] = 'E'; t[t.length - 2] = 'F'; t[t.length - 1] = 'T';
            f.tail = t;
            f.tailRecords = kept.size();
            f.notes.add(String.format(Locale.US, "Samsung index: %d record(s) kept (%s), %d video record(s) removed",
                    kept.size(), names(kept), gone.size()));
            return null;
        }

        private static String names(List<Record> rs) {
            StringBuilder b = new StringBuilder();
            for (Record r : rs) b.append(b.length() > 0 ? ", " : "").append(r.name);
            return b.toString();
        }
    }

    // ------------------------------------------------------------------ MP4 boxes
    /** {size, header size} of the box at {@code p} if it is a plausible box ending by {@code limit}, else null. */
    static long[] box(FileChannel ch, long p, long limit) throws IOException {
        if (p + 8 > limit) return null;
        byte[] h = read(ch, p, 8);
        for (int i = 4; i < 8; i++) if (h[i] < 0x20 || h[i] > 0x7E) return null;
        long size = u32(h, 0, false), hdr = 8;
        if (size == 1) {
            if (p + 16 > limit) return null;
            size = u64(read(ch, p + 8, 8));
            hdr = 16;
        } else if (size == 0) {
            size = limit - p;
        }
        if (size < hdr || size > limit - p) return null;
        return new long[]{size, hdr};
    }

    /**
     * The end of the chain of boxes that starts at {@code p}, if it is an MP4/MOV video (an {@code ftyp} box first,
     * a {@code moov} box among them) ending by {@code limit}; else -1.
     */
    static long mp4End(FileChannel ch, long p, long limit) throws IOException {
        long q = p;
        boolean moov = false;
        for (int n = 0; ; n++) {
            long[] b = box(ch, q, limit);
            if (b == null) break;
            String t = type(read(ch, q + 4, 4), 0);
            if (n == 0 && !t.equals("ftyp")) return -1;
            if (t.equals("moov")) moov = true;
            q += b[0];
        }
        return moov ? q : -1;
    }

    // ------------------------------------------------------------------ bytes
    /** Buffered reading through a channel at any position. */
    private static final class Reader {
        final FileChannel ch;
        final ByteBuffer buf = ByteBuffer.allocate(1 << 16);
        long pos, bufStart = -1;
        int bufLen;
        Reader(FileChannel ch) { this.ch = ch; }

        int read() throws IOException {
            if (bufStart < 0 || pos < bufStart || pos >= bufStart + bufLen) {
                buf.clear();
                bufStart = pos;
                while (buf.hasRemaining()) if (ch.read(buf, bufStart + buf.position()) < 0) break;
                bufLen = buf.position();
                if (bufLen == 0) return -1;
            }
            return buf.get((int) (pos++ - bufStart)) & 0xFF;
        }
    }

    static byte[] read(FileChannel ch, long pos, int n) throws IOException {
        byte[] b = new byte[n];
        readInto(ch, pos, b, n);
        return b;
    }

    private static void readInto(FileChannel ch, long pos, byte[] b, int n) throws IOException {
        ByteBuffer bb = ByteBuffer.wrap(b, 0, n);
        while (bb.hasRemaining()) if (ch.read(bb, pos + bb.position()) < 0) throw new EOFException("end of file at " + (pos + bb.position()));
    }

    /** True if every byte in [from, to) is zero (padding). */
    private static boolean zeros(FileChannel ch, long from, long to) throws IOException {
        byte[] buf = new byte[1 << 16];
        for (long p = from; p < to; ) {
            int n = (int) Math.min(buf.length, to - p);
            readInto(ch, p, buf, n);
            for (int i = 0; i < n; i++) if (buf[i] != 0) return false;
            p += n;
        }
        return true;
    }

    /** First position >= from of the ASCII {@code s} wholly inside [from, to), or -1. */
    static long indexOf(FileChannel ch, long from, long to, String s) throws IOException {
        byte[] k = s.getBytes(StandardCharsets.US_ASCII);
        byte[] buf = new byte[1 << 16];
        for (long p = Math.max(0, from); p + k.length <= to; ) {
            int n = (int) Math.min(buf.length, to - p);
            readInto(ch, p, buf, n);
            outer:
            for (int i = 0; i + k.length <= n; i++) {
                for (int j = 0; j < k.length; j++) if (buf[i + j] != k[j]) continue outer;
                return p + i;
            }
            if (p + n >= to) break;
            p += n - k.length + 1;
        }
        return -1;
    }

    private static boolean startsWith(byte[] d, byte[] prefix) {
        if (prefix.length > d.length) return false;
        for (int i = 0; i < prefix.length; i++) if (d[i] != prefix[i]) return false;
        return true;
    }

    private static String type(byte[] b, int p) { return new String(b, p, 4, StandardCharsets.ISO_8859_1); }

    private static int u16(byte[] b, int p, boolean le) {
        return le ? (b[p] & 0xFF) | (b[p + 1] & 0xFF) << 8 : (b[p] & 0xFF) << 8 | (b[p + 1] & 0xFF);
    }

    private static long u32(byte[] b, int p, boolean le) {
        long v = 0;
        for (int i = 0; i < 4; i++) v |= (long) (b[p + (le ? i : 3 - i)] & 0xFF) << (8 * i);
        return v;
    }

    private static long u64(byte[] b) { long v = 0; for (int i = 0; i < 8; i++) v = v << 8 | (b[i] & 0xFF); return v; }

    private static int le16(byte[] b, int p) { return u16(b, p, true); }

    private static long le32(byte[] b, int p) { return u32(b, p, true); }

    private static void putLe32(byte[] b, int p, long v) { for (int i = 0; i < 4; i++) b[p + i] = (byte) (v >> (8 * i)); }

    /** A reason to stop that is not a bug (shown as {@code skipped} without the exception's class). */
    private static final class Stop extends IOException { Stop(String m) { super(m); } }
}
