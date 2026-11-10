# Intelligent on-device storage engine: Phase 0 discovery and design

Status: design. Decisions taken since (section 10): GitHub-only distribution, the app may act on files
(through a separate action engine, not built yet), zero dependencies kept, *All safe steps* is the default.
The first part is implemented: the decision algorithm of section 6 (`core/Assessor.java`, `Policy.java`,
`Actions.java`; README, "The decision algorithm"). Not implemented yet: the index, the wider relationship
search, documents, the action engine. The original text follows; it was written with no app code changes. Written 2026-11-10 against
commit `4e84791` (app 1.8.1), in answer to the brief *Intelligent On-Device Storage Optimization Engine*.
The brief asks for research, a repository audit and a design **before** implementation; this is that
deliverable. Section 10 lists the decisions that need an answer before Phase 1 starts.

How to read the labels:

- **[verified]**: read in this repository's code, or on a primary source fetched for this document.
- **[search]**: from a web search summary of a primary source; the page itself was not read.
- **[hypothesis]**: reasoning or prior knowledge, not checked. Check before relying on it.

## 1. Summary

1. **The repository is already most of the brief's Phases 1–3, in a different shape.** It has a
   read-only scanner, magic-byte typing, exact and near-duplicate detection, a best-shot ranker, offline
   OCR, face and QR detection, 14 picture categories, a tiered planner, a review UI, and verified
   compression tools. The work is to restructure and extend it, not to start again.
2. **The biggest structural gap is the data model, not the intelligence.** Everything lives in memory and
   in one binary blob (`ResultStore`). There is no index with stable identifiers, no typed relationships,
   no evidence records and no action log. Sections 5 and 6 design those.
3. **The current decision model collapses the brief's six dimensions into one number** (`Option.risk`,
   R0–R4). That is the thing section 6 of the brief says not to do. Section 6 here replaces it with a
   dimension vector, hard safety gates and an explicit *abstain*, while keeping the tiers as the label
   users see.
4. **No new third-party dependency is recommended for Phases 1–4.** The framework's SQLite, `PdfRenderer`,
   `MediaMetadataRetriever`, `java.util.zip` and the bundled Tesseract, YuNet and ZXing cover them. The
   candidates that would add the most (ML Kit, a TFLite/LiteRT runtime with an embedding model) each
   conflict with something the project already guarantees: no closed binaries, no native code, an audited
   APK, or a usable model licence.
5. **Three platform facts constrain the design more than any algorithm:**
   - the app depends entirely on `MANAGE_EXTERNAL_STORAGE`, which Google Play only allows for listed
     core uses, and a storage cleaner is not obviously one of them;
   - it targets API 34, and Play has required API 36 for new apps and updates since 31 August 2026;
   - once it targets API 35+, its `dataSync` foreground service is limited to 6 hours per 24 hours.
6. **Nothing in the app can act on a file today**, by design and enforced by the APK audit. The brief's
   section 17 (transactional cleanup, trash, undo) is entirely new work, and it is the highest-risk part.

## 2. Repository assessment

### 2.1 What the repository is

| Property | Value |
|---|---|
| Platform | Android app, single module `:app`, package `com.storageplanner` [verified] |
| Language | Java 8 source level, no Kotlin, no AndroidX (`android.useAndroidX=false`) [verified] |
| SDK levels | `minSdk 26` (Android 8.0), `targetSdk 34`, `compileSdk 34` [verified] |
| Dependencies | None in Gradle. ZXing 3.5.3 decoders are vendored as source [verified] |
| Native code | None. YuNet runs in plain Java (`core/FaceNet.java`); Tesseract runs as WebAssembly in a WebView [verified] |
| Size | About 20,800 lines of first-party Java and docs: `core` (algorithm, no Android imports), `app` (UI and platform), `tools` (file-changing code) [verified] |
| Tests | 19 desktop test classes in `jvmtest/`, run by CI before the build. No instrumented (on-device) tests [verified] |
| Build | Gradle/AGP 8.5.2, or `tools/build_offline.sh` (javac, D8, aapt2, a Python v2 signer). CI builds, audits and uploads the APK [verified] |
| Network | No `INTERNET` permission. `tools/audit_apk.py` fails the build on network, process, reflection or code-loading calls [verified] |

Architecture: `core` talks to the device only through the `Platform` interface. `AndroidPlatform` implements
it on the phone and `JvmPlatform` on the desktop, which is why the whole algorithm is testable without a
device. This seam is the most valuable thing to keep.

### 2.2 Capability matrix

"Brief §" is the section of the brief that asks for the capability.

| Capability | Brief § | State today | Where | Gap |
|---|---|---|---|---|
| File enumeration | 4 | **Works**, one volume | `Scanner.scan` walks `Platform.storageRoot()` with `java.io.File` | No SD card or USB volumes. No MediaStore or SAF path, so without all-files access there is no scan at all |
| Coverage reporting | 4 | **Partial** | `Result.unreadableDirs` (a count); `Android/data` skipped | No list of what was not covered or why; not shown as coverage |
| Area classification | 4, 6 | **Works** | `Classifier.classify`: about 30 areas from path rules (camera, screenshot, WhatsApp kinds, Telegram, trash, temp, residue) | English/Latin folder names only; WhatsApp and Telegram are hard-coded; Bluetooth, screen recordings and other messengers fall into `user_other` |
| File type from content | 5 | **Partial** | `Classifier.magic`: 15 signatures | Run only in media areas, else `"other"`. No RAR, 7z, tar, gzip, Office (OLE2), GIF, BMP, WAV, RAW. Extension mismatch is never computed |
| Exact duplicates | 7, 13 | **Works** | size → SHA-256 of first+last 64 KiB → full SHA-256 | Files under 4 KB skipped. Hashes are not kept between scans, so every scan re-hashes |
| Near-duplicate images | 7 | **Works, narrow** | 63-bit DCT pHash (`ImageFeatures`), Hamming ≤ 10, union-find | Camera photos only, same dimensions, within 10 minutes. No resized, edited or cross-folder copies, except WhatsApp Sent (Hamming ≤ 8 plus aspect ratio) |
| Best shot in a group | 7 | **Works**, tested | `PhotoQuality`, `EyeState` (own model, 98.5% on 716 unseen faces), `FaceNet` | Smiles and composition not judged (documented) |
| Blur, exposure, accidental | 7 | **Works** | Laplacian sharpness; `ImageSignals` | Blur is relative to the user's median, and is then offered as a deletion candidate: see 2.4 |
| Faces | 7 | **Works** | YuNet in Java, checked against OpenCV to 0.001 px | No clustering (and none proposed) |
| OCR | 7, 10 | **Works**, English | Tesseract 5 (tesseract.js 7.0.0) in an offline WebView, 20-minute budget, resumable | Latin script only. Depends on the phone's WebView. About 1–3 s per picture (estimate) |
| QR and barcodes | 7 | **Works** | ZXing decoders | — |
| Picture categories | 7 | **Works**, 87% on 399 pictures | `Sorter`, `TextRules`, `PhotoCategory` | One category per picture; the brief asks for overlapping labels |
| Sensitive-record protection | 10, 17 | **Works for ID pictures** | `PhotoCategory.ID`: text is not stored, and every delete option but an exact duplicate is removed | Only pictures. PDFs and Office files are not read |
| Video metadata | 8 | **Works** | `Platform.video`: codec, size, bitrate, duration | No frame sampling in the scan, no video near-duplicates, no categories |
| Video compression | 8 | **Tool works**, one phone | `VideoCompressor`, `VideoSearch`, `VideoTranscoder`, `FrameScore`; five levels with probe search | Lab only. Refuses HDR, slow motion and extra tracks by design |
| Image compression | 7 | **Tool works**, one phone | `ImageCompressor`, `HeifEncoder`, `Guard` (SSIM ≥ 0.98, PSNR ≥ 38 dB, colour check) | Lab only |
| Motion Photo trimming | — | **Tool works** | `MotionPhoto` | Lab only. Not in the brief; a real saving the brief missed |
| Zip | 14 | **Tool works** | `ZipCompressor`, four levels, verified | Lab only. Writes archives; nothing reads archives |
| Audio | 4 | **Minimal** | Bitrate of files in `Music/` | No duplicates beyond byte-identical, no voice-note handling |
| Documents | 10 | **None** | `document` and `wa_doc` are areas only; `ViewerScreen` can show a PDF | No text extraction, classification, versions or sensitivity for any document file |
| APK files | 9 | **Works** | `Platform.apkInfo` → package and version; compared with installed apps | No label or signing info; "several versions of one package" is not grouped |
| Installed apps | 9 | **Works** | `StorageStatsManager`, `UsageStatsManager`; essential apps protected; without usage access no app is ever suggested | — |
| Archives | 4 | **None** | ZIP magic only | No listing of contents |
| Relationship graph | 11 | **Implicit** | `UnitBuilder.Detections`: three string maps (`duplicateOf`, `burstExtraOf`, `sentCopyOf`) | No types, confidence, evidence or persistence. No lineage, sidecar, cross-format or collection edges |
| Decision engine | 6, 13 | **Works, one dimension** | `UnitBuilder` → `Planner.tiered` (lowest tier that reaches the target, greedy then prune) | See 2.4 |
| Local index | 5 | **Blob** | `ResultStore` v3: checksummed binary file, written atomically | Not queryable, keyed by path, rewritten whole. No stable id, volume, provenance or model versions |
| Incremental scan | 12 | **Partial** | `Scanner.reuse` / `sortedBefore`: same path, size, mtime and type keeps picture features | A moved or renamed file is re-analysed. Duplicates and video metadata always recomputed |
| Background work | 12 | **Works** | Foreground service (`dataSync`), wake lock ≤ 60 min, cancel, notification | Single thread. No battery or thermal checks. Results saved only at the end, so an interrupted scan restarts |
| Review UX | 18 | **Works** | Overview, lists, swipe deck, group compare, category grid, viewers; TalkBack throughout | Records approval only |
| Feedback | 16 | **Minimal** | `Session.kept`, `Session.accepted` in SharedPreferences, by path | Not used to learn anything. Lost when a file moves. No reset button |
| Anomaly detection | 15 | **None** | — | — |
| Executing actions | 17 | **None, by design** | The audit forbids `File.delete` outside four tools and forbids any MediaStore trash, delete or write request | The whole transaction layer |
| Report export | 18 | **Works** | `Report`: txt, csv, json | — |

### 2.3 What to reuse as it is

- The `Platform` seam and the desktop test harness. Every new analyzer should be written against it.
- `Guard`, `Ssim`, `FrameScore` and the compression tools, including their refusal rules and the lessons in
  `tools/README.md`. They already implement "write a new output, validate it, only then offer
  replacement" (brief §17).
- The APK audit. It is the mechanism that makes "offline" and "nothing is deleted" checkable claims
  instead of promises. The action layer must extend its allowlist deliberately, not weaken it.
- YuNet, the eye model, ZXing and the Tesseract bridge.
- The ID-document rule (never store the text, never suggest deletion). It is the model for every
  sensitive class.
- "Without usage access, never suggest removing an app" (`AndroidPlatform.apps`). It is a working example
  of abstaining when evidence is missing.

### 2.4 Where the current algorithm conflicts with the brief

These are behaviours to change, listed because the brief states the opposite rule.

| Current behaviour | Brief rule | Change |
|---|---|---|
| A photo softer than 25% of the median sharpness becomes a delete option (`BLURRY`, R4) | "Blurry does not mean disposable"; never suggest deletion solely for blur (§6, §7) | Blur alone becomes a *review label*, not a deletion candidate. It may only strengthen a redundancy finding |
| A download older than 180 days and over 1 MB becomes a delete option | "Old does not mean unimportant" (§6) | Age alone gives *review*. A deletion candidate needs a second independent signal (installer, duplicate, temp pattern, re-downloadable source) |
| `Telegram/` and `Android/media/org.telegram.*` files are R1 "nothing lost" | High regenerability "does not prove disposability" (§6) | Keep as a recommendation, but mark regenerability as *assumed*: files saved to the gallery or from deleted chats are not in the cloud. Send the user to Telegram's own clear-cache, as now |
| "Reach my target" mode adds higher-risk tiers until the byte target is met | "Size must never override safety" (§14); primary metric is the false-positive deletion rate (§20) | Keep the mode, but the target can only pull in items that already passed the gates in 6.3. When the gated set is too small, say so; do not relax a gate |
| One risk number per option | Separate dimensions (§6, §13) | Section 6 |
| `Option.COST = {0, 0.5, 1, 3, 5}` and other constants "fixed in the research prototype" | "Do not assign arbitrary weights" (§13) | Keep as starting values, record them as uncalibrated, and calibrate on the corpus in 8 |
| Payment screenshots (90 days), tickets and OTPs become delete options by age | Financial records must never be presumed unnecessary (§10) | OTPs stay (a code is worthless after minutes). Payments and tickets move to *review*, never preselected |

### 2.5 Inconsistencies found in the repository

- `README.md` "Known limits" says the app has **not run on a real device**, while `tools/README.md` and
  the 1.8 notes describe results from a Galaxy S23 FE. The first statement looks out of date.
- `README.md` "Layout" lists `UnitBuilder`, `Planner`, `Report` and `ResultStore` under the OCR assets
  bullet, and `ResultFiles` under `tools/`.
- `AUDIT.txt` is from version 1.7 and predates the tools folder ("no code that deletes / renames files").
- The manifest comment calls all-files access "read-only"; the Lab writes test copies under it.

None of these affect behaviour. They are worth fixing with the first code change.

## 3. Platform constraints and access strategy

### 3.1 What Android allows

| Fact | Source | Consequence |
|---|---|---|
| All-files access gives read and write to shared storage, `MediaStore.Files`, and the roots of SD and OTG volumes | [verified] Android docs, *Manage all files* | The current walker can be extended to other volumes with the same permission |
| It does **not** give access to other apps' directories under `Android/data` | [verified] same page | Never claim it. The app already counts those bytes through `StorageStatsManager` instead |
| The Storage Access Framework cannot open `Android/data`, `Android/obb`, the volume root or the Download root | [search] Android storage FAQ | SAF is a way to add user-chosen folders, not a substitute for all-files access |
| Play evaluates every app that requests all-files access. Uses "likely" allowed: file managers, backup and restore, anti-virus, document management, on-device file search, disk and file encryption, device migration. Use must be core functionality, and MediaStore or SAF must be insufficient | [verified] same Android page. The Play policy page itself (its exceptions and its list of uses that are not permitted) could **not** be read from this environment | Storage cleaners are not on the list that was read. Whether this app qualifies is **unknown**, and it decides the whole access design. See decision D1 |
| New apps and updates on Play must target API 36 since 31 August 2026 | [search] Play target API requirements page | The app targets 34. A Play release needs a target-SDK migration first (edge-to-edge, foreground-service and other behaviour changes) |
| Apps targeting API 35+ get 6 hours per 24 hours of `dataSync` foreground service; then `Service.onTimeout` is called and the service must stop | [search] Android docs, *Foreground service timeouts* | The scan must checkpoint and resume. Today it restarts from zero |
| `MediaStore.createTrashRequest` (API 30+) shows a system confirmation and marks items trashed; restorable for a period reported as 30 days | [search] API reference and press reports; the 30 days is not from the reference page | The recoverable-delete path on Android 11+. Verify the retention on the reference page before showing a number to users |
| Last-access time is not available for files | [hypothesis] shared storage is mounted without reliable atime | Never use "last opened" for files. App usage comes from `UsageStatsManager` only |
| Play requires 16 KB page-size support for native libraries in apps targeting API 35+ | [search] Android docs, *Support 16 KB page sizes* | The app has no native libraries, so it is unaffected. Any future native dependency must be checked |

### 3.2 Recommended access design: tiers of coverage

The scanner should work at whatever level of access it has, and say which level that is.

| Tier | Needs | Covers | Cannot see |
|---|---|---|---|
| A. Full | All-files access | Every file on shared storage and removable volumes | Other apps' `Android/data` and `Android/obb` |
| B. Media | `READ_MEDIA_IMAGES` / `VIDEO` / `AUDIO` (API 33+), `ACCESS_MEDIA_LOCATION` | Photos, videos and audio in MediaStore | Documents, APKs, archives, hidden and temp files |
| C. Picked | SAF tree grants | Folders the user chose | Everything else; blocked roots |
| D. Apps | Usage access (optional) | App sizes, caches, last use | Contents of app data |

Tiers combine. A new `Source` abstraction under `Platform` (`FileSource`, `MediaStoreSource`,
`TreeSource`) yields the same inventory record, with its provenance. The **coverage report** is a
first-class result: per volume, bytes the system reports as used, bytes inventoried, bytes attributed to
apps, and a named remainder ("14.2 GB on this phone belongs to apps or the system and cannot be
inspected"). The overview shows it; the plan never implies completeness.

Tier A is the only one that finds the junk classes (temp files, residue, trash, old backups), so a
Play build restricted to B and C would be a media-and-downloads optimizer, a narrower product.

## 4. Landscape review

### 4.1 Existing tools

| Tool | Approach | Offline | Licence | Takeaway |
|---|---|---|---|---|
| **Czkawka / Krokiet** (desktop, Rust) | Duplicates: group by size → prehash of first and last 4 KB → full hash (Blake3, xxh3 or CRC32). Similar images: perceptual hash (gradient, mean, blockhash, median, double gradient) at 8×8 to 64×64 in a **BK-tree**, grouped by Hamming distance; optional flip and rotate invariance. Similar videos: group by duration, hash frames taken at intervals, needs ffmpeg | Yes | MIT core; the Android front end (Cedinia) is GPL-3.0 and has no video tools because ffmpeg is missing [verified] | Confirms this repo's duplicate pipeline is the standard one. The BK-tree and the duration prefilter are the two ideas to adopt |
| **SD Maid SE** (Android, Kotlin) | CorpseFinder (leftovers of uninstalled apps), AppCleaner, SystemCleaner, Deduplicator, StorageAnalyzer, Media Squeeze, Swiper; optional root, ADB or Shizuku | Yes | GPL-3.0; on Play, F-Droid and GitHub [verified] | The closest product. Its code cannot be copied into this project unless this project becomes GPL. How it gets storage access on Play, and how its deduplicator hashes, were **not** found on the pages read |
| **PDQ** (Meta ThreatExchange) | DCT-based 256-bit perceptual hash with a quality score; match at distance ≤ 31, discard quality ≤ 49; not rotation invariant; Java reference exists | Yes | Licence not confirmed on the page read [verified, except hash length: hypothesis] | A stronger hash than a 64-bit pHash for resized and re-compressed copies. Candidate for stage 3 if the corpus shows pHash is not enough |
| Google Files, Samsung Device Care, gallery "similar photos" | Closed. Rule-based categories (duplicates, large files, old screenshots, memes, unused apps) | Partly | Proprietary | [hypothesis] Useful only as UX references: grouped cards with a size, review before delete, system trash |

### 4.2 Technology selection

"Keep" means the repository already has it. Costs are estimates unless a measurement is cited.

| Need | Recommended | Why | Alternatives considered | Rejected because |
|---|---|---|---|---|
| Index | **Framework SQLite** (`android.database.sqlite`), WAL mode | No dependency; transactional; resumable; queryable | Room, ObjectBox; keeping `ResultStore` | Room needs AndroidX and annotation processing. The blob cannot be updated incrementally |
| Text search | SQLite FTS4 on OCR and document text | Replaces the linear search in `PicturesScreen` | FTS5 | [hypothesis] FTS5 is not guaranteed in every phone's SQLite build; FTS4 is. Verify on the device matrix |
| Exact duplicates | **Keep** SHA-256 with size and quick-hash prefilter; persist both hashes | Collision-safe, and "identical" is a claim that justifies deletion | xxh3, Blake3 | Faster, but need vendored code; hashing is not the bottleneck when results are cached. Never use a non-cryptographic hash as the only proof before a delete |
| Image near-duplicates | **Keep** 63-bit DCT pHash; add a **dHash** from the same decode; index both in a BK-tree or by multi-index hashing; confirm candidates with the existing `Ssim` on small copies | Removes the 10-minute window and same-size limits at about O(n log n). Two different hashes agreeing is real evidence; one is a hint | PDQ; embeddings | PDQ: adopt only if measured recall on resized copies is poor. Embeddings: see below |
| Image embeddings | **None for now** | — | MobileCLIP2-S0 (about 11 M image-encoder parameters, about 43 MB as ONNX [search]); MobileNet/EfficientNet feature vectors | MobileCLIP's weights are under Apple's research licence, which [search] excludes product use. Any embedding model needs a native inference runtime (LiteRT or ONNX Runtime): several MB of native code per ABI, a 16 KB page-size check, and an audit rewrite. The brief says to add embeddings only "where they add measurable value"; the corpus in 8 must show that first |
| Scene and object labels | **Defer**; keep signal-based categories | The categories that drive decisions (documents, receipts, screenshots, IDs, accidental shots) are already found without a classifier. "Travel", "food" or "car" labels do not change whether a file is safe to remove | ML Kit Image Labeling; a MobileNet classifier in plain Java | ML Kit: closed binaries with Google dependencies, against the audited zero-dependency build. A plain-Java CNN at 224 px is feasible like YuNet [hypothesis] but only worth it for browsing, not safety |
| OCR | **Keep** Tesseract (WASM). Add script models as optional assets (for example Devanagari) | Works, licensed Apache-2.0, pinned by hash | ML Kit Text Recognition v2 (bundled, about 4 MB per script per architecture [search]); PaddleOCR | ML Kit: as above. PaddleOCR needs a native runtime. Revisit only if on-device measurements show Tesseract is too slow |
| Faces | **Keep** YuNet in Java. No clustering | — | Face embeddings for "same person" | Biometric data, a much higher privacy bar, and it does not change deletion safety. The brief marks it optional |
| QR and barcodes | **Keep** ZXing | — | — | — |
| PDF text | `PdfRenderer` to render pages, then the existing OCR, on API 26+. Where available (API 35+), `PdfRenderer.Page.getTextContents()` for the embedded text | No dependency | PdfBox-Android (Apache-2.0); MuPDF (AGPL); pdf.js in the existing WebView | PdfBox adds about 2–3 MB of code [hypothesis]. MuPDF's licence is unusable. pdf.js is a real option (pure JS, Apache-2.0, fits the offline WebView already audited) and should be the fallback if render-and-OCR is too slow. `getTextContents` exists in API 35 [search]; the pre-35 backport class was not confirmed to include it |
| DOCX, XLSX, PPTX | `java.util.zip` + `XmlPullParser`: read `docProps/core.xml` and the main text part | These are zip files of XML; about 200 lines of code | Apache POI | POI is tens of MB and known to be awkward on Android [hypothesis] |
| DOC, XLS, PPT (old binary) | Metadata only: detect OLE2 by signature, classify by name and folder | Parsing them properly needs POI | — | Treat as *uncertain*, which means protected |
| Archives | ZIP and APK: list entries with `java.util.zip` (names, sizes, CRC-32). TAR and GZIP: small header reader. RAR and 7z: detect by signature, list nothing | Entry CRCs and sizes allow "this archive's contents also exist unpacked" without extracting | Apache Commons Compress (7z, tar); junrar | [hypothesis] junrar inherits the unRAR licence restrictions. Commons Compress is a reasonable later addition for 7z listing if the corpus shows many 7z files |
| Video similarity | Stage 1: group by duration (± 1 s) and aspect ratio. Stage 2: 8–10 frames at fixed fractions of the duration through `MediaMetadataRetriever`, pHash each, compare sequences | The Czkawka method, with framework decoding in place of ffmpeg. Finds re-encoded and forwarded copies | vPDQ, TMK+PDQF; audio fingerprints | Heavier, and not needed until measured recall is poor |
| Video content | Black or empty and accidental recordings from the same sampled frames, using `ImageSignals`. Screen recordings from folder, name and dimensions equal to the display | Reuses existing code | Scene detection; speech transcription (Whisper, Vosk) | Transcription needs a 40 MB+ model and a native runtime, and no deletion decision depends on it |
| Audio | Exact hash; metadata by `MediaMetadataRetriever` (duration, bitrate, title, artist); "same title, artist and duration ± 2 s" as a *possible duplicate* for review only | — | Chromaprint (LGPL-2.1 [search], native) | Native code; music libraries on phones are small now. Revisit on evidence |
| Inference runtime | **None** | Models so far are small enough for plain Java | LiteRT 2.x (CPU, GPU, NPU; separate GPU and NPU packages [search]) ; ONNX Runtime Mobile | Only justified by a model that passes the test in 7.4 |
| Background work | **Keep** the foreground service; add checkpoints; consider `mediaProcessing` type for compression | — | WorkManager | Needs AndroidX. A framework `JobScheduler` job that requires charging and idle is the dependency-free way to run deep analysis overnight |
| Compression | **Keep** the tools | Measured, guarded, documented | Media3 Transformer | AndroidX; the existing transcoder already works |

## 5. System architecture

### 5.1 Modules

```
                       ┌────────────────────────── app (Android) ──────────────────────────┐
                       │  screens · AnalysisService · AndroidPlatform · ActionRunner (new) │
                       └───────────────▲───────────────────────────────▲────────────────────┘
                                       │ Platform + Source + Index     │ tools (unchanged rule:
 core (pure Java, desktop-testable)    │ interfaces                    │ core never calls them)
 ┌─────────────────────────────────────┴───────────────────────────────┴───────────────────┐
 │ 1 inventory     Scanner → Source(s) → InventoryStore                                    │
 │ 2 cheap signals Signature · Hashes · Metadata · PathRules(Classifier)                   │
 │ 3 analyzers     ImageAnalyzer · VideoAnalyzer · AudioAnalyzer · DocumentAnalyzer ·      │
 │                 ApkAnalyzer · ArchiveAnalyzer · OtherAnalyzer      → Evidence           │
 │ 4 relations     RelationBuilder → typed edges → Groups (canonical copy, collections)    │
 │ 5 decisions     Assessor (dimension vector) → Policy gates → Ranker → Recommendations   │
 │ 6 feedback      Preferences (local, resettable) → Ranker ordering only                  │
 │   anomalies     AnomalyDetector → investigation flags (never actions)                   │
 └─────────────────────────────────────────────────────────────────────────────────────────┘
```

Each analyzer implements one interface: given an inventory record and a budget, return **evidence**
(labels with confidence) and say whether a more expensive stage is worth running. Analyzers do not make
decisions and do not know about each other. `UnitBuilder` is split into `RelationBuilder`, `Assessor` and
`Policy`; `Planner` is kept for the target mode and fed only gated items.

The index is reached through a small `Index` interface with a SQLite implementation on Android and an
in-memory or JDBC-free implementation in `jvmtest`, so `core` keeps its no-Android-imports rule.

### 5.2 Index schema (SQLite, app-private storage)

```
volume(id, uuid, kind[internal|sd|usb], label, last_seen, total_bytes, free_bytes)

file(id INTEGER PK,               -- stable internal id, survives rename and move (see 5.3)
     volume_id, source[file|mediastore|tree], uri, rel_path, parent, name,
     size, mtime, mtime_reliable, ext, detected_type, mime, ext_mismatch,
     area, owner_pkg,
     quick_hash, sha256,           -- nullable; filled on demand
     state[present|missing|trashed|replaced], first_seen, last_seen,
     stage_done,                   -- highest analysis stage completed for this content
     content_key)                  -- size + quick_hash; moves the analysis with the bytes

media(file_id, width, height, duration_ms, codec, bitrate, fps, taken_at, has_gps,
      phash, dhash, sharp, quality_blob, faces, extras[motion|hdr|depth|…])

evidence(file_id, analyzer, analyzer_version, label, confidence, detail, created)
      -- overlapping labels: one row each ("screenshot", "receipt", "has_text", "sensitive:id" …)

text(file_id, body)               -- FTS4. Never written for sensitive classes

edge(id, a, b, type, confidence, evidence, analyzer_version, created)
      -- type: same_bytes | same_pixels | near_duplicate | burst | resized_of | edited_of |
      --       reencoded_of | sent_copy_of | sidecar_of | motion_part_of | installer_of |
      --       older_version_of | contained_in | same_event | scan_of

grp(id, type, canonical_file, confidence);  grp_member(grp_id, file_id, role)

assessment(file_id, retention, redundancy, regenerability, risk, confidence,
           gross_bytes, net_bytes, action, gates_failed, reasons, policy_version, created)

decision(file_id, content_key, choice[keep|protect|approve|always_review|not_useful|wrong_category],
         context, created)        -- the user's choices; keyed by content so they survive moves

action_log(id, file_id, action, state[prepared|approved|executed|verified|failed|undone],
           before_size, before_sha256, result_uri, bytes_freed, error, created, updated)

coverage(scan_id, volume_id, source, bytes_inventoried, files, dirs_unreadable, note)
scan(id, started, finished, state, checkpoint)        -- resumable
pref(key, value, weight, updated)                      -- learned preferences; one table to wipe on reset
```

What is deliberately **not** stored: text of sensitive documents, face crops or face embeddings,
thumbnails of sensitive items, and anything derived about the user as a person.

### 5.3 Identity and change detection

- A file's identity is its `content_key` (size plus quick hash), not its path. On a rescan, a path that
  disappeared and a new path with the same `content_key` on the same volume is a **move**: the row keeps
  its id, its analysis and its user decisions.
- A file is *unchanged* when volume, path, size and mtime match (as now). Size or mtime changed means the
  quick hash is recomputed; if that also changed, analysis is discarded and stage 2 reruns.
- Evidence rows carry the analyzer version. Raising a version invalidates only that analyzer's rows.
- A volume that is absent (SD card removed) marks its files `missing`, not deleted, and their group
  mates lose the redundancy that depended on them (see 6.3, gate G3).

### 5.4 Background execution

- The scan is a sequence of idempotent work items recorded in `scan.checkpoint`. Every N files the stage
  commits a transaction, so stop, crash, `onTimeout` or cancel loses at most one batch.
- Foreground analysis is bounded: stages 1–2 for everything, stage 3 only for candidates (7.1). The
  remainder is **deep analysis**, a `JobScheduler` job that requires charging and idle, plus a manual
  "continue now" button.
- Before each batch, check `PowerManager.getCurrentThermalStatus()` (API 29+), battery level and
  power-save mode; pause or halve the worker count at moderate thermal status and stop at severe.
- Workers: one for decode-heavy analyzers on low-RAM devices (`ActivityManager.isLowRamDevice`), at most
  `min(4, cores − 2)` otherwise. OCR stays single (one WebView).

## 6. Algorithm specification

### 6.1 Evidence

Every signal is a record: `(label, confidence 0–1, source, independent_group)`. Signals in the same
`independent_group` (for example, two path rules) count once when agreement is measured; a path rule and
a content measurement count twice. This is what keeps "three weak rules that all read the folder name"
from looking like strong evidence.

### 6.2 The dimension vector

Each file gets six values. They are kept separate to the end; no weighted sum of them is ever compared
with a threshold to permit an action.

| Dimension | Range | Computed from | Notes |
|---|---|---|---|
| **Retention** `V` | 0–1, default **0.6** | max over value signals: camera original (0.8), has faces (0.9), document or record class (0.8–1.0), unique information (text, code), user "keep" on similar items | Unknown means *valuable*. Only positive evidence of low value lowers it, and never below 0.3 on path evidence alone |
| **Redundancy** `D` | 0–1 | best incoming edge: `same_bytes` 1.0; `same_pixels` 0.98; `sent_copy_of`, `reencoded_of`, `resized_of` 0.6–0.9 by measured similarity; `burst` 0.5–0.8 | 0 without an edge to a **present, verified** canonical file |
| **Regenerability** `G` | 0–1, default 0 | cache and thumbnail 1.0; installer of an installed app at the same or newer version 0.9; cloud-backed app media 0.6 (*assumed*); temp or partial download 0.9 | Carries a flag `assumed` when the source of truth cannot be checked from the device |
| **Risk** `R` | 0–1 | `max(V·(1−D)·(1−G), sensitivity, dependency, irreversibility)` then raised by uncertainty: `R = 1 − (1−R)·C` | Sensitivity: 1.0 for identity, financial, legal, medical and tax classes. Dependency: 1.0 if another file needs it (sidecar, canonical of a group). Irreversibility: 0 with a trash, 0.3 without |
| **Confidence** `C` | 0–1 | `1 − Π(1 − cᵢ)` over independent groups supporting the action, capped at 0.6 with one group, multiplied by 0.5 if any signal contradicts | Low `C` raises `R` (formula above), so uncertainty always moves toward keeping |
| **Storage** `B` | bytes | gross = file size; **net** = bytes actually freed (6.5) | Plus processing cost in seconds for transforms |

The numbers in this table are **starting values**, not calibrated ones. Each lives in one versioned
`Policy` object, and the calibration procedure is in 8.3.

### 6.3 Gates (hard rules, checked before any ranking)

A candidate action that fails a gate is not offered, whatever its size.

| Gate | Rule |
|---|---|
| G1 Sensitive | A file with a sensitive label (ID, bank, tax, legal, medical, insurance, payslip) is `protect`. The only action ever offered is removing a `same_bytes` extra copy, with the canonical copy shown |
| G2 Single reason | No delete candidate rests on one of: age, blur, low resolution, "screenshot", "similar", hidden name, unknown type. Each needs a second signal from a different independent group |
| G3 Canonical survives | A redundancy-based action requires the canonical file to be present, on a volume that is mounted, readable, and re-verified (size and quick hash) in this scan. A copy on a removable volume is never the only survivor |
| G4 Last copy | A group action can never remove every member unless the user ticks every member (the existing all-ticked warning stays) |
| G5 Unknown | Type `bin`, extension mismatch, unreadable, extensionless, or analysis incomplete → `review` at most, never `delete candidate` |
| G6 Dependencies | Not offered for a file that is a target of `sidecar_of`, `motion_part_of`, or is inside a detected project tree (`.git`, `.nomedia`-marked app folder, `Android/media/<installed app>`) |
| G7 Transform safety | Compress or convert only if the tool's own refusal rules pass (HDR, depth, Motion Photo, wide colour) and the measured guard passes on the real output |
| G8 Personalization | Learned preferences may reorder and may raise protection. They can never satisfy or relax G1–G7 |
| G9 Apps | No app action without usage access; never essential or system apps (as today) |

### 6.4 Actions

`protect` · `keep` · `review` (no suggestion, just shown) · `delete candidate` · `keep best version`
(group) · `compress` · `convert` · `trim` (Motion Photo clip) · `archive` · `group` (organise only) ·
**`abstain`** (not shown as a recommendation; counted in the coverage report as "not assessed").

```
assess(file):
    ev   = evidence(file) ; rel = edges(file)
    V,D,G,C = dimensions(ev, rel)
    R    = risk(V, D, G, C, sensitivity(ev), dependency(rel), reversibility(volume))
    if sensitive(ev):                      return PROTECT            # G1
    cands = []
    for act in candidate_actions(file, ev, rel):                      # category policy proposes
        if not gates_pass(act, file, ev, rel): continue               # G2..G9
        net = net_bytes(act, file, rel)
        if net <= MIN_NET[act]: continue
        tier = tier_of(act, D, G, R, C)                               # table below
        cands.append((act, tier, net))
    if cands is empty:
        return ABSTAIN if C < C_MIN and stage < MAX_STAGE else KEEP
    return cands            # mutually exclusive options, lowest tier first

tier_of:
    Safe            G ≥ 0.9 not assumed, V ≤ 0.3, C ≥ 0.9              (cache, thumbnails, stale temp)
    No loss         D ≥ 0.98 with verified canonical, C ≥ 0.9          (identical copies; lossless)
    Looks identical transform with measured guard pass
    Trade-off       transform with stated, measured quality loss
    Your call       everything else that passed the gates
```

The five tier names are the ones the app already shows, so the UI vocabulary does not change. What
changes is that a tier is now *derived* from the dimensions and can be explained from them, and that
"Your call" items are never preselected.

### 6.5 Net savings

- Delete: file size, counted once per group (the canonical file's bytes are never counted).
- Group "keep best": sum of non-canonical members.
- Compress or convert: `size − measured_or_estimated_output`. Estimated values are labelled; after a
  calibration sample (`Calibrator`) they are labelled "measured on N of your files".
- Trim: bytes after the photo, exact.
- Zip: `sum(inputs) − archive_size` **only if positive and above 5%**; otherwise the recommendation is
  presented as organisation, with "frees: none".
- Trash: freed bytes are shown as **pending** until the trash empties, because trashed items still
  occupy space.
- A file appears in at most one recommendation's total (the existing `Unit` rule).

### 6.6 Ranking

Among recommendations that passed the gates, order by tier, then by

```
priority = net_bytes × C × max(D, G) × (1 − R) / (1 + effort)
effort   = number of separate confirmations the user must give (a group counts as one)
```

then apply learned ordering (6.8). This is a display order. It is computed after the gates and cannot
bring a file into the list.

### 6.7 Category policies

| Family | Protect by default | May be a delete candidate when | Other actions |
|---|---|---|---|
| Camera photos and videos | Everything; faces raise `V` | `same_bytes` extra; burst extra with a measured better shot (kept as "Your call") | compress, trim Motion Photo, group |
| Screenshots | Chats, anything with a sensitive label | OTP older than a day; exact duplicate; near-identical consecutive screenshots (keep newest) | lossless WebP |
| Received chat media | Photos with faces; documents | Sent copy with a verified original; exact duplicate; greeting with text evidence **and** received in a chat **and** older than a week | compress |
| Downloads | Documents until classified | Installer of an installed app; duplicate download (`name (1).ext` **and** same bytes); stale partial download | review list by size |
| Documents | Financial, legal, identity, medical, tax, work, education: always | `same_bytes` extra only. "Superseded version" is shown as a group for review, never as a candidate | — |
| Video | Camera video | Exact duplicate; re-encoded copy (`reencoded_of` ≥ 0.9) of a present original; black or empty recording as "Your call" | compress at five levels |
| Audio | Voice notes and recordings (unique by nature) | Exact duplicate | re-encode music above 256 kbps (trade-off tier) |
| APK | — | Installed at the same or newer version; older of two versions of one package (keep newest) | "These are installers, not the installed apps" |
| Archives | Backups; anything that cannot be listed | Every entry exists unpacked with the same CRC and size (regenerable from the files), as "Your call" | — |
| App data | — | Never deleted by this app | Open the app's settings; archive or uninstall is the user's action |
| Hidden, temp, unknown | Unknown and hidden files | Known patterns only (`.trashed-*`, `.pending-*`, `*.crdownload`, thumbnails) | anomaly flag |

Documents are classified by rules on extracted text, extending `TextRules` (already 21 tested cases for
pictures) with the classes in brief §10, into: temporary, reproducible, duplicate, superseded, important,
sensitive, uncertain. Extracted dates, amounts and invoice numbers are stored as evidence detail and used
for search and for `scan_of` edges (a photo and a PDF with the same invoice number and total).

### 6.8 Relationships

Edges are built cheapest first, and each kind names its evidence:

| Edge | Candidate generation | Confirmation | Confidence |
|---|---|---|---|
| `same_bytes` | equal size → equal quick hash | full SHA-256 | 1.0 |
| `same_pixels` | equal dimensions, pHash distance 0 | decoded pixels equal on a 256 px copy | 0.98 |
| `near_duplicate`, `resized_of` | BK-tree search, pHash ≤ 10 **and** dHash ≤ 10, aspect ratio within 2% | SSIM on 256 px copies ≥ 0.90 | by SSIM |
| `burst` | as above, plus taken within 10 minutes and same camera | — | 0.5–0.8 |
| `edited_of` | same base name with an edit suffix (`-edited`, `_1`, `(1)`), or EXIF software tag, plus near-duplicate | — | 0.7 |
| `reencoded_of` (video) | duration ± 1 s and aspect ratio | ≥ 80% of sampled-frame hashes within distance 10 | by match share |
| `sent_copy_of` | existing rule | add SSIM confirmation | 0.6–0.9 |
| `sidecar_of`, `motion_part_of` | same base name and folder with a known sidecar extension (`.xmp`, `.aae`, `.srt`, `.dng`+`.jpg`) | — | 0.9 |
| `installer_of`, `older_version_of` | APK package and version | — | 1.0 |
| `contained_in` | archive entry size and CRC-32 equal to a file's | full CRC of the file | 0.95 |
| `same_event` | same day and folder, or GPS within 1 km and 6 hours | — | 0.4 (grouping only) |
| `scan_of` | equal extracted identifiers (invoice number plus amount) | — | 0.6 (grouping only) |

The canonical member of a group is chosen by, in order: user choice, original over derived (edge
direction), highest resolution, least compressed, best `PhotoQuality`, most canonical folder (the
existing priority list), internal volume over removable.

"Grouping only" edges never raise redundancy. They exist so the UI can show collections and so that
deleting one item shows what it is connected to.

### 6.9 Anomalies

Statistical flags, shown under "Worth a look", each with its reason and what is unknown:

- file larger than the 99th percentile for its type and area (robust z-score on log size);
- a folder holding more than 5% of inventoried bytes outside the known media areas;
- a folder that grew by more than 20% and 500 MB between two scans (needs scan history, kept in `coverage`);
- duplicate directory trees: two folders whose sorted lists of `(name, size)` hash equally, confirmed by
  member `same_bytes` edges;
- repeated exports: more than five files with one base name and a counter or timestamp suffix;
- extension mismatch, extensionless files over 10 MB, and stale local backups (several dated files of
  one pattern; keep-newest is suggested for review only).

An anomaly never creates a delete candidate by itself (G2, G5).

### 6.10 Personalization

- Input: rows in `decision`. Each is attributed to features of the recommendation it answered: technique,
  area, label, and size bucket.
- Model: per feature, a Beta posterior of "the user accepts" with prior Beta(2, 2) and exponential decay
  (half-life 90 days). A feature only has an effect after at least 5 decisions. One accidental tap moves
  nothing.
- Effect: multiply the ranking `priority` by a factor in [0.5, 1.5]; move a technique the user mostly
  rejects below the fold; raise retention for content the user protects. **No effect on gates or tiers**
  (G8). Accepting 500 burst deletions never preselects the 501st.
- Explanation: a recommendation moved by a preference says so ("Shown lower because you usually keep
  chat photos").
- No personal attributes are inferred; features are properties of files and recommendations only.
- Reset: one button drops `pref` and, separately, `decision`.

## 7. Performance plan

### 7.1 Stages and their budgets

| Stage | Runs on | Cost per file (estimate) | Decides the next stage by |
|---|---|---|---|
| 1 Inventory | Everything | about 0.1 ms (directory listing) | — |
| 2 Cheap signals | Everything new or changed: 32-byte signature, path rules, MediaStore or header metadata. Quick hash only for files sharing a size | under 1 ms; quick hash about 1 ms | Full hash only when quick hashes collide |
| 3a Image features | Images over 20 KB: one decode at 512 px → pHash, dHash, sharpness, `ImageSignals` | 50–250 ms | OCR only if text or paper signals are present (today 8 of 78 everyday photos); faces and quality only inside groups |
| 3b Video frames | Videos sharing a duration bucket with another, or over 50 MB | 0.3–1 s for 8 frames | — |
| 3c Documents | PDFs and Office files, newest and largest first | 0.05–3 s (text layer vs OCR) | OCR only when there is no text layer; at most 3 pages |
| 4 Relations | Index queries | milliseconds per file | SSIM confirmation only for candidate pairs |
| 5 Decisions | All assessed files | microseconds | — |

Time-to-first-plan target: stages 1–2 and the exact-duplicate, installer, junk and large-file
recommendations within **60 seconds for 50,000 files** on a mid-range phone; media intelligence fills in
afterwards. This is a target to be measured, not a claim.

### 7.2 Caching and incremental work

- All derived data is keyed by `content_key` and analyzer version (5.3). An unchanged library costs one
  directory walk and one indexed comparison per file.
- Hashes are stored, so a second scan re-hashes only new and changed files.
- BK-tree nodes are rebuilt in memory from the `media` table at start (65,000 hashes is about 1 MB).

### 7.3 Benchmark plan

| Class | Example | Purpose |
|---|---|---|
| Low end | 3–4 GB RAM, eMMC, Android 10–12, an Android Go phone | Memory ceilings, slow storage, old WebView |
| Mid range | 6–8 GB RAM, UFS, Android 13–15 | Reference for targets |
| Flagship | Galaxy S23 FE (already used) or newer, Android 16 | Encoder behaviour, thermal throttling |

Measured per run, with libraries of 5,000, 30,000 and 100,000 files: wall-clock per stage, files per
second, peak PSS (`Debug.MemoryInfo`), battery percent and mAh (`BatteryManager`), peak thermal status,
index size on disk, and APK size. The Lab log already records device, Android and WebView versions and
is the natural place to record these.

### 7.4 Test a model must pass before it is added

1. A decision in 6.7 changes because of it, on the corpus, and the false-positive rate does not rise.
2. Its licence allows redistribution in a product.
3. It runs within the budgets on the low-end device: cold start under 1 s, under 300 ms per image, peak
   memory under 150 MB.
4. It can be pinned by hash and checked by the audit, and it adds no network-capable code.

By this test YuNet, the eye model and Tesseract pass (2–4 partly on desktop evidence only); an embedding
model currently fails 1 and 2.

## 8. Testing and evaluation

### 8.1 What exists

`CategoryTest` (399 pictures), `PhotoQualityTest`, `FaceNetTest`, `EyeStateTest`, `TextRulesTest`,
`DecisionTest`, `PlannerTest`, `GroupDecisionTest`, the compression tests, and the virtual phone in
`JvmPlatform`. These become regression tests for the refactor: the same inputs must give the same or
safer outputs.

### 8.2 Labelled corpus to add

A virtual phone built by script (like `tools/categories/build.py`), each file labelled with the expected
action class and a **must-not-suggest-deletion** flag. It must contain the brief's hard cases:

| Case | Expected |
|---|---|
| Only copy of a blurry family photo | keep; never a candidate |
| Photo and its edited version | grouped; neither preselected |
| Burst of five with one blink | group; best kept; "Your call" |
| `report.pdf` and `report (1).pdf`, same bytes | one is a "No loss" candidate |
| `report.pdf` and `report (1).pdf`, different bytes | review group; no candidate |
| Ten-year-old PDF of a property deed | protect |
| Photo of an ID card, and a second identical copy | protect; the copy is the only candidate |
| Video and its WhatsApp re-encode | `reencoded_of`; the small copy is the candidate, never the original |
| JPEG named `.pdf`; extensionless 200 MB file | review; anomaly flag |
| Folder under `Android/data` | counted as not inspected |
| Duplicate whose original is on an SD card | no candidate (G3) |
| RAW + JPEG pair, `.xmp` sidecar | protected by G6 |
| Motion Photo, Ultra HDR photo, Display P3 photo | compression refused or WebP only (existing rules) |
| APK older than the installed app; APK newer than it | candidate; keep |

### 8.3 Metrics and targets

| Metric | Target for release | Notes |
|---|---|---|
| **False-positive deletion rate**: share of must-not items offered at "Safe" or "No loss" | **0** on the corpus | The primary metric. Any failure blocks a release |
| Same, at "Your call" | under 1% per category | Reported per category |
| Exact-duplicate precision / recall | 100% / 100% | Deterministic |
| Near-duplicate precision / recall | ≥ 99% / ≥ 85% | Precision is tuned first; recall is traded away |
| Category accuracy | no regression from 87%; per-category table kept | |
| Document class accuracy; sensitive recall | ≥ 85%; **≥ 98%** | A missed sensitive file is the costly error |
| Estimated vs actual bytes freed | within 10% for deletions; estimates within 25%, labelled | Checked after execution from `action_log` |
| Scan completeness | inventoried bytes ÷ bytes visible with the granted access ≥ 99% | Per tier |
| Undo success | 100% of trashed items restorable in test; interrupted action leaves the original intact in 100% of fault-injection runs | |

Calibration: thresholds in `Policy` are chosen on the corpus by fixing the false-positive constraint
first and then maximising recovered bytes, and the chosen values and the corpus version are recorded in
the policy. On-device decisions are never uploaded; the only "user data" used for tuning is what the
developer exports by hand from the Lab.

## 9. Safety and privacy design

### 9.1 Local-only guarantees

| Guarantee | How it is enforced today | To add |
|---|---|---|
| Nothing leaves the device | No `INTERNET` permission; audit fails on network APIs; WebView serves only bundled files and returns empty for anything else | Keep. Any new asset is pinned by SHA-256 like the OCR and face files |
| No closed or remote code | No dependencies; audit forbids reflection and code loading | Keep as a rule for technology choices (4.2) |
| Index stays private | App-private storage; `allowBackup="false"` | Keep `allowBackup` off, or the index (file names, text) would go to cloud backup. Add "Delete all analysis data" |
| Sensitive text is not kept | ID pictures: text never stored | Extend to all sensitive document classes; exclude from FTS and from exported reports |
| Exports are the user's act | Report and Lab log written to Download on request | Reports omit extracted text by default |

### 9.2 The cleanup transaction

The action layer is new. It lives in `app` (an `ActionRunner`), calls `tools`, and is the only code
allowed to remove anything.

```
PREPARE   build the batch from approved recommendations; compute net bytes; check free space for
          transforms (need output size + margin)
VALIDATE  per file, immediately before acting: exists, same size, same mtime, same quick hash as the
          index; canonical copy of its group re-verified by full hash; volume mounted; permission held
APPROVE   one screen: exact items, previews, reason, tier, net bytes, reversibility. Android 11+: the
          system's own trash dialog follows
EXECUTE   delete   → MediaStore trash request (API 30+); below 30 or for non-media files, move into an
                     app-managed quarantine folder on the same volume, kept 30 days
          transform→ tool writes a new file; guard checks it; scanner indexes it; the original is then
                     trashed as a separate, approved delete
VERIFY    confirm each item's new state; measure bytes actually freed; never report from the estimate
RECORD    action_log row per item with before-hash, result, error; summary shows done / failed / skipped
UNDO      from the log: untrash, or move back from quarantine; a transform's original is restorable until
          the trash empties
```

Failure handling:

| Event | Behaviour |
|---|---|
| File changed since the scan | Skip it, mark it for re-analysis, tell the user how many were skipped |
| Permission revoked mid-batch | Stop; items already done stay logged; the rest return to "approved" |
| Volume removed | Stop actions on that volume; G3 re-evaluates groups that relied on it |
| Low space during a transform | Stop before writing; never delete an original to make room |
| Process killed | On restart, read `prepared` and `executed` rows without `verified`, verify each, and resume or roll back. Outputs are written to a temporary name and renamed last |
| Partial failure | Report exact counts; success is only stated for verified items |

Disclosures the UI must make (from `tools/README.md` and platform behaviour): trashed files keep using
space until the trash empties; backup apps will upload converted copies again; WhatsApp shows a
converted or removed chat image as missing; a copy does not keep XMP edit history.

The audit's allowlist changes from "no MediaStore trash, delete or write requests anywhere" to "only in
`ActionRunner`", and a new audit rule checks that `ActionRunner` is reachable only from the approval
screen.

## 10. Decisions needed before Phase 1

| # | Decision | Options | Recommendation |
|---|---|---|---|
| D1 | **Distribution** | (a) Sideload / GitHub / F-Droid only; (b) Google Play | Decide first. (a) keeps all-files access and the current design. (b) needs API 36, a Play declaration for all-files access whose outcome is uncertain, and a fallback build on media permissions and SAF. Build the `Source` abstraction either way so (b) stays possible |
| D2 | **May the app act on files?** | (a) stay read-only; (b) add the action layer in 9.2 | (b), in Phase 2, trash-only at first. It changes the product's central promise ("it only reads") and the README must say so |
| D3 | **Dependency rule** | (a) keep zero dependencies and no native code; (b) allow vetted libraries | (a) through Phase 4. Revisit only with corpus evidence (7.4) |
| D4 | **Minimum Android version** | keep 26, or raise to 29/30 | Keep 26 for analysis. Offer destructive actions with system trash on 30+, quarantine below |
| D5 | **Scripts for OCR** | English only, or add Indic scripts | The text rules are India-specific (UPI, Aadhaar, PAN, GSTIN), so Devanagari is the first to add; each script is about 4 MB of pinned assets [hypothesis] |
| D6 | **Reach-my-target mode** | keep as the default, or make "safe steps" the default | Make safe steps the default; keep the target as a progress marker (2.4) |

## 11. Implementation roadmap

Effort is in working weeks for one developer, as a rough guide. Each phase ends with the full `jvmtest`
suite and the corpus passing.

| Phase | Work | Exit criteria | Effort | Main risk |
|---|---|---|---|---|
| **0 Discovery** | This document; answers to D1–D6; read the Play all-files policy page; fix the README inconsistencies (2.5) | Decisions recorded | 0.5 | D1 unanswered |
| **1 Inventory** | SQLite index behind an `Index` interface; `Source` abstraction (file walker, MediaStore, SAF tree); all volumes; signatures everywhere plus extension mismatch; persisted hashes; move detection; checkpointed, resumable scan; coverage report; `ResultStore` migration | 50,000-file virtual phone: second scan does no re-hashing; kill at any point resumes; coverage screen shows inventoried vs not inspected; exact-duplicate precision and recall 100% | 4–5 | The refactor touches every screen through `Session` |
| **2 High-value cleanup** | Dimension vector, gates and derived tiers (6.2–6.6); the behaviour changes in 2.4; typed edges for the three existing relations; APK version groups; large-file and old-download review lists; **action layer** with trash, log, undo (9.2); audit update | False-positive rate 0 at "Safe" and "No loss" on the corpus; fault-injection tests for the transaction; estimated vs actual within 10% | 5–6 | Trash and delete behaviour differs by Android version and vendor; needs real devices |
| **3 Media intelligence** | dHash and BK-tree; global near-duplicates with SSIM confirmation; resized and edited lineage; overlapping labels; video frame sampling, re-encode detection, black and accidental recordings; wire the compression and Motion Photo tools into the plan through the action layer | Near-duplicate precision ≥ 99%, recall ≥ 85%; budgets in 7.1 met on the low-end device; compression originals survive every failure case | 5–6 | Decode cost on low-end phones; encoder differences between vendors |
| **4 Documents and relationships** | PDF and OOXML text; document classes and sensitive protection; archive listing and `contained_in`; sidecar, version and cross-format edges; collections; FTS search over everything | Document class accuracy ≥ 85%, sensitive recall ≥ 98%; no sensitive text in the index or reports | 4–5 | PDF OCR speed; rule coverage beyond English |
| **5 Personalization and anomalies** | Decision log by content key; Beta-posterior ranking; explanations; reset; anomaly flags and scan history | Ranking changes are explained; corpus safety metrics identical with personalization on and off | 2–3 | Overfitting to few decisions |
| **6 Hardening** | Device matrix; thermal and battery tuning; `JobScheduler` deep analysis; SD and OTG removal tests; permission revocation; accessibility pass on new screens; target-SDK migration if D1 is Play | Targets in 7.3 and 8.3 met and recorded per device | 3–4 | Vendor background limits |

Total: about 24–30 weeks. Phases 1 and 2 are the ones that change what the app is; 3–5 add reach.

## 12. What was not verified

- The Google Play all-files access policy page (permitted uses, exceptions, uses not permitted) could not
  be opened from this environment. The list in 3.1 comes from the Android developer page that summarises
  it. **Read the Play page before deciding D1.**
- How SD Maid SE obtains storage access on Play, and how its deduplicator works.
- The licences of PDQ, junrar and Apache Commons Compress as they apply here; MobileCLIP's licence terms
  were seen only through a search summary.
- The trash retention period, FTS5 availability, and whether the pre-API-35 PDF backport exposes page text.
- Every per-file cost in section 7 is an estimate from desktop timings in the README, not a phone
  measurement.

## Sources

Read directly:

- Android Developers, *Manage all files on a storage device*: https://developer.android.com/training/data-storage/manage-all-files
- Czkawka repository and core instructions: https://github.com/qarmin/czkawka , https://github.com/qarmin/czkawka/blob/master/instructions/Instruction_Core.md
- SD Maid SE repository: https://github.com/d4rken-org/sdmaid-se
- Meta ThreatExchange, PDQ: https://github.com/facebook/ThreatExchange/tree/main/pdq

Found through search (summaries only):

- Google Play, *Target API level requirements*: https://support.google.com/googleplay/android-developer/answer/11926878
- Google Play, *Use of All files access permission*: https://support.google.com/googleplay/android-developer/answer/10467955
- Android Developers, *Foreground service timeouts*: https://developer.android.com/develop/background-work/services/fgs/timeout
- Android Developers, `MediaStore` reference: https://developer.android.com/reference/android/provider/MediaStore
- Android Developers, *Android 11 storage FAQ*: https://medium.com/androiddevelopers/android-11-storage-faq-78cefea52b7c
- Android Open Source Project, *Scoped storage*: https://source.android.com/docs/core/storage/scoped
- Android Developers, *Support 16 KB page sizes*: https://developer.android.com/guide/practices/page-sizes
- Android Developers, `PdfRenderer.Page` reference: https://developer.android.com/reference/android/graphics/pdf/PdfRenderer.Page
- ML Kit, *Text recognition v2* and *Installation paths*: https://developers.google.com/ml-kit/vision/text-recognition/android , https://developers.google.com/ml-kit/tips/installation-paths
- LiteRT for Android: https://developers.google.com/edge/lite/android
- Apple, MobileCLIP: https://github.com/apple/ml-mobileclip
- Chromaprint packaging metadata (Fedora): https://src.fedoraproject.org/rpms/chromaprint

Repository documents this builds on: `README.md`, `app/src/main/java/com/storageplanner/tools/README.md`,
`docs/video-compression-research.md`, `AUDIT.txt`.
