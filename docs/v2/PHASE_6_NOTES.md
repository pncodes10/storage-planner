# Phase 6 implementation notes

## S1 — content model, codec, carry-over, tools

Created: `v2/model/{VisualInfo,TextInfo,DocInfo,ActivityInfo,TextStatus,DocStatus,ActivityStatus,DetectorStatus}.java` (exactly §4.2),
`v2/store/ContentCodec.java`, jvmtest `ContentCodecTest`, `ContentCarryOverTest`, `tools/v2eval/{p6_gate.sh,rec_diff.py}`.
Changed: `FeatureRecord` (`EXTRACTORS = 24`, 4 fields, `copy()`), `Extractors` (ids 20-23), `FeatureCodec` (calls `ContentCodec`),
`FileContext` (4 accessors), `T1MetadataStage.extract` (carry-over), jvmtest `V2Json.writeFile` (`content` object only when a
sub-record exists), jvmtest `Fx` (new `extraStages` list, appended after T1..T3 so a test can plug in a stub content pass), `AllV2Tests`.

Carry-over (F-B1): right after the fresh `FeatureRecord` gets its fingerprint, the previous record is read; if its fingerprint
`sameAs` the file's current one, `visual/text/doc/activity` and `versions[20..23]` are copied (sub-records are shared, never mutated).
A changed fingerprint drops them. `ContentCarryOverTest` (stub content pass in `Fx.extraStages`): FULL run 1 = 3 calls, FULL run 2 = 0
new calls with equal sub-records (and `extractedScanId` advanced, so T1 really rebuilt the record), INCREMENTAL = 0, one touched file =
exactly 1 call, extractor version bump = all again. Verified the test fails (6 failures) with the carry-over disabled.

Tests: `ContentCodecTest` 175 checks (round trip of all four records alone and through `FeatureCodec.FEATURE`, unknown omitted / `{}`,
old 20-version snapshot, bad enums, no raw text, copy independence, V2Json `content`), `ContentCarryOverTest` 17 checks.

### Deviations / choices
* D1 `FeatureCodec.write` emits the four keys only when the sub-record is non-null (other sub-records write `null`). The design says
  "omit unknown" and "snapshot growth small (A21)"; a missing key reads as null. Store files of S1 for files without content are
  byte-identical to the old ones except for the longer `versions` array.
* D2 `ContentCodec` read methods are `public` (design shows `static`), because the jvmtest lives in another package.
* D3 `DocInfo.category` default "UNKNOWN" is omitted on write and a missing key reads "UNKNOWN"; `TextInfo.attempts` 0 is omitted.
* D4 Doubles are written by `JsonWriter` with at most 6 decimals (existing behaviour); a value with more precision does not round-trip
  exactly. Future extractors should round to 6 decimals themselves.
* D5 `p6_gate.sh` also honours an optional `P6_DATASET` env var (default `/home/user/sp-dataset`) and writes `rc.txt`; otherwise as specified.
  The lead's `gate.sh` per-mode `.nt.json` (timings stripped) output was not copied: S1's `rec_diff.py` works on the raw result.
* D6 `rec_diff.py` prints `ABSENT` as the tier for a path missing in one of the two results; a file without recommendations is KEEP/None.
* D7 `--allow` semantics implemented as written: a change is accepted when the first reason code in OLD or NEW is in the allowed set.
  Without `--allow` and without `--expect-none` the tool only reports and exits 0.

### What S2 needs to know
* A content pass should select files by `fr.versions[Extractors.ACTIVITY] != Extractors.version(Extractors.ACTIVITY)`, build a new
  `FeatureRecord` copy (`fr.copy()`), set the sub-record and the version, and `put` it (see the stub in `ContentCarryOverTest`).
* `Fx.extraStages` can host a real `ContentStage` in jvmtests; V2Cli does not yet wire anything (S2).
* `Extractors.version()` is 1 for ids 20-23. `tiersDone/tiersPending` are untouched.

### S1 dataset steps — NOT RUN
`/home/user/sp-dataset/.rebuilt_ok` did not exist after ~10 minutes of waiting, so `p6_gate.sh /home/user/p6runs/S0` (on the unchanged
tree), `p6_gate.sh /home/user/p6runs/S1`, `rec_diff --expect-none` (standard and aggressive) and the dataset `store_equal.sh` are still to
be run. Suggested: `git stash`-free S0 run via `git archive HEAD` into a temp dir plus the two new tool files. The synthetic-tree store
equality inside `run_tests.sh` passed. `rec_diff.py` was exercised on two synthetic results (no change -> OK; one changed -> listed, FAIL).

### S1 dataset steps — RUN (during S2, dataset `.rebuilt_ok` present)
S0 = `git archive 595cf30` (plus the two S1 tool files) run through `p6_gate.sh` into `/home/user/p6runs/S0` (tree under `/home/user/p6runs/S0src`);
S1 = the S1 tree into `/home/user/p6runs/S1`. `p6_gate.sh` needed no fix (all rc 0). `rec_diff S0 S1 --expect-none`: standard "changed files: 0 of 2414",
aggressive "changed files: 0 of 2414". The phase4 and phase5 gate texts of S0 and S1 are byte-identical in both modes (the dataset label fix was
already in place for both runs). S1 is therefore confirmed neutral.

## S2 — ContentStage skeleton and MP4 activity

Created (prod, `v2/content/`): `Mp4Tables` (164 lines), `ActivityProfile` (126), `ContentStage` (157), `ContentConfig` (24), `ContentProviders` (8),
`TextReader`, `OcrResult`, `UnavailableTextReader` (§6.2 stubs), `FaceDetector` (empty placeholder interface, S6 fills it in).
Changed: jvmtest `V2Cli` (stage added when `--max-tier >= 4`, after the graph stage, `new ContentProviders(null, null)`, `new ContentConfig()`),
`AllV2Tests` (2 suites), `TestControl` (no longer `final`, so a test can wrap its own `ScanControl`).
Tests: `ActivityProfileTest` 65 checks (§8.1 table rows 1-11 incl. dataset S08, plus: no video track, no stsz, no stts, moov above the cap, no moov,
truncated mdat, box < 8, stts/stsz mismatch, unsorted stss, bytes read bounded, 580 truncations/byte-flips never throw, determinism, stage selection by
usage/type, version stamp, carry-over (2nd FULL run reads 0), version bump re-reads, unreadable file), `ContentStageResumeTest` 15 checks (30 MP4s:
pause after the first commit -> run 2 reads only the other 20 and the result equals one uninterrupted run, event detail `activity=20` then gone, a
later FULL run reads 0; byte-budgeted chain equals the uninterrupted run). `ForbiddenApiTest` green. `run_tests.sh`: 47 suites, 0 failures,
store equality OK, ALL PASSED; `check_android_compile.sh`: OK (release 8 and 17).

Stage: id `T4_TEXT`, selection (type mp4/mov/3gp, `UsageClassifier.of` in CAMERA_VIDEO/CHAT_MEDIA/DOWNLOAD, fingerprint matches the file, `versions[ACTIVITY]`
not current) in ascending fileId; per file: stop check (cancel, pause, throttle, budget, same order and effects as `ExtractionLoop`), read, `fr.copy()` +
`activity` + version stamp, `put`; commit every `ContentConfig.commitEvery` (200) files or 3 s together with the session; `ctx.control.progress` after each
commit and every 64 files; session counter `content.activity`; event `C_CONTENT_INCOMPLETE` (replaced each run, detail `ocr=N visual=M doc=D activity=A`,
absent when 0). No wiring in `V2Runner` or `V2Analysis` (S9/S10).

### Dataset (S2)
`p6_gate.sh S2` (default `--max-tier 3`) vs S1: `rec_diff --expect-none` OK in standard and aggressive (0 of 2414); gate texts identical. `p6_gate.sh S2m4 --max-tier 4`
vs S2: `--expect-none` OK in both modes (0 of 2414), gate texts identical. 31 files get an activity record at tier 4: 8 OK, 22 SHORT, 1 CORRUPT
(`VID_20211021_152000.mp4`, which T1 also marks integrity CORRUPT).
Scenario 8 (peak intervals in ms, `[start,end)`), all four windows found, control none:
* `VID_20211218_072048` (expect 455-465 s): `[454000, 465000]`, 11 peak windows, median 573.5, threshold 2621.5
* `VID_20221001_124052` (expect 211-217 s): `[0, 1000]` and `[210000, 217000]`, 8 peak windows (the first window peaks too: first non-key frames after the opening keyframe)
* `VID_20221108_181137` (expect 377-385 s): `[376000, 385000]`, 9 peak windows
* `VID_20240926_121949` (expect 302-307 s): `[301000, 307000]`, 6 peak windows
* control `VID_20210313_082140`: OK, 0 peak windows, `peaks == []`, irregularSync 0 (all five: irregularSync 0).

### Deviations / choices (S2)
* D8 `catch OutOfMemoryError` per file (§5.3) is not implemented: `ForbiddenApiTest` forbids catching `Error` (tests use `Error`s as process death). Reads are
  bounded (moov <= 16 MiB, counts checked against box size before allocating), so only `RuntimeException` and `IOException` are caught per file.
* D9 `ActivityStatus` has no FAILED: a file that cannot be opened/read (`IOException`) or throws a `RuntimeException` gets `videoStatus = CORRUPT` (unknown),
  problem `content:ACTIVITY:<Class>`, and IS stamped, so it is not retried every run (a changed file has a new fingerprint and is re-read).
* D10 §8.1 step 4b: an early sync sample is measured against the previous *regular* sync sample (early ones are skipped as reference). Otherwise test 9
  (one extra key at 4555 -> `irregularSync 1`) would count the regular key at 4600 too (distance 45 < 80).
* D11 Additional CORRUPT cases (design silent): stts entries not summing to the stsz sample count, stss not strictly ascending or outside 1..count, timescale 0,
  a moov child box that is < 8 bytes or overruns its parent, a file with ftyp but no moov. A truncated file (top-level box past EOF) is CORRUPT as written.
  `stsz`/`stts`/`stss` counts above 2,000,000 are TOO_LARGE (before allocation).
* D12 `ContentProviders` takes a `FaceDetector`; S2 created that as an empty interface only so the constructor `(TextReader, FaceDetector)` of §5.2 exists. S6 owns it.
* D13 `ContentConfig` gained `mp4MaxMoovBytes`, `commitEvery`, `commitEveryMs` (the test uses 10 to get a mid-run commit); `forProfile` implemented (trivial).
* D14 A video with no `vide` track gets an `ActivityInfo` with `NOT_RUN` (stamped, so not re-selected); written as `{}` by the codec.
* D15 `ContentStage` imports `v2.risk.UsageClassifier`/`FileContext` for the usage test (a minimal `FileContext` with `f` only; `UsageClassifier.of` reads only `f`).
* D16 Mp4Tables walks at most 4096 top-level boxes (then CORRUPT/UNSUPPORTED by the usual rules) and 200,000 boxes inside moov.
* D17 Windows are built only from non-key samples in time order (no array sized by duration); the median is over windows with >= 1 non-key sample; for an even count it is the mean of the two middle values.

### What S3a needs to know
* `ContentStage.run` runs `activityPass` only; the pass order of §5.1 is DOC first, so add `docPass` before `activityPass`, filling `pending[0]` on a stop
  (`pending = {doc, activity, visual, ocr}`, index 3 = ocr) and returning the stop outcome without running later passes (as `activityPass` does).
* Reuse `ContentStage.stopReason`, the commit pattern (`commit(ctx)` + `ctx.control.progress`) and `failed(...)`-style per-file catch; selection must check
  `fr.fp.sameAs(f.fingerprint())`.
* In tests: `fx.extraStages.add(new ContentStage(...))` with `fx.maxTier = 1`; `ContentConfig.commitEvery` can be lowered; `ContentStageResumeTest.PauseAfterFirstCommit`
  pauses after the stage's first commit; `FxImg.box/full/u32/cat` build containers.
* Dataset: `--max-tier 4` is needed to see content records (V2Json prints `content.activity`); the default run stays unchanged until S9.

## S3a — PDF text

Created: `v2/content/doc/PdfText.java` (553 lines), `v2/content/doc/PdfCMap.java` (175), jvmtest `PdfTextTest` (421, registered in `AllV2Tests`).
Changed: `ContentStage` (DOC pass `docPass`/`doc`, selection `docFormat`, counter `content.doc`, DOC runs before ACTIVITY), `util/BoundedInflater`
(new `inflateZlib`), jvmtest `ForbiddenApiTest` (`javax.xml` rule), jvmtest `V2Cli` (`--content-dump F.jsonl`: one line per file with content, `path`, `type`, `usage`,
then the sub-records through `ContentCodec`).

Tests: `PdfTextTest` 94 checks: §7.2 table rows 1-14 (14 = dataset `Rohan_Statement_2019_07.pdf`, skipped without the dataset), plus ASCII85 alone, nested parens/continuation/octal,
1-byte CMap with `bfrange` array, `/EncryptMetadata` is not encryption, `/ImageB` ProcSet is not a scan, XMP title/creator (plain and Flate), PdfInfo pages/title/author,
LZW only -> UNSUPPORTED, predictor stream, `'`/`"`/Td/T* newlines, control-character stream dropped, inline image bytes not text, conflicting CMap -> hex not decoded,
200,000-char cap, every 3rd truncation point (never throws), determinism. Hostile cases: circular references (`/Length 3 0 R` inside object 3, `/Pages` and `/Kids` pointing at
themselves), absurd `/Length` (999999999, 2147483647, 20 digits, -5, 0, too short), 12,000 streams (only the first 10,000 looked at), 50,000 `>>` and 100,000 `<<` before a stream
(dictionary search bounded to 8 KiB), 10 x 8 MiB inflated -> LIMIT in < 5 s, ASCII85 `z` bomb (3 MiB -> 12 MiB) skipped, invalid and truncated Flate, unbalanced `(` over 100 KB,
missing `endstream`, CMap range abuse (5,000 x 65,536 codes) in < 5 s, garbage CMap tokens, 400 random byte-flip corruptions and 200 KB random bytes (no exception).
Stage tests (Fx, `fx.maxTier = 1`): good / encrypted / scanned / truncated / over-`docMaxBytes` (TOO_LARGE) / junk / empty / txt (not selected in S3a) / open failure (CORRUPT, stamped),
version stamp, second FULL run reads 0, version bump re-reads, a changed file is re-read, no marker word of the text in the stored `DocInfo` JSON, problems or `extra`, pause after the first
commit (`doc=7` in the event) and resume equals an uninterrupted run.
`run_tests.sh`: 48 suites, 0 failures, store equality OK, ALL PASSED. `check_android_compile.sh`: OK.

### Dataset (S3a)
`p6_gate.sh S3a` (default tier 3) vs S2: `rec_diff --expect-none` OK in both modes (0 of 2414). `p6_gate.sh S3am4 --max-tier 4 --content-dump S3am4.dump.jsonl` vs S2m4: OK in both modes (0 of 2414).
PDFs at tier 4 (105): 42 OK, 55 NO_TEXT_LAYER, 5 ENCRYPTED (5/5 of the encrypted ones; passwords not used), 3 CORRUPT (`Documents/Insurance_scan.pdf` = "PDF header followed by garbage"
(manifest `corrupt_pdf`), `Download/.pending-1733800000-report.pdf` (10 bytes), and the dataset-root helper script `mutate.py`, which T1 types as pdf because its source contains `%PDF-`).
Cross-check with `pdftotext` (>= 20 non-blank chars = has a text layer): 41/41 text-layer PDFs are OK (100 %), 55/55 without text are NO_TEXT_LAYER (these are the scanned PDFs and the image-only
invoice/paystub PDFs; the "11 scanned" of the design are among them), the 1 remaining OK is `hello_world.pdf` (25 chars incl. whitespace, poppler 12 non-blank), and the owner-password-only
`Rohan_Statement_2024_09_protected.pdf` is ENCRYPTED (unknown) although poppler could read it - by design (spec 5.3).

### Deviations / choices (S3a)
* D18 FlateDecode is zlib-wrapped, `BoundedInflater.inflate` is raw deflate: added `BoundedInflater.inflateZlib(byte[], off, len, maxOut)` (null on > maxOut, invalid data or a preset dictionary; truncated input returns what was inflated).
* D19 Types are the detector's names (`f.type`): `pdf`, later `ooxml-docx` etc.; `DocInfo.format` carries the design's names.
* D20 Encryption is checked twice: `PdfInfo.encrypted == TRUE` (design) and an own scan of the (<= 16 MiB) bytes for `/Encrypt` followed by `N G R` or `<<` (PdfProbe only looks at head/tail of files > 8 MiB and `fr.pdf` is null when T2 did not run).
* D21 A stream skipped for `/Predictor >= 2` or a non-name `/Filter` (indirect) is also counted in `unsupported` (design: skip only), so a PDF whose text is behind such a stream becomes UNSUPPORTED, not "OK with few chars".
* D22 Two passes over the decoded streams (pass 1: CMaps, ObjStm page objects, XMP; pass 2: text) instead of keeping decoded streams in memory; the 64 MiB total is counted in pass 1 (LIMIT returns no text; the stage stores chars/words -1).
* D23 Hex strings without any usable CMap are decoded as 1-byte Windows-1252 unless they contain a byte < 0x20 (then ignored); the stream-level "> 30 % control characters" rule drops garbage streams. A conflicting CMap (> 10 %) ignores all hex strings and adds 1 to `unsupported`.
* D24 Text of a stream has its trailing newlines/blanks trimmed; streams are joined by one `\n`. `chars` therefore excludes trailing line ends. Added bounds not in the design: 100,000 operands per operator, CMap work cap 1,000,000 mappings, inline images (`BI .. ID .. EI`) skipped.
* D25 NO_TEXT_LAYER condition is `chars < 20 && pages != 0 && /Image (word boundary, so `/ImageB` ProcSets do not count) present`; "pages unknown" (-1) is treated like pages >= 1 so a scan with a hidden page tree is still "needs OCR", not "OK, no text".
* D26 `DocInfo.chars/words` only for OK (all other statuses keep -1 = unknown, also NO_TEXT_LAYER); `pages` whenever known (also NO_TEXT_LAYER, not ENCRYPTED); `unsupportedStreams` for every status except ENCRYPTED. `createdAt/modifiedAt` stay unknown for PDFs.
* D27 When a DOC pass stops (pause/budget), `pending[1]` (activity) is filled from the activity selection so the `C_CONTENT_INCOMPLETE` detail counts files of the passes that did not run.
* D28 Title/author are in `PdfText.Result` for S4 (`DocFacts.fill(doc, text, title)`); the stage does not keep them.
* D29 `--content-dump` lists only PRESENT files that have at least one content sub-record; `usage` is `UsageClassifier.of` (as the design's "path, usage and sub-records").

### What S3b / S4 need to know
* `ContentStage.doc(...)` is the single place that turns an extractor result into a `DocInfo` (text dropped there); S3b extends `docFormat(f)` and the dispatch in `doc(...)`. S4 calls `DocFacts.fill` right where the text/title are still in scope (inside `doc(...)`, before the `Result` goes out of scope) and bumps `Extractors` DOC_TEXT to 2.
* Dataset needs `--max-tier 4` (+ `--content-dump F`) to see anything; `/home/user/p6runs/S3am4` is the reference run for S3b (`rec_diff S3am4 S3bm4`).

## S3b — OOXML, ODF, OLE2, plain text

Created (`v2/content/doc/`): `XmlText` (179 lines), `OoxmlText` (208, also home of the shared `OoxmlText.Result`), `OdfText` (66), `Ole2` (49), `PlainText` (65); jvmtest `OoxmlTextTest` (400, registered).
Changed: `ContentStage` (`docFormat` selection and the dispatch inside `doc(...)`), jvmtest `PdfTextTest` (its "txt not handled" check became "txt handled", counts 7 -> 8).
Zip access reuses `ZipProbe.list` (default `Limits`) and `ZipProbe.read(rf, size, entry, 4 MiB, ratio 200)`; nothing re-implemented.

Tests: `OoxmlTextTest` 93 checks: XmlText (entities incl. numeric and unknown kept, comments/PI/CDATA, `>` inside attributes, self-closing break, depth 300, mismatched and unterminated
tags, DOCTYPE entities not expanded, maxChars cap, firstText/attribute); docx (two `w:p` -> two lines, footnotes, core.xml title/creator/created/modified incl. a +05:30 offset, app.xml pages),
xlsx (`&amp;`, sheets not read, no sharedStrings = OK with 0 chars), pptx (slides 10 and 2 -> 2 first, `slide1.xml.rels`/`slideX`/`slide201` ignored, slide count as pages), ODF (text/meta, page count,
manifest `encryption-data` -> ENCRYPTED), OLE2 (UTF-16LE `EncryptedPackage` / `EncryptionInfo` -> ENCRYPTED, ASCII spelling no hit, only the first 4 MiB searched, legacy -> UNSUPPORTED),
plain text (UTF-16LE/BE BOM, UTF-8 BOM, UTF-8, Windows-1252, a multi-byte character cut at 1 MiB, binary -> UNSUPPORTED), dataset `Letter_to_Society_Secretary.docx` (OK, chars > 100), and the stage
dispatch (docx/xlsx/pptx/odt/ods/odg-not-selected/OLE2 encrypted + legacy/txt/csv incl. a `.csv` typed text/`.log` not selected/TOO_LARGE txt/junk zip/encrypted flag/open failure, privacy marker, 2nd run reads 0).
Hostile inputs (the "tests first" list): zip bomb entry (40 MiB inflated, 40 KB compressed) -> LIMIT in well under 1 s; a central directory that lies (declares 1000 bytes) -> not OK, no text, no exception, < 1 s;
ODF bomb -> LIMIT; nine 3.9 MiB slides (> 32 MiB read in total) -> LIMIT; every 5th truncation point of a docx; 500 random corruptions parsed as docx and as odt; EOCD with absurd counts -> CORRUPT;
100,000 nested elements; 500,000 entities (cap 200,000 chars); duplicate/traversal entry names; encrypted flag in the central directory.
`run_tests.sh`: 49 suites, 0 failures, store equality OK, ALL PASSED. `check_android_compile.sh`: OK.

### Dataset (S3b)
`p6_gate.sh S3b` (tier 3) vs S3a and vs S2: `rec_diff --expect-none` OK in both modes (0 of 2414); `S3bm4` (`--max-tier 4`) vs S3am4 and vs S2m4: OK in both modes (0 of 2414); phase4/phase5 gate texts identical.
Tier 4 dump: docx 8/8 OK, xlsx 3/3 OK, pptx 2/2 OK, csv 2/2 OK, txt 3/3 OK (the other 5 `.txt` files are 0-byte `empty_*.txt`, never selected), PDF as in S3a. The dataset has no ODF, no OLE2 and no
password-protected Office file, so ENCRYPTED/ODF/OLE2 are covered by the synthetic tests only. Design target "all dataset OOXML/ODF documents OK or ENCRYPTED": met.

### Deviations / choices (S3b)
* D30 Type names are the detector's: docx/xlsx/pptx from `ooxml-docx/xlsx/pptx`; ODF only when type `odf` and extension odt/ods/odp (odg etc. not selected); OLE2 = detector type `ole2` (any extension, so a password-protected `.docx`, which is an OLE2 file, is found); txt = type `text` with extension txt, and `.csv` files typed `text` or `csv` are format csv (dataset `contacts_backup.csv` is typed text by the detector).
* D31 txt/csv: only the first 1 MiB is read (not the whole file as §7.1 says; `PlainText` uses 1 MiB anyway); `TOO_LARGE` above `docMaxBytes` still applies. A buffer with > 30 % control characters is UNSUPPORTED (binary), not OK text. A character cut by the 1 MiB limit is dropped instead of forcing Windows-1252.
* D32 ODF: `text:h` also ends a line (design lists `text:p` only), and a manifest with `encryption-data` is ENCRYPTED (otherwise the encrypted `content.xml` would only fail to inflate = UNSUPPORTED, also unknown, but wrongly labelled).
* D33 A missing main part (`word/document.xml`, `xl/workbook.xml`, any `ppt/slides/slideN.xml`, `content.xml`) is UNSUPPORTED (design silent); an xlsx with a workbook and no sharedStrings is OK with 0 chars. A part that cannot be read (ratio guard, bad data, lying size) with < 20 chars collected is UNSUPPORTED, never "OK, no text".
* D34 The design's ratio guard (`ZipProbe.read` maxRatio 200 for entries > 4 KiB) makes a highly repetitive but small entry UNSUPPORTED (not LIMIT); only a declared size > 4 MiB is LIMIT.
* D35 `DocInfo.createdAt/modifiedAt` are filled from OOXML core.xml / ODF `meta:creation-date` (ODF has no modified date in the design); `pages` from app.xml (`Pages` docx, `Slides` pptx, slide count if absent) or `meta:page-count`; xlsx/ods/txt unknown. Title and author are kept only in `OoxmlText.Result` (dropped by the stage). `unsupportedStreams` stays -1 for non-PDF.
* D36 `XmlText.text` returns `XmlText.Out {StringBuilder text; boolean malformed; boolean full}`; "malformed" = depth > 256, a tag without `>`, an unterminated comment/CDATA/PI, or an end tag that does not match; it stops there and the extractor keeps what it has (OK if >= 20 chars else CORRUPT). Entities beyond the five predefined + numeric ones are never expanded.
* D37 `text:s`/`text:tab` (ODF spaces) are not text elements, so runs of `text:s` do not add blanks; word counts of ODF files with such markup can be slightly low (S4 only uses them as coarse facts).

### What S4 needs to know
* The text is available in `ContentStage.doc(...)` as `PdfText.Result.text` (pdf) or `OoxmlText.Result.text` (everything else, same class for ODF/OLE2/plain) together with `title`/`author`; call `DocFacts.fill(di, text, title)` there before the results go out of scope, and bump `Extractors` DOC_TEXT to 2.
* Statuses to treat as unknown (never "no text", never unimportant): ENCRYPTED, UNSUPPORTED (legacy OLE2, unreadable parts), CORRUPT, LIMIT, TOO_LARGE, NO_TEXT_LAYER (scanned; needs OCR). Only OK has `chars/words`.
* Dataset reference runs: `/home/user/p6runs/S3bm4` (tier 4) and `S3b` (tier 3), dump `S3bm4.dump.jsonl`; the dataset PDFs: 42 OK / 55 NO_TEXT_LAYER / 5 ENCRYPTED / 3 CORRUPT.
