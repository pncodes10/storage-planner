package com.storageplanner.v2.store;

import com.storageplanner.v2.model.*;
import com.storageplanner.v2.util.Json;
import com.storageplanner.v2.util.JsonWriter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * JSON of the Phase 6 content sub-records (PHASE_6 4.4). Every field whose value is its unknown value is omitted, a missing key
 * reads as unknown, an unknown enum name reads as the enum's NOT_RUN (null for DecodeStatus). Nothing here ever holds raw text.
 */
public final class ContentCodec {
    private ContentCodec() {}

    private static void num(JsonWriter w, String k, double v) { if (!Double.isNaN(v)) w.name(k).value(v); }
    private static void cnt(JsonWriter w, String k, long v) { if (v >= 0) w.name(k).value(v); }
    private static void time(JsonWriter w, String k, long v) { if (Unknown.knownTime(v)) w.name(k).value(v); }
    private static void str(JsonWriter w, String k, String v) { if (v != null) w.name(k).value(v); }
    private static void strs(JsonWriter w, String k, List<String> v) { if (v != null) w.name(k).strings(v); }

    public static void write(JsonWriter w, VisualInfo x) {
        w.beginObject();
        if (x.decodeStatus != null) w.name("decodeStatus").value(x.decodeStatus);
        cnt(w, "analysedWidth", x.analysedWidth); cnt(w, "analysedHeight", x.analysedHeight); num(w, "aspect", x.aspect);
        num(w, "meanLuma", x.meanLuma); num(w, "darkFrac", x.darkFrac); num(w, "contrast", x.contrast); num(w, "colorful", x.colorful);
        num(w, "paper", x.paper); num(w, "flat", x.flat); num(w, "textArea", x.textArea); num(w, "textHeight", x.textHeight);
        num(w, "bigText", x.bigText); cnt(w, "textBlobs", x.textBlobs); cnt(w, "textLines", x.textLines);
        if (x.qrStatus != DetectorStatus.NOT_RUN) w.name("qrStatus").value(x.qrStatus);
        str(w, "qrFormat", x.qrFormat); str(w, "qrKind", x.qrKind);
        if (x.faceStatus != DetectorStatus.NOT_RUN) w.name("faceStatus").value(x.faceStatus);
        cnt(w, "faceCount", x.faceCount); num(w, "faceMaxScore", x.faceMaxScore); cnt(w, "faceModelVersion", x.faceModelVersion);
        w.endObject();
    }
    public static VisualInfo readVisual(Map<String, Object> m) {
        if (m == null) return null;
        VisualInfo x = new VisualInfo();
        x.decodeStatus = Json.enumOf(m, "decodeStatus", DecodeStatus.class, null);
        x.analysedWidth = Json.integer(m, "analysedWidth", -1); x.analysedHeight = Json.integer(m, "analysedHeight", -1);
        x.aspect = Json.dbl(m, "aspect"); x.meanLuma = Json.dbl(m, "meanLuma"); x.darkFrac = Json.dbl(m, "darkFrac");
        x.contrast = Json.dbl(m, "contrast"); x.colorful = Json.dbl(m, "colorful"); x.paper = Json.dbl(m, "paper"); x.flat = Json.dbl(m, "flat");
        x.textArea = Json.dbl(m, "textArea"); x.textHeight = Json.dbl(m, "textHeight"); x.bigText = Json.dbl(m, "bigText");
        x.textBlobs = Json.integer(m, "textBlobs", -1); x.textLines = Json.integer(m, "textLines", -1);
        x.qrStatus = Json.enumOf(m, "qrStatus", DetectorStatus.class, DetectorStatus.NOT_RUN);
        x.qrFormat = Json.str(m, "qrFormat"); x.qrKind = Json.str(m, "qrKind");
        x.faceStatus = Json.enumOf(m, "faceStatus", DetectorStatus.class, DetectorStatus.NOT_RUN);
        x.faceCount = Json.integer(m, "faceCount", -1); x.faceMaxScore = Json.dbl(m, "faceMaxScore");
        x.faceModelVersion = Json.integer(m, "faceModelVersion", -1);
        return x;
    }

    public static void write(JsonWriter w, TextInfo x) {
        w.beginObject();
        if (x.status != TextStatus.NOT_RUN) w.name("status").value(x.status);
        str(w, "engine", x.engine); cnt(w, "engineVersion", x.engineVersion);
        cnt(w, "words", x.words); cnt(w, "goodWords", x.goodWords); cnt(w, "garbage", x.garbage);
        num(w, "meanConf", x.meanConf); num(w, "readRatio", x.readRatio);
        str(w, "kind", x.kind); cnt(w, "kindScore", x.kindScore); strs(w, "classes", x.classes);
        cnt(w, "idPatterns", x.idPatterns); cnt(w, "amounts", x.amounts); cnt(w, "digitRuns4", x.digitRuns4);
        time(w, "textDate", x.textDate);
        if (x.attempts != 0) w.name("attempts").value(x.attempts);
        w.endObject();
    }
    public static TextInfo readText(Map<String, Object> m) {
        if (m == null) return null;
        TextInfo x = new TextInfo();
        x.status = Json.enumOf(m, "status", TextStatus.class, TextStatus.NOT_RUN);
        x.engine = Json.str(m, "engine"); x.engineVersion = Json.integer(m, "engineVersion", -1);
        x.words = Json.integer(m, "words", -1); x.goodWords = Json.integer(m, "goodWords", -1); x.garbage = Json.integer(m, "garbage", -1);
        x.meanConf = Json.dbl(m, "meanConf"); x.readRatio = Json.dbl(m, "readRatio");
        x.kind = Json.str(m, "kind"); x.kindScore = Json.integer(m, "kindScore", -1); x.classes = Json.strings(m, "classes");
        x.idPatterns = Json.integer(m, "idPatterns", -1); x.amounts = Json.integer(m, "amounts", -1);
        x.digitRuns4 = Json.integer(m, "digitRuns4", -1);
        x.textDate = Json.lng(m, "textDate", Unknown.TIME); x.attempts = Json.integer(m, "attempts", 0);
        return x;
    }

    public static void write(JsonWriter w, DocInfo x) {
        w.beginObject();
        if (x.status != DocStatus.NOT_RUN) w.name("status").value(x.status);
        str(w, "format", x.format); cnt(w, "pages", x.pages); time(w, "createdAt", x.createdAt); time(w, "modifiedAt", x.modifiedAt);
        cnt(w, "chars", x.chars); cnt(w, "words", x.words); num(w, "latinShare", x.latinShare); num(w, "devanagariShare", x.devanagariShare);
        if (x.category != null && !x.category.equals("UNKNOWN")) w.name("category").value(x.category);
        num(w, "categoryScore", x.categoryScore); strs(w, "categoryEvidence", x.categoryEvidence); strs(w, "idKinds", x.idKinds);
        cnt(w, "dates", x.dates); cnt(w, "amounts", x.amounts); time(w, "latestDate", x.latestDate);
        if (x.minhash != null) { w.name("minhash").beginArray(); for (int v : x.minhash) w.value(v); w.endArray(); }
        cnt(w, "unsupportedStreams", x.unsupportedStreams);
        w.endObject();
    }
    public static DocInfo readDoc(Map<String, Object> m) {
        if (m == null) return null;
        DocInfo x = new DocInfo();
        x.status = Json.enumOf(m, "status", DocStatus.class, DocStatus.NOT_RUN);
        x.format = Json.str(m, "format"); x.pages = Json.integer(m, "pages", -1);
        x.createdAt = Json.lng(m, "createdAt", Unknown.TIME); x.modifiedAt = Json.lng(m, "modifiedAt", Unknown.TIME);
        x.chars = Json.integer(m, "chars", -1); x.words = Json.integer(m, "words", -1);
        x.latinShare = Json.dbl(m, "latinShare"); x.devanagariShare = Json.dbl(m, "devanagariShare");
        String c = Json.str(m, "category"); x.category = c == null ? "UNKNOWN" : c;
        x.categoryScore = Json.dbl(m, "categoryScore"); x.categoryEvidence = Json.strings(m, "categoryEvidence"); x.idKinds = Json.strings(m, "idKinds");
        x.dates = Json.integer(m, "dates", -1); x.amounts = Json.integer(m, "amounts", -1);
        x.latestDate = Json.lng(m, "latestDate", Unknown.TIME);
        List<Object> h = Json.list(m, "minhash");
        if (h != null) {
            int[] a = new int[h.size()];
            for (int k = 0; k < a.length; k++) a[k] = h.get(k) instanceof Number ? ((Number) h.get(k)).intValue() : 0;
            x.minhash = a;
        }
        x.unsupportedStreams = Json.integer(m, "unsupportedStreams", -1);
        return x;
    }

    public static void write(JsonWriter w, ActivityInfo x) {
        w.beginObject();
        if (x.videoStatus != ActivityStatus.NOT_RUN) w.name("videoStatus").value(x.videoStatus);
        cnt(w, "durationMs", x.durationMs); cnt(w, "videoSamples", x.videoSamples);
        num(w, "medianWindowBytes", x.medianWindowBytes); num(w, "peakThreshold", x.peakThreshold);
        cnt(w, "peakWindows", x.peakWindows); cnt(w, "irregularSync", x.irregularSync);
        if (x.peaks != null) { w.name("peaks").beginArray(); for (long v : x.peaks) w.value(v); w.endArray(); }
        w.endObject();
    }
    public static ActivityInfo readActivity(Map<String, Object> m) {
        if (m == null) return null;
        ActivityInfo x = new ActivityInfo();
        x.videoStatus = Json.enumOf(m, "videoStatus", ActivityStatus.class, ActivityStatus.NOT_RUN);
        x.durationMs = Json.lng(m, "durationMs", -1); x.videoSamples = Json.integer(m, "videoSamples", -1);
        x.medianWindowBytes = Json.dbl(m, "medianWindowBytes"); x.peakThreshold = Json.dbl(m, "peakThreshold");
        x.peakWindows = Json.integer(m, "peakWindows", -1); x.irregularSync = Json.integer(m, "irregularSync", -1);
        List<Object> p = Json.list(m, "peaks");
        if (p != null) {
            long[] a = new long[p.size()];
            for (int k = 0; k < a.length; k++) a[k] = p.get(k) instanceof Number ? ((Number) p.get(k)).longValue() : 0L;
            x.peaks = a;
        }
        return x;
    }

    /** Writes the four optional sub-records of a FeatureRecord under their keys; absent ones are omitted (A21). */
    static void writeAll(JsonWriter w, FeatureRecord r) {
        if (r.visual != null) { w.name("visual"); write(w, r.visual); }
        if (r.text != null) { w.name("text"); write(w, r.text); }
        if (r.doc != null) { w.name("doc"); write(w, r.doc); }
        if (r.activity != null) { w.name("activity"); write(w, r.activity); }
    }
    static void readAll(Map<String, Object> m, FeatureRecord r) {
        r.visual = readVisual(Json.obj(m, "visual")); r.text = readText(Json.obj(m, "text"));
        r.doc = readDoc(Json.obj(m, "doc")); r.activity = readActivity(Json.obj(m, "activity"));
    }
}
