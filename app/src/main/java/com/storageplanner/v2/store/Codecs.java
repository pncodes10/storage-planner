package com.storageplanner.v2.store;

import com.storageplanner.v2.model.*;
import com.storageplanner.v2.util.Hex;
import com.storageplanner.v2.util.Json;
import com.storageplanner.v2.util.JsonWriter;

import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Record <-> JSON for every table (ARCHITECTURE 4.2). Unknowns are written as null and read back as the 2.1
 * sentinels; unknown fields are ignored; missing fields take their unknown value.
 */
public final class Codecs {
    private Codecs() {}

    public interface Codec<R extends Record> {
        /** Snapshot table name. */
        String table();
        void write(JsonWriter w, R r);
        R read(Map<String, Object> m);
    }

    /** The record as one compact JSON object (also used to detect no-op puts). */
    public static <R extends Record> String json(Codec<R> c, R r) {
        StringWriter sw = new StringWriter(256);
        c.write(new JsonWriter(sw), r);
        return sw.toString();
    }

    /** Schema migrations; none exist at schema 1. */
    public static Map<String, Object> migrate(int from, Map<String, Object> rec) { return rec; }

    // ---------------------------------------------------------------- shared pieces

    public static void writeGrant(JsonWriter w, AccessGrant g) {
        w.beginObject();
        w.name("volumeId").value(g.volumeId).name("prefix").value(g.prefix).name("mode").value(g.mode);
        w.name("canList").value(g.canList).name("canReadMetadata").value(g.canReadMetadata)
         .name("canReadContent").value(g.canReadContent).name("canWrite").value(g.canWrite).name("canDelete").value(g.canDelete);
        w.name("mediaTypes").strings(g.mediaTypes);
        w.name("firstSeenAt").valueTime(g.firstSeenAt).name("lastSeenAt").valueTime(g.lastSeenAt).name("revokedAt").valueTime(g.revokedAt);
        w.name("active").value(g.active);
        w.endObject();
    }

    public static AccessGrant readGrant(Map<String, Object> m) {
        AccessGrant g = new AccessGrant();
        g.volumeId = Json.str(m, "volumeId"); g.prefix = Json.str(m, "prefix"); if (g.prefix == null) g.prefix = "";
        g.mode = Json.enumOf(m, "mode", AccessMode.class, null);
        g.canList = tri(m, "canList"); g.canReadMetadata = tri(m, "canReadMetadata"); g.canReadContent = tri(m, "canReadContent");
        g.canWrite = tri(m, "canWrite"); g.canDelete = tri(m, "canDelete");
        g.mediaTypes = Json.strings(m, "mediaTypes");
        g.firstSeenAt = Json.lng(m, "firstSeenAt", Unknown.TIME); g.lastSeenAt = Json.lng(m, "lastSeenAt", Unknown.TIME);
        g.revokedAt = Json.lng(m, "revokedAt", Unknown.TIME);
        g.active = Json.bool(m, "active", false);
        return g;
    }

    static Tri tri(Map<String, Object> m, String k) { Object o = m.get(k); return o instanceof Boolean ? Tri.of((Boolean) o) : Tri.UNKNOWN; }

    static void writeGrants(JsonWriter w, List<AccessGrant> l) {
        if (l == null) { w.nullValue(); return; }
        w.beginArray(); for (AccessGrant g : l) writeGrant(w, g); w.endArray();
    }

    @SuppressWarnings("unchecked")
    static List<AccessGrant> readGrants(Map<String, Object> m, String k) {
        List<Object> l = Json.list(m, k);
        if (l == null) return null;
        List<AccessGrant> r = new ArrayList<AccessGrant>();
        for (Object o : l) if (o instanceof Map) r.add(readGrant((Map<String, Object>) o));
        return r;
    }

    public static void writeEvent(JsonWriter w, ScanEvent e) {
        w.beginObject().name("at").valueTime(e.at).name("code").value(e.code).name("volumeId").value(e.volumeId)
         .name("path").value(e.path).name("detail").value(e.detail).endObject();
    }

    static ScanEvent readEvent(Map<String, Object> m) {
        return new ScanEvent(Json.lng(m, "at", Unknown.TIME), Json.str(m, "code"), Json.str(m, "volumeId"), Json.str(m, "path"), Json.str(m, "detail"));
    }

    // ---------------------------------------------------------------- files

    public static final Codec<FileRecord> FILE = new Codec<FileRecord>() {
        public String table() { return "files"; }
        public void write(JsonWriter w, FileRecord r) {
            w.beginObject();
            w.name("fileId").value(r.fileId).name("volumeId").value(r.volumeId).name("path").value(r.path).name("uri").value(r.uri)
             .name("name").value(r.name).name("extension").value(r.extension).name("mimeType").value(r.mimeType)
             .name("sizeBytes").valueCount(r.sizeBytes).name("createdAt").valueTime(r.createdAt).name("modifiedAt").valueTime(r.modifiedAt)
             .name("lastAccessedAt").valueTime(r.lastAccessedAt).name("sha256").value(r.sha256);
            w.name("phash"); if (r.hasPhash) w.value(Hex.of16(r.phash)); else w.nullValue();
            w.name("embeddingRef").value(r.embeddingRef).name("category").value(r.category).name("extractionStatus").value(r.extractionStatus)
             .name("utility").value(r.utility).name("lossSeverity").value(r.lossSeverity).name("recoverability").value(r.recoverability)
             .name("confidence").value(r.confidence).name("protection").value(r.protection).name("scanVersion").valueCount(r.scanVersion)
             .name("inode").valueCount(r.inode).name("physicalKey").value(r.physicalKey).name("presence").value(r.presence)
             .name("presenceReason").value(r.presenceReason).name("change").value(r.change)
             .name("firstSeenScanId").valueCount(r.firstSeenScanId).name("changeScanId").valueCount(r.changeScanId)
             .name("removedScanId").valueCount(r.removedScanId).name("rawPath").value(r.rawPath)
             .name("readable").value(r.readable).name("writable").value(r.writable).name("accessMode").value(r.accessMode)
             .name("area").value(r.area).name("ownerPackage").value(r.ownerPackage).name("hidden").value(r.hidden)
             .name("type").value(r.type).name("typeSource").value(r.typeSource).name("extMismatch").value(r.extMismatch)
             .name("integrity").value(r.integrity).name("createdAtSource").value(r.createdAtSource)
             .name("extractionConfidence").value(r.extractionConfidence)
             .name("tiersDone").value(r.tiersDone).name("tiersFailed").value(r.tiersFailed).name("tiersPending").value(r.tiersPending)
             .name("duplicateGroupId").value(r.duplicateGroupId).name("lastError").value(r.lastError);
            w.endObject();
        }
        public FileRecord read(Map<String, Object> m) {
            FileRecord r = new FileRecord();
            r.fileId = Json.str(m, "fileId"); r.volumeId = Json.str(m, "volumeId"); r.path = Json.str(m, "path"); r.uri = Json.str(m, "uri");
            r.name = Json.str(m, "name"); r.extension = Json.str(m, "extension"); if (r.extension == null) r.extension = "";
            r.mimeType = Json.str(m, "mimeType");
            r.sizeBytes = Json.lng(m, "sizeBytes", Unknown.LONG); r.createdAt = Json.lng(m, "createdAt", Unknown.TIME);
            r.modifiedAt = Json.lng(m, "modifiedAt", Unknown.TIME); r.lastAccessedAt = Json.lng(m, "lastAccessedAt", Unknown.TIME);
            r.sha256 = Json.str(m, "sha256");
            String ph = Json.str(m, "phash"); if (ph != null) { r.hasPhash = true; r.phash = Hex.parse16(ph); }
            r.embeddingRef = Json.str(m, "embeddingRef");
            r.category = Json.str(m, "category"); if (r.category == null) r.category = "UNKNOWN";
            r.extractionStatus = Json.enumOf(m, "extractionStatus", ExtractionStatus.class, ExtractionStatus.NOT_STARTED);
            r.utility = Json.dbl(m, "utility"); r.lossSeverity = Json.dbl(m, "lossSeverity");
            r.recoverability = Json.enumOf(m, "recoverability", Recoverability.class, Recoverability.UNKNOWN);
            r.confidence = Json.dbl(m, "confidence");
            r.protection = Json.enumOf(m, "protection", Protection.class, Protection.NONE);
            r.scanVersion = Json.integer(m, "scanVersion", Unknown.INT);
            r.inode = Json.lng(m, "inode", Unknown.LONG); r.physicalKey = Json.str(m, "physicalKey");
            r.presence = Json.enumOf(m, "presence", Presence.class, Presence.UNVERIFIABLE);
            r.presenceReason = Json.str(m, "presenceReason");
            r.change = Json.enumOf(m, "change", ChangeState.class, null);
            r.firstSeenScanId = Json.lng(m, "firstSeenScanId", Unknown.LONG); r.changeScanId = Json.lng(m, "changeScanId", Unknown.LONG);
            r.removedScanId = Json.lng(m, "removedScanId", Unknown.LONG); r.rawPath = Json.str(m, "rawPath");
            r.readable = tri(m, "readable"); r.writable = tri(m, "writable");
            r.accessMode = Json.enumOf(m, "accessMode", AccessMode.class, null);
            r.area = Json.str(m, "area"); r.ownerPackage = Json.str(m, "ownerPackage"); r.hidden = Json.bool(m, "hidden", false);
            r.type = Json.str(m, "type"); if (r.type == null) r.type = "unknown";
            r.typeSource = Json.enumOf(m, "typeSource", TypeSource.class, TypeSource.NONE);
            r.extMismatch = Json.enumOf(m, "extMismatch", ExtMismatch.class, ExtMismatch.UNKNOWN);
            r.integrity = Json.enumOf(m, "integrity", Integrity.class, Integrity.UNKNOWN);
            r.createdAtSource = Json.enumOf(m, "createdAtSource", TimeSource.class, TimeSource.NONE);
            r.extractionConfidence = Json.dbl(m, "extractionConfidence");
            r.tiersDone = Json.integer(m, "tiersDone", 0); r.tiersFailed = Json.integer(m, "tiersFailed", 0);
            r.tiersPending = Json.integer(m, "tiersPending", 0);
            r.duplicateGroupId = Json.str(m, "duplicateGroupId"); r.lastError = Json.str(m, "lastError");
            return r;
        }
    };

    // ---------------------------------------------------------------- directories

    public static final Codec<DirRecord> DIR = new Codec<DirRecord>() {
        public String table() { return "directories"; }
        public void write(JsonWriter w, DirRecord r) {
            w.beginObject().name("volumeId").value(r.volumeId).name("path").value(r.path).name("rawPath").value(r.rawPath)
             .name("status").value(r.status).name("lastVisitedScanId").valueCount(r.lastVisitedScanId)
             .name("lastListedOkScanId").valueCount(r.lastListedOkScanId).name("mtime").valueTime(r.mtime).name("inode").valueCount(r.inode)
             .name("identity").value(r.identity).name("entries").valueCount(r.entries).name("entriesSkipped").valueCount(r.entriesSkipped)
             .name("error").value(r.error).endObject();
        }
        public DirRecord read(Map<String, Object> m) {
            DirRecord r = new DirRecord();
            r.volumeId = Json.str(m, "volumeId"); r.path = Json.str(m, "path"); r.rawPath = Json.str(m, "rawPath");
            r.status = Json.enumOf(m, "status", DirStatus.class, null);
            r.lastVisitedScanId = Json.lng(m, "lastVisitedScanId", Unknown.LONG); r.lastListedOkScanId = Json.lng(m, "lastListedOkScanId", Unknown.LONG);
            r.mtime = Json.lng(m, "mtime", Unknown.TIME); r.inode = Json.lng(m, "inode", Unknown.LONG); r.identity = Json.str(m, "identity");
            r.entries = Json.integer(m, "entries", Unknown.INT); r.entriesSkipped = Json.integer(m, "entriesSkipped", Unknown.INT);
            r.error = Json.str(m, "error");
            return r;
        }
    };

    // ---------------------------------------------------------------- volumes

    public static final Codec<Volume> VOLUME = new Codec<Volume>() {
        public String table() { return "storage_volumes"; }
        public void write(JsonWriter w, Volume v) {
            w.beginObject().name("volumeId").value(v.volumeId).name("kind").value(v.kind).name("label").value(v.label).name("root").value(v.root)
             .name("fsUuid").value(v.fsUuid).name("totalBytes").valueCount(v.totalBytes).name("freeBytes").valueCount(v.freeBytes)
             .name("mounted").value(v.mounted).name("fileKeyStable").value(v.fileKeyStable).name("caseInsensitive").value(v.caseInsensitive)
             .name("atimeReliable").value(v.atimeReliable).name("identityScheme").value(v.identityScheme)
             .name("restrictedPrefixes").strings(v.restrictedPrefixes);
            w.name("grants"); writeGrants(w, v.grants);
            w.name("firstSeenAt").valueTime(v.firstSeenAt).name("lastSeenAt").valueTime(v.lastSeenAt).name("lastFullScanId").valueCount(v.lastFullScanId);
            w.endObject();
        }
        public Volume read(Map<String, Object> m) {
            Volume v = new Volume();
            v.volumeId = Json.str(m, "volumeId"); v.kind = Json.enumOf(m, "kind", VolumeKind.class, VolumeKind.UNKNOWN);
            v.label = Json.str(m, "label"); v.root = Json.str(m, "root"); v.fsUuid = Json.str(m, "fsUuid");
            v.totalBytes = Json.lng(m, "totalBytes", Unknown.LONG); v.freeBytes = Json.lng(m, "freeBytes", Unknown.LONG);
            v.mounted = Json.bool(m, "mounted", false); v.fileKeyStable = tri(m, "fileKeyStable"); v.caseInsensitive = tri(m, "caseInsensitive");
            Tri at = tri(m, "atimeReliable"); v.atimeReliable = at == Tri.UNKNOWN ? Tri.FALSE : at;
            v.identityScheme = Json.str(m, "identityScheme");
            List<String> rp = Json.strings(m, "restrictedPrefixes"); v.restrictedPrefixes = rp == null ? new ArrayList<String>() : rp;
            List<AccessGrant> g = readGrants(m, "grants"); v.grants = g == null ? new ArrayList<AccessGrant>() : g;
            v.firstSeenAt = Json.lng(m, "firstSeenAt", Unknown.TIME); v.lastSeenAt = Json.lng(m, "lastSeenAt", Unknown.TIME);
            v.lastFullScanId = Json.lng(m, "lastFullScanId", Unknown.LONG);
            return v;
        }
    };

    // ---------------------------------------------------------------- scan sessions

    public static final Codec<ScanSession> SESSION = new Codec<ScanSession>() {
        public String table() { return "scan_sessions"; }
        public void write(JsonWriter w, ScanSession s) {
            w.beginObject().name("scanId").value(s.scanId).name("kind").value(s.kind).name("state").value(s.state)
             .name("startedAt").valueTime(s.startedAt).name("endedAt").valueTime(s.endedAt).name("resumedCount").value(s.resumedCount)
             .name("stage").value(s.stage).name("stagesDone").strings(s.stagesDone)
             .name("algorithmVersion").value(s.algorithmVersion).name("scanVersion").valueCount(s.scanVersion);
            w.name("checkpoint");
            if (s.checkpoint == null) w.nullValue();
            else w.beginObject().name("volumeQueue").strings(s.checkpoint.volumeQueue).name("frontier").strings(s.checkpoint.frontier)
                  .name("commits").value(s.checkpoint.commits).endObject();
            w.name("events").beginArray(); for (ScanEvent e : s.events) writeEvent(w, e); w.endArray();
            w.name("eventsDropped").value(s.eventsDropped);
            w.name("counters").beginObject(); for (Map.Entry<String, Long> e : s.counters.entrySet()) w.name(e.getKey()).value(e.getValue()); w.endObject();
            w.name("pauseReason").value(s.pauseReason).name("lastError").value(s.lastError);
            w.name("grantsAtStart"); writeGrants(w, s.grantsAtStart);
            w.endObject();
        }
        @SuppressWarnings("unchecked")
        public ScanSession read(Map<String, Object> m) {
            ScanSession s = new ScanSession();
            s.scanId = Json.lng(m, "scanId", Unknown.LONG); s.kind = Json.enumOf(m, "kind", ScanKind.class, null);
            s.state = Json.enumOf(m, "state", ScanState.class, null);
            s.startedAt = Json.lng(m, "startedAt", Unknown.TIME); s.endedAt = Json.lng(m, "endedAt", Unknown.TIME);
            s.resumedCount = Json.integer(m, "resumedCount", 0); s.stage = Json.str(m, "stage");
            List<String> sd = Json.strings(m, "stagesDone"); if (sd != null) s.stagesDone = sd;
            s.algorithmVersion = Json.str(m, "algorithmVersion"); s.scanVersion = Json.integer(m, "scanVersion", Unknown.INT);
            Map<String, Object> cp = Json.obj(m, "checkpoint");
            if (cp != null) {
                s.checkpoint = new ScanCheckpoint();
                List<String> q = Json.strings(cp, "volumeQueue"); if (q != null) s.checkpoint.volumeQueue = q;
                List<String> f = Json.strings(cp, "frontier"); if (f != null) s.checkpoint.frontier = f;
                s.checkpoint.commits = Json.integer(cp, "commits", 0);
            }
            List<Object> ev = Json.list(m, "events");
            if (ev != null) for (Object o : ev) if (o instanceof Map) s.events.add(readEvent((Map<String, Object>) o));
            s.eventsDropped = Json.integer(m, "eventsDropped", 0);
            Map<String, Object> c = Json.obj(m, "counters");
            if (c != null) { s.counters = new TreeMap<String, Long>(); for (Map.Entry<String, Object> e : c.entrySet()) if (e.getValue() instanceof Number) s.counters.put(e.getKey(), ((Number) e.getValue()).longValue()); }
            s.pauseReason = Json.str(m, "pauseReason"); s.lastError = Json.str(m, "lastError");
            s.grantsAtStart = readGrants(m, "grantsAtStart");
            return s;
        }
    };

    // ---------------------------------------------------------------- placeholders of later phases

    private abstract static class Simple<R extends Record> implements Codec<R> {
        private final String table; private final String[] fields;
        Simple(String table, String... fields) { this.table = table; this.fields = fields; }
        public String table() { return table; }
        abstract Object get(R r, int i);
        abstract R make(Object[] v);
        public void write(JsonWriter w, R r) {
            w.beginObject();
            for (int i = 0; i < fields.length; i++) { w.name(fields[i]); w.any(get(r, i)); }
            w.endObject();
        }
        public R read(Map<String, Object> m) {
            Object[] v = new Object[fields.length];
            for (int i = 0; i < fields.length; i++) v[i] = m.get(fields[i]);
            return make(v);
        }
    }
    static String s(Object o) { return o instanceof String ? (String) o : null; }

    public static final Codec<FeatureRecord> FEATURE = new Simple<FeatureRecord>("file_features", "fileId") {
        Object get(FeatureRecord r, int i) { return r.fileId; }
        FeatureRecord make(Object[] v) { FeatureRecord r = new FeatureRecord(); r.fileId = s(v[0]); return r; }
    };
    public static final Codec<HashRecord> HASH = new Simple<HashRecord>("file_hashes", "fileId") {
        Object get(HashRecord r, int i) { return r.fileId; }
        HashRecord make(Object[] v) { HashRecord r = new HashRecord(); r.fileId = s(v[0]); return r; }
    };
    public static final Codec<EmbeddingRecord> EMBEDDING = new Simple<EmbeddingRecord>("file_embeddings", "fileId", "modelId") {
        Object get(EmbeddingRecord r, int i) { return i == 0 ? r.fileId : r.modelId; }
        EmbeddingRecord make(Object[] v) { EmbeddingRecord r = new EmbeddingRecord(); r.fileId = s(v[0]); r.modelId = s(v[1]); return r; }
    };
    public static final Codec<Relationship> RELATIONSHIP = new Simple<Relationship>("file_relationships", "edgeId", "src", "dst") {
        Object get(Relationship r, int i) { return i == 0 ? r.edgeId : i == 1 ? r.src : r.dst; }
        Relationship make(Object[] v) { Relationship r = new Relationship(); r.edgeId = s(v[0]); r.src = s(v[1]); r.dst = s(v[2]); return r; }
    };
    public static final Codec<DuplicateGroup> DUPLICATE_GROUP = new Simple<DuplicateGroup>("duplicate_groups", "groupId") {
        Object get(DuplicateGroup r, int i) { return r.groupId; }
        DuplicateGroup make(Object[] v) { DuplicateGroup r = new DuplicateGroup(); r.groupId = s(v[0]); return r; }
    };
    public static final Codec<BackupRecord> BACKUP = new Simple<BackupRecord>("backup_records", "backupId") {
        Object get(BackupRecord r, int i) { return r.backupId; }
        BackupRecord make(Object[] v) { BackupRecord r = new BackupRecord(); r.backupId = s(v[0]); return r; }
    };
    public static final Codec<Recommendation> RECOMMENDATION = new Simple<Recommendation>("recommendations", "recommendationId", "fileId") {
        Object get(Recommendation r, int i) { return i == 0 ? r.recommendationId : r.fileId; }
        Recommendation make(Object[] v) { Recommendation r = new Recommendation(); r.recommendationId = s(v[0]); r.fileId = s(v[1]); return r; }
    };
    public static final Codec<ActionPlan> ACTION_PLAN = new Simple<ActionPlan>("action_plans", "planId") {
        Object get(ActionPlan r, int i) { return r.planId; }
        ActionPlan make(Object[] v) { ActionPlan r = new ActionPlan(); r.planId = s(v[0]); return r; }
    };
    public static final Codec<OperationEntry> OPERATION = new Simple<OperationEntry>("action_operations", "opId", "seq", "phase") {
        Object get(OperationEntry r, int i) { return i == 0 ? r.opId : i == 1 ? (Object) Long.valueOf(r.seq) : (r.phase == null ? null : r.phase.name()); }
        OperationEntry make(Object[] v) {
            OperationEntry r = new OperationEntry(); r.opId = s(v[0]); r.seq = v[1] instanceof Number ? ((Number) v[1]).intValue() : 0;
            String p = s(v[2]);
            try { r.phase = p == null ? null : OpPhase.valueOf(p); } catch (IllegalArgumentException e) { r.phase = null; }
            return r;
        }
    };
    public static final Codec<Feedback> FEEDBACK = new Simple<Feedback>("user_feedback", "feedbackId") {
        Object get(Feedback r, int i) { return r.feedbackId; }
        Feedback make(Object[] v) { Feedback r = new Feedback(); r.feedbackId = s(v[0]); return r; }
    };
    public static final Codec<Preference> PREFERENCE = new Simple<Preference>("user_preferences", "name", "value", "updatedAt") {
        Object get(Preference r, int i) { return i == 0 ? r.name : i == 1 ? r.value : (Unknown.knownTime(r.updatedAt) ? (Object) Long.valueOf(r.updatedAt) : null); }
        Preference make(Object[] v) {
            Preference r = new Preference(); r.name = s(v[0]); r.value = s(v[1]);
            r.updatedAt = v[2] instanceof Number ? ((Number) v[2]).longValue() : Unknown.TIME; return r;
        }
    };
    public static final Codec<ModelRecord> MODEL = new Simple<ModelRecord>("model_metadata", "modelId") {
        Object get(ModelRecord r, int i) { return r.modelId; }
        ModelRecord make(Object[] v) { ModelRecord r = new ModelRecord(); r.modelId = s(v[0]); return r; }
    };
}
