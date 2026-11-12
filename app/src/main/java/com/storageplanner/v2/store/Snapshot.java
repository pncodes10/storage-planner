package com.storageplanner.v2.store;

import com.storageplanner.v2.V2;
import com.storageplanner.v2.device.BlobStore;
import com.storageplanner.v2.model.Record;
import com.storageplanner.v2.util.Hex;
import com.storageplanner.v2.util.Json;
import com.storageplanner.v2.util.JsonException;
import com.storageplanner.v2.util.JsonWriter;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.CRC32;

/** The snapshot format of ARCHITECTURE 4.2: header line, one line per record, trailer with count and CRC32. */
final class Snapshot {
    static final String FORMAT = "sp-v2-store";
    private Snapshot() {}

    private static final class CrcOut extends FilterOutputStream {
        final CRC32 crc = new CRC32();
        CrcOut(OutputStream o) { super(o); }
        @Override public void write(int b) throws IOException { out.write(b); crc.update(b); }
        @Override public void write(byte[] b, int off, int len) throws IOException { out.write(b, off, len); crc.update(b, off, len); }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static void write(MemoryStore s, BlobStore blobs, long commitSeq, long createdAt) throws StoreException {
        BlobStore.AtomicWrite aw;
        try { aw = blobs.replace(MemoryStore.SNAPSHOT); } catch (IOException e) { throw new StoreException("snapshot open: " + e, e); }
        boolean ok = false;
        try {
            CrcOut co = new CrcOut(aw.stream());
            Writer wr = new OutputStreamWriter(co, StandardCharsets.UTF_8);
            java.io.BufferedWriter bw = new java.io.BufferedWriter(wr, 1 << 16);
            JsonWriter jw = new JsonWriter(bw);
            jw.beginObject().name("format").value(FORMAT).name("schema").value(V2.STORE_SCHEMA).name("commitSeq").value(commitSeq)
              .name("createdAt").value(createdAt).endObject();
            bw.write('\n');
            long n = 0;
            for (MemoryStore.MemTable t : s.tables) {
                for (Object r : t.raw()) {
                    jw = new JsonWriter(bw);
                    jw.beginObject().name("t").value(t.codec.table()).name("v");
                    t.codec.write(jw, (Record) r);
                    jw.endObject();
                    bw.write('\n');
                    n++;
                }
            }
            bw.flush();
            String crc = Hex.of8(co.crc.getValue());
            jw = new JsonWriter(bw);
            jw.beginObject().name("end").value(true).name("records").value(n).name("crc32").value(crc).endObject();
            bw.write('\n');
            bw.flush();
            aw.commit();
            ok = true;
        } catch (IOException e) {
            throw new StoreException("snapshot write: " + e, e);
        } catch (JsonWriter.UncheckedIo e) {
            throw new StoreException("snapshot write: " + e.io, e.io);
        } finally {
            if (!ok) aw.abort();
        }
    }

    /** Loads into an empty store; returns commitSeq. Any defect → StoreException (never a partial load: the store is discarded). */
    static long read(InputStream raw, MemoryStore s) throws StoreException {
        Map<String, MemoryStore.MemTable<?>> byName = new HashMap<String, MemoryStore.MemTable<?>>();
        for (MemoryStore.MemTable<?> t : s.tables) byName.put(t.codec.table(), t);
        InputStream in = new BufferedInputStream(raw, 1 << 16);
        CRC32 crc = new CRC32();
        ByteArrayOutputStream line = new ByteArrayOutputStream(1024);
        long lineNo = 0, records = 0, commitSeq = -1;
        boolean ended = false;
        try {
            while (true) {
                line.reset();
                int b;
                boolean nl = false;
                while ((b = in.read()) >= 0) { if (b == '\n') { nl = true; break; } line.write(b); }
                if (!nl) {
                    if (line.size() == 0 && b < 0) break;
                    throw new StoreException("snapshot truncated (line " + lineNo + ")");
                }
                if (ended) throw new StoreException("data after snapshot trailer");
                byte[] bytes = line.toByteArray();
                String text = new String(bytes, StandardCharsets.UTF_8);
                Map<String, Object> m;
                try { m = Json.parseObject(text); } catch (JsonException e) { throw new StoreException("snapshot line " + lineNo + ": " + e.getMessage()); }
                if (lineNo == 0) {
                    if (!FORMAT.equals(Json.str(m, "format"))) throw new StoreException("not a v2 store snapshot");
                    int schema = Json.integer(m, "schema", -1);
                    if (schema < 1 || schema > V2.STORE_SCHEMA) throw new StoreException("unsupported store schema " + schema);
                    commitSeq = Json.lng(m, "commitSeq", 0);
                } else if (m.containsKey("end")) {
                    if (Json.lng(m, "records", -1) != records) throw new StoreException("snapshot record count mismatch");
                    if (!Hex.of8(crc.getValue()).equals(Json.str(m, "crc32"))) throw new StoreException("snapshot CRC mismatch");
                    ended = true;
                } else {
                    String t = Json.str(m, "t");
                    Map<String, Object> v = Json.obj(m, "v");
                    if (t == null || v == null) throw new StoreException("snapshot line " + lineNo + ": bad record");
                    MemoryStore.MemTable<?> table = byName.get(t);
                    if (table != null) table.load(table.codec.read(Codecs.migrate(V2.STORE_SCHEMA, v)));
                    records++;
                }
                if (!ended) { crc.update(bytes, 0, bytes.length); crc.update('\n'); }
                lineNo++;
            }
        } catch (IOException e) {
            throw new StoreException("snapshot read: " + e, e);
        } catch (RuntimeException e) {
            throw new StoreException("snapshot decode: " + e, e);
        }
        if (!ended) throw new StoreException("snapshot has no trailer");
        return commitSeq;
    }
}
