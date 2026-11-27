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
 * "faces = 2"). They are what the rules (Rule, BuiltInRules) test. Nothing here decides anything.
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
            FACES = "faces", EYES_OPEN = "eyes_open", HAS_CODE = "has_code", BLUR = "blur", BRIGHTNESS = "brightness", DARK_SHARE = "dark_share", GLARE_SHARE = "glare_share", FAVOURITE = "favourite", TAKEN_DAYS = "taken_days",
            SCREENSHOT_APP = "screenshot_app", MOTION_PHOTO = "motion_photo", MOTION_VIDEO_MB = "motion_video_mb", CODE_TYPE = "code_type",
            SCENE = "scene", AESTHETIC = "aesthetic", SMILING = "smiling", LOOKING = "looking_at_camera",
            COLOURFULNESS = "colourfulness", GRAPHIC_SHARE = "graphic_share", PAPER_SHARE = "paper_share", INK_SHARE = "ink_share", LOOKS_LIKE_PAGE = "looks_like_page", TEXT_AMOUNT = "text_amount",
            DUPLICATE = "duplicate", BURST_EXTRA = "burst_extra", BURST_KEEPER = "burst_keeper", SENT_COPY = "sent_copy",
            BLURRY = "blurry_vs_your_photos", INSTALLER_INSTALLED = "installer_already_installed", INSTALLER_NOT_INSTALLED = "installer_not_installed",
            ID_DOCUMENT = "id_document", EYES_CLOSED = "eyes_closed", SCREENSHOT = "screenshot", CHAT_MEDIA = "chat_media", CAMERA_PHOTO = "camera_photo",
            SIZE_MB = "size_mb", FILE_TYPE = "file_type", BITS_PER_PIXEL = "bits_per_pixel", VIDEO_CODEC = "video_codec",
            BITRATE_KBPS = "bitrate_kbps", DATE_IN_PICTURE = "date_in_picture", DAYS_SINCE_DATE = "days_since_date_in_picture",
            APK_PACKAGE = "apk_package",
            // what Mirage (v3, AlgorithmV3) saw; known only after it looked, except the two yes/no tags marked "always known"
            MIRAGE_LOOKED = "mirage_looked", MIRAGE_SEES = "mirage_sees", MIRAGE_STYLE = "mirage_style", MIRAGE_PEOPLE = "mirage_people",
            MIRAGE_OBJECTS = "mirage_objects", MIRAGE_SCENE = "mirage_scene", REFERENCE_PHOTO = "reference_photo", V3_CATEGORY = "mirage_category",
            // videos: length and frame rate from the file; the rest from a few frames (VideoCheck), known once they were looked at
            VIDEO_SECONDS = "video_seconds", VIDEO_FPS = "video_fps", VIDEO_VERTICAL = "vertical_video", VIDEO_LOOKED = "video_looked",
            VIDEO_DARK = "dark_video", VIDEO_BLURRY = "blurry_video", VIDEO_STILL = "still_video", VIDEO_PEOPLE = "video_people",
            VIDEO_WORDS = "video_text_words", ACCIDENTAL_CLIP = "accidental_clip", FORWARD_VIDEO = "forward_like_video",
            VIDEO_SILENT = "silent_video", VIDEO_JUNK = "video_junk_score";

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
            for (FileItem f : r.files) if ("camera".equals(f.area) && f.sharp >= 0 && !f.pending) n++;
            cameraSharp = new double[n];
            n = 0;
            for (FileItem f : r.files) {
                byRel.put(f.rel, f);
                if ("camera".equals(f.area) && f.sharp >= 0 && !f.pending) cameraSharp[n++] = f.sharp;
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
        public int eyesOpen = UNKNOWN;                  // faces whose eyes are open; UNKNOWN when the eyes were not looked at
        public Boolean hasCode;                         // null = not sorted
        public int blur = UNKNOWN;                      // 0..100, higher = blurrier than your other camera photos
        public int brightness = UNKNOWN;                // 0..100
        public int darkShare = UNKNOWN;                 // 0..100: share of nearly black pixels
        public int glareShare = UNKNOWN;                // 0..100: share lit up around blown-out lights (ImageSignals.glare)
        public int colourfulness = UNKNOWN;             // 0 (grey) and up; about 15 is a colourful picture, 60+ vivid
        public int graphicShare = UNKNOWN;              // 0..100: share of perfectly flat pixels (screens, graphics)
        public int paperShare = UNKNOWN;                // 0..100: share of plain colourless pixels as bright as the picture's own white (paper, also in dim light)
        public int inkShare = UNKNOWN;                  // 0..100: share clearly darker than the paper around it (pen, print); UNKNOWN when the picture has no plain paper area to look at
        public Boolean looksLikePage;                   // a photo of a handwritten or printed page; null = not measured
        public String textAmount;                       // AMOUNT_*; null = not measured
        public boolean duplicate, burstExtra, burstKeeper, sentCopy, blurry, installerAlreadyInstalled, installerNotInstalled,
                idDocument, screenshot, chatMedia, cameraPhoto;
        public String duplicateOf, burstKeptShot, sentCopyOf;      // the other file, by path
        public Boolean eyesClosed;                      // null when the eyes were not looked at (no faces, or not sorted by this version yet)
        public String installerFor;                     // package of an installer file
        public double sizeMb = UNKNOWN;                 // size in decimal MB, like Android Settings
        public String fileType;                         // as read from the first bytes: jpeg, png, mp4, ...; null = not known
        public double bitsPerPixel = UNKNOWN;           // file size in bits per picture pixel; UNKNOWN without picture dimensions
        public String videoCodec;                       // video codec mime (video/avc, ...); null = not read
        public double bitrateKbps = UNKNOWN;            // video or audio bitrate in kbit/s; UNKNOWN = not read
        public Boolean dateInPicture;                   // a date was read in the picture; null = not sorted
        public double daysSinceDate = UNKNOWN;          // days since that date (0 if it lies ahead); UNKNOWN without a date
        public String apkPackage;                       // package inside an .apk file
        public Boolean favourite;                       // a favourite in the gallery; null = the phone cannot say (before Android 11)
        public double takenDays = UNKNOWN;              // days since the photo was taken (EXIF); UNKNOWN when the file has no such date
        public String screenshotApp;                    // the app a screenshot shows, from its file name; null when the name does not say
        public Boolean motionPhoto;                     // has a short video inside; null = not checked (only camera JPEG/HEIC are)
        public double motionVideoMb = UNKNOWN;          // that video's size in MB (0 when not a Motion Photo)
        public double videoSeconds = UNKNOWN;           // a video's length; UNKNOWN for other files or when not read
        public double videoFps = UNKNOWN;               // its frame rate; UNKNOWN when the video does not say
        public Boolean videoVertical;                   // taller than wide as shown; null for other files or an unknown size
        public Boolean videoLooked;                     // its frames were looked at (VideoCheck); null for files that are not videos
        public Boolean videoDark, videoBlurry, videoStill; // dark or covered / blurred all through / nothing moving; null until looked at
        public double videoPeople = UNKNOWN;            // most faces in one of its frames; UNKNOWN until looked at
        public double videoWords = UNKNOWN;             // words read in its middle frame; UNKNOWN until looked at
        public Boolean accidentalClip;                  // a clip taken by mistake (VideoCheck.accidental); null until looked at
        public Boolean forwardVideo;                    // looks like a clip passed around in chats (VideoCheck.forwardLike); null until looked at
        public Boolean videoSilent;                     // no sound track, or quiet nearly all through; null when unknown
        public double videoJunk = UNKNOWN;              // junk score 0..100 (VideoCheck.junk); UNKNOWN until checked
        public String codeType;                         // kind of QR code or barcode (PhotoMeta.CODE_*); null = not sorted
        public String scene;                            // what the photo shows (Scene.*); null = not looked at yet
        public double aesthetic = UNKNOWN;              // learned aesthetic score 1..10; UNKNOWN = not looked at yet
        public double smiling = UNKNOWN;                // faces smiling; UNKNOWN = no faces judged or no mouth positions
        public double looking = UNKNOWN;                // faces looking at the camera; UNKNOWN as for smiling
        public boolean mirageLooked;                    // Mirage looked at this picture (always known)
        public boolean mirageSees;                      // it clearly recognised something; no when it did not look (always known)
        public String mirageStyle;                      // photograph, drawn, collage, screenshot, unspecified; null = not looked at
        public int miragePeople = UNKNOWN;              // people Mirage named with a good score; UNKNOWN = not looked at
        public String mirageObjects;                    // things it named, comma-separated ("" = none); null = not looked at
        public String mirageScene;                      // its scene words ("" = none); null = not looked at
        public Boolean referencePhoto;                  // a photo taken to remember something; null = not looked at
        public String v3Category;                       // v3's category (PhotoCategory id); null = not looked at

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
            l.add(num(EYES_OPEN, "Faces with eyes open", eyesOpen, String.valueOf(eyesOpen)));
            l.add(flag(HAS_CODE, "Has QR code or barcode", hasCode, null));
            l.add(num(BLUR, "Blur (vs your photos)", blur, blur + " of 100"));
            l.add(num(BRIGHTNESS, "Brightness", brightness, brightness + " of 100"));
            l.add(num(DARK_SHARE, "Dark share", darkShare, darkShare + "%"));
            l.add(num(GLARE_SHARE, "Glare share", glareShare, glareShare + "%"));
            l.add(num(COLOURFULNESS, "Colourfulness", colourfulness, String.valueOf(colourfulness)));
            l.add(num(GRAPHIC_SHARE, "Flat graphic share", graphicShare, graphicShare + "%"));
            l.add(num(PAPER_SHARE, "Paper-like share", paperShare, paperShare + "%"));
            l.add(num(INK_SHARE, "Ink share (pen or print on paper)", inkShare, inkShare + "%"));
            l.add(text(TEXT_AMOUNT, "Text in the picture", textAmount));
            l.add(flag(LOOKS_LIKE_PAGE, "Looks like a page of paper", looksLikePage, null));
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
            l.add(num(SIZE_MB, "Size in MB", sizeMb, String.format(Locale.US, "%.1f MB", sizeMb)));
            l.add(text(FILE_TYPE, "File type", fileType));
            l.add(num(BITS_PER_PIXEL, "Bits per pixel", bitsPerPixel, String.format(Locale.US, "%.2f", bitsPerPixel)));
            l.add(text(VIDEO_CODEC, "Video codec", videoCodec));
            l.add(num(BITRATE_KBPS, "Bitrate (kbit/s)", bitrateKbps, String.format(Locale.US, "%.0f kbit/s", bitrateKbps)));
            l.add(flag(DATE_IN_PICTURE, "Date read in the picture", dateInPicture, null));
            l.add(num(DAYS_SINCE_DATE, "Days since the date in the picture", daysSinceDate, String.format(Locale.US, "%.0f days", daysSinceDate)));
            l.add(text(APK_PACKAGE, "Installer for package", apkPackage));
            l.add(flag(FAVOURITE, "Favourite in your gallery", favourite, null));
            l.add(num(TAKEN_DAYS, "Age in days (since taken)", takenDays, String.format(Locale.US, "%.0f days", takenDays)));
            l.add(text(SCREENSHOT_APP, "Screenshot of app", screenshotApp));
            l.add(flag(MOTION_PHOTO, "Motion photo (has a short video)", motionPhoto, motionPhoto != null && motionPhoto ? String.format(Locale.US, "%.1f MB video", motionVideoMb) : null));
            l.add(num(MOTION_VIDEO_MB, "Motion photo video size", motionVideoMb, String.format(Locale.US, "%.1f MB", motionVideoMb)));
            l.add(num(VIDEO_SECONDS, "Video length", videoSeconds, String.format(Locale.US, "%.1f s", videoSeconds)));
            l.add(num(VIDEO_FPS, "Video frame rate", videoFps, String.format(Locale.US, "%.0f fps", videoFps)));
            l.add(flag(VIDEO_VERTICAL, "Upright video (taller than wide)", videoVertical, null));
            l.add(flag(VIDEO_LOOKED, "Video frames looked at", videoLooked, null));
            l.add(flag(VIDEO_DARK, "Video dark or covered", videoDark, null));
            l.add(flag(VIDEO_BLURRY, "Video blurred all through", videoBlurry, null));
            l.add(flag(VIDEO_STILL, "Nothing moves in the video", videoStill, null));
            l.add(num(VIDEO_PEOPLE, "Faces in the video", videoPeople, String.format(Locale.US, "%.0f", videoPeople)));
            l.add(num(VIDEO_WORDS, "Words in the video", videoWords, String.format(Locale.US, "%.0f", videoWords)));
            l.add(flag(ACCIDENTAL_CLIP, "Video clip taken by mistake", accidentalClip, null));
            l.add(flag(FORWARD_VIDEO, "Looks like a forwarded video", forwardVideo, null));
            l.add(flag(VIDEO_SILENT, "Video silent or without sound", videoSilent, null));
            l.add(num(VIDEO_JUNK, "Video junk score", videoJunk, String.format(Locale.US, "%.0f of 100", videoJunk)));
            l.add(text(CODE_TYPE, "Code type", codeType));
            l.add(text(SCENE, "Scene (what the photo shows)", scene));
            l.add(num(AESTHETIC, "Aesthetic score", aesthetic, String.format(Locale.US, "%.1f of 10", aesthetic)));
            l.add(num(SMILING, "Faces smiling", smiling, String.valueOf((int) smiling)));
            l.add(num(LOOKING, "Faces looking at the camera", looking, String.valueOf((int) looking)));
            l.add(flag(MIRAGE_LOOKED, "Mirage looked at it", mirageLooked, null));
            l.add(flag(MIRAGE_SEES, "Mirage clearly sees something (people, things or text)", mirageSees, null));
            l.add(text(MIRAGE_STYLE, "Kind of picture (Mirage)", mirageStyle));
            l.add(num(MIRAGE_PEOPLE, "People seen by Mirage", miragePeople, String.valueOf(miragePeople)));
            l.add(text(MIRAGE_OBJECTS, "Things seen by Mirage", mirageObjects == null ? null : mirageObjects.isEmpty() ? "none" : mirageObjects));
            l.add(text(MIRAGE_SCENE, "Scene (Mirage)", mirageScene == null ? null : mirageScene.isEmpty() ? "none" : mirageScene));
            l.add(flag(REFERENCE_PHOTO, "Photo taken to remember something (Mirage)", referencePhoto, null));
            l.add(text(V3_CATEGORY, "Picture category by v3 (Mirage)", v3Category == null ? null : PhotoCategory.label(v3Category)));
            return l;
        }

        /** Number tag by id (the rule engine); UNKNOWN (-1) when not measured or when the id is not a number tag. */
        public double numTag(String id) {
            switch (id) {
                case SIZE_BYTES: return sizeBytes;
                case AGE_DAYS: return ageDays;
                case WIDTH: return width;
                case HEIGHT: return height;
                case MEGAPIXELS: return megapixels;
                case FACES: return faces;
                case EYES_OPEN: return eyesOpen;
                case BLUR: return blur;
                case BRIGHTNESS: return brightness;
                case DARK_SHARE: return darkShare;
                case GLARE_SHARE: return glareShare;
                case COLOURFULNESS: return colourfulness;
                case GRAPHIC_SHARE: return graphicShare;
                case PAPER_SHARE: return paperShare;
                case INK_SHARE: return inkShare;
                case SIZE_MB: return sizeMb;
                case BITS_PER_PIXEL: return bitsPerPixel;
                case BITRATE_KBPS: return bitrateKbps;
                case DAYS_SINCE_DATE: return daysSinceDate;
                case TAKEN_DAYS: return takenDays;
                case MOTION_VIDEO_MB: return motionVideoMb;
                case AESTHETIC: return aesthetic;
                case SMILING: return smiling;
                case LOOKING: return looking;
                case MIRAGE_PEOPLE: return miragePeople;
                case VIDEO_SECONDS: return videoSeconds;
                case VIDEO_FPS: return videoFps;
                case VIDEO_PEOPLE: return videoPeople;
                case VIDEO_WORDS: return videoWords;
                case VIDEO_JUNK: return videoJunk;
                default: return UNKNOWN;
            }
        }

        /** Text tag by id (the rule engine); null when not known. For category this is the id (see PhotoCategory). */
        public String textTag(String id) {
            switch (id) {
                case SOURCE: return source;
                case AREA: return area;
                case KIND: return kind;
                case CATEGORY: return category;
                case TEXT_READ: return textRead;
                case TEXT_AMOUNT: return textAmount;
                case FILE_TYPE: return fileType;
                case VIDEO_CODEC: return videoCodec;
                case APK_PACKAGE: return apkPackage;
                case SCREENSHOT_APP: return screenshotApp;
                case CODE_TYPE: return codeType;
                case SCENE: return scene;
                case MIRAGE_STYLE: return mirageStyle;
                case MIRAGE_OBJECTS: return mirageObjects;
                case MIRAGE_SCENE: return mirageScene;
                case V3_CATEGORY: return v3Category;
                default: return null;
            }
        }

        /** Yes/no tag by id (the rule engine); null when not known. */
        public Boolean flagTag(String id) {
            switch (id) {
                case HAS_CODE: return hasCode;
                case DUPLICATE: return duplicate;
                case BURST_EXTRA: return burstExtra;
                case BURST_KEEPER: return burstKeeper;
                case SENT_COPY: return sentCopy;
                case BLURRY: return blurry;
                case INSTALLER_INSTALLED: return installerAlreadyInstalled;
                case INSTALLER_NOT_INSTALLED: return installerNotInstalled;
                case ID_DOCUMENT: return idDocument;
                case EYES_CLOSED: return eyesClosed;
                case LOOKS_LIKE_PAGE: return looksLikePage;
                case SCREENSHOT: return screenshot;
                case CHAT_MEDIA: return chatMedia;
                case CAMERA_PHOTO: return cameraPhoto;
                case DATE_IN_PICTURE: return dateInPicture;
                case FAVOURITE: return favourite;
                case MOTION_PHOTO: return motionPhoto;
                case MIRAGE_LOOKED: return mirageLooked;
                case MIRAGE_SEES: return mirageSees;
                case REFERENCE_PHOTO: return referencePhoto;
                case VIDEO_VERTICAL: return videoVertical;
                case VIDEO_LOOKED: return videoLooked;
                case VIDEO_DARK: return videoDark;
                case VIDEO_BLURRY: return videoBlurry;
                case VIDEO_STILL: return videoStill;
                case ACCIDENTAL_CLIP: return accidentalClip;
                case FORWARD_VIDEO: return forwardVideo;
                case VIDEO_SILENT: return videoSilent;
                default: return null;
            }
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

    /** What kind of value a tag has. */
    public enum Type { NUMBER, TEXT, FLAG }

    private static final Map<String, Type> TYPES = new HashMap<String, Type>();
    static {
        for (String id : new String[] {SIZE_BYTES, AGE_DAYS, WIDTH, HEIGHT, MEGAPIXELS, FACES, EYES_OPEN, BLUR, BRIGHTNESS, DARK_SHARE, GLARE_SHARE, COLOURFULNESS,
                GRAPHIC_SHARE, PAPER_SHARE, INK_SHARE, SIZE_MB, BITS_PER_PIXEL, BITRATE_KBPS, DAYS_SINCE_DATE, TAKEN_DAYS, MOTION_VIDEO_MB,
                AESTHETIC, SMILING, LOOKING, MIRAGE_PEOPLE, VIDEO_SECONDS, VIDEO_FPS, VIDEO_PEOPLE, VIDEO_WORDS, VIDEO_JUNK}) TYPES.put(id, Type.NUMBER);
        for (String id : new String[] {SOURCE, AREA, KIND, CATEGORY, TEXT_READ, TEXT_AMOUNT, FILE_TYPE, VIDEO_CODEC, APK_PACKAGE, SCREENSHOT_APP, CODE_TYPE, SCENE,
                MIRAGE_STYLE, MIRAGE_OBJECTS, MIRAGE_SCENE, V3_CATEGORY}) TYPES.put(id, Type.TEXT);
        for (String id : new String[] {HAS_CODE, DUPLICATE, BURST_EXTRA, BURST_KEEPER, SENT_COPY, BLURRY, INSTALLER_INSTALLED, INSTALLER_NOT_INSTALLED,
                ID_DOCUMENT, EYES_CLOSED, SCREENSHOT, CHAT_MEDIA, CAMERA_PHOTO, DATE_IN_PICTURE, LOOKS_LIKE_PAGE, FAVOURITE, MOTION_PHOTO,
                MIRAGE_LOOKED, MIRAGE_SEES, REFERENCE_PHOTO, VIDEO_VERTICAL, VIDEO_LOOKED, VIDEO_DARK, VIDEO_BLURRY, VIDEO_STILL,
                ACCIDENTAL_CLIP, FORWARD_VIDEO, VIDEO_SILENT}) TYPES.put(id, Type.FLAG);
    }

    /** The type of the tag with this id; null if there is no such tag. */
    public static Type typeOf(String id) { return id == null ? null : TYPES.get(id); }

    // ------------------------------------------------------------------ listing (for the rule editor and for wording rules)
    /** One tag as the rule editor needs it: id, label for screens, type, unit of its numbers, and a short phrase for sentences. */
    public static final class Info {
        public final String id, label, unit, phrase;
        public final Type type;
        Info(String id, String label, Type type, String unit, String phrase) { this.id = id; this.label = label; this.type = type; this.unit = unit; this.phrase = phrase; }
    }

    // id -> {unit, phrase}; a yes/no phrase starts with "is" or "has" so it can be turned around ("is not a screenshot")
    private static final String[][] WORDS = {
        {SOURCE, "", "source"}, {AREA, "", "storage area"}, {KIND, "", "kind"}, {SIZE_BYTES, "bytes", "size"}, {AGE_DAYS, "days", "age"},
        {WIDTH, "px", "width"}, {HEIGHT, "px", "height"}, {MEGAPIXELS, "MP", "size in megapixels"}, {CATEGORY, "", "picture category"},
        {TEXT_READ, "", "text status"}, {FACES, "", "number of faces"}, {EYES_OPEN, "", "number of faces with eyes open"}, {HAS_CODE, "", "has a QR code or barcode"}, {BLUR, "of 100", "blur"},
        {BRIGHTNESS, "of 100", "brightness"}, {DARK_SHARE, "%", "dark share"}, {GLARE_SHARE, "%", "glare share"}, {COLOURFULNESS, "", "colourfulness"},
        {GRAPHIC_SHARE, "%", "flat graphic share"}, {PAPER_SHARE, "%", "paper-like share"}, {INK_SHARE, "%", "ink share"}, {LOOKS_LIKE_PAGE, "", "is a photo of a page of paper"}, {TEXT_AMOUNT, "", "amount of text"},
        {DUPLICATE, "", "is an exact duplicate"}, {BURST_EXTRA, "", "is an extra shot of a burst"}, {BURST_KEEPER, "", "is the best shot of a burst"},
        {SENT_COPY, "", "is a sent copy of a camera photo"}, {BLURRY, "", "is much blurrier than your other photos"},
        {INSTALLER_INSTALLED, "", "is an installer for an app that is already installed"}, {INSTALLER_NOT_INSTALLED, "", "is an installer for an app that is not installed"},
        {ID_DOCUMENT, "", "is an ID document"}, {EYES_CLOSED, "", "has someone with eyes closed"}, {SCREENSHOT, "", "is a screenshot"},
        {CHAT_MEDIA, "", "is chat or Telegram media"}, {CAMERA_PHOTO, "", "is a camera photo"}, {SIZE_MB, "MB", "size"},
        {FILE_TYPE, "", "file type"}, {BITS_PER_PIXEL, "", "bits per pixel"}, {VIDEO_CODEC, "", "video codec"}, {BITRATE_KBPS, "kbit/s", "bitrate"},
        {DATE_IN_PICTURE, "", "has a date read in the picture"}, {DAYS_SINCE_DATE, "days", "time since the date in the picture"},
        {APK_PACKAGE, "", "installer for package"},
        {FAVOURITE, "", "is a favourite in your gallery"}, {TAKEN_DAYS, "days", "time since it was taken"},
        {SCREENSHOT_APP, "", "app in the screenshot"}, {MOTION_PHOTO, "", "is a motion photo"}, {MOTION_VIDEO_MB, "MB", "size of its motion photo video"},
        {CODE_TYPE, "", "kind of code"}, {SCENE, "", "scene"}, {AESTHETIC, "of 10", "aesthetic score"},
        {SMILING, "", "number of smiling faces"}, {LOOKING, "", "number of faces looking at the camera"},
        {MIRAGE_LOOKED, "", "has been looked at by Mirage"}, {MIRAGE_SEES, "", "has something Mirage clearly sees"},
        {MIRAGE_STYLE, "", "kind of picture (Mirage)"}, {MIRAGE_PEOPLE, "", "number of people seen by Mirage"},
        {MIRAGE_OBJECTS, "", "things seen by Mirage"}, {MIRAGE_SCENE, "", "scene seen by Mirage"},
        {REFERENCE_PHOTO, "", "is a photo taken to remember something"}, {V3_CATEGORY, "", "picture category by v3"},
        {VIDEO_SECONDS, "s", "video length"}, {VIDEO_FPS, "fps", "frame rate"}, {VIDEO_VERTICAL, "", "is an upright video"},
        {VIDEO_LOOKED, "", "has had its frames looked at"}, {VIDEO_DARK, "", "is a dark or covered video"}, {VIDEO_BLURRY, "", "is blurred all through"},
        {VIDEO_STILL, "", "has nothing moving in it"}, {VIDEO_PEOPLE, "", "number of faces in the video"}, {VIDEO_WORDS, "", "number of words in the video"},
        {ACCIDENTAL_CLIP, "", "is a clip taken by mistake"}, {FORWARD_VIDEO, "", "is a forwarded-looking video"},
        {VIDEO_SILENT, "", "is silent or has no sound"}, {VIDEO_JUNK, "of 100", "video junk score"}};

    private static List<Info> LIST;

    /** Every tag in the order of T.all(), with its type, unit and phrase. Tags that rules cannot test (none today) would be left out. */
    public static synchronized List<Info> list() {
        if (LIST == null) {
            Map<String, String[]> w = new HashMap<String, String[]>();
            for (String[] x : WORDS) w.put(x[0], x);
            List<Info> l = new ArrayList<Info>();
            for (Entry e : new T().all()) {
                Type ty = typeOf(e.id);
                String[] x = w.get(e.id);
                if (ty == null || x == null) continue;
                l.add(new Info(e.id, e.label, ty, x[1], x[2]));
            }
            LIST = java.util.Collections.unmodifiableList(l);
        }
        return LIST;
    }

    /** The listing entry of a tag; null if there is no such tag. */
    public static Info info(String id) {
        for (Info i : list()) if (i.id.equals(id)) return i;
        return null;
    }

    /** A value a text tag can have: what goes into a rule, and what to show for it. */
    public static final class Choice {
        public final String value, label;
        Choice(String value, String label) { this.value = value; this.label = label; }
    }

    private static final String[] FILE_TYPES = {"jpeg", "png", "webp", "heic", "avif", "mp4", "mkv", "mp3", "flac", "m4a", "ogg", "pdf", "zip"};
    private static final String[] AREAS = {"camera", "screenshot", "pictures", "movies", "download", "music", "document", "trash", "thumbnails", "temp",
        "residue", "ext_cache", "ext_data", "wa_image", "wa_sent", "wa_video", "wa_sent_video", "wa_voice", "wa_doc", "wa_status", "wa_sticker", "wa_gif",
        "wa_old_backup", "wa_other", "telegram_media", "app_obb", "app_media", "user_other"};

    /**
     * Values the app itself knows for a text tag (source, area, kind, category, text status, text amount, file type); empty for tags
     * with free values (video codec, package). The editor adds the values that occur in the scan.
     */
    public static List<Choice> choices(String id) {
        List<Choice> l = new ArrayList<Choice>();
        if (id == null) return l;
        switch (id) {
            case SOURCE: {
                Set<String> seen = new HashSet<String>();
                for (String a : AREAS) { String s = SOURCES.get(a); if (s != null && seen.add(s)) l.add(new Choice(s, s)); }
                if (seen.add("camera video")) l.add(new Choice("camera video", "camera video"));
                break;
            }
            case AREA: for (String a : AREAS) l.add(new Choice(a, valueLabel(AREA, a))); break;
            case KIND: for (String k : new String[] {IMAGE, VIDEO, AUDIO, DOCUMENT, APK, OTHER}) l.add(new Choice(k, k)); break;
            case CATEGORY: for (String c : PhotoCategory.ALL) l.add(new Choice(c, PhotoCategory.label(c))); break;
            case TEXT_READ: for (String c : new String[] {TEXT_HAS, TEXT_NOT_YET, TEXT_NONE}) l.add(new Choice(c, c)); break;
            case TEXT_AMOUNT: for (String c : new String[] {AMOUNT_NONE, AMOUNT_SOME, AMOUNT_LOTS}) l.add(new Choice(c, c)); break;
            case FILE_TYPE: for (String c : FILE_TYPES) l.add(new Choice(c, c)); break;
            case CODE_TYPE: for (String c : PhotoMeta.CODE_TYPES) l.add(new Choice(c, c)); break;
            case SCENE: for (String c : Scene.ALL) l.add(new Choice(c, c)); break;
            case MIRAGE_STYLE: for (String c : AlgorithmV3.STYLES) l.add(new Choice(c, c)); break;
            case V3_CATEGORY: for (String c : PhotoCategory.ALL) l.add(new Choice(c, PhotoCategory.label(c))); break;
            default: break;
        }
        return l;
    }

    /** What to show for a value of a text tag: the name of a picture category, the name of a storage area, else the value itself. */
    public static String valueLabel(String id, String value) {
        if (CATEGORY.equals(id) || V3_CATEGORY.equals(id)) { for (String c : PhotoCategory.ALL) if (c.equalsIgnoreCase(value)) return PhotoCategory.label(c); }
        if (AREA.equals(id)) { String s = SOURCES.get(value); if (s != null) return s; }
        return value;
    }

    /** The values a text tag has in this scan, sorted (category: ids). Lets the editor offer what the user's phone really has. */
    public static List<String> occurring(String id, Scanner.Result r, Context ctx, long now) {
        java.util.TreeSet<String> out = new java.util.TreeSet<String>();
        if (typeOf(id) != Type.TEXT) return new ArrayList<String>();
        for (FileItem f : r.files) {
            String v = of(f, ctx, now).textTag(id);
            if (v != null && v.length() > 0) out.add(v);
            if (out.size() >= 200) break;
        }
        return new ArrayList<String>(out);
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
            if (s.glare >= 0) t.glareShare = pct(s.glare);
            t.colourfulness = (int) Math.round(s.colorful);
            t.graphicShare = pct(s.flat);
            if (s.pagePaper >= 0) {                  // measured by this version of the app; older measurements have no page check
                t.paperShare = pct(s.pagePaper);
                t.looksLikePage = s.page();
                if (s.ink >= 0) t.inkShare = pct(s.ink);
            }
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
        // eyes: measured more carefully for near-identical shots (quality), else by the sorter on every photo with a face
        // (no faces: nobody has eyes closed, and "faces with eyes open" stays unknown rather than 0)
        if (f.quality != null) {
            if (f.quality.faces > 0) t.eyesOpen = f.quality.openFaces;
            t.eyesClosed = f.quality.faces > 0 && f.quality.openFaces < f.quality.faces;
        } else if (f.eyeFaces > 0 && f.eyesOpen >= 0) {
            t.eyesOpen = f.eyesOpen;
            t.eyesClosed = f.eyesOpen < f.eyeFaces;
        } else if (f.faces == 0) t.eyesClosed = false;
        t.sizeMb = f.size / 1e6;
        t.fileType = f.type;
        if (f.width > 0 && f.height > 0) t.bitsPerPixel = Estimates.bpp(f);
        t.videoCodec = f.vcodec;
        if (f.bitrate > 0) t.bitrateKbps = f.bitrate / 1000.0;
        t.apkPackage = f.apkPackage;
        if (f.category != null) t.dateInPicture = f.catDate > 0;
        if (f.catDate > 0) t.daysSinceDate = Math.max(0, (now - f.catDate) / 86400000.0);
        t.screenshot = PhotoCategory.isScreenshot(f);
        t.chatMedia = f.area != null && (f.area.startsWith("wa_") || "telegram_media".equals(f.area));
        t.cameraPhoto = "camera".equals(f.area) && IMAGE.equals(t.kind);
        t.favourite = f.favourite;
        if (f.taken > 0) t.takenDays = Math.max(0, (now - f.taken) / 86400000.0);
        if (t.screenshot) t.screenshotApp = PhotoMeta.screenshotApp(f.name(), ctx.apps);
        if (f.motionBytes >= 0) { t.motionPhoto = f.motionBytes > 0; t.motionVideoMb = f.motionBytes / 1e6; }
        if (VIDEO.equals(t.kind)) {
            t.videoSeconds = VideoCheck.seconds(f);
            if (f.fps > 0) t.videoFps = f.fps;
            t.videoVertical = VideoCheck.vertical(f);
            t.videoLooked = VideoCheck.looked(f);
            t.videoDark = VideoCheck.dark(f); t.videoBlurry = VideoCheck.blurry(f, ctx.cameraSharp); t.videoStill = VideoCheck.still(f);
            if (VideoCheck.decoded(f) && f.vPeople >= 0) t.videoPeople = f.vPeople;
            if (VideoCheck.decoded(f) && f.vWords >= 0) t.videoWords = f.vWords;
            t.videoSilent = VideoCheck.silent(f);
            int js = VideoCheck.junkScore(f, ctx.cameraSharp);
            if (js >= 0) t.videoJunk = js;
            t.accidentalClip = VideoCheck.accidental(f, ctx.cameraSharp);
            t.forwardVideo = VideoCheck.forwardLike(f);
        }
        if (f.category != null) t.codeType = PhotoMeta.codeType(f.code);
        if (f.category != null) t.scene = Scene.of(f);
        if (f.aesthetic >= 0) t.aesthetic = Math.round(f.aesthetic * 10) / 10.0;
        if (f.eyeFaces > 0 && f.smiling >= 0) t.smiling = f.smiling;
        if (f.eyeFaces > 0 && f.looking >= 0) t.looking = f.looking;
        t.mirageLooked = f.mirageVersion > 0;
        t.mirageSees = f.mirageVersion > 0 && f.mirageSees;
        if (f.mirageVersion > 0) {
            t.mirageStyle = f.mirageStyle; t.miragePeople = Math.max(0, f.miragePeople);
            t.mirageObjects = f.mirageObjects == null ? "" : f.mirageObjects;
            t.mirageScene = f.mirageScene == null ? "" : f.mirageScene;
            t.referencePhoto = f.mirageReference;
            t.v3Category = f.v3Category;
        }
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
