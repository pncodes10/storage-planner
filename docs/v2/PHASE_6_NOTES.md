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
