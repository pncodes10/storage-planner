package com.storageplanner.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Tags: a flat, read-only set of named facts about one file ("source = chat image (received)", "blur = 85",
 * "faces = 2"). They are what user-written rules will test later. Nothing here decides anything; the planner's
 * rules (UnitBuilder) are unchanged and read the same measurements directly.
 *
 * Every tag has a stable lower_snake id (the constants below, for the rule engine) and a plain label (for
 * screens). A value that was not measured is UNKNOWN (-1 for numbers, null for yes/no and text), never a fake 0:
 * faces are not looked for in screenshots, picture measurements exist only for sorted pictures, and so on.
 * Pure Java; the per-scan facts that need the whole scan (the user's own photo sharpness, kept copies, installed
 * apps) are gathered once in a Context.
 */
public final class Tags {
    private Tags() {}

    // ---- ids (stable; rules refer to them)
    public static final String SOURCE = "source", AREA = "area", KIND = "kind", SIZE_BYTES = "size_bytes", AGE_DAYS = "age_days",
            WIDTH = "width", HEIGHT = "height", MEGAPIXELS = "megapixels", CATEGORY = "category", TEXT_READ = "text_read",
            FACES = "faces", HAS_CODE = "has_code", BLUR = "blur", BRIGHTNESS = "brightness", DARK_SHARE = "dark_share",
            COLOURFULNESS = "colourfulness", GRAPHIC_SHARE = "graphic_share", PAPER_SHARE = "paper_share", TEXT_AMOUNT = "text_amount",
            DUPLICATE = "duplicate", BURST_EXTRA = "burst_extra", BURST_KEEPER = "burst_keeper", SENT_COPY = "sent_copy",
            BLURRY = "blurry_vs_your_photos", INSTALLER_INSTALLED = "installer_already_installed", INSTALLER_NOT_INSTALLED = "installer_not_installed",
            ID_DOCUMENT = "id_document", EYES_CLOSED = "eyes_closed", SCREENSHOT = "screenshot", CHAT_MEDIA = "chat_media", CAMERA_PHOTO = "camera_photo";

    public static final int UNKNOWN = -1;

    /** Values of kind. */
    public static final String IMAGE = "image", VIDEO = "video", AUDIO = "audio", DOCUMENT = "document", APK = "apk", OTHER = "other";
    /** Values of text_read. */
    public static final String TEXT_HAS = "has text", TEXT_NOT_YET = "not read yet", TEXT_NONE = "no text";
    /** Values of text_amount. */
    public static final String AMOUNT_NONE = "none", AMOUNT_SOME = "some", AMOUNT_LOTS = "lots";

    /** The blur score needs this many camera photos with a sharpness to compare with; fewer and it is unknown. */
    public static final int MIN_CAMERA_PHOTOS = 10;

    private static final Map<String, String> SOURCES = new HashMap<String, String>();
    static {
        String[][] a = {{"camera", "camera photo"}, {"screenshot", "screenshot"}, {"pictures", "pictures folder"}, {"movies", "movies folder"},
            {"download", "download"}, {"music", "music"}, {"document", "document"}, {"trash", "trash"}, {"thumbnails", "thumbnail cache"},
            {"temp", "temporary file"}, {"residue", "app leftover"}, {"ext_cache", "app cache"}, {"ext_data", "app data"},
            {"wa_image", "chat image (received)"}, {"wa_sent", "chat image (sent)"}, {"wa_video", "chat video (received)"},
            {"wa_sent_video", "chat video (sent)"}, {"wa_voice", "chat audio"}, {"wa_doc", "chat document"}, {"wa_status", "chat status"},
            {"wa_sticker", "chat sticker"}, {"wa_gif", "chat GIF"}, {"wa_old_backup", "chat backup"}, {"wa_other", "chat file"},
            {"telegram_media", "telegram"}, {"app_obb", "app asset"}, {"app_media", "app media"}, {"user_other", "other"}};
        for (String[] x : a) SOURCES.put(x[0], x[1]);
    }

    // ------------------------------------------------------------------ context (once per scan)
    /** What a tag needs from the whole scan; build once with context() and reuse for every file. */
    public static final class Context {
        final UnitBuilder.Detections det;
        final double[] cameraSharp;                 // ImageFeatures sharpness of camera photos that have one, ascending
        final Set<String> blurry = new HashSet<String>();
        final Set<String> burstKeepers = new HashSet<String>();
        final Map<String, AppItem> apps = new HashMap<String, AppItem>();
        final Map<String, FileItem> byRel = new HashMap<String, FileItem>();

        Context(Scanner.Result r, UnitBuilder.Detections det) {
            this.det = det;
            int n = 0;
            for (FileItem f : r.files) if ("camera".equals(f.area) && f.sharp >= 0) n++;
            cameraSharp = new double[n];
            n = 0;
            for (FileItem f : r.files) {
                byRel.put(f.rel, f);
                if ("camera".equals(f.area) && f.sharp >= 0) cameraSharp[n++] = f.sharp;
            }
            Arrays.sort(cameraSharp);
            blurry.addAll(det.blurry);
            burstKeepers.addAll(det.burstExtraOf.values());
            for (AppItem a : r.apps) apps.put(a.pkg, a);
        }

        /** 0..100, higher = blurrier, among your own camera photos; UNKNOWN without a sharpness or enough photos to compare with. */
        int blur(FileItem f) {
            if (f.sharp < 0 || cameraSharp.length < MIN_CAMERA_PHOTOS) return UNKNOWN;
            int less = lowerBound(f.sharp), upto = lowerBound(Math.nextUp(f.sharp));       // [less, upto) are equal to f.sharp
            double rank = 100.0 * (less + 0.5 * (upto - less)) / cameraSharp.length;       // mid-rank percentile, 0..100
            return (int) Math.round(100.0 - rank);
        }

        private int lowerBound(double v) {
            int lo = 0, hi = cameraSharp.length;
            while (lo < hi) { int mid = (lo + hi) >>> 1; if (cameraSharp[mid] < v) lo = mid + 1; else hi = mid; }
            return lo;
        }
    }

    public static Context context(Scanner.Result r, UnitBuilder.Detections det) { return new Context(r, det); }

    // ------------------------------------------------------------------ one file's tags
    public static final class T {
        public String source, area, kind;
        public long sizeBytes;
        /** Days since the file was last MODIFIED (not last opened: Android does not tell us that). */
        public double ageDays;
        public int width = UNKNOWN, height = UNKNOWN;
        public double megapixels = UNKNOWN;
        public String category;                         // PhotoCategory id; null = not sorted
        public String textRead;                         // TEXT_*; null = not sorted
        public int faces = UNKNOWN;                     // UNKNOWN = not checked; never the same as 0
        public Boolean hasCode;                         // null = not sorted
        public int blur = UNKNOWN;                      // 0..100, higher = blurrier than your other camera photos
        public int brightness = UNKNOWN;                // 0..100
        public int darkShare = UNKNOWN;                 // 0..100: share of nearly black pixels
        public int colourfulness = UNKNOWN;             // 0 (grey) and up; about 15 is a colourful picture, 60+ vivid
        public int graphicShare = UNKNOWN;              // 0..100: share of perfectly flat pixels (screens, graphics)
        public int paperShare = UNKNOWN;                // 0..100: share of bright, colourless pixels (paper, documents)
        public String textAmount;                       // AMOUNT_*; null = not measured
        public boolean duplicate, burstExtra, burstKeeper, sentCopy, blurry, installerAlreadyInstalled, installerNotInstalled,
                idDocument, screenshot, chatMedia, cameraPhoto;
        public String duplicateOf, burstKeptShot, sentCopyOf;      // the other file, by path
        public Boolean eyesClosed;                      // null unless the photo was compared in a burst (quality measured)
        public String installerFor;                     // package of an installer file

        /** All tags in a fixed order, unknown ones included (for rules and tests). */
        public List<Entry> all() {
            List<Entry> l = new ArrayList<Entry>();
            l.add(text(SOURCE, "Source", source));
            l.add(text(AREA, "Storage area", area));
            l.add(text(KIND, "Kind", kind));
            l.add(num(SIZE_BYTES, "Size in bytes", sizeBytes, String.valueOf(sizeBytes)));
            l.add(num(AGE_DAYS, "Age in days (since modified)", ageDays, String.format(Locale.US, "%.0f days", ageDays)));
            l.add(num(WIDTH, "Width", width, width + " px"));
            l.add(num(HEIGHT, "Height", height, height + " px"));
            l.add(num(MEGAPIXELS, "Megapixels", megapixels, String.format(Locale.US, "%.1f MP", megapixels)));
            l.add(text(CATEGORY, "Picture category", category == null ? null : PhotoCategory.label(category)));      // the id stays in T.category
            l.add(text(TEXT_READ, "Text", textRead));
            l.add(num(FACES, "Faces", faces, String.valueOf(faces)));
            l.add(flag(HAS_CODE, "Has QR code or barcode", hasCode, null));
            l.add(num(BLUR, "Blur (vs your photos)", blur, blur + " of 100"));
            l.add(num(BRIGHTNESS, "Brightness", brightness, brightness + " of 100"));
            l.add(num(DARK_SHARE, "Dark share", darkShare, darkShare + "%"));
            l.add(num(COLOURFULNESS, "Colourfulness", colourfulness, String.valueOf(colourfulness)));
            l.add(num(GRAPHIC_SHARE, "Flat graphic share", graphicShare, graphicShare + "%"));
            l.add(num(PAPER_SHARE, "Paper-like share", paperShare, paperShare + "%"));
            l.add(text(TEXT_AMOUNT, "Text in the picture", textAmount));
            l.add(flag(SCREENSHOT, "Screenshot", screenshot, null));
            l.add(flag(CAMERA_PHOTO, "Camera photo", cameraPhoto, null));
            l.add(flag(CHAT_MEDIA, "Chat or Telegram media", chatMedia, null));
            l.add(flag(DUPLICATE, "Exact duplicate", duplicate, duplicateOf == null ? null : "identical to " + duplicateOf));
            l.add(flag(BURST_EXTRA, "Extra shot of a burst", burstExtra, burstKeptShot == null ? null : "better shot: " + burstKeptShot));
            l.add(flag(BURST_KEEPER, "Best shot of a burst", burstKeeper, null));
            l.add(flag(SENT_COPY, "Sent copy of a camera photo", sentCopy, sentCopyOf == null ? null : "original: " + sentCopyOf));
            l.add(flag(BLURRY, "Much blurrier than your other photos", blurry, null));
            l.add(flag(INSTALLER_INSTALLED, "Installer, app already installed", installerAlreadyInstalled, installerFor));
            l.add(flag(INSTALLER_NOT_INSTALLED, "Installer, app not installed", installerNotInstalled, installerFor));
            l.add(flag(ID_DOCUMENT, "ID document", idDocument, null));
            l.add(flag(EYES_CLOSED, "Someone has eyes closed", eyesClosed, null));
            return l;
        }

        /** What a screen shows: every tag that is known; yes/no tags only when they are yes. */
        public List<Entry> shown() {
            List<Entry> l = new ArrayList<Entry>();
            for (Entry e : all()) if (e.known && (!e.flag || e.on)) l.add(e);
            return l;
        }

        /** The tag with this id (null if there is none). */
        public Entry get(String id) {
            for (Entry e : all()) if (e.id.equals(id)) return e;
            return null;
        }
    }

    /** One tag as name and value. */
    public static final class Entry {
        public final String id, label;
        /** Text for a screen ("85 of 100", "yes", "chat image (received)"); "not measured" if unknown. */
        public final String value;
        public final boolean known;
        public final boolean flag;           // a yes/no tag
        public final boolean on;             // for a flag: it is true
        Entry(String id, String label, String value, boolean known, boolean flag, boolean on) {
            this.id = id; this.label = label; this.value = value; this.known = known; this.flag = flag; this.on = on;
        }
        @Override public String toString() { return label + ": " + value; }
    }

    private static Entry text(String id, String label, String v) { return new Entry(id, label, v == null ? "not measured" : v, v != null, false, false); }
    private static Entry num(String id, String label, double v, String shown) { return new Entry(id, label, v < 0 ? "not measured" : shown, v >= 0, false, false); }
    private static Entry flag(String id, String label, Boolean v, String detail) {
        boolean on = v != null && v;
        return new Entry(id, label, v == null ? "not measured" : on ? (detail == null ? "yes" : "yes, " + detail) : "no", v != null, true, on);
    }

    // ------------------------------------------------------------------ computing
    public static T of(FileItem f, Context ctx, long now) {
        T t = new T();
        t.area = f.area;
        t.source = source(f);
        t.kind = kind(f);
        t.sizeBytes = f.size;
        t.ageDays = Math.max(0, f.ageDays(now));      // a date in the future counts as new
        if (f.width > 0 && f.height > 0) {
            t.width = f.width; t.height = f.height;
            t.megapixels = f.width * (double) f.height / 1e6;
        }
        t.category = f.category;
        if (f.category != null) {
            t.textRead = f.textPending ? TEXT_NOT_YET : f.text != null ? TEXT_HAS : TEXT_NONE;    // "no text" includes pictures never worth reading
            t.hasCode = f.code != null;
        }
        t.faces = f.faces < 0 ? UNKNOWN : f.faces;
        // Relative to the user's own camera photos. The planner's "possibly blurry" rule (sharpness below 0.25 x the
        // median, UnitBuilder.BLUR_REL) is a different cut on the same measure; in practice it picks photos near the
        // blurry end of this scale (high blur), but the two are not the same number and the rule is unchanged.
        t.blur = ctx.blur(f);
        ImageSignals.S s = f.signals;
        if (s != null) {
            t.brightness = pct(s.meanLuma / 255.0);
            t.darkShare = pct(s.darkFrac);
            t.colourfulness = (int) Math.round(s.colorful);
            t.graphicShare = pct(s.flat);
            t.paperShare = pct(s.paper);
            // thresholds as in the sorter: "lots" is what makes a picture a page of text, "some" is what is worth reading
            t.textAmount = s.textBlobs >= 60 && s.textLines >= 4 ? AMOUNT_LOTS : s.wordy() ? AMOUNT_SOME : AMOUNT_NONE;
        }
        String dup = ctx.det.duplicateOf.get(f.rel), burst = ctx.det.burstExtraOf.get(f.rel), sent = ctx.det.sentCopyOf.get(f.rel);
        t.duplicate = dup != null; t.duplicateOf = dup;
        t.burstExtra = burst != null; t.burstKeptShot = burst;
        t.burstKeeper = ctx.burstKeepers.contains(f.rel);
        t.sentCopy = sent != null; t.sentCopyOf = sent;
        t.blurry = ctx.blurry.contains(f.rel);
        if (f.apkPackage != null) {
            t.installerFor = f.apkPackage;
            AppItem inst = ctx.apps.get(f.apkPackage);
            boolean installed = inst != null && inst.versionCode >= f.apkVersion;     // same test as UnitBuilder
            t.installerAlreadyInstalled = installed;
            t.installerNotInstalled = !installed;
        }
        t.idDocument = PhotoCategory.ID.equals(f.category);
        if (f.quality != null) t.eyesClosed = f.quality.faces > 0 && f.quality.openFaces < f.quality.faces;
        t.screenshot = PhotoCategory.isScreenshot(f);
        t.chatMedia = f.area != null && (f.area.startsWith("wa_") || "telegram_media".equals(f.area));
        t.cameraPhoto = "camera".equals(f.area) && IMAGE.equals(t.kind);
        return t;
    }

    private static int pct(double fraction) { return (int) Math.round(100 * Math.max(0, Math.min(1, fraction))); }

    /** User-facing name of where the file comes from. */
    public static String source(FileItem f) {
        if (f.area == null) return "other";
        if ("camera".equals(f.area) && VIDEO.equals(kind(f))) return "camera video";
        String n = SOURCES.get(f.area);
        return n != null ? n : f.area;
    }

    /** image / video / audio / document / apk / other, from the file type read from its first bytes. */
    public static String kind(FileItem f) {
        String ty = f.type;
        if (f.apkPackage != null || f.rel != null && f.rel.toLowerCase(Locale.ROOT).endsWith(".apk") && "zip".equals(ty)) return APK;
        if (ty == null) return OTHER;
        if (ty.equals("jpeg") || ty.equals("png") || ty.equals("webp") || ty.equals("heic") || ty.equals("avif")) return IMAGE;
        if (ty.equals("mp4") || ty.equals("mkv")) return VIDEO;
        if (ty.equals("mp3") || ty.equals("flac") || ty.equals("m4a") || ty.equals("ogg")) return AUDIO;
        if (ty.equals("pdf")) return DOCUMENT;
        return OTHER;
    }
}
