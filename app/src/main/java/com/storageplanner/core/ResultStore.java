package com.storageplanner.core;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.CheckedInputStream;
import java.util.zip.CheckedOutputStream;

/**
 * Compact, versioned binary snapshot of the last analysis: the scan result, the estimates and the
 * user's settings. Units and plans are NOT stored; they are rebuilt with UnitBuilder (deterministic).
 * Pure Java (no Android), so it is unit-tested on the JVM; the app supplies the streams (AtomicFile).
 *
 * Layout: int MAGIC, int VERSION, payload, long CRC32(payload). Repeated short strings (area, type,
 * package, codec, path prefix) go through a string pool written inline on first use.
 */
public final class ResultStore {
    private ResultStore() {}

    public static final int MAGIC = 0x53504C52;     // "SPLR"
    public static final int VERSION = 3;            // 2 (app 1.5): photo quality of near-identical shots; 3 (1.6): picture categories
    static final int OLDEST_READABLE = 1;
    private static final int MAX_ITEMS = 5000000, MAX_STRING = 1 << 20;

    /** Everything needed to rebuild Session after process death. */
    public static final class Saved {
        public Scanner.Result result;
        public Estimates est;
        public long targetUsed;
        public boolean appStats;
        public int sdk;
        public int mode;
    }

    // ------------------------------------------------------------------ write
    /** Writes the snapshot and flushes; does not close the stream (AtomicFile.finishWrite does that). */
    public static void write(OutputStream os, Saved s) throws IOException { write(os, s, VERSION); }

    /** Writes in an older format version (tests: files saved by an earlier app version must still load). */
    public static void write(OutputStream os, Saved s, int version) throws IOException {
        if (version < OLDEST_READABLE || version > VERSION) throw new IllegalArgumentException("version " + version);
        DataOutputStream head = new DataOutputStream(os);
        head.writeInt(MAGIC);
        head.writeInt(version);
        head.flush();
        CheckedOutputStream cs = new CheckedOutputStream(os, new CRC32());
        DataOutputStream out = new DataOutputStream(new java.io.BufferedOutputStream(cs, 1 << 16));
        Pool pool = new Pool();
        Scanner.Result r = s.result;

        out.writeLong(s.targetUsed);
        out.writeBoolean(s.appStats);
        out.writeInt(s.sdk);
        out.writeInt(s.mode);

        Estimates e = s.est;
        out.writeDouble(e.cameraPhoto);
        out.writeDouble(e.chatImage);
        out.writeDouble(e.screenshotLossless);
        str(out, e.photoSource);
        str(out, e.chatSource);
        str(out, e.screenshotSource);

        out.writeLong(r.total);
        out.writeLong(r.free);
        out.writeLong(r.now);
        out.writeInt(r.unreadableDirs);
        out.writeLong(r.scannedBytes);

        out.writeInt(r.files.size());
        for (FileItem f : r.files) {
            str(out, f.rel);
            // abs is almost always <root>/<rel>: store the shared prefix through the pool
            if (f.abs != null && f.rel != null && f.abs.endsWith(f.rel)) {
                out.writeBoolean(true);
                pool.write(out, f.abs.substring(0, f.abs.length() - f.rel.length()));
            } else {
                out.writeBoolean(false);
                str(out, f.abs);
            }
            out.writeLong(f.size);
            out.writeLong(f.mtime);
            pool.write(out, f.area);
            pool.write(out, f.pkg);
            pool.write(out, f.type);
            str(out, f.sha256);
            out.writeBoolean(f.hasPhash);
            out.writeLong(f.phash);
            out.writeDouble(f.sharp);
            out.writeInt(f.width);
            out.writeInt(f.height);
            pool.write(out, f.vcodec);
            out.writeLong(f.bitrate);
            out.writeLong(f.durationMs);
            pool.write(out, f.apkPackage);
            out.writeLong(f.apkVersion);
            if (version >= 2) writeQuality(out, f.quality);
            if (version >= 3) {
                pool.write(out, f.category);
                str(out, f.code);
                out.writeLong(f.catDate);
                str(out, f.text);
                out.writeBoolean(f.textPending);
                out.writeInt(f.faces);
            }
        }

        out.writeInt(r.apps.size());
        for (AppItem a : r.apps) {
            str(out, a.pkg);
            str(out, a.label);
            out.writeLong(a.codeBytes);
            out.writeLong(a.dataBytes);
            out.writeLong(a.cacheBytes);
            out.writeLong(a.lastUsed);
            out.writeLong(a.firstInstall);
            out.writeLong(a.versionCode);
            out.writeBoolean(a.isGame);
            out.writeBoolean(a.isSystem);
            out.writeBoolean(a.hasLauncher);
            out.writeBoolean(a.isEssential);
        }
        out.writeInt(MAGIC);                         // end marker
        out.flush();
        head.writeLong(cs.getChecksum().getValue());  // checksum of the payload, written outside it
        head.flush();
    }

    // ------------------------------------------------------------------ read
    /** Reads a snapshot. Throws IOException for a foreign, truncated, corrupted or other-version file. */
    public static Saved read(InputStream is) throws IOException {
        if (!(is instanceof BufferedInputStream)) is = new BufferedInputStream(is, 1 << 16);
        DataInputStream head = new DataInputStream(is);
        if (head.readInt() != MAGIC) throw new IOException("not a Storage Planner results file");
        int v = head.readInt();
        if (v < OLDEST_READABLE || v > VERSION) throw new IOException("results format " + v + ", expected " + OLDEST_READABLE + ".." + VERSION);
        CheckedInputStream cs = new CheckedInputStream(is, new CRC32());
        DataInputStream in = new DataInputStream(cs);
        List<String> pool = new ArrayList<String>();
        Saved s = new Saved();
        Scanner.Result r = new Scanner.Result();
        s.result = r;

        s.targetUsed = in.readLong();
        s.appStats = in.readBoolean();
        s.sdk = in.readInt();
        s.mode = in.readInt();

        Estimates e = new Estimates();
        e.cameraPhoto = in.readDouble();
        e.chatImage = in.readDouble();
        e.screenshotLossless = in.readDouble();
        e.photoSource = str(in);
        e.chatSource = str(in);
        e.screenshotSource = str(in);
        s.est = e;

        r.total = in.readLong();
        r.free = in.readLong();
        r.now = in.readLong();
        r.unreadableDirs = in.readInt();
        r.scannedBytes = in.readLong();

        int nf = count(in);
        for (int i = 0; i < nf; i++) {
            String rel = str(in);
            String abs = in.readBoolean() ? concat(pooled(in, pool), rel) : str(in);
            long size = in.readLong(), mtime = in.readLong();
            FileItem f = new FileItem(rel, abs, size, mtime);
            f.area = pooled(in, pool);
            f.pkg = pooled(in, pool);
            f.type = pooled(in, pool);
            f.sha256 = str(in);
            f.hasPhash = in.readBoolean();
            f.phash = in.readLong();
            f.sharp = in.readDouble();
            f.width = in.readInt();
            f.height = in.readInt();
            f.vcodec = pooled(in, pool);
            f.bitrate = in.readLong();
            f.durationMs = in.readLong();
            f.apkPackage = pooled(in, pool);
            f.apkVersion = in.readLong();
            if (v >= 2) f.quality = readQuality(in);
            if (v >= 3) {
                f.category = pooled(in, pool);
                f.code = str(in);
                f.catDate = in.readLong();
                f.text = str(in);
                f.textPending = in.readBoolean();
                f.faces = in.readInt();
                if (f.faces < -1) throw new IOException("results file damaged (faces)");
            }
            r.files.add(f);
        }

        int na = count(in);
        List<AppItem> apps = new ArrayList<AppItem>(na);
        for (int i = 0; i < na; i++) {
            AppItem a = new AppItem(str(in), str(in));
            a.codeBytes = in.readLong();
            a.dataBytes = in.readLong();
            a.cacheBytes = in.readLong();
            a.lastUsed = in.readLong();
            a.firstInstall = in.readLong();
            a.versionCode = in.readLong();
            a.isGame = in.readBoolean();
            a.isSystem = in.readBoolean();
            a.hasLauncher = in.readBoolean();
            a.isEssential = in.readBoolean();
            apps.add(a);
        }
        r.apps = apps;
        if (in.readInt() != MAGIC) throw new IOException("results file damaged (end marker)");
        long crc = cs.getChecksum().getValue();
        if (head.readLong() != crc) throw new IOException("results file damaged (checksum)");
        if (s.result.files.isEmpty() && s.result.apps.isEmpty() && s.result.total == 0) throw new IOException("empty results");
        return s;
    }

    /** Like read, but returns null instead of throwing for any unreadable input. */
    public static Saved tryRead(InputStream is) {
        try {
            return read(is);
        } catch (Exception e) {
            return null;
        } catch (OutOfMemoryError e) {
            return null;
        }
    }

    private static void writeQuality(DataOutputStream out, PhotoQuality.Q q) throws IOException {
        out.writeBoolean(q != null);
        if (q == null) return;
        out.writeInt(q.faces); out.writeInt(q.openFaces);
        out.writeDouble(q.eyes); out.writeDouble(q.faceSharp); out.writeDouble(q.faceLuma); out.writeDouble(q.sharp);
        out.writeDouble(q.meanLuma); out.writeDouble(q.clipHi); out.writeDouble(q.clipLo);
        out.writeInt(q.face.length);
        for (float x : q.face) out.writeFloat(x);
    }

    private static PhotoQuality.Q readQuality(DataInputStream in) throws IOException {
        if (!in.readBoolean()) return null;
        PhotoQuality.Q q = new PhotoQuality.Q();
        q.faces = in.readInt(); q.openFaces = in.readInt();
        q.eyes = in.readDouble(); q.faceSharp = in.readDouble(); q.faceLuma = in.readDouble(); q.sharp = in.readDouble();
        q.meanLuma = in.readDouble(); q.clipHi = in.readDouble(); q.clipLo = in.readDouble();
        int n = in.readInt();
        if (n < 0 || n > 4096 || n != q.faces * PhotoQuality.FACE_FIELDS || q.openFaces < 0 || q.openFaces > q.faces)
            throw new IOException("results file damaged (faces " + n + ")");
        q.face = new float[n];
        for (int i = 0; i < n; i++) q.face[i] = in.readFloat();
        return q;
    }

    // ------------------------------------------------------------------ helpers
    private static void str(DataOutputStream out, String s) throws IOException {
        if (s == null) { out.writeInt(-1); return; }
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        out.writeInt(b.length);
        out.write(b);
    }

    private static String str(DataInputStream in) throws IOException {
        int n = in.readInt();
        if (n == -1) return null;
        if (n < 0 || n > MAX_STRING) throw new IOException("results file damaged (string length " + n + ")");
        byte[] b = new byte[n];
        in.readFully(b);
        return new String(b, StandardCharsets.UTF_8);
    }

    private static int count(DataInputStream in) throws IOException {
        int n = in.readInt();
        if (n < 0 || n > MAX_ITEMS) throw new IOException("results file damaged (count " + n + ")");
        return n;
    }

    private static String concat(String prefix, String rel) throws IOException {
        if (prefix == null || rel == null) throw new IOException("results file damaged (path)");
        return prefix + rel;
    }

    /** Index into the pool; -1 = null; index == pool size means "new string follows". */
    private static String pooled(DataInputStream in, List<String> pool) throws IOException {
        int i = in.readInt();
        if (i == -1) return null;
        if (i >= 0 && i < pool.size()) return pool.get(i);
        if (i == pool.size()) {
            String s = str(in);
            if (s == null) throw new IOException("results file damaged (pool)");
            pool.add(s);
            return s;
        }
        throw new IOException("results file damaged (pool index " + i + ")");
    }

    private static final class Pool {
        final Map<String, Integer> ids = new HashMap<String, Integer>();

        void write(DataOutputStream out, String s) throws IOException {
            if (s == null) { out.writeInt(-1); return; }
            Integer id = ids.get(s);
            if (id != null) { out.writeInt(id); return; }
            int n = ids.size();
            ids.put(s, n);
            out.writeInt(n);
            str(out, s);
        }
    }
}
