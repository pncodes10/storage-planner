# Phase 6 — content signals: OCR, document intelligence, adaptive video, audio

Status: design for review. Author: Phase 6 architect. Implementers: Sonnet-class agents, one slice at a time (§13).
Binding inputs: spec §5.2–5.5, §7, §9.4–9.5, §14.1, §16.4; `MANAGER_LOG.md` (D1–D10, A1–A37); `ARCHITECTURE.md`;
`PHASE_2..5_NOTES.md`. Where this document departs from them it says so in §15 (design choices) and §16 (questions for the lead).

**One-paragraph summary.** Phase 6 adds *content facts* (what a picture, document, video or recording contains) and uses them in
exactly two directions: **protect** (raise loss severity, `P_UNIQUE_RECORD`, veto removals) and **make eligible** for YOUR_CALL the
three rules A28 deferred (`Y_OLD_SCREENSHOT`, `Y_FORWARDED_MEDIA`, `Y_OLD_DOWNLOAD`), only when positive disposable evidence exists,
no protective signal fires, and every protective detector actually ran. Nothing in Phase 6 creates SAFE or NO-LOSS. Pure-Java detectors
(classic `ImageSignals`, `Codes` (QR), `Faces`/`FaceNet` (YuNet), PDF/OOXML text, MP4 sample tables) run identically on the JVM and the
phone. OCR and video frame decoding are device-only; on the desktop they are fed by fixtures that are derived from pixels/content, never
from labels. A content rule that needs a device-only engine stays **off on the phone until that engine is verified** (§9.4).

---

## 0. Measurements this design rests on (dataset `/home/user/sp-dataset`, result of HEAD `v-i3`, `--now 1792000000000`)

Scripts: `<scratchpad>/p6/a2.py` (misses), `<scratchpad>/p6/m/Measure.java` (classic `ImageSignals` + `Codes` + `Faces` on 943 images,
long edge 1024). Numbers below are medians per (folder, manifest category).

### 0.1 YOUR_CALL misses (evaluator row "YOUR_CALL after cap": 276/453 = 0.609 hit, 177 miss)

| Manifest group (expected YOUR_CALL, KEEP not acceptable) | n | Folder | Signal that can catch it | Phase 6 rule |
|---|---|---|---|---|
| `meme_greeting/meme` | 30 | WhatsApp Images | text over picture (bigText 0.56, textArea 0.098), English caption, faces in 13 % | `Y_FORWARDED_MEDIA` (faces ⇒ vetoed: ~26 reachable) |
| `meme_greeting/greeting_*` (9 festivals) | 59 | WhatsApp Images | English greeting phrase + **Devanagari** line on every image (generator line 290–297); colourful 68–128 | `Y_FORWARDED_MEDIA`, needs the PARTIAL-script ruling (§16 Q2) |
| `meme_greeting/unicode_name` | 4 | `Pictures/शुभेच्छा/` | as above, but `pictures` area | none (no rule for `pictures`; stays KEEP) |
| `advertisement` | 12+1 / 10 / 10 | WA / Download / Screenshots | "MEGA SALE", "FESTIVE OFFER", "FLASH DEAL" (gen_synth 360) | `Y_FORWARDED_MEDIA` / `Y_OLD_DOWNLOAD` / `Y_OLD_SCREENSHOT` |
| `screenshot/otp_sms` | 15 | Screenshots | "{code} is your OTP. Valid for 10 min." | `Y_OLD_SCREENSHOT` kind OTP (§16 Q1) |
| `unknown/accidental_pocket_shot` | 8 | Camera | flat 0.97, colourful 0.5 (pixel, not content) | none (not content; §14) |
| `backup/chat_db_rotation` | 4 | Databases | — | none (Phase 4 rule; not content) |
| `audio/silence` | 3 | Voice Recorder | every AAC frame is 4 bytes (digital silence) | `Y_SILENT_RECORDING` (S12, §16 Q3) |
| `video/long_static_no_event` | 1 | Camera | no motion peaks, 0.0018 bits/pixel | `Y_STATIC_VIDEO` (S11, §16 Q3) |
| `archive/junk_archive` | 1 | Download | — | none (§14) |
| capped copies (WebP etc., A23) | ~20 | various | — | none |

The other expected-YOUR_CALL groups already score as hits because KEEP is acceptable for them: `screenshot/chat` 70,
`settings` 20, `social_feed` 20, `qr_code` 72, `photo_of_screen` 12, `screenshot/None` 4.

### 0.2 Must-never-delete (mnd) files a naive "old screenshot / forward / download" rule would expose

| mnd group | n | Folder | What distinguishes it (measured) | Veto that protects it (§6.4) |
|---|---|---|---|---|
| `screenshot/payment_confirmation` | 30 | Screenshots | name suffix `_PaySpecimen`; flat 0.95, bigText 0.40, colourful 71; text "Paid", amounts | V-TEXT (PAYMENT kind / protect words), V-APPNAME (`pay`), and `P_UNIQUE_RECORD` |
| `id_document` | 1 | WhatsApp Images | **aspect 1.586** (ID-1 card), bigText 0.55, colourful 51 — looks like a meme on pixels alone | V-CARD, V-TEXT ("Republic", ID patterns) |
| `id_document/misleading_name` | 3 | Download, Pictures, Camera | aspect 1.586, flat 0.77, bigText 0.52 | V-CARD, V-TEXT; `pictures`/`camera` have no Y rule |
| `id_document` | 1 | Download (`id_card_gurpreet.jpg`) | no text over picture (bigText 0) | no positive evidence ⇒ never eligible |
| `people_family` | 6 (+1 REVIEW, +1 A27) | WhatsApp Images | faces in 100 % | V-FACES |
| `personal_photo` | 15+2 | WhatsApp Images | bigText 0, textArea 0.005 | no positive evidence; V-FACES for 18 % |
| receipts / certificates / IDs / family | 4/3/2/9 | Camera | — | no Phase 6 Y rule touches `camera` |
| documents (bank, invoices, IDs, encrypted) | 107 | Documents, Download | — | no Phase 6 Y rule touches documents (§7.5) |
| `video/long_static_with_event` | 4 | Camera | activity windows found by the bitstream profile (§8.1) | V-ACTIVITY |
| `screenshot/ticket` (not mnd, KEEP acceptable) | 12 | Screenshots | QR found in 12/12 | V-QR |

### 0.3 Detector facts

* QR (`core.Codes`, thorough): camera QR photos 91 %, downloaded/saved QR 100 %, ticket screenshots 100 %, payment screenshots 0 %;
  cost median 12 ms, max 65 ms at long edge 1024 (JVM).
* Faces (`core.Faces` + YuNet, score ≥ 0.7): WhatsApp family 100 %, camera family 85 %, memes 13 %, ads/greetings/screenshots 0 %;
  cost median 137 ms, max 350 ms (JVM, 1024 px; the detector itself works at 640).
* Video bitstream (ffprobe packet sizes, verified to equal what `stsz` holds): the four mnd "brief activity" videos show non-key
  bytes per second ≥ 6.5 KB exactly in the labelled windows (455–465 s, 211–217 s, 377–385 s, 302–307 s) against a median of
  0.55–0.62 KB/s; the no-event control's maximum second is 0.71 KB. 640×360, 10 fps, no audio track.
* Audio: the three "silence" m4a files have every AAC frame = 4 bytes; the four voice recordings have median frames 261–655 bytes.
* PDFs: 64 of 92 dataset PDFs are reportlab `PDF-1.3` with `[/ASCII85Decode /FlateDecode]` content streams and literal `(…) Tj`
  strings; the Form 1099 / W-2 PDFs use plain Flate; paystubs use 2-byte hex glyph strings (`<0015>`) without `/ToUnicode` (text
  unrecoverable ⇒ unknown); 11 scanned PDFs are image-only (`/DCTDecode`); 5 are encrypted.
* No MKV, AVI, TAR (one `.tar.gz`), ICO or GIF file exists in the dataset.

---

## 1. Scope

**In Phase 6** (slices S1–S13, §13): content-fact records and codec; MP4/M4A bitstream activity profile; PDF/OOXML/ODF/TXT/CSV
text and metadata; document categories, identifiers, dates, amounts; Devanagari and transliterated path keywords; document version
links; visual pass (ImageSignals, QR, faces) on selected images; OCR provider interface, budgeted OCR pass, script handling;
image categories; protective integration; `Y_OLD_SCREENSHOT`, `Y_FORWARDED_MEDIA`, `Y_OLD_DOWNLOAD`; evaluator `--gate phase6`;
Android bridges (OCR, video frames); adaptive video frame sampling and `Y_STATIC_VIDEO`; audio container probe, digital-silence
detection and `Y_SILENT_RECORDING`; faces for `P_PERSONAL_MEDIA_UNIQUE` (optional last slice).

**Cut from Phase 6** — see §14 (full list with reasons).

---

## 2. Deferred items collected from earlier phases, and the decision for each

| # | Item (source) | Decision | Slice / reason |
|---|---|---|---|
| 1 | `AudioProbe` mp3/flac/ogg/opus/wav (P2 notes slice 6, A11) | **In** (header facts only) | S12 |
| 2 | Audio silence %, speech/music activity, fingerprint (ARCH §11, REVIEW_1 F-M9) | **Partly**: digital silence for AAC-in-MP4 and PCM WAV only; speech/music/fingerprint stay unknown (need a decoder) | S12 |
| 3 | `MkvProbe`, `AviProbe` (A11) | **Cut** | §14 |
| 4 | `TarProbe` (A11, P3 §scenario 10) | **Cut** | §14 |
| 5 | ICO probe, GIF frame counting (A11) | **Cut** | §14 |
| 6 | `InstallerLinker` (P3 D-13, REVIEW_2 F-15) | **Cut** | §14 |
| 7 | `CompanionLinker` C1/C4: sidecars, multi-part files (P3 D-13) | **Cut** (to Phase 9); Phase 6 Y rules veto any file with a `COMPANION_OF` edge instead | §14 |
| 8 | `DocVersionLinker` (P3 D-13) | **In** | S5 |
| 9 | `N_ARCHIVE_FOLDER` (REVIEW_2 F-16, P5 D-11) | **Cut again** (to Phase 9) | §14 |
| 10 | `Y_OLD_SCREENSHOT`, `Y_FORWARDED_MEDIA`, `Y_OLD_DOWNLOAD` (A28, PHASE_4 §9.2) | **In** | S9 |
| 11 | Devanagari keywords (P4 notes "Keywords has no Devanagari entries") | **In** | S4 |
| 12 | Crops / collages | **Cut** (to Phase 7) | §14 |
| 13 | Low `EDITED_FROM` (R 0.52) and image `VERSION_OF` recall (P3) | **Cut** (graph calibration, not content) | §14 |
| 14 | `P_PERSONAL_MEDIA_UNIQUE` (faces; PHASE_4 §9.2) | **In, optional** | S13 |
| 15 | Lossy COMPRESS at YOUR_CALL ("Phase 6+", PHASE_5 §1) | **Cut** (to Phase 9) | §14 |
| 16 | `N_OLDER_BACKUP` producer (PHASE_5 §3 priority 4) | **Cut** (to Phase 9) | §14 |
| 17 | OOXML/ODF docProps, OLE2/OOXML encryption detection (ARCH §11 row 6) | **In** | S3 |
| 18 | PDF object parser with object streams (PHASE_2 choice 2-7) | **In** (bounded text extraction, not a full parser) | S3 |
| 19 | OCR of scanned PDF pages (spec 5.3) | **Cut** (device page rendering; no Phase 6 rule needs it) | §14 |
| 20 | Video OCR / frame faces beyond the sampler (spec 5.4) | Faces on sampled frames **in** (S11); frame OCR **cut** | §14 |
| 21 | Multi-threading (A11) | **Not in this phase** (A11) | — |
| 22 | APK bundle base manifest (P2 P6) | **Not content**; stays with installers | — |

---

## 3. Package and class map (all new code; Java 8; no android.* under `v2`)

All paths under `app/src/main/java/com/storageplanner/` unless noted. "S#" = slice that creates it.

| Class | Package | S# | Purpose |
|---|---|---|---|
| `VisualInfo`, `TextInfo`, `DocInfo`, `ActivityInfo`, `FrameInfo` | `v2.model` | S1 | content sub-records of `FeatureRecord` |
| `TextStatus`, `DocStatus`, `ActivityStatus`, `DetectorStatus` | `v2.model` | S1 | enums with explicit unknown values |
| `ContentCodec` | `v2.store` | S1 | JSON codec of the five sub-records (called from `FeatureCodec`) |
| `Mp4Tables`, `ActivityProfile` | `v2.content` | S2 | sample tables → `ActivityInfo` |
| `PdfText`, `PdfCMap`, `OoxmlText`, `OdfText`, `PlainText`, `XmlText`, `Ole2` | `v2.content.doc` | S3 | document text and metadata |
| `DocFacts`, `DocCategorizer`, `Identifiers`, `DocKeywords`, `MinHash` | `v2.content.doc` | S4 | categories, ids, dates, amounts, text sketch |
| `DocVersionLinker` | `v2.graph` | S5 | `VERSION_OF` between document versions |
| `VisualPass`, `FaceDetector`, `YunetFaceDetector`, `QrKind` | `v2.content` | S6 | ImageSignals + QR + faces |
| `TextReader`, `OcrResult`, `UnavailableTextReader`, `OcrPass`, `TextFacts`, `TextWords` | `v2.content` | S7 | OCR interface and pass |
| `ContentProviders`, `ContentConfig`, `ContentStage` | `v2.content` | S6/S7 | wiring, budgets, stage `T4_TEXT` |
| `ImageCategorizer` | `v2.content` | S8 | spec 5.2 categories |
| `ContentFacts`, `ContentRules` | `v2.risk` | S8/S9 | per-file facts for the risk engine; Y candidates |
| `FrameSampler`, `FrameStage` | `v2.content` | S11 | adaptive sampling, stage `T6_DETAIL` |
| `AudioProbe`, `AudioActivity` | `v2.features` / `v2.content` | S12 | audio headers; digital silence |
| `SidecarTextReader`, `DirFrameSource` | `jvmtest/.../v2test` | S7/S11 | desktop fixtures |
| `OcrTextReader` | `com.storageplanner.app` | S10 | bridge to the package-private `OcrEngine` (no edit to it) |
| `AndroidContent` | `com.storageplanner.app.v2` | S10 | builds `ContentProviders` on the phone |

Edited existing classes (exact edits are in each slice): `FeatureRecord`, `Extractors`, `FeatureCodec`, `FileContext`, `ContextBuilder`,
`SignalExtractor`, `LossEstimator`, `CandidateRules`, `HardRules`, `PresentationPolicy`, `ReasonCatalog`, `Keywords`, `RiskConfig`,
`V2Cli`, `V2Json`, `AndroidV2Device` (videoFrame), `V2Analysis`, `tools/v2eval/evaluate.py`, `tools/v2dataset/common.py`.
**Classic `core/` is not edited.** `com.storageplanner.app.OcrEngine` is not edited.

---

## 4. Content-fact model and storage (S1)

### 4.1 Rules for every new field

* Unknown representation follows ARCHITECTURE §2.1: `-1` counts, `NaN` measures, `Long.MIN_VALUE` times, `null` strings/lists,
  `Tri.UNKNOWN`, and an explicit `NOT_RUN`/`UNKNOWN` enum value.
* **No raw extracted text is stored** (spec 15: OCR/document text is as sensitive as the file). Only derived facts are stored:
  counts, kinds, keyword *class* names, identifier *kinds* (never values), dates, a 16-value MinHash sketch for version links.
  QR payloads are never stored; only `QrKind`.
* Every sub-record carries the version of the extractor that produced it (`FeatureRecord.versions[id]`, §4.3); a different current
  version makes the file selectable again (re-extraction).

### 4.2 Classes (`com.storageplanner.v2.model`, each `extends Sub` like `PdfInfo`; `copy()` via `Sub`)

```java
public enum DetectorStatus { NOT_RUN, OK, FAILED }            // NOT_RUN = unknown; FAILED = unknown + reason in problems

public final class VisualInfo extends Sub {                    // S6: one decode at long edge 1024, upright
    public DecodeStatus decodeStatus;                          // null = not attempted
    public int analysedWidth = -1, analysedHeight = -1;
    public double aspect = Double.NaN;                         // upright width / height of the ORIGINAL (from ImageInfo when known)
    // classic ImageSignals.S, copied 1:1 (NaN / -1 = unknown)
    public double meanLuma = Double.NaN, darkFrac = Double.NaN, contrast = Double.NaN, colorful = Double.NaN, paper = Double.NaN,
                  flat = Double.NaN, textArea = Double.NaN, textHeight = Double.NaN, bigText = Double.NaN;
    public int textBlobs = -1, textLines = -1;
    public DetectorStatus qrStatus = DetectorStatus.NOT_RUN;
    public String qrFormat;                                    // e.g. "QR_CODE"; null when none/unknown
    public String qrKind;                                      // QrKind name: URL, UPI, WIFI, CONTACT, TEXT; null when none/unknown
    public DetectorStatus faceStatus = DetectorStatus.NOT_RUN;
    public int faceCount = -1;                                 // faces with score >= 0.7 (PhotoQuality.MIN_FACE_CONFIDENCE)
    public double faceMaxScore = Double.NaN;
    public int faceModelVersion = -1;
}

public enum TextStatus { NOT_RUN, READ, PARTIAL_SCRIPT, NO_TEXT, LOW_CONFIDENCE, FAILED, UNAVAILABLE }
// READ: readable Latin text; PARTIAL_SCRIPT: some text read, other text present but unreadable (non-Latin script);
// NO_TEXT: pixels show no text AND the reader found none; LOW_CONFIDENCE: text present, reader could not read it;
// FAILED: reader error/timeout; UNAVAILABLE: no reader this run (never stored; see §6.3). Only READ/PARTIAL_SCRIPT are "known".

public final class TextInfo extends Sub {                      // S7
    public TextStatus status = TextStatus.NOT_RUN;
    public String engine; public int engineVersion = -1;
    public boolean engineVerified;                             // engine is on ContentConfig.VERIFIED_ENGINES at extraction time (§9.4)
    public int words = -1, goodWords = -1;                     // TextRules.goodWords
    public double meanConf = Double.NaN, readRatio = Double.NaN;   // §6.3 step 5
    public String kind;                                        // TextRules verdict kind (PhotoCategory id) or null
    public int kindScore = -1;
    public java.util.List<String> classes;                     // TextWords classes hit: PROTECT_PAY, PROTECT_ID, PROTECT_TRAVEL, PROTECT_MED,
                                                               // PROTECT_BANK, PROMO, GREETING, OTP; null = unknown, [] = none
    public int idPatterns = -1, amounts = -1, digitRuns4 = -1;   // counts; values never stored
    public long textDate = com.storageplanner.v2.model.Unknown.TIME;
    public int attempts = 0;                                   // failed attempts with this engine version
}

public enum DocStatus { NOT_RUN, OK, NO_TEXT_LAYER, ENCRYPTED, UNSUPPORTED, CORRUPT, LIMIT, TOO_LARGE }
public final class DocInfo extends Sub {                       // S3 (text facts) + S4 (category)
    public DocStatus status = DocStatus.NOT_RUN;
    public String format;                                      // pdf, docx, xlsx, pptx, odt, ods, odp, txt, csv, ole2
    public String title, author;                               // metadata only (spec 5.3); ≤ 200 chars each
    public int pages = -1;
    public long createdAt = Unknown.TIME, modifiedAt = Unknown.TIME;
    public int chars = -1, words = -1;
    public double latinShare = Double.NaN;                     // letters in Latin script / all letters
    public double devanagariShare = Double.NaN;
    public String category = "UNKNOWN";                        // DOC_* of ARCHITECTURE 2.10 or UNKNOWN (S4)
    public double categoryScore = Double.NaN;
    public java.util.List<String> categoryEvidence;            // keyword ids, ≤ 5, e.g. "kw:statement", "id:IFSC"
    public java.util.List<String> idKinds;                     // PAN, AADHAAR, IFSC, GSTIN, CARD, ACCOUNT, SSN, PASSPORT_IN; [] = none
    public int dates = -1, amounts = -1;
    public long latestDate = Unknown.TIME;
    public int[] minhash;                                      // 16 values, null = unknown (S4)
    public int unsupportedStreams = -1;                        // PDF streams with filters we do not decode
}

public enum ActivityStatus { NOT_RUN, OK, SHORT, NO_SAMPLE_TABLE, FRAGMENTED, ALL_KEYFRAMES, TOO_LARGE, CORRUPT, UNSUPPORTED }
public final class ActivityInfo extends Sub {                  // S2 (video), S12 (audio)
    public ActivityStatus videoStatus = ActivityStatus.NOT_RUN, audioStatus = ActivityStatus.NOT_RUN;
    public long durationMs = -1; public int videoSamples = -1, audioSamples = -1;
    public double medianWindowBytes = Double.NaN, peakThreshold = Double.NaN, bppMedian = Double.NaN, activeShare = Double.NaN;
    public int peakWindows = -1;
    public long[] peaks;                                       // [start0,end0,start1,end1,…] ms, ≤ 32 intervals; null = unknown
    public Tri staticCandidate = Tri.UNKNOWN;                  // §8.1 step 8
    public double tinyAudioShare = Double.NaN;                 // share of audio frames ≤ 8 bytes
    public Tri digitalSilence = Tri.UNKNOWN;                   // §10
}

public final class FrameInfo extends Sub {                     // S11
    public DetectorStatus status = DetectorStatus.NOT_RUN;
    public int planned = -1, decoded = -1, failed = -1;
    public long[] timesMs; public long[] phash;                // per decoded frame, same order; null = unknown
    public double diversity = Double.NaN;                      // mean pairwise Hamming / 64 over decoded frames
    public int sceneChanges = -1, faceFrames = -1, nothingVisible = -1;
    public boolean coversPeaks;                                // every ActivityInfo peak interval has ≥ 1 decoded frame
    public boolean engineVerified;
}
```

`FeatureRecord` gains `public VisualInfo visual; public TextInfo text; public DocInfo doc; public ActivityInfo activity; public FrameInfo frames;`
(copied in `copy()` like `pdf`). `FileContext` gains accessors `visual()`, `text()`, `doc()`, `activity()`, `frames()` (null-safe like `image()`).

### 4.3 Extractor ids and versioning (`v2.features.Extractors`)

Add ids `VISUAL = 20, OCR = 21, DOC_TEXT = 22, ACTIVITY = 23, FRAMES = 24`; `FeatureRecord.EXTRACTORS = 25`. Old snapshots hold
20 versions; `FeatureCodec` already reads `min(stored, current)` entries, so the new ids read as 0 ("never run"). No tier bit is used
for T4/T6: `tiersDone/tiersPending` are **not** touched (P13 would turn every file PARTIAL and change `extraction.status` across the
board). A file is pending for pass *k* iff `versions[k] != Extractors.version(k)` and the pass's selection (§6–§8) includes it. OCR
additionally re-runs when `text.engine`/`text.engineVersion` differ from the current reader's (`TextReader.id()/version()`).

### 4.4 Codec (`v2.store.ContentCodec`)

`static void write(JsonWriter w, VisualInfo|TextInfo|DocInfo|ActivityInfo|FrameInfo x)` and `static X readX(Map<String,Object> m)`;
`FeatureCodec.write/read` call them under keys `"visual"`, `"text"`, `"doc"`, `"activity"`, `"frames"`. **Omit** every field whose
value is its unknown value (keeps snapshot growth small, A21); a missing key reads as unknown. Arrays (`peaks`, `phash`, `minhash`,
`timesMs`) as JSON arrays of numbers; `long` phash as a 16-hex-digit string. `V2Json` (CLI output) writes the same objects under
`files[].content.{visual,text,doc,activity,frames}` and `files[].category`, `categoryConfidence`, `categorySource` (ARCH 2.10).

### 4.5 Tests (S1) — `ContentCodecTest` (jvmtest, registered in `AllV2Tests`)

| Case | Input | Expected |
|---|---|---|
| round trip | each sub-record with every field set to a non-default value | `read(write(x))` equals field by field |
| unknown omitted | fresh `new VisualInfo()` | written object is `{}`; read back gives `NaN`/`-1`/`NOT_RUN` |
| old snapshot | `FeatureRecord` JSON with a 20-element `versions` array | reads; `versions[20..24] == 0`; no exception |
| copy independence | `fr.copy()` then mutate `copy.visual.faceCount` | original unchanged |
| JSON CLI | `V2Json` of a file with `text` set | contains `content.text.status`, no key named `words` holding strings |

---

## 5. Processing tiers, stages, budgets, resumability (spec 14.1; A11, A34)

### 5.1 Where Phase 6 runs

| Order | Stage id | Class | Passes (in this order) | Device-only parts |
|---|---|---|---|---|
| after `GRAPH` | `T4_TEXT` | `ContentStage` | 1 DOC (§7) · 2 ACTIVITY (§8.1, §10) · 3 VISUAL (§6.1) · 4 OCR (§6.3) | OCR engine |
| after `T4_TEXT` | `T6_DETAIL` | `FrameStage` | FRAMES (§8.2) | frame decoder |
| then | `ASSESS`, `RECOMMEND`, `PLAN` | unchanged entry points | content facts read through `ContentFacts` (§9.1) | — |

Wiring: wherever `GraphStage` is added to a stage list (`grep -rn "new GraphStage(" app jvmtest`), append
`if (cfg.maxTier >= 4) list.add(new ContentStage(providers, contentCfg));` and `if (cfg.maxTier >= 6) list.add(new FrameStage(providers, contentCfg));`.
`V2Cli` default `--max-tier` stays 3 until S9 (so slices S1–S8 leave default results unchanged); S9 makes the default 4 and S11
makes it 6 (each slice records the new baseline). `V2Analysis` (phone) uses 4 from S10 and 6 from S11.

### 5.2 `ContentProviders` and `ContentConfig`

```java
package com.storageplanner.v2.content;
public final class ContentProviders {
    public final TextReader text;          // never null: UnavailableTextReader when there is none
    public final FaceDetector faces;       // null = faces NOT_RUN (unknown)
    public ContentProviders(TextReader text, FaceDetector faces) { ... }
    // video frames come from V2Device.media().videoFrame (existing interface, ARCH 3.2)
}
public final class ContentConfig {
    public int visualPerRun = 2000, ocrPerRun = 300, framesPerRun = 600, framesPerVideo = 24;   // BALANCED
    public long docMaxBytes = 16L << 20;          // whole-file read cap for document text
    public long ocrTimeoutMs = 20000;
    public int ocrLongEdge = 1600; public long ocrMaxPixels = 4000000L;
    public int visualLongEdge = 1024; public long visualMaxPixels = 100000000L;
    public static final java.util.Set<String> VERIFIED_ENGINES = new java.util.TreeSet<String>(java.util.Arrays.asList("sidecar-sim"));
    public static final java.util.Set<String> VERIFIED_FRAME_SOURCES = new java.util.TreeSet<String>(java.util.Arrays.asList("dir-fixture"));
    // Phone ids "tesseract.js-eng" / "mmr-26" are added only after the §12 checklist passes (separate commit, lead's decision).
    public static ContentConfig forProfile(com.storageplanner.v2.device.DeviceProfile p) { ... }   // LOW: 500/100/200/12; HIGH: 5000/600/1200/40
}
```

### 5.3 Budgets and the S7 partial-result contract

* Each pass counts its own work against `ContentConfig.*PerRun`; reaching a cap ends **that pass for this run** and the stage
  continues with the next pass, then returns `DONE` (as S7's GRAPH does, A34), so ASSESS/PLAN always run and the result is valid.
* The pipeline budget (`StageContext.budget`: ms/bytes) and `ScanControl` pause/cancel are checked before every file; on stop the
  stage commits and returns `PAUSED`/`BUDGET_EXHAUSTED`/`CANCELLED` exactly like `ExtractionLoop`.
* Commit every 200 files or 3 s (`Clock`), in the same commit as the sub-records and their `versions[k]` stamp, so a kill re-selects
  exactly the unfinished files (ARCH 5.2).
* Files a pass did not reach keep `NOT_RUN` ⇒ every rule treats them as unknown ⇒ no Phase 6 Y candidate, no content protection
  either (protection is only *added*; its absence is the Phase 5 state). The stage writes one session event
  `C_CONTENT_INCOMPLETE` (detail `"ocr=N visual=M doc=D frames=F"`, pending counts at stage end; replaced each run; absent when all 0).
  `V2Analysis.Result.pendingContent` reads it; message: "{n} pictures and documents have not been read yet, so v2 does not suggest
  anything based on their content; run the analysis again to continue." (`ReasonCatalog` code `C_CONTENT_INCOMPLETE`.)
* Determinism: within a pass files are processed in the order of §6.1/§6.3/§7.1/§8.2 (keys end with `fileId`), so a budgeted chain
  converges to the unbudgeted result (test in S7 and S11, modelled on `PerceptualBudgetTest`).
* Every pass catches `RuntimeException` and `OutOfMemoryError` per file: sub-record status `FAILED`, problem `content:<pass>:<Class>`,
  continue. A thrown `StoreException` propagates (stage FAILED), as in the other stages.

---

## 6. Images: visual pass, OCR, categories (S6, S7, S8)

### 6.1 VISUAL pass (`VisualPass`, pure Java, JVM == phone)

Selection (a file is selected iff all hold): PRESENT; `type ∈ {jpeg, png, webp, heic, heif}` and in `media().decodableTypes()`;
`versions[VISUAL] != current`; usage (`UsageClassifier.of` on a minimal `FileContext{f}`) ∈ {SCREENSHOT, CHAT_MEDIA, DOWNLOAD,
PICTURES} or `area == "document"`; not a `.thumbnails`/cache file. Camera photos are selected only by S13. Order: usage rank
(SCREENSHOT 0, CHAT_MEDIA 1, DOWNLOAD 2, PICTURES 3, document 4), then `sizeBytes` descending, then `fileId`.

Steps per file:
1. `DecodedImage d = media.decode(vol, path, cfg.visualLongEdge, cfg.visualMaxPixels, true)`; charge `budget.chargeDecode()`.
   `d.status != OK` ⇒ `visual.decodeStatus = d.status`, all detectors `NOT_RUN`; stamp version; done (unknown).
2. `ImageSignals.S s = ImageSignals.measure(d.argb, d.w, d.h)`; copy the 11 fields of §4.2.
3. `visual.aspect` = `ImageInfo.uprightWidth / uprightHeight` when both > 0, else `d.w / (double) d.h`.
4. QR: `String[] c = Codes.read(d.luma, d.w, d.h, true)`; `qrStatus = OK`; if `c != null`: `qrFormat = c[0]`, `qrKind = QrKind.of(c[1])`
   where `QrKind.of`: lower-case starts with `upi:` → UPI, `wifi:` → WIFI, `http://`/`https://` → URL, `begin:vcard`/`mecard:` → CONTACT,
   else TEXT. The payload string is dropped immediately. A `Throwable` from `Codes` ⇒ `qrStatus = FAILED`.
5. Faces: if `providers.faces == null` ⇒ `faceStatus = NOT_RUN`. Else `FaceResult r = faces.detect(d.argb, d.w, d.h)`;
   `faceCount` = faces with score ≥ 0.7; `faceMaxScore`; `faceModelVersion = faces.version()`; status OK, or FAILED on throw.
6. Stamp `versions[VISUAL]`; put the `FeatureRecord`.

```java
public interface FaceDetector { int version(); FaceResult detect(int[] argb, int w, int h); }
public final class FaceResult { public int count; public double maxScore; }
/** Pure Java: core.FaceNet.load(model) once; core.Faces.detect(net, argb, w, h); counts faces with score (index 3) >= 0.7. */
public final class YunetFaceDetector implements FaceDetector { public YunetFaceDetector(byte[] model) {...} public int version() { return 1; } ... }
```
Model bytes: JVM `app/src/main/assets/faces/yunet.bin` (V2Cli `--face-model PATH`, default that path); phone: the same asset read by
`AndroidContent` (S10). Cost: QR ≤ 65 ms, faces ≈ 140 ms per image (JVM); `visualPerRun` 2000 ≈ 6 min on the JVM.

Tests (`VisualPassTest`, fixtures drawn with `java.awt` in jvmtest `FxImg`):

| Input | Expected |
|---|---|
| 800×800 PNG, white, black QR from `com.google.zxing` encoder (vendored) with text `upi://pay?pa=x@y` | `qrStatus OK`, `qrKind "UPI"`, no field holds `x@y` |
| 1080×2400 PNG, flat grey with 30 lines of 24 px text | `flat ≥ 0.8`, `textBlobs ≥ 25`, `aspect 0.45 ± 0.01` |
| JPEG with `decode` forced to `NO_DECODER` (stub `MediaDecoder`) | `decodeStatus NO_DECODER`, `qrStatus NOT_RUN`, `faceStatus NOT_RUN` |
| stub `FaceDetector` returning 2 faces | `faceCount 2` |
| `faces == null` | `faceStatus NOT_RUN`, `faceCount -1` |
| `Codes` stub throwing `StackOverflowError` (inject via a package-private hook `VisualPass.qr`) | `qrStatus FAILED` |
| camera-area JPEG | not selected |
| second run, nothing changed | 0 files selected, 0 decodes |

### 6.2 `TextReader` interface (device boundary)

```java
package com.storageplanner.v2.content;
public interface TextReader {
    String id();                 // e.g. "tesseract.js-eng", "sidecar-sim", "none"
    int version();               // bump when engine/traineddata changes ⇒ re-OCR
    boolean available();         // false ⇒ OCR pass is skipped this run (files stay NOT_RUN)
    /** Reads one picture. argb is upright, long edge ≤ ContentConfig.ocrLongEdge. Never throws; errors come back as status FAILED. */
    OcrResult read(String volumeId, String path, int[] argb, int w, int h, long timeoutMs);
}
public final class OcrResult {
    public TextStatus status;    // READ (words valid), NO_TEXT, FAILED, UNAVAILABLE — the reader never decides PARTIAL/LOW (OcrPass does)
    public java.util.List<String> words = new java.util.ArrayList<String>();
    public java.util.List<Integer> conf = new java.util.ArrayList<Integer>();   // 0..100 per word
    public String error;
}
public final class UnavailableTextReader implements TextReader { id "none", version 0, available() false }
```

### 6.3 OCR pass (`OcrPass`, spec 14.1 tier 4 — never every image)

Selection (all hold): `visual.decodeStatus == OK`; usage ∈ {SCREENSHOT, CHAT_MEDIA, DOWNLOAD}; `text == null` or `versions[OCR] !=
current` or `text.engine/engineVersion` differ from the reader, or (`text.status == FAILED` and `text.attempts < 2`); and the
picture shows text: `visual.textBlobs ≥ 25 && visual.textArea ≥ 0.004` (classic `ImageSignals.S.wordy()`) **or** `visual.bigText ≥ 0.2`;
and the file is not a non-keeper member of an exact group whose edge to the keeper is VERIFIED (it is NO_LOSS already; its text is the
keeper's). Pictures that fail the "shows text" test get `text.status = NO_TEXT` **without** calling the reader (pixels found no text:
the pixel detector is the reader for "no text"). Order: as §6.1, then `fileId`.

If `!reader.available()`: the pass does nothing (no record written; files stay NOT_RUN; counted in `C_CONTENT_INCOMPLETE`).

Steps per file:
1. Decode again at `cfg.ocrLongEdge` with ARGB (`media.decode(…, 1600, 4_000_000, true)`); not OK ⇒ `status FAILED`, `attempts++`.
2. `OcrResult r = reader.read(vol, path, argb, w, h, cfg.ocrTimeoutMs)`.
3. `r.status == UNAVAILABLE` ⇒ stop the pass for this run (reader died); write nothing for this file.
   `r.status == FAILED` ⇒ `text.status FAILED`, `attempts++`, stamp; continue.
4. Build `TextRules.Words w` from `r.words/conf`; `goodWords = TextRules.goodWords(w)`; `words = r.words.size()`;
   `meanConf` = mean of `conf` over all words (NaN if none).
5. Readability: `expectedWords = max(1, visual.textBlobs / 5.0)`; `readRatio = goodWords / expectedWords` (cap 1.0).
   * `goodWords ≥ 3 && readRatio ≥ 0.6` ⇒ `READ`;
   * `goodWords ≥ 2 && readRatio ≥ 0.15` ⇒ `PARTIAL_SCRIPT` (some text read; the rest is present but unreadable — with an English-only
     engine that is how a Devanagari line shows: low-confidence garbage, spec 7.2);
   * `goodWords < 2` and pixels show text ⇒ `LOW_CONFIDENCE` (text "unknown", **never** "no text").
   Thresholds (5 blobs per word, 0.6, 0.15) are initial values, fitted in S9 on fold 0 (§11.3).
6. For READ/PARTIAL_SCRIPT: `TextRules.Verdict v = TextRules.classify(w, visual.aspect, photoLike, usage == SCREENSHOT,
   usage == CHAT_MEDIA, now)` with `photoLike = visual.colorful ≥ 40 && visual.flat < 0.85`; store `kind = v.kind`, `kindScore = v.score`,
   `textDate = v.date == 0 ? TIME : v.date`.
7. `TextFacts.scan(String cleaned = TextRules.clean(w))` → `classes`, `idPatterns`, `amounts`, `digitRuns4` (§6.5, §7.3).
8. `engine = reader.id()`, `engineVersion = reader.version()`, `engineVerified = ContentConfig.VERIFIED_ENGINES.contains(engine)`; stamp.
   The `Words` object and all strings are discarded here.

### 6.4 The vetoes (shared by every content Y rule; implemented once in `ContentFacts`, §9.1)

| Id | Fires when | Why |
|---|---|---|
| V-TEXT | `text.kind ∈ {PAYMENT, TICKET, ID, RECEIPT, BILL, DOCUMENT}`, or `classes` ∩ {PROTECT_PAY, PROTECT_ID, PROTECT_TRAVEL, PROTECT_MED, PROTECT_BANK} ≠ ∅, or `idPatterns ≥ 1` | record content |
| V-AMOUNT | `amounts ≥ 2`, or `amounts ≥ 1` and `classes` does not contain PROMO (a price on an advert is allowed) | bills, payments |
| V-QR | `visual.qrFormat != null` | tickets, boarding passes, UPI/Wi-Fi codes |
| V-FACES | `visual.faceCount ≥ 1` | people (spec 16.4 #1, #5) |
| V-CARD | `abs(a − 1.586) ≤ 0.06` or `abs(1/a − 1.586) ≤ 0.06`, `a = visual.aspect` | ID-1 cards (Aadhaar, PAN, licence, bank card); measured 1.586 on all 4 dataset ID images, memes 1.0, ads 0.8, screenshots 0.45, 3:2 photos 1.5 |
| V-PAGE | `abs(a − 0.707) ≤ 0.03` or `abs(a − 1.414) ≤ 0.04` with `visual.textBlobs ≥ 60` | photographed/scanned pages |
| V-APPNAME | screenshot name suffix app token (`Screenshot_<date>-<time>_<App>.<ext>`, also `_<pkg>`) contains any of `pay, upi, bank, wallet, gpay, phonepe, paytm, bhim, irctc, rail, train, flight, air, boarding, ticket, uber, ola, rapido, health, hospital, pharm, digilocker, aadhaar, umang, parivahan, insurance, bill` (lower-case substring) | payment/ticket apps (dataset: `_PaySpecimen`) |
| V-COMPANION | any `COMPANION_OF` edge on the file | multi-part / sidecar (deferred linker, §14) |
| V-DATE-RECENT | `text.textDate` known and within 30 days before `now` (standard) / 7 (aggressive) | a ticket/booking for the near future or past week |
| V-UNKNOWN | any required detector not OK: `visual.decodeStatus != OK`, `qrStatus != OK`, `faceStatus != OK`, `text.status ∉ {READ, PARTIAL_SCRIPT}`, or `!text.engineVerified` | protective detectors must actually have run (§9.4) |

`PARTIAL_SCRIPT` satisfies V-UNKNOWN **only** for a GREETING verdict (§9.2 rule F step 4) — §16 Q2.

### 6.5 `TextWords` (closed lists, lower-case, matched as whole tokens of `TextRules.clean` output; multi-word entries as substrings)

| Class | Words |
|---|---|
| PROTECT_PAY | paid, payment, received, debited, credited, transaction, txn, upi, utr, ref no, refund, transferred, amount, ₹, rs., inr, successful |
| PROTECT_BANK | bank, account, a/c, ifsc, statement, balance, cheque, loan, emi, policy, premium, insurance |
| PROTECT_ID | aadhaar, aadhar, uidai, pan, income tax department, passport, licence, license, voter, election commission, government, govt, republic, date of birth, dob, identity, permanent account |
| PROTECT_TRAVEL | ticket, pnr, booking, boarding, seat, coach, berth, flight, train, gate, departure, platform, e-ticket |
| PROTECT_MED | prescription, hospital, clinic, diagnosis, report, lab, patient, doctor, dr., vaccination, certificate |
| PROMO | sale, offer, off, discount, deal, cashback, coupon, buy, shop, order now, limited, free delivery, bachat, mega, flash |
| GREETING | happy, wishes, greetings, good morning, good night, diwali, holi, eid, mubarak, raksha, bandhan, ganpati, bappa, padwa, navratri, christmas, new year, shubh |
| OTP | otp, one time password, verification code, do not share |

`TextFacts.scan` also counts `idPatterns` with the patterns of §7.3 and `digitRuns4` = runs of ≥ 4 digits. Tests (`TextFactsTest`):
`"Paid ₹ 1,250 to Ravi UPI Ref 123456789012"` → classes ⊇ {PROTECT_PAY}, amounts 1; `"482913 is your OTP. Valid for 10 min. Do not
share."` → classes = {OTP}, amounts 0, idPatterns 0; `"MEGA SALE 50% OFF"` → {PROMO}; `"Happy Diwali"` → {GREETING};
`"ABCPE1234F"` → idPatterns 1 (PAN); `""` → classes `[]`, counts 0.

### 6.6 Image categories (`ImageCategorizer`, spec 5.2; S8) — display and evaluation only, never a removal input

`static Result categorize(FileContext ctx)`; `Result {String category; double confidence; String evidence}`; first match:

| # | Condition | Category | Conf |
|---|---|---|---|
| 1 | `visual == null` or `decodeStatus != OK` | `UNKNOWN` | NaN |
| 2 | V-TEXT with `kind == ID` or `idPatterns ≥ 1` or (V-CARD and `textBlobs ≥ 25`) | `DOCUMENT_PHOTO` (evidence "id") | 0.8 |
| 3 | `kind ∈ {RECEIPT, BILL}` | `RECEIPT` | 0.8 |
| 4 | `kind == DOCUMENT` or V-PAGE | `DOCUMENT_PHOTO` | 0.7 |
| 5 | usage SCREENSHOT | `SCREENSHOT` | 0.95 |
| 6 | `qrFormat != null` and `goodWords < 3` (or text unknown) | `QR_CODE` | 0.9 |
| 7 | `classes ∋ PROMO` (≥ 2 PROMO words) | `ADVERTISEMENT` | 0.7 |
| 8 | `kind == GREETING` or (`bigText ≥ 0.25 && textArea ≥ 0.03` and `faceCount == 0` and usage CHAT_MEDIA) | `GREETING_MEME` | 0.7 |
| 9 | `faceCount ≥ 1` | `PEOPLE` | 0.7 |
| 10 | `flat ≥ 0.4 && contrast ≥ 16 && colorful < 30` and EXIF camera make present | `SCREEN_PHOTO` | 0.5 |
| 11 | EXIF camera make present | `PERSONAL_PHOTO` | 0.5 |
| 12 | otherwise | `UNKNOWN` | NaN |

PETS, FOOD, PRODUCT, TRAVEL_SCENERY stay UNKNOWN until Phase 7 (they need a model). Written to `extra["category"]`,
`["category.confidence"]`, `["category.source"] = "CONTENT_RULES"` only when the existing value is null/UNKNOWN (never overwrite a
`PATH` category). Tests: one synthetic `FileContext` per row asserting the category; row order test (ID card with greeting text ⇒ row 2).

---

## 7. Documents (S3, S4, S5) — pure Java, fully JVM-testable

### 7.1 Selection and dispatch (DOC pass)

PRESENT; `type ∈ {pdf, docx, xlsx, pptx, odt, ods, odp, txt, csv}` or OLE2 magic `D0 CF 11 E0 A1 B1 1A E1` (`doc/xls/ppt`); size ≥ 1;
`versions[DOC_TEXT] != current`. Order: `fileId`. `size > cfg.docMaxBytes (16 MiB)` ⇒ `status TOO_LARGE` (unknown). Read the whole file
through `StorageAccess.open` into a `byte[]` (charge `budget.chargeBytes`). Dispatch: pdf → `PdfText`, OOXML → `OoxmlText`, ODF →
`OdfText`, txt/csv → `PlainText`, OLE2 → `Ole2`. Then `DocFacts.fill(doc, text, title)` (S4). Text strings are discarded after S4 facts.

### 7.2 `PdfText.extract(byte[] b, PdfInfo info) → Result {DocStatus status; StringBuilder text; int pages; int unsupported; String title, author}`

1. `info != null && info.encrypted == TRUE` ⇒ `ENCRYPTED`, return (spec 5.3: unknown, scenario 9).
2. Find streams: scan for the byte sequence `stream` followed by `\r\n` or `\n`; the stream dictionary is the last `<<`…`>>` (balanced,
   nested allowed) that ends ≤ 3 bytes before the keyword, searched backwards ≤ 8 KiB. Data end = `/Length N` if it is a direct integer
   and `start+N ≤ b.length`; else the next `endstream` (strip one trailing EOL).
3. Skip streams whose dictionary has `/Subtype /Image`, `/Type /XRef`, `/Type /Metadata` (read XMP separately, step 8),
   `/Subtype /Type1C`, `/FontFile`, `/Length1`.
4. Filters (`/Filter` name or array, applied in order): `ASCIIHexDecode`, `ASCII85Decode` (`<~` optional, `~>` end, `z` = 4 zeros),
   `FlateDecode` via `BoundedInflater.inflate(data, 8 << 20)`; `/DecodeParms` with `/Predictor ≥ 2` ⇒ skip stream. Any other filter
   (`LZWDecode, RunLengthDecode, DCTDecode, JPXDecode, JBIG2Decode, CCITTFaxDecode, Crypt`) ⇒ skip, `unsupported++`. Inflate failure ⇒
   skip, `unsupported++`. Running total of decoded bytes > 64 MiB ⇒ stop, `status LIMIT`.
5. `/Type /ObjStm` streams: decode and append their body to a list of "object text" scanned again by steps 6–7 (CMaps and Page
   objects may live there).
6. CMaps: a decoded stream containing `begincmap` → `PdfCMap.parse`: `begincodespacerange` (record max source-code byte length 1 or 2),
   `beginbfchar <src> <dst>` and `beginbfrange <lo> <hi> <dst>` / `<lo> <hi> [<d1> …]` with `dst` UTF-16BE. All CMaps of the file are
   merged into one map (first mapping wins; count conflicts). Conflicts > 10 % of entries ⇒ hex strings are not decoded (count as unsupported).
7. Content streams (decoded, not CMap, not image): tokenise; collect strings of operators `Tj`, `'`, `"`, and the string elements of
   `TJ` arrays (a number < −200 inside TJ inserts a space). Literal strings `( … )`: nested parens, escapes `\n \r \t \b \f \( \) \\`,
   octal `\ddd`, line continuation; bytes → chars by Windows-1252. Hex strings `< … >` (not `<<`): with a merged CMap and 2-byte
   codespace, decode 2-byte codes via the CMap; 1-byte via CMap if present else Windows-1252; codes missing from the CMap → skip.
   Operators `Td, TD, T*, Tm, ET, '` insert a newline. A stream whose decoded chars are > 30 % control characters (< 0x20 except
   whitespace) is dropped (Identity-H literal strings without a CMap).
8. Metadata: title/author = `PdfInfo.title/author` (Phase 2) else XMP `<dc:title>…<rdf:li …>X</rdf:li>` / `<dc:creator>` from a
   `/Type /Metadata` stream (uncompressed or Flate). Pages = `PdfInfo.pages` if ≥ 0 else count of `/Type /Page` (word boundary, not
   `/Pages`) in raw bytes + object-stream bodies.
9. Caps: text 200,000 chars (stop appending; status stays OK); streams 10,000.
10. Status: `chars ≥ 20` ⇒ `OK`; else pages ≥ 1 and the raw bytes contain `/Subtype /Image` (or `/Image`) ⇒ `NO_TEXT_LAYER`
    (scanned: unknown, needs OCR — not in Phase 6); else unsupported > 0 ⇒ `UNSUPPORTED`; else `CORRUPT` when no `%PDF-` header or no
    stream parsed, otherwise `OK` with `chars` as found (a PDF with only a few words).

Tests (`PdfTextTest`; PDFs built byte by byte in the test with `java.util.zip.Deflater` and a 20-line ASCII85 encoder in the test):

| # | Fixture | Expected |
|---|---|---|
| 1 | uncompressed page, `BT /F1 12 Tf 72 700 Td (Account Statement) Tj ET` | OK, text contains "Account Statement", pages 1 |
| 2 | same, Flate | same |
| 3 | same, `[/ASCII85Decode /FlateDecode]` (reportlab form) | same |
| 4 | `[(Ac) -250 (count)] TJ` | "Ac count" |
| 5 | escapes `(a\(b\)c\\d\101)` | `a(b)c\dA` |
| 6 | hex `<00480049>` + ToUnicode `begincodespacerange <0000> <FFFF>` `beginbfchar <0048> <0048> <0049> <0049>` | "HI" |
| 7 | 2-byte hex with no CMap | OK only if other text ≥ 20 chars; hex part ignored |
| 8 | `/Encrypt` present (PdfInfo.encrypted TRUE) | ENCRYPTED, chars −1 |
| 9 | only an image XObject | NO_TEXT_LAYER |
| 10 | Flate stream inflating to 9 MiB of zeros | stream skipped, unsupported 1, no OOM, < 1 s |
| 11 | object stream holding the Page object | pages 1 |
| 12 | `/Length 5 0 R` (indirect) | uses `endstream`, text found |
| 13 | truncated file (last 30 % cut) | no exception; status OK or CORRUPT |
| 14 | dataset: `Documents/Bank/Rohan_Statement_2019_07.pdf` (skipped if dataset absent) | OK, text contains "SPECIMEN BANK" |

### 7.3 OOXML, ODF, OLE2, plain text (`OoxmlText`, `OdfText`, `Ole2`, `PlainText`, `XmlText`)

* Zip access: use `ZipProbe`'s central-directory listing (Phase 2) for names, compressed size, local header offset; read the local
  header (30 bytes + name + extra), then `STORED` bytes or `BoundedInflater.inflate(comp, cap)`. Caps: per entry 16 MiB out, total
  32 MiB, entries read ≤ 300. Encrypted entry flag (bit 0) ⇒ `ENCRYPTED`.
* Entries: docx `word/document.xml` (+ `word/footnotes.xml` if present); xlsx `xl/sharedStrings.xml` (no sheets); pptx
  `ppt/slides/slide<N>.xml` for N = 1..200 in numeric order; ODF `content.xml`, metadata `meta.xml` (`dc:title`, `meta:initial-creator`,
  `meta:creation-date`, `meta:document-statistic meta:page-count`); OOXML metadata `docProps/core.xml` (`dc:title`, `dc:creator`,
  `dcterms:created`, `dcterms:modified` ISO-8601) and `docProps/app.xml` (`<Pages>`, `<Slides>`).
* `XmlText.text(byte[] xml, Set<String> textElements, Set<String> breakElements, int maxChars)`: a byte scanner (no DOM, no
  `javax.xml`): UTF-8 decode; skip `<?…?>`, `<!--…-->`, `<![CDATA[` (CDATA content counts as text inside a text element); character
  data inside `textElements` (`w:t`, `t`, `a:t`, `text:p`, `text:span`, `text:h`) is appended; end of `breakElements` (`w:p`, `a:p`,
  `si`, `text:p`) appends `'\n'`; entities `&amp; &lt; &gt; &quot; &apos; &#N; &#xH;` decoded, unknown entity kept literally;
  depth > 256 or malformed ⇒ stop and return what was collected (status stays OK if ≥ 20 chars, else CORRUPT).
* `Ole2`: magic match ⇒ read the directory sector chain minimally: if any directory entry name (UTF-16LE) equals `EncryptedPackage`
  or `EncryptionInfo` ⇒ `ENCRYPTED` (password-protected OOXML); else `format "ole2"`, `UNSUPPORTED` (legacy .doc/.xls text is not
  extracted; unknown). A malformed header ⇒ `CORRUPT`.
* `PlainText`: first 1 MiB; BOM `EF BB BF` UTF-8, `FF FE`/`FE FF` UTF-16; else valid UTF-8 ⇒ UTF-8; else Windows-1252. CSV is text.

Tests (`OoxmlTextTest`; fixtures zipped in the test with `java.util.zip.ZipOutputStream`): docx with two `w:p` → two lines, title/creator
from core.xml; xlsx sharedStrings with `&amp;` → "&"; pptx slides 10 and 2 → slide 2 text first; ODF `content.xml`; zip-bomb entry
(1 KB → 40 MiB) → `LIMIT`, < 1 s; OLE2 fixture with a directory entry `EncryptedPackage` → ENCRYPTED; plain UTF-16LE text with BOM →
decoded; dataset `Documents/Letter_to_Society_Secretary.docx` → OK, chars > 100.

### 7.4 Facts and categories (`DocFacts`, `DocCategorizer`, `Identifiers`, `DocKeywords`, `MinHash`; S4)

`DocFacts.fill(DocInfo d, CharSequence text, String title)`:
1. `chars`, `words` (split on whitespace), `latinShare` (letters in U+0041–024F / all letters), `devanagariShare` (U+0900–097F).
2. `Identifiers.find(text)` → `idKinds` (sorted, distinct): PAN `\b[A-Z]{5}[0-9]{4}[A-Z]\b`; AADHAAR `\b[2-9][0-9]{3} ?[0-9]{4} ?[0-9]{4}\b`
   **and** Verhoeff checksum valid; IFSC `\b[A-Z]{4}0[A-Z0-9]{6}\b`; GSTIN `\b[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]\b`; CARD
   `\b(?:[0-9]{4}[ -]?){3}[0-9]{4}\b` and Luhn valid; ACCOUNT `(?i)\b(?:a/c|account)\s*(?:no\.?|number)?\s*[:#]?\s*[X*0-9]{6,18}\b`;
   SSN `\b[0-9]{3}-[0-9]{2}-[0-9]{4}\b`; PASSPORT_IN `\b[A-PR-WY][1-9][0-9]{5}[1-9]\b`. Values are never stored.
3. `amounts` = count of `(?i)(?:₹|rs\.?|inr|\$|usd)\s?[0-9][0-9,]*(?:\.[0-9]{1,2})?` plus `\b[0-9]{1,3}(?:,[0-9]{2,3})+\.[0-9]{2}\b` (no
   double count of overlapping spans); `dates` = count of `TextRules`' three date shapes (re-declared as v2 patterns: `D_NUM`, `D_TXT`,
   `D_ISO`); `latestDate = TextRules.date(lowercased text, now)` (0 ⇒ unknown).
4. Category: `DocCategorizer.score(lowercased title + "\n" + text)` adds the weight of each **distinct** keyword hit (list below,
   English + transliterated + Devanagari, whole-token for single words, substring for multi-word) and of each `idKinds` element
   (+3 to the category in brackets). Highest score ≥ 3 wins; ties by the order of the table (protective first). Otherwise `UNKNOWN`.
5. `minhash` = `MinHash.of(text)`: 5-character shingles of the lower-cased, whitespace-collapsed text; 16 hash functions
   `h_i(s) = (a_i · fnv1a32(s) + b_i) mod 2^31−1` with fixed `a_i, b_i` (constants in the class); `null` when `chars < 200`.

| Category (order) | Weight-2 keywords (examples are complete lists in `DocKeywords`) | Id kinds (+3) |
|---|---|---|
| `DOC_LEGAL` (incl. identity) | passport, aadhaar, voter, licence, license, agreement, contract, deed, affidavit, court, lease, notary, stamp duty, registration, sale deed; translit: karar, khareedi khat, satbara, utara, malmatta; Devanagari: आधार, पॅन, पैन, मतदार, मतदाता, पासपोर्ट, परवाना, करार, अनुबंध, खरेदीखत, न्यायालय, प्रतिज्ञापत्र, सातबारा, उतारा, मालमत्ता, संपत्ति | PAN, AADHAAR, PASSPORT_IN, SSN |
| `DOC_FINANCIAL` | statement, bank, account, ifsc, balance, passbook, tax, itr, form 16, gst, salary, payslip, pay stub, loan, emi, insurance, policy, premium, mutual fund, demat, fixed deposit; translit: khata, bima, vima, aayakar, vetan, pagar, karj; Devanagari: बँक, बैंक, खाते, खाता, पासबुक, आयकर, वेतन, पगार, विमा, बीमा, कर्ज, ऋण, हप्ता, गुंतवणूक | IFSC, ACCOUNT, CARD, GSTIN |
| `DOC_MEDICAL` | prescription, hospital, diagnosis, discharge, patient, laboratory, lab report, vaccination, doctor; Devanagari: रुग्णालय, अस्पताल, दवाखाना, औषध, दवा, अहवाल, तपासणी, जांच, लसीकरण, टीकाकरण | — |
| `DOC_CERTIFICATE` | certificate, certified, marksheet, degree, diploma, birth, marriage, death; translit: pramanpatra, dakhla, janm, vivah, lagna; Devanagari: प्रमाणपत्र, दाखला, जन्म, विवाह, लग्न, मृत्यू, मृत्यु | — |
| `DOC_INVOICE` | invoice, tax invoice, bill to, gstin, hsn, due date | GSTIN |
| `DOC_RECEIPT` | receipt, received with thanks, paid, acknowledgement; translit: rasid, pavti, pavati; Devanagari: पावती, रसीद | — |
| `DOC_PERSONAL_RECORD` | resume, curriculum vitae, biodata, admission, application form | — |
| `DOC_EDUCATIONAL` | syllabus, chapter, exercise, lecture, assignment, school, university | — |
| `DOC_BUSINESS` | quarterly, revenue, minutes, agenda, proposal, quotation, purchase order | — |
| `DOC_MANUAL` | user manual, instructions, warranty, installation, troubleshooting | — |
| `DOC_TEMPORARY` | draft, test, sample, untitled, scratch (weight 1 only) | — |

Protective categories (`DocCategorizer.RECORD`): LEGAL, FINANCIAL, MEDICAL, CERTIFICATE, INVOICE, RECEIPT, PERSONAL_RECORD.
`DOC_TEMPORARY` is display-only and never lowers anything. Transliterated tokens of fewer than 4 letters are not used (they collide
with English). Why Devanagari lists are justified: Indian users type Hindi/Marathi file names (`आधार_कार्ड_नमूना.pdf` is in the
dataset); `Keywords.tokens` already splits on `_ - . space`, and NFC handles matras; a false hit only adds protection.

**Path keywords (`v2.risk.Keywords`, S4):** append the Devanagari and transliterated words above to `FINANCIAL`, `LEGAL`, `MEDICAL`, and
`SENTIMENTAL += लग्न, विवाह, वाढदिवस, जन्मदिन, आठवणी, यादें`. This changes Phase 4/5 results (expected: `Documents/आधार_कार्ड_नमूना.pdf`
and its sibling move KEEP → PROTECTED `P_UNIQUE_RECORD`; acceptable for both).

Tests (`DocFactsTest`): `"PAN: ABCPE1234F"` → idKinds [PAN], category LEGAL (score 3 ≥ 3); `"Aadhaar 2345 6789 0123"` with a Verhoeff-invalid
number → no AADHAAR; a valid one (compute in test) → [AADHAAR]; `"Account Statement … IFSC SPEC0001234 … Balance"` → FINANCIAL;
`"दिवाळीच्या हार्दिक शुभेच्छा"` → UNKNOWN, devanagariShare > 0.9; `"खाते उतारा बँक"` → FINANCIAL; `"draft"` → UNKNOWN (score 1 < 3);
`"Tax Invoice GSTIN 27ABCDE1234F1Z5"` → INVOICE (2+2+3 > FINANCIAL's "tax" 2); minhash of two texts differing in one word → ≥ 12/16 equal;
`chars < 200` → minhash null.

### 7.5 What document facts may do (and may not)

* Protect: `DocInfo.category ∈ RECORD` ⇒ `ContentFacts.recordCategory` (§9.1) ⇒ L factor raised and `P_UNIQUE_RECORD` candidate.
* **No Phase 6 rule makes any document a removal candidate** (spec 9.5: "a unique document cannot become an automatic deletion
  candidate merely because it is old"; spec 5.3: encrypted/unreadable stay unknown). `DOC_TEMPORARY` is shown, never acted on.
* `ENCRYPTED/NO_TEXT_LAYER/UNSUPPORTED/CORRUPT/LIMIT/TOO_LARGE/NOT_RUN` ⇒ category UNKNOWN ⇒ no effect (unknown never lowers).

### 7.6 Document version links (`v2.graph.DocVersionLinker`, S5)

1. Candidates: PRESENT documents with `doc.status == OK`, grouped by (`volumeId`, extension, `versionStem(name)`) where
   `versionStem` = `NameStem.of(name)` lower-cased, then remove, repeatedly, a trailing token matching
   `(?i)[ _-]*(v\d{1,3}|ver\d{1,3}|final|draft|rev\d{0,3}|old|new|copy|\(\d{1,2}\)|\d{1,2})$`. Groups of size 2..20 only.
2. Within a group, a pair (a, b) is linked iff both `minhash` known and `jaccard ≥ 0.5` (count of equal positions / 16), **or** one
   of them has `chars < 200` and the stems are equal and both are in the same directory (name-only evidence, `similarity = "name only"`).
3. Edge `VERSION_OF` from older to newer, UNVERIFIED, evidence `"DOC_STEM:" + stem + ";J=" + j`. Newer = later `modifiedAt` (DocInfo)
   else later mtime; a name with `final` outranks one without at equal day; tie → larger size; tie → fileId.
4. Store through the existing `Relationship` table (edge id as PHASE_3 `Edges`).
5. Rule (CandidateRules, S5): a file that is the older end of a `VERSION_OF` (DOC_STEM) edge to a PRESENT newer file gets
   `R_OLDER_VERSION` REVIEW/REVIEW with params `{newer, similarity}` (§9.5 catalogue). Never YOUR_CALL in Phase 6 (H2: VERSION_OF never
   justifies SAFE/NO_LOSS; documents get no Y rule). A protective P_ rule still wins (row 6 precedes row 10).

Tests: `Resume_Priya_v1.docx`, `_v2`, `_final`, `_final_FINAL` fixtures with shared text → stems equal "resume_priya"; edges
v1→v2→final→final_FINAL (chain by modifiedAt); jaccard of unrelated texts < 0.5 ⇒ no edge; group > 20 ⇒ no edges; dataset expectation:
`Documents/Resume_Priya_{v1,v2,final}.docx` become REVIEW `R_OLDER_VERSION` (expected REVIEW, currently KEEP).

---

## 8. Video (S2, S11)

### 8.1 Bitstream activity profile (`Mp4Tables`, `ActivityProfile`; pure Java; S2)

Why: a phone camera video's compressed non-key frames are tiny when nothing changes and spike when something moves. The sample-size
table (`stsz`) covers **every** frame of the whole recording without decoding anything, so scenario 8's 5–10 s events cannot fall
between sample points (§0.3).

`Mp4Tables.read(ReadableFile f, long maxMoovBytes = 16 MiB) → Tables` (per track: handler `vide`/`soun`, `mdhd` timescale + duration,
`stts` entries, `stsz` (constant size or table), `stss` (null = absent), `stsd` first codec fourcc). Reuse `Mp4Probe`'s box walk; read
the whole `moov` (≤ 16 MiB, else `TOO_LARGE`). `moof` present ⇒ `FRAGMENTED`. Sample count > 2,000,000 ⇒ `TOO_LARGE`. Malformed sizes
⇒ `CORRUPT`. Not an ISO-BMFF ⇒ `UNSUPPORTED`.

`ActivityProfile.video(Tables t, int width, int height) → fills ActivityInfo`:
1. No `vide` track ⇒ `videoStatus` stays `NOT_RUN` (audio-only file). No `stsz` ⇒ `NO_SAMPLE_TABLE`.
2. Sample times: `t_i` (ms) = Σ stts deltas before i × 1000 / timescale. `durationMs` = mdhd duration (else last t + last delta).
3. `stss == null` or every sample is a sync sample ⇒ `ALL_KEYFRAMES` (MJPEG-like: profile meaningless ⇒ unknown).
4. Windows of W = 1000 ms; `B_w` = Σ size of **non-key** samples with `t_i` in window w; `n` = number of windows with ≥ 1 non-key sample.
5. `n < 10` ⇒ `SHORT` (peaks still computed; `staticCandidate` UNKNOWN).
6. `M = median(B_w)`, `P = max(4·M, M + 2048)`; `peakThreshold = P`; peak windows = `B_w > P`; merge peak windows ≤ 2 windows apart
   into intervals `[startMs, endMs)`; keep the first 32 in `peaks`; `peakWindows` = count; `activeShare = peakWindows / n`.
7. `bppMedian = 8 · median(non-key sample sizes) / (width · height)` when width, height > 0 (from `VideoInfo`), else NaN.
8. `staticCandidate`: `FALSE` if `peakWindows ≥ 1`; `TRUE` iff `n ≥ 60` and `peakWindows == 0` and `bppMedian < 0.005` and the 99th
   percentile of `B_w` ≤ `2·M + 2048`; else `UNKNOWN`. (A constant-bitrate encoder fills static scenes with bits: high `bppMedian` ⇒ UNKNOWN,
   never TRUE — that is the case a flat profile cannot decide.)
9. `videoStatus = OK` (or SHORT).

Thresholds: 4×, +2048 B, 0.005 bpp are initial values from §0.3 (events ≥ 6.5 KB/s vs median ≤ 0.62 KB/s; static control 0.0018 bpp);
S2 records the dataset margins; real-phone recordings are a device-verification item (§12).

Effects: `peakWindows ≥ 1` ⇒ CONTENT signal `VIDEO_ACTIVITY` (+1, 0.6) ⇒ personal L factor +0.1 (`LossEstimator`) and veto V-ACTIVITY on
every video Y rule; it never lowers anything. `staticCandidate` alone authorises nothing (§8.3 needs decoded frames too).

Tests (`ActivityProfileTest`; MP4 built in test via the existing `Mp4ProbeTest` box writer, extended with `stsz`/`stss`/`stts`):

| # | Synthetic track | Expected |
|---|---|---|
| 1 | 600 s, 10 fps, key every 100, non-key 25 B, frames 4550–4650 = 800 B | 1 peak `[455000, 466000)` ±1 window, staticCandidate FALSE |
| 2 | 300 s, non-key 51 B, 640×360 | 0 peaks, bpp 0.00177, staticCandidate TRUE |
| 3 | 300 s CBR: every non-key 3000 B, 640×360 | 0 peaks, bpp 0.104, staticCandidate UNKNOWN |
| 4 | no `stss` | ALL_KEYFRAMES |
| 5 | 5 s clip | SHORT, staticCandidate UNKNOWN |
| 6 | `moof` box present | FRAGMENTED |
| 7 | `stsz` claims 3,000,000 samples | TOO_LARGE, no allocation > 16 MiB |
| 8 | 40 separate peaks | `peaks.length == 64` (32 intervals), `peakWindows == 40` |
| 9 | dataset S08 (skipped without dataset) | the 4 mnd videos: ≥ 1 interval overlapping 455–466 / 211–218 / 377–386 / 302–308 s; `VID_20210313_082140.mp4`: 0 peaks, TRUE |

### 8.2 Adaptive frame sampling (`FrameSampler`, stage `T6_DETAIL`; S11; decode device-only)

Selection: usage CAMERA_VIDEO (screen recordings under `DCIM/ScreenRecorder` excluded: path contains `screenrecord`); `activity.videoStatus ∈ {OK, SHORT}`;
`durationMs ≥ 60,000`; `versions[FRAMES] != current`; and the video is a **Y_STATIC_VIDEO prospect** (`staticCandidate == TRUE`) **or** has
`peakWindows ≥ 1` (frames there feed faces ⇒ protection). Order: `staticCandidate TRUE` first, then size descending, then fileId.

Frame source: `V2Device.media().videoFrame(vol, path, timeMs, 256)` (existing ARCH 3.2 interface; Android implementation in S10; JVM
`JvmMediaDecoder` gets an optional `DirFrameSource`, §11.2).

Plan (all times clamped to `[0, durationMs − 500]`, de-duplicated within 500 ms, processed in ascending time):
1. Initial: 0 %, 10 %, 25 %, 50 %, 75 %, 90 %, 100 % (spec 5.4).
2. Activity: for each peak interval (ordered by bytes, largest first): start, middle, end − 500.
3. Scene trigger: after decoding, for consecutive decoded frames with Hamming(phash) > 12, add the midpoint once (depth ≤ 2).
4. Face trigger: a frame with a face (detector on, ≤ 6 detector calls per video) ⇒ add t − 2000 and t + 2000.
5. Uncertainty trigger: if after steps 1–4 the decoded frames disagree on "nothing visible" (some `contrast < 10`, some not) ⇒ add 3
   frames evenly between the two disagreeing neighbours.
6. Stop when `framesPerVideo` (LOW 12 / BALANCED 24 / HIGH 40) or `framesPerRun` is reached; a video stopped by the run cap keeps
   `versions[FRAMES]` unstamped and its partial `FrameInfo` is **not** written (all-or-nothing per video ⇒ resumable).

Per frame: status OK ⇒ `phash = ImageFeatures.phash(luma, w, h)`; `contrast` (luma std-dev, as `ImageBasics`); `nothingVisible` if
`contrast < 10`. Summary: `diversity` = mean pairwise Hamming/64; `sceneChanges` = consecutive pairs with Hamming > 12;
`faceFrames`; `coversPeaks` = every peak interval contains ≥ 1 decoded frame; `status OK` iff `decoded ≥ 7` and `failed ≤ 2`, else
`FAILED`; `engineVerified = ContentConfig.VERIFIED_FRAME_SOURCES.contains(source id)` (`"dir-fixture"` on JVM; Android
`"mmr-26"` added only after §12 verification).

### 8.3 Video rule `Y_STATIC_VIDEO` (S11; subject to §16 Q3)

YOUR_CALL DELETE, families {CONTENT, PIXEL}, `needsFeatures = true`, all must hold: usage CAMERA_VIDEO; `durationMs ≥ 60 s`;
`activity.staticCandidate == TRUE` and `peakWindows == 0`; `frames.status == OK && engineVerified`; `frames.faceFrames == 0`;
(`frames.nothingVisible == frames.decoded`) **or** (`frames.diversity < 0.05` and `frames.sceneChanges == 0`); audio: `VideoInfo.audioTracks == 0`
or `activity.digitalSilence == TRUE` (a pocket recording may hold a conversation: unknown audio ⇒ not eligible); no exact/near group role
(keeper/member); not protected. Params `{duration, n}`. Standard and aggressive identical (no knob). Scenario 8 guarantee: an mnd video
with an event has `peakWindows ≥ 1` ⇒ ineligible **regardless of frames**; with frames unavailable the rule cannot fire at all.

---

## 9. Risk integration and the A28 rules (S8, S9)

### 9.1 `v2.risk.ContentFacts` (built in `ContextBuilder` after `usage`; field `FileContext.content`, never null)

```java
public final class ContentFacts {
    public String recordCategory;            // "payment confirmation", "ticket", "identity document", "receipt", "bill",
                                             // "financial record", "legal record", "medical record", "certificate", "invoice",
                                             // "personal record"; null = none known
    public java.util.List<String> recordEvidence = new java.util.ArrayList<String>();   // e.g. "text:PAYMENT", "doc:DOC_FINANCIAL", "id:PAN"
    public java.util.List<String> vetoes = new java.util.ArrayList<String>();           // §6.4 ids that fired, incl. V-UNKNOWN reasons
    public String disposableKind;            // OTP, ADVERT, GREETING, MEME, CHAT; null = none
    public boolean detectorsComplete;        // V-UNKNOWN did not fire
    public Tri videoActivity = Tri.UNKNOWN;  // TRUE peaks, FALSE staticCandidate TRUE, else UNKNOWN
    public static ContentFacts of(FileContext ctx, long now, PresentationPolicy pol) { ... }
}
```
Algorithm `of`:
1. Record (protect): images — `text.kind` PAYMENT → "payment confirmation", TICKET → "ticket", ID → "identity document",
   RECEIPT → "receipt", BILL → "bill"; else `idPatterns ≥ 1` → "identity document"; else classes ∋ PROTECT_PAY and `amounts ≥ 1` →
   "payment confirmation". Only from `text.status ∈ {READ, PARTIAL_SCRIPT}` (any engine, verified or not: protection is always allowed).
   Documents — `doc.category ∈ RECORD` → mapped name; `idKinds` non-empty → "identity document" if LEGAL else the category's name.
2. Vetoes: evaluate §6.4 in table order; append ids (V-UNKNOWN appends `V-UNKNOWN:<which>`).
3. `disposableKind` (only if `text.status == READ`, or `PARTIAL_SCRIPT` and GREETING per §9.2): `kind == OTP` or classes == {OTP} ⇒ OTP;
   ≥ 2 PROMO words (or "% off") ⇒ ADVERT; `kind == GREETING` or classes ∋ GREETING ⇒ GREETING; usage CHAT_MEDIA and `bigText ≥ 0.25`
   and `textArea ≥ 0.03` and `goodWords ≤ 40` and `kind == null` ⇒ MEME; usage SCREENSHOT and `kind == CHAT` ⇒ CHAT.
4. `videoActivity` from `activity`.

Effects wired in S8 (protect only):
* `SignalExtractor`: `recordCategory != null` ⇒ `Signal(CONTENT, "CONTENT_RECORD", +1, 0.9, "content:" + evidence0)`;
  `videoActivity == TRUE` ⇒ `Signal(CONTENT, "VIDEO_ACTIVITY", +1, 0.6, "activity")`. Disposable kinds produce **no** signal (they only
  enable candidates; U is never lowered by content, PHASE_4 §10).
* `LossEstimator.estimate`, after the path-keyword lines: payment/receipt/bill/invoice/financial ⇒ `f[1] = f[1].max(.9,.9,1)`;
  identity/legal/certificate/medical ⇒ `f[2] = f[2].max(.9,.9,1)`; ticket ⇒ `f[0] = f[0].max(.6,.7,.9)` and `f[1].max(.5,.6,.8)`;
  `VIDEO_ACTIVITY` ⇒ `f[0]` +0.1 (capped at 1), as CAPTURE does.
* `CandidateRules` `P_UNIQUE_RECORD`: condition becomes `(u == DOCUMENT_RECORD || (u == CHAT_BACKUP && …) || (ctx.content.recordCategory != null
  && !u.junk()))` and the rest unchanged (`lossRange.high ≥ 0.8`, not verified-backed-up, not a redundant member); `category` param =
  `recordCategory` when set; families `{CONTENT}` for content-only records; evidence `"content:" + …`.
  Row T then forbids transforms on them automatically (`lossRange.high ≥ 0.8`).
* `ImageCategorizer` output to `extra["category"]` (§6.6).

### 9.2 The three content Y rules (`v2.risk.ContentRules.candidates(ctx, a, pol)`, called from `CandidateRules.generate` before the P_ block; S9)

Common requirements (all three): action DELETE, tier YOUR_CALL, `needsFeatures = true`, `ctx.content.detectorsComplete`,
`ctx.content.vetoes.isEmpty()`, `ctx.content.recordCategory == null`, `ctx.content.disposableKind != null`, `ctx.keeperOf.isEmpty()`,
`f.protection == NONE`, type ∈ {jpeg, png}, `ctx.ageMs ≥ minAge` (ageSource CREATED or mtime fallback; unknown age ⇒ not eligible),
`fallbackCode = null` (dropped when blocked). Families listed per rule; TIME is never listed (H10).

| Rule | Usage | Allowed `disposableKind` (standard / aggressive) | minAge std / aggr | Families | Params |
|---|---|---|---|---|---|
| `Y_OLD_SCREENSHOT` | SCREENSHOT | OTP, ADVERT / + CHAT | 90 d / 30 d | CONTENT, PROV | `age`, `kind` ∈ {"a one-time code message", "an advertisement", "a chat conversation"} |
| `Y_FORWARDED_MEDIA` | CHAT_MEDIA (area ∈ wa_image, telegram_media) | GREETING, MEME, ADVERT / same | 30 d / 7 d | CONTENT, PROV, PIXEL | `kind` ∈ {"greeting", "meme", "advertisement"}, `app` ("WhatsApp"/"Telegram"), `signal` ("text written over the picture", "no people in it") |
| `Y_OLD_DOWNLOAD` | DOWNLOAD, image only | ADVERT, GREETING / same | 90 d / 30 d | CONTENT, PROV | `age`, `kind` |

Rule F step 4 (PARTIAL_SCRIPT greetings, §16 Q2): `Y_FORWARDED_MEDIA` with `disposableKind == GREETING` accepts `text.status ==
PARTIAL_SCRIPT` iff additionally `digitRuns4 == 0`, `bigText ≥ 0.25` and `colorful ≥ 50`; every other rule and kind needs `READ`.

Defence in depth — new hard rule **HC16** in `HardRules.check` (no mode input, H7): for a removal candidate whose `families` contain
`CONTENT`: `!ctx.content.vetoes.isEmpty()` or `ctx.content.recordCategory != null` ⇒ block `H_CONTENT_PROTECTIVE`; `!ctx.content.detectorsComplete`
⇒ block `H_CONTENT_NOT_CHECKED`. Add `"HC16"` to `HardRules.ORDER`. The existing HC7 (CONTENT/TIME alone never enough) and HC8
(`needsFeatures`) apply unchanged; HC12 is extended in S11/S12 only (§16 Q3).

`PresentationPolicy` gains (STANDARD / AGGRESSIVE): `screenshotMinAgeDays 90/30`, `forwardMinAgeDays 30/7`, `downloadMinAgeDays 90/30`,
`screenshotChatKind false/true`, `recentTextDateDays 30/7`. `HardRules`, vetoes and detector requirements are identical in both modes.

TierAssigner row 8 is unchanged (EL_pess ≤ tYourCall, C ≥ cYourCall). Check (S9 test): with the Phase 4 tables a SCREENSHOT has
`EL_pess ≈ 0.218 × 0.80 = 0.17`, CHAT_MEDIA ≈ 0.08, DOWNLOAD ≈ 0.08 (all ≤ 0.25) — if `anyUnknown` pushes a screenshot over 0.25 the
candidate is dropped, which is the safe direction; report the count.

### 9.3 User-facing sentences (ReasonCatalog; S5, S8, S9, S11, S12)

| Code | Tier | Sentence |
|---|---|---|
| `Y_OLD_SCREENSHOT` | YOUR_CALL | "Screenshot from {age} ago that looks like {kind}." (unchanged) |
| `Y_FORWARDED_MEDIA` | YOUR_CALL | "Looks like a forwarded {kind} received in {app} ({signal})." (parenthesis replaces "and"; same params) |
| `Y_OLD_DOWNLOAD` | YOUR_CALL | "Downloaded {age} ago; it looks like {kind}. Android does not tell us whether you opened it since." |
| `Y_STATIC_VIDEO` ★ | YOUR_CALL | "{duration} video in which nothing changes: {n} frames checked and no movement found anywhere in the recording." |
| `Y_SILENT_RECORDING` ★ | YOUR_CALL | "{duration} recording that contains only silence: no sound at all was recorded." |
| `R_OLDER_VERSION` ★ | REVIEW | "Earlier version of {newer} ({similarity}). Compare them before removing either." |
| `H_CONTENT_PROTECTIVE` ★ | (block) | "Its content looks important ({why}), so it is not offered." |
| `H_CONTENT_NOT_CHECKED` ★ | (block) | "Its content could not be fully checked on this device, so it is not offered." |
| `C_CONTENT_INCOMPLETE` ★ | — | "{n} pictures and documents have not been read yet, so nothing is suggested from their content; run the analysis again to continue." |

`P_UNIQUE_RECORD` is reused with `category` = `recordCategory` ("Only copy of what looks like a payment confirmation; …").

### 9.4 Device-only engines are never a precondition until verified

* OCR text from an engine not in `ContentConfig.VERIFIED_ENGINES` sets `engineVerified = false` ⇒ V-UNKNOWN fires ⇒ **no Y rule** —
  but its protective uses (record category, V-TEXT) still apply. On the phone `tesseract.js-eng` starts unverified; the Phase 6 Y rules
  therefore produce nothing on the phone until the owner's checklist (§12) passes and the lead adds the engine id (one-line change,
  separate commit). Same for frames (`VERIFIED_FRAME_SOURCES`).
* QR, faces, ImageSignals, PDF/OOXML text, MP4 tables and AAC frame sizes are the same Java code on both platforms: verified on the JVM.
  The only difference is the decoder (BitmapFactory vs ImageIO); `MediaDecoder`'s contract (upright, long edge, luma) is already tested.

### 9.5 Expected effect (dataset, standard mode; estimates to be replaced by the S9 measurement)

| Rule / kind | Reachable misses | Expected caught (sidecar OCR) |
|---|---|---|
| `Y_FORWARDED_MEDIA` MEME | 30 | ~26 (faces veto ~4) |
| `Y_FORWARDED_MEDIA` GREETING (PARTIAL_SCRIPT) | 59 | ~45–55 if Q2 = yes; 0 if no |
| ADVERT (WA / Download / Screenshot) | 33 | ~28 |
| `Y_OLD_SCREENSHOT` OTP | 15 | ~13 if Q1 = yes; 0 if no |
| `Y_STATIC_VIDEO`, `Y_SILENT_RECORDING` | 4 | 4 with fixtures if Q3 = yes |
| **Hit rate** (276 + caught) / 453 | | **≈ 0.86** with Q1–Q3 yes; **≈ 0.75** with Q2 no; **0.61 with no OCR** |

Exposure (Y on mnd): designed 0 — every mnd group of §0.2 is covered by ≥ 1 veto or by having no positive evidence or no rule for its
folder. The A27 label defect (`IMG-20240122-WA0001.jpg`, `Y_CHAT_COPY`) is not a Phase 6 code and stays the only exposure until fixed.
**0.8 is reachable on the desktop only with Q2 (or Q1+Q3 and near-perfect OCR).** On the phone the A28 rules add 0 until §12 passes.

---

## 10. Audio (spec 5.5; S12)

* `AudioProbe` (`v2.features`, tier 1, extractor id `AUDIO` = 9, version 1 → `AudioInfo`): MP3 (skip ID3v2; first frame header →
  sample rate, channels, bitrate; Xing/Info/VBRI frame count ⇒ duration, else CBR `(size − tags) × 8 / bitrate`, `durationConfidence` 0.5),
  FLAC (`STREAMINFO`: sample rate, channels, total samples ⇒ duration), Ogg (Vorbis/Opus id header: rate, channels; last page granule
  position within the last 64 KiB ⇒ duration; Opus granule at 48 kHz minus pre-skip), WAV (`fmt `: format, channels, rate; `data` size ⇒
  duration), AAC ADTS (first header). Read caps: head 64 KiB + tail 64 KiB (`Limits.audioBytes`). Malformed ⇒ fields unknown + problem.
* Digital silence (`AudioActivity`, in the ACTIVITY pass): AAC in MP4/M4A — `soun` track `stsz`: `tinyAudioShare` = share of frames ≤ 8 bytes;
  `digitalSilence = TRUE` iff ≥ 100 frames and share ≥ 0.995; `FALSE` iff share < 0.5; else UNKNOWN. PCM WAV (format 1, 16-bit) —
  read ≤ 4 MiB of `data` in 64 KiB blocks: TRUE iff every sample |x| ≤ 2; FALSE if > 1 % of samples |x| > 64; else UNKNOWN. All other codecs:
  UNKNOWN. **Speech activity, music activity and fingerprints are not computed (UNKNOWN) — they need a decoder.**
* Rule `Y_SILENT_RECORDING` (§16 Q3): YOUR_CALL DELETE, families {CONTENT, PROV}, usage VOICE, `digitalSilence == TRUE`, duration ≥ 2 s,
  no group role, not protected. "No speech/music" is never evidence (it is not computed); digital silence means the file holds no sound
  samples at all, which is the audio counterpart of `Y_LOW_QUALITY`'s "nothing visible".
* Tests: MP3 CBR/Xing fixtures, FLAC/Ogg/WAV headers (bytes built in test); AAC stsz with all 4-byte frames → TRUE, voice-like sizes → FALSE,
  mixed 60 % tiny → UNKNOWN; WAV zeros → TRUE, sine → FALSE; dataset: the 3 silent m4a → TRUE, 4 voice m4a → FALSE.

---

## 11. Evaluation (`tools/v2eval/evaluate.py --gate phase6`; S9 and later)

### 11.1 Desktop fixtures (labels never enter v2)

| Fixture | Built by | Content | Where |
|---|---|---|---|
| OCR truth | `tools/v2dataset/common.py`: a recorder wraps `PIL.ImageDraw.ImageDraw.text` during `build.py`; `save_image` writes `{path, sha256, lines:[{text, font_script}]}` per image | the strings the generator drew (content, not labels) | `/home/user/sp-dataset-text/ocr_truth.jsonl` (outside the scanned tree) |
| OCR simulation | `tools/v2eval/ocr_sim.py TRUTH OUT --seed 6` | English-only Tesseract model: Latin words kept with conf U[70,95], 15 % dropped, 5 % with one character changed (conf 50–69); every Devanagari word → 1–3 ASCII garbage tokens conf U[15,45]; seeded by sha256 | `/home/user/sp-dataset-text/ocr_sim.jsonl` (`{"path","size","sha256","engine":"sidecar-sim","version":1,"words":[[w,conf],…]}`) |
| Frames | `tools/v2eval/extract_frames.sh DATASET OUT` — `ffmpeg -v error -i IN -vf fps=1,scale=256:-2 OUT/<sha256(relpath)>/%06d.png` for every mp4 under DCIM, Movies | 1 frame per second | `/home/user/sp-dataset-frames/` |

The dataset is rebuilt into a scratch directory (`build.py --out <scratch>/rebuild`); entries whose sha256 differs from the live
manifest are dropped from `ocr_truth.jsonl` (count printed). Real (non-synthetic) images have no truth ⇒ the sidecar reader returns
`UNAVAILABLE` for them ⇒ they stay unknown (honest coverage, reported). `ocr_sim.py` reads only `ocr_truth.jsonl`, never the manifest;
`evaluate.py` asserts the sim file has no keys other than those listed.

`SidecarTextReader` (jvmtest): constructor reads the jsonl into `Map<relPath, entry>`; `read()` returns `READ` with the words when the
path and size match; a missing or size-mismatched entry returns `FAILED` with error `"no fixture"` (never `UNAVAILABLE`, which would
stop the whole pass); after 2 attempts such files are not retried until the sidecar version changes. `id() = "sidecar-sim"`, `version() = 1`, `available() = true`. `DirFrameSource`: `frame(path, timeMs)` loads
`<root>/<sha256hex(relPath)>/<k+1 zero-padded 6>.png` with `k = round(timeMs/1000)`; missing ⇒ `UNSUPPORTED_FORMAT`; source id `"dir-fixture"`.

`V2Cli` options (S7/S11): `--ocr-sidecar FILE`, `--frames-dir DIR`, `--face-model FILE`, `--ocr-budget N`, `--visual-budget N`,
`--frame-budget N`, `--content-dump FILE` (jsonl of every file's content sub-records + usage; no manifest data).

### 11.2 Runs

`tools/v2eval/p6_gate.sh OUT CLASSES` (copy of the lead's `gate.sh`): for mode in standard, aggressive: run A = `--max-tier 6
--ocr-sidecar … --frames-dir … --face-model …`; run N = `--max-tier 6` with no sidecar and no frames (`UnavailableTextReader`);
`evaluate.py --gate phase6 --result A --no-content N`.

### 11.3 Metrics and gates

| Metric | Definition | Gate |
|---|---|---|
| DDRR | Phase 5 definition, runs A and N, both modes | 0 |
| exposure | YOUR_CALL/any delete on mnd, A and N | 0 excluding the A27 file (listed in `KNOWN_LABEL_DEFECTS` with the ruling id); **0 strict for Phase 6 codes** |
| spec_violations | Phase 5 | 0 |
| no-content invariance | in N, count of recs with codes `Y_OLD_SCREENSHOT/Y_FORWARDED_MEDIA/Y_OLD_DOWNLOAD/Y_STATIC_VIDEO` | 0 |
| unverified invariance | run A with `--ocr-unverified` (engine id not in the verified set) | 0 of the same codes |
| YOUR_CALL hit rate | Phase 5 row "YOUR_CALL after cap", run A standard | ≥ 0.80 (if §16 Q2 = no: report, gate 0.75, lead to rule) |
| YOUR_CALL precision | Phase 5 | ≥ 0.95 (was 0.987) |
| category precision (fold 1) | per predicted class among SCREENSHOT, QR_CODE, GREETING_MEME, ADVERTISEMENT, RECEIPT, DOCUMENT_PHOTO; truth map: screenshot→SCREENSHOT, qr_code→QR_CODE, meme_greeting→GREETING_MEME, advertisement→ADVERTISEMENT, receipt→RECEIPT, id_document & document (images)→DOCUMENT_PHOTO | each ≥ 0.85; coverage (non-UNKNOWN share per manifest class) reported |
| protective recall | manifest `payment_confirmation`, `ticket`, `id_document`, `receipt` images that have a sim entry: share with `recordCategory != null` or a V-TEXT/V-QR/V-CARD veto | 1.00 for mnd, ≥ 0.9 others |
| documents | text-layer PDFs (manifest pdf, not encrypted, not scanned) with `doc.status OK`: ≥ 0.9; encrypted 5/5 ENCRYPTED & category UNKNOWN; scanned 11/11 NO_TEXT_LAYER; `financial_document` PDFs with status OK → category ∈ {FINANCIAL, INVOICE, RECEIPT}: ≥ 0.9 | as stated |
| video | 4 event videos: a peak interval overlaps the window parsed from the manifest `notes` ("activity only at A-Bs"): 4/4; control: 0 peaks | as stated |
| scenarios | Phase 5 rules | no FAIL; ≥ 8 PASS; S08 PASS in A (CAUTIOUS allowed in N) |
| budget convergence | S7-style chain, `--ocr-budget 40 --visual-budget 150` until no `C_CONTENT_INCOMPLETE` | final run equals unbudgeted (minus timings/decode counts) |

Calibration (S9): `tools/v2eval/calibrate_content.py --dump D --manifest M` reads the `--content-dump` of run A, fits on fold 0
(`fold_of(path)`, the evaluator's function) the thresholds marked "initial" in §6.3 (5, 0.6, 0.15), §6.4 (0.06, 0.03/0.04), §9.1 step 3
(0.25, 0.03, 40), §9.2 (50) with the objective: (1) zero eligible mnd and zero eligible protective-class files on fold 0 with each
threshold moved ≥ 2 grid steps away from the nearest violation; (2) maximise eligible expected-YOUR_CALL. Reports fold 1. The chosen
values are written as constants into `ContentRules`/`OcrPass` with a comment citing the fold-1 numbers.

---

## 12. What is JVM-testable and what only the phone can verify

| Feature | JVM (this container) | Fixture | Phone only |
|---|---|---|---|
| PDF/OOXML/ODF/TXT text, categories, ids, version links | fully | synthetic bytes + dataset | — |
| MP4/M4A activity profile, AAC silence, WAV silence, audio headers | fully | synthetic boxes + dataset (real H.264/AAC) | real phone recordings' noise floor and CBR behaviour (thresholds §8.1) |
| ImageSignals, QR, faces | fully (same code) | dataset images | WebP/HEIC chat images (decoder) |
| OCR pass logic, readability, vetoes, Y rules | fully, with the simulated sidecar | `ocr_sim.jsonl` | real Tesseract output (confidence scale, Devanagari garbage shape, speed) |
| Frame sampler logic | fully, with 1-fps PNG fixtures | `sp-dataset-frames` | `MediaMetadataRetriever` frame accuracy, timing, memory |
| Android bridges | compile only (`check_android_compile.sh`) | — | everything |

Owner's phone checklist (added to `PHASE_A_NOTES.md` in S10): (1) OCR starts and reads 20 chosen screenshots (5 payment, 5 OTP, 5 ads,
5 chat): kinds as expected, no crash, time per image recorded; (2) 10 Hindi/Marathi greetings: status PARTIAL_SCRIPT or LOW_CONFIDENCE,
never READ with a protective kind missing; (3) one 10-min static video and one with a 5-s event: activity peaks as expected; frames
decoded ≥ 7; (4) budget: run on the full phone and confirm `C_CONTENT_INCOMPLETE` then convergence over runs. Passing (1)–(2) is the
condition for adding `tesseract.js-eng` to `VERIFIED_ENGINES`; (3) for `mmr-26`.

---

## 13. Implementation plan — slices for Sonnet agents

Every slice ends with: `bash tools/v2eval/run_tests.sh` green; `bash tools/v2eval/check_android_compile.sh` green;
`bash tools/v2eval/p6_gate.sh` (from S9; before S9 the lead's `gate.sh`) and `python3 -I tools/v2eval/rec_diff.py BASE NEW` showing
**only** the changes the slice lists. `BASE` = the previous slice's result (S1 records the first one). Each new test class is added to
`AllV2Tests`. No slice edits `core/` or `app/OcrEngine.java`. ForbiddenApiTest must stay green (no `java.awt`, `javax.imageio`, `javax.xml`,
`java.net`, reflection, `File.delete` in v2).

### S1 — content model, codec, versions, diff tool (≈ 350 lines + tests)
Files: new `v2/model/{VisualInfo,TextInfo,DocInfo,ActivityInfo,FrameInfo,TextStatus,DocStatus,ActivityStatus,DetectorStatus}.java`,
`v2/store/ContentCodec.java`; edit `FeatureRecord` (fields, `EXTRACTORS = 25`, `copy()`), `Extractors` (ids 20–24, `COUNT`), `FeatureCodec`
(call `ContentCodec`), `FileContext` (accessors), `V2Json` (`content` object, category fields); new `tools/v2eval/rec_diff.py`
(joins two result JSONs by path; prints files whose predicted tier or primary reason changed; `--allow CODE,…` exit 0 if every change
involves an allowed code; `--expect-none` exit 1 on any change). Tests §4.5. Acceptance: dataset results identical (`rec_diff --expect-none`);
JSON gains no keys for files without content.

### S2 — MP4 tables and activity profile (≈ 400)
Files: `v2/content/{Mp4Tables,ActivityProfile}.java`; `ContentStage` skeleton with the ACTIVITY pass only (selection: type ∈ {mp4, mov, 3gp, m4a};
`versions[ACTIVITY]` stale); wiring (§5.1) behind `--max-tier ≥ 4`; `Limits.activityMoovMax = 16 MiB`. Algorithm §8.1. Tests §8.1 table
(+ `ContentStage` resume: kill after first commit ⇒ second run processes only the rest). Acceptance: default runs unchanged
(`--expect-none`); `--max-tier 4` run: activity for 25 videos, S08 numbers of test 9 printed by the test; recommendations unchanged
(`--expect-none` against the `--max-tier 3` run — S2 has no effect on rules).

### S3 — document text (≈ 600; may split S3a PDF / S3b OOXML+ODF+OLE2+TXT)
Files: `v2/content/doc/{PdfText,PdfCMap,OoxmlText,OdfText,Ole2,PlainText,XmlText}.java`; DOC pass in `ContentStage` filling the S3 fields
of `DocInfo` (§7.1–7.3). Tests §7.2, §7.3. Acceptance: recommendations unchanged; `--content-dump` shows ≥ 90 % of text-layer dataset
PDFs OK, 5/5 ENCRYPTED, 11/11 NO_TEXT_LAYER, all 13 OOXML OK.

### S4 — document facts, categories, Devanagari keywords, protective wiring for documents (≈ 450)
Files: `v2/content/doc/{DocFacts,DocCategorizer,Identifiers,DocKeywords,MinHash}.java`; `v2/risk/ContentFacts.java` (record part only,
§9.1 step 1 for documents); edits `Keywords` (§7.4 lists), `ContextBuilder` (build `ctx.content`), `SignalExtractor`, `LossEstimator`,
`CandidateRules` (`P_UNIQUE_RECORD` condition, §9.1). Tests §7.4 + `ContentFactsDocTest`: a DOCUMENT (not path-keyworded) with
`doc.category = DOC_FINANCIAL` ⇒ `P_UNIQUE_RECORD` PROTECTED with category "financial record"; same with `DOC_TEMPORARY` ⇒ unchanged;
`doc.status ENCRYPTED` ⇒ unchanged; `RiskPropertyTest` invariants still pass (content never lowers L/U). Acceptance (`rec_diff --allow
P_UNIQUE_RECORD`): only KEEP → PROTECTED changes, every one on a file whose manifest `acceptable_tiers` contains PROTECTED **or** listed
in the slice notes with its manifest category (expected: the two Devanagari-named ID/financial files; any `KEEP`-only file that turns
PROTECTED, e.g. `School_Admission_Form.docx`, is reported, not blocked); DDRR/exposure unchanged.

### S5 — document version links (≈ 300)
Files: `v2/graph/DocVersionLinker.java` (called at the end of `ContentStage` DOC pass, not in GRAPH — it needs DocInfo); `CandidateRules`
`R_OLDER_VERSION`; `ReasonCatalog`. Algorithm §7.6. Tests §7.6. Acceptance: `rec_diff --allow R_OLDER_VERSION`: exactly
`Documents/Resume_Priya_{v1,v2,final}.docx` KEEP → REVIEW (plus any other doc-stem group, each listed).

### S6 — visual pass (≈ 400)
Files: `v2/content/{VisualPass,FaceDetector,FaceResult,YunetFaceDetector,QrKind,ContentProviders,ContentConfig}.java`; VISUAL pass in
`ContentStage`; V2Cli `--face-model`, `--visual-budget`. Algorithm §6.1. Tests §6.1 + budget: `--visual-budget 50` ⇒ event
`C_CONTENT_INCOMPLETE`, re-runs converge. Acceptance: recommendations unchanged; dump shows QR on 12/12 ticket screenshots, faces on 6/6
WhatsApp family images, card aspect 1.586 on the 4 ID images.

### S7 — OCR interface and pass (≈ 450)
Files: `v2/content/{TextReader,OcrResult,UnavailableTextReader,OcrPass,TextFacts,TextWords}.java`; jvmtest `SidecarTextReader`;
`tools/v2dataset/common.py` recorder; `tools/v2eval/ocr_sim.py`; V2Cli `--ocr-sidecar`, `--ocr-budget`, `--ocr-unverified`,
`--content-dump`. Algorithm §6.3, §6.5. Tests: `TextFactsTest` (§6.5); `OcrPassTest` with a stub reader: (a) 30 good words on a picture with
120 blobs → READ (readRatio 1.0); (b1) 3 good + 40 garbage (conf 30) on 200 blobs → readRatio 0.075 → LOW_CONFIDENCE; (b2) 8 good +
30 garbage on 200 blobs → readRatio 0.2 → PARTIAL_SCRIPT; (b3) 1 good word on 200 blobs → LOW_CONFIDENCE (never NO_TEXT); (c) reader FAILED twice → `attempts 2`, not
selected a third time; (d) `available() false` → no records; (e) picture with 5 blobs → NO_TEXT without a reader call; (f) engine version
bump → re-selected; (g) `UNAVAILABLE` mid-pass → pass stops, earlier files kept. Acceptance: recommendations unchanged (no rule reads text
yet); dump: sim coverage count printed; OTP screenshots READ kind OTP ≥ 12/15.

### S8 — image categories and protective integration for images (≈ 350)
Files: `ImageCategorizer`; `ContentFacts` full (§9.1 steps 1–4, vetoes); `SignalExtractor`/`LossEstimator` image records; category to
`extra`. Tests §6.6 + `ContentFactsTest`: payment screenshot fixture (kind PAYMENT) ⇒ recordCategory "payment confirmation", V-TEXT;
ID card aspect 1.586 ⇒ V-CARD; `text.engineVerified false` ⇒ V-UNKNOWN but recordCategory still set; faces NOT_RUN ⇒ V-UNKNOWN:faces.
Acceptance (`rec_diff --allow P_UNIQUE_RECORD`): the 30 `payment_confirmation` screenshots KEEP → PROTECTED, ID/receipt images may move
to PROTECTED; nothing else; category precision table printed (§11.3).

### S9 — A28 rules, HC16, policy knobs, gate phase6, calibration (≈ 500)
Files: `v2/risk/ContentRules.java`; edits `CandidateRules` (call), `HardRules` (HC16), `PresentationPolicy`, `ReasonCatalog`, `V2Cli`
(default `--max-tier 4`); `tools/v2eval/{evaluate.py (phase6), p6_gate.sh, calibrate_content.py}`. Algorithm §9.2, §11. Tests:
`ContentRulesTest` — one positive fixture per rule/kind, and for each positive one negative per veto of §6.4 (11 vetoes × 3 rules, table
driven), `detectorsComplete false` ⇒ none, age unknown ⇒ none, PARTIAL_SCRIPT only for GREETING; `HardRulesTest` HC16 ± (both modes
identical); `RiskPropertyTest` extension: random content facts never yield SAFE/NO_LOSS with a CONTENT family, never Y with any veto;
`ReasonCatalogTest` params filled. Acceptance: `p6_gate.sh` exit 0 (all §11.3 rows); `rec_diff --allow Y_OLD_SCREENSHOT,Y_FORWARDED_MEDIA,Y_OLD_DOWNLOAD,P_UNIQUE_RECORD`
against S8; hit rate and the calibrated constants recorded in `PHASE_6_NOTES.md`.

### S10 — Android bridges (≈ 300; compile-checked only)
Files: `app/OcrTextReader.java` (package `com.storageplanner.app`: wraps a lazily started `OcrEngine` (`start(90000)` on first `read`;
failure ⇒ `available()` false for the rest of the run); converts ARGB → JPEG 92 like `AndroidPlatform.readText`; `read` honours
`timeoutMs`; id `"tesseract.js-eng"`, version = hash of the asset list (`eng.traineddata` size) & 0x7fffffff); `app/v2/AndroidContent.java`
(builds `ContentProviders` with `OcrTextReader` and `YunetFaceDetector` from `assets/faces/yunet.bin`; profile-based `ContentConfig`);
`AndroidV2Device.BitmapDecoder.videoFrame` via `MediaMetadataRetriever` (API 26: `getFrameAtTime(us, OPTION_CLOSEST_SYNC)` for the
initial 7 points, `OPTION_CLOSEST` otherwise; scale with `Bitmap.createScaledBitmap`; keep one retriever per path, released on path
change and in `close()`; source id `"mmr-26"`); `V2Analysis` adds the stages and `pendingContent`. No `getScaledFrameAtTime` (API 27).
Acceptance: `check_android_compile.sh` green; JVM results unchanged; phone checklist appended to `PHASE_A_NOTES.md`.

### S11 — frame sampler and `Y_STATIC_VIDEO` (≈ 450; rule only if §16 Q3 = yes)
Files: `v2/content/{FrameSampler,FrameStage}.java`; jvmtest `DirFrameSource` + `JvmMediaDecoder.videoFrame` using it;
`tools/v2eval/extract_frames.sh`; `ContentRules` video rule; `HardRules` HC12 exception list += `Y_STATIC_VIDEO`; V2Cli `--frames-dir`,
`--frame-budget`, default `--max-tier 6`. Algorithm §8.2–8.3. Tests: synthetic frame source (luma arrays) — static dark video ⇒ 7 frames,
nothingVisible 7, eligible; peak at 455 s ⇒ frames at 455/460/465 s planned, `coversPeaks`; scene change between 50 % and 75 % ⇒ midpoint
added; face at a frame ⇒ ±2 s added; per-video cap 12 respected; run cap mid-video ⇒ nothing written for that video, resumes next run;
source unverified ⇒ rule silent. Acceptance: `p6_gate.sh` green; `rec_diff --allow Y_STATIC_VIDEO`: only `DCIM/Camera/VID_20210313_082140.mp4`
KEEP → YOUR_CALL (if its frames qualify; else no change, reported); the 4 mnd S08 videos unchanged; S08 PASS in run A.

### S12 — audio probe, digital silence, `Y_SILENT_RECORDING` (≈ 450; rule only if §16 Q3 = yes)
Files: `v2/features/AudioProbe.java` (wired in `Extractors.probe` for mp3/flac/ogg/opus/wav/aac, `T1MetadataStage` dispatch);
`v2/content/AudioActivity.java` (ACTIVITY pass); rule in `ContentRules`; HC12 exception += `Y_SILENT_RECORDING`. Tests §10. Acceptance:
`rec_diff --allow Y_SILENT_RECORDING`: exactly the 3 `Recordings/Voice Recorder` silent m4a KEEP → YOUR_CALL; audio durations now known
for 31 files (JSON-only change, listed).

### S13 (optional) — faces on camera photos, `P_PERSONAL_MEDIA_UNIQUE` (≈ 250)
VISUAL selection += camera photos that are non-keeper members of a BURST/CONTENT_EQUIVALENT group or have `ImageInfo.contrast < 10`;
rule PHASE_4 §9.2 (`P_PERSONAL_MEDIA_UNIQUE`, PROTECTED, faces ≥ 1, no verified copy). Acceptance: only KEEP/YOUR_CALL/REVIEW → PROTECTED
changes on camera photos with faces; DDRR unchanged; S01 PASS count ≥ before.

Order rationale: S1–S5 are pure Java, fully testable, and protect only; S6–S8 add detectors and protection with no new removal;
S9 is the first slice that creates YOUR_CALL, after every veto it relies on exists and is measured; device bridges (S10) come after the
logic they feed; S11–S13 are lower value and depend on rulings.

---

## 14. Cut from Phase 6

| Item | Reason | Where it goes |
|---|---|---|
| MKV, AVI probes | 0 dataset files; no rule needs their fields; unknown is safe; the activity profile could later read Matroska `SimpleBlock` sizes | Phase 10, if device data shows MKV/AVI matter |
| TAR / tar.gz listing | needs a full gzip inflate of the file (no central directory); S10's tar.gz is already acceptable (KEEP); no rule gains | Phase 9/10 |
| ICO, GIF frame count | no rule reads them | — |
| `InstallerLinker` | installer rules use `ApkInfo.installState`; no consumer | — |
| CompanionLinker C1/C4 (sidecars `.xmp/.aae/.srt`, multi-part `.001/.partN`) | matters only when executing a removal (bundles); Phase 6 Y rules veto any file with `COMPANION_OF` instead | Phase 9 |
| `N_ARCHIVE_FOLDER` | new subject kind + folder executor | Phase 9 |
| Crops, collages | need local features/embeddings | Phase 7 |
| EDITED_FROM / VERSION_OF image recall | graph calibration (PHASE_3 D-14), not content; misses are on the safe side | a graph-calibration slice (lead) |
| Lossy COMPRESS, `N_OLDER_BACKUP` | need execution verification | Phase 9 |
| OCR of scanned PDF pages, video-frame OCR | device page rendering / OCR budget; no Phase 6 rule needs them (documents get no Y rule) | after §12 verification |
| Speech/music activity, audio fingerprint | need a decoder (spec 5.5 "unknown" is the honest value) | Phase 7+ |
| `Y_` rules for `pictures`, `camera` QR photos, photo-of-screen, pocket shots, junk archives | no positive content evidence that is safe enough, or not content at all (pocket shots are pixel quality) | Phase 7 / later tuning |
| A non-English traineddata (hin/mar) | asset size (~15–25 MB each), needs owner approval and device benchmarking | owner decision |
| Multi-threading | A11 | Phase 10 |

---

## 15. Design choices (with the rejected alternative)

| # | Choice | Rejected alternative | Why |
|---|---|---|---|
| C1 | Video motion from the **compressed frame-size table** over the whole recording; decoded frames only to confirm | dense frame decoding (every 2 s) | scenario 8's events are 5–10 s long; 7 + triggered samples can miss them, a full-coverage table cannot; zero decode cost; pure Java ⇒ JVM-verified on real H.264 |
| C2 | Store **no raw OCR/document text**, only derived facts and a MinHash | store the text for re-classification | spec 15 (extracted text is as sensitive as the file); store size (A21/A31); re-classification re-extracts via the version bump |
| C3 | Desktop OCR = generator-drawn text + an English-only degradation model | (a) install a desktop OCR engine; (b) fake text from labels | (a) blocked in this environment and a different engine than the phone's anyway; (b) leaks labels. (a)/(b) both overstate; the sim at least reproduces the script failure |
| C4 | Y rules need **every** protective detector complete (visual, QR, faces, verified OCR) | allow pixel-only eligibility when OCR is missing | pixel signals alone make the WhatsApp ID card (aspect 1.586, bigText 0.55) look like a meme; spec 7.2 regional text |
| C5 | Device-only engines are **unverified until the owner's checklist** passes; protection uses them immediately | enable Y rules on the phone at once | task rule: nothing device-only becomes a precondition for a deletion recommendation until verified; protection is the safe direction |
| C6 | Content providers passed to the stages (`ContentProviders`), not added to `V2Device` | add `text()` to `V2Device` (its comment says Phase 6 would) | avoids default methods/interface churn in the Android device class (P5 D-3 avoided them too) |
| C7 | No tier bits for T4/T6; versions only | `Tiers.T4/T6` bits | P13 would mark every file PARTIAL and change `extraction.status` for the whole dataset |
| C8 | Documents get categories and protection only, no Y rule | `DOC_TEMPORARY` ⇒ YOUR_CALL | spec 9.5; one dataset category (`docx REVIEW`) is better served by version links |
| C9 | Card/page aspect vetoes | rely on OCR ID words | IDs photographed with Devanagari text or OCR failure; aspect is free, script-independent and measured exact (1.586) |
| C10 | OCR bridge class in package `com.storageplanner.app` | widen `OcrEngine` visibility | leaves the classic class untouched (A2 spirit) |
| C11 | Version links in `ContentStage`, not `GRAPH` | add to GRAPH | needs `DocInfo` (text sketch), which exists only after the DOC pass |

---

## 16. Questions for the lead (each changes the expected result)

* **Q1 — OTP screenshots.** The task brief lists OTP among protective texts; the dataset labels 15 OTP screenshots YOUR_CALL with KEEP
  *not* acceptable, and OTPs expire within minutes. Proposal: OTP is a disposable kind **unless** any bank/payment/amount word or ID pattern
  is present (V-TEXT, V-AMOUNT). If ruled protective, drop OTP from §9.2 (−13 hits).
* **Q2 — mixed-script greetings.** Every dataset greeting has an English line and a Devanagari line; an English-only OCR reads only the
  first. Proposal (§9.2 rule F step 4): a GREETING verdict with PARTIAL_SCRIPT text is eligible when no digit run ≥ 4, big decorative text
  and colourful image, and every other veto passes. If ruled "non-Latin anywhere ⇒ unknown", 0.8 is not reachable (≈ 0.75).
* **Q3 — HC12 exceptions.** `Y_STATIC_VIDEO` and `Y_SILENT_RECORDING` remove personal captures on content evidence; HC12 allows that only
  for `Y_LOW_QUALITY` ("nothing visible"). Proposal: extend the exception to "nothing recorded" (static, no-audio/digitally-silent video with
  frames confirmed; digitally silent recording). Gain 4 hits.
* **Q4 — A27.** The exposure gate stays red until the dataset label of `IMG-20240122-WA0001.jpg` is fixed; Phase 6 assumes the
  `KNOWN_LABEL_DEFECTS` exemption of §11.3.
* **Q5 — default tier.** S9 turns `--max-tier 4` on by default in V2Cli and S10 in the app; OCR then costs ≈ 1–3 s per selected image on the
  phone (300 per run at BALANCED). Confirm the per-run caps.
