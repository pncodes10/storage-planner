package com.storageplanner.v2.store;

import com.storageplanner.v2.V2;
import com.storageplanner.v2.device.BlobStore;
import com.storageplanner.v2.device.Clock;
import com.storageplanner.v2.model.*;
import com.storageplanner.v2.model.Record;   // single-type import: java.lang.Record exists in android-34.jar
import com.storageplanner.v2.store.disk.Frame;
import com.storageplanner.v2.store.disk.FrameException;
import com.storageplanner.v2.store.disk.KeyCmp;
import com.storageplanner.v2.store.disk.KeyIndex;
import com.storageplanner.v2.store.disk.SegmentReader;
import com.storageplanner.v2.store.disk.SegmentWriter;
import com.storageplanner.v2.store.disk.TableIds;
import com.storageplanner.v2.util.Hex;
import com.storageplanner.v2.util.Json;
import com.storageplanner.v2.util.JsonException;
import com.storageplanner.v2.util.JsonWriter;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.CRC32;
import java.util.zip.CheckedInputStream;
import java.util.zip.CheckedOutputStream;

/**
 * Disk-backed v2 store (PHASE_S_STORE section 3, review edits REVIEW_S F-01…F-05 applied): one segmented append-only
 * log of CRC-framed {@link Codecs} JSON records for all tables, an in-memory {@link KeyIndex} per table mapping key →
 * slot → (segment, offset), records decoded on demand through an LRU, and a manifest replaced atomically at checkpoints
 * that defines the durable state. Same contract as {@link MemoryStore} (the oracle in the tests).
 * <p>
 * Safety rules: every read verifies the frame it got (CRC, type PUT, table, key bytes, value hash, decoded record key);
 * a mismatch on a CRC-valid frame is a locator bug and throws {@link StoreCorruption} for every table (F-04). A damaged
 * frame of a derived table (features, hashes, embeddings, pair checks) reads as absent and is counted; of any other
 * table it throws {@link StoreCorruption} and marks the manifest damaged, so the next open refuses (3.11). Damage inside
 * the durable range at open fails the open (F-05). Single writer; every public method synchronizes on the store.
 */
public final class DiskStore implements V2Store {
    public static final String MANIFEST = "ds.manifest";
    static final String FORMAT = "sp-v2-ds";
    static final int VERSION = 1;
    static final byte[] IDX_MAGIC = "SPDSIDX1".getBytes(StandardCharsets.US_ASCII);
    static final int IDX_VERSION = 1;

    /** Tunables; the defaults are the design's, tests shrink them. */
    public static final class Options {
        /** Rotation threshold of the active segment (3.7). */
        public long maxSegmentBytes = 32L << 20;
        /** KeyIndex merge threshold: max(mergeMin, n/8) (3.5). */
        public int mergeMin = 4096;
        /** Index file written at flush() when the log tail it does not cover exceeds this (3.4). */
        public long indexTailBytes = 8L << 20;
        public int lruRecords = 4096;
        public long lruBytes = 8L << 20;
        /** Write the index file at close() (3.4). */
        public boolean indexAtClose = true;
    }

    final Object lock = this;
    private final BlobStore blobs;
    private final long checkpointEveryMs;   // -1 adaptive, 0 every commit
    private final Clock clock;
    private final Options opt;

    // ---------------------------------------------------------------- durable state and segments
    private Manifest durable;                // the manifest last written (the durable state)
    private int schemaOnDisk = V2.STORE_SCHEMA;
    private static final class Seg {
        final int seq; final String name; long limit;
        Seg(int seq, String name, long limit) { this.seq = seq; this.name = name; this.limit = limit; }
    }
    private final TreeMap<Integer, Seg> segs = new TreeMap<Integer, Seg>();
    private SegmentWriter writer;
    private int writerSeq = -1;
    private final LinkedHashMap<Integer, SegmentReader> readers = new LinkedHashMap<Integer, SegmentReader>(16, 0.75f, true);
    private static final int MAX_READERS = 16;
    /** Index file: covers per segment seq, its idxSeq and CRC. */
    private final TreeMap<Integer, Long> covers = new TreeMap<Integer, Long>();
    private long idxSeq;
    private long idxCrc;
    private boolean idxValid;

    // ---------------------------------------------------------------- slots
    // per slot, in pages of 4,096 (no doubling waste, no big copy): locator (segment:16 | offset:48), 32-bit FNV-1a of
    // the value (no-change detection and an F-04 cross-check), frame length
    private static final int PAGE_BITS = 12, PAGE = 1 << PAGE_BITS, PMASK = PAGE - 1;
    private long[][] locP = new long[0][];
    private int[][] hashP = new int[0][], lenP = new int[0][];
    private int slotTop;
    private int[] free = new int[16];
    private int freeN;
    private long liveBytes;

    // ---------------------------------------------------------------- txn
    private long txn, txnNext, commitSeq;
    private int txnFrames;
    private final ArrayList<Undo> undo = new ArrayList<Undo>();
    private boolean dirty;
    private long lastCkNanos, lastCkDurationMs;
    private int checkpoints;
    private long changedPuts;
    private boolean broken;                  // an append/sync failed: the in-memory state is ahead of the log
    private boolean opening;
    private boolean loadedFromIndex;
    private boolean damagedMark;             // fail-closed damage seen: every manifest from now on says damaged:true
    private final TreeSet<String> damagedKeys = new TreeSet<String>();

    private static final class Undo {
        final DTable<?> t; final String key; final boolean existed; final long loc, hash; final int flen;
        Undo(DTable<?> t, String key, boolean existed, long loc, long hash, int flen) {
            this.t = t; this.key = key; this.existed = existed; this.loc = loc; this.hash = hash; this.flen = flen;
        }
    }

    // ---------------------------------------------------------------- LRU of decoded records
    private static final class Cached { final Record r; final int len; Cached(Record r, int len) { this.r = r; this.len = len; } }
    private final LinkedHashMap<String, Cached> lru = new LinkedHashMap<String, Cached>(256, 0.75f, true);
    private long lruBytes;

    // ---------------------------------------------------------------- tables
    private final FilesT files;
    private final DTable<FeatureRecord> features;
    private final DTable<HashRecord> hashes;
    private final DTable<EmbeddingRecord> embeddings;
    private final RelsT relationships;
    private final DTable<DuplicateGroup> duplicateGroups;
    private final DTable<BackupRecord> backups;
    private final DTable<PairCheck> pairChecks;
    private final RecsT recommendations;
    private final DTable<ActionPlan> actionPlans;
    private final OpsT operations;
    private final DTable<Feedback> feedback;
    private final DTable<Preference> preferences;
    private final DTable<ModelRecord> models;
    private final DTable<ScanSession> scanSessions;
    private final DTable<Volume> volumes;
    private final DirsT directories;
    private final DTable<?>[] byId = new DTable<?>[TableIds.COUNT + 1];

    private DiskStore(BlobStore blobs, long checkpointEveryMs, Clock clock, Options opt) {
        this.blobs = blobs; this.checkpointEveryMs = checkpointEveryMs; this.clock = clock; this.opt = opt == null ? new Options() : opt;
        files = new FilesT();
        features = new DTable<FeatureRecord>(TableIds.FEATURES);
        hashes = new DTable<HashRecord>(TableIds.HASHES);
        embeddings = new DTable<EmbeddingRecord>(TableIds.EMBEDDINGS);
        relationships = new RelsT();
        duplicateGroups = new DTable<DuplicateGroup>(TableIds.DUPLICATE_GROUPS);
        backups = new DTable<BackupRecord>(TableIds.BACKUPS);
        pairChecks = new DTable<PairCheck>(TableIds.PAIR_CHECKS);
        recommendations = new RecsT();
        actionPlans = new DTable<ActionPlan>(TableIds.ACTION_PLANS);
        operations = new OpsT();
        feedback = new DTable<Feedback>(TableIds.FEEDBACK);
        preferences = new DTable<Preference>(TableIds.PREFERENCES);
        models = new DTable<ModelRecord>(TableIds.MODELS);
        scanSessions = new DTable<ScanSession>(TableIds.SESSIONS);
        volumes = new DTable<Volume>(TableIds.VOLUMES);
        directories = new DirsT();
        this.lastCkNanos = clock.monotonicNanos();
    }

    // ================================================================ open / create

    /** Opens the store described by ds.manifest; an absent manifest creates an empty store. StoreException → start fresh. */
    public static DiskStore open(BlobStore blobs, long checkpointEveryMs, Clock clock) throws StoreException {
        return open(blobs, checkpointEveryMs, clock, null);
    }

    public static DiskStore open(BlobStore blobs, long checkpointEveryMs, Clock clock, Options opt) throws StoreException {
        String text = readManifestText(blobs);
        if (text == null) return create(blobs, checkpointEveryMs, clock, opt);
        Manifest m = Manifest.parse(text);
        if (m.damaged) throw new StoreException("store marked damaged");
        DiskStore s = new DiskStore(blobs, checkpointEveryMs, clock, opt);
        s.opening = true;
        try {
            s.load(m);
        } catch (StoreCorruption e) {
            s.closeQuietly();
            throw new StoreException("store damaged: " + e.getMessage(), e);
        } catch (IOException e) {
            s.closeQuietly();
            throw new StoreException("store read failed: " + e, e);
        } catch (RuntimeException e) {
            s.closeQuietly();
            throw new StoreException("store open failed: " + e, e);
        }
        s.opening = false;
        return s;
    }

    /**
     * An empty store over blobs (first use or after a reset). Writes a fresh manifest first, then truncates every file of
     * an earlier store (segments of both sets up to their high-water mark, index files) and an old {@code store.snap}.
     */
    public static DiskStore create(BlobStore blobs, long checkpointEveryMs, Clock clock, Options opt) throws StoreException {
        long gen = 1;
        int hwA = 0, hwB = 0;
        try {
            String t = readManifestText(blobs);
            if (t != null) {
                Manifest old = Manifest.parseLenient(t);
                if (old != null) { gen = old.gen + 1; hwA = old.hwA; hwB = old.hwB; }
            }
        } catch (StoreException ignored) { /* unreadable: fall back to probing */ }
        hwA = Math.max(hwA, probe(blobs, "a"));
        hwB = Math.max(hwB, probe(blobs, "b"));
        DiskStore s = new DiskStore(blobs, checkpointEveryMs, clock, opt);
        Manifest m = new Manifest();
        m.gen = gen; m.set = "a"; m.hwA = hwA; m.hwB = hwB; m.txnNext = 1; m.commitSeq = 0; m.schema = V2.STORE_SCHEMA;
        try {
            writeManifest(blobs, m);
            for (String set : new String[]{"a", "b"})
                for (int i = 1; i <= ("a".equals(set) ? hwA : hwB); i++) truncateIfNonEmpty(blobs, segName(set, i));
            truncateIfNonEmpty(blobs, idxName("a"));
            truncateIfNonEmpty(blobs, idxName("b"));
            truncateIfNonEmpty(blobs, MemoryStore.SNAPSHOT);
        } catch (IOException e) {
            throw new StoreException("cannot create store: " + e, e);
        }
        s.durable = m;
        s.txn = 1; s.txnNext = 2; s.commitSeq = 0;
        return s;
    }

    public static DiskStore create(BlobStore blobs, long checkpointEveryMs, Clock clock) throws StoreException {
        return create(blobs, checkpointEveryMs, clock, null);
    }

    private static int probe(BlobStore b, String set) {
        int i = 0;
        while (b.length(segName(set, i + 1)) >= 0) i++;
        return i;
    }

    private static void truncateIfNonEmpty(BlobStore b, String name) throws IOException {
        if (b.length(name) > 0) b.truncate(name);
    }

    static String segName(String set, int seq) { return String.format(Locale.ROOT, "ds-%s-%04d.seg", set, seq); }
    static String idxName(String set) { return "ds-" + set + ".idx"; }

    private static String readManifestText(BlobStore blobs) throws StoreException {
        InputStream in;
        try { in = blobs.read(MANIFEST); } catch (IOException e) { throw new StoreException("cannot read manifest: " + e, e); }
        if (in == null) return null;
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int k;
            while ((k = in.read(buf)) > 0) { bo.write(buf, 0, k); if (bo.size() > (16 << 20)) throw new StoreException("manifest too large"); }
            return new String(bo.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new StoreException("cannot read manifest: " + e, e);
        } finally {
            try { in.close(); } catch (IOException ignored) { /* nothing to do */ }
        }
    }

    /** Loads indexes (index file if valid for this manifest, else a full scan), then replays the uncovered tail. */
    private void load(Manifest m) throws IOException, StoreException {
        if (m.version > VERSION) throw new StoreException("unsupported store version " + m.version);
        if (m.schema < 1 || m.schema > V2.STORE_SCHEMA) throw new StoreException("unsupported store schema " + m.schema);
        if (!"a".equals(m.set) && !"b".equals(m.set)) throw new StoreException("bad set " + m.set);
        schemaOnDisk = m.schema;
        durable = m;
        int prev = 0;
        for (Manifest.SegEntry e : m.segments) {
            if (e.seq <= prev) throw new StoreException("segments out of order");
            if (e.seq > ("a".equals(m.set) ? m.hwA : m.hwB)) throw new StoreException("segment beyond the high-water mark");
            prev = e.seq;
            segs.put(e.seq, new Seg(e.seq, e.name, e.durable));
        }
        // F-05: every listed segment exists, is at least as long as its durable length, and starts with its HEADER (F-02 b)
        for (Seg sg : segs.values()) {
            BlobStore.RandomRead rr = blobs.openRead(sg.name);
            if (rr == null) throw new StoreException("segment missing: " + sg.name);
            try {
                if (rr.length() < sg.limit) throw new StoreException("segment shorter than durable: " + sg.name);
                SegmentReader r = new SegmentReader(rr, 4096);
                Frame h;
                try { h = r.readAt(0, sg.limit); } catch (FrameException e) { throw new StoreException("bad segment header: " + sg.name + ": " + e.getMessage()); }
                checkHeader(h, m, sg.seq);
            } finally { rr.close(); }
        }
        boolean fromIndex = false;
        if (m.idxName != null) {
            try { fromIndex = loadIndex(m); } catch (IOException e) { fromIndex = false; } catch (RuntimeException e) { fromIndex = false; }
            if (!fromIndex) resetIndexes();
        }
        if (!fromIndex) covers.clear();
        idxValid = fromIndex;
        loadedFromIndex = fromIndex;
        if (fromIndex) { idxSeq = m.idxSeq; idxCrc = m.idxCrc; } else { idxSeq = m.idxSeq; idxCrc = 0; }
        replay(m);
        txn = m.txnNext; txnNext = txn + 1; commitSeq = m.commitSeq;
        undo.clear(); txnFrames = 0; dirty = false;
    }

    private void checkHeader(Frame h, Manifest m, int seq) throws StoreException {
        if (h.type != Frame.HEADER) throw new StoreException("segment " + seq + " does not start with a HEADER");
        Map<String, Object> j;
        try { j = Json.parseObject(new String(h.val, StandardCharsets.UTF_8)); } catch (JsonException e) { throw new StoreException("bad HEADER json"); }
        if (Json.integer(j, "version", -1) > VERSION) throw new StoreException("unsupported segment version");
        if (Json.lng(j, "gen", -1) != m.gen || !m.set.equals(Json.str(j, "set")) || Json.lng(j, "seq", -1) != seq)
            throw new StoreException("segment " + seq + " HEADER does not match the manifest (stale segment)");
    }

    private void resetIndexes() {
        for (int i = 1; i <= TableIds.COUNT; i++) byId[i].reset();
        slotTop = 0; freeN = 0; liveBytes = 0;
        lru.clear(); lruBytes = 0;
        covers.clear();
    }

    private static final class Change {
        final int table; final byte[] key; final boolean put; final long loc, hash; final int flen; final byte[] val;
        Change(int table, byte[] key, boolean put, long loc, long hash, int flen, byte[] val) {
            this.table = table; this.key = key; this.put = put; this.loc = loc; this.hash = hash; this.flen = flen; this.val = val;
        }
    }

    /** Replays [covers, durable) of every listed segment, applying a txn's frames at its COMMIT (3.9). Damage → StoreException. */
    private void replay(Manifest m) throws IOException, StoreException {
        HashMap<Long, ArrayList<Change>> pending = new HashMap<Long, ArrayList<Change>>();
        long lastSeq = -1;
        for (Seg sg : segs.values()) {
            Long cv = covers.get(sg.seq);
            long pos = cv == null ? 0 : cv;
            BlobStore.RandomRead rr = blobs.openRead(sg.name);
            if (rr == null) throw new StoreException("segment missing: " + sg.name);
            try {
                SegmentReader r = new SegmentReader(rr, 1 << 16);
                if (pos == 0) {
                    try { pos = r.readAt(0, sg.limit).length; } catch (FrameException e) { throw new StoreException("bad header: " + e.getMessage()); }
                }
                while (pos < sg.limit) {
                    Frame f;
                    try { f = r.readAt(pos, sg.limit); }
                    catch (FrameException e) { throw new StoreException("damaged frame at " + sg.name + ":" + pos + ": " + e.getMessage()); }
                    long l = ((long) sg.seq << 48) | pos;
                    switch (f.type) {
                        case Frame.PUT:
                        case Frame.DEL: {
                            ArrayList<Change> c = pending.get(f.txn);
                            if (c == null) pending.put(f.txn, c = new ArrayList<Change>());
                            boolean secondary = byId[f.table].hasSecondary();
                            c.add(new Change(f.table, f.key, f.type == Frame.PUT, l, f.type == Frame.PUT ? fnv(f.val) : 0, f.length,
                                    f.type == Frame.PUT && secondary ? f.val : null));
                            break;
                        }
                        case Frame.COMMIT: {
                            long cs = f.commitSeq();
                            if (cs <= lastSeq || cs > m.commitSeq) throw new StoreException("commit sequence out of order at " + sg.name + ":" + pos);
                            lastSeq = cs;
                            ArrayList<Change> c = pending.remove(f.txn);
                            if (c != null) for (Change ch : c) apply(ch);
                            break;
                        }
                        default:
                            throw new StoreException("HEADER inside segment " + sg.name + " at " + pos);
                    }
                    pos += f.length;
                }
            } finally { rr.close(); }
        }
    }

    /** Applies one committed change during replay, keeping secondary indexes (old record read and verified at its locator). */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void apply(Change ch) throws StoreException {
        DTable t = byId[ch.table];
        String key = KeyCmp.decode(ch.key);
        int slot = t.prim.get(key);
        if (slot >= 0 && t.hasSecondary()) {
            Record old = readRecord(t, key, slot);
            t.unindex(old);
        }
        lruRemove(t, key);
        if (ch.put) {
            if (slot < 0) { slot = allocSlot(); t.prim.put(key, slot); } else liveBytes -= sLen(slot);
            setSlot(slot, ch.loc, ch.hash, ch.flen); liveBytes += ch.flen;
            if (t.hasSecondary()) {
                Record r = decode(t, key, ch.val);
                t.index(r);
            }
        } else if (slot >= 0) {
            t.prim.remove(key);
            liveBytes -= sLen(slot);
            freeSlot(slot);
        }
    }

    // ================================================================ index file (3.4, F-01)

    private boolean loadIndex(Manifest m) throws IOException {
        InputStream raw = blobs.read(m.idxName);
        if (raw == null) return false;
        try {
            CheckedInputStream ci = new CheckedInputStream(new java.io.BufferedInputStream(raw, 1 << 16), new CRC32());
            DataInputStream in = new DataInputStream(ci);
            byte[] mg = new byte[IDX_MAGIC.length];
            in.readFully(mg);
            if (!Arrays.equals(mg, IDX_MAGIC)) return false;
            if (in.readInt() != IDX_VERSION) return false;
            if (in.readLong() != m.gen || in.readLong() != m.idxSeq) return false;
            int nseg = in.readInt();
            if (nseg < 0 || nseg > segs.size()) return false;
            TreeMap<Integer, Long> cv = new TreeMap<Integer, Long>();
            for (int i = 0; i < nseg; i++) {
                int seq = in.readInt();
                long c = in.readLong();
                Seg sg = segs.get(seq);
                if (sg == null || c < 0 || c > sg.limit) return false;     // F-01: never past durable
                cv.put(seq, c);
            }
            for (int id = 1; id <= TableIds.COUNT; id++) {
                if (in.readUnsignedByte() != id) return false;
                DTable<?> t = byId[id];
                int count = in.readInt();
                if (count < 0) return false;
                for (int i = 0; i < count; i++) {
                    byte[] k = readBytes(in);
                    long l = in.readLong(), h = in.readLong();
                    int fl = in.readInt();
                    int seq = (int) (l >>> 48);
                    long off = l & 0xFFFFFFFFFFFFL;
                    Long c = cv.get(seq);
                    if (c == null || off + fl > c || fl < Frame.MIN_FRAME) return false;   // locator inside the covered range
                    int slot = allocSlot();
                    setSlot(slot, l, h, fl); liveBytes += fl;
                    t.prim.load(k, slot);
                }
                t.prim.endLoad();
                KeyIndex[] sec = t.secondaries();
                for (KeyIndex x : sec) {
                    int sc = in.readInt();
                    if (sc < 0) return false;
                    for (int i = 0; i < sc; i++) x.load(readBytes(in), 0);
                    x.endLoad();
                }
            }
            long crc = ci.getChecksum().getValue();
            int trailer = in.readInt();
            if ((int) crc != trailer || (crc & 0xffffffffL) != m.idxCrc) return false;
            if (in.read() >= 0) return false;
            covers.putAll(cv);
            return true;
        } catch (EOFException e) {
            return false;
        } finally {
            try { raw.close(); } catch (IOException ignored) { /* nothing to do */ }
        }
    }

    private static byte[] readBytes(DataInputStream in) throws IOException {
        int n = in.readInt();
        if (n < 0 || n > Frame.MAX_KEY) throw new IOException("bad key length");
        byte[] b = new byte[n];
        in.readFully(b);
        return b;
    }

    /** Writes ds-<set>.idx covering the current lengths; the caller writes the manifest naming it right after (F-01 rule 3). */
    private void writeIndex() throws IOException {
        long seqNo = idxSeq + 1;
        final TreeMap<Integer, Long> cv = new TreeMap<Integer, Long>();
        for (Seg sg : segs.values()) cv.put(sg.seq, sg.seq == writerSeq ? writer.length() : sg.limit);
        String name = idxName(durable.set);
        BlobStore.AtomicWrite aw = blobs.replace(name);
        boolean ok = false;
        try {
            CheckedOutputStream co = new CheckedOutputStream(new java.io.BufferedOutputStream(aw.stream(), 1 << 16), new CRC32());
            final DataOutputStream out = new DataOutputStream(co);
            out.write(IDX_MAGIC);
            out.writeInt(IDX_VERSION);
            out.writeLong(durable.gen);
            out.writeLong(seqNo);
            out.writeInt(cv.size());
            for (Map.Entry<Integer, Long> e : cv.entrySet()) { out.writeInt(e.getKey()); out.writeLong(e.getValue()); }
            for (int id = 1; id <= TableIds.COUNT; id++) {
                DTable<?> t = byId[id];
                out.writeByte(id);
                out.writeInt(t.prim.size());
                t.prim.visit(new KeyIndex.Visitor() {
                    public void entry(byte[] a, int off, int len, int slot) throws IOException {
                        out.writeInt(len); out.write(a, off, len);
                        out.writeLong(sLoc(slot)); out.writeLong(sHash(slot)); out.writeInt(sLen(slot));
                    }
                });
                for (KeyIndex x : t.secondaries()) {
                    out.writeInt(x.size());
                    x.visit(new KeyIndex.Visitor() {
                        public void entry(byte[] a, int off, int len, int v) throws IOException { out.writeInt(len); out.write(a, off, len); }
                    });
                }
            }
            out.flush();
            long crc = co.getChecksum().getValue();
            out.writeInt((int) crc);
            out.flush();
            aw.commit();
            ok = true;
            idxSeq = seqNo; idxCrc = crc; idxValid = true;
            covers.clear(); covers.putAll(cv);
        } finally {
            if (!ok) aw.abort();
        }
    }

    private long uncoveredTail() {
        long t = 0;
        for (Seg sg : segs.values()) {
            long len = sg.seq == writerSeq ? writer.length() : sg.limit;
            Long c = idxValid ? covers.get(sg.seq) : null;
            t += len - (c == null ? 0 : c);
        }
        return t;
    }

    // ================================================================ manifest (3.3)

    static final class Manifest {
        static final class SegEntry { final String name; final int seq; final long durable; SegEntry(String n, int s, long d) { name = n; seq = s; durable = d; } }
        int version = VERSION, schema = V2.STORE_SCHEMA;
        long gen; String set = "a"; long txnNext, commitSeq;
        List<SegEntry> segments = new ArrayList<SegEntry>();
        int hwA, hwB;
        String idxName; long idxSeq, idxCrc;
        long live, garbage;
        boolean damaged;

        Manifest copy() {
            Manifest m = new Manifest();
            m.version = version; m.schema = schema; m.gen = gen; m.set = set; m.txnNext = txnNext; m.commitSeq = commitSeq;
            m.segments = new ArrayList<SegEntry>(segments); m.hwA = hwA; m.hwB = hwB;
            m.idxName = idxName; m.idxSeq = idxSeq; m.idxCrc = idxCrc; m.live = live; m.garbage = garbage; m.damaged = damaged;
            return m;
        }

        String text() {
            StringWriter sw = new StringWriter(512);
            JsonWriter w = new JsonWriter(sw);
            w.beginObject().name("format").value(FORMAT).name("version").value(version).name("schema").value(schema)
             .name("gen").value(gen).name("set").value(set).name("txnNext").value(txnNext).name("commitSeq").value(commitSeq);
            w.name("segments").beginArray();
            for (SegEntry e : segments) w.beginObject().name("name").value(e.name).name("durable").value(e.durable).endObject();
            w.endArray();
            w.name("hw").beginObject().name("a").value(hwA).name("b").value(hwB).endObject();
            w.name("index");
            if (idxName == null) w.nullValue();
            else w.beginObject().name("name").value(idxName).name("idxSeq").value(idxSeq).name("crc32").value(Hex.of8(idxCrc)).endObject();
            w.name("live").value(live).name("garbage").value(garbage).name("damaged").value(damaged).endObject();
            String body = sw.toString();
            CRC32 c = new CRC32();
            c.update(body.getBytes(StandardCharsets.UTF_8));
            return body.substring(0, body.length() - 1) + ",\"crc32\":\"" + Hex.of8(c.getValue()) + "\"}";
        }

        static Manifest parse(String full) throws StoreException {
            int n = full.length();
            String tail = ",\"crc32\":\"";
            if (n < 22 || !full.endsWith("\"}") || !full.startsWith(tail, n - 20)) throw new StoreException("manifest has no CRC");
            String body = full.substring(0, n - 20) + "}";
            CRC32 c = new CRC32();
            c.update(body.getBytes(StandardCharsets.UTF_8));
            if (!Hex.of8(c.getValue()).equals(full.substring(n - 10, n - 2))) throw new StoreException("manifest CRC mismatch");
            return fields(body);
        }

        /** Without the CRC check (only to learn gen and hw before a reset); null if unparseable. */
        static Manifest parseLenient(String full) {
            try {
                int n = full.length();
                String body = n >= 22 && full.endsWith("\"}") ? full.substring(0, n - 20) + "}" : full;
                return fields(body);
            } catch (StoreException e) { return null; } catch (RuntimeException e) { return null; }
        }

        private static Manifest fields(String body) throws StoreException {
            Map<String, Object> j;
            try { j = Json.parseObject(body); } catch (JsonException e) { throw new StoreException("manifest: " + e.getMessage()); }
            if (!FORMAT.equals(Json.str(j, "format"))) throw new StoreException("not a v2 disk store manifest");
            Manifest m = new Manifest();
            m.version = Json.integer(j, "version", -1);
            m.schema = Json.integer(j, "schema", -1);
            m.gen = Json.lng(j, "gen", -1);
            m.set = Json.str(j, "set");
            m.txnNext = Json.lng(j, "txnNext", -1);
            m.commitSeq = Json.lng(j, "commitSeq", -1);
            if (m.version < 1 || m.gen < 0 || m.set == null || m.txnNext < 1 || m.commitSeq < 0) throw new StoreException("manifest fields");
            List<Object> sl = Json.list(j, "segments");
            if (sl == null) throw new StoreException("manifest segments");
            for (Object o : sl) {
                if (!(o instanceof Map)) throw new StoreException("manifest segment");
                @SuppressWarnings("unchecked") Map<String, Object> e = (Map<String, Object>) o;
                String name = Json.str(e, "name");
                long d = Json.lng(e, "durable", -1);
                int seq = seqOf(name, m.set);
                if (seq < 1 || d < Frame.MIN_FRAME) throw new StoreException("manifest segment entry");
                m.segments.add(new SegEntry(name, seq, d));
            }
            Map<String, Object> hw = Json.obj(j, "hw");
            if (hw == null) throw new StoreException("manifest hw");
            m.hwA = Json.integer(hw, "a", -1); m.hwB = Json.integer(hw, "b", -1);
            if (m.hwA < 0 || m.hwB < 0) throw new StoreException("manifest hw");
            Map<String, Object> ix = Json.obj(j, "index");
            if (ix != null) {
                m.idxName = Json.str(ix, "name");
                m.idxSeq = Json.lng(ix, "idxSeq", -1);
                String c = Json.str(ix, "crc32");
                if (!idxName(m.set).equals(m.idxName) || m.idxSeq < 1 || c == null || c.length() != 8) throw new StoreException("manifest index");
                try { m.idxCrc = Long.parseLong(c, 16); } catch (NumberFormatException e) { throw new StoreException("manifest index crc"); }
            }
            m.live = Json.lng(j, "live", 0); m.garbage = Json.lng(j, "garbage", 0);
            m.damaged = Json.bool(j, "damaged", true);
            return m;
        }

        static int seqOf(String name, String set) {
            if (name == null) return -1;
            String p = "ds-" + set + "-";
            if (!name.startsWith(p) || !name.endsWith(".seg")) return -1;
            String num = name.substring(p.length(), name.length() - 4);
            if (num.length() < 4 || num.length() > 5) return -1;
            for (int i = 0; i < num.length(); i++) if (num.charAt(i) < '0' || num.charAt(i) > '9') return -1;
            int seq = Integer.parseInt(num);
            return seq <= 0xFFFF && segName(set, seq).equals(name) ? seq : -1;
        }
    }

    private static void writeManifest(BlobStore blobs, Manifest m) throws IOException {
        BlobStore.AtomicWrite aw = blobs.replace(MANIFEST);
        boolean ok = false;
        try {
            OutputStream o = aw.stream();
            o.write(m.text().getBytes(StandardCharsets.UTF_8));
            o.flush();
            aw.commit();
            ok = true;
        } finally {
            if (!ok) aw.abort();
        }
    }

    // ================================================================ checkpoint, commit, rollback

    private int hwOf(Manifest m) { return "a".equals(m.set) ? m.hwA : m.hwB; }

    /** flush buffers, sync, (index file when due or forced), manifest (3.9). Called only at a commit boundary. */
    private void checkpoint(boolean forceIndex) throws StoreException {
        long t0 = clock.monotonicNanos();
        try {
            if (writer != null) { writer.sync(); segs.get(writerSeq).limit = writer.length(); }
            Manifest m = durable.copy();
            m.damaged = damagedMark;            // once marked, every later manifest says so (Pipeline.fail flushes after the error)
            m.schema = schemaOnDisk;
            m.txnNext = txnNext;
            m.commitSeq = commitSeq;
            m.segments = new ArrayList<Manifest.SegEntry>();
            long disk = 0;
            for (Seg sg : segs.values()) { m.segments.add(new Manifest.SegEntry(sg.name, sg.seq, sg.limit)); disk += sg.limit; }
            m.live = liveBytes; m.garbage = disk - liveBytes;
            long tail = uncoveredTail();
            if (tail > 0 && (forceIndex || tail > opt.indexTailBytes)) {
                writeIndex();
                m.idxName = idxName(m.set); m.idxSeq = idxSeq; m.idxCrc = idxCrc;
            } else if (idxValid) {
                m.idxName = idxName(m.set); m.idxSeq = idxSeq; m.idxCrc = idxCrc;
            } else {
                m.idxName = null;
            }
            writeManifest(blobs, m);
            durable = m;
        } catch (IOException e) {
            broken = true;
            throw new StoreException("checkpoint failed: " + e, e);
        }
        long t1 = clock.monotonicNanos();
        lastCkDurationMs = (t1 - t0) / 1000000L;
        lastCkNanos = t1;
        dirty = false;
        checkpoints++;
    }

    public synchronized void commit() throws StoreException {
        if (broken) throw new StoreException("store write failed earlier");
        undo.clear();
        commitSeq++;
        if (txnFrames > 0) {
            try { append(Frame.commit(txn, commitSeq)); }
            catch (StoreCorruption e) { throw new StoreException(e.getMessage(), e); }
        }
        txnFrames = 0;
        txn = txnNext++;
        if (!dirty) return;
        if (writer != null && writer.length() >= opt.maxSegmentBytes) { checkpoint(false); closeWriter(); return; }
        long interval = checkpointEveryMs >= 0 ? checkpointEveryMs : Math.max(5000L, 20 * lastCkDurationMs);
        long elapsedMs = (clock.monotonicNanos() - lastCkNanos) / 1000000L;
        if (elapsedMs >= interval) checkpoint(false);
    }

    public synchronized void flush() throws StoreException {
        commit();
        if (dirty) checkpoint(false);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public synchronized void rollback() {
        for (int i = undo.size() - 1; i >= 0; i--) {
            Undo u = undo.get(i);
            DTable t = u.t;
            int slot = t.prim.get(u.key);
            if (t.hasSecondary() && slot >= 0) t.unindex(readRecord(t, u.key, slot));
            lruRemove(t, u.key);
            if (u.existed) {
                if (slot < 0) { slot = allocSlot(); t.prim.put(u.key, slot); } else liveBytes -= sLen(slot);
                setSlot(slot, u.loc, u.hash, u.flen); liveBytes += u.flen;
                if (t.hasSecondary()) t.index(readRecord(t, u.key, slot));
            } else if (slot >= 0) {
                t.prim.remove(u.key);
                liveBytes -= sLen(slot);
                freeSlot(slot);
            }
        }
        undo.clear();
        txnFrames = 0;
        txn = txnNext++;
    }

    /** flush(), then the index file (if the tail is not covered) and the manifest naming it; releases file handles. */
    public void close() throws IOException {
        synchronized (lock) {
            try {
                flush();
                if (opt.indexAtClose && uncoveredTail() > 0) checkpoint(true);
            } catch (StoreException e) {
                closeQuietly();
                throw new IOException(e.getMessage(), e);
            }
            closeWriter();
            closeReaders();
        }
    }

    private void closeWriter() {
        if (writer != null) {
            try { writer.close(); } catch (IOException ignored) { /* the bytes that matter were synced at the checkpoint */ }
            writer = null; writerSeq = -1;
        }
    }

    private void closeReaders() {
        for (SegmentReader r : readers.values()) { try { r.close(); } catch (IOException ignored) { /* read handle */ } }
        readers.clear();
    }

    private void closeQuietly() { closeWriter(); closeReaders(); }

    // ================================================================ append path

    private long append(byte[] frame) {
        if (broken) throw new StoreCorruption("store write failed earlier");
        try {
            if (writer == null) startSegment();
            long pos = writer.append(frame);
            dirty = true;
            return ((long) writerSeq << 48) | pos;
        } catch (IOException e) {
            broken = true;
            throw new StoreCorruption("store append failed: " + e, e);
        }
    }

    /** Starts the next segment: raise the high-water mark in the manifest first (F-02 c), then truncate + HEADER (F-02 a). */
    private void startSegment() throws IOException {
        int next = segs.isEmpty() ? 1 : segs.lastKey() + 1;
        if (next > 0xFFFF) throw new IOException("segment numbers exhausted");
        if (next > hwOf(durable)) {
            Manifest m = durable.copy();
            if ("a".equals(m.set)) m.hwA = next; else m.hwB = next;
            writeManifest(blobs, m);
            durable = m;
        }
        String name = segName(durable.set, next);
        SegmentReader old = readers.remove(next);
        if (old != null) old.close();
        String header = "{\"version\":" + VERSION + ",\"schema\":" + schemaOnDisk + ",\"gen\":" + durable.gen + ",\"set\":\"" + durable.set
                + "\",\"seq\":" + next + ",\"epoch\":" + durable.commitSeq + "}";
        writer = SegmentWriter.start(blobs, name, header);
        writerSeq = next;
        segs.put(next, new Seg(next, name, 0));
    }

    // ================================================================ read path (3.6, F-04)

    private SegmentReader reader(Seg sg) throws IOException {
        SegmentReader r = readers.get(sg.seq);
        if (r != null) return r;
        BlobStore.RandomRead rr = blobs.openRead(sg.name);
        if (rr == null) throw new IOException("segment missing: " + sg.name);
        r = new SegmentReader(rr, 4096);
        readers.put(sg.seq, r);
        if (readers.size() > MAX_READERS) {
            Iterator<Map.Entry<Integer, SegmentReader>> it = readers.entrySet().iterator();
            SegmentReader eldest = it.next().getValue();
            it.remove();
            eldest.close();
        }
        return r;
    }

    /** The frame at a locator, read within the segment's readable limit. */
    private Frame frameAt(long l) throws FrameException, IOException {
        int seq = (int) (l >>> 48);
        long off = l & 0xFFFFFFFFFFFFL;
        Seg sg = segs.get(seq);
        if (sg == null) throw corrupt("locator names an unknown segment " + seq);
        long limit;
        if (seq == writerSeq) {
            if (off >= writer.flushedLength()) writer.flushBuffer();
            limit = writer.flushedLength();
        } else limit = sg.limit;
        return reader(sg).readAt(off, limit);
    }

    private static String ck(DTable<?> t, String key) { return (char) t.id + key; }

    private void lruRemove(DTable<?> t, String key) {
        Cached c = lru.remove(ck(t, key));
        if (c != null) lruBytes -= c.len;
    }

    private void lruPut(DTable<?> t, String key, Record r, int len) {
        Cached old = lru.put(ck(t, key), new Cached(r, len));
        if (old != null) lruBytes -= old.len;
        lruBytes += len;
        Iterator<Map.Entry<String, Cached>> it = lru.entrySet().iterator();
        while ((lru.size() > opt.lruRecords || lruBytes > opt.lruBytes) && it.hasNext()) {
            lruBytes -= it.next().getValue().len;
            it.remove();
        }
    }

    /**
     * The verified frame of key at its slot. Damage → null for a derived table (counted), else StoreCorruption and the
     * manifest marked damaged. A CRC-valid frame of another type, table, key or value → StoreCorruption (F-04).
     */
    private Frame verifiedFrame(DTable<?> t, String key, int slot) {
        Frame f;
        try {
            f = frameAt(sLoc(slot));
        } catch (FrameException e) {
            return damaged(t, key, e.getMessage());
        } catch (IOException e) {
            throw new StoreCorruption("store read failed (" + TableIds.name(t.id) + "): " + e, e);
        }
        if (f.type != Frame.PUT || f.table != t.id || !Arrays.equals(f.key, KeyCmp.encode(key))
                || f.length != sLen(slot) || fnv(f.val) != sHash(slot))
            throw corrupt("locator of " + TableIds.name(t.id) + " key " + key + " reached another frame (type " + f.type
                    + ", table " + f.table + ", key " + KeyCmp.decode(f.key) + ")");
        return f;
    }

    private Frame damaged(DTable<?> t, String key, String why) {
        if (TableIds.derived(t.id) && !opening) {
            damagedKeys.add(TableIds.name(t.id) + "\u0000" + key);
            return null;
        }
        throw corrupt("damaged record in " + TableIds.name(t.id) + " (" + key + "): " + why);
    }

    /** Fail closed: marks the manifest damaged (outside open) and returns the exception to throw. */
    private StoreCorruption corrupt(String why) {
        if (!opening) markDamaged();
        return new StoreCorruption(why);
    }

    /** Writes damaged:true into the manifest at once (3.11); the next open refuses and the caller starts fresh. */
    private void markDamaged() {
        damagedMark = true;
        try {
            Manifest m = durable.copy();
            m.damaged = true;
            writeManifest(blobs, m);
            durable = m;
        } catch (IOException ignored) {
            // the exception that follows fails the run anyway
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Record decode(DTable t, String key, byte[] val) {
        Record r;
        try {
            r = (Record) t.codec.read(Codecs.migrate(schemaOnDisk, Json.parseObject(new String(val, StandardCharsets.UTF_8))));
        } catch (JsonException e) {
            throw corrupt("undecodable record in " + TableIds.name(t.id) + " (" + key + "): " + e.getMessage());
        } catch (RuntimeException e) {
            throw corrupt("undecodable record in " + TableIds.name(t.id) + " (" + key + "): " + e);
        }
        if (!key.equals(r.key()))
            throw corrupt("record under key " + key + " of " + TableIds.name(t.id) + " has key " + r.key());
        return r;
    }

    /** The stored record (not a copy; never hand it out), or null for a damaged derived record. */
    private Record readRecord(DTable<?> t, String key, int slot) {
        Cached c = lru.get(ck(t, key));
        if (c != null) return c.r;
        Frame f = verifiedFrame(t, key, slot);
        if (f == null) return null;
        Record r = decode(t, key, f.val);
        lruPut(t, key, r, f.val.length);
        return r;
    }

    // ================================================================ slots

    private int allocSlot() {
        if (freeN > 0) return free[--freeN];
        if ((slotTop >> PAGE_BITS) == locP.length) {
            int n = locP.length + 1;
            locP = Arrays.copyOf(locP, n); hashP = Arrays.copyOf(hashP, n); lenP = Arrays.copyOf(lenP, n);
            locP[n - 1] = new long[PAGE]; hashP[n - 1] = new int[PAGE]; lenP[n - 1] = new int[PAGE];
        }
        return slotTop++;
    }

    private long sLoc(int s) { return locP[s >> PAGE_BITS][s & PMASK]; }
    private long sHash(int s) { return hashP[s >> PAGE_BITS][s & PMASK] & 0xffffffffL; }
    private int sLen(int s) { return lenP[s >> PAGE_BITS][s & PMASK]; }
    private void setSlot(int s, long l, long h, int len) {
        locP[s >> PAGE_BITS][s & PMASK] = l; hashP[s >> PAGE_BITS][s & PMASK] = (int) h; lenP[s >> PAGE_BITS][s & PMASK] = len;
    }

    private void freeSlot(int s) {
        if (freeN == free.length) free = Arrays.copyOf(free, free.length * 2);
        free[freeN++] = s;
    }

    /** FNV-1a 64 of b, folded to 32 bits (stored per slot). */
    static long fnv(byte[] b) {
        long h = 0xcbf29ce484222325L;
        for (byte x : b) { h ^= (x & 0xff); h *= 0x100000001b3L; }
        return (h ^ (h >>> 32)) & 0xffffffffL;
    }

    // ================================================================ public extras

    public int schemaVersion() { return V2.STORE_SCHEMA; }
    public synchronized long commitSeq() { return commitSeq; }
    public synchronized long changedPutsSinceOpen() { return changedPuts; }
    public synchronized int checkpointsWritten() { return checkpoints; }
    /** Whether open() used the index file (else it rebuilt the indexes by scanning the log). */
    public synchronized boolean loadedFromIndex() { return loadedFromIndex; }
    /** Keys of derived tables found damaged since open ("table\0key"). */
    public synchronized List<String> damagedKeys() { return new ArrayList<String>(damagedKeys); }
    /** KeyIndex merges so far over all tables (tests). */
    public synchronized int merges() {
        int m = 0;
        for (int i = 1; i <= TableIds.COUNT; i++) { m += byId[i].prim.merges(); for (KeyIndex x : byId[i].secondaries()) m += x.merges(); }
        return m;
    }

    public synchronized StoreStats stats() {
        long records = 0, idx = 0;
        for (int i = 1; i <= TableIds.COUNT; i++) {
            DTable<?> t = byId[i];
            records += t.prim.size();
            idx += t.prim.bytesEstimate();
            for (KeyIndex x : t.secondaries()) idx += x.bytesEstimate();
        }
        idx += files.lazyBytesEstimate();
        idx += 16L * PAGE * locP.length + 24L * locP.length + 4L * free.length;   // slot pages (loc 8 + hash 4 + len 4), free list
        long disk = 0;
        for (Seg sg : segs.values()) disk += sg.seq == writerSeq ? writer.length() : sg.limit;
        return new StoreStats(records, idx, liveBytes, disk - liveBytes, disk, damagedKeys.size());
    }

    /** Tests only (F-04 injection): {loc, hash, flen} of key, or null. */
    public synchronized long[] debugLocator(int tableId, String key) {
        int s = byId[tableId].prim.get(key);
        return s < 0 ? null : new long[]{sLoc(s), sHash(s), sLen(s)};
    }

    /** Tests only (F-04 injection): points key's slot at another locator, as a locator bug would. */
    public synchronized void debugSetLocator(int tableId, String key, long l, long h, int fl) {
        int s = byId[tableId].prim.get(key);
        if (s < 0) throw new IllegalArgumentException("no such key");
        setSlot(s, l, h, fl);
        lruRemove(byId[tableId], key);
    }

    /** Tests only: forgets decoded records, so the next read goes to the log. */
    public synchronized void debugDropCache() { lru.clear(); lruBytes = 0; }

    // ================================================================ accessors

    public FileTable files() { return files; }
    public Table<FeatureRecord> features() { return features; }
    public Table<HashRecord> hashes() { return hashes; }
    public Table<EmbeddingRecord> embeddings() { return embeddings; }
    public RelationshipTable relationships() { return relationships; }
    public Table<DuplicateGroup> duplicateGroups() { return duplicateGroups; }
    public Table<BackupRecord> backups() { return backups; }
    public Table<PairCheck> pairChecks() { return pairChecks; }
    public RecommendationTable recommendations() { return recommendations; }
    public Table<ActionPlan> actionPlans() { return actionPlans; }
    public JournalTable operations() { return operations; }
    public Table<Feedback> feedback() { return feedback; }
    public Table<Preference> preferences() { return preferences; }
    public Table<ModelRecord> models() { return models; }
    public Table<ScanSession> scanSessions() { return scanSessions; }
    public Table<Volume> volumes() { return volumes; }
    public DirTable directories() { return directories; }

    // ================================================================ tables

    private static final KeyIndex[] NO_SECONDARY = new KeyIndex[0];

    class DTable<R extends Record> implements Table<R> {
        final int id;
        final Codecs.Codec<R> codec;
        KeyIndex prim;

        @SuppressWarnings("unchecked")
        DTable(int id) {
            this.id = id;
            this.codec = (Codecs.Codec<R>) TableIds.codec(id);
            this.prim = new KeyIndex(opt.mergeMin);
            byId[id] = this;
        }

        void reset() { prim = new KeyIndex(opt.mergeMin); resetSecondary(); }
        void resetSecondary() {}
        boolean hasSecondary() { return false; }
        KeyIndex[] secondaries() { return NO_SECONDARY; }
        void index(R r) {}
        void unindex(R r) {}

        @SuppressWarnings("unchecked")
        R cp(Record r) { return r == null ? null : (R) r.copy(); }

        /** The stored record (not a copy) or null; caller holds the lock. */
        @SuppressWarnings("unchecked")
        R raw(String key) {
            int slot = prim.get(key);
            return slot < 0 ? null : (R) readRecord(this, key, slot);
        }

        public R get(String key) { synchronized (lock) { return cp(raw(key)); } }

        public void put(R record) { synchronized (lock) { putLocked(record); } }

        @SuppressWarnings("unchecked")
        void putLocked(R record) {
            String k = record.key();
            byte[] val = Codecs.json(codec, record).getBytes(StandardCharsets.UTF_8);
            long h = fnv(val);
            int slot = prim.get(k);
            R old = null;
            if (slot >= 0) {
                if (sHash(slot) == h) {
                    Frame f = verifiedFrame(this, k, slot);
                    if (f != null && Arrays.equals(f.val, val)) return;   // no change (MemoryStore compares the JSON)
                }
                if (hasSecondary()) old = (R) readRecord(this, k, slot);
            }
            byte[] fr = Frame.encode(Frame.PUT, id, txn, KeyCmp.encode(k), val);
            long l = append(fr);
            txnFrames++;
            if (slot >= 0) {
                undo.add(new Undo(this, k, true, sLoc(slot), sHash(slot), sLen(slot)));
                liveBytes -= sLen(slot);
            } else {
                undo.add(new Undo(this, k, false, 0, 0, 0));
                slot = allocSlot();
                prim.put(k, slot);
            }
            setSlot(slot, l, h, fr.length); liveBytes += fr.length;
            lruRemove(this, k);
            if (hasSecondary()) {
                if (old != null) unindex(old);
                index(cp(record));
            }
            changedPuts++;
        }

        @SuppressWarnings("unchecked")
        public boolean remove(String key) {
            synchronized (lock) {
                int slot = prim.get(key);
                if (slot < 0) return false;
                R old = hasSecondary() ? (R) readRecord(this, key, slot) : null;
                byte[] fr = Frame.encode(Frame.DEL, id, txn, KeyCmp.encode(key), new byte[0]);
                append(fr);
                txnFrames++;
                undo.add(new Undo(this, key, true, sLoc(slot), sHash(slot), sLen(slot)));
                prim.remove(key);
                liveBytes -= sLen(slot);
                freeSlot(slot);
                lruRemove(this, key);
                if (old != null) unindex(old);
                return true;
            }
        }

        public int size() { synchronized (lock) { return prim.size(); } }

        public Iterable<R> all() { synchronized (lock) { return records(prim.keys()); } }

        public Iterable<String> keys() { synchronized (lock) { return prim.keys(); } }

        /** Copies of the records under a key snapshot, each read when reached; keys gone meanwhile are skipped. */
        Iterable<R> records(final Iterable<String> keys) {
            return new Iterable<R>() {
                public Iterator<R> iterator() {
                    final Iterator<String> ki = keys.iterator();
                    return new Iterator<R>() {
                        R next = advance();
                        R advance() { while (ki.hasNext()) { R r = get(ki.next()); if (r != null) return r; } return null; }
                        public boolean hasNext() { return next != null; }
                        public R next() { if (next == null) throw new NoSuchElementException(); R r = next; next = advance(); return r; }
                        public void remove() { throw new UnsupportedOperationException(); }
                    };
                }
            };
        }

        /** Copies now (caller holds the lock), skipping absent keys. */
        List<R> list(Iterable<String> keys) {
            List<R> l = new ArrayList<R>();
            for (String k : keys) { R r = raw(k); if (r != null) l.add(cp(r)); }
            return l;
        }
    }

    /** Last component after the separator: "a\0b\0c" → "c". */
    static String afterLastNul(String s) { return s.substring(s.lastIndexOf('\u0000') + 1); }
    /** The strings of [prefix, prefix+"\u0001") of a set index, with the prefix stripped. */
    static List<String> suffixes(KeyIndex x, String prefix) {
        List<String> l = new ArrayList<String>();
        for (String k : x.range(prefix + "\u0000", prefix + "\u0001")) l.add(k.substring(prefix.length() + 1));
        return l;
    }
    static Iterable<String> mapped(final Iterable<String> in, final boolean lastComponent) {
        return new Iterable<String>() {
            public Iterator<String> iterator() {
                final Iterator<String> it = in.iterator();
                return new Iterator<String>() {
                    public boolean hasNext() { return it.hasNext(); }
                    public String next() { String s = it.next(); return lastComponent ? afterLastNul(s) : s; }
                    public void remove() { throw new UnsupportedOperationException(); }
                };
            }
        };
    }

    final class FilesT extends DTable<FileRecord> implements FileTable {
        KeyIndex byPathKey = new KeyIndex(opt.mergeMin);              // volumeId \0 path \0 fileId
        TreeMap<Long, TreeSet<String>> bySize;                         // lazy (no production caller)
        HashMap<String, TreeSet<String>> bySha;                        // lazy
        FilesT() { super(TableIds.FILES); }

        @Override void resetSecondary() { byPathKey = new KeyIndex(opt.mergeMin); bySize = null; bySha = null; }
        @Override boolean hasSecondary() { return true; }
        @Override KeyIndex[] secondaries() { return new KeyIndex[]{byPathKey}; }
        String ok(FileRecord r) { return r.volumeId + "\u0000" + r.path + "\u0000" + r.fileId; }
        @Override void index(FileRecord r) {
            byPathKey.put(ok(r), 0);
            if (bySize != null) MemoryStore.add(bySize, r.sizeBytes, r.fileId);
            if (bySha != null && r.sha256 != null) MemoryStore.add(bySha, r.sha256, r.fileId);
        }
        @Override void unindex(FileRecord r) {
            byPathKey.remove(ok(r));
            if (bySize != null) MemoryStore.del(bySize, r.sizeBytes, r.fileId);
            if (bySha != null && r.sha256 != null) MemoryStore.del(bySha, r.sha256, r.fileId);
        }

        long lazyBytesEstimate() {
            long b = 0;
            if (bySize != null) for (TreeSet<String> s : bySize.values()) b += 120 + 80L * s.size();
            if (bySha != null) for (TreeSet<String> s : bySha.values()) b += 200 + 80L * s.size();
            return b;
        }

        private void buildLazy() {
            if (bySize != null) return;
            TreeMap<Long, TreeSet<String>> sz = new TreeMap<Long, TreeSet<String>>();
            HashMap<String, TreeSet<String>> sh = new HashMap<String, TreeSet<String>>();
            for (String k : prim.keys()) {
                FileRecord r = raw(k);
                MemoryStore.add(sz, r.sizeBytes, r.fileId);
                if (r.sha256 != null) MemoryStore.add(sh, r.sha256, r.fileId);
            }
            bySize = sz; bySha = sh;
        }

        public FileRecord byPath(String volumeId, String path) {
            synchronized (lock) {
                FileRecord best = null;
                for (String id : suffixes(byPathKey, volumeId + "\u0000" + path)) {
                    FileRecord r = raw(id);
                    if (r == null) throw corrupt("path index names a missing file " + id);
                    if (r.presence != Presence.REMOVED) return cp(r);
                    if (best == null || r.changeScanId > best.changeScanId) best = r;
                }
                return cp(best);
            }
        }
        public List<FileRecord> bySize(long size) {
            synchronized (lock) { buildLazy(); TreeSet<String> s = bySize.get(size); return s == null ? new ArrayList<FileRecord>() : list(s); }
        }
        public List<FileRecord> bySha256(String sha) {
            synchronized (lock) { buildLazy(); TreeSet<String> s = bySha.get(sha); return s == null ? new ArrayList<FileRecord>() : list(s); }
        }
        public Iterable<FileRecord> byVolume(String volumeId) { return underPrefix(volumeId, ""); }
        public Iterable<FileRecord> underPrefix(String volumeId, String dirPath) {
            synchronized (lock) {
                Iterable<String> r = dirPath.isEmpty()
                        ? byPathKey.range(volumeId + "\u0000", volumeId + "\u0001")
                        : byPathKey.range(volumeId + "\u0000" + dirPath + "/", volumeId + "\u0000" + dirPath + "0");
                return records(mapped(r, true));
            }
        }
        public Iterable<FileRecord> childrenOf(String volumeId, String dirPath) {
            synchronized (lock) {
                final String base = dirPath.isEmpty() ? volumeId + "\u0000" : volumeId + "\u0000" + dirPath + "/";
                final Iterable<String> r = dirPath.isEmpty()
                        ? byPathKey.range(volumeId + "\u0000", volumeId + "\u0001")
                        : byPathKey.range(base, volumeId + "\u0000" + dirPath + "0");
                return records(childIds(r, base.length()));
            }
        }
        /** fileIds of composite keys whose path, after the dir prefix, is non-empty-or-child and has no further '/'. */
        private Iterable<String> childIds(final Iterable<String> r, final int baseLen) {
            return new Iterable<String>() {
                public Iterator<String> iterator() {
                    final Iterator<String> it = r.iterator();
                    return new Iterator<String>() {
                        String next = adv();
                        String adv() {
                            while (it.hasNext()) {
                                String k = it.next();
                                int z = k.lastIndexOf('\u0000');
                                String path = k.substring(k.indexOf('\u0000') + 1, z);
                                String rest = k.substring(baseLen, z);
                                if (path.isEmpty() || rest.indexOf('/') >= 0) continue;
                                return k.substring(z + 1);
                            }
                            return null;
                        }
                        public boolean hasNext() { return next != null; }
                        public String next() { if (next == null) throw new NoSuchElementException(); String s = next; next = adv(); return s; }
                        public void remove() { throw new UnsupportedOperationException(); }
                    };
                }
            };
        }
    }

    final class DirsT extends DTable<DirRecord> implements DirTable {
        DirsT() { super(TableIds.DIRECTORIES); }
        public DirRecord get(String volumeId, String path) { return get(DirRecord.keyOf(volumeId, path)); }
        public Iterable<DirRecord> childrenOf(final String volumeId, String dirPath) {
            synchronized (lock) {
                final String base = dirPath.isEmpty() ? volumeId + "\u0000" : volumeId + "\u0000" + dirPath + "/";
                final Iterable<String> r = dirPath.isEmpty()
                        ? prim.range(volumeId + "\u0000", volumeId + "\u0001")
                        : prim.range(base, volumeId + "\u0000" + dirPath + "0");
                return records(new Iterable<String>() {
                    public Iterator<String> iterator() {
                        final Iterator<String> it = r.iterator();
                        return new Iterator<String>() {
                            String next = adv();
                            String adv() {
                                while (it.hasNext()) {
                                    String k = it.next();
                                    String path = k.substring(volumeId.length() + 1);
                                    if (path.isEmpty() || k.substring(base.length()).indexOf('/') >= 0) continue;
                                    return k;
                                }
                                return null;
                            }
                            public boolean hasNext() { return next != null; }
                            public String next() { if (next == null) throw new NoSuchElementException(); String s = next; next = adv(); return s; }
                            public void remove() { throw new UnsupportedOperationException(); }
                        };
                    }
                });
            }
        }
        public Iterable<DirRecord> underPrefix(String volumeId, String dirPath) {
            synchronized (lock) {
                if (dirPath.isEmpty()) {
                    final String root = DirRecord.keyOf(volumeId, "");
                    final Iterable<String> r = prim.range(volumeId + "\u0000", volumeId + "\u0001");
                    return records(new Iterable<String>() {
                        public Iterator<String> iterator() {
                            final Iterator<String> it = r.iterator();
                            return new Iterator<String>() {
                                String next = adv();
                                String adv() { while (it.hasNext()) { String k = it.next(); if (!k.equals(root)) return k; } return null; }
                                public boolean hasNext() { return next != null; }
                                public String next() { if (next == null) throw new NoSuchElementException(); String s = next; next = adv(); return s; }
                                public void remove() { throw new UnsupportedOperationException(); }
                            };
                        }
                    });
                }
                return records(prim.range(volumeId + "\u0000" + dirPath + "/", volumeId + "\u0000" + dirPath + "0"));
            }
        }
    }

    final class RelsT extends DTable<Relationship> implements RelationshipTable {
        KeyIndex byNode = new KeyIndex(opt.mergeMin);                  // nodeRef \0 edgeId
        RelsT() { super(TableIds.RELATIONSHIPS); }
        @Override void resetSecondary() { byNode = new KeyIndex(opt.mergeMin); }
        @Override boolean hasSecondary() { return true; }
        @Override KeyIndex[] secondaries() { return new KeyIndex[]{byNode}; }
        @Override void index(Relationship r) { if (r.src != null) byNode.put(r.src + "\u0000" + r.key(), 0); if (r.dst != null) byNode.put(r.dst + "\u0000" + r.key(), 0); }
        @Override void unindex(Relationship r) { if (r.src != null) byNode.remove(r.src + "\u0000" + r.key()); if (r.dst != null) byNode.remove(r.dst + "\u0000" + r.key()); }
        public List<Relationship> of(String nodeRef) {
            synchronized (lock) { return checked(list(suffixes(byNode, nodeRef)), nodeRef); }
        }
        private List<Relationship> checked(List<Relationship> l, String node) {
            for (Relationship r : l) if (!node.equals(r.src) && !node.equals(r.dst)) throw corrupt("node index mismatch for " + r.key());
            return l;
        }
    }

    final class RecsT extends DTable<Recommendation> implements RecommendationTable {
        KeyIndex byFile = new KeyIndex(opt.mergeMin);                  // fileId \0 recommendationId
        RecsT() { super(TableIds.RECOMMENDATIONS); }
        @Override void resetSecondary() { byFile = new KeyIndex(opt.mergeMin); }
        @Override boolean hasSecondary() { return true; }
        @Override KeyIndex[] secondaries() { return new KeyIndex[]{byFile}; }
        @Override void index(Recommendation r) { if (r.fileId != null) byFile.put(r.fileId + "\u0000" + r.key(), 0); }
        @Override void unindex(Recommendation r) { if (r.fileId != null) byFile.remove(r.fileId + "\u0000" + r.key()); }
        public List<Recommendation> forFile(String fileId) {
            synchronized (lock) {
                List<Recommendation> l = list(suffixes(byFile, fileId));
                for (Recommendation r : l) if (!fileId.equals(r.fileId)) throw corrupt("file index mismatch for " + r.key());
                return l;
            }
        }
    }

    final class OpsT extends DTable<OperationEntry> implements JournalTable {
        KeyIndex byOp = new KeyIndex(opt.mergeMin);                    // opId \0 key
        OpsT() { super(TableIds.OPERATIONS); }
        @Override void resetSecondary() { byOp = new KeyIndex(opt.mergeMin); }
        @Override boolean hasSecondary() { return true; }
        @Override KeyIndex[] secondaries() { return new KeyIndex[]{byOp}; }
        @Override void index(OperationEntry r) { byOp.put(r.opId + "\u0000" + r.key(), 0); }
        @Override void unindex(OperationEntry r) { byOp.remove(r.opId + "\u0000" + r.key()); }
        @Override public void put(OperationEntry r) {
            synchronized (lock) {
                if (prim.get(r.key()) >= 0) throw new IllegalStateException("journal entry exists: " + r.key());
                putLocked(r);
            }
        }
        public List<OperationEntry> forOp(String opId) {
            synchronized (lock) {
                List<OperationEntry> l = list(suffixes(byOp, opId));
                for (OperationEntry e : l) if (!opId.equals(e.opId)) throw corrupt("op index mismatch for " + e.key());
                Collections.sort(l, new Comparator<OperationEntry>() { public int compare(OperationEntry a, OperationEntry b) { return a.seq < b.seq ? -1 : a.seq == b.seq ? 0 : 1; } });
                return l;
            }
        }
        public List<String> openOps() {
            synchronized (lock) {
                TreeSet<String> ops = new TreeSet<String>();
                for (String k : byOp.keys()) ops.add(k.substring(0, k.indexOf('\u0000')));
                List<String> r = new ArrayList<String>();
                for (String op : ops) {
                    List<OperationEntry> l = forOp(op);
                    OpPhase p = l.get(l.size() - 1).phase;
                    if (p != OpPhase.COMMITTED && p != OpPhase.ROLLED_BACK && p != OpPhase.FAILED && p != OpPhase.PRECHECK_FAILED && p != OpPhase.RESTORED) r.add(op);
                }
                return r;
            }
        }
    }
}
