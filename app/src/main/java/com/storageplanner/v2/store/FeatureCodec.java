package com.storageplanner.v2.store;

import com.storageplanner.v2.model.*;
import com.storageplanner.v2.util.Json;
import com.storageplanner.v2.util.JsonWriter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** JSON of FeatureRecord / HashRecord and their sub-records (PHASE_2 2). Unknowns are null. Also used by V2Cli. */
public final class FeatureCodec {
    private FeatureCodec() {}

    static Tri tri(Map<String, Object> m, String k) { return Codecs.tri(m, k); }
    static int i(Map<String, Object> m, String k) { return Json.integer(m, k, -1); }
    static long l(Map<String, Object> m, String k) { return Json.lng(m, k, -1); }
    static long t(Map<String, Object> m, String k) { return Json.lng(m, k, Unknown.TIME); }
    static String s(Map<String, Object> m, String k) { return Json.str(m, k); }
    static double d(Map<String, Object> m, String k) { return Json.dbl(m, k); }

    public static void writeFp(JsonWriter w, Fingerprint fp) {
        if (fp == null) { w.nullValue(); return; }
        w.beginObject().name("size").valueCount(fp.size).name("mtime").valueTime(fp.mtime).name("inode").valueCount(fp.inode).endObject();
    }
    static Fingerprint readFp(Map<String, Object> m) { return m == null ? null : new Fingerprint(l(m, "size"), t(m, "mtime"), l(m, "inode")); }

    public static void writeImage(JsonWriter w, ImageInfo x) {
        if (x == null) { w.nullValue(); return; }
        w.beginObject().name("format").value(x.format).name("width").valueCount(x.width).name("height").valueCount(x.height)
         .name("orientation").valueCount(x.orientation).name("uprightWidth").valueCount(x.uprightWidth).name("uprightHeight").valueCount(x.uprightHeight)
         .name("aspect").value(x.aspect).name("megapixels").value(x.megapixels).name("animated").value(x.animated).name("frames").valueCount(x.frames)
         .name("hasAlpha").value(x.hasAlpha).name("bitDepth").valueCount(x.bitDepth).name("progressive").value(x.progressive)
         .name("jpegQuality").valueCount(x.jpegQuality).name("motionPhoto").value(x.motionPhoto).name("motionVideoBytes").valueCount(x.motionVideoBytes)
         .name("decodeStatus").value(x.decodeStatus).name("analysedWidth").valueCount(x.analysedWidth).name("analysedHeight").valueCount(x.analysedHeight)
         .name("sharpness").value(x.sharpness).name("brightness").value(x.brightness).name("contrast").value(x.contrast)
         .name("clipLow").value(x.clipLow).name("clipHigh").value(x.clipHigh).name("exposure").value(x.exposure)
         .name("colorfulness").value(x.colorfulness).name("saturation").value(x.saturation).name("lumaHist16");
        if (x.lumaHist16 == null) w.nullValue(); else { w.beginArray(); for (int v : x.lumaHist16) w.value(v); w.endArray(); }
        w.endObject();
    }
    static ImageInfo readImage(Map<String, Object> m) {
        if (m == null) return null;
        ImageInfo x = new ImageInfo();
        x.format = s(m, "format"); x.width = i(m, "width"); x.height = i(m, "height"); x.orientation = i(m, "orientation");
        x.uprightWidth = i(m, "uprightWidth"); x.uprightHeight = i(m, "uprightHeight"); x.aspect = d(m, "aspect"); x.megapixels = d(m, "megapixels");
        x.animated = tri(m, "animated"); x.frames = i(m, "frames"); x.hasAlpha = tri(m, "hasAlpha"); x.bitDepth = i(m, "bitDepth");
        x.progressive = tri(m, "progressive"); x.jpegQuality = i(m, "jpegQuality"); x.motionPhoto = tri(m, "motionPhoto");
        x.motionVideoBytes = l(m, "motionVideoBytes"); x.decodeStatus = Json.enumOf(m, "decodeStatus", DecodeStatus.class, null);
        x.analysedWidth = i(m, "analysedWidth"); x.analysedHeight = i(m, "analysedHeight"); x.sharpness = d(m, "sharpness");
        x.brightness = d(m, "brightness"); x.contrast = d(m, "contrast"); x.clipLow = d(m, "clipLow"); x.clipHigh = d(m, "clipHigh");
        x.exposure = d(m, "exposure"); x.colorfulness = d(m, "colorfulness"); x.saturation = d(m, "saturation");
        List<Object> h = Json.list(m, "lumaHist16");
        if (h != null) { x.lumaHist16 = new int[h.size()]; for (int k = 0; k < h.size(); k++) x.lumaHist16[k] = h.get(k) instanceof Number ? ((Number) h.get(k)).intValue() : 0; }
        return x;
    }

    public static void writeExif(JsonWriter w, ExifInfo x) {
        if (x == null) { w.nullValue(); return; }
        w.beginObject().name("dateTimeOriginal").valueTime(x.dateTimeOriginal).name("offsetTimeOriginal").value(x.offsetTimeOriginal)
         .name("tzKnown").value(x.tzKnown).name("dateTime").valueTime(x.dateTime).name("make").value(x.make).name("model").value(x.model)
         .name("software").value(x.software).name("hasGps").value(x.hasGps).name("orientation").valueCount(x.orientation)
         .name("pixelX").valueCount(x.pixelX).name("pixelY").valueCount(x.pixelY).name("hasThumbnail").value(x.hasThumbnail).endObject();
    }
    static ExifInfo readExif(Map<String, Object> m) {
        if (m == null) return null;
        ExifInfo x = new ExifInfo();
        x.dateTimeOriginal = t(m, "dateTimeOriginal"); x.offsetTimeOriginal = s(m, "offsetTimeOriginal"); x.tzKnown = tri(m, "tzKnown");
        x.dateTime = t(m, "dateTime"); x.make = s(m, "make"); x.model = s(m, "model"); x.software = s(m, "software"); x.hasGps = tri(m, "hasGps");
        x.orientation = i(m, "orientation"); x.pixelX = i(m, "pixelX"); x.pixelY = i(m, "pixelY"); x.hasThumbnail = tri(m, "hasThumbnail");
        return x;
    }

    public static void writeNameDate(JsonWriter w, NameDate x) {
        if (x == null) { w.nullValue(); return; }
        w.beginObject().name("at").valueTime(x.at).name("precision").value(x.precision).name("pattern").value(x.pattern).name("source").value(x.source).endObject();
    }
    static NameDate readNameDate(Map<String, Object> m) {
        if (m == null) return null;
        NameDate x = new NameDate(); x.at = t(m, "at"); x.precision = s(m, "precision"); x.pattern = s(m, "pattern"); x.source = s(m, "source"); return x;
    }

    public static void writeVideo(JsonWriter w, VideoInfo x) {
        if (x == null) { w.nullValue(); return; }
        w.beginObject().name("container").value(x.container).name("durationMs").valueCount(x.durationMs).name("width").valueCount(x.width)
         .name("height").valueCount(x.height).name("rotation").valueCount(x.rotation).name("fps").value(x.fps).name("bitrate").valueCount(x.bitrate)
         .name("videoCodec").value(x.videoCodec).name("audioCodec").value(x.audioCodec).name("hasAudio").value(x.hasAudio)
         .name("videoTracks").valueCount(x.videoTracks).name("audioTracks").valueCount(x.audioTracks).name("createdAt").valueTime(x.createdAt)
         .name("fragmented").value(x.fragmented).name("moovAtEnd").value(x.moovAtEnd).name("trailerBytes").valueCount(x.trailerBytes)
         .name("samsungTrailer").value(x.samsungTrailer).name("problem").value(x.problem).endObject();
    }
    static VideoInfo readVideo(Map<String, Object> m) {
        if (m == null) return null;
        VideoInfo x = new VideoInfo();
        x.container = s(m, "container"); x.durationMs = l(m, "durationMs"); x.width = i(m, "width"); x.height = i(m, "height");
        x.rotation = i(m, "rotation"); x.fps = d(m, "fps"); x.bitrate = l(m, "bitrate"); x.videoCodec = s(m, "videoCodec"); x.audioCodec = s(m, "audioCodec");
        x.hasAudio = tri(m, "hasAudio"); x.videoTracks = i(m, "videoTracks"); x.audioTracks = i(m, "audioTracks"); x.createdAt = t(m, "createdAt");
        x.fragmented = tri(m, "fragmented"); x.moovAtEnd = tri(m, "moovAtEnd"); x.trailerBytes = l(m, "trailerBytes");
        x.samsungTrailer = tri(m, "samsungTrailer"); x.problem = s(m, "problem");
        return x;
    }

    public static void writeAudio(JsonWriter w, AudioInfo x) {
        if (x == null) { w.nullValue(); return; }
        w.beginObject().name("container").value(x.container).name("codec").value(x.codec).name("durationMs").valueCount(x.durationMs)
         .name("durationConfidence").value(x.durationConfidence).name("bitrate").valueCount(x.bitrate).name("sampleRate").valueCount(x.sampleRate)
         .name("channels").valueCount(x.channels).name("vbr").value(x.vbr).endObject();
    }
    static AudioInfo readAudio(Map<String, Object> m) {
        if (m == null) return null;
        AudioInfo x = new AudioInfo();
        x.container = s(m, "container"); x.codec = s(m, "codec"); x.durationMs = l(m, "durationMs"); x.durationConfidence = d(m, "durationConfidence");
        x.bitrate = l(m, "bitrate"); x.sampleRate = i(m, "sampleRate"); x.channels = i(m, "channels"); x.vbr = tri(m, "vbr");
        return x;
    }

    public static void writeApk(JsonWriter w, ApkInfo x) {
        if (x == null) { w.nullValue(); return; }
        w.beginObject().name("packageName").value(x.packageName).name("versionName").value(x.versionName).name("versionCode").valueCount(x.versionCode)
         .name("minSdk").valueCount(x.minSdk).name("targetSdk").valueCount(x.targetSdk).name("signerCertSha256").strings(x.signerCertSha256)
         .name("signatureScheme").value(x.signatureScheme).name("split").value(x.split).name("bundle").value(x.bundle)
         .name("installState").value(x.installState).name("installedVersionCode").valueCount(x.installedVersionCode)
         .name("sameSigner").value(x.sameSigner).name("parseSource").value(x.parseSource).name("problem").value(x.problem).endObject();
    }
    static ApkInfo readApk(Map<String, Object> m) {
        if (m == null) return null;
        ApkInfo x = new ApkInfo();
        x.packageName = s(m, "packageName"); x.versionName = s(m, "versionName"); x.versionCode = l(m, "versionCode");
        x.minSdk = i(m, "minSdk"); x.targetSdk = i(m, "targetSdk"); x.signerCertSha256 = Json.strings(m, "signerCertSha256");
        x.signatureScheme = s(m, "signatureScheme"); x.split = tri(m, "split"); x.bundle = tri(m, "bundle");
        x.installState = Json.enumOf(m, "installState", InstallState.class, InstallState.UNKNOWN);
        x.installedVersionCode = l(m, "installedVersionCode"); x.sameSigner = tri(m, "sameSigner"); x.parseSource = s(m, "parseSource");
        x.problem = s(m, "problem");
        return x;
    }

    public static void writePdf(JsonWriter w, PdfInfo x) {
        if (x == null) { w.nullValue(); return; }
        w.beginObject().name("version").value(x.version).name("encrypted").value(x.encrypted).name("linearized").value(x.linearized)
         .name("pages").valueCount(x.pages).name("pagesSource").value(x.pagesSource).name("objectStreams").value(x.objectStreams)
         .name("incrementalUpdates").valueCount(x.incrementalUpdates).name("title").value(x.title).name("author").value(x.author)
         .name("producer").value(x.producer).name("createdAt").valueTime(x.createdAt).endObject();
    }
    static PdfInfo readPdf(Map<String, Object> m) {
        if (m == null) return null;
        PdfInfo x = new PdfInfo();
        x.version = s(m, "version"); x.encrypted = tri(m, "encrypted"); x.linearized = tri(m, "linearized"); x.pages = i(m, "pages");
        x.pagesSource = s(m, "pagesSource"); x.objectStreams = tri(m, "objectStreams"); x.incrementalUpdates = i(m, "incrementalUpdates");
        x.title = s(m, "title"); x.author = s(m, "author"); x.producer = s(m, "producer"); x.createdAt = t(m, "createdAt");
        return x;
    }

    public static void writeEntry(JsonWriter w, ArchiveEntry e) {
        w.beginObject().name("path").value(e.path).name("rawName").value(e.rawName).name("container").value(e.container).name("depth").value(e.depth)
         .name("size").valueCount(e.size).name("compressedSize").valueCount(e.compressedSize).name("crc32").valueCount(e.crc32)
         .name("method").valueCount(e.method).name("mtime").valueTime(e.mtime).name("dir").value(e.dir).name("encrypted").value(e.encrypted)
         .name("localOffset").valueCount(e.localOffset).endObject();   // PHASE_S S3: ArchiveMatcher reads it after a reload
    }
    static ArchiveEntry readEntry(Map<String, Object> m) {
        ArchiveEntry e = new ArchiveEntry();
        e.path = s(m, "path"); e.rawName = s(m, "rawName"); e.container = s(m, "container"); e.depth = Json.integer(m, "depth", 0);
        e.size = l(m, "size"); e.compressedSize = l(m, "compressedSize"); e.crc32 = l(m, "crc32"); e.method = i(m, "method");
        e.mtime = t(m, "mtime"); e.dir = Json.bool(m, "dir", false); e.encrypted = tri(m, "encrypted");
        e.localOffset = l(m, "localOffset");
        return e;
    }

    /** maxEntries < 0: all; else at most that many, plus "entriesTruncated". */
    public static void writeArchive(JsonWriter w, ArchiveInfo x, int maxEntries) {
        if (x == null) { w.nullValue(); return; }
        w.beginObject().name("format").value(x.format).name("subtype").value(x.subtype).name("listingStatus").value(x.listingStatus)
         .name("declaredEntries").valueCount(x.declaredEntries).name("listedEntries").value(x.listedEntries)
         .name("totalUncompressed").valueCount(x.totalUncompressed).name("totalCompressed").valueCount(x.totalCompressed)
         .name("maxRatio").value(x.maxRatio).name("bombSuspect").value(x.bombSuspect).name("pathTraversal").value(x.pathTraversal)
         .name("absolutePaths").value(x.absolutePaths).name("encryptedEntries").value(x.encryptedEntries)
         .name("nameEncodingUncertain").value(x.nameEncodingUncertain).name("nestedArchives").valueCount(x.nestedArchives)
         .name("nestedListed").value(x.nestedListed).name("problem").value(x.problem).name("entries");
        if (x.entries == null) w.nullValue();
        else {
            int n = maxEntries < 0 ? x.entries.size() : Math.min(maxEntries, x.entries.size());
            w.beginArray(); for (int k = 0; k < n; k++) writeEntry(w, x.entries.get(k)); w.endArray();
            if (n < x.entries.size()) w.name("entriesTruncated").value(x.entries.size() - n);
        }
        w.endObject();
    }
    @SuppressWarnings("unchecked")
    static ArchiveInfo readArchive(Map<String, Object> m) {
        if (m == null) return null;
        ArchiveInfo x = new ArchiveInfo();
        x.format = s(m, "format"); x.subtype = s(m, "subtype"); x.listingStatus = s(m, "listingStatus"); x.declaredEntries = l(m, "declaredEntries");
        x.listedEntries = Json.integer(m, "listedEntries", 0); x.totalUncompressed = l(m, "totalUncompressed"); x.totalCompressed = l(m, "totalCompressed");
        x.maxRatio = d(m, "maxRatio"); x.bombSuspect = tri(m, "bombSuspect"); x.pathTraversal = tri(m, "pathTraversal");
        x.absolutePaths = tri(m, "absolutePaths"); x.encryptedEntries = tri(m, "encryptedEntries"); x.nameEncodingUncertain = tri(m, "nameEncodingUncertain");
        x.nestedArchives = i(m, "nestedArchives"); x.nestedListed = Json.integer(m, "nestedListed", 0); x.problem = s(m, "problem");
        List<Object> es = Json.list(m, "entries");
        if (es != null) { x.entries = new ArrayList<ArchiveEntry>(); for (Object o : es) if (o instanceof Map) x.entries.add(readEntry((Map<String, Object>) o)); }
        return x;
    }

    public static final Codecs.Codec<FeatureRecord> FEATURE = new Codecs.Codec<FeatureRecord>() {
        public String table() { return "file_features"; }
        public void write(JsonWriter w, FeatureRecord r) {
            w.beginObject().name("fileId").value(r.fileId).name("fp"); writeFp(w, r.fp);
            w.name("versions").beginArray(); for (int v : r.versions) w.value(v); w.endArray();
            w.name("extractedScanId").valueCount(r.extractedScanId).name("confidence").value(r.confidence).name("problems").strings(r.problems);
            w.name("image"); writeImage(w, r.image);
            w.name("exif"); writeExif(w, r.exif);
            w.name("nameDate"); writeNameDate(w, r.nameDate);
            w.name("video"); writeVideo(w, r.video);
            w.name("audio"); writeAudio(w, r.audio);
            w.name("apk"); writeApk(w, r.apk);
            w.name("pdf"); writePdf(w, r.pdf);
            w.name("archive"); writeArchive(w, r.archive, -1);
            w.name("extra").beginObject(); for (Map.Entry<String, String> e : r.extra.entrySet()) w.name(e.getKey()).value(e.getValue()); w.endObject();
            w.endObject();
        }
        public FeatureRecord read(Map<String, Object> m) {
            FeatureRecord r = new FeatureRecord();
            r.fileId = s(m, "fileId"); r.fp = readFp(Json.obj(m, "fp"));
            List<Object> v = Json.list(m, "versions");
            if (v != null) for (int k = 0; k < v.size() && k < r.versions.length; k++) r.versions[k] = v.get(k) instanceof Number ? ((Number) v.get(k)).intValue() : 0;
            r.extractedScanId = l(m, "extractedScanId"); r.confidence = d(m, "confidence");
            List<String> p = Json.strings(m, "problems"); if (p != null) r.problems = p;
            r.image = readImage(Json.obj(m, "image")); r.exif = readExif(Json.obj(m, "exif")); r.nameDate = readNameDate(Json.obj(m, "nameDate"));
            r.video = readVideo(Json.obj(m, "video")); r.audio = readAudio(Json.obj(m, "audio")); r.apk = readApk(Json.obj(m, "apk"));
            r.pdf = readPdf(Json.obj(m, "pdf")); r.archive = readArchive(Json.obj(m, "archive"));
            Map<String, Object> ex = Json.obj(m, "extra");
            if (ex != null) { r.extra = new TreeMap<String, String>(); for (Map.Entry<String, Object> e : ex.entrySet()) if (e.getValue() instanceof String) r.extra.put(e.getKey(), (String) e.getValue()); }
            return r;
        }
    };

    public static final Codecs.Codec<HashRecord> HASH = new Codecs.Codec<HashRecord>() {
        public String table() { return "file_hashes"; }
        public void write(JsonWriter w, HashRecord r) {
            w.beginObject().name("fileId").value(r.fileId).name("fp"); writeFp(w, r.fp);
            w.name("quickHash").value(r.quickHash).name("sha256").value(r.sha256).name("crc32").valueCount(r.crc32)
             .name("hashedAt").valueTime(r.hashedAt).name("reason").value(r.reason).endObject();
        }
        public HashRecord read(Map<String, Object> m) {
            HashRecord r = new HashRecord();
            r.fileId = s(m, "fileId"); r.fp = readFp(Json.obj(m, "fp")); r.quickHash = s(m, "quickHash"); r.sha256 = s(m, "sha256");
            r.crc32 = l(m, "crc32"); r.hashedAt = t(m, "hashedAt"); r.reason = s(m, "reason");
            return r;
        }
    };
}
