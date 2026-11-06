package com.storageplanner.test;

import com.storageplanner.tools.ZipCompressor;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Desktop tests for tools/ZipCompressor: archives read back byte for byte, names, levels, refusals, safety. */
public final class ZipTest {
    static int failed = 0, passed = 0;

    static void check(String what, boolean ok) {
        if (ok) passed++; else { failed++; System.out.println("FAIL " + what); }
    }

    public static void main(String[] a) throws Exception {
        File dir = Files.createTempDirectory("ziptest").toFile();
        try {
            names();
            roundTrip(dir);
            levels(dir);
            folder(dir);
            refusals(dir);
            changing(dir);
        } finally {
            rm(dir);
        }
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    // ------------------------------------------------------------------ helpers
    static File write(File f, byte[] b) throws IOException {
        f.getParentFile().mkdirs();
        FileOutputStream o = new FileOutputStream(f);
        try { o.write(b); } finally { o.close(); }
        return f;
    }

    static byte[] text(int n) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; s.length() < n; i++) s.append("line ").append(i).append(": the quick brown fox jumps over the lazy dog\n");
        return s.substring(0, n).getBytes(StandardCharsets.UTF_8);
    }

    static byte[] random(int n, long seed) { byte[] b = new byte[n]; new Random(seed).nextBytes(b); return b; }

    static byte[] read(ZipFile z, ZipEntry e) throws IOException {
        InputStream in = z.getInputStream(e);
        try {
            java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[8192]; int k;
            while ((k = in.read(b)) > 0) o.write(b, 0, k);
            return o.toByteArray();
        } finally { in.close(); }
    }

    static void rm(File f) {
        File[] c = f.listFiles();
        if (c != null) for (File x : c) rm(x);
        f.delete();
    }

    static boolean noWorkFiles(File dir) {
        String[] n = dir.list();
        if (n != null) for (String s : n) if (s.startsWith(".sp-")) return false;
        return true;
    }

    // ------------------------------------------------------------------ names
    static void names() {
        check("plain name kept", ZipCompressor.entryName("a.txt").equals("a.txt"));
        check("sub folder kept", ZipCompressor.entryName("dir/a.txt").equals("dir/a.txt"));
        check(".. removed", ZipCompressor.entryName("../../etc/passwd").equals("etc/passwd"));
        check("leading / removed", ZipCompressor.entryName("/sdcard/a.jpg").equals("sdcard/a.jpg"));
        check("backslashes become /", ZipCompressor.entryName("a\\b\\c.txt").equals("a/b/c.txt"));
        check("drive letter removed", ZipCompressor.entryName("C:\\x\\y.txt").equals("x/y.txt"));
        check("control characters replaced", ZipCompressor.entryName("a\nb\u0000.txt").equals("a_b_.txt"));
        check("empty name gets one", ZipCompressor.entryName("../").equals("file") && ZipCompressor.entryName(null).equals("file"));
        check("unicode kept", ZipCompressor.entryName("फोटो é.jpg").equals("फोटो é.jpg"));
        Set<String> t = new HashSet<String>();
        check("first name unique", ZipCompressor.unique("IMG.jpg", t).equals("IMG.jpg"));
        check("second gets (2)", ZipCompressor.unique("IMG.jpg", t).equals("IMG (2).jpg"));
        check("case-insensitive duplicate gets (3)", ZipCompressor.unique("img.JPG", t).equals("img (3).JPG"));
        check("no extension", ZipCompressor.unique("README", t).equals("README") && ZipCompressor.unique("README", t).equals("README (2)"));
        check("dot in folder is not an extension", ZipCompressor.unique("a.b/c", t).equals("a.b/c") && ZipCompressor.unique("a.b/c", t).equals("a.b/c (2)"));
    }

    // ------------------------------------------------------------------ round trip
    static void roundTrip(File dir) throws Exception {
        File src = new File(dir, "rt");
        byte[] txt = text(300_000), rnd = random(3_000_000, 1), small = "hi".getBytes(StandardCharsets.UTF_8);
        File f1 = write(new File(src, "notes.txt"), txt);
        File f2 = write(new File(src, "photo.jpg"), rnd);           // incompressible, like a JPEG
        File f3 = write(new File(src, "empty.dat"), new byte[0]);
        File f4 = write(new File(src, "other/notes.txt"), small);   // same name as f1
        File f5 = write(new File(src, "नमस्ते.txt"), txt);
        long when = 1_600_000_000_000L;                             // 2020-09-13, an even number of seconds
        f1.setLastModified(when);
        List<ZipCompressor.Source> s = new ArrayList<ZipCompressor.Source>();
        for (File f : new File[]{f1, f2, f3, f4, f5}) s.add(ZipCompressor.file(f));
        File out = new File(dir, "rt.zip");
        final List<String> seen = new ArrayList<String>();
        ZipCompressor.Outcome o = ZipCompressor.zip(s, out, ZipCompressor.Level.BALANCED, new ZipCompressor.Progress() {
            public boolean onFile(int i, int n, String name) { seen.add(i + "/" + n); return true; }
        });
        check("round trip ok: " + o.skipped, o.ok && out.isFile() && o.output.equals(out.getPath()));
        check("progress called per file", seen.equals(Arrays.asList("0/5", "1/5", "2/5", "3/5", "4/5")));
        check("before is the sum", o.before == txt.length * 2L + rnd.length + small.length);
        check("after is the archive size", o.after == out.length());
        check("text shrinks a lot", o.after < rnd.length + 100_000);
        check("no work file left", noWorkFiles(dir));
        check("originals untouched", f1.length() == txt.length && f2.length() == rnd.length && f1.lastModified() == when);
        check("random file stored", o.entries.get(1).stored && o.entries.get(1).tested < ZipCompressor.MIN_ENTRY_SAVING);
        check("text deflated", !o.entries.get(0).stored && o.entries.get(0).tested > 0.5);
        check("random and 2-byte file stored (deflate would grow them)", o.stored() == 2 && o.entries.get(3).stored && !o.entries.get(2).stored);
        ZipFile z = new ZipFile(out);
        try {
            List<String> names = new ArrayList<String>();
            for (ZipEntry e : Collections.list(z.entries())) names.add(e.getName());
            check("names in order, duplicate renamed: " + names,
                    names.equals(Arrays.asList("notes.txt", "photo.jpg", "empty.dat", "notes (2).txt", "नमस्ते.txt")));
            check("text bytes identical", Arrays.equals(read(z, z.getEntry("notes.txt")), txt));
            check("random bytes identical", Arrays.equals(read(z, z.getEntry("photo.jpg")), rnd));
            check("empty file empty", read(z, z.getEntry("empty.dat")).length == 0);
            check("second notes is the small one", Arrays.equals(read(z, z.getEntry("notes (2).txt")), small));
            check("unicode entry identical", Arrays.equals(read(z, z.getEntry("नमस्ते.txt")), txt));
            check("stored method for random", z.getEntry("photo.jpg").getMethod() == ZipEntry.STORED);
            check("deflated method for text", z.getEntry("notes.txt").getMethod() == ZipEntry.DEFLATED);
            check("modified time kept: " + z.getEntry("notes.txt").getTime(), Math.abs(z.getEntry("notes.txt").getTime() - when) < 2000);
            check("exact time in the extended field", z.getEntry("notes.txt").getLastModifiedTime().toMillis() / 1000 == when / 1000);
        } finally { z.close(); }

        ZipCompressor.Outcome again = ZipCompressor.zip(s, out, ZipCompressor.Level.BALANCED);
        check("an existing archive is never replaced", !again.ok && again.skipped.contains("already exists") && out.length() == o.after);
    }

    // ------------------------------------------------------------------ levels
    static void levels(File dir) throws Exception {
        File f = write(new File(dir, "lv/log.txt"), text(2_000_000));
        File r = write(new File(dir, "lv/video.mp4"), random(500_000, 2));
        List<ZipCompressor.Source> s = Arrays.asList(ZipCompressor.file(f), ZipCompressor.file(r));
        long[] size = new long[4];
        for (ZipCompressor.Level l : ZipCompressor.Level.values()) {
            ZipCompressor.Outcome o = ZipCompressor.zip(s, new File(dir, "lv-" + l.number() + ".zip"), l);
            check("level " + l + " ok: " + o.skipped, o.ok);
            size[l.number()] = o.after;
            if (l == ZipCompressor.Level.STORE) check("store: every entry stored, none tested", o.stored() == 2 && o.entries.get(0).tested < 0);
            else check(l + ": random stored, text deflated", o.entries.get(1).stored && !o.entries.get(0).stored);
        }
        check("store is about the sources' size", size[0] >= 2_500_000 && size[0] < 2_501_000);
        check("fast < store", size[1] < size[0] / 3);
        check("maximum <= balanced <= fast", size[3] <= size[2] && size[2] <= size[1]);
    }

    // ------------------------------------------------------------------ folders
    static void folder(File dir) throws Exception {
        File d = new File(dir, "Trip");
        write(new File(d, "b.txt"), text(1000));
        write(new File(d, "a.txt"), text(1000));
        write(new File(d, "day 2/c.txt"), text(1000));
        new File(d, "empty").mkdirs();
        List<ZipCompressor.Source> s = ZipCompressor.folder(d);
        List<String> n = new ArrayList<String>();
        for (ZipCompressor.Source x : s) n.add(x.name());
        check("folder walked, sorted, named under the folder: " + n, n.equals(Arrays.asList("Trip/a.txt", "Trip/b.txt", "Trip/day 2/c.txt")));
        try {
            File link = new File(d, "loop");
            Files.createSymbolicLink(link.toPath(), d.toPath());
            File out = new File(dir, "outside.txt");
            write(out, text(10));
            Files.createSymbolicLink(new File(d, "out.txt").toPath(), out.toPath());
            List<String> n2 = new ArrayList<String>();
            for (ZipCompressor.Source x : ZipCompressor.folder(d)) n2.add(x.name());
            check("links back in or out are skipped: " + n2, n2.equals(n));
        } catch (UnsupportedOperationException | IOException e) {
            System.out.println("(symbolic links not supported here: skipped)");
        }
        ZipCompressor.Outcome o = ZipCompressor.zip(s, new File(dir, "trip.zip"), ZipCompressor.Level.MAXIMUM);
        check("folder zip ok", o.ok && o.entries.size() == 3);
    }

    // ------------------------------------------------------------------ refusals
    static void refusals(File dir) throws Exception {
        File f = write(new File(dir, "rf/a.txt"), text(100));
        List<ZipCompressor.Source> one = Collections.singletonList(ZipCompressor.file(f));
        ZipCompressor.Outcome o = ZipCompressor.zip(new ArrayList<ZipCompressor.Source>(), new File(dir, "rf/none.zip"), ZipCompressor.Level.FAST);
        check("no files refused", !o.ok && o.skipped.contains("no files"));
        o = ZipCompressor.zip(one, new File(dir, "missing/x.zip"), ZipCompressor.Level.FAST);
        check("missing folder refused", !o.ok && o.skipped.contains("does not exist"));
        o = ZipCompressor.zip(one, f, ZipCompressor.Level.FAST);
        check("archive over a source refused (exists)", !o.ok && f.length() == 100);
        o = ZipCompressor.zip(Collections.singletonList(ZipCompressor.file(new File(dir, "rf/gone.txt"))), new File(dir, "rf/g.zip"), ZipCompressor.Level.FAST);
        check("missing source refused", !o.ok && o.skipped.contains("not a file") && !new File(dir, "rf/g.zip").exists());
        File out = new File(dir, "rf/stop.zip");
        o = ZipCompressor.zip(Arrays.asList(ZipCompressor.file(f), ZipCompressor.file(f, "b.txt")), out, ZipCompressor.Level.FAST, new ZipCompressor.Progress() {
            public boolean onFile(int i, int n, String name) { return i == 0; }
        });
        check("stopping keeps nothing", !o.ok && o.skipped.equals("stopped") && !out.exists() && noWorkFiles(new File(dir, "rf")) && o.entries.isEmpty());
    }

    // ------------------------------------------------------------------ a source that changes
    /** 1000 zero bytes (compressible, so deflated); with changeSize its size grows by one after the test pass. */
    static final class Changing implements ZipCompressor.Source {
        int opens;
        final boolean changeSize;
        Changing(boolean changeSize) { this.changeSize = changeSize; }
        public String name() { return "changing.bin"; }
        public long size() { return changeSize && opens >= 2 ? 1001 : 1000; }
        public long modified() { return 0; }
        public InputStream open() { opens++; return new ByteArrayInputStream(new byte[1000]); }
        public File file() { return null; }
    }

    static void changing(File dir) throws Exception {
        File d = new File(dir, "ch");
        d.mkdirs();
        // STORE: the CRC pass sees 1,2,3; the write pass sees 9,9,9 -> the stored CRC no longer matches
        ZipCompressor.Outcome o = ZipCompressor.zip(Collections.<ZipCompressor.Source>singletonList(new StoreChanging()), new File(d, "a.zip"), ZipCompressor.Level.STORE);
        check("content change while storing is caught: " + o.skipped, !o.ok && !new File(d, "a.zip").exists() && noWorkFiles(d));
        o = ZipCompressor.zip(Collections.<ZipCompressor.Source>singletonList(new Changing(true)), new File(d, "b.zip"), ZipCompressor.Level.BALANCED);
        check("size change while deflating is caught: " + o.skipped, !o.ok && o.skipped.contains("changed") && !new File(d, "b.zip").exists() && noWorkFiles(d));
        o = ZipCompressor.zip(Collections.<ZipCompressor.Source>singletonList(new Changing(false)), new File(d, "c.zip"), ZipCompressor.Level.BALANCED);
        check("a stream source (no file) zips: " + o.skipped, o.ok && o.entries.get(0).size == 1000 && !o.entries.get(0).stored);
    }

    /** First open (CRC pass) and second (write pass) see different bytes. */
    static final class StoreChanging implements ZipCompressor.Source {
        int opens;
        public String name() { return "s.bin"; }
        public long size() { return 3; }
        public long modified() { return 0; }
        public InputStream open() { opens++; return new ByteArrayInputStream(opens == 1 ? new byte[]{1, 2, 3} : new byte[]{9, 9, 9}); }
        public File file() { return null; }
    }
}
