package com.storageplanner.v2test;

import com.storageplanner.v2.V2;
import com.storageplanner.v2.discovery.CoverageBuilder;
import com.storageplanner.v2.model.*;
import com.storageplanner.v2.store.FeatureCodec;
import com.storageplanner.v2.store.V2Store;
import com.storageplanner.v2.util.Hex;
import com.storageplanner.v2.util.JsonWriter;

import java.util.*;

/** The V2Cli result JSON (ARCHITECTURE 9), Phase-1 content; later sections are emitted empty or null. */
public final class V2Json {
    private V2Json() {}

    public static final int PHASE = 2;

    public static final class Options { public String kind = "full"; public Integer maxTier; public String hash = "collisions"; public String mode = "standard"; public Long targetBytes; }

    public static final class Timings { public long totalMs; public Map<String, Long> stages = new LinkedHashMap<String, Long>(); public double filesPerSecond; public long bytesRead; public long peakHeapBytes; }

    public static java.util.Map<String, Long> graphCoverage;

    public static void writeResult(JsonWriter w, V2Store store, String root, String volumeId, Options opt, ScanSession s,
                                   CoverageReport cov, long generatedAt, Timings t) {
        w.beginObject();
        w.name("schema").value("sp-v2-result").name("schemaVersion").value(1)
         .name("algorithmVersion").value(V2.ALGORITHM_VERSION).name("scanVersion").value(V2.SCAN_VERSION).name("phase").value(PHASE)
         .name("generatedAt").valueTime(generatedAt).name("root").value(root).name("volumeId").value(volumeId);
        w.name("options").beginObject().name("kind").value(opt.kind).name("maxTier");
        if (opt.maxTier == null) w.nullValue(); else w.value(opt.maxTier.longValue());
        w.name("hash").value(opt.hash).name("mode").value(opt.mode).name("targetBytes");
        if (opt.targetBytes == null) w.nullValue(); else w.value(opt.targetBytes.longValue());
        w.endObject();
        w.name("scan").beginObject().name("scanId").value(s.scanId).name("state").value(s.state).name("startedAt").valueTime(s.startedAt)
         .name("endedAt").valueTime(s.endedAt).name("resumedCount").value(s.resumedCount).name("stagesDone").strings(s.stagesDone).endObject();
        w.name("volumes").beginArray();
        for (Volume v : store.volumes().all()) {
            w.beginObject().name("volumeId").value(v.volumeId).name("kind").value(v.kind)
             .name("totalBytes").valueCount(v.kind == VolumeKind.DIRECTORY ? -1 : v.totalBytes)
             .name("freeBytes").valueCount(v.kind == VolumeKind.DIRECTORY ? -1 : v.freeBytes).name("modes").beginArray();
            TreeSet<AccessMode> modes = new TreeSet<AccessMode>();
            for (AccessGrant g : v.grants) if (g.active) modes.add(g.mode);
            for (AccessMode m : modes) w.value(m);
            w.endArray().name("identityScheme").value(v.identityScheme).endObject();
        }
        w.endArray();
        List<FileRecord> files = new ArrayList<FileRecord>();
        for (FileRecord f : store.files().all()) files.add(f);
        Collections.sort(files, new Comparator<FileRecord>() {
            public int compare(FileRecord a, FileRecord b) {
                int c = a.path.compareTo(b.path); if (c != 0) return c;
                c = a.volumeId.compareTo(b.volumeId); if (c != 0) return c;
                return a.fileId.compareTo(b.fileId);
            }
        });
        w.name("files").beginArray();
        for (FileRecord f : files) writeFile(w, f, s.scanId, store);
        w.endArray();
        w.name("relationships").beginArray();
        for (Relationship e : store.relationships().all()) com.storageplanner.v2.store.Codecs.RELATIONSHIP.write(w, e);
        w.endArray();
        w.name("duplicateGroups").beginArray();
        for (DuplicateGroup g : store.duplicateGroups().all()) com.storageplanner.v2.store.Codecs.DUPLICATE_GROUP.write(w, g);
        w.endArray();
        if (graphCoverage != null) {   // only when GRAPH ran (keeps the Phase 1-2 result layout otherwise)
            w.name("backups").beginArray();
            for (BackupRecord b : store.backups().all()) com.storageplanner.v2.store.Codecs.BACKUP.write(w, b);
            w.endArray();
            w.name("graphCoverage").any(graphCoverage);
        }
        w.name("recommendations"); AssessJson.recommendations(w);
        w.name("plan"); PlanJson.write(w);
        w.name("coverage");
        if (cov == null) w.nullValue(); else CoverageBuilder.write(w, cov);
        w.name("execution").nullValue();
        w.name("timings").beginObject().name("totalMs").value(t.totalMs).name("stages").any(t.stages)
         .name("filesPerSecond").value(t.filesPerSecond).name("bytesRead").value(t.bytesRead).name("peakHeapBytes").value(t.peakHeapBytes).endObject();
        w.endObject();
    }

    static void bits(JsonWriter w, int mask) { w.beginArray(); for (int k = 1; k <= 6; k++) if ((mask & (1 << k)) != 0) w.value(k); w.endArray(); }

    static void writeFile(JsonWriter w, FileRecord f, long scanId, V2Store store) {
        FeatureRecord fr = store.features().get(f.fileId);
        HashRecord hr = store.hashes().get(f.fileId);
        w.beginObject();
        w.name("fileId").value(f.fileId).name("path").value(f.path).name("name").value(f.name).name("ext").value(f.extension)
         .name("size").valueCount(f.sizeBytes).name("mtime").valueTime(f.modifiedAt).name("createdAt").valueTime(f.createdAt)
         .name("createdAtSource").value(f.createdAtSource).name("lastAccessedAt").valueTime(f.lastAccessedAt)
         .name("presence").value(f.presence).name("change").value(f.changeScanId == scanId ? f.change : null)
         .name("changeScanId").valueCount(f.changeScanId).name("presenceReason").value(f.presenceReason)
         .name("area").value(f.area).name("ownerPackage").value(f.ownerPackage).name("hidden").value(f.hidden)
         .name("type").value(f.type).name("typeSource").value(f.typeSource).name("mime").value(f.mimeType)
         .name("extMismatch").value(f.extMismatch).name("integrity").value(f.integrity)
         .name("quickHash").value(hr != null && hr.fp != null && hr.fp.sameAs(f.fingerprint()) ? hr.quickHash : null).name("sha256").value(f.sha256).name("phash");
        if (f.hasPhash) w.value(Hex.of16(f.phash)); else w.nullValue();
        w.name("extraction").beginObject().name("status").value(f.extractionStatus).name("tiersDone"); bits(w, f.tiersDone);
        w.name("tiersPending"); bits(w, f.tiersPending);
        w.name("tiersFailed"); bits(w, f.tiersFailed);
        w.name("confidence").value(f.extractionConfidence).name("error").value(f.lastError)
         .name("problems").strings(fr == null ? null : fr.problems).endObject();
        w.name("image"); FeatureCodec.writeImage(w, fr == null ? null : fr.image);
        w.name("exif"); FeatureCodec.writeExif(w, fr == null ? null : fr.exif);
        w.name("nameDate"); FeatureCodec.writeNameDate(w, fr == null ? null : fr.nameDate);
        w.name("video"); FeatureCodec.writeVideo(w, fr == null ? null : fr.video);
        w.name("audio"); FeatureCodec.writeAudio(w, fr == null ? null : fr.audio);
        w.name("apk"); FeatureCodec.writeApk(w, fr == null ? null : fr.apk);
        w.name("pdf"); FeatureCodec.writePdf(w, fr == null ? null : fr.pdf);
        w.name("archive"); FeatureCodec.writeArchive(w, fr == null ? null : fr.archive, 200);
        w.name("category").value(f.category).name("categoryConfidence").nullValue()
         .name("duplicateGroupId").value(f.duplicateGroupId).name("perceptualStatus").value(fr == null ? null : fr.extra.get("graph.perceptual"))
         .name("assessment"); AssessJson.assessment(w, f.fileId);
        w.endObject();
    }
}
