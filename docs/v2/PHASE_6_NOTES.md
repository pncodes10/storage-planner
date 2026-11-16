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
