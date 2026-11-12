# Phase 2 — Metadata extraction and incremental scanner

Implements spec §5.1, §5.2 (non-ML image basics), §5.3 (PDF basics only), §5.4/5.5 (container basics only), §5.6,
§14.1 tiers 1–3, §14.2 bounded queues. Builds on `ARCHITECTURE.md` and `PHASE_1.md`. Extraction never decides
anything about deletion; it only records facts, each with an explicit unknown.

## 0. Deliverables

| Path | Classes |
|---|---|
| `v2/V2.java` | `ALGORITHM_VERSION = "2.0.0-p2"`, `SCAN_VERSION = 2` |
| `v2/model/` | `FeatureRecord`, `ImageInfo`, `ExifInfo`, `NameDate`, `VideoInfo`, `AudioInfo`, `ApkInfo`, `InstallState`, `PdfInfo`, `ArchiveInfo`, `ArchiveEntry`, `HashRecord`, `TypeSource`, `ExtMismatch`, `Integrity`, `TimeSource` |
| `v2/device/` | `MediaDecoder`, `DecodedImage`, `DecodeStatus`, `InstalledApps`, `InstalledApp`, `Availability`; `V2Device` gains `media()`, `apps()` |
| `v2/util/` | `Bytes` (u16/u32/u64 LE/BE, bounds-checked), `Io` (`readAt`, `readFully`, `CountingReadableFile`, `SliceReadableFile`), `BoundedInflater` |
| `v2/features/` | `FileType`, `TypeDetector`, `ExtensionMap`, `MimeMap`, `Signature`; `NameDates`; `ExifReader`; `JpegProbe`, `PngProbe`, `WebpProbe`, `GifProbe`, `BmpProbe`, `HeifProbe`; `Mp4Probe`, `MkvProbe`, `AviProbe`; `AudioProbe`; `PdfProbe`; `ZipProbe`, `TarProbe`, `ArchiveSubtype`; `AxmlParser`, `ApkSignatures`, `Der`, `ApkProbe`; `IntegrityProbe`; `CreatedAt`; `ImageBasics`; `QuickHash`; `Confidence`; `Extractors`; `Limits`; stages `T1MetadataStage`, `T2HashStructStage`, `T3ImageStage`; `ExtractionScheduler`, `CommitPolicy`; `ExtractionCoverage` |
| `v2/pipeline/` | `Budget` filled in; `Pipeline` default stage list = `[DISCOVER, T1, T2, T3]` filtered by `maxTier` |
| `jvmtest/.../v2test/` | `JvmMediaDecoder`, `JvmInstalledApps`, fixture builders (`Fx*` classes, §11), tests of §11; `FaultyStorageAccess` gains `denyOpen(path)`, `failReadAfter(path, long bytes)` (IOException mid-read) and `mutateAfterOpen(path, Runnable)` |
| `jvmtest/com/storageplanner/core/CoreOracle.java` | test-only bridge in package `core` exposing `Scanner.quickHash` / `fullHash` (package-private) to v2 tests — no change to `core/` itself |
| `tools/v2eval/evaluate.py` | Phase-2 extraction metrics (§10) |

`core/` changes: none. v2 calls `core.ImageFeatures.phash/sharpness/hamming` and `core.Classifier.classify`;
`core.Classifier.magic` and `tools.Mp4`/`tools.Exif` are used only as **test oracles**.

---

## 1. File types (`FileType`, `TypeDetector`)

### 1.1 Type ids and families

| Family | Type ids |
|---|---|
| image | `jpeg png gif webp bmp tiff dng heic heif avif ico svg` |
| video | `mp4 mov 3gp mkv webm avi` |
| audio | `m4a mp3 aac flac ogg opus wav amr midi` |
| document | `pdf ooxml-docx ooxml-xlsx ooxml-pptx odf ole2 rtf epub text csv html xml json vcf ics` |
| archive | `zip tar gzip bzip2 xz zstd 7z rar` |
| installer | `apk apks xapk` (bundles = zip containing `.apk` entries) |
| data | `sqlite wa-crypt` (`wa-crypt` = WhatsApp `.crypt12/14/15`, extension-only) |
| executable | `elf dex class jar` |
| special | `empty` (size 0), `unknown` |

### 1.2 Detection (`TypeDetector.detect(byte[] head, int headLen, long size, String ext) → Signature`)

`Signature { String type; TypeSource source; double confidence; String detail; }`. `head` = first `min(size, 64 KiB)` bytes.
Rules are evaluated in this order; the first match wins.

| # | Condition on `head` | type | source / conf |
|---|---|---|---|
| 0 | `size == 0` | `empty` | SIGNATURE 1.0 |
| 1 | `FF D8 FF` | `jpeg` | SIGNATURE 1.0 |
| 2 | `89 50 4E 47 0D 0A 1A 0A` | `png` | 1.0 |
| 3 | `GIF87a` / `GIF89a` | `gif` | 1.0 |
| 4 | `RIFF....WEBP` / `RIFF....AVI ` / `RIFF....WAVE` | `webp` / `avi` / `wav` | 1.0 |
| 5 | bytes 4..7 = `ftyp`: major brand then compatible brands (box size ≤ 4 KiB): `avif`,`avis` → `avif`; `heic heix hevc hevx heim heis` → `heic`; `mif1 msf1` (no heic/avif brand) → `heif`; `qt  ` → `mov`; `3gp*`, `3g2*` → `3gp`; `M4A `,`M4B `,`M4P ` → `m4a`; `crx ` → `unknown` (raw, detail "crx"); anything else → `mp4` | as listed | 1.0 |
| 6 | `1A 45 DF A3` + EBML DocType (`42 82`) within 64 bytes = `webm` → `webm`, else `mkv` | | 1.0 |
| 7 | `%PDF-` within the first 1024 bytes | `pdf` | 1.0 |
| 8 | `PK 03 04`, `PK 05 06`, `PK 07 08` | `zip` (refined in T2 to `apk`, `apks`, `xapk`, `jar`, `ooxml-*`, `odf`, `epub` by `ArchiveSubtype`) | 1.0 |
| 9 | `Rar! 1A 07`, `37 7A BC AF 27 1C`, `1F 8B`, `BZh[1-9]`, `FD 37 7A 58 5A 00`, `28 B5 2F FD` | `rar 7z gzip bzip2 xz zstd` | 1.0 |
| 10 | `ustar` at offset 257 | `tar` | 1.0 |
| 11 | `SQLite format 3\0` | `sqlite` | 1.0 |
| 12 | `D0 CF 11 E0 A1 B1 1A E1` | `ole2` (detail = ext when doc/xls/ppt/msg) | 1.0 |
| 13 | `7F 45 4C 46` / `dex\n` / `CA FE BA BE` | `elf` / `dex` / `class` | 1.0 |
| 14 | `fLaC` / `OggS` (+ first packet `\x01vorbis` → `ogg`, `OpusHead` → `opus`) / `#!AMR` / `MThd` | `flac` / `ogg`/`opus` / `amr` / `midi` | 1.0 |
| 15 | `ID3` + valid syncsafe size, or a valid MPEG audio frame header at 0 **and** a second valid header exactly one frame later | `mp3` | ID3: SIGNATURE 1.0; frame pair: STRUCTURE 0.9 |
| 16 | ADTS `FFF1`/`FFF9` with valid header and a second ADTS header one frame later | `aac` | STRUCTURE 0.9 |
| 17 | `00 00 01 00` + count 1..64 + plausible entries | `ico` | STRUCTURE 0.8 |
| 18 | `BM` + header size field ∈ {12, 40, 52, 56, 108, 124} at offset 14 | `bmp` | STRUCTURE 0.9 |
| 19 | `II*\0` / `MM\0*` → `dng` if ext is `dng`, else `tiff` | | 1.0 |
| 20 | `{\rtf` | `rtf` | 1.0 |
| 21 | Text test on the first 4 KiB: BOM UTF-16/32 or valid UTF-8, no NUL, ≤ 2% C0 controls other than `\t\r\n\f`. Then by content after leading whitespace/BOM: `<?xml` (+ `<svg` within 1 KiB → `svg`), `<!DOCTYPE html`/`<html` (case-insensitive) → `html`; `BEGIN:VCARD` → `vcf`; `BEGIN:VCALENDAR` → `ics`; ext `csv` with ≥ 2 lines having the same count (≥ 2) of `,` or `;` → `csv`; ext `json` and first char `{`/`[` → `json`; else `text` | | STRUCTURE 0.7 |
| 22 | ext ∈ `crypt12 crypt14 crypt15` | `wa-crypt` | EXTENSION 0.5 |
| 23 | otherwise: `ExtensionMap.typeFor(ext)` if any | that type | EXTENSION 0.5 |
| 24 | otherwise | `unknown` | NONE 0.3 |

The deliberately strict MP3/AAC rules (two consecutive frames) avoid the classic detector's false positives
(`FF Ex` matches much binary data). `ClassicAgreementTest` (§11) documents every difference from `core.Classifier.magic`.

### 1.3 Extension/content mismatch (`ExtensionMap.mismatch(String ext, String type) → ExtMismatch`)

`ExtensionMap` holds `ext → expected type ids` (e.g. `jpg,jpeg,jpe,jfif → {jpeg}`, `heic,heif → {heic,heif}`, `mp4,m4v → {mp4}`,
`mov → {mov,mp4}`, `apk → {apk}`, `docx → {ooxml-docx}`, `doc → {ole2}`, `txt → {text}` …, ~120 entries, listed in the class).

| Result | Rule |
|---|---|
| `UNKNOWN` | ext not in the map, or type `unknown`, or type came from the extension (source EXTENSION) |
| `NONE` | type ∈ expected(ext) |
| `BENIGN` | same family (§1.1) but different format: `.jpg` holding png/heic/webp (phones and chat apps do this), `.mp4` holding mov/3gp, `.doc` holding ooxml, `.zip` holding apk/jar/ooxml, `.apk` holding a plain zip, `.txt` holding csv/json/xml |
| `SUSPICIOUS` | different family: e.g. `.jpg` holding pdf/zip/apk/elf, `.mp4` holding apk, `.pdf` holding html, media extension holding `executable` family |

Mismatch is a signal for Phase 3/4/5 (`R_NAME_CONTENT_MISMATCH`); it never changes the type.

---

## 2. Records (field-level; unknowns per ARCHITECTURE §2.1)

```java
public final class FeatureRecord implements Record {     // key = fileId
    public String fileId; public Fingerprint fp;         // fingerprint the features describe
    public int[] versions = new int[Extractors.COUNT];   // extractor version per extractor id, 0 = not run
    public long extractedScanId = -1;
    public double confidence = Double.NaN;               // = FileRecord.extractionConfidence
    public java.util.List<String> problems = new java.util.ArrayList<String>();   // "<extractor>:<code>", e.g. "pdf:OBJECT_STREAMS", ≤ 20
    public ImageInfo image; public ExifInfo exif; public NameDate nameDate;
    public VideoInfo video; public AudioInfo audio; public ApkInfo apk; public PdfInfo pdf; public ArchiveInfo archive;
    public java.util.Map<String, String> extra = new java.util.TreeMap<String, String>();
}
```

| Record | Fields (type, unknown) |
|---|---|
| `ImageInfo` | `format` (String); `width, height` (int, -1; as stored, before orientation); `orientation` (int 1..8; 1 when the format has no orientation tag; -1 if a tag exists but is unreadable); `uprightWidth, uprightHeight` (int, -1); `aspect` (double, NaN; upright w/h); `megapixels` (double, NaN); `animated` (Tri); `frames` (int, -1, counted up to 1000); `hasAlpha` (Tri); `bitDepth` (int, -1); `progressive` (Tri); `jpegQuality` (int 1..100, -1; IJG estimate from DQT); `motionPhoto` (Tri); `motionVideoBytes` (long, -1); — tier 3 — `decodeStatus` (DecodeStatus, null = not attempted); `analysedWidth, analysedHeight` (int, -1); `sharpness` (double NaN; `ImageFeatures.sharpness`, -1 from it → NaN); `brightness` (mean luma 0..255, NaN); `contrast` (luma std dev, NaN); `clipLow`, `clipHigh` (fraction of luma ≤ 5 / ≥ 250, NaN); `exposure` ((mean − 128)/128 ∈ [−1, 1], NaN); `colorfulness` (Hasler–Süsstrunk M, NaN); `saturation` (mean HSV saturation 0..1, NaN); `lumaHist16` (int[16] counts normalised to sum 10000, null) |
| `ExifInfo` | `dateTimeOriginal` (long TIME; local time read as UTC unless offset known); `offsetTimeOriginal` (String, null); `tzKnown` (Tri); `dateTime` (long TIME, tag 0x0132); `make`, `model`, `software` (String, null; ≤ 64 chars); `hasGps` (Tri); `orientation` (int, -1); `pixelX`, `pixelY` (int, -1); `hasThumbnail` (Tri) |
| `NameDate` | `at` (long TIME); `precision` (`DAY`, `SECOND`, `MILLI`); `pattern` (String id, §4); `source` (`camera`, `screenshot`, `whatsapp`, `generic`) |
| `VideoInfo` | `container` (String); `durationMs` (long, -1); `width, height` (int, -1; track header, before rotation); `rotation` (int 0/90/180/270, -1); `fps` (double, NaN); `bitrate` (long bits/s, -1; = size × 8000 / durationMs); `videoCodec`, `audioCodec` (String fourcc/CodecID lower-case, null); `hasAudio` (Tri); `videoTracks`, `audioTracks` (int, -1); `createdAt` (long TIME; container UTC); `fragmented` (Tri); `moovAtEnd` (Tri); `trailerBytes` (long, -1); `samsungTrailer` (Tri); `problem` (String, null) |
| `AudioInfo` | `container`, `codec` (String, null); `durationMs` (long, -1); `durationConfidence` (double 0..1, NaN); `bitrate` (long, -1); `sampleRate` (int, -1); `channels` (int, -1); `vbr` (Tri) |
| `ApkInfo` | `packageName`, `versionName` (String, null); `versionCode` (long, -1; includes versionCodeMajor << 32); `minSdk`, `targetSdk` (int, -1); `signerCertSha256` (List<String>; null unknown, empty = unsigned); `signatureScheme` (`none`, `v1`, `v2`, `v3`, `v2+v3`, `v1+v2`… ; null unknown); `split` (Tri); `bundle` (Tri; apks/xapk); `installState` (`NOT_INSTALLED, SAME_VERSION, INSTALLED_NEWER, INSTALLED_OLDER, UNKNOWN`); `installedVersionCode` (long, -1); `sameSigner` (Tri); `parseSource` (`ZIP_AXML`, `PLATFORM`, null); `problem` |
| `PdfInfo` | `version` (String "1.7", null); `encrypted` (Tri); `linearized` (Tri); `pages` (int, -1); `pagesSource` (`LINEARIZED`, `PAGES_ROOT`, `PAGE_OBJECTS`, null); `objectStreams` (Tri); `incrementalUpdates` (int, -1; number of `%%EOF` − 1 found in scanned regions); `title`, `author`, `producer` (String ≤ 256 chars, null; always null when encrypted ≠ FALSE); `createdAt` (long TIME, `/CreationDate`) |
| `ArchiveInfo` | `format` (`zip`, `tar`); `subtype` (`apk apks xapk jar ooxml-docx ooxml-xlsx ooxml-pptx odf epub`, null); `listingStatus` (`OK, TRUNCATED, LIMIT, CORRUPT, UNSUPPORTED`); `declaredEntries` (long, -1); `listedEntries` (int); `totalUncompressed`, `totalCompressed` (long, -1); `maxRatio` (double, NaN); `bombSuspect`, `pathTraversal`, `absolutePaths`, `encryptedEntries`, `nameEncodingUncertain` (Tri); `nestedArchives` (int, -1); `nestedListed` (int); `entries` (List<ArchiveEntry>, ≤ `Limits.ARCHIVE_MAX_ENTRIES`) ; `problem` |
| `ArchiveEntry` | `path` (String: `\`→`/`, NFC, leading `./` removed — raw name kept in `rawName` when different); `container` (String, null for top level; outer entry path for nested); `depth` (0/1/2); `size`, `compressedSize` (long, -1); `crc32` (long, -1); `method` (int, -1); `mtime` (long TIME; DOS time read as UTC); `dir` (boolean); `encrypted` (Tri) |
| `HashRecord` | ARCHITECTURE §2.5; `crc32` is filled whenever a full SHA-256 is computed (same pass) |

`FileRecord` fields set in this phase: `type`, `typeSource`, `mimeType` (+ `extra["mimeSource"] = "CONTENT"|"EXTENSION"` in FeatureRecord),
`extMismatch`, `integrity`, `createdAt`, `createdAtSource`, `sha256`, `phash`/`hasPhash`, `extractionStatus`,
`extractionConfidence`, `tiersDone/tiersFailed/tiersPending`, `readable` (FALSE after an open failure), `lastError`.

---

## 3. Tiered extraction

### 3.1 Tiers, selection, bits

| Bit | Stage | Applicable to | "Done" means |
|---|---|---|---|
| 1 | `T1_METADATA` | every PRESENT file | type + common metadata + format probe ran for the current fingerprint |
| 2 | `T2_HASH_STRUCT` | every PRESENT file with size > 0 (hash part); zip-family and tar (structure part) | hash state is current for this scan's size groups (a size-unique file is "done" with `sha256 = null`); archive/APK structure parsed |
| 3 | `T3_IMAGE` | image family | decode attempted; result in `ImageInfo.decodeStatus` |

`tiersFailed` bit k = the tier ran but produced no features (unreadable, probe error, `NO_DECODER`, `TOO_LARGE`, `CORRUPT`);
`tiersPending` bit k = applicable but not yet run (budget, pause, `maxTier`).

A stage selects file `f` (PRESENT, readable ≠ FALSE unless retrying) when **any** holds:
`(f.tiersDone & bit) == 0`; `features.fp` not `sameAs(f.fingerprint)`; some extractor the stage would run for
`f.type` has `features.versions[id] != Extractors.VERSION[id]`; session kind `FULL` and `features.extractedScanId < scanId`.
Unreadable files (`extractionStatus == UNREADABLE`) are retried once per scan (they may have become readable).

When `FileUpsert` (Phase 1) records `CHANGED`/`ADDED`, it clears bits 1–3 and sets them pending — Phase 2 adds this one line
to `FileUpsert`.

### 3.2 `T1MetadataStage` (per file, inside a worker)

1. `size == 0` → type `empty`, `integrity = OK`, `mimeType = null`, `sha256 = SHA256_EMPTY`
   (`e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`) without reading, bits 1–3 done (2 and 3 not applicable),
   confidence 1.0, status `COMPLETE`.
2. `rf = storage.open(vol, rawPathOrPath)` wrapped in `CountingReadableFile` (bytes go to `Budget`). On `IOException`:
   status `UNREADABLE` (PERMISSION_DENIED) or `FAILED` (other), `readable = FALSE`, `lastError`, type by extension
   (source EXTENSION, conf 0.5) or `unknown`, `extractionConfidence = 0.1`, bit 1 failed; done.
3. `head = Io.readAt(rf, 0, min(size, 64 KiB))`. `sig = TypeDetector.detect(...)`. `mime = MimeMap.of(type)` (source CONTENT) else
   `MimeMap.ofExtension(ext)` (source EXTENSION) else null.
4. `extMismatch = ExtensionMap.mismatch(ext, type)`.
5. Probe by type (each probe takes `(ReadableFile rf, byte[] head, long size, Limits lim)` and returns its info record plus
   `problems`; probes never throw for malformed data — see §8):

   | Type | Probe | Max bytes read |
   |---|---|---|
   | jpeg | `JpegProbe` → ImageInfo (SOFn dims, progressive, DQT quality, XMP motion-photo markers) + `ExifReader` → ExifInfo | 1 MiB (markers walked by seeking) + 64 KiB tail |
   | png / gif / webp / bmp / ico | `PngProbe` (IHDR, `acTL` → animated, `IEND` in tail), `GifProbe` (logical screen, frames by block walk ≤ 1000 frames / 4 MiB), `WebpProbe` (VP8/VP8L/VP8X dims, ANIM, EXIF chunk → ExifReader), `BmpProbe` | 4 MiB |
   | heic / heif / avif | `HeifProbe` → ImageInfo (`ispe` of primary item, `irot`) + ExifInfo from the `Exif` item | 4 MiB of `meta` + 1 MiB Exif item |
   | tiff / dng | `ExifReader.parseTiff` on the head → dims (0x0100/0x0101), orientation, dates | 1 MiB |
   | mp4 / mov / 3gp / m4a | `Mp4Probe` → VideoInfo (and AudioInfo for audio-only) | moov ≤ 32 MiB, else first 1 MiB of moov (`problem = MOOV_LARGE`) |
   | mkv / webm / avi | `MkvProbe` / `AviProbe` → VideoInfo | 4 MiB |
   | mp3 / aac / flac / ogg / opus / wav / amr | `AudioProbe` → AudioInfo | 2 MiB + 64 KiB tail |
   | pdf | `PdfProbe` → PdfInfo | whole file if ≤ 8 MiB, else first 4 MiB + last 4 MiB |
   | zip family, tar, apk | nothing in T1 (T2 structure part) | — |
6. `integrity = IntegrityProbe.check(type, rf, head, size, infos)` (§3.3).
7. `nameDate = NameDates.parse(name, clock.nowMillis())` (§4).
8. `createdAt`, `createdAtSource` = `CreatedAt.choose(exif, video, pdf, nameDate, stat.birthTime, now)`:
   first plausible of EXIF `dateTimeOriginal` → `EXIF`; container `createdAt` (MP4 mvhd, PDF `/CreationDate`) → `CONTAINER`;
   `nameDate.at` → `FILENAME`; `birthTime` → `FS_BIRTH`; else `TIME`/`NONE`. Plausible = `946684800000` (2000-01-01) ≤ t ≤ now + 1 day
   (MP4 "1904"/"1970" zero dates are therefore rejected). **`modifiedAt` is never used** (copies and downloads reset it).
9. Results go to the coordinator (`Sink`): FeatureRecord (fp, versions of extractors run, sub-records, problems,
   `extractedScanId`), FileRecord fields of §2, bit 1 done (or failed if the probe returned a fatal problem), confidence (§6).

### 3.3 `IntegrityProbe`

| Type | OK | TRUNCATED | CORRUPT | ENCRYPTED |
|---|---|---|---|---|
| jpeg | file ends with `FF D9` (ignoring trailing zero bytes), or `motionPhoto == TRUE`, or an `ftyp` box starts within the last 64 KiB after an `FF D9` | no `FF D9` in the tail | no SOF marker before SOS | — |
| png | last chunk is `IEND` | no `IEND` | bad signature / IHDR | — |
| gif | ends with `0x3B` | otherwise | bad header | — |
| mp4 family | top-level boxes cover the file (trailer allowed) and `moov` (or `moof`) present | a box extends past EOF | no moov/moof | — |
| pdf | `%%EOF` within the last 1024 bytes | no `%%EOF` | no `%PDF-` header | `encrypted == TRUE` |
| zip family (set in T2) | EOCD found and central directory parsed | EOCD missing but local header at 0 | central directory unparseable | every non-directory entry encrypted |
| tar (T2) | ends with two zero blocks or exact entry coverage | entry data past EOF | header checksum mismatch on first header | — |
| others | `UNKNOWN` | | | |

### 3.4 `T2HashStructStage`

**Part A — structure** (selection: type ∈ zip family ∪ {tar}, bit 2 structure not current). `ZipProbe.list(rf, size, Limits)` or
`TarProbe.list(...)` → ArchiveInfo; `ArchiveSubtype.of(entries)` refines `FileRecord.type`:
`AndroidManifest.xml` + any `classes*.dex` or `resources.arsc` → `apk`; ≥ 1 `*.apk` entry + (`toc.pb` | `manifest.json` | `info.json`) → `apks`/`xapk`
(`xapk` when `manifest.json` has no `.pb`); `[Content_Types].xml` + `word/` → `ooxml-docx`, `xl/` → `ooxml-xlsx`, `ppt/` → `ooxml-pptx`;
first entry `mimetype` stored with `application/vnd.oasis.opendocument.` → `odf`, `application/epub+zip` → `epub`;
`META-INF/MANIFEST.MF` without manifest/dex → `jar`. For `apk`: `ApkProbe` (§5). Integrity updated per §3.3.

**Part B — hashes** (`HashStage`, runs once per scan over all PRESENT files of all scanned volumes, on the coordinator thread with
reads in workers):

```
groups = files PRESENT, readable != FALSE, size >= 1, grouped by size
policy ALL        → every file needs a full hash
policy COLLISIONS → files in groups with >= 2 members need hashing; others: bit 2 done, sha256 stays null (unique by size)
for each group needing hashing, ordered by (size × (members − 1)) descending, then size descending:
    for each member (ascending path): reuse HashRecord if hr.fp sameAs(member fp); else
        if size <= 131072: compute full SHA-256 (+CRC32) → quickHash = sha256 (one read)
        else quickHash = QuickHash.of(rf)          // identical algorithm to core.Scanner.quickHash
    subgroups by quickHash with >= 2 members → full SHA-256 + CRC32 for each (reuse if current)
    write HashRecord + FileRecord.sha256 for every member processed; bit 2 done
```
`QuickHash.of(rf, size)`: SHA-256 over bytes `[0, min(65536, size))`, then if `size > 131072` bytes `[size − 65536, size)`, then
`Long.toString(size)` as ASCII — byte-for-byte the classic definition (verified against `CoreOracle.quickHash`).
Full hash streams 1 MiB buffers; a read error → `HashRecord` not written, `lastError`, bit 2 failed (the file stays unhashed;
Phase 3 treats it as "unknown", never as unique).
Budget: bytes read are charged; when exhausted, unprocessed groups' members get bit 2 pending, stage returns `BUDGET_EXHAUSTED`
(resume continues with the next group). Incremental: a new file joining a size group reads only itself (other members reuse
their current quick hashes) plus full hashes of members whose quick hash now matches.

### 3.5 `T3ImageStage`

Selection: image family, bit 3 not current. If `type ∉ media.decodableTypes()` → `decodeStatus = NO_DECODER` without I/O,
bit 3 failed. If `width × height > Limits.MAX_PIXELS` (100 000 000) → `TOO_LARGE`, failed. If dims unknown and size > 64 MiB →
`TOO_LARGE`. Order: area priority `camera, screenshot, pictures, wa_sent, wa_image, download, document, telegram_media,
app_media, user_other, (rest)`, then path. Budget charges one decode and the elapsed ms.

Per file: `d = media.decode(vol, rawPathOrPath, 1024, MAX_PIXELS, wantArgb = true)`. `d.status != OK` → `decodeStatus`, bit 3 failed,
**no pixel field is set** (they stay NaN; `hasPhash` false). On OK — `ImageBasics.measure(d)`:

| Feature | Computation (luma `L` 0..255 of `d.w × d.h`, upright) |
|---|---|
| `phash` | `core.ImageFeatures.phash(L, w, h)`; `hasPhash = true` |
| `sharpness` | `core.ImageFeatures.sharpness(L, w, h)`; `-1` (image < 64 px) → NaN |
| `brightness`, `contrast` | mean and population std dev of `L` |
| `clipLow`, `clipHigh` | fraction of `L ≤ 5`, `L ≥ 250` |
| `exposure` | `(brightness − 128) / 128` |
| `colorfulness` | on ARGB sampled on a grid of ≤ 256 × 256 points: `rg = R − G`, `yb = (R + G)/2 − B`; `M = sqrt(σrg² + σyb²) + 0.3 sqrt(μrg² + μyb²)` |
| `saturation` | mean of `(max − min)/max` over the same samples (0 when max = 0) |
| `lumaHist16` | 16 bins of `L >> 4`, scaled to sum 10000 (integer rounding, remainder to the largest bin) |
| `analysedWidth/Height`, `orientation` | from `d` |

Luma conversion when a decoder supplies only ARGB: `(299 R + 587 G + 114 B) / 1000` (same as `core.Scanner.quality`).
Animated images: first frame only (`ImageInfo.animated` tells the reader).

`MediaDecoder` contract recap (ARCHITECTURE §3.2): pixels **upright**; never upscaled; `TOO_LARGE` decided from header dimensions
before allocation; decoder errors are statuses, not exceptions. `JvmMediaDecoder`: `ImageIO` reader for jpeg/png/gif/bmp
(`getWidth(0)/getHeight(0)` first, then `ImageReadParam.setSourceSubsampling` to ≈ 2× the target, then bilinear scale to long
edge 1024); EXIF orientation from `ExifReader`; `IIOException` → `CORRUPT` (or `UNSUPPORTED_FORMAT` for "Unsupported Image Type");
heic/heif/avif/webp → not in `decodableTypes()`.

---

## 4. `NameDates`

`NameDates.parse(String name, long now) → NameDate` (null if no pattern). Applied to the name without extension; first match wins;
a match is kept only if the date is valid (calendar check), ≥ 2000-01-01 and ≤ now + 1 day. Times are local, stored as UTC (tz unknown).

| id | Regex (Java) | precision / source | Examples |
|---|---|---|---|
| `CAM_YMD_HMS` | `^(?:IMG\|VID\|PXL\|MVIMG\|PANO\|BURST\d*\|DSC\|MOV)?[_-]?(\d{8})[_-](\d{6})(\d{0,3})(?:\D.*)?$` | SECOND (MILLI if 3 extra digits) / camera | `IMG_20240627_175114`, `IMG20240627_200846`, `PXL_20240627_175114123`, `VID_20240627_175114` |
| `SCREENSHOT` | `^Screenshot[_ -](\d{8})[-_](\d{6}).*$` or `^Screenshot[_ ](\d{4})-(\d{2})-(\d{2})[-_ ](\d{2})[-.](\d{2})[-.](\d{2}).*$` | SECOND / screenshot | `Screenshot_20240627-175114_WhatsApp` |
| `WA_DAY` | `^(?:IMG\|VID\|AUD\|PTT\|DOC\|STK)-(\d{8})-WA\d{4}.*$` | DAY / whatsapp | `IMG-20240627-WA0027` |
| `WA_DESKTOP` | `^WhatsApp (?:Image\|Video\|Audio) (\d{4})-(\d{2})-(\d{2}) at (\d{1,2})\.(\d{2})\.(\d{2})(?: ?([AP]M))?(?: \(\d+\))?$` | SECOND / whatsapp | `WhatsApp Image 2024-06-27 at 17.51.14 (2)` |
| `SAMSUNG` | `^(\d{8})_(\d{6})(?:\D.*)?$` | SECOND / camera | `20240627_175114` |
| `GENERIC_DAY` | `(?<!\d)(20\d{2})[-_.]?(\d{2})[-_.]?(\d{2})(?!\d)` (find, not match) | DAY / generic | `invoice_2023-04-01` |

`NameDatesTest` also runs every name in `/home/user/sp-dataset-drive/inventory.jsonl` (when present): every `IMG_…`, `IMG2024…`,
`IMG-…-WA…` and `WhatsApp Image …` name must parse; it prints the unmatched remainder for review.

---

## 5. APK features (`ApkProbe`, spec 5.6)

1. `ZipProbe` entries must contain `AndroidManifest.xml`; read it with `BoundedInflater` (declared size ≤ 4 MiB, actual output
   capped at 4 MiB, ratio ≤ 200; method STORED read directly).
2. `AxmlParser.parse(byte[]) → ManifestInfo`: chunk walk (u16 type, u16 headerSize, u32 size; every size checked against the
   remaining buffer). `RES_STRING_POOL` (0x0001, UTF-8 flag 0x100 or UTF-16; ≤ 100 000 strings), `RES_XML_RESOURCE_MAP` (0x0180),
   `START_ELEMENT` (0x0102: attribute count ≤ 1000, each `ns, name, rawValue, Res_value{size, res0, dataType, data}`).
   Attributes are matched by resource id first (via the resource map — obfuscated names are common), then by name:
   `versionCode 0x0101021b`, `versionName 0x0101021c`, `minSdkVersion 0x0101020c`, `targetSdkVersion 0x01010270`,
   `versionCodeMajor 0x01010576`, `isSplitRequired 0x01010591`; `package` and `split` are un-namespaced attributes of `<manifest>`.
   Typed values: INT_DEC/INT_HEX (0x10/0x11) → number; STRING (0x03) → pool string; REFERENCE (0x01) → unknown (resource tables are
   not parsed). Element depth ≤ 64; any inconsistency → `problem = AXML_<code>`, partial results kept.
3. `ApkSignatures.read(rf, eocd)`: APK Signing Block = the 16 bytes before the central directory offset read as
   `"APK Sig Block 42"` preceded by u64 size; block size ≤ 16 MiB and consistent at both ends. Pairs `(u64 len, u32 id, value)`;
   ids v2 `0x7109871a`, v3 `0xf05368c0`, v3.1 `0x1b93ad61`. Each value: u32-length-prefixed sequence of signers; signer:
   length-prefixed signed data → digests (skip) → certificates (sequence of length-prefixed DER) → first certificate. Fingerprint =
   lower-hex SHA-256 of the DER. No block: v1 — entries `META-INF/*.RSA|*.DSA|*.EC` (≤ 1 MiB inflated) parsed by `Der` (tag/length
   walker, ≤ depth 16): `ContentInfo → [0] → SignedData → certificates [0] IMPLICIT` → first certificate TLV → SHA-256. No signature
   at all → empty list, scheme `none`. Signatures are **not verified** (identity only); `ApkInfo.extra` notes "unverified".
4. Install state via `InstalledApps`: `availability == UNAVAILABLE` → `UNKNOWN`; `get(pkg) == null` → `NOT_INSTALLED` only if
   availability `FULL`, else `UNKNOWN`; else compare version codes → `SAME_VERSION` / `INSTALLED_NEWER` (APK is older) /
   `INSTALLED_OLDER` (APK is newer). `sameSigner` = TRUE if both cert lists known and intersect, FALSE if both known and disjoint,
   else UNKNOWN.
5. Fallback: if AXML parse failed and `storage.localPath(..) != null` → `apps.inspectArchive(localPath)` (Android:
   `PackageManager.getPackageArchiveInfo`; JVM: null) → `parseSource = PLATFORM`.
6. Bundles (`apks`/`xapk`): list inner `.apk` entries; parse the base APK's manifest only if it is STORED or ≤ 32 MiB deflated
   (bounded inflate into memory is not allowed above that: `problem = BUNDLE_BASE_TOO_LARGE`).

`JvmInstalledApps(String jsonPathOrNull)`: reads `installed_apps.json` — accepts a list or `{"apps": [...]}`; per app keys
`package|packageName`, `versionCode`, `versionName`, `signingCertSha256` (string or list), `label`; `availability = FULL` when the file
is given, `UNAVAILABLE` otherwise.

---

## 6. PDF basics (`PdfProbe`, no library)

Regions scanned: whole file if ≤ 8 MiB, else head 4 MiB + tail 4 MiB (decoded as ISO-8859-1 so offsets equal bytes).

| Field | Algorithm |
|---|---|
| `version` | `%PDF-(\d\.\d)` within the first 1024 bytes |
| `encrypted` | regex `/Encrypt\s*(?:\d+\s+\d+\s+R\|<<)` in the head 64 KiB and tail 64 KiB (all regions if ≤ 8 MiB). Match → TRUE. No match but a `trailer\s*<<` or an `/Type\s*/XRef` dictionary was seen → FALSE. Otherwise UNKNOWN |
| `linearized` / `pages` (1) | `/Linearized` dictionary in the first 1024 bytes → TRUE; its `/N (\d+)` → `pages`, source `LINEARIZED` |
| `pages` (2) | for each `/Type\s*/Pages\b`: enclosing object = text between the preceding `(\d+)\s+(\d+)\s+obj` (≤ 8 KiB back) and the next `endobj`; if it has `/Count\s+(\d+)` and no `/Parent` it is a root; take the root with the highest file offset (latest incremental update) → source `PAGES_ROOT` |
| `pages` (3) | file ≤ 8 MiB: count `/Type\s*/Page(?![s\w])` → source `PAGE_OBJECTS` (lower confidence) |
| `objectStreams` | `/Type\s*/ObjStm` seen → TRUE; then (2)/(3) failing is expected → `pages = -1`, `problem pdf:OBJECT_STREAMS` |
| `incrementalUpdates` | number of `%%EOF` in scanned regions − 1 (≥ 0) |
| `title`, `author`, `producer`, `createdAt` | only if `encrypted == FALSE`: `/Title\s*(\(…\)\|<hex>)` etc. Literal strings: balanced parentheses, escapes `\n \r \t \b \f \( \) \\ \ddd`, line continuation; hex strings; UTF-16BE BOM `FE FF` → UTF-16, else PDFDocEncoding ≈ ISO-8859-1. Truncate to 256 chars. `/CreationDate (D:YYYYMMDDHHmmSS±HH'mm')` → UTC when offset present, else local-as-UTC |

Encrypted or unparseable PDFs keep every unknown as unknown; nothing here implies importance (spec 5.3).

---

## 7. Archive listing with safety limits (`ZipProbe`, `TarProbe`, `Limits`)

| Limit (`Limits`) | Default | On exceed |
|---|---|---|
| `ARCHIVE_MAX_ENTRIES` | 10 000 (all nesting levels together) | stop listing, `listingStatus = TRUNCATED` |
| `ZIP_MAX_CD_BYTES` | 16 MiB | `LIMIT`, no entries |
| `ZIP_EOCD_SEARCH` | 65 557 bytes from the end | not found → `TRUNCATED` (local header at 0) or `CORRUPT` |
| `BOMB_ENTRY_RATIO` | 100 (and entry `size` > 1 MiB) | `bombSuspect = TRUE` |
| `BOMB_TOTAL_RATIO` | `totalUncompressed > 1000 × fileSize` or `> 8 GiB` | `bombSuspect = TRUE` |
| `MAX_NESTED_DEPTH` | 2 (top = 0) | nested archives deeper are counted, not listed |
| `NESTED_LIST_ONLY_STORED` | true | a nested archive is listed only if its entry is STORED (read through `SliceReadableFile`, no inflation) |
| `INFLATE_MAX_OUT` | 4 MiB (APK manifest), 1 MiB (v1 signature) | stop, `problem` |
| `TAR_MAX_HEADERS` | 10 000 | `TRUNCATED` |

ZIP procedure: (1) search backwards for EOCD `50 4B 05 06`; (2) if the ZIP64 locator `50 4B 06 07` sits 20 bytes before it, read the
ZIP64 EOCD `50 4B 06 06` for count/size/offset; (3) validate `cdOffset + cdSize ≤ eocdOffset` (else try `eocdOffset − cdSize` as the
real offset for archives with a prefix, e.g. self-extracting; else `CORRUPT`); (4) read the central directory and walk
`50 4B 01 02` headers: flags (bit 0 encrypted, bit 11 UTF-8), method, DOS time, CRC, sizes, name/extra/comment lengths (all checked
against remaining bytes), local header offset, ZIP64 extra `0x0001` when a field is `0xFFFFFFFF`; (5) names: UTF-8 when bit 11, else
UTF-8 with replacement and `nameEncodingUncertain = TRUE` if any replacement occurred (CP437 is not available on every Android);
(6) per entry flags: a path component `..`, a leading `/`, a drive prefix `^[A-Za-z]:`, or a backslash → `pathTraversal` /
`absolutePaths`; ratio checks; encryption; (7) nested: entry ext ∈ `zip apk jar apks xapk 7z rar tar gz tgz` → `nestedArchives++`, list
if STORED and depth < 2. No entry data is ever written anywhere; only the APK manifest / v1 signature entries are inflated, in memory,
with caps.

TAR: 512-byte headers; name (100) + ustar prefix (155); size octal (or base-256); checksum verified (mismatch on the first header →
`CORRUPT`, later → `TRUNCATED`); typeflags `0`/`\0` file, `5` dir, `L` GNU long name (≤ 4 KiB), `x`/`g` pax (skipped); data skipped by
seeking; stop at two zero blocks.

---

## 8. Error handling rules (all probes)

* Every length/offset from a file is validated with long arithmetic against the region size and the stage cap **before** allocation;
  allocation size ≤ the cap of the table in §3.2/§7.
* Parsers are iterative or depth-limited (ISO-BMFF ≤ 8 levels, EBML ≤ 6, AXML elements ≤ 64, DER ≤ 16); loops have explicit
  iteration caps (boxes ≤ 10 000, markers ≤ 10 000, chunks ≤ 100 000).
* A probe catches `RuntimeException` (malformed data, e.g. index errors) itself and returns its partial info with
  `problem = "<probe>:EXCEPTION:<class>"`; it never catches `Error`. The worker wrapper catches `IOException` → `FAILED`
  (`lastError`), and `RuntimeException` that escaped a probe → `problems += "stage:EXCEPTION"` and bit failed.
* Decoder `OutOfMemoryError` is handled inside the `MediaDecoder` implementation (device side) and returned as `OUT_OF_MEMORY`.
* A file whose fingerprint changes *during* extraction (stat after the probe differs from the selected fingerprint) is discarded and
  left pending (`problems += "stage:CHANGED_DURING_READ"`); the next run re-extracts it.

---

## 9. Scheduler, budget, backpressure, confidence

```java
public final class ExtractionScheduler<J, R> {
    public interface Worker<J, R> { R process(J job) throws java.io.IOException; }          // runs on worker threads
    public interface Sink<J, R> { void accept(J job, R result, java.io.IOException error) throws StoreException; }   // coordinator only
    public ExtractionScheduler(int workers, int queueCapacity, StageContext ctx);
    /** Feeds jobs in iterator order; commits via CommitPolicy (500 results or 3 s). Returns DONE, PAUSED, CANCELLED, BUDGET_EXHAUSTED. */
    public StageOutcome run(java.util.Iterator<J> jobs, Worker<J, R> worker, Sink<J, R> sink) throws StoreException;
}
public final class Budget {
    public Budget(long maxMillis /* -1 none */, long maxBytes /* -1 */, long maxDecodes /* -1 */, Clock clock);
    public void chargeBytes(long n); public void chargeDecode(); public boolean exhausted(); public String stoppedBy();   // "TIME", "BYTES", "DECODES", null
}
```
* `workers == 0`: inline (deterministic; default for tests). Otherwise `workers` daemon threads, job queue `ArrayBlockingQueue`
  of `queueCapacity` (default `max(4, 2 × workers)`), result queue capacity 64; the coordinator blocks on `offer(job, 100 ms)` and drains
  results while waiting — so at most `queueCapacity + workers + 64` jobs are in memory.
* Before each enqueue and every 2 s: `control.cancelRequested()`, `control.pauseRequested()`, `throttle.shouldPause()`,
  `budget.exhausted()`. On stop: no new jobs, drain in-flight, commit, return the outcome; unprocessed files keep pending bits.
* Throttle effects (ARCHITECTURE §3.4): MODERATE/low heap → the scheduler drops to 1 active worker (others wait on a
  `Semaphore`); T3 additionally limits concurrent decodes (`LOW` 1, `BALANCED` 2, `HIGH` 3).
* Workers never touch the store; Sink results are applied in job order (results carry their sequence number; the coordinator
  applies them in sequence order using a small reorder buffer) so the store content is identical for any worker count.

`Confidence.of(FileRecord, FeatureRecord)` (rounded to 3 decimals; unreadable → 0.1):

| Factor | Values |
|---|---|
| type source | SIGNATURE 1.0 · STRUCTURE 0.9 · EXTENSION 0.5 · NONE 0.3 |
| integrity | OK 1.0 · UNKNOWN 0.9 · ENCRYPTED 0.7 · TRUNCATED 0.6 · CORRUPT 0.4 |
| tier-1 probe | complete 1.0 · partial (any `problems` from the probe) 0.85 · exception 0.6 · no probe for the type 1.0 |
| tier 3 (images only) | OK 1.0 · pending 0.85 · NO_DECODER / TOO_LARGE 0.7 · CORRUPT / IO_ERROR / OOM 0.5 |
| tier 2 (archives/apk) | OK 1.0 · TRUNCATED/LIMIT 0.8 · CORRUPT 0.5 |

`extractionStatus`: `UNREADABLE` / `FAILED` as in §3.2; `UNSUPPORTED` when type is `unknown`; `PARTIAL` when any applicable tier
(≤ `maxTier`) is pending or failed; else `COMPLETE`. So a HEIC on the JVM is `PARTIAL` with `decodeStatus = NO_DECODER` — visibly
incomplete, never "complete with invented values".

`Extractors` — ids and versions (bump the version to force re-extraction of that extractor only):
`TYPE 0, EXIF 1, JPEG 2, PNG 3, WEBP 4, GIF 5, HEIF 6, MP4 7, MKV 8, AUDIO 9, PDF 10, ZIP 11, TAR 12, APK 13, NAMEDATE 14,
INTEGRITY 15, IMAGE_BASICS 16, HASH 17, BMP 18, AVI 19`; all at version 1. `Extractors.forType(String type)` lists the ids a
type uses. Test hook: `Extractors.overrideVersionForTest(int id, int v)` (package-private, used by the incremental test).

Coverage (`ExtractionCoverage`, fills `CoverageReport.extraction`):

```json
{"tiers": {"1": {"applicable": 0, "done": 0, "failed": 0, "pending": 0}, "2": {}, "3": {}},
 "status": {"COMPLETE": 0, "PARTIAL": 0, "UNREADABLE": 0, "FAILED": 0, "UNSUPPORTED": 0, "NOT_STARTED": 0},
 "types": {"jpeg": 0}, "typeSource": {"SIGNATURE": 0}, "extMismatch": {"BENIGN": 0, "SUSPICIOUS": 0},
 "integrity": {"OK": 0}, "decodeStatus": {"OK": 0, "NO_DECODER": 0}, "undecodableByType": {"heic": 0},
 "hash": {"policy": "collisions", "sizeGroups": 0, "quickHashed": 0, "fullHashed": 0, "reused": 0, "bytesRead": 0},
 "archives": {"listed": 0, "truncated": 0, "bombSuspect": 0, "pathTraversal": 0, "encrypted": 0},
 "budget": {"stoppedBy": null, "pendingFiles": 0}, "extractedThisScan": 0, "reusedThisScan": 0}
```

---

## 10. V2Cli and evaluator additions

`V2Cli scan` gains `--max-tier`, `--hash`, `--installed-apps`, `--workers`, `--budget-ms`, `--budget-bytes`; files carry the Phase-2
fields of ARCHITECTURE §9 (archive `entries` capped at 200 in JSON with `"entriesTruncated": n`); `phash` as 16 lower-hex digits.

`evaluate.py` Phase-2 metrics (`--gate phase2`):
* **type accuracy**: manifest `category` → expected type set via a table at the top of the script (e.g. `photo → {jpeg, heic}`,
  `screenshot → {png, jpeg}`, `pdf → {pdf}`, `apk → {apk}`); files with no mapping are skipped. Gate ≥ 0.99.
* **mismatch detection**: files in the "misleading filename" scenario must have `extMismatch ∈ {BENIGN, SUSPICIOUS}`; others counted
  as false positives (reported).
* **sha256 agreement** (results produced with `--hash all`): equal to manifest `sha256` for 100% of files with `presence == PRESENT`.
* **unknown handling**: for every image whose `image.decodeStatus != "OK"`: all of `sharpness, brightness, contrast, exposure,
  colorfulness, phash` are `null` and `extraction.status != "COMPLETE"`. Gate: 100%.
* **APK**: package/versionCode equal to `installed_apps.json`/manifest labels where the manifest provides them.
* **incremental**: run the same `--state-dir` twice; second run `coverage.extraction.extractedThisScan == 0`.
* throughput: `files / timings.stages.T1_METADATA` reported.

---

## 11. Tests

Fixture builders (jvmtest, deterministic, no binary files checked in): `FxJpeg` (ImageIO writer at a given quality + an EXIF APP1
built by `FxTiff`, optional XMP motion-photo block and trailing MP4 bytes), `FxTiff` (IFD builder LE/BE, ASCII/SHORT/LONG/RATIONAL,
sub-IFD pointers, deliberately bad offsets), `FxPng`, `FxGif` (n frames), `FxWebp` (VP8X header + chunks; pixels not needed),
`FxHeif` (ISO-BMFF box writer: ftyp, meta{hdlr, pitm, iinf{infe}, iloc, iprp{ipco{ispe, irot}, ipma}}, Exif item), `FxMp4` (ftyp,
mdat, moov{mvhd, trak{tkhd(matrix), mdia{mdhd, hdlr, minf{stbl{stsd, stts}}}}}, options: moov at end, 64-bit size, truncated),
`FxMkv`, `FxAudio` (MP3 CBR frames ± Xing, ID3v2; FLAC STREAMINFO; Ogg pages for Vorbis and Opus with granule; WAV), `FxPdf`
(text PDFs: plain with xref/trailer, encrypted trailer ref, xref-stream dict with `/Encrypt`, linearized, incremental update, object
stream marker, title strings), `FxZip` (`ZipOutputStream` for normal cases; a raw writer for crafted central directories: huge
declared sizes, `../` names, absolute names, encrypted flag, ZIP64 fields, long comment, cut files), `FxTar`, `FxAxml` (binary XML
encoder: string pool UTF-8/UTF-16, resource map, elements/attributes), `FxApk` (zip + manifest + optional v2/v3 signing block with
given fake certificate bytes + optional v1 PKCS#7 built with a tiny DER writer).

| Test class | Cases |
|---|---|
| `TypeDetectorTest` | every row of §1.2 with a minimal header; `empty`; random bytes starting `FF FB` without a second frame → not `mp3`; ID3 mp3; UTF-8 text vs binary with NUL; UTF-16 BOM text; `%PDF-` at offset 500; ftyp brands (heic, mif1-only → heif, avif, qt, 3gp4, M4A, isom); webm vs mkv DocType; ext-only `crypt14` → `wa-crypt` EXTENSION |
| `ExtensionMapTest` | mismatch table: `.jpg`+png → BENIGN, `.jpg`+heic → BENIGN, `.jpg`+pdf → SUSPICIOUS, `.mp4`+apk → SUSPICIOUS, `.apk`+zip → BENIGN, `.xyz`+jpeg → UNKNOWN, `.jpg`+jpeg → NONE, EXTENSION-sourced type → UNKNOWN |
| `ClassicAgreementTest` | on all fixtures and (if present) every dataset/Drive file: v2 type equals `Classifier.magic` or is a listed refinement (`mp4 → {mp4, mov, 3gp}`, `zip → zip family ∪ installer`, `heic → {heic, heif}`, `bin → *`, `mp3 → {mp3, unknown}`); prints each disagreement; fails on any outside the refinement table |
| `NameDatesTest` | each pattern's examples → exact epoch; invalid dates (`20241332_…`, Feb 30) → null; future (> now + 1 d) → null; pre-2000 → null; `WhatsApp Image … at 5.51.14 PM (2)` → 17:51:14; Drive inventory names (when present) |
| `ExifReaderTest` | LE and BE; DateTimeOriginal + OffsetTimeOriginal `+05:30` → exact UTC, `tzKnown TRUE`; without offset → local-as-UTC, `tzKnown FALSE`; orientation 1..8; Make/Model/Software trimmed of NULs; GPS pointer → `hasGps TRUE`; IFD offset loop (IFD1 → IFD0) terminates; entry count 60000 → bounded; offset beyond block → field unknown, no exception; orientation agrees with `tools.Exif.orientation` on all fixtures |
| `JpegProbeTest` | SOF0 and SOF2 dims; SOF after 3 × 60 KiB APP segments found; DQT quality estimate within ±5 of written quality 50/75/95; missing EOI → TRUNCATED; motion-photo XMP + trailing `ftyp` → `motionPhoto TRUE`, integrity OK; no SOF → CORRUPT |
| `ImageContainerProbesTest` | PNG IHDR/alpha/bit depth, missing IEND → TRUNCATED, `acTL` → animated; GIF 3 frames → `frames 3, animated TRUE`; WebP VP8, VP8L, VP8X dims + ANIM; BMP dims; ICO |
| `HeifProbeTest` | `FxHeif` primary item 4032×3024 + irot 1 → upright 3024×4032, orientation 6; grid primary item; EXIF item → DateTimeOriginal; missing ispe → dims unknown + problem; box size overflow → no exception; Drive corpus (if present): all `.HEIC` files have dims > 0, type heic/heif |
| `Mp4ProbeTest` | duration from mvhd (v0 and v1); tkhd 1920×1080 with 90° matrix → rotation 90; stsd `avc1` + `mp4a` → codecs, hasAudio TRUE; stts → fps 30.0 ± 0.01; moov after mdat → `moovAtEnd TRUE`; truncated mdat → TRUNCATED; size-0 last box; 64-bit box size; non-printable box type stops walk with problem; `moof` → fragmented; audio-only M4A → AudioInfo; creation time and track handlers equal `tools.Mp4.read` on the same fixture files |
| `MkvAviProbeTest` | EBML fixture: Duration (float64) × TimecodeScale → ms; video track 1280×720 `V_VP9`; unknown-size Segment; depth cap; AVI avih → duration/dims |
| `AudioProbeTest` | MP3 CBR 128 kbps 10 s → duration ± 1%, `durationConfidence 0.6`; with Xing frames → exact, 0.95; ID3v2 of 20 KiB skipped; FLAC total samples → exact; Ogg Vorbis/Opus last-granule duration (Opus pre-skip applied); WAV byte rate; random bytes named `.mp3` → type `mp3` with source EXTENSION (conf 0.5), `audio == null`, problem `audio:NO_FRAME_SYNC`, mismatch UNKNOWN |
| `PdfProbeTest` | 3-page plain → pages 3 `PAGES_ROOT`, encrypted FALSE, title/author decoded (escapes, octal, UTF-16BE BOM); `/Encrypt 5 0 R` in trailer → TRUE, title null, integrity ENCRYPTED; `/Encrypt` in an xref-stream dict → TRUE; linearized `/N 12` → 12 `LINEARIZED`; incremental update with roots Count 4 then 5 → 5; object-stream PDF → pages -1, problem `OBJECT_STREAMS`; no `%%EOF` → TRUNCATED; neither trailer nor XRef seen (cut head-only file) → encrypted UNKNOWN; 12 MiB file → only head/tail read (CountingReadableFile ≤ 8 MiB + 2 KiB) |
| `ZipProbeTest` | normal zip: names, sizes, CRCs equal `java.util.zip.ZipFile`'s; UTF-8 flag; non-UTF-8 name → `nameEncodingUncertain`; 120 entries with limit 50 → TRUNCATED, `declaredEntries 120`, `listedEntries 50`; `../evil` → pathTraversal; `/etc/x`, `C:\x` → absolutePaths; declared 10 GiB entry in a 1 KiB file → bombSuspect; encrypted flag → `encryptedEntries`, all-encrypted → integrity ENCRYPTED; 65 535-byte comment → EOCD found; cut in half → TRUNCATED; ZIP64 crafted → sizes from extra; CD > limit → LIMIT; stored nested zip → listed at depth 1 with `container`; deflated nested zip → counted only; zip-in-zip-in-zip all stored → depth 2 listed, depth 3 counted; prefix bytes before the archive (SFX) → still listed; subtype detection docx/xlsx/pptx/odt/epub/jar/apk/apks/xapk |
| `TarProbeTest` | ustar entries; GNU long name; checksum mismatch on first header → CORRUPT; data past EOF → TRUNCATED; header limit |
| `AxmlParserTest` | UTF-8 and UTF-16 pools; package/versionCode/versionName/minSdk/targetSdk; versionCodeMajor combined; attribute names obfuscated to "" → resolved by resource ids; versionName as REFERENCE → null; chunk size past buffer → partial + problem, no exception; string count 10⁶ → limit problem |
| `ApkSignaturesTest` | v2 block with cert bytes C → `sha256(C)`; v3 only; v2+v3 → scheme `v2+v3`; v1 PKCS#7 (built by `Der` writer) → cert hash; unsigned → empty list `none`; inconsistent block sizes → UNKNOWN (null) with problem |
| `ApkProbeTest` | `FxApk` + `JvmInstalledApps` stubs: same version+cert → SAME_VERSION / sameSigner TRUE; installed newer → INSTALLED_NEWER; installed older → INSTALLED_OLDER; absent with FULL → NOT_INSTALLED; absent with PARTIAL → UNKNOWN; UNAVAILABLE → UNKNOWN; different cert → sameSigner FALSE; zip named `.apk` without manifest → type `zip`, mismatch BENIGN, `apk == null` |
| `HashStageTest` | unique sizes are not read (CountingReadableFile 0 bytes); same size different content → quick hashes differ, no full hash; identical content → full SHA-256 equal to `MessageDigest` of the file, CRC32 equal to `java.util.zip.CRC32`; ≤ 128 KiB group → one read, quick = full; quickHash equals `CoreOracle.quickHash` for sizes 1, 65 536, 131 072, 131 073, 10 MiB; zero-byte → `SHA256_EMPTY` without open; policy ALL → every file hashed; incremental: adding one same-size file reads only it (+ full hashes on match); changed fingerprint → re-hash; byte budget → BUDGET_EXHAUSTED, pending bits, next run completes; read error mid-file → no HashRecord, bit failed |
| `ImageBasicsTest` | synthetic luma: uniform 128 → contrast 0, brightness 128, exposure 0; checkerboard → sharpness ≫ uniform; all-black → clipLow 1.0; gray ARGB → colorfulness ≈ 0, saturated colours → > 50; histogram sums to 10000; phash equals `ImageFeatures.phash` of the same luma; JPEG with EXIF orientation 6 vs the same picture physically rotated → equal upright dims, phash Hamming ≤ 4 |
| `JvmMediaDecoderTest` | header claiming 30 000 × 30 000 → TOO_LARGE before decode (heap delta small); HEIC → NO_DECODER; random bytes `.jpg` → CORRUPT; long edge ≤ 1024 and never upscaled (200 px image stays 200 px) |
| `T1MetadataStageTest` | TempTree with one fixture per type: expected type/mime/integrity/sub-record fields; unreadable (`FaultyStorageAccess.denyOpen`) → UNREADABLE, confidence 0.1, type from extension; probe exception fixture → problem recorded, other fields kept, bit 1 failed; createdAt priority EXIF > container > name > none, never mtime (mtime set to 2030 in fixtures must not appear); HEIC fixture → dims known, tier 3 NO_DECODER, status PARTIAL, `hasPhash false`; file changed during read (Faulty hook changes size after open) → discarded, pending |
| `IncrementalExtractionTest` | second run: 0 probes executed (probe invocation counter), bytes read 0; modify one file → only it re-extracted; `overrideVersionForTest(PDF, 2)` → only PDFs re-run; `ScanKind.FULL` → all re-run; deleted file's FeatureRecord kept but not selected |
| `SchedulerTest` | workers 0 vs 3 → identical store JSON lines; in-flight never exceeds capacity + workers + 64 (atomic gauge); `pauseAfterProgress` mid-T1 → PAUSED, resume → equal store; scripted throttle MODERATE → max concurrent workers observed = 1; SEVERE → PAUSED with `PL_PAUSED_DEVICE`; time budget with `FixedClock` → BUDGET_EXHAUSTED, `stoppedBy TIME`; `killAfterCommits(2)` during T1 → reopen + resume → equal to uninterrupted |
| `UnknownHandlingTest` (A9) | every undecodable/NO_DECODER image: all pixel fields NaN, `hasPhash false`, confidence ≤ 0.7, status ≠ COMPLETE; truncated JPEG still decodable → features present but integrity TRUNCATED and confidence ≤ 0.6; encrypted PDF: `encrypted TRUE`, title/author null, status COMPLETE only for tiers that apply (no tier 3) and integrity ENCRYPTED; no field anywhere defaults to 0 when unknown (reflection-free check: JSON of every FeatureRecord has `null`, never `0`, for the listed unknown fields of each fixture) |
| `V2CliPhase2Test` | JSON has `image/video/audio/apk/pdf/archive` per type with `null` unknowns; `--hash all` SHA equals Python-independent `MessageDigest`; `--max-tier 1` → tiers 2/3 pending, status PARTIAL; `--installed-apps` wiring |

Dataset/corpus checks (manual, by the implementer): `--gate phase2` on `/home/user/sp-dataset`; Drive corpus: 268 files, 36 HEIC with
dims known and pixel features unknown, 232 JPEG with phash and sharpness, `createdAt` source EXIF or FILENAME for ≥ 95% (mtimes
there are download times).

## 12. Exit criteria (Phase 2)

1. All Phase 1 and Phase 2 tests pass (`tools/v2test.sh`, CI).
2. `evaluate.py --gate phase2` on the dataset: type accuracy ≥ 0.99; SHA-256 agreement 100% (`--hash all`); unknown-handling 100%.
3. Drive corpus: every HEIC `PARTIAL` + `NO_DECODER` + dims known; no HEIC has a numeric pixel feature.
4. Second incremental run: `extractedThisScan == 0`, no file bytes read except T2 re-checks of changed size groups (none).
5. Encrypted-PDF, archive-bomb and path-traversal fixtures flagged as specified.
6. T1 throughput on the JVM dataset ≥ 300 files/s with `workers = 2` (reported in `timings`; regression > 25% is a warning).

## 13. Design choices

| # | Choice | Rejected alternative | Why |
|---|---|---|---|
| 2-1 | Own `TypeDetector` over `ReadableFile`; `Classifier.magic` is an oracle | Call `Classifier.magic(path)` | `magic` needs a local path (no SAF), knows 15 types, and its MP3 rule (`FF Ex`) misfires on binary data. The agreement test keeps the two honest. |
| 2-2 | Full SHA-256 only inside size + quick-hash collisions (classic quick-hash definition reused); ≤ 128 KiB hashed fully | Hash every file | Spec 14.1 / the lead's "only where needed"; reading every byte of a phone is the single most expensive step. `--hash all` exists for evaluation and Phase 9 revalidation. Same quick hash as classic lets the two algorithms be compared file by file. |
| 2-3 | Tier 3 decodes every decodable image, in area priority order, under a budget | Only camera/WhatsApp-sent like classic | Relationship detection (Phase 3: WhatsApp copies, downloads, edits) needs hashes of the copies too; the budget and priority keep cost bounded and visible (`pending`). |
| 2-4 | Undecodable images: header dims via container parse (`ispe`, SOF, IHDR), pixel features unknown, status PARTIAL | Skip them, or treat as low quality | A9 (unknown ≠ unimportant); the Drive corpus has 36 HEIC files the JVM cannot decode. |
| 2-5 | `createdAt` never falls back to `mtime` | Use mtime as last resort | mtime is reset by downloads/copies (all Drive corpus mtimes are download dates); a wrong "old" date is worse than unknown. |
| 2-6 | Archives: central directory only; inflate only tiny APK manifest/signature entries in memory with output caps; nested listing only for STORED entries | Inflate nested archives / extract to temp | Safe archive handling (spec 15) with no disk writes and bounded memory; deeper inspection is a Phase 6 (T6) decision for specific candidates. |
| 2-7 | PDF by regex over head/tail; object streams → unknown | Write a PDF object parser | Encryption and most page counts are visible without a parser; a real parser (with inflate of object streams) belongs to Phase 6 document intelligence. |
| 2-8 | APK manifest and signer parsed in pure Java; `PackageManager` only as fallback | Platform-only parsing | Testable on the JVM, works for APKs on any volume, and gives the signing certificate needed to call an installer "redundant" (spec 5.6). |
| 2-9 | `tools.Mp4` not called; own `Mp4Probe` | Extend `tools.Mp4` | `tools.Mp4.read(File)` needs a `java.io.File` and returns no duration/dimensions/codec; extending it would change a shipped tools class. It remains the oracle for creation times and handlers. |
| 2-10 | Results applied in job order (reorder buffer) | Apply as they complete | Store and JSON are identical for any worker count — required for reproducible evaluation. |
