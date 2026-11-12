package com.storageplanner.v2.store;

import com.storageplanner.v2.model.*;
import com.storageplanner.v2.util.Json;
import com.storageplanner.v2.util.JsonWriter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Codecs of the Phase 3 tables: relationships, duplicate groups, backup records, pair checks (PHASE_3 2). */
public final class GraphCodecs {
    private GraphCodecs() {}

    static long l(Map<String, Object> m, String k, long u) { return Json.lng(m, k, u); }

    public static final Codecs.Codec<Relationship> RELATIONSHIP = new Codecs.Codec<Relationship>() {
        public String table() { return "file_relationships"; }
        public void write(JsonWriter w, Relationship r) {
            w.beginObject().name("edgeId").value(r.edgeId).name("src").value(r.src).name("dst").value(r.dst).name("type").value(r.type)
             .name("confidence").value(r.confidence).name("verification").value(r.verification).name("evidence").beginArray();
            if (r.evidence != null) for (Evidence e : r.evidence)
                w.beginObject().name("code").value(e.code).name("value").value(e.value).name("structural").value(e.structural).endObject();
            w.endArray().name("srcFp"); FeatureCodec.writeFp(w, r.srcFp);
            w.name("dstFp"); FeatureCodec.writeFp(w, r.dstFp);
            w.name("createdScanId").valueCount(r.createdScanId).name("verifiedAt").valueTime(r.verifiedAt).endObject();
        }
        public Relationship read(Map<String, Object> m) {
            Relationship r = new Relationship();
            r.edgeId = Json.str(m, "edgeId"); r.src = Json.str(m, "src"); r.dst = Json.str(m, "dst");
            r.type = Json.enumOf(m, "type", RelType.class, null); r.confidence = Json.dbl(m, "confidence");
            r.verification = Json.enumOf(m, "verification", Verification.class, Verification.UNVERIFIED);
            List<Object> ev = Json.list(m, "evidence");
            if (ev != null) for (Object o : ev) if (o instanceof Map) {
                @SuppressWarnings("unchecked") Map<String, Object> e = (Map<String, Object>) o;
                r.evidence.add(new Evidence(Json.str(e, "code"), Json.str(e, "value"), Json.bool(e, "structural", false)));
            }
            r.srcFp = FeatureCodec.readFp(Json.obj(m, "srcFp")); r.dstFp = FeatureCodec.readFp(Json.obj(m, "dstFp"));
            r.createdScanId = l(m, "createdScanId", Unknown.LONG); r.verifiedAt = l(m, "verifiedAt", Unknown.TIME);
            return r;
        }
    };

    public static final Codecs.Codec<DuplicateGroup> DUPLICATE_GROUP = new Codecs.Codec<DuplicateGroup>() {
        public String table() { return "duplicate_groups"; }
        public void write(JsonWriter w, DuplicateGroup g) {
            w.beginObject().name("groupId").value(g.groupId).name("kind").value(g.kind).name("exact").value(g.exact)
             .name("members").strings(g.members).name("keeperId").value(g.keeperId).name("keeperReasons").strings(g.keeperReasons)
             .name("keeperFallback").value(g.keeperFallback).name("memberBytes").valueCount(g.memberBytes)
             .name("recoverableBytes").valueCount(g.recoverableBytes).name("verification").value(g.verification)
             .name("createdScanId").valueCount(g.createdScanId).endObject();
        }
        public DuplicateGroup read(Map<String, Object> m) {
            DuplicateGroup g = new DuplicateGroup();
            g.groupId = Json.str(m, "groupId"); g.kind = Json.enumOf(m, "kind", NearGroupKind.class, null); g.exact = Json.bool(m, "exact", false);
            List<String> mem = Json.strings(m, "members"); g.members = mem == null ? new ArrayList<String>() : mem;
            g.keeperId = Json.str(m, "keeperId");
            List<String> kr = Json.strings(m, "keeperReasons"); g.keeperReasons = kr == null ? new ArrayList<String>() : kr;
            g.keeperFallback = Json.bool(m, "keeperFallback", false); g.memberBytes = l(m, "memberBytes", Unknown.LONG);
            g.recoverableBytes = l(m, "recoverableBytes", Unknown.LONG);
            g.verification = Json.enumOf(m, "verification", Verification.class, Verification.UNVERIFIED);
            g.createdScanId = l(m, "createdScanId", Unknown.LONG);
            return g;
        }
    };

    public static final Codecs.Codec<BackupRecord> BACKUP = new Codecs.Codec<BackupRecord>() {
        public String table() { return "backup_records"; }
        public void write(JsonWriter w, BackupRecord b) {
            w.beginObject().name("backupId").value(b.backupId).name("subjectId").value(b.subjectId).name("copyRef").value(b.copyRef)
             .name("kind").value(b.kind).name("level").value(b.level).name("state").value(b.state).name("note").value(b.note)
             .name("verifiedAt").valueTime(b.verifiedAt).name("subjectFp"); FeatureCodec.writeFp(w, b.subjectFp);
            w.name("copyFp"); FeatureCodec.writeFp(w, b.copyFp);
            w.endObject();
        }
        public BackupRecord read(Map<String, Object> m) {
            BackupRecord b = new BackupRecord();
            b.backupId = Json.str(m, "backupId"); b.subjectId = Json.str(m, "subjectId"); b.copyRef = Json.str(m, "copyRef");
            b.kind = Json.enumOf(m, "kind", BackupKind.class, BackupKind.ARCHIVE_ENTRY);
            b.level = Json.enumOf(m, "level", BackupLevel.class, BackupLevel.NONE);
            b.state = Json.enumOf(m, "state", BackupState.class, BackupState.UNKNOWN); b.note = Json.str(m, "note");
            b.verifiedAt = l(m, "verifiedAt", Unknown.TIME);
            b.subjectFp = FeatureCodec.readFp(Json.obj(m, "subjectFp")); b.copyFp = FeatureCodec.readFp(Json.obj(m, "copyFp"));
            return b;
        }
    };

    public static void writeMetrics(JsonWriter w, PairMetrics x) {
        if (x == null) { w.nullValue(); return; }
        w.beginObject().name("ssim64Mean").value(x.ssim64Mean).name("ssim64Min").value(x.ssim64Min).name("diff64Max").value(x.diff64Max)
         .name("ssimFineMin").value(x.ssimFineMin).name("diffFineMax").value(x.diffFineMax).name("chroma16").value(x.chroma16)
         .name("fracBlocksSame").value(x.fracBlocksSame).name("gridW").value(x.gridW).name("gridH").value(x.gridH)
         .name("fineN").value(x.fineN).name("band").value(x.band).endObject();
    }
    static PairMetrics readMetrics(Map<String, Object> m) {
        if (m == null) return null;
        PairMetrics x = new PairMetrics();
        x.ssim64Mean = Json.dbl(m, "ssim64Mean"); x.ssim64Min = Json.dbl(m, "ssim64Min"); x.diff64Max = Json.dbl(m, "diff64Max");
        x.ssimFineMin = Json.dbl(m, "ssimFineMin"); x.diffFineMax = Json.dbl(m, "diffFineMax"); x.chroma16 = Json.dbl(m, "chroma16");
        x.fracBlocksSame = Json.dbl(m, "fracBlocksSame"); x.gridW = Json.integer(m, "gridW", 0); x.gridH = Json.integer(m, "gridH", 0);
        x.fineN = Json.integer(m, "fineN", 0); x.band = Json.integer(m, "band", 0);
        return x;
    }

    public static final Codecs.Codec<PairCheck> PAIR_CHECK = new Codecs.Codec<PairCheck>() {
        public String table() { return "pair_checks"; }
        public void write(JsonWriter w, PairCheck p) {
            w.beginObject().name("pairKey").value(p.pairKey).name("a").value(p.a).name("b").value(p.b).name("fpA"); FeatureCodec.writeFp(w, p.fpA);
            w.name("fpB"); FeatureCodec.writeFp(w, p.fpB);
            w.name("matcherVersion").value(p.matcherVersion).name("sources").value(p.sources).name("hamming").value(p.hamming)
             .name("metrics"); writeMetrics(w, p.metrics);
            w.name("verdict").value(p.verdict).name("transform").value(p.transform).name("note").value(p.note)
             .name("checkedScanId").valueCount(p.checkedScanId).endObject();
        }
        public PairCheck read(Map<String, Object> m) {
            PairCheck p = new PairCheck();
            p.pairKey = Json.str(m, "pairKey"); p.a = Json.str(m, "a"); p.b = Json.str(m, "b");
            p.fpA = FeatureCodec.readFp(Json.obj(m, "fpA")); p.fpB = FeatureCodec.readFp(Json.obj(m, "fpB"));
            p.matcherVersion = Json.integer(m, "matcherVersion", 0); p.sources = Json.integer(m, "sources", 0);
            p.hamming = Json.integer(m, "hamming", -1); p.metrics = readMetrics(Json.obj(m, "metrics"));
            p.verdict = Json.enumOf(m, "verdict", PairVerdict.class, null); p.transform = Json.str(m, "transform"); p.note = Json.str(m, "note");
            p.checkedScanId = l(m, "checkedScanId", Unknown.LONG);
            return p;
        }
    };
}
