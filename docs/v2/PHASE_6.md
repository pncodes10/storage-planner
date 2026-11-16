# Phase 6 — content signals: OCR, document intelligence, adaptive video, audio

Status: **reviewed (REVIEW_6); rulings A39–A43 applied in place.** Author: Phase 6 architect. Implementers: Sonnet-class agents,
one slice at a time (§13 — start with the Implementer brief there).

> **REVIEW_6 summary of binding changes (read first).** Where older text below conflicts with a `REVIEW_6 F-…` tag, the tag wins.
> * A39 (F-M3): OTP is disposable unless bank/payment/amount text or an id pattern is present (vetoes V-TEXT/V-AMOUNT); test in S8.
> * A40 (F-B2): only `text.status == READ` (all text read, **no unreadable token**) can enable YOUR_CALL. `PARTIAL_SCRIPT` and
>   "rule F step 4" are removed from eligibility. Greetings with a Devanagari line are **not** eligible. Desktop hit-rate gate ≈ 0.75 (upper bound).
> * A41 (F-B3): no `Y_STATIC_VIDEO`, no `Y_SILENT_RECORDING`. Video activity (MP4 sample-size table) is extracted and used **only to
>   protect**. Frame sampling (old S11), `FrameInfo`, `T6_DETAIL`, the frame bridge and audio silence/`AudioProbe` (old S12) are cut.
> * A42 (F-B4): exposure gate is exactly 0, no `KNOWN_LABEL_DEFECTS` exemption.
> * A43 (F-B5): OCR verification is checked **live** at assessment by `ContentTrust.textEngineVerified(id)`; the Android engine id is
>   not in the verified set (defaults to unverified); a test proves that with it off no YOUR_CALL depends on OCR.
> * F-B1: T1 must carry content sub-records over when the file is unchanged, or every FULL scan erases them.
Binding inputs: spec §5.2–5.5, §7, §9.4–9.5, §14.1, §16.4; `MANAGER_LOG.md` (D1–D10, A1–A37); `ARCHITECTURE.md`;
`PHASE_2..5_NOTES.md`. Where this document departs from them it says so in §15 (design choices) and §16 (questions for the lead).

**One-paragraph summary.** Phase 6 adds *content facts* (what a picture, document, video or recording contains) and uses them in
exactly two directions: **protect** (raise loss severity, `P_UNIQUE_RECORD`, veto removals) and **make eligible** for YOUR_CALL the
three rules A28 deferred (`Y_OLD_SCREENSHOT`, `Y_FORWARDED_MEDIA`, `Y_OLD_DOWNLOAD`), only when positive disposable evidence exists,
no protective signal fires, and every protective detector actually ran. Nothing in Phase 6 creates SAFE or NO-LOSS. Pure-Java detectors
(classic `ImageSignals`, `Codes` (QR), `Faces`/`FaceNet` (YuNet), PDF/OOXML text, MP4 sample tables) run identically on the JVM and the
phone. OCR is device-only; on the desktop it is fed by a fixture derived from the text the generator drew (content), never from labels.
A content rule that needs OCR stays **off on the phone until the engine is verified** (§9.4, A43). (REVIEW_6 F-B3: video frame decoding
is not in Phase 6.)

---

## 0. Measurements this design rests on (dataset `/home/user/sp-dataset`, result of HEAD `v-i3`, `--now 1792000000000`)

Scripts: `<scratchpad>/p6/a2.py` (misses), `<scratchpad>/p6/m/Measure.java` (classic `ImageSignals` + `Codes` + `Faces` on 943 images,
long edge 1024). Numbers below are medians per (folder, manifest category).

### 0.1 YOUR_CALL misses (evaluator row "YOUR_CALL after cap": 276/453 = 0.609 hit, 177 miss)

| Manifest group (expected YOUR_CALL, KEEP not acceptable) | n | Folder | Signal that can catch it | Phase 6 rule |
|---|---|---|---|---|
| `meme_greeting/meme` | 30 | WhatsApp Images | text over picture (bigText 0.56, textArea 0.098), English caption, faces in 13 % | `Y_FORWARDED_MEDIA` (faces ⇒ vetoed: ~26 reachable) |
| `meme_greeting/greeting_*` (9 festivals) | 59 | WhatsApp Images | English greeting phrase + **Devanagari** line on every image (generator line 290–297); colourful 68–128 | **none** — A40: unreadable text ⇒ not eligible (REVIEW_6 F-B2) |
| `meme_greeting/unicode_name` | 4 | `Pictures/शुभेच्छा/` | as above, but `pictures` area | none (no rule for `pictures`; stays KEEP) |
| `advertisement` | 12+1 / 10 / 10 | WA / Download / Screenshots | "MEGA SALE", "FESTIVE OFFER", "FLASH DEAL" (gen_synth 360) | `Y_FORWARDED_MEDIA` / `Y_OLD_DOWNLOAD` / `Y_OLD_SCREENSHOT` |
| `screenshot/otp_sms` | 15 | Screenshots | "{code} is your OTP. Valid for 10 min." | `Y_OLD_SCREENSHOT` kind OTP (§16 Q1) |
| `unknown/accidental_pocket_shot` | 8 | Camera | flat 0.97, colourful 0.5 (pixel, not content) | none (not content; §14) |
| `backup/chat_db_rotation` | 4 | Databases | — | none (Phase 4 rule; not content) |
| `audio/silence` | 3 | Voice Recorder | every AAC frame is 4 bytes (digital silence) | none — A41 (REVIEW_6 F-B3) |
| `video/long_static_no_event` | 1 | Camera | no motion peaks, 0.0018 bits/pixel | none — A41; scenario S08 becomes CAUTIOUS, not FAIL |
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
  **REVIEW_6 re-check** (own `stsz`/`stts`/`stss` parser on the rebuilt dataset, §8.1 rule with W = 1 s, P = max(4M, M+2048)):
  peaks `[454,464]`, `[210,216]`, `[376,384]`, `[301,306]` s — 4/4 windows found; control 0 peaks (max 707 B vs P 2561 B). One video
  also shows a spurious first-second peak `[0,0]` (encoder start-up), harmless because peaks only protect. All five have B-frames
  (`ctts`), keyframes every 10 s (regular GOP), not fragmented.
* Audio: the three "silence" m4a files have every AAC frame = 4 bytes; the four voice recordings have median frames 261–655 bytes.
* PDFs: 64 of 92 dataset PDFs are reportlab `PDF-1.3` with `[/ASCII85Decode /FlateDecode]` content streams and literal `(…) Tj`
  strings; the Form 1099 / W-2 PDFs use plain Flate; paystubs use 2-byte hex glyph strings (`<0015>`) without `/ToUnicode` (text
  unrecoverable ⇒ unknown); 11 scanned PDFs are image-only (`/DCTDecode`); 5 are encrypted.
* No MKV, AVI, TAR (one `.tar.gz`), ICO or GIF file exists in the dataset.

---

## 1. Scope

**In Phase 6** (slices S1–S11, §13, REVIEW_6 renumbered): content-fact records and codec; MP4 bitstream activity profile (protect
only); PDF/OOXML/ODF/TXT/CSV text; document categories, identifier kinds, dates, amounts; Devanagari and transliterated path keywords;
document version links; visual pass (ImageSignals, QR, faces) on selected images; OCR provider interface, budgeted OCR pass;
image categories (output only); protective integration; `Y_OLD_SCREENSHOT`, `Y_FORWARDED_MEDIA`, `Y_OLD_DOWNLOAD`; HC16; evaluator
`--gate phase6`; Android OCR bridge; faces for `P_PERSONAL_MEDIA_UNIQUE` (optional last slice).
**Not in Phase 6 (A41, REVIEW_6 F-B3):** frame sampling, `Y_STATIC_VIDEO`, audio probe, digital silence, `Y_SILENT_RECORDING`.

**Cut from Phase 6** — see §14 (full list with reasons).

---

## 2. Deferred items collected from earlier phases, and the decision for each

| # | Item (source) | Decision | Slice / reason |
|---|---|---|---|
| 1 | `AudioProbe` mp3/flac/ogg/opus/wav (P2 notes slice 6, A11) | **Cut** (REVIEW_6 F-B3: no rule or protection reads it after A41; LEFT FOR LEAD L1) | §14 |
| 2 | Audio silence %, speech/music activity, fingerprint (ARCH §11, REVIEW_1 F-M9) | **Cut** (A41; nothing protective uses them) | §14 |
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
| 14 | `P_PERSONAL_MEDIA_UNIQUE` (faces; PHASE_4 §9.2) | **In, optional** | S11 (was S13) |
| 15 | Lossy COMPRESS at YOUR_CALL ("Phase 6+", PHASE_5 §1) | **Cut** (to Phase 9) | §14 |
| 16 | `N_OLDER_BACKUP` producer (PHASE_5 §3 priority 4) | **Cut** (to Phase 9) | §14 |
| 17 | OOXML/ODF docProps, OLE2/OOXML encryption detection (ARCH §11 row 6) | **In** | S3 |
| 18 | PDF object parser with object streams (PHASE_2 choice 2-7) | **In** (bounded text extraction, not a full parser) | S3 |
| 19 | OCR of scanned PDF pages (spec 5.3) | **Cut** (device page rendering; no Phase 6 rule needs it) | §14 |
| 20 | Video OCR / frame faces / adaptive frame sampling (spec 5.4) | **Cut** (A41, REVIEW_6 F-B3) | §14 |
| 21 | Multi-threading (A11) | **Not in this phase** (A11) | — |
| 22 | APK bundle base manifest (P2 P6) | **Not content**; stays with installers | — |

---

## 3. Package and class map (all new code; Java 8; no android.* under `v2`)

All paths under `app/src/main/java/com/storageplanner/` unless noted. "S#" = slice that creates it.

| Class | Package | S# | Purpose |
|---|---|---|---|
| `VisualInfo`, `TextInfo`, `DocInfo`, `ActivityInfo` | `v2.model` | S1 | content sub-records of `FeatureRecord` |
| `TextStatus`, `DocStatus`, `ActivityStatus`, `DetectorStatus` | `v2.model` | S1 | enums with explicit unknown values |
| `ContentCodec` | `v2.store` | S1 | JSON codec of the four sub-records (called from `FeatureCodec`) |
| `Mp4Tables`, `ActivityProfile` | `v2.content` | S2 | sample tables → `ActivityInfo` (protect only) |
| `PdfText`, `PdfCMap` | `v2.content.doc` | S3a | PDF text |
| `OoxmlText`, `OdfText`, `PlainText`, `XmlText`, `Ole2` | `v2.content.doc` | S3b | zip-XML / plain text, OLE2 encryption flag |
| `DocFacts`, `DocCategorizer`, `Identifiers`, `DocKeywords`, `MinHash` | `v2.content.doc` | S4 | categories, id kinds, dates, amounts, text sketch |
| `DocVersionLinker` | `v2.graph` | S5 | `VERSION_OF` between document versions |
| `VisualPass`, `FaceDetector`, `FaceResult`, `YunetFaceDetector`, `QrKind` | `v2.content` | S6 | ImageSignals + QR + faces |
| `TextReader`, `OcrResult`, `UnavailableTextReader`, `OcrPass`, `TextFacts`, `TextWords` | `v2.content` | S7 | OCR interface and pass |
| `ContentProviders`, `ContentConfig`, `ContentTrust`, `ContentStage` | `v2.content` | S2 (stage), S6/S7 | wiring, budgets, A43 trust, stage `T4_TEXT` |
| `ImageCategorizer` | `v2.content` | S8 | spec 5.2 categories (JSON output only) |
| `ContentFacts` | `v2.risk` | S4 (docs) / S8 (images, video) | per-file facts for the risk engine |
| `ContentRules` | `v2.risk` | S9 | the three Y candidates |
| `SidecarTextReader` | `jvmtest/.../v2test` | S7 | desktop OCR fixture |
| `OcrTextReader` | `com.storageplanner.app` | S10 | bridge to the package-private `OcrEngine` (no edit to it) |
| `AndroidContent` | `com.storageplanner.app.v2` | S10 | builds `ContentProviders` on the phone |

(REVIEW_6 F-B3: `FrameInfo`, `FrameSampler`, `FrameStage`, `DirFrameSource`, `AudioProbe`, `AudioActivity` removed.)
(REVIEW_6 F-m6: `v2.content` already holds `ContentClassifier`/`RuleClassifier`/`Classification` (PHASE_4 §10 plug point, path
keywords → `ctx.classification`). They stay unchanged; `ContentFacts` is the Phase 6 content channel and is read **in addition** to
`ctx.classification`. Do not delete or rename the existing classes.)

Edited existing classes (exact edits are in each slice): `FeatureRecord`, `Extractors`, `FeatureCodec`, `T1MetadataStage` (carry-over,
F-B1), `FileContext`, `ContextBuilder`, `SignalExtractor`, `LossEstimator`, `CandidateRules`, `HardRules`, `v2.plan.PresentationPolicy`,
`v2.plan.ReasonCatalog`, `Keywords`, jvmtest `V2Cli`, jvmtest `V2Json`, jvmtest `ForbiddenApiTest`, `app/V2Runner` (phone stage list —
REVIEW_6 F-M7: it is `V2Runner.runOpen`, not `V2Analysis`, that builds the phone's stages), `v2adapter/V2Analysis` (`pendingContent`
only), `tools/v2eval/evaluate.py`, `tools/v2dataset/common.py`.
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
* REVIEW_6 F-M8 (privacy): **not stored**: document title/author (used for scoring during extraction, then dropped), any text,
  identifier or amount *value*, QR payload. Dates found in text (`textDate`, `latestDate`) are stored **only** if they lie within
  400 days before the extraction time (a birth date on an ID card is never stored); otherwise the field stays unknown.
* REVIEW_6 F-m3: `Sub.copy()` is a shallow clone, so lists/arrays (`classes`, `idKinds`, `peaks`, `minhash`) are shared between
  copies. Rule: build a new sub-record and `put` it; never mutate a sub-record or its lists after it was read from the store.

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
// READ: all text read (no garbage token, §6.3 step 5); PARTIAL_SCRIPT: some text read, other text present but unreadable;
// NO_TEXT: pixels show no text (reader not called); LOW_CONFIDENCE: text present, reader could not read it;
// FAILED: reader error/timeout; UNAVAILABLE: no reader this run (never stored).
// REVIEW_6 F-B2 (A40): ONLY READ may enable YOUR_CALL. PARTIAL_SCRIPT and LOW_CONFIDENCE may still PROTECT (record category, V-TEXT).

public final class TextInfo extends Sub {                      // S7
    public TextStatus status = TextStatus.NOT_RUN;
    public String engine; public int engineVersion = -1;
    // REVIEW_6 F-B5: no stored "engineVerified"; verification is checked live by ContentTrust.textEngineVerified(engine) (§9.4)
    public int words = -1, goodWords = -1, garbage = -1;       // TextRules.goodWords; garbage per §6.3 step 5
    public double meanConf = Double.NaN, readRatio = Double.NaN;   // §6.3 step 5
    public String kind;                                        // TextRules verdict kind (PhotoCategory id) or null
    public int kindScore = -1;
    public java.util.List<String> classes;                     // TextWords classes hit: PROTECT_PAY, PROTECT_ID, PROTECT_TRAVEL, PROTECT_MED,
                                                               // PROTECT_BANK, PROMO, GREETING, OTP; null = unknown, [] = none
    public int idPatterns = -1, amounts = -1, digitRuns4 = -1;   // counts; values never stored
    public long textDate = com.storageplanner.v2.model.Unknown.TIME;   // stored only if within 400 d before extraction (F-M8)
    public int attempts = 0;                                   // failed attempts with this engine version
}

public enum DocStatus { NOT_RUN, OK, NO_TEXT_LAYER, ENCRYPTED, UNSUPPORTED, CORRUPT, LIMIT, TOO_LARGE }
public final class DocInfo extends Sub {                       // S3 (text facts) + S4 (category)
    public DocStatus status = DocStatus.NOT_RUN;
    public String format;                                      // pdf, docx, xlsx, pptx, odt, ods, odp, txt, csv, ole2
    // REVIEW_6 F-M8: no title/author fields (personal names); they feed DocCategorizer during extraction only
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
    public long latestDate = Unknown.TIME;                     // stored only if within 400 d before extraction (F-M8)
    public int[] minhash;                                      // 16 values, null = unknown (S4)
    public int unsupportedStreams = -1;                        // PDF streams with filters we do not decode
}

public enum ActivityStatus { NOT_RUN, OK, SHORT, NO_SAMPLE_TABLE, FRAGMENTED, ALL_KEYFRAMES, TOO_LARGE, CORRUPT, UNSUPPORTED }
public final class ActivityInfo extends Sub {                  // S2 (video only; REVIEW_6 F-B3 removed audio and static fields)
    public ActivityStatus videoStatus = ActivityStatus.NOT_RUN;
    public long durationMs = -1; public int videoSamples = -1;
    public double medianWindowBytes = Double.NaN, peakThreshold = Double.NaN;
    public int peakWindows = -1, irregularSync = -1;           // irregularSync: §8.1 step 4b
    public long[] peaks;                                       // [start0,end0,start1,end1,…] ms, ≤ 32 intervals; null = unknown
}
```
(REVIEW_6 F-B3: `FrameInfo`, `staticCandidate`, `bppMedian`, `activeShare`, audio fields removed — nothing reads them after A41.)

`FeatureRecord` gains `public VisualInfo visual; public TextInfo text; public DocInfo doc; public ActivityInfo activity;`
(copied in `copy()` like `pdf`). `FileContext` gains accessors `visual()`, `text()`, `doc()`, `activity()` (null-safe like `image()`:
`return feat == null ? null : feat.visual;`).

### 4.3 Extractor ids and versioning (`v2.features.Extractors`)

Add ids `VISUAL = 20, OCR = 21, DOC_TEXT = 22, ACTIVITY = 23`; `FeatureRecord.EXTRACTORS = 24` (REVIEW_6: FRAMES removed). Old snapshots hold
20 versions; `FeatureCodec.read` (line ~221) copies `k < v.size() && k < r.versions.length`, so the new ids read as 0 ("never run"). No tier bit is used
for T4/T6: `tiersDone/tiersPending` are **not** touched (P13 would turn every file PARTIAL and change `extraction.status` across the
board). A file is pending for pass *k* iff `versions[k] != Extractors.version(k)` and the pass's selection (§6–§8) includes it. OCR
additionally re-runs when `text.engine`/`text.engineVersion` differ from the current reader's (`TextReader.id()/version()`).

**REVIEW_6 F-B1 (BLOCKER) — carry-over in T1.** `T1MetadataStage.extract` builds a `new FeatureRecord()` for every selected file,
and with `ScanKind.FULL` (the default of `PipelineConfig`, of `V2Cli` and of the phone) **every** file is re-selected on every run
(`selected(...)`: `full && fr.extractedScanId < scanId`). Without a change every run would erase all content sub-records, OCR would
restart from zero each run and a budgeted run would never converge. Required edit (S1), in `T1MetadataStage.extract` right after
`FeatureRecord fr = new FeatureRecord(); fr.fileId = …; fr.fp = f.fingerprint();`:
```java
FeatureRecord old = ctx.store.features().get(f.fileId);
if (old != null && old.fp != null && old.fp.sameAs(fr.fp)) {          // same bytes: content facts still valid
    fr.visual = old.visual; fr.text = old.text; fr.doc = old.doc; fr.activity = old.activity;
    for (int k = Extractors.VISUAL; k <= Extractors.ACTIVITY; k++) fr.versions[k] = old.versions[k];
}
```
(A changed fingerprint drops them, so a modified file is re-read.) Test `ContentCarryOverTest`: run scan twice (FULL) on a fixture tree
with a stub content pass that counts calls ⇒ second run makes 0 calls and the sub-records are equal; touch one file (new mtime) ⇒
exactly 1 call.

### 4.4 Codec (`v2.store.ContentCodec`)

`static void write(JsonWriter w, VisualInfo|TextInfo|DocInfo|ActivityInfo x)` and `static X readX(Map<String,Object> m)`;
`FeatureCodec.write/read` call them under keys `"visual"`, `"text"`, `"doc"`, `"activity"`. **Omit** every field whose
value is its unknown value (keeps snapshot growth small, A21); a missing key reads as unknown. Arrays (`peaks`, `minhash`) as JSON
arrays of numbers. Enums by `name()`; an unknown enum name on read ⇒ the enum's `NOT_RUN` (never an exception). `V2Json` (jvmtest CLI
output) writes the same objects under `files[].content.{visual,text,doc,activity}` and, from S8, `files[].contentCategory`,
`contentCategoryConfidence` (REVIEW_6 F-M9: computed at output time by `ImageCategorizer`, not stored).
Schema safety: a store written by a newer codec and read by an older one ignores unknown keys (existing `Json` behaviour); a store
from before Phase 6 reads the new ids as version 0 ⇒ re-extracted. No store version bump is needed.

### 4.5 Tests (S1) — `ContentCodecTest` (jvmtest, registered in `AllV2Tests`)

| Case | Input | Expected |
|---|---|---|
| round trip | each sub-record with every field set to a non-default value | `read(write(x))` equals field by field |
| unknown omitted | fresh `new VisualInfo()` | written object is `{}`; read back gives `NaN`/`-1`/`NOT_RUN` |
| old snapshot | `FeatureRecord` JSON with a 20-element `versions` array | reads; `versions[20..23] == 0`; no exception |
| bad enum | `{"text":{"status":"BOGUS"}}` | `text.status == NOT_RUN` |
| no raw text | write a `DocInfo`/`TextInfo` with every field set | JSON contains no key `title`, `author`, `words` as string list, `text` string |
| copy independence | `fr.copy()` then mutate `copy.visual.faceCount` | original unchanged |
| JSON CLI | `V2Json` of a file with `text` set | contains `content.text.status`, no key named `words` holding strings |

---

## 5. Processing tiers, stages, budgets, resumability (spec 14.1; A11, A34)

### 5.1 Where Phase 6 runs

| Order | Stage id | Class | Passes (in this order) | Device-only parts |
|---|---|---|---|---|
| after `GRAPH` | `T4_TEXT` | `ContentStage` | 1 DOC (§7) · 2 ACTIVITY (§8.1) · 3 VISUAL (§6.1) · 4 OCR (§6.3) | OCR engine |
| then | `ASSESS`, `RECOMMEND`, `PLAN` | unchanged entry points | content facts read through `ContentFacts` (§9.1) | — |

Wiring (REVIEW_6 F-M7, exact places): the stage list is built in (a) jvmtest `V2Cli` (lines ~199–202: `T1…T3` then `GraphStage gs`),
(b) `app/V2Runner.runOpen` (`stages.add(new GraphStage())`, `cfg.maxTier = 3` at line ~116), (c) jvmtest `Fx`, `SampleTest`, `ScaleRun`.
In (a) and (b) only, directly after the `GraphStage` is added, append
`if (cfg.maxTier >= 4) stages.add(new ContentStage(providers, contentCfg));`. (c) stay without content (their tests are unchanged).
`V2Cli` default `--max-tier` stays 3 until S9 (so slices S1–S8 leave default results unchanged); S9 makes the default 4.
`V2Runner` sets `cfg.maxTier = 4` in S10. The quick sample run (A38) gets the content stage too (its 1,000 images are exactly what
OCR is for); its own store keeps the results separate.

### 5.2 `ContentProviders` and `ContentConfig`

```java
package com.storageplanner.v2.content;
public final class ContentProviders {
    public final TextReader text;          // never null: UnavailableTextReader when there is none
    public final FaceDetector faces;       // null = faces NOT_RUN (unknown)
    public ContentProviders(TextReader text, FaceDetector faces) { this.text = text == null ? new UnavailableTextReader() : text; this.faces = faces; }
}
public final class ContentConfig {
    public int visualPerRun = 2000, ocrPerRun = 300;            // BALANCED
    public long docMaxBytes = 16L << 20;          // whole-file read cap for PDF/plain text (zips are read entry by entry, §7.3)
    public long ocrTimeoutMs = 20000;
    public int ocrLongEdge = 1600; public long ocrMaxPixels = 4000000L;
    public int visualLongEdge = 1024; public long visualMaxPixels = 100000000L;
    public static ContentConfig forProfile(com.storageplanner.v2.device.DeviceProfile p) { ... }   // LOW: 500/100; HIGH: 5000/600
}

/** REVIEW_6 F-B5 (A43): the ONE place that says which OCR engines may enable YOUR_CALL. Read live at assessment (§9.1). */
public final class ContentTrust {
    private ContentTrust() {}
    /** Engine id of the phone's OCR (OcrTextReader.id() returns exactly this constant). */
    public static final String ANDROID_OCR_ENGINE = "tesseract.js-eng";
    /** Desktop fixture id; the jvmtest SidecarTextReader uses it unless --ocr-unverified, which makes it report "sidecar-sim-unverified". */
    public static final String DESKTOP_SIM_ENGINE = "sidecar-sim";
    /** false until the owner's device checklist (§12) passes; flipping it is a one-line lead commit. */
    public static final boolean ANDROID_OCR_VERIFIED = false;
    public static boolean textEngineVerified(String engineId) {
        if (engineId == null) return false;
        if (engineId.equals(DESKTOP_SIM_ENGINE)) return true;
        if (engineId.equals(ANDROID_OCR_ENGINE)) return ANDROID_OCR_VERIFIED;
        return false;                                   // any other engine (incl. "none", "sidecar-sim-unverified"): unverified
    }
}
```
Why live, not stored: a stored per-record flag keeps old records "verified" if the lead ever revokes an engine, and keeps them
"unverified" after it is verified until a re-OCR. A live check is correct in both directions. `"sidecar-sim"` cannot occur on the
phone (the reader exists only in jvmtest). Tests (`ContentTrustTest`, S7): `textEngineVerified(ANDROID_OCR_ENGINE) == false`;
`ANDROID_OCR_VERIFIED == false` (this assertion is changed by the same commit that flips it); `"sidecar-sim-unverified"`, `"none"`,
`null` ⇒ false.

### 5.3 Budgets and the S7 partial-result contract

* Each pass counts its own work against `ContentConfig.*PerRun`; reaching a cap ends **that pass for this run** and the stage
  continues with the next pass, then returns `DONE` (as S7's GRAPH does, A34), so ASSESS/PLAN always run and the result is valid.
* The pipeline budget (`StageContext.budget`: ms/bytes) and `ScanControl` pause/cancel are checked before every file; on stop the
  stage commits and returns `PAUSED`/`BUDGET_EXHAUSTED`/`CANCELLED` exactly like `ExtractionLoop`.
* Commit every 200 files or 3 s (`Clock`), in the same commit as the sub-records and their `versions[k]` stamp, so a kill re-selects
  exactly the unfinished files (ARCH 5.2).
* Files a pass did not reach keep `NOT_RUN` ⇒ every rule treats them as unknown ⇒ no Phase 6 Y candidate, no content protection
  either (protection is only *added*; its absence is the Phase 5 state). The stage writes one session event
  `C_CONTENT_INCOMPLETE` (detail `"ocr=N visual=M doc=D activity=A"`, pending counts at stage end; replaced each run; absent when all 0;
  implement exactly like `GraphStage.noteIncomplete`/`pendingImages`: remove the old event, add the new one).
  `V2Analysis.Result.pendingContent` (new `long` field) = sum of the four numbers (parse failure ⇒ 1); message with `{n}` = that sum: "{n} pictures and documents have not been read yet, so v2 does not suggest
  anything based on their content; run the analysis again to continue." (`ReasonCatalog` code `C_CONTENT_INCOMPLETE`.)
* Determinism: within a pass files are processed in the order of §6.1/§6.3/§7.1/§8.1 (keys end with `fileId`), so a budgeted chain
  converges to the unbudgeted result (test in S6 and S7, modelled on `PerceptualBudgetTest`; requires the T1 carry-over of §4.3).
* Time: never `System.currentTimeMillis/nanoTime` (ForbiddenApiTest); use `ctx.device.clock()`.
* Every pass catches `RuntimeException` and `OutOfMemoryError` per file: sub-record status `FAILED`, problem `content:<pass>:<Class>`,
  continue. A thrown `StoreException` propagates (stage FAILED), as in the other stages.

---

## 6. Images: visual pass, OCR, categories (S6, S7, S8)

### 6.1 VISUAL pass (`VisualPass`, pure Java, JVM == phone)

Selection (a file is selected iff all hold): PRESENT; `type ∈ {jpeg, png, webp, heic, heif}` and in `media().decodableTypes()`;
`versions[VISUAL] != current`; usage (`UsageClassifier.of` on a minimal `FileContext{f}`) ∈ {SCREENSHOT, CHAT_MEDIA, DOWNLOAD,
PICTURES} or `area == "document"`; not a `.thumbnails`/cache file. Camera photos are selected only by S11 (optional). Order: usage rank
(SCREENSHOT 0, CHAT_MEDIA 1, DOWNLOAD 2, PICTURES 3, document 4), then `sizeBytes` descending, then `fileId`.

Steps per file:
1. `DecodedImage d = ctx.device.media().decode(f.volumeId, f.accessPath(), cfg.visualLongEdge, cfg.visualMaxPixels, true)`; charge
   `ctx.budget.chargeDecode()`. If `d.luma == null` compute it from `d.argb` with `(299r+587g+114b)/1000`.
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
| 800×800 white image with a black QR for `upi://pay?pa=x@y` drawn from a module matrix (REVIEW_6 F-m1: zxing's *encoder* is not vendored; generate the matrix once with `python3 -I -c "import qrcode;q=qrcode.QRCode(border=0);q.add_data('upi://pay?pa=x@y');q.make();print('\n'.join(''.join('1' if c else '0' for c in r) for r in q.get_matrix()))"` and paste it as a `String[]` constant; 20 px per module, 4-module white border) | `qrStatus OK`, `qrKind "UPI"`, no field holds `x@y` |
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
picture shows text: `visual.textBlobs ≥ 25 && visual.textArea ≥ 0.004` (classic `ImageSignals.S.wordy()`) **or** `visual.bigText ≥ 0.2`.
(REVIEW_6 F-m7: the old "not a verified exact copy" exclusion is cut — the stage has no graph view; it only saved time.) Pictures that fail the "shows text" test get `text.status = NO_TEXT` **without** calling the reader (pixels found no text:
the pixel detector is the reader for "no text"). Order: as §6.1, then `fileId`.

If `!reader.available()`: the pass does nothing (no record written; files stay NOT_RUN; counted in `C_CONTENT_INCOMPLETE`).

Steps per file:
1. Decode again at `cfg.ocrLongEdge` with ARGB (`media.decode(…, 1600, 4_000_000, true)`); not OK ⇒ `status FAILED`, `attempts++`.
2. `OcrResult r = reader.read(vol, path, argb, w, h, cfg.ocrTimeoutMs)`.
3. `r == null` or `r.status == FAILED` ⇒ `text.status FAILED`, `attempts++`, stamp; continue.
   `r.status == UNAVAILABLE` ⇒ stop the pass for this run (reader died); write nothing for this file.
   REVIEW_6 F-M2: `r.status == NO_TEXT`, or `READ` with an empty word list, is treated as `READ` with zero words: the pixels say there is
   text (selection), so step 5 yields `LOW_CONFIDENCE` — an empty or timed-out OCR is **never** "no text".
4. Build `TextRules.Words w` (`w.add(word, conf)` for each pair); `goodWords = TextRules.goodWords(w)`; `words = r.words.size()`;
   `meanConf` = mean of `conf` over all words (NaN if none);
   `garbage` = number of words with `conf < 50`, **or** of length ≥ 2 with fewer than 2/3 letters/digits, **or** containing a character
   outside U+0020–U+024F and outside {`₹`, `’`, `“`, `”`, `–`, `—`, `•`} (REVIEW_6 F-B2).
5. Readability: `expectedWords = max(1, visual.textBlobs / 5.0)`; `readRatio = min(1, goodWords / expectedWords)`.
   * `garbage == 0 && goodWords ≥ 3 && readRatio ≥ 0.6` ⇒ `READ` (REVIEW_6 F-B2, A40: all visible text was read);
   * else `goodWords ≥ 2` ⇒ `PARTIAL_SCRIPT` (some text read, some unreadable — e.g. a Devanagari line with the English-only engine,
     a handwritten line, a stylised word): **protective uses only**;
   * else ⇒ `LOW_CONFIDENCE` (text "unknown", **never** "no text").
   Thresholds are fixed constants in `OcrPass` (`BLOBS_PER_WORD = 5`, `READ_RATIO = 0.6`, `GARBAGE_CONF = 50`); REVIEW_6 F-M13: no
   automatic calibration — §11.3 reports fold-1 numbers only, and any change is a lead decision.
6. For READ/PARTIAL_SCRIPT: `TextRules.Verdict v = TextRules.classify(w, visual.aspect, photoLike, usage == SCREENSHOT,
   usage == CHAT_MEDIA, now)` with `photoLike = visual.colorful ≥ 40 && visual.flat < 0.85`; store `kind = v.kind`, `kindScore = v.score`,
   `textDate = v.date == 0 ? TIME : v.date`, then apply the 400-day rule of §4.1 (date older than `now − 400 d` ⇒ `TIME`).
7. For every status with words (READ, PARTIAL_SCRIPT, LOW_CONFIDENCE): `TextFacts.scan(TextRules.clean(w))` → `classes`, `idPatterns`
   (PAN/AADHAAR/IFSC/GSTIN/CARD/SSN/PASSPORT_IN patterns of §7.4 step 2), `amounts` (the pattern of **§7.4 step 3** — REVIEW_6 F-M3
   corrected reference), `digitRuns4`. (Protective facts are taken even from partly read text.)
8. `engine = reader.id()`, `engineVersion = reader.version()`; stamp. (No verification flag is stored — §5.2 `ContentTrust`.)
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
| V-DATE-RECENT | `text.textDate` known and `now − 30 d ≤ textDate` (future dates included); **same in both modes** (REVIEW_6 F-M10) | a ticket/booking for the near future or past month |

**"Check not finished" (REVIEW_6 F-M1: kept in a separate list `ContentFacts.unknowns`, not in `vetoes`).** U-ids, each appended when:
`U-VISUAL` `visual == null || visual.decodeStatus != OK`; `U-QR` `visual.qrStatus != OK`; `U-FACES` `visual.faceStatus != OK`;
`U-TEXT` `text == null || text.status != READ` (A40: PARTIAL_SCRIPT/LOW_CONFIDENCE/FAILED/NO_TEXT/NOT_RUN all count);
`U-ENGINE` `!ContentTrust.textEngineVerified(text.engine)` (A43). `detectorsComplete = unknowns.isEmpty()`.

How the brief's adversarial cases are stopped (each has a test row in S8/S9):

| Case | Stopped by |
|---|---|
| Hindi bank-transfer screenshot | Devanagari ⇒ garbage tokens ⇒ not READ ⇒ U-TEXT; also V-AMOUNT (₹ amount), V-APPNAME if the app token is a pay app |
| boarding-pass screenshot whose QR did not decode | V-TEXT PROTECT_TRAVEL ("boarding", "flight", "gate", "seat"); if text unreadable ⇒ U-TEXT; no disposable kind anyway |
| WhatsApp-forwarded photo of a relative's ID | V-CARD (cropped card), V-TEXT PROTECT_ID ("government", "dob"), V-FACES (photo on card); Hindi-only card ⇒ U-TEXT |
| "download" that is the only copy of a signed PDF | no Phase 6 rule touches documents (§7.5); `Y_OLD_DOWNLOAD` is jpeg/png only |
| meme-shaped image that is a handwritten note | handwriting ⇒ low-confidence/garbage tokens ⇒ not READ ⇒ U-TEXT |
| OCR timed out / empty / low confidence | FAILED or LOW_CONFIDENCE (§6.3 steps 3, 5) ⇒ U-TEXT |
| OTP with bank/payment/amount text (A39) | V-TEXT (PROTECT_BANK/PAY) or V-AMOUNT |
| phone, OCR not yet verified (A43) | U-ENGINE |

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

Matching (REVIEW_6 F-m10): tokens = `text.split("[\\s,.:;!?()\\[\\]/|\"']+")`; single-word entries match a whole token; entries
containing a space or a non-letter (`ref no`, `rs.`, `a/c`, `₹`, `dr.`) match as substrings of the cleaned text.
`TextFacts.scan` also counts `idPatterns` with the patterns of §7.3 and `digitRuns4` = runs of ≥ 4 digits. Tests (`TextFactsTest`):
`"Paid ₹ 1,250 to Ravi UPI Ref 123456789012"` → classes ⊇ {PROTECT_PAY}, amounts 1; `"482913 is your OTP. Valid for 10 min. Do not
share."` → classes = {OTP}, amounts 0, idPatterns 0; `"MEGA SALE 50% OFF"` → {PROMO}; `"Happy Diwali"` → {GREETING};
`"ABCPE1234F"` → idPatterns 1 (PAN); `""` → classes `[]`, counts 0; (A39) `"482913 is your OTP for a payment of Rs. 5,000 at Specimen
Bank"` → classes ⊇ {OTP, PROTECT_BANK}, amounts 1.

### 6.6 Image categories (`ImageCategorizer`, spec 5.2; S8) — display and evaluation only, never a removal input

`static Result categorize(FileContext ctx)` (needs only `ctx.f`, `ctx.feat`, `ctx.usage`; where `V2Json` has no context it builds one:
`FileContext c = new FileContext(); c.f = f; c.feat = feat; c.usage = UsageClassifier.of(c);`); `Result {String category; double
confidence; String evidence}`; "V-…" conditions below mean the §6.4 formulas evaluated on the facts (no unknowns check); first match:

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

PETS, FOOD, PRODUCT, TRAVEL_SCENERY stay UNKNOWN until Phase 7 (they need a model). REVIEW_6 F-M9: **not stored** (there is no
"PATH category" to protect, and `FileRecord.category` is owned by discovery); jvmtest `V2Json` calls `ImageCategorizer.categorize(ctx)`
when writing each file and emits `contentCategory`, `contentCategoryConfidence`. No rule reads it. Tests: one synthetic `FileContext` per row asserting the category; row order test (ID card with greeting text ⇒ row 2).

---

## 7. Documents (S3, S4, S5) — pure Java, fully JVM-testable

### 7.1 Selection and dispatch (DOC pass)

PRESENT; `type ∈ {pdf, docx, xlsx, pptx, odt, ods, odp, txt, csv}` or OLE2 magic `D0 CF 11 E0 A1 B1 1A E1` (`doc/xls/ppt`); size ≥ 1;
`versions[DOC_TEXT] != current`. Order: `fileId`. PDF / txt / csv: `size > cfg.docMaxBytes (16 MiB)` ⇒ `status TOO_LARGE` (unknown);
else read the whole file through `ctx.device.storage().open(f.volumeId, f.accessPath())` into a `byte[]` (charge `budget.chargeBytes`).
OOXML/ODF: no whole-file read — `ReadableFile` + `ZipProbe` (§7.3). OLE2: first 4 MiB only (§7.3). Dispatch: pdf → `PdfText`,
OOXML → `OoxmlText`, ODF → `OdfText`, txt/csv → `PlainText`, OLE2 → `Ole2`. Any `IOException` ⇒ `CORRUPT`; any `RuntimeException`/
`OutOfMemoryError` ⇒ `CORRUPT` + problem (§5.3). Then `DocFacts.fill(doc, text, title)` (S4; before S4 the DOC pass fills only the
§7.2/§7.3 fields). Text strings, title and author are discarded after `fill`.

### 7.2 `PdfText.extract(byte[] b, PdfInfo info) → Result {DocStatus status; StringBuilder text; int pages; int unsupported; String title, author}`

1. `info != null && info.encrypted == TRUE` ⇒ `ENCRYPTED`, return (spec 5.3: unknown, scenario 9).
2. Find streams: scan for the byte sequence `stream` followed by `\r\n` or `\n` and **not** preceded by `end` (REVIEW_6 F-m4); the stream dictionary is the last `<<`…`>>` (balanced,
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

* Zip access (REVIEW_6 F-M6 — reuse, do not re-implement): `ZipProbe.Listing l = ZipProbe.list(rf, size, Limits)` (existing, Phase 2)
  gives `l.entries` (`ArchiveEntry`: `path`, `size`, `compressedSize`, `localOffset`, `encrypted` (Tri)); read one entry with the
  existing `ZipProbe.read(rf, size, entry, maxOut = 4 MiB, maxRatio = 200)`, which returns **null** for an oversized/bad entry (⇒ `LIMIT`
  if `entry.size > 4 MiB`, else skip with `unsupported++`). `l.info.listingStatus != "OK"` ⇒ `CORRUPT`. Caps: per entry 4 MiB
  out, total 32 MiB, entries read ≤ 300; cap hit ⇒ `LIMIT` (unknown). An entry with `encrypted == Tri.TRUE` ⇒
  `ENCRYPTED`. An `IOException` from `read` ⇒ skip entry, `unsupported++`.
* Entries: docx `word/document.xml` (+ `word/footnotes.xml` if present); xlsx `xl/sharedStrings.xml` (no sheets); pptx
  `ppt/slides/slide<N>.xml` for N = 1..200 in numeric order; ODF `content.xml`, metadata `meta.xml` (`dc:title`, `meta:initial-creator`,
  `meta:creation-date`, `meta:document-statistic meta:page-count`); OOXML metadata `docProps/core.xml` (`dc:title`, `dc:creator`,
  `dcterms:created`, `dcterms:modified` ISO-8601) and `docProps/app.xml` (`<Pages>`, `<Slides>`).
* `XmlText.text(byte[] xml, Set<String> textElements, Set<String> breakElements, int maxChars)`: a byte scanner (no DOM, no
  `javax.xml`): UTF-8 decode; skip `<?…?>`, `<!--…-->`, `<![CDATA[` (CDATA content counts as text inside a text element); character
  data inside `textElements` (`w:t`, `t`, `a:t`, `text:p`, `text:span`, `text:h`) is appended; end of `breakElements` (`w:p`, `a:p`,
  `si`, `text:p`) appends `'\n'`; entities `&amp; &lt; &gt; &quot; &apos; &#N; &#xH;` decoded, unknown entity kept literally;
  depth > 256 or malformed ⇒ stop and return what was collected (status stays OK if ≥ 20 chars, else CORRUPT).
* `Ole2` (REVIEW_6 F-M6, simplified): magic match ⇒ search the first 4 MiB for the UTF-16LE byte strings of `EncryptedPackage` or
  `EncryptionInfo`; found ⇒ `ENCRYPTED` (password-protected OOXML); else `format "ole2"`, `UNSUPPORTED` (legacy .doc/.xls text is not
  extracted; unknown). No sector-chain parsing. (A false hit only yields "encrypted" = unknown, the safe side.)
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
   English + transliterated + Devanagari, whole-token for single words, substring for multi-word; REVIEW_6 F-m10: text is NFC-normalised
   and tokens are `split("[\\s\\p{Punct}।॥]+")` — do **not** use regex `\b`, which breaks Devanagari words at vowel signs) and of each `idKinds` element
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
and its sibling move KEEP → PROTECTED `P_UNIQUE_RECORD`; acceptable for both). REVIEW_6 F-m9: `Keywords.record(path)` also drives
`UsageClassifier` (DOCUMENT → DOCUMENT_RECORD, and images under `Documents/`), so the slice notes must list every file whose usage
changes, not only tier changes.

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

## 8. Video (S2) — REVIEW_6: protect only

### 8.1 Bitstream activity profile (`Mp4Tables`, `ActivityProfile`; pure Java; S2) — protect only (A41)

Why: a phone camera video's compressed non-key frames are tiny when nothing changes and spike when something moves. The sample-size
table (`stsz`) covers **every** frame of the whole recording without decoding anything, so scenario 8's 5–10 s events cannot fall
between sample points (§0.3, re-checked by REVIEW_6: 4/4 windows found, control 0 peaks).

Selection (ACTIVITY pass): PRESENT, `type ∈ {mp4, mov, 3gp}`, usage CAMERA_VIDEO or CHAT_MEDIA or DOWNLOAD, `versions[ACTIVITY] != current`.
Order `fileId`. (m4a/audio is not selected — REVIEW_6 F-B3.)

`Mp4Tables.read(ReadableFile f, long size, long maxMoovBytes = 16 MiB) → Tables` (per track: handler `vide`/`soun`, `mdhd` timescale +
duration, `stts` entries, `stsz` (constant size or table), `stss` (null = absent), `ctts` present?). Walk the top-level boxes yourself with
the same 32/64-bit size rules as `Mp4Probe.walk` (do not change `Mp4Probe`); read the whole `moov` (≤ 16 MiB, else `TOO_LARGE`).
Status rules, checked in this order: no `ftyp`/`moov` box in the first 64 KiB of top-level boxes and no `moov` anywhere ⇒ `UNSUPPORTED`;
any top-level `moof` ⇒ `FRAGMENTED`; a box size < 8 or past end of file ⇒ `CORRUPT`; `stsz` sample count > 2,000,000 **or**
`12 + 4·count > stsz box payload` (count lies) ⇒ `TOO_LARGE`/`CORRUPT` **before** allocating any array.
Non-MP4 containers (mkv, avi, webm, ts) are not selected and stay `NOT_RUN`. **Every status other than OK means "activity unknown"; unknown
is never read as "static" or "no activity" by anything** (there is no static rule in Phase 6).

`ActivityProfile.video(Tables t) → fills ActivityInfo`:
1. No `vide` track ⇒ `videoStatus` stays `NOT_RUN`. No `stsz` ⇒ `NO_SAMPLE_TABLE`.
2. Sample times (decode order): `t_i` (ms) = Σ stts deltas before i × 1000 / timescale. B-frames (`ctts`) shift presentation by < 1 s and
   are ignored (1-s windows absorb them). `durationMs` = mdhd duration × 1000 / timescale (else last t + last delta).
3. `stss == null` (every sample is sync) or `stss` lists every sample ⇒ `ALL_KEYFRAMES` (unknown; MJPEG-like).
4. Windows of W = 1000 ms; `B_w` = Σ size of **non-key** samples with `t_i` in window w; `n` = number of windows with ≥ 1 non-key sample.
   4b. (REVIEW_6 F-M4) Irregular keyframes: let `g` = median distance (in samples) between consecutive sync samples. A sync sample whose
   distance to the previous sync sample is `< 0.8·g` (an encoder "scene-cut" keyframe inserted early, which would hide an event's bytes
   from `B_w`) marks its window as a peak window. `irregularSync` = their count. With a regular GOP (the dataset: every 10 s) it is 0.
5. `n < 10` ⇒ `SHORT` (peaks still computed and still protect).
6. `M = median(B_w)`, `P = max(4·M, M + 2048)`; `peakThreshold = P`; peak windows = `B_w > P` plus the windows of step 4b; merge peak
   windows ≤ 2 windows apart into intervals `[startMs, endMs)`; keep the first 32 in `peaks`; `peakWindows` = count of peak windows.
7. `videoStatus = OK` (or SHORT).

Constant-bitrate encoders, encoders that spread bits evenly, and very noisy scenes give few or no peaks: that only means *no extra
protection* (the Phase 5 state), never a candidate. Thresholds 4×, +2048 B are fixed constants in `ActivityProfile`.

Effects (wired in S8): `peakWindows ≥ 1` ⇒ CONTENT signal `VIDEO_ACTIVITY` (+1, 0.6) ⇒ personal L factor `f[0]` +0.1 (`LossEstimator`,
as CAPTURE does, capped at 1). It never lowers anything and no rule reads its absence.

Tests (`ActivityProfileTest`; MP4 built in the test by a small box writer — `Mp4ProbeTest` has one to copy — with `stsz`/`stss`/`stts`):

| # | Synthetic track | Expected |
|---|---|---|
| 1 | 600 s, 10 fps, key every 100, non-key 25 B, frames 4550–4650 = 800 B | 1 interval covering `[455000, 465000]` ± 1 window, `irregularSync 0` |
| 2 | 300 s, non-key 51 B, regular keys | 0 peaks, OK |
| 3 | 300 s CBR: every non-key 3000 B | 0 peaks, OK (no protection, no other effect) |
| 4 | no `stss` | ALL_KEYFRAMES, `peaks == null` |
| 5 | 5 s clip with one 5 KB non-key frame | SHORT, 1 peak |
| 6 | `moof` box present | FRAGMENTED |
| 7 | `stsz` claims 3,000,000 samples in a 100-byte box | TOO_LARGE or CORRUPT, no array allocated (assert via a counting allocator or by timing < 50 ms) |
| 8 | 40 separate peaks 10 s apart | `peaks.length == 64` (32 intervals), `peakWindows == 40` |
| 9 | keys every 100 samples plus one extra key at sample 4555 (event bytes inside it) | that window is a peak, `irregularSync 1` |
| 10 | a Matroska header (`1A 45 DF A3`) named `.mp4` | UNSUPPORTED |
| 11 | dataset S08 (skipped without dataset) | each of `VID_20211218_072048`, `VID_20221001_124052`, `VID_20221108_181137`, `VID_20240926_121949`: ≥ 1 interval overlapping 455–465 / 211–217 / 377–385 / 302–307 s; `VID_20210313_082140`: 0 peaks |

(REVIEW_6 F-B3: §8.2 adaptive frame sampling and §8.3 `Y_STATIC_VIDEO` are removed by A41. If a later phase reinstates them, the
design is in git history at the commit before REVIEW_6.)

---

## 9. Risk integration and the A28 rules (S8, S9)

### 9.1 `v2.risk.ContentFacts` (built in `ContextBuilder.finish` right after the `classification` loop (line ~52): `c.content = ContentFacts.of(c, c.now);`; field `FileContext.content`, never null)

(REVIEW_6: section rewritten for F-M1, A39–A41, A43.)

```java
package com.storageplanner.v2.risk;
public final class ContentFacts {
    public String recordCategory;            // see step 1; null = none known
    public java.util.List<String> recordEvidence = new java.util.ArrayList<String>();   // e.g. "text:PAYMENT", "doc:DOC_FINANCIAL", "id:PAN"
    public java.util.List<String> vetoes = new java.util.ArrayList<String>();           // §6.4 V-ids that fired (protective content)
    public java.util.List<String> unknowns = new java.util.ArrayList<String>();         // §6.4 U-ids (a check did not finish / not verified)
    public String disposableKind;            // OTP, ADVERT, GREETING, MEME; null = none
    public boolean detectorsComplete;        // unknowns.isEmpty() (false for every non-image file)
    public boolean videoActivity;            // activity peaks found (protect only)
    public static ContentFacts of(FileContext ctx, long now) { ... }      // pure: no I/O, no mode input
}
```
Algorithm `of` (`img` = `Types.image(ctx.f.type)`; `v = ctx.visual()`, `t = ctx.text()`, `d = ctx.doc()`, `act = ctx.activity()`):
1. **Record (protect).** Images, when `t != null && t.status ∈ {READ, PARTIAL_SCRIPT, LOW_CONFIDENCE}` (any engine, verified or not —
   protection is always allowed): `t.kind` PAYMENT → "payment confirmation", TICKET → "ticket", ID → "identity document",
   RECEIPT → "receipt", BILL → "bill"; else `t.idPatterns ≥ 1` → "identity document"; else `t.classes ∋ PROTECT_PAY && t.amounts ≥ 1`
   → "payment confirmation". Evidence `"text:" + kind` or `"text:ids"` / `"text:pay"`.
   Documents, when `d != null && d.status == OK`: `d.idKinds` ∩ {PAN, AADHAAR, PASSPORT_IN, SSN} ≠ ∅ → "identity document"; else
   `d.category` DOC_LEGAL → "legal record", DOC_FINANCIAL → "financial record", DOC_MEDICAL → "medical record", DOC_CERTIFICATE →
   "certificate", DOC_INVOICE → "invoice", DOC_RECEIPT → "receipt", DOC_PERSONAL_RECORD → "personal record". Evidence
   `"doc:" + category` and `"id:" + kind` for each id kind.
2. If `!img`: stop here (`vetoes`, `unknowns` empty, `detectorsComplete = false`, `disposableKind = null`) — then step 5.
3. Vetoes: evaluate the §6.4 V-table in table order on `v`, `t` (a V-condition whose input is unknown does not fire — the U-list covers
   it); append ids. Unknowns: append the U-ids of §6.4. `detectorsComplete = unknowns.isEmpty()`.
4. `disposableKind`, only if `t.status == READ`, first match:
   * OTP (A39) — `t.kind == OTP` or `t.classes` equals exactly `[OTP]`. (Bank/payment/amount/id text with it fires V-TEXT or V-AMOUNT,
     so the file is not eligible; the S8 test proves it.)
   * ADVERT — `t.classes ∋ PROMO` (TextFacts adds PROMO only for ≥ 2 distinct PROMO entries or the substring `% off`).
   * GREETING — `t.kind == GREETING` or `t.classes ∋ GREETING`.
   * MEME — `ctx.usage == CHAT_MEDIA && v.bigText ≥ 0.25 && v.textArea ≥ 0.03 && t.goodWords ≤ 40 && t.kind == null`.
   (REVIEW_6 F-M10: the aggressive-only CHAT kind is removed.)
5. `videoActivity = act != null && act.videoStatus ∈ {OK, SHORT} && act.peakWindows ≥ 1`.

Effects wired in S4 (documents) and S8 (images, video) — protect only:
* `SignalExtractor.extract`, at the end: `recordCategory != null` ⇒ `s.add(new Signal(EvidenceFamily.CONTENT, "CONTENT_RECORD", +1, 0.9,
  "content:" + recordEvidence.get(0)))`; `videoActivity` ⇒ `s.add(new Signal(EvidenceFamily.CONTENT, "VIDEO_ACTIVITY", +1, 0.6, "activity"))`.
  Disposable kinds produce **no** signal (U is never lowered by content, PHASE_4 §10).
* `LossEstimator.estimate`, directly after the path-keyword lines (41–43): payment confirmation / receipt / bill / invoice / financial
  record ⇒ `f[1] = f[1].max(Interval.of(.9, .9, 1))`; identity document / legal record / certificate / medical record / personal record
  ⇒ `f[2] = f[2].max(Interval.of(.9, .9, 1))`; ticket ⇒ `f[0] = f[0].max(Interval.of(.6, .7, .9))` and `f[1] = f[1].max(Interval.of(.5, .6, .8))`;
  `VIDEO_ACTIVITY` signal present ⇒ `f[0]` + 0.1 on low/mid/high capped at 1 (same code as the CAPTURE line 44–45).
* `CandidateRules` `P_UNIQUE_RECORD` (line ~185): replace
  `(u == UsageClass.DOCUMENT_RECORD || (u == UsageClass.CHAT_BACKUP && nameLow.startsWith("msgstore.db.crypt")))` by
  `(u == UsageClass.DOCUMENT_RECORD || (u == UsageClass.CHAT_BACKUP && nameLow.startsWith("msgstore.db.crypt")) || (ctx.content.recordCategory != null && !u.junk()))`;
  rest unchanged; `cat` = `ctx.content.recordCategory` when set (it wins over `ctx.classification`); when the content record is the
  only reason, add `EvidenceFamily.CONTENT` to the candidate and `"content:" + recordEvidence.get(0)` to `evidenceRefs`.

### 9.2 The three content Y rules (`v2.risk.ContentRules.candidates(FileContext ctx, Assessment a, PresentationPolicy pol)`, called from `CandidateRules.generate` before the `// ---- P_ ----` block; S9)

Common requirements (all three; every one is a separate `if` returning no candidate): `ctx.content.detectorsComplete`;
`ctx.content.vetoes.isEmpty()`; `ctx.content.recordCategory == null`; `ctx.content.disposableKind != null`; `ctx.keeperOf.isEmpty()`;
`ctx.f.protection == Protection.NONE`; `ctx.f.type ∈ {jpeg, png}`; `ctx.ageMs ≥ 0 && ctx.ageMs ≥ minAgeDays · RiskConfig.DAY`
(unknown age ⇒ not eligible). Candidate: `new Candidate(code, Tier.YOUR_CALL, Action.DELETE, families…)`, `needsFeatures = true`,
`fallbackCode = null`. Families per rule; TIME is never listed (H10).

| Rule | Usage | Allowed `disposableKind` (both modes) | minAge std / aggr (days) | Families | Params |
|---|---|---|---|---|---|
| `Y_OLD_SCREENSHOT` | SCREENSHOT | OTP, ADVERT | 90 / 30 | CONTENT, PROV | `age` (as `CandidateRules.age(ms)`), `kind` ∈ {"a one-time code message", "an advertisement"} |
| `Y_FORWARDED_MEDIA` | CHAT_MEDIA and `ctx.f.area ∈ {wa_image, telegram_media}` | GREETING, MEME, ADVERT | 30 / 7 | CONTENT, PROV, PIXEL | `kind` ∈ {"greeting", "meme", "advertisement"}, `app` ("WhatsApp" for wa_image, "Telegram"), `signal` "text written over the picture, no people in it" |
| `Y_OLD_DOWNLOAD` | DOWNLOAD | ADVERT, GREETING | 90 / 30 | CONTENT, PROV | `age`, `kind` ∈ {"an advertisement", "a greeting"} |

(REVIEW_6 F-B2: "rule F step 4" — PARTIAL_SCRIPT greetings — is deleted by A40.)

`PresentationPolicy` gains three `int` fields `screenshotMinAgeDays 90/30`, `forwardMinAgeDays 30/7`, `downloadMinAgeDays 90/30`
(add them to the constructor; STANDARD/AGGRESSIVE constants updated). REVIEW_6 F-M10: aggressive mode changes **only these ages**;
eligible kinds, vetoes, unknowns and HC16 are identical in both modes.

**Hard rule HC16** (defence in depth, `HardRules.check`, no mode input, H7). Add at the end of `check()` (after HC14) and append `"HC16"`
to `ORDER`:
```java
if (rem && c.families.contains(EvidenceFamily.CONTENT)) {                                                        // HC16
    if (!ctx.content.vetoes.isEmpty() || ctx.content.recordCategory != null) add(b, "H_CONTENT_PROTECTIVE");
    if (!ctx.content.detectorsComplete) add(b, "H_CONTENT_NOT_CHECKED");
}
```
Order rationale: HC1 (user protection) and HC2 still come first; HC16 is last because it can only add blocks, never remove one.
It applies to **every** removal candidate citing CONTENT (today only the three rules above). HC7 (CONTENT alone never enough, never SAFE)
and HC8 (`needsFeatures`) apply unchanged; HC12 is **not** extended (A41). `ReasonCatalog` gets both block codes (§9.3).

TierAssigner row 8 is unchanged (EL_pess ≤ tYourCall, C ≥ cYourCall). If `anyUnknown` pushes a screenshot's EL over 0.25 the candidate
is dropped — the safe direction; S9 reports the count.

### 9.3 User-facing sentences (ReasonCatalog; S4, S5, S8, S9) — all are **new** entries (REVIEW_6 F-m5: none exists yet)

| Code | Tier | Sentence |
|---|---|---|
| `Y_OLD_SCREENSHOT` | YOUR_CALL | "Screenshot from {age} ago that looks like {kind}." |
| `Y_FORWARDED_MEDIA` | YOUR_CALL | "Looks like a forwarded {kind} received in {app} ({signal})." |
| `Y_OLD_DOWNLOAD` | YOUR_CALL | "Downloaded {age} ago; it looks like {kind}. Android does not tell us whether you opened it since." |
| `R_OLDER_VERSION` | REVIEW | "Earlier version of {newer} ({similarity}). Compare them before removing either." |
| `H_CONTENT_PROTECTIVE` | (block) | "Its content looks important, so it is not offered." |
| `H_CONTENT_NOT_CHECKED` | (block) | "Its content could not be fully checked on this device, so it is not offered." |
| `C_CONTENT_INCOMPLETE` | — | "{n} pictures and documents have not been read yet, so nothing is suggested from their content; run the analysis again to continue." |

Add each with `c(code, tier, sentence)` exactly like the existing lines 23–41 of `ReasonCatalog`. `P_UNIQUE_RECORD` is reused with
`category` = `recordCategory`.

### 9.4 Device-only engines are never a precondition until verified (A43)

* OCR text from an engine for which `ContentTrust.textEngineVerified(text.engine)` is false ⇒ `U-ENGINE` ⇒ `detectorsComplete = false`
  ⇒ **no Y rule** and HC16 blocks any CONTENT removal — but its protective uses (record category, V-TEXT, V-AMOUNT) still apply.
  On the phone `OcrTextReader.id()` returns `ContentTrust.ANDROID_OCR_ENGINE` and `ANDROID_OCR_VERIFIED == false`, so the Phase 6 Y rules
  produce nothing on the phone until the owner's checklist (§12) passes and the lead flips that constant (one-line commit).
* Frame-based signals: none exist in Phase 6 (A41). Any future frame source must get its own `ContentTrust` flag defaulting to false.
* QR, faces, ImageSignals, PDF/OOXML text and MP4 tables are the same Java code on both platforms: verified on the JVM. The only
  difference is the image decoder (BitmapFactory vs ImageIO); `MediaDecoder`'s contract is already tested.
* Tests: S8 `ContentFactsTest` "engine tesseract.js-eng, everything else eligible ⇒ unknowns = [U-ENGINE], recordCategory still set";
  S9 `ContentRulesTest` same fixture ⇒ 0 candidates; S9 gate row "unverified invariance" (§11.3).

### 9.5 Expected effect (dataset, standard mode; REVIEW_6 F-M12 — honest figures)

| Rule / kind | Reachable misses | Expected caught (simulated OCR) |
|---|---|---|
| `Y_FORWARDED_MEDIA` MEME | 30 | ~26 (faces veto ~4) |
| `Y_FORWARDED_MEDIA` GREETING | 59 | **0** (A40: every greeting has a Devanagari line ⇒ not READ) |
| ADVERT (WA 15 / Download 10 / Screenshot 10) | 33–35 | ~28 |
| `Y_OLD_SCREENSHOT` OTP | 15 | ~13 |
| video / audio | 4 | 0 (A41) |
| **Hit rate** (276 + caught) / 453 | | **≈ 0.757 desktop, an upper bound** (simulated OCR reads every drawn Latin word); **0.61 with no OCR and on the phone until §12 passes** |

The gate (§11.3) is 0.75. If the measured value is between 0.74 and 0.75 the implementer reports it and stops (lead decides); the
gate is **not** met by relaxing a threshold. Exposure (Y on mnd): exactly 0 (A42) — every mnd group of §0.2 is covered by ≥ 1 veto, by
having no positive evidence, or by having no rule for its folder.

---

## 10. Audio — cut (REVIEW_6 F-B3, A41)

No `AudioProbe`, no digital-silence detection, no `Y_SILENT_RECORDING` in Phase 6: no rule and no protection would read them. The
`Extractors.AUDIO` id stays reserved. If they return later, `Extractors` must bump `AUDIO` to version 2, otherwise files already stamped
version 1 are never probed. (LEFT FOR LEAD L1.)

---

## 11. Evaluation (`tools/v2eval/evaluate.py --gate phase6`; S9)

### 11.1 Desktop OCR fixture (labels never enter v2)

| Fixture | Built by | Content | Where |
|---|---|---|---|
| OCR truth | `tools/v2dataset/common.py`: a recorder wraps `PIL.ImageDraw.ImageDraw.text` during `build.py`; `save_image` writes `{path, sha256, lines:[text, …]}` per image | the strings the generator drew (content, not labels) | `/home/user/sp-dataset-text/ocr_truth.jsonl` (outside the scanned tree) |
| OCR simulation | `tools/v2eval/ocr_sim.py TRUTH OUT` | English-only Tesseract model, below | `/home/user/sp-dataset-text/ocr_sim.jsonl` |

Recorder details (REVIEW_6 F-M11): the recorder keeps a dict `id(image) → [text, …]`; `ImageDraw.Draw(im)` remembers `im`; each
`.text(xy, text, …)` call appends `text` to that image's list. `save_image(rel, img, …)` writes one truth line for `img` **only if**
`id(img)` has an entry (an image derived by `resize/convert/crop/paste` is a new object without an entry ⇒ no truth ⇒ the sidecar
answers FAILED ⇒ unknown — honest, safe). It records **no** label fields (no category, tier, importance, mnd, scenario). The truth
is built by `build.py --out <scratch>/rebuild`; truth lines whose sha256 differs from the live manifest are dropped. If more than 5 %
of the truth lines are dropped the slice **stops and reports** (the build is then not reproducible enough for a fair figure).

`ocr_sim.py` (reads only `ocr_truth.jsonl`; deterministic: `random.Random(sha256)` per image): split each line on spaces; per word, the
**script is decided from the characters** (any char in U+0900–U+097F ⇒ Devanagari) — never from the font (the generator draws Latin
text with a "deva" font); Latin word: dropped with p 0.15, else one character changed with p 0.05 (conf 50–69), else kept (conf 70–95);
Devanagari word: replaced by 1–3 ASCII garbage tokens of 2–6 chars from `"il1|/\\-_.,:;'~^"` (conf 15–45). Output
`{"path","size","sha256","engine":"sidecar-sim","version":1,"words":[[w,conf],…]}` — no other keys (`evaluate.py` asserts this).

`SidecarTextReader` (jvmtest): constructor `(Path jsonl, Path root, boolean unverified)` reads the jsonl into `Map<relPath, entry>`;
`read()` returns `READ` with the words when the path and size match; a missing or size-mismatched entry returns `FAILED` with error
`"no fixture"` (never `UNAVAILABLE`, which would stop the whole pass). `id()` = `unverified ? "sidecar-sim-unverified" :
ContentTrust.DESKTOP_SIM_ENGINE`, `version() = 1`, `available() = true`.

The figure produced with it is an **upper bound**: the simulated reader sees exactly the drawn strings, never misreads layout, and
real (non-synthetic) images get no text at all.

`V2Cli` options: `--ocr-sidecar FILE` (S7), `--ocr-unverified` (S7), `--face-model FILE` (S6, default `app/src/main/assets/faces/yunet.bin`),
`--ocr-budget N`, `--visual-budget N` (S6/S7), `--content-dump FILE` (S7: jsonl, one line per file with path, usage and the content
sub-records — no manifest data).

### 11.2 Runs

`tools/v2eval/p6_gate.sh OUT` (created in S1, §13): for mode in standard, aggressive: run A = `--max-tier 4 --ocr-sidecar
/home/user/sp-dataset-text/ocr_sim.jsonl` (from S7); run U = run A plus `--ocr-unverified` (from S9); run N = `--max-tier 4` without a
sidecar. Evaluations: phase4 and phase5 on A (as the lead's gate.sh); from S9 `evaluate.py --gate phase6 --result A --no-content N
--unverified U`.

### 11.3 Metrics and gates (`--gate phase6`, S9)

| Metric | Definition | Gate |
|---|---|---|
| DDRR | Phase 5 definition, runs A, U and N, both modes | 0 |
| exposure | YOUR_CALL / any delete on a must_never_delete file, runs A, U, N, both modes | **exactly 0, no exemption** (A42, REVIEW_6 F-B4); if the A27 file is still mislabelled when S9 runs, stop and report — do not add an exemption |
| spec_violations | Phase 5 | 0 |
| no-content invariance | in N, recs with `Y_OLD_SCREENSHOT/Y_FORWARDED_MEDIA/Y_OLD_DOWNLOAD` | 0 |
| unverified invariance (A43) | in U, recs with the same codes | 0 |
| YOUR_CALL hit rate | Phase 5 row "YOUR_CALL after cap", run A standard | ≥ 0.75, printed as "desktop, simulated OCR, upper bound" |
| YOUR_CALL precision | Phase 5 | ≥ 0.95 |
| category precision (fold 1, reported) | per predicted class among SCREENSHOT, QR_CODE, GREETING_MEME, ADVERTISEMENT, RECEIPT, DOCUMENT_PHOTO vs manifest category | reported only |
| protective recall | manifest `payment_confirmation`, `ticket`, `id_document`, `receipt` images: share with `recordCategory != null` or a V-TEXT/V-QR/V-CARD veto **or** a U-id | 1.00 for mnd, ≥ 0.9 others |
| documents | text-layer PDFs (manifest pdf, not encrypted, not scanned) with `doc.status OK` ≥ 0.9; encrypted 5/5 ENCRYPTED; scanned 11/11 NO_TEXT_LAYER; financial PDFs with status OK → category ∈ {FINANCIAL, INVOICE, RECEIPT} ≥ 0.9 | as stated |
| video | 4 event videos: a peak interval overlaps the window parsed from the manifest `notes` ("activity only at A-Bs") | 4/4 |
| scenarios | Phase 5 rules | no FAIL; S08 CAUTIOUS is expected (its control video stays KEEP, A41) |
| budget convergence | chain of runs with `--ocr-budget 40 --visual-budget 150 --state DIR` (FULL kind) until no `C_CONTENT_INCOMPLETE` | final run equals the unbudgeted one (minus timings/decode counts) |

(REVIEW_6 F-M13: `calibrate_content.py` is cut. Thresholds are fixed constants; S9 prints fold-0/fold-1 margins only.)

---

## 12. What is JVM-testable and what only the phone can verify

| Feature | JVM (this container) | Fixture | Phone only |
|---|---|---|---|
| PDF/OOXML/ODF/TXT text, categories, ids, version links | fully | synthetic bytes + dataset | — |
| MP4 activity profile | fully | synthetic boxes + dataset (real H.264) | real phone recordings' bitrate behaviour (thresholds §8.1) |
| ImageSignals, QR, faces | fully (same code) | dataset images | WebP/HEIC chat images (decoder) |
| OCR pass logic, readability, vetoes, Y rules | fully, with the simulated sidecar | `ocr_sim.jsonl` | real Tesseract output (confidence scale, Devanagari garbage shape, speed) |
| Android OCR bridge | compile only (`check_android_compile.sh`) | — | everything |

Owner's phone checklist (added to `PHASE_A_NOTES.md` in S10): (1) OCR starts and reads 20 chosen screenshots (5 payment, 5 OTP, 5 ads,
5 chat): kinds as expected, no crash, time per image recorded; (2) 10 Hindi/Marathi greetings and 5 Hindi payment screenshots: status
PARTIAL_SCRIPT or LOW_CONFIDENCE, **never READ**; (3) a timed-out read gives FAILED, not NO_TEXT; (4) budget: run on the full phone and
confirm `C_CONTENT_INCOMPLETE`, then convergence over runs. Passing (1)–(3) is the condition for the lead to set
`ContentTrust.ANDROID_OCR_VERIFIED = true`.

---

## 13. Implementation plan — slices for Sonnet agents (REVIEW_6: rewritten)

### Implementer brief (read before any slice)

* **Constraints.** Java 8 source (`javac --release 8`), zero new libraries. Under `app/src/main/java/com/storageplanner/v2/`: no
  `android.*`, no reflection, no `java.net`, no `File.delete`/`renameTo`/`Files.move/delete`, no `System.currentTimeMillis/nanoTime`
  (use `ctx.device.clock()`), no Java 9+ API (`List.of`, `String.repeat`, `Optional.isEmpty`, `var`, …) and nothing newer than Android
  API 26. `ForbiddenApiTest` enforces most of this. **Do not edit `app/src/main/java/com/storageplanner/core/`** or `app/OcrEngine.java`.
* **Verify after every slice (both must be green):**
  `LC_ALL=C.UTF-8 bash tools/v2eval/run_tests.sh` and `bash tools/v2eval/check_android_compile.sh`.
  Then the dataset run: `bash tools/v2eval/p6_gate.sh /home/user/p6runs/S<k>` and
  `python3 -I tools/v2eval/rec_diff.py /home/user/p6runs/S<k-1>/standard.json /home/user/p6runs/S<k>/standard.json <flags of the slice>`
  (and the same for `aggressive.json`). S1's previous run is `/home/user/p6runs/S0` (S1 step 0 creates it).
* **Every new test class** goes into `jvmtest/com/storageplanner/v2test/` and is registered in `AllV2Tests`.
* **Notes:** append a section `## S<k>` to `docs/v2/PHASE_6_NOTES.md`: what was done, every deviation from this document with the
  reason, the rec_diff output (counts and every changed file), gate rows. If something in this document does not match the code, do
  the smallest thing that keeps the safety rules and write it down — never invent a new rule or relax a threshold.
* **Do not commit or push.**
* Dataset results may change **only** as each slice's "Allowed changes" says; anything else is a stop-and-report.
* Thresholds in this document are constants. A slice that misses a gate reports; it does not tune.

### S1 — content model, codec, carry-over, tools (≈ 350 prod lines)
Step 0: create `tools/v2eval/p6_gate.sh OUT [extra V2Cli args]`: compile exactly like `run_tests.sh` (same `find … | sort` source list,
`javac --release 8 -encoding UTF-8 -nowarn -d OUT/classes`), then for mode in standard, aggressive run
`java -cp OUT/classes com.storageplanner.v2test.V2Cli scan /home/user/sp-dataset --store memory --mode $m --target-bytes max --now 1792000000000 --out OUT/$m.json "$@"`
and `evaluate.py --gate phase4` and `--gate phase5 --plans max=OUT/$m.json` (copy the body of
`/tmp/claude-0/-home-user-storage-planner/69e294b1-7ff5-57b8-9df7-ed244c7e43c3/scratchpad/v2out/gate.sh`, the lead's script); exit 1 if
any step's rc ≠ 0. Run it on the unchanged tree into `/home/user/p6runs/S0`.
Create `tools/v2eval/rec_diff.py OLD NEW [--allow CODE,…] [--expect-none]`: load both results; map `fileId → path` from `files[]`;
per path compute `evaluate.translate(recs)` (copy that function) and the first `reasonCodes[0]` of the first rec; print every path whose
translated tier or primary code differs as `path: OLDTIER/OLDCODE -> NEWTIER/NEWCODE`; `--expect-none` exit 1 on any change; `--allow`
exit 1 if a change involves neither an allowed code in OLD nor in NEW; print counts by (old→new).
Java: create `v2/model/{VisualInfo,TextInfo,DocInfo,ActivityInfo,TextStatus,DocStatus,ActivityStatus,DetectorStatus}.java` exactly as
§4.2; `v2/store/ContentCodec.java` (§4.4); edit `FeatureRecord` (`EXTRACTORS = 24`, 4 fields, `copy()`), `Extractors` (ids 20–23),
`FeatureCodec` (call `ContentCodec` under the four keys), `FileContext` (4 accessors), `T1MetadataStage.extract` (carry-over, §4.3),
jvmtest `V2Json.writeFile` (`content` object only when at least one sub-record is non-null).
Tests: §4.5 table (`ContentCodecTest`), `ContentCarryOverTest` (§4.3).
Allowed changes: none (`rec_diff --expect-none`); JSON gains no keys for files without content.

### S2 — `ContentStage` skeleton + MP4 activity (≈ 400)
Create `v2/content/{Mp4Tables,ActivityProfile,ContentStage,ContentConfig,ContentProviders}.java` (ContentProviders/ContentConfig as §5.2
with what S2 needs; `UnavailableTextReader` and `TextReader` may be created here as empty-reader stubs per §6.2).
`ContentStage implements Stage`, id `"T4_TEXT"`, constructor `(ContentProviders p, ContentConfig cfg)`; `run(ctx)` runs the passes in
§5.1 order (S2: only ACTIVITY), honouring §5.3 (budget/ScanControl check before each file; commit every 200 files or 3 s; per-file
catch; `C_CONTENT_INCOMPLETE` event). Wire it in `V2Cli` only (§5.1 (a), behind `--max-tier ≥ 4`); V2Cli builds
`new ContentProviders(null, null)` and `new ContentConfig()`.
Algorithm §8.1. Tests: §8.1 table; `ContentStageResumeTest`: 30 synthetic MP4s, a `ScanControl` that pauses after the first commit ⇒
second run processes only the rest and the final records equal a single uninterrupted run.
Allowed changes: default runs none (`--expect-none`); also run `p6_gate.sh /home/user/p6runs/S2m4 --max-tier 4` and
`rec_diff S2 S2m4 --expect-none` (activity is not used by any rule yet). Print the S08 intervals in the notes.

### S3a — PDF text (≈ 450)
Create `v2/content/doc/{PdfText,PdfCMap}.java`; DOC pass in `ContentStage` for `type == pdf` only (§7.1) filling `DocInfo.status, format,
pages, chars, words, unsupportedStreams` (`chars/words`: count of the extracted text; text then discarded). Add `{"javax.xml", "javax\\.xml"}`
to `ForbiddenApiTest`'s pattern list (REVIEW_6 F-m2). Algorithm §7.2. Tests §7.2 table (`PdfTextTest`; build PDFs in the test with
`java.util.zip.Deflater` and a small ASCII85 encoder). Allowed changes: none; `--content-dump` (add the option here if not yet present:
one jsonl line per file with path, usage and sub-records) shows ≥ 90 % of text-layer PDFs OK, 5/5 encrypted ENCRYPTED, 11/11 scanned NO_TEXT_LAYER.

### S3b — OOXML, ODF, OLE2, plain text (≈ 400)
Create `v2/content/doc/{OoxmlText,OdfText,Ole2,PlainText,XmlText}.java`; extend the DOC pass dispatch (§7.1). Algorithm §7.3. Tests
§7.3 (`OoxmlTextTest`). Allowed changes: none; dump: all dataset OOXML/ODF documents OK or ENCRYPTED.

### S4 — document facts, categories, keywords, document protection (≈ 500)
Create `v2/content/doc/{DocFacts,DocCategorizer,Identifiers,DocKeywords,MinHash}.java` (§7.4); call `DocFacts.fill` from the DOC pass;
create `v2/risk/ContentFacts.java` with §9.1 steps 1 (documents part), 2 and 5 (the image part comes in S8); edits: `Keywords` (§7.4
lists), `ContextBuilder.finish` (build `c.content`), `SignalExtractor`, `LossEstimator`, `CandidateRules` (`P_UNIQUE_RECORD`, §9.1).
Bump `Extractors` version of `DOC_TEXT` to 2 (the S3 records lack facts). Tests §7.4 + `ContentFactsDocTest`: a DOCUMENT (no path
keyword) with `doc.category = DOC_FINANCIAL` ⇒ `P_UNIQUE_RECORD` PROTECTED with category "financial record"; `DOC_TEMPORARY` ⇒ unchanged;
`doc.status ENCRYPTED` ⇒ unchanged; `RiskPropertyTest` still passes.
Allowed changes (`rec_diff --allow P_UNIQUE_RECORD`): only KEEP/REVIEW → PROTECTED and usage DOCUMENT → DOCUMENT_RECORD; list each
with its manifest category and acceptable tiers; any file whose new tier is not acceptable is reported (not blocked) unless it is a
removal. DDRR/exposure unchanged. Run the gate with `--max-tier 4` too (`/home/user/p6runs/S4m4`) — the content effects show only there.

### S5 — document version links (≈ 300)
Create `v2/graph/DocVersionLinker.java` (§7.6), called at the end of the DOC pass with all `DocInfo` records of the run (store through
the existing relationship table exactly as `CompanionLinker` writes its edges); `CandidateRules` `R_OLDER_VERSION`; `ReasonCatalog`
entry (§9.3). Tests §7.6. Allowed changes (`--max-tier 4` run): `--allow R_OLDER_VERSION`: `Documents/Resume_Priya_{v1,v2,final}.docx`
KEEP → REVIEW plus any other doc-stem group, each listed.

### S6 — visual pass (≈ 400)
Create `v2/content/{VisualPass,FaceDetector,FaceResult,YunetFaceDetector,QrKind}.java`; VISUAL pass in `ContentStage` (§6.1);
`ContentProviders` gets the face detector; V2Cli `--face-model`, `--visual-budget`. Tests §6.1 table + `VisualBudgetTest`: budget 50 on
a 120-image fixture ⇒ event present, three runs converge to the unbudgeted records. Allowed changes: none; dump shows QR on 12/12
ticket screenshots, faces on the 6 WhatsApp family images, aspect 1.586 ± 0.01 on the 4 ID images.

### S7 — OCR interface, pass, desktop fixture (≈ 450)
Create `v2/content/{TextReader,OcrResult,UnavailableTextReader,OcrPass,TextFacts,TextWords,ContentTrust}.java`; jvmtest
`SidecarTextReader`; `tools/v2dataset/common.py` recorder; `tools/v2eval/ocr_sim.py`; build the fixture (§11.1); V2Cli
`--ocr-sidecar`, `--ocr-unverified`, `--ocr-budget`. Algorithm §6.3, §6.5. Tests: `TextFactsTest` (§6.5), `ContentTrustTest` (§5.2),
`OcrPassTest` with a stub reader: (a) 30 good words, 0 garbage, 120 blobs → READ; (b) 8 good + 3 garbage (conf 30) on 40 blobs →
PARTIAL_SCRIPT; (c) 1 good word on 200 blobs → LOW_CONFIDENCE; (d) reader returns NO_TEXT on a wordy picture → LOW_CONFIDENCE;
(e) reader returns null / FAILED twice → `attempts 2`, not selected a third time; (f) `available() false` → no records; (g) picture
with 5 blobs and bigText 0 → NO_TEXT without a reader call; (h) engine version bump → re-selected; (i) UNAVAILABLE mid-pass → pass
stops, earlier files kept; (j) word "दिवाळी" (conf 90) → garbage 1 → not READ; (k) a DOB-like text date 30 years old → `textDate` unknown.
Allowed changes: none (no rule reads text yet); dump: OTP screenshots READ ≥ 12/15, greetings READ 0/59, truth drop rate printed.

### S8 — protective integration for images and video, categories output (≈ 350)
Complete `ContentFacts` (§9.1 steps 1 images, 3, 4) and the S8 effects (§9.1); create `ImageCategorizer` (§6.6) and emit it in
`V2Json`. Tests: §6.6 rows; `ContentFactsTest`: payment screenshot (kind PAYMENT) ⇒ recordCategory "payment confirmation" and V-TEXT;
ID aspect 1.586 ⇒ V-CARD; engine `tesseract.js-eng` ⇒ unknowns [U-ENGINE] and recordCategory still set; faces NOT_RUN ⇒ U-FACES;
OTP text READ + classes [OTP] ⇒ disposableKind OTP, no veto; OTP + "bank" ⇒ V-TEXT; OTP + "Rs. 5,000" ⇒ V-AMOUNT; PARTIAL_SCRIPT
greeting ⇒ U-TEXT, disposableKind null; video with peaks ⇒ VIDEO_ACTIVITY signal and `f[0]` +0.1.
Allowed changes (`--max-tier 4` run, `--allow P_UNIQUE_RECORD`): only → PROTECTED on payment/ID/receipt/ticket images; the 4 S08 event videos may
only move toward KEEP/REVIEW/PROTECTED; **no** change on SAFE/NO_LOSS redundant copies (the WA receipt copies must stay SAFE — report if not).

### S9 — the three Y rules, HC16, gate (≈ 450 incl. evaluator)
Create `v2/risk/ContentRules.java` (§9.2); edits: `CandidateRules.generate` (call), `HardRules` (HC16), `PresentationPolicy` (3 ages),
`ReasonCatalog` (§9.3), V2Cli default `--max-tier 4`; `evaluate.py --gate phase6` (§11.3; new args `--no-content`, `--unverified`);
`p6_gate.sh` runs A/U/N (§11.2). Tests: `ContentRulesTest` — one positive per (rule, kind); for each positive, one negative per V-id and
per U-id (table driven), `ageMs` unknown ⇒ none, aggressive vs standard differ only in age; `HardRulesTest` HC16 ±; `RiskPropertyTest`
extension: random content facts never yield SAFE/NO_LOSS with a CONTENT family, never Y with any veto or unknown.
Allowed changes (`--allow Y_OLD_SCREENSHOT,Y_FORWARDED_MEDIA,Y_OLD_DOWNLOAD,P_UNIQUE_RECORD`): KEEP → YOUR_CALL only on files whose
manifest expected tier is YOUR_CALL; every §11.3 gate passes; hit rate and margins recorded.

### S10 — Android OCR bridge (≈ 200; compile-checked only)
Create `app/src/main/java/com/storageplanner/app/OcrTextReader.java` (package `com.storageplanner.app`, implements `TextReader`): lazily
`new OcrEngine(context)` + `start(90000)` on the first `read`; start failure ⇒ `available()` false for the rest of the run; converts ARGB →
`Bitmap` → JPEG 92 (copy `AndroidPlatform.readText` lines ~325–340); `OcrEngine.read(jpeg, timeoutMs)` returns `null` on timeout or
error ⇒ `OcrResult.status FAILED`; else `READ` with its words/conf (possibly empty — §6.3 step 3 handles it); `id()` returns
`ContentTrust.ANDROID_OCR_ENGINE`; `version()` = 1 (bump when the traineddata asset changes). Create `app/v2/AndroidContent.java`
(builds `ContentProviders(new OcrTextReader(ctx), new YunetFaceDetector(bytes of assets/faces/yunet.bin))` and
`ContentConfig.forProfile(profile)`); `V2Runner`: `cfg.maxTier = 4`, add the `ContentStage` (§5.1 (b)), close the engine at the end;
`V2Analysis.Result.pendingContent` (§5.3). Allowed changes: JVM results none; phone checklist (§12) appended to `PHASE_A_NOTES.md`.

### S11 (optional) — faces on camera photos, `P_PERSONAL_MEDIA_UNIQUE` (≈ 250)
VISUAL selection += camera photos that are non-keeper members of a BURST/CONTENT_EQUIVALENT group or have `ImageInfo.contrast < 10`;
rule PHASE_4 §9.2 (`P_PERSONAL_MEDIA_UNIQUE`, PROTECTED, `faceCount ≥ 1`, no verified copy). Allowed changes: only KEEP/YOUR_CALL/REVIEW
→ PROTECTED on camera photos with faces; DDRR unchanged; S01 PASS count ≥ before.

Order rationale: S1–S5 are pure Java and protect only; S6–S8 add detectors and protection with no new removal; S9 is the first slice
that creates YOUR_CALL, after every veto and unknown it relies on exists and is measured; S10 (device) comes after the logic it feeds.
S3a, S3b, S5, S6 are independent of each other after S2 and may run in any order.

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
| Adaptive video frame sampling, `Y_STATIC_VIDEO`, frame bridge (REVIEW_6 F-B3) | A41: static videos are not offered; nothing protective needs frames once the bitstream profile protects scenario 8 | later phase, behind its own `ContentTrust` flag |
| `AudioProbe`, digital silence, `Y_SILENT_RECORDING` (REVIEW_6 F-B3) | A41; no rule or protection reads them | later phase (bump `Extractors.AUDIO`) |
| `calibrate_content.py` (REVIEW_6 F-M13) | automatic threshold fitting on the scored dataset invites tuning to the labels; thresholds are fixed constants | — |
| Stored image categories (REVIEW_6 F-M9) | no rule reads them; computed at output | — |

---

## 15. Design choices (with the rejected alternative)

| # | Choice | Rejected alternative | Why |
|---|---|---|---|
| C1 | Video motion from the **compressed frame-size table** over the whole recording; used to protect only (A41) | dense frame decoding (every 2 s) | scenario 8's events are 5–10 s long; 7 + triggered samples can miss them, a full-coverage table cannot; zero decode cost; pure Java ⇒ JVM-verified on real H.264 |
| C2 | Store **no raw OCR/document text**, only derived facts and a MinHash | store the text for re-classification | spec 15 (extracted text is as sensitive as the file); store size (A21/A31); re-classification re-extracts via the version bump |
| C3 | Desktop OCR = generator-drawn text + an English-only degradation model | (a) install a desktop OCR engine; (b) fake text from labels | (a) blocked in this environment and a different engine than the phone's anyway; (b) leaks labels. (a)/(b) both overstate; the sim at least reproduces the script failure |
| C4 | Y rules need **every** protective detector complete (visual, QR, faces, verified OCR with status READ — A40) | allow pixel-only eligibility when OCR is missing | pixel signals alone make the WhatsApp ID card (aspect 1.586, bigText 0.55) look like a meme; spec 7.2 regional text |
| C5 | Device-only engines are **unverified until the owner's checklist** passes (`ContentTrust`, checked live — A43); protection uses them immediately | enable Y rules on the phone at once | task rule: nothing device-only becomes a precondition for a deletion recommendation until verified; protection is the safe direction |
| C6 | Content providers passed to the stages (`ContentProviders`), not added to `V2Device` | add `text()` to `V2Device` (its comment says Phase 6 would) | avoids default methods/interface churn in the Android device class (P5 D-3 avoided them too) |
| C7 | No tier bits for T4; versions only (+ T1 carry-over, F-B1) | `Tiers.T4/T6` bits | P13 would mark every file PARTIAL and change `extraction.status` for the whole dataset |
| C8 | Documents get categories and protection only, no Y rule | `DOC_TEMPORARY` ⇒ YOUR_CALL | spec 9.5; one dataset category (`docx REVIEW`) is better served by version links |
| C9 | Card/page aspect vetoes | rely on OCR ID words | IDs photographed with Devanagari text or OCR failure; aspect is free, script-independent and measured exact (1.586) |
| C10 | OCR bridge class in package `com.storageplanner.app` | widen `OcrEngine` visibility | leaves the classic class untouched (A2 spirit) |
| C11 | Version links in `ContentStage`, not `GRAPH` | add to GRAPH | needs `DocInfo` (text sketch), which exists only after the DOC pass |
| C12 (REVIEW_6) | `ContentFacts` alongside the existing `ContentClassifier` plug point | implement Phase 6 as a `ContentClassifier` | that interface returns one category and a family; vetoes, unknowns and disposable kinds do not fit it |

---

## 16. Questions for the lead — answered (REVIEW_6)

* Q1 → **A39**: OTP disposable unless bank/payment/amount/id text appears (V-TEXT, V-AMOUNT). Applied in §9.1 step 4, tests in S7/S8.
* Q2 → **A40**: rejected. Only READ (no unreadable token) enables YOUR_CALL. Applied in §6.3 step 5, §6.4 U-TEXT, §9.2; hit-rate gate 0.75 desktop.
* Q3 → **A41**: no static-video / silent-recording candidates; video activity protects only. Applied in §8, §10, §13, §14.
* Q4 → **A42**: exposure exactly 0, no exemption. Applied in §11.3.
* (new) **A43**: `ContentTrust` (§5.2, §9.4).
* Q5 (default tier / per-run caps: `--max-tier 4` by default from S9 on the desktop and from S10 on the phone; BALANCED 2000 visual /
  300 OCR per run): still open — REVIEW_6 L2.
* REVIEW_6 L1: audio probe and silence detection cut (A41 says audio features are "extracted and used only to protect", but no
  protective rule needs them). Recommended: confirm the cut.
