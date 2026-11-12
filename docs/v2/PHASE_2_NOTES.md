# Phase 2 — implementation notes

Records where the implementation departs from `PHASE_2.md` (with its binding `REVIEW_1` edits), or had to choose because
the design was ambiguous. Each entry says what the design said, what was done, and why.

## Slices

| Slice | State |
|---|---|
| 1 Type detection, MIME, mismatch, common metadata (name dates, createdAt, integrity, confidence, status) | done |
| 2 Hashing tier (size → quick hash → SHA-256 + CRC32), exact-duplicate group ids | done |
| 3 Image basics (JPEG/PNG/GIF/WebP/BMP/TIFF/HEIF probes, EXIF, T3 decode, ImageBasics, pHash/sharpness) | done |
| 4 ZIP listing with safety limits, subtypes, APK manifest/signer/install state | done |
| 5 PDF basics incl. encryption | done |
| 6 MP4/MOV/3GP/M4A basics | done; **AudioProbe (mp3/flac/ogg/opus/wav) not done** |
| 7 Installed apps / app-cache data | partial: `InstalledApp` has the F-M11 size/usage fields, `JvmInstalledApps` reads them when present, the installed set reaches discovery when availability is FULL; no CLI `apps` section |
| 8 Incremental re-extraction + budgets | done (time / bytes / decodes; pause; resume) |

## Deviations and choices

| # | Design said | Done | Why |
|---|---|---|---|
| P1 | `ExtractionScheduler` with worker threads, reorder buffer, `CommitPolicy` | `ExtractionLoop`: single-threaded loop, commit every 500 jobs or 3 s, cancel/pause/throttle/budget checked before each job. `--workers` is accepted and ignored. | A11 (single-threaded until Phase 10). Order is deterministic, so the reorder buffer is not needed. |
| P2 | `MkvProbe`, `AviProbe`, `TarProbe`, `AudioProbe`, GIF frame counting, ICO probe | Not written. TAR gets `archive.listingStatus = UNSUPPORTED`, problem `tar:DEFERRED_PHASE6`. mp3/flac/ogg/opus/wav have type + MIME but `audio == null`. GIF has dims, `frames`/`animated` unknown. | A11. These fields stay unknown, which is safe. |
| P3 | One class per probe | `ImageProbes` holds the JPEG/PNG/GIF/WebP/BMP/TIFF probes; `Archives` is the T2 Part A dispatch; the hash part lives in `T2HashStructStage` (no `HashStage` class). Helpers added: `ProbeCtx`, `Probes`, `Tiers`, `Dates`, `Mpeg`, `model.Sub`. | Less code; the same behaviour. |
| P4 | `AxmlParser` parses binary XML | Parses binary AXML **and** a text-XML `AndroidManifest.xml`. The text form gives `parseSource = "ZIP_TEXT"` (a new value) and problem `apk:TEXT_MANIFEST`. | Every dataset APK has a plain-text manifest stand-in. Real APKs use binary AXML (`ZIP_AXML`), which is tested with an AXML encoder. |
| P5 | v1 signer = SHA-256 of the first certificate in the PKCS#7 | If the signature file is not PKCS#7 SignedData, the SHA-256 of the **whole file** is used, with problem `apk:APK_V1_NOT_PKCS7_FILE_HASH`. | The dataset's `CERT.RSA` files are not PKCS#7 stand-ins, and `installed_apps.json` defines the certificate hash as `sha256(META-INF/CERT.RSA)`. Real PKCS#7 still uses the certificate. |
| P6 | Bundles: parse the base APK manifest if STORED or ≤ 32 MiB deflated | Bundle type (`apks`/`xapk`) is detected and `apk.bundle = TRUE`, but the base APK is not parsed (`problem = BUNDLE_BASE_NOT_PARSED`, or `BUNDLE_BASE_TOO_LARGE`). | This needs nested inflation, which was out of time. Install state stays UNKNOWN for bundles. |
| P7 | `CAM_YMD_HMS` requires `[_-]` between the date and the time | The separator is optional: `[_-]?`. | 230 Drive names are `IMG20240628095906.jpg`. The design's "every `IMG2024…` must parse" needs it. `IMG_1976.HEIC` (no date) is not counted by the test. |
| P8 | `irot 1` → orientation 6 (test table) | `irot` is an anticlockwise angle: 1 → 8, 2 → 3, 3 → 6. | ISO/IEC 23008-12. The upright dimensions are the same either way. The Drive HEICs come out 4284×5712 upright, orientation 6, which matches their EXIF. |
| P9 | Exact-duplicate groups are Phase 3 | T2 sets `FileRecord.duplicateGroupId = "x:" + sha256` for PRESENT files that share a computed SHA-256 (size > 0). No `DuplicateGroup` records are written. | The lead asked for group ids in slice 2. Phase 3 owns the table, keepers and A19. |
| P10 | `FileUpsert` clears bits 1–3 on CHANGED/ADDED and sets them pending | The bits move to pending only when the record had a done or failed bit. New records get their bits from T1. | Otherwise Phase-1-only scans (`maxTier 0`) would show pending tiers, which would change Phase 1 results. |
| P11 | Selection by fingerprint, versions, and FULL + `extractedScanId` | The same, plus: T2 Part A and T3 count as current per scan through `extra["t2scan"/"t3scan"]`. On a FULL scan, all three tiers re-run for every file. `--kind incremental` re-extracts only changed files and changed versions. | This is the design's FULL rule. The dataset's incremental re-run has `extractedThisScan = 0` and reads 0 bytes. |
| P12 | PDF > 8 MiB: only head 4 MiB + tail 4 MiB read | Also reads T1's shared 64 KiB head and 64 KiB tail. The total is ≤ 8 MiB + 128 KiB + 2 KiB. | The head/tail buffers are shared by every probe. The test bound was adjusted to match. |
| P13 | `PARTIAL` when an applicable tier ≤ maxTier is pending | Pending tiers above `maxTier` also give PARTIAL. | The design's V2Cli case "`--max-tier 1` → status PARTIAL" requires it. |
| P14 | JPEG integrity: OK only for EOI at the end / motion photo / ftyp / Samsung trailer | EOI followed by any other trailer gives `UNKNOWN` + `jpeg:TRAILING_DATA`. This problem is not counted as a probe problem for confidence. | 167 of the 232 Drive JPEGs (OnePlus) end in a watermark JSON trailer after EOI. They are not truncated, so they must not be marked TRUNCATED or have their confidence lowered as if the probe were partial. |
| P15 | Decoder subsampling to about 2× the target | `round(longEdge_src / 2048)`. "No ImageIO reader" → `UNSUPPORTED_FORMAT`. | A 4000 px photo then decodes at 2000 px instead of at full size. |
| P16 | `Budget(maxMillis, maxBytes, maxDecodes, clock)` | The Phase-1 `Budget` (public fields) gained `chargeBytes`, `chargeDecode` and `stoppedBy(clock)`. | Phase 1 callers keep compiling. |
| P17 | `Pipeline` default stages filtered by `maxTier` | `Pipeline.defaultStages(config, device)`. `PipelineConfig.maxTier` still defaults to 0 (discovery only). `V2Cli` defaults to `--max-tier 3`. | Phase 1 tests and callers are unchanged. |
| P18 | Evaluator type accuracy uses a `category → types` table | Manifest categories are semantic (`travel_scenery`, `receipt`…), so `evaluate.py` uses its own byte-sniff oracle for the expected family and reports accuracy per category. Exact-duplicate precision counts the predicted pairs that are byte-identical by manifest SHA-256. Precision against the labelled pairs is also printed (0.9923: one identical pair is not labelled). | A table keyed by semantic category cannot give an expected type. |
| P19 | Mismatch flags on "misleading filename" files | Gated: `misleading_extension` (JPEG saved as `.pdf`) → SUSPICIOUS. Reported but not gated: `misleading_name` (`IMG_0001.jpg` holding a JPEG → NONE, correct), `temp123.tmp` holding a PDF → UNKNOWN (`.tmp` has no expected type). | Mismatch compares the extension with the content. The name's wording is a Phase 4 signal. |
| P20 | Phase 1 gate: every manifest file found | Files under `Android/data` / `Android/obb` must be missing **and** covered by a `coverage.inaccessible` location. | Lead ruling (A18). |
| P21 | V2Cli: no memory option | Added `--measure-memory` (A12): used heap after GC before/after the scan, and the snapshot JSON size, printed on stderr. | A12 measurement. |
| P22 | Tests of §11 | Written: TypeDetector (+ExtensionMap, NameDates), HashStage, Image (probes, EXIF, HEIF, ImageBasics, JvmMediaDecoder, T3, unknown handling), ZipApk (ZIP, subtypes, AXML, signing block, v1, install state), PdfProbe, Mp4Probe, IncrementalExtraction (versions, FULL, unreadable, changed during read, decode budget, resume). **Not written:** ClassicAgreementTest, SchedulerTest (N/A, P1), AudioProbe/MkvAvi/Tar tests (P2), V2CliPhase2Test (covered by the dataset gate run by hand), the throttle test. | Time. |

## Dataset checks

* `/home/user/sp-dataset`, `--hash all`, installed apps given: `--gate phase1` PASSED (2,405/2,406 found; the one
  `Android/obb` file is missing and its location is reported inaccessible). `--gate phase2` PASSED: type accuracy 2377/2377,
  SHA-256 2405/2405, exact-duplicate precision 1.0 (130 pairs) and recall 129/129, encrypted PDFs 5/5 with 0 false positives, path
  traversal / high ratio / nested 1/1 each, APK identity 10/10, install state and signer 10/10, `misleading_extension` 2/2,
  unknown handling 48/48. Statuses: COMPLETE 2342, PARTIAL 48 (WebP: no JVM decoder), UNSUPPORTED 16.
  Incremental re-run on the same state: `extractedThisScan = 0`, 0 bytes read. T1 throughput ≈ 1,900 files/s (warm cache).
  With the default `collisions` policy, 302 of 2,406 files carry a SHA-256 (297 hash records; empty files use the constant), all correct.
* Drive corpus (268 files): 232 JPEGs decoded with pHash and sharpness; 36 HEIC: type `heic`, dimensions known, `NO_DECODER`,
  PARTIAL, no numeric pixel feature. `createdAt` from EXIF 237, FILENAME 31 (100 % EXIF or FILENAME). Warm scan 42 s
  (T3 decode 41.6 s ≈ 180 ms per 12–50 MP JPEG). A cold first run took 92 s (T1 32 s of first reads).
* **A12**: dataset, after GC: ≈ 2.7 KB retained heap per file (2,406 files with features, 297 hash records). The snapshot is ≈ 2.1 KB
  per file (5.2 MB). Drive: 2.3 KB snapshot per file (the heap figure there, 6.2 KB, is dominated by ImageIO class loading over few
  files). **This exceeds the A12 limit of about 1 KB per file.** Two ways to cut it: the snapshot JSON writes every unknown field as
  `null` (omitting them would roughly halve it), and `ImageInfo` has about 30 fields. Otherwise A12 says SQLite comes forward.

## For Phases 3–5

* `FileRecord.sha256` exists only for size collisions, entry-size matches and files ≤ 128 KiB in a collision group (A10). A
  size-unique file has `sha256 = null` and bit 2 "done" (provisional). Hash on demand before any destructive recommendation (V3).
* `duplicateGroupId = "x:" + sha256` is set already. Phase 3 should build the `DuplicateGroup` records from it (and apply A19).
* Archive `entries` are stored only for plain zips. Each entry's CRC32 and size are present. `HashRecord.crc32` is filled whenever a full hash ran.
* `extMismatch` SUSPICIOUS / BENIGN feeds `R_NAME_CONTENT_MISMATCH`. `integrity` is a heuristic only (H4b). Many real JPEGs are
  `UNKNOWN` (`jpeg:TRAILING_DATA`), and that must not be treated as damage.
* `apk.signerCertSha256` is not verified (`apk:SIGNATURE_UNVERIFIED` is always present). For the dataset it is the whole-file hash (P5).
* Audio duration is unknown except for m4a. Bundles have no install state (P6). TAR archives are not listed (P2).
* FULL scans re-extract everything (T3 dominates: ~180 ms per camera JPEG on the JVM). Use `INCREMENTAL` for repeat runs.
