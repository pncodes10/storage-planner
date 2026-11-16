package com.storageplanner.v2test;

import com.storageplanner.v2.model.*;
import com.storageplanner.v2.store.Codecs;
import com.storageplanner.v2.store.ContentCodec;
import com.storageplanner.v2.store.FeatureCodec;
import com.storageplanner.v2.util.Json;
import com.storageplanner.v2.util.JsonWriter;

import java.io.StringWriter;
import java.util.*;

/** Phase 6 S1: ContentCodec and the FeatureRecord content fields (PHASE_6 4.5). */
public final class ContentCodecTest {
    interface W { void write(JsonWriter w); }
    static String json(W x) { StringWriter s = new StringWriter(); JsonWriter w = new JsonWriter(s); x.write(w); return s.toString(); }
    static Map<String, Object> parse(String s) throws Exception { return Json.parseObject(s); }

    static VisualInfo fullVisual() {
        VisualInfo x = new VisualInfo();
        x.decodeStatus = DecodeStatus.OK; x.analysedWidth = 1024; x.analysedHeight = 576; x.aspect = 1.777778;
        x.meanLuma = 0.25; x.darkFrac = 0.5; x.contrast = 0.125; x.colorful = 0.75; x.paper = 0.0625; x.flat = 0.375;
        x.textArea = 0.5; x.textHeight = 0.015625; x.bigText = 0.03125; x.textBlobs = 120; x.textLines = 9;
        x.qrStatus = DetectorStatus.OK; x.qrFormat = "QR_CODE"; x.qrKind = "UPI";
        x.faceStatus = DetectorStatus.FAILED; x.faceCount = 3; x.faceMaxScore = 0.9375; x.faceModelVersion = 2;
        return x;
    }
    static TextInfo fullText() {
        TextInfo x = new TextInfo();
        x.status = TextStatus.PARTIAL_SCRIPT; x.engine = "mlkit-latin"; x.engineVersion = 3; x.words = 40; x.goodWords = 30; x.garbage = 2;
        x.meanConf = 0.875; x.readRatio = 0.75; x.kind = "PAYMENT"; x.kindScore = 7; x.classes = new ArrayList<String>(Arrays.asList("PROTECT_PAY", "OTP"));
        x.idPatterns = 1; x.amounts = 2; x.digitRuns4 = 3; x.textDate = 1791000000000L; x.attempts = 2;
        return x;
    }
    static DocInfo fullDoc() {
        DocInfo x = new DocInfo();
        x.status = DocStatus.OK; x.format = "pdf"; x.pages = 4; x.createdAt = 1700000000000L; x.modifiedAt = 1710000000000L; x.chars = 5000; x.words = 800;
        x.latinShare = 0.875; x.devanagariShare = 0.125; x.category = "DOC_FINANCIAL"; x.categoryScore = 0.625;
        x.categoryEvidence = new ArrayList<String>(Arrays.asList("kw:statement", "id:IFSC")); x.idKinds = new ArrayList<String>(Arrays.asList("PAN", "IFSC"));
        x.dates = 5; x.amounts = 6; x.latestDate = 1791500000000L; x.minhash = new int[16]; for (int k = 0; k < 16; k++) x.minhash[k] = k * 1000003 - 7;
        x.unsupportedStreams = 1;
        return x;
    }
    static ActivityInfo fullActivity() {
        ActivityInfo x = new ActivityInfo();
        x.videoStatus = ActivityStatus.FRAGMENTED; x.durationMs = 61000; x.videoSamples = 1800; x.medianWindowBytes = 12345.5; x.peakThreshold = 30000.25;
        x.peakWindows = 3; x.irregularSync = 1; x.peaks = new long[]{1000, 4000, 9000, 12000};
        return x;
    }

    static void sameVisual(VisualInfo a, VisualInfo b) {
        T.eq("visual decodeStatus", a.decodeStatus, b.decodeStatus); T.eq("visual analysedWidth", a.analysedWidth, b.analysedWidth);
        T.eq("visual analysedHeight", a.analysedHeight, b.analysedHeight); T.eq("visual aspect", a.aspect, b.aspect);
        T.eq("visual meanLuma", a.meanLuma, b.meanLuma); T.eq("visual darkFrac", a.darkFrac, b.darkFrac); T.eq("visual contrast", a.contrast, b.contrast);
        T.eq("visual colorful", a.colorful, b.colorful); T.eq("visual paper", a.paper, b.paper); T.eq("visual flat", a.flat, b.flat);
        T.eq("visual textArea", a.textArea, b.textArea); T.eq("visual textHeight", a.textHeight, b.textHeight); T.eq("visual bigText", a.bigText, b.bigText);
        T.eq("visual textBlobs", a.textBlobs, b.textBlobs); T.eq("visual textLines", a.textLines, b.textLines);
        T.eq("visual qrStatus", a.qrStatus, b.qrStatus); T.eq("visual qrFormat", a.qrFormat, b.qrFormat); T.eq("visual qrKind", a.qrKind, b.qrKind);
        T.eq("visual faceStatus", a.faceStatus, b.faceStatus); T.eq("visual faceCount", a.faceCount, b.faceCount);
        T.eq("visual faceMaxScore", a.faceMaxScore, b.faceMaxScore); T.eq("visual faceModelVersion", a.faceModelVersion, b.faceModelVersion);
    }
    static void sameText(TextInfo a, TextInfo b) {
        T.eq("text status", a.status, b.status); T.eq("text engine", a.engine, b.engine); T.eq("text engineVersion", a.engineVersion, b.engineVersion);
        T.eq("text words", a.words, b.words); T.eq("text goodWords", a.goodWords, b.goodWords); T.eq("text garbage", a.garbage, b.garbage);
        T.eq("text meanConf", a.meanConf, b.meanConf); T.eq("text readRatio", a.readRatio, b.readRatio); T.eq("text kind", a.kind, b.kind);
        T.eq("text kindScore", a.kindScore, b.kindScore); T.eq("text classes", a.classes, b.classes); T.eq("text idPatterns", a.idPatterns, b.idPatterns);
        T.eq("text amounts", a.amounts, b.amounts); T.eq("text digitRuns4", a.digitRuns4, b.digitRuns4); T.eq("text textDate", a.textDate, b.textDate);
        T.eq("text attempts", a.attempts, b.attempts);
    }
    static void sameDoc(DocInfo a, DocInfo b) {
        T.eq("doc status", a.status, b.status); T.eq("doc format", a.format, b.format); T.eq("doc pages", a.pages, b.pages);
        T.eq("doc createdAt", a.createdAt, b.createdAt); T.eq("doc modifiedAt", a.modifiedAt, b.modifiedAt); T.eq("doc chars", a.chars, b.chars);
        T.eq("doc words", a.words, b.words); T.eq("doc latinShare", a.latinShare, b.latinShare); T.eq("doc devanagariShare", a.devanagariShare, b.devanagariShare);
        T.eq("doc category", a.category, b.category); T.eq("doc categoryScore", a.categoryScore, b.categoryScore);
        T.eq("doc categoryEvidence", a.categoryEvidence, b.categoryEvidence); T.eq("doc idKinds", a.idKinds, b.idKinds);
        T.eq("doc dates", a.dates, b.dates); T.eq("doc amounts", a.amounts, b.amounts); T.eq("doc latestDate", a.latestDate, b.latestDate);
        T.check("doc minhash", Arrays.equals(a.minhash, b.minhash)); T.eq("doc unsupportedStreams", a.unsupportedStreams, b.unsupportedStreams);
    }
    static void sameActivity(ActivityInfo a, ActivityInfo b) {
        T.eq("activity videoStatus", a.videoStatus, b.videoStatus); T.eq("activity durationMs", a.durationMs, b.durationMs);
        T.eq("activity videoSamples", a.videoSamples, b.videoSamples); T.eq("activity median", a.medianWindowBytes, b.medianWindowBytes);
        T.eq("activity threshold", a.peakThreshold, b.peakThreshold); T.eq("activity peakWindows", a.peakWindows, b.peakWindows);
        T.eq("activity irregularSync", a.irregularSync, b.irregularSync); T.check("activity peaks", Arrays.equals(a.peaks, b.peaks));
    }

    @SuppressWarnings("unchecked")
    public static int run(String[] args) throws Exception {
        T.section("ContentCodec");
        // round trip, each sub-record on its own
        final VisualInfo v = fullVisual(); final TextInfo t = fullText(); final DocInfo d = fullDoc(); final ActivityInfo a = fullActivity();
        sameVisual(v, ContentCodec.readVisual(parse(json(new W() { public void write(JsonWriter w) { ContentCodec.write(w, v); } }))));
        sameText(t, ContentCodec.readText(parse(json(new W() { public void write(JsonWriter w) { ContentCodec.write(w, t); } }))));
        sameDoc(d, ContentCodec.readDoc(parse(json(new W() { public void write(JsonWriter w) { ContentCodec.write(w, d); } }))));
        sameActivity(a, ContentCodec.readActivity(parse(json(new W() { public void write(JsonWriter w) { ContentCodec.write(w, a); } }))));

        // round trip through the whole FeatureRecord codec
        FeatureRecord fr = new FeatureRecord(); fr.fileId = "f1"; fr.fp = new Fingerprint(10, 20, 30);
        fr.visual = v; fr.text = t; fr.doc = d; fr.activity = a;
        for (int k = Extractors_VISUAL; k <= Extractors_ACTIVITY; k++) fr.versions[k] = 1;
        String line = Codecs.json(FeatureCodec.FEATURE, fr);
        FeatureRecord back = FeatureCodec.FEATURE.read(parse(line));
        sameVisual(v, back.visual); sameText(t, back.text); sameDoc(d, back.doc); sameActivity(a, back.activity);
        T.eq("versions[20..23] kept", 1, back.versions[20] + back.versions[21] + back.versions[22] + back.versions[23] - 3);

        // unknown omitted
        T.eq("fresh VisualInfo -> {}", "{}", json(new W() { public void write(JsonWriter w) { ContentCodec.write(w, new VisualInfo()); } }));
        T.eq("fresh TextInfo -> {}", "{}", json(new W() { public void write(JsonWriter w) { ContentCodec.write(w, new TextInfo()); } }));
        T.eq("fresh DocInfo -> {}", "{}", json(new W() { public void write(JsonWriter w) { ContentCodec.write(w, new DocInfo()); } }));
        T.eq("fresh ActivityInfo -> {}", "{}", json(new W() { public void write(JsonWriter w) { ContentCodec.write(w, new ActivityInfo()); } }));
        VisualInfo e = ContentCodec.readVisual(parse("{}"));
        T.check("empty read: NaN aspect", Double.isNaN(e.aspect)); T.eq("empty read: -1 blobs", -1, e.textBlobs);
        T.eq("empty read: qr NOT_RUN", DetectorStatus.NOT_RUN, e.qrStatus); T.eq("empty read: decodeStatus null", null, e.decodeStatus);
        T.eq("empty read: faceStatus NOT_RUN", DetectorStatus.NOT_RUN, e.faceStatus);
        DocInfo ed = ContentCodec.readDoc(parse("{}"));
        T.eq("empty doc: category UNKNOWN", "UNKNOWN", ed.category); T.eq("empty doc: minhash null", null, ed.minhash); T.eq("empty doc: latestDate unknown", Unknown.TIME, ed.latestDate);
        TextInfo et = ContentCodec.readText(parse("{}"));
        T.eq("empty text: classes null (unknown)", null, et.classes); T.eq("empty text: NOT_RUN", TextStatus.NOT_RUN, et.status); T.eq("empty text: attempts 0", 0, et.attempts);
        // empty list stays distinct from unknown
        final TextInfo none = new TextInfo(); none.classes = new ArrayList<String>();
        T.eq("classes [] survives", new ArrayList<String>(), ContentCodec.readText(parse(json(new W() { public void write(JsonWriter w) { ContentCodec.write(w, none); } }))).classes);
        // a record without content writes no content keys
        FeatureRecord plain = new FeatureRecord(); plain.fileId = "f2";
        String pl = Codecs.json(FeatureCodec.FEATURE, plain);
        T.check("no content keys without content", !pl.contains("\"visual\"") && !pl.contains("\"text\"") && !pl.contains("\"doc\"") && !pl.contains("\"activity\""));
        FeatureRecord pb = FeatureCodec.FEATURE.read(parse(pl));
        T.check("no content read back", pb.visual == null && pb.text == null && pb.doc == null && pb.activity == null);

        // old snapshot: 20 versions
        Map<String, Object> old = parse(pl);
        List<Object> vs = new ArrayList<Object>(); for (int k = 0; k < 20; k++) vs.add(Long.valueOf(1));
        old.put("versions", vs);
        FeatureRecord ob = FeatureCodec.FEATURE.read(old);
        T.eq("old snapshot reads: versions length 24", 24, ob.versions.length);
        T.eq("old snapshot: versions[19] 1", 1, ob.versions[19]);
        T.eq("old snapshot: versions[20..23] 0", 0, ob.versions[20] + ob.versions[21] + ob.versions[22] + ob.versions[23]);
        // and a future 30-element array does not overflow
        for (int k = 20; k < 30; k++) vs.add(Long.valueOf(5));
        T.eq("longer versions array tolerated", 24, FeatureCodec.FEATURE.read(old).versions.length);

        // bad enums
        T.eq("bad enum text.status -> NOT_RUN", TextStatus.NOT_RUN, ContentCodec.readText(parse("{\"status\":\"BOGUS\"}")).status);
        T.eq("bad enum doc.status -> NOT_RUN", DocStatus.NOT_RUN, ContentCodec.readDoc(parse("{\"status\":\"BOGUS\"}")).status);
        T.eq("bad enum activity -> NOT_RUN", ActivityStatus.NOT_RUN, ContentCodec.readActivity(parse("{\"videoStatus\":\"BOGUS\"}")).videoStatus);
        T.eq("bad enum qrStatus -> NOT_RUN", DetectorStatus.NOT_RUN, ContentCodec.readVisual(parse("{\"qrStatus\":\"BOGUS\"}")).qrStatus);
        T.eq("bad enum decodeStatus -> null", null, ContentCodec.readVisual(parse("{\"decodeStatus\":\"BOGUS\"}")).decodeStatus);
        FeatureRecord bf = FeatureCodec.FEATURE.read(parse("{\"fileId\":\"x\",\"text\":{\"status\":\"BOGUS\"}}"));
        T.eq("bad enum through FeatureCodec", TextStatus.NOT_RUN, bf.text.status);
        T.eq("unknown keys ignored", DocStatus.OK, ContentCodec.readDoc(parse("{\"status\":\"OK\",\"future\":[1,2]}")).status);

        // no raw text
        String dj = json(new W() { public void write(JsonWriter w) { ContentCodec.write(w, d); } });
        String tj = json(new W() { public void write(JsonWriter w) { ContentCodec.write(w, t); } });
        Map<String, Object> dm = parse(dj), tm = parse(tj);
        for (String k : new String[]{"title", "author", "text"}) T.check("doc has no key " + k, !dm.containsKey(k) && !tm.containsKey(k));
        T.check("doc words is a number", dm.get("words") instanceof Number); T.check("text words is a number", tm.get("words") instanceof Number);
        boolean strList = false;
        for (Object o : dm.values()) if (o instanceof String && ((String) o).length() > 40) strList = true;
        for (Object o : tm.values()) if (o instanceof String && ((String) o).length() > 40) strList = true;
        T.check("no long string values", !strList);

        // copy independence
        FeatureRecord orig = new FeatureRecord(); orig.fileId = "c"; orig.visual = fullVisual(); orig.text = fullText(); orig.doc = fullDoc(); orig.activity = fullActivity();
        FeatureRecord cp = orig.copy();
        cp.visual.faceCount = 99; cp.text.words = 99; cp.doc.pages = 99; cp.activity.peakWindows = 99;
        T.eq("copy: original faceCount unchanged", 3, orig.visual.faceCount); T.eq("copy: original words unchanged", 40, orig.text.words);
        T.eq("copy: original pages unchanged", 4, orig.doc.pages); T.eq("copy: original peakWindows unchanged", 3, orig.activity.peakWindows);
        T.check("copy: sub-records are new objects", cp.visual != orig.visual && cp.text != orig.text && cp.doc != orig.doc && cp.activity != orig.activity);
        T.eq("copy: versions length", 24, cp.versions.length);

        // JSON CLI: V2Json file object
        try (Fx fx = Fx.create()) {
            fx.maxTier = 1;
            fx.tree.file("a.txt", "hello".getBytes("UTF-8")); fx.tree.file("b.txt", "world".getBytes("UTF-8"));
            fx.scan();
            FeatureRecord r = fx.store.features().get(fx.f("a.txt").fileId).copy();
            r.text = fullText();
            fx.store.features().put(r);
            String ja = fileJson(fx, "a.txt"), jb = fileJson(fx, "b.txt");
            Map<String, Object> ma = parse(ja), mb = parse(jb);
            Map<String, Object> content = Json.obj(ma, "content");
            T.check("content object present", content != null);
            T.eq("content.text.status", "PARTIAL_SCRIPT", Json.str(Json.obj(content, "text"), "status"));
            T.check("content has only text", content != null && content.size() == 1);
            T.check("words is a number, no string list", Json.obj(content, "text").get("words") instanceof Number);
            T.check("file without content has no content key", !mb.containsKey("content"));
        }
        return T.finish("ContentCodecTest");
    }

    static final int Extractors_VISUAL = com.storageplanner.v2.features.Extractors.VISUAL, Extractors_ACTIVITY = com.storageplanner.v2.features.Extractors.ACTIVITY;

    static String fileJson(final Fx fx, final String path) {
        return json(new W() { public void write(JsonWriter w) { V2Json.writeFile(w, fx.f(path), 1, fx.store); } });
    }
}
