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
    public static final int VERSION = 14;            // 14: files not analysed yet (a paused analysis); 13: what the video index said (detail, stillness, sound) and whether the fingerprint was measured; 12: frame rate and what the frames of each video showed (VideoCheck); 11: what Mirage saw (style, people, objects, scene, reference photo, v3 category); 2 (app 1.5): photo quality of near-identical shots; 3 (1.6): picture categories; 4: picture measurements (signals) on every sorted picture; 5: page-of-paper measurements and the sorting version of each picture; 6: eyes open on every sorted photo with a face; 7: glare share; 8: focus of the sharpest parts; 9: favourite, date taken, Motion Photo video; 10: scene, aesthetic score, smiles, faces looking at the camera, face size (and smiles in the near-identical shots' measurements)
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
            if (version >= 2) writeQuality(out, version >= 10 ? f.quality : null);   // older formats had fewer numbers per face
            if (version >= 3) {
                pool.write(out, f.category);
                str(out, f.code);
                out.writeLong(f.catDate);
                str(out, f.text);
                out.writeBoolean(f.textPending);
                out.writeInt(f.faces);
            }
            if (version >= 4) writeSignals(out, f.signals, version);
            if (version >= 5) out.writeInt(f.sortVersion);
            if (version >= 6) { out.writeInt(f.eyeFaces); out.writeInt(f.eyesOpen); }
            if (version >= 9) { out.writeByte(f.favourite == null ? -1 : f.favourite ? 1 : 0); out.writeLong(f.taken); out.writeLong(f.motionBytes); }
            if (version >= 10) {
                out.writeByte(f.scene == null ? -1 : f.scene.length);
                if (f.scene != null) for (float x : f.scene) out.writeFloat(x);
                out.writeFloat(f.aesthetic); out.writeInt(f.sceneVersion);
                out.writeFloat(f.faceSize); out.writeInt(f.smiling); out.writeInt(f.looking);
            }
            if (version >= 11) {
                out.writeInt(f.mirageVersion);
                if (f.mirageVersion > 0) {
                    str(out, f.mirageStyle); out.writeInt(f.miragePeople); str(out, f.mirageObjects); str(out, f.mirageScene);
                    out.writeBoolean(f.mirageReference); out.writeBoolean(f.mirageSees); pool.write(out, f.v3Category); out.writeLong(f.v3Date);
                }
            }
            if (version >= 12) {
                out.writeFloat(f.fps);
                out.writeInt(f.videoVersion);
                if (f.videoVersion > 0) {
                    out.writeInt(f.vFrames); out.writeInt(f.vDark); out.writeDouble(f.vMotion); out.writeDouble(f.vSharpMax);
                    out.writeInt(f.vPeople); out.writeInt(f.vWords); out.writeLong(f.vHash);
                }
            }
            if (version >= 13 && f.videoVersion > 0) {
                out.writeBoolean(f.vHashed); out.writeBoolean(f.vIndexed); out.writeInt(f.vKeyframes);
                out.writeDouble(f.vLowDetail); out.writeDouble(f.vStill); out.writeInt(f.vAudio); out.writeDouble(f.vQuiet);
            }
            if (version >= 14) out.writeBoolean(f.pending);
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
            if (v >= 2) f.quality = readQuality(in, v >= 10 ? PhotoQuality.FACE_FIELDS : 5);
            if (v < 10) f.quality = null;         // measured before focus ignored sensor grain (7) and before smiles (10): compare similar shots afresh
            if (v >= 3) {
                f.category = pooled(in, pool);
                f.code = str(in);
                f.catDate = in.readLong();
                f.text = str(in);
                f.textPending = in.readBoolean();
                f.faces = in.readInt();
                if (f.faces < -1) throw new IOException("results file damaged (faces)");
            }
            if (v >= 4) f.signals = readSignals(in, v);
            if (v >= 5) { f.sortVersion = in.readInt(); if (f.sortVersion < 0) throw new IOException("results file damaged (sort version)"); }
            if (v >= 6) {
                f.eyeFaces = in.readInt(); f.eyesOpen = in.readInt();
                if (f.eyeFaces < -1 || f.eyesOpen < -1 || f.eyesOpen > Math.max(0, f.eyeFaces)) throw new IOException("results file damaged (eyes)");
            }
            if (v >= 9) {
                byte fv = in.readByte();
                if (fv < -1 || fv > 1) throw new IOException("results file damaged (favourite)");
                f.favourite = fv < 0 ? null : fv == 1;
                f.taken = in.readLong(); f.motionBytes = in.readLong();
                if (f.taken < -1 || f.motionBytes < -1 || f.motionBytes > f.size) throw new IOException("results file damaged (photo details)");
            }
            if (v >= 10) {
                int ns = in.readByte();
                if (ns < -1 || ns > 16) throw new IOException("results file damaged (scene)");
                if (ns >= 0) {
                    f.scene = new float[ns];
                    for (int k = 0; k < ns; k++) { f.scene[k] = in.readFloat(); if (!(f.scene[k] >= 0 && f.scene[k] <= 1.001f)) throw new IOException("results file damaged (scene)"); }
                }
                f.aesthetic = in.readFloat(); f.sceneVersion = in.readInt();
                f.faceSize = in.readFloat(); f.smiling = in.readInt(); f.looking = in.readInt();
                if (!(f.aesthetic == -1 || f.aesthetic >= 0 && f.aesthetic <= 10) || f.sceneVersion < 0 || !(f.faceSize == -1 || f.faceSize >= 0 && f.faceSize <= 10)
                        || f.smiling < -1 || f.looking < -1 || f.smiling > Math.max(0, f.eyeFaces) || f.looking > Math.max(0, f.eyeFaces))
                    throw new IOException("results file damaged (people and scene)");
            }
            if (v >= 11) {
                f.mirageVersion = in.readInt();
                if (f.mirageVersion < 0) throw new IOException("results file damaged (Mirage)");
                if (f.mirageVersion > 0) {
                    f.mirageStyle = str(in); f.miragePeople = in.readInt(); f.mirageObjects = str(in); f.mirageScene = str(in);
                    f.mirageReference = in.readBoolean(); f.mirageSees = in.readBoolean(); f.v3Category = pooled(in, pool); f.v3Date = in.readLong();
                    if (f.miragePeople < 0 || f.mirageStyle == null || f.v3Date < 0) throw new IOException("results file damaged (Mirage)");
                }
            }
            if (v >= 12) {
                f.fps = in.readFloat();
                f.videoVersion = in.readInt();
                if (!(f.fps >= 0 && f.fps < 1000) || f.videoVersion < 0) throw new IOException("results file damaged (video)");
                if (f.videoVersion > 0) {
                    f.vFrames = in.readInt(); f.vDark = in.readInt(); f.vMotion = in.readDouble(); f.vSharpMax = in.readDouble();
                    f.vPeople = in.readInt(); f.vWords = in.readInt(); f.vHash = in.readLong();
                    if (f.vFrames < 0 || f.vDark < 0 || f.vDark > f.vFrames || f.vPeople < -1 || f.vWords < -1 || !(f.vMotion >= -1 && f.vMotion <= 64))
                        throw new IOException("results file damaged (video)");
                }
            }
            if (v >= 13 && f.videoVersion > 0) {
                f.vHashed = in.readBoolean(); f.vIndexed = in.readBoolean(); f.vKeyframes = in.readInt();
                f.vLowDetail = in.readDouble(); f.vStill = in.readDouble(); f.vAudio = in.readInt(); f.vQuiet = in.readDouble();
                if (f.vKeyframes < 0 || !(f.vLowDetail >= -1 && f.vLowDetail <= 1) || !(f.vStill >= -1 && f.vStill <= 1) || f.vAudio < -1 || f.vAudio > 1
                        || !(f.vQuiet >= -1 && f.vQuiet <= 1)) throw new IOException("results file damaged (video index)");
            } else if (v < 13 && f.videoVersion > 0) f.vHashed = f.vFrames > 0;
            if (v >= 14) f.pending = in.readBoolean();
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

    private static void writeSignals(DataOutputStream out, ImageSignals.S s, int version) throws IOException {
        out.writeBoolean(s != null);
        if (s == null) return;
        out.writeDouble(s.meanLuma); out.writeDouble(s.darkFrac); out.writeDouble(s.contrast); out.writeDouble(s.colorful);
        out.writeDouble(s.paper); out.writeDouble(s.flat); out.writeDouble(s.sharp);
        out.writeInt(s.textBlobs); out.writeDouble(s.textArea); out.writeInt(s.textLines); out.writeDouble(s.textHeight); out.writeDouble(s.bigText);
        if (version >= 5) { out.writeDouble(s.whiteLevel); out.writeDouble(s.pagePaper); out.writeDouble(s.smooth); out.writeDouble(s.ink); out.writeInt(s.inkBands); }
        if (version >= 7) out.writeDouble(s.glare);
        if (version >= 8) out.writeDouble(s.peakSharp);
    }

    private static ImageSignals.S readSignals(DataInputStream in, int version) throws IOException {
        if (!in.readBoolean()) return null;
        ImageSignals.S s = new ImageSignals.S();
        s.meanLuma = in.readDouble(); s.darkFrac = in.readDouble(); s.contrast = in.readDouble(); s.colorful = in.readDouble();
        s.paper = in.readDouble(); s.flat = in.readDouble(); s.sharp = in.readDouble();
        s.textBlobs = in.readInt(); s.textArea = in.readDouble(); s.textLines = in.readInt(); s.textHeight = in.readDouble(); s.bigText = in.readDouble();
        if (version >= 5) { s.whiteLevel = in.readDouble(); s.pagePaper = in.readDouble(); s.smooth = in.readDouble(); s.ink = in.readDouble(); s.inkBands = in.readInt(); }
        if (version >= 7) s.glare = in.readDouble();
        if (version >= 8) s.peakSharp = in.readDouble();
        if (s.peakSharp != s.peakSharp) throw new IOException("results file damaged (signals)");
        if (s.glare != s.glare || s.glare > 1 || s.glare < -1) throw new IOException("results file damaged (signals)");
        if (s.textBlobs < 0 || s.textLines < 0) throw new IOException("results file damaged (signals)");
        if (s.inkBands < -1 || s.inkBands > 8) throw new IOException("results file damaged (signals)");
        return s;
    }

    private static PhotoQuality.Q readQuality(DataInputStream in, int fields) throws IOException {
        if (!in.readBoolean()) return null;
        PhotoQuality.Q q = new PhotoQuality.Q();
        q.faces = in.readInt(); q.openFaces = in.readInt();
        q.eyes = in.readDouble(); q.faceSharp = in.readDouble(); q.faceLuma = in.readDouble(); q.sharp = in.readDouble();
        q.meanLuma = in.readDouble(); q.clipHi = in.readDouble(); q.clipLo = in.readDouble();
        int n = in.readInt();
        if (n < 0 || n > 4096 || n != q.faces * fields || q.openFaces < 0 || q.openFaces > q.faces)
            throw new IOException("results file damaged (faces " + n + ")");
        q.face = new float[n];
        for (int i = 0; i < n; i++) q.face[i] = in.readFloat();
        if (fields == PhotoQuality.FACE_FIELDS) PhotoQuality.countPeople(q);
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
