package com.storageplanner.tools;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Puts the files it is given into one checked .zip archive. Plain Java (java.util.zip only), so it runs the same on
 * the phone and on a desktop, and is tested there.
 *
 * <p>The originals are only read, never changed, moved or deleted. The archive is first written to a hidden work
 * file next to {@code out}; every entry is read back from it and must give exactly the bytes that were read from its
 * source (same length, same CRC-32); only then is the work file renamed to {@code out}. An existing file is never
 * replaced.
 *
 * <p>Files that are already compressed (photos, videos, music, other archives) shrink by a percent or two at best,
 * so each file's first and middle megabyte is test-compressed first, and a file that saves under
 * {@link #MIN_ENTRY_SAVING} is stored as it is: that is as small as Deflate gets it and several times faster.
 */
public final class ZipCompressor {
    private ZipCompressor() {}

    /** How hard Deflate works. All levels make a standard .zip any phone or computer opens. */
    public enum Level {
        STORE("Store only", Deflater.NO_COMPRESSION),
        FAST("Fast", 1),
        BALANCED("Balanced", 6),
        MAXIMUM("Maximum", 9);

        public final String label;
        /** java.util.zip.Deflater level (0-9); STORE writes entries with ZipEntry.STORED instead. */
        public final int deflate;

        Level(String label, int deflate) { this.label = label; this.deflate = deflate; }

        public int number() { return ordinal(); }
    }

    /** A file to put into the archive. {@link #open()} may be called more than once (test, CRC, write passes). */
    public interface Source {
        /** Path inside the archive, '/' separated (cleaned by {@link #entryName}). */
        String name();
        /** Size in bytes, or -1 when unknown. */
        long size();
        /** Last-modified time in ms since 1970, or 0 when unknown. */
        long modified();
        InputStream open() throws IOException;
        /** The file on this device, if it is one (to refuse writing the archive over it), else null. */
        File file();
    }

    /** Called before each file; return false to stop (the work file is then deleted and nothing is kept). */
    public interface Progress { boolean onFile(int index, int count, String name); }

    /** What happened to one file. */
    public static final class Entry {
        public String name, source;
        public long size, compressed, crc;
        /** True when stored as it is (STORE level, or already compressed). */
        public boolean stored;
        /** Saving the test compression measured (0..1), or -1 when not tested. */
        public double tested = -1;
    }

    public static final class Outcome {
        /** The archive is at {@link #output} and every entry was read back and matched. */
        public boolean ok;
        public String output, skipped;
        public Level level;
        /** Sum of the sources' sizes, and the archive's size, in bytes. */
        public long before, after;
        public long ms;
        public final List<Entry> entries = new ArrayList<Entry>();
        public final List<String> trace = new ArrayList<String>();

        public int stored() { int n = 0; for (Entry e : entries) if (e.stored) n++; return n; }

        /** Saving as a fraction of {@link #before} (negative when the archive is bigger). */
        public double saving() { return before > 0 ? 1.0 - (double) after / before : 0; }
    }

    /** A file saving under this in the test compression is stored as it is. */
    public static final double MIN_ENTRY_SAVING = 0.03;
    /** How much of a file is test-compressed, at its start and at its middle. */
    public static final int TEST_BYTES = 1 << 20;
    /** Room kept free besides the archive's worst-case size. */
    public static final long SPARE_BYTES = 16L << 20;

    // ------------------------------------------------------------------ sources
    public static Source file(File f) { return file(f, f.getName()); }

    public static Source file(final File f, final String nameInZip) {
        return new Source() {
            public String name() { return nameInZip; }
            public long size() { return f.length(); }
            public long modified() { return f.lastModified(); }
            public InputStream open() throws IOException { return new FileInputStream(f); }
            public File file() { return f; }
            @Override public String toString() { return f.getPath(); }
        };
    }

    /**
     * Every file under {@code dir}, named {@code <dir name>/<path below it>}, sorted by name. Links that lead out of
     * the folder or back into it are skipped (no loops), and so are empty folders (a .zip of files only).
     */
    public static List<Source> folder(File dir) throws IOException {
        List<Source> out = new ArrayList<Source>();
        File root = dir.getCanonicalFile();
        walk(root, root, dir.getName(), out, new HashSet<String>());
        return out;
    }

    private static void walk(File root, File d, String prefix, List<Source> out, Set<String> seen) throws IOException {
        File c = d.getCanonicalFile();
        if (!seen.add(c.getPath())) return;
        String[] names = d.list();
        if (names == null) return;
        Arrays.sort(names);
        for (String n : names) {
            File f = new File(d, n);
            File cf = f.getCanonicalFile();
            if (!cf.getPath().startsWith(root.getPath() + File.separator)) continue;   // a link out of the folder
            if (cf.isDirectory()) walk(root, f, prefix + "/" + n, out, seen);
            else if (cf.isFile()) out.add(file(f, prefix + "/" + n));
        }
    }

    // ------------------------------------------------------------------ names
    /**
     * A safe path inside the archive: '/' separators, no drive or leading '/', no "." or ".." parts, no control
     * characters, so unzipping can never write outside the folder it unzips into. Never empty.
     */
    public static String entryName(String name) {
        String s = name == null ? "" : name.replace('\\', '/');
        StringBuilder b = new StringBuilder();
        for (String part : s.split("/")) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) continue;
            if (b.length() == 0 && part.length() == 2 && part.charAt(1) == ':') continue;   // "C:"
            StringBuilder p = new StringBuilder();
            for (int i = 0; i < part.length(); i++) { char ch = part.charAt(i); p.append(ch < 32 || ch == 127 ? '_' : ch); }
            if (b.length() > 0) b.append('/');
            b.append(p);
        }
        return b.length() == 0 ? "file" : b.toString();
    }

    /** {@code name}, or "name (2).ext", "name (3).ext"... when the name is taken (ignoring case, as Windows does). */
    public static String unique(String name, Set<String> taken) {
        String n = name;
        int slash = name.lastIndexOf('/'), dot = name.lastIndexOf('.');
        String stem = dot > slash + 1 ? name.substring(0, dot) : name, ext = dot > slash + 1 ? name.substring(dot) : "";
        for (int k = 2; taken.contains(n.toLowerCase(Locale.ROOT)); k++) n = stem + " (" + k + ")" + ext;
        taken.add(n.toLowerCase(Locale.ROOT));
        return n;
    }

    // ------------------------------------------------------------------ zip
    public static Outcome zip(List<Source> sources, File out, Level level) { return zip(sources, out, level, null); }

    /** Blocking: call it off the main thread. */
    public static Outcome zip(List<Source> sources, File out, Level level, Progress progress) {
        long t0 = System.nanoTime();
        Outcome o = new Outcome();
        o.level = level;
        File tmp = null;
        try {
            String why = refuse(sources, out);
            if (why != null) { o.skipped = why; return o; }
            for (Source s : sources) if (s.size() > 0) o.before += s.size();
            long free = out.getAbsoluteFile().getParentFile().getUsableSpace();
            // stored entries + headers: never more than the sources plus ~0.1%; Deflate adds at most 5 bytes per 16 KB
            long need = o.before + o.before / 500 + SPARE_BYTES;
            if (free < need) { o.skipped = String.format(Locale.US, "not enough free space: %d bytes free, need about %d", free, need); return o; }
            o.trace.add(String.format(Locale.US, "%d files, %d bytes, level %s", sources.size(), o.before, level.label));

            tmp = new File(CopyPaths.tempPath(out.getAbsolutePath()));
            if (tmp.exists() && !tmp.delete()) { o.skipped = "cannot remove an old work file " + tmp; tmp = null; return o; }
            Set<String> taken = new HashSet<String>();
            ZipOutputStream z = new ZipOutputStream(new FileOutputStream(tmp));
            try {
                z.setLevel(level == Level.STORE ? Deflater.NO_COMPRESSION : level.deflate);
                for (int i = 0; i < sources.size(); i++) {
                    Source s = sources.get(i);
                    if (progress != null && !progress.onFile(i, sources.size(), s.name())) throw new Stop("stopped");
                    o.entries.add(add(z, s, unique(entryName(s.name()), taken), level, o.trace));
                }
            } finally {
                z.close();
            }
            verify(tmp, o.entries);
            if (out.exists()) throw new Stop("a file appeared at " + out + " meanwhile");
            if (!tmp.renameTo(out)) throw new Stop("cannot rename the work file to " + out);
            tmp = null;
            o.after = out.length();
            o.output = out.getPath();
            o.ok = true;
            o.trace.add(String.format(Locale.US, "verified %d entries; %d -> %d bytes (%.1f%% saved), %d stored as they are",
                    o.entries.size(), o.before, o.after, 100 * o.saving(), o.stored()));
        } catch (Stop e) {
            o.skipped = e.getMessage();
        } catch (Throwable t) {
            o.skipped = "failed: " + t;
        } finally {
            if (tmp != null && tmp.exists() && !tmp.delete()) o.trace.add("could not remove the work file " + tmp);
            o.ms = (System.nanoTime() - t0) / 1_000_000;
            if (!o.ok) { o.entries.clear(); o.trace.add("not made: " + o.skipped); }
        }
        return o;
    }

    private static String refuse(List<Source> sources, File out) throws IOException {
        if (sources == null || sources.isEmpty()) return "no files to zip";
        if (out.exists()) return "the archive already exists: " + out + " (a tool never replaces a file)";
        File dir = out.getAbsoluteFile().getParentFile();
        if (dir == null || !dir.isDirectory()) return "the archive's folder does not exist: " + dir;
        String co = out.getCanonicalPath();
        for (Source s : sources) {
            File f = s.file();
            if (f == null) continue;
            if (!f.isFile()) return "not a file: " + f;
            if (f.getCanonicalPath().equals(co)) return "the archive would overwrite one of its own files: " + f;
        }
        return null;
    }

    /** One entry: test-compress, then (stored) a CRC pass, then the write pass; the source must not change meanwhile. */
    private static Entry add(ZipOutputStream z, Source s, String name, Level level, List<String> trace) throws IOException {
        Entry e = new Entry();
        e.name = name;
        e.source = String.valueOf(s);
        long size0 = s.size(), mod0 = s.modified();
        e.stored = level == Level.STORE;
        if (!e.stored) {
            e.tested = testSaving(s, size0, level.deflate);
            e.stored = e.tested < MIN_ENTRY_SAVING;
        }
        ZipEntry ze = new ZipEntry(name);
        if (mod0 > 0) { ze.setTime(mod0); ze.setLastModifiedTime(FileTime.fromMillis(mod0)); }
        if (e.stored) {
            long[] sc = crc(s);
            ze.setMethod(ZipEntry.STORED);
            ze.setSize(sc[0]); ze.setCompressedSize(sc[0]); ze.setCrc(sc[1]);
        } else {
            ze.setMethod(ZipEntry.DEFLATED);
        }
        z.putNextEntry(ze);
        CRC32 crc = new CRC32();
        long n = 0;
        InputStream in = s.open();
        try {
            byte[] buf = new byte[1 << 16];
            int k;
            while ((k = in.read(buf)) > 0) { z.write(buf, 0, k); crc.update(buf, 0, k); n += k; }
        } finally { in.close(); }
        z.closeEntry();      // for STORED this throws if the bytes differ from the CRC pass
        e.size = n; e.crc = crc.getValue();
        e.compressed = ze.getCompressedSize();
        if ((size0 >= 0 && n != size0) || s.size() != size0 || s.modified() != mod0)
            throw new Stop("the file changed while it was zipped: " + s.name());
        trace.add(String.format(Locale.US, "  %s: %d -> %d bytes, %s%s", name, n, e.compressed, e.stored ? "stored" : "deflated",
                e.tested >= 0 ? String.format(Locale.US, " (test saved %.1f%%)", 100 * e.tested) : ""));
        return e;
    }

    /** Saving of Deflate at {@code level} on the first and the middle {@link #TEST_BYTES} of the file. */
    static double testSaving(Source s, long size, int level) throws IOException {
        long in = 0, outBytes = 0;
        long[] at = size > 2L * TEST_BYTES ? new long[]{0, size / 2} : new long[]{0};
        byte[] buf = new byte[TEST_BYTES], dst = new byte[1 << 16];
        for (long start : at) {
            InputStream is = s.open();
            int got = 0;
            try {
                skipFully(is, start);
                int k;
                while (got < buf.length && (k = is.read(buf, got, buf.length - got)) > 0) got += k;
            } finally { is.close(); }
            if (got == 0) continue;
            Deflater d = new Deflater(level, true);
            try {
                d.setInput(buf, 0, got);
                d.finish();
                while (!d.finished()) outBytes += d.deflate(dst);
            } finally { d.end(); }
            in += got;
        }
        return in == 0 ? 1.0 : 1.0 - (double) outBytes / in;     // an empty file "saves" everything: deflate it
    }

    private static void skipFully(InputStream in, long n) throws IOException {
        while (n > 0) {
            long k = in.skip(n);
            if (k <= 0) { if (in.read() < 0) return; k = 1; }
            n -= k;
        }
    }

    /** {length, CRC-32} of the source's bytes. */
    private static long[] crc(Source s) throws IOException {
        CRC32 c = new CRC32();
        long n = 0;
        InputStream in = s.open();
        try {
            byte[] buf = new byte[1 << 16];
            int k;
            while ((k = in.read(buf)) > 0) { c.update(buf, 0, k); n += k; }
        } finally { in.close(); }
        return new long[]{n, c.getValue()};
    }

    /** Reads every entry back from the archive: same names in the same order, same length and CRC-32 as was written. */
    static void verify(File zipFile, List<Entry> entries) throws IOException {
        ZipFile zf = new ZipFile(zipFile);
        try {
            List<? extends ZipEntry> got = Collections.list(zf.entries());
            if (got.size() != entries.size()) throw new Stop("check failed: " + got.size() + " entries read back, " + entries.size() + " written");
            for (int i = 0; i < got.size(); i++) {
                ZipEntry ze = got.get(i);
                Entry e = entries.get(i);
                if (!ze.getName().equals(e.name)) throw new Stop("check failed: entry " + i + " is " + ze.getName() + ", not " + e.name);
                CRC32 c = new CRC32();
                long n = 0;
                InputStream in = zf.getInputStream(ze);
                try {
                    byte[] buf = new byte[1 << 16];
                    int k;
                    while ((k = in.read(buf)) > 0) { c.update(buf, 0, k); n += k; }
                } finally { in.close(); }
                if (n != e.size || c.getValue() != e.crc || ze.getCrc() != e.crc)
                    throw new Stop("check failed: " + e.name + " reads back as " + n + " bytes, CRC " + Long.toHexString(c.getValue()));
            }
        } finally { zf.close(); }
    }

    /** A reason to stop that is not a bug (shown as {@code skipped} without the exception's class). */
    private static final class Stop extends IOException { Stop(String m) { super(m); } }
}
