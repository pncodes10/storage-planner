# Phase S implementation notes

Running log for the disk-backed store (design: `PHASE_S_STORE.md`, review edits `REVIEW_S F-…` binding; rulings A32–A35).
One entry per finished slice. Each slice ended with `bash tools/v2eval/run_tests.sh` and
`bash tools/v2eval/check_android_compile.sh` green.

## Baseline (before any slice)

* Gate baseline on `/home/user/sp-dataset`, captured with the unchanged code (`--now 1792000000000 --target-bytes max`, modes
  standard and aggressive): `<scratchpad>/v2out/base/{standard,aggressive}.{json,phase4.txt,phase5.txt}`, script
  `<scratchpad>/v2out/base/run.sh <outdir> <classesdir>` (scratchpad = `/tmp/claude-0/-home-user-storage-planner/69e294b1-7ff5-57b8-9df7-ed244c7e43c3/scratchpad`).
  To compare after a slice that touches stages: compile the tree into a fresh classes dir, run `run.sh` into a new dir and
  `diff` the four `.txt` files (and `evaluate.py --compare` the JSONs).
* `evaluate.py` exits 1 on both gates in the baseline. That is pre-existing, not a regression: phase-4 "exposure" FAIL for
  `WhatsApp/Media/WhatsApp Images/IMG-20240122-WA0001.jpg` (YOUR_CALL). Key numbers: DDRR 0/178 PASS both modes; deletion
  precision (all) 316/317 standard, 311/312 aggressive.

## S0 — harness and baseline (done)

Exists:
* `tools/v2eval/gen_synth.py OUT N [--images SHARE] [--seed S]` — the section 1.1 generator (from the scratch `gen.py`).
* `jvmtest/.../HeapProfile.java` — used heap after 4 GCs at checkpoints, optional class histograms (DiagnosticCommand
  MBean), per-table retained size. No reflection (explicit table accessors).
* `jvmtest/.../ScaleRun.java` — DISCOVER…GRAPH through `Pipeline`, then the `V2Runner.analyse` path inlined (ASSESS,
  recommendations, hash-on-demand, app subjects, `Plans.make`, `Plans.version`, flush, `V2Adapter.adapt`). Options
  `--store memory` (only value so far), `--profile`, `--histograms DIR`, `--tables`, `--no-adapter`. Prints
  `SCALE_RUN OK …`; an OOM exits 1 with `OOM:` on stderr.
* `tools/v2eval/run_scale.sh N [--xmx 2g] [--images 0.3] [--seed 7] [ScaleRun options]` — generates the tree once under
  `$SCALE_DIR` (default `$TMPDIR/v2scale`), compiles, runs under `-Xmx`.

Measured (B/file, after full GC; design section 1.2 value in brackets) — all within ±10 %:

| checkpoint | synth 10k | synth 100k |
|---|---:|---:|
| after DISCOVER | 1,360 (1,434) | 1,288 (1,296) |
| after T1 | 1,843 (1,917) | 1,767 (1,775) |
| after T2 | 2,343 (2,426) | 2,320 (2,319) |
| after T3 | 2,429 (2,503) | 2,364 (2,370) |
| after GRAPH, stage referenced | 4,443 (4,503) | 3,645 (3,655) |
| store after GRAPH | 2,625 (2,691) | 2,534 (2,541) |
| ASSESS verdicts | 5,192 (5,748) | 5,080 (5,082) |
| store + fm + recs | 3,159 (3,222) | 3,062 (3,072) |
| + `Plans.make` | 3,221 (3,274) | 3,120 (3,121) |
| + adapter result | 3,748 (—) | not run |
| store only, end | 2,665 (2,696) | 2,571 (2,543) |

Per table at 10k: files 1,317 B/record, features 599, hashes 344, relationships 740, pair checks 409, directories 475.
100k also reproduces the decode cap: GRAPH `BUDGET_EXHAUSTED`, decodes 20,001, pending 2,363 (section 1.6). Times: 10k
total 17.6 s; 100k 84 s (+ about 40 s generation).

Deviations:
* `ScaleRun` inlines the `V2Runner.analyse` body (V2Analysis arrives in S5 item 6; then ScaleRun must call it). Hash on
  demand = SHA-256 of the local file; sdk 34; total/free 0 for the adapter.
* The "ASSESS verdicts" mark is taken after `Assessor.run` returns, so the GraphView/ContextBuilder (≈ 500 B/file) is
  already released; the design's row included it (hence 5,192 vs 5,748 at 10k).
* `gen_synth.py` uses a fixed UTC time base and `gmtime` for camera names, so the tree no longer depends on the TZ.
* The dataset column of section 1.2 was not re-profiled (the dataset run is dominated by T3 on real photos); 10k and 100k
  reproduce the table.

## S1 — device layer and frames (done)

Exists:
* `BlobStore.openRead(name)` → `RandomRead {read(pos,b,off,len); length(); close()}`, null if absent. Implemented in
  `DirBlobStore` (`FileChannel.read(ByteBuffer,pos)`), `AndroidV2Device.AndroidBlobStore` (`RandomAccessFile "r"`,
  synchronized seek+read) and `KillingBlobStore` (pass-through).
* `KillingBlobStore.killAfterAppendedBytes(n)` (summed over all append streams; writes exactly up to n, flushes, kills) and
  `killBeforeTruncate(n)`; `appendedBytes()`.
* `v2.store.disk.Frame` — encode (`put/del/commit/header/encode`) and strict `parse(b, off, avail)`;
  `FrameException {truncated, needed}` (damaged vs truncated).
* `v2.store.disk.SegmentReader(RandomRead, chunk)` — `readAt(pos, limit)`: never requests a byte at or past `limit`; a
  frame that crosses the limit or the physical end is `truncated`; bad magic/fields/CRC is damaged. `reset()` after a
  segment was truncated and rewritten.
* `v2.store.disk.SegmentWriter.start(blobs, name, headerJson)` — truncate + sync + verify length 0 (F-02 a), then HEADER at
  offset 0; `append(frame)` → position; 64 KiB buffer; `flushBuffer()`, `sync()`, `length()`, `flushedLength()` (the
  readable limit of the active segment, 3.6 step 3).

Tests: `FrameTest` (in AllV2Tests, 39 checks): round trip of records of all 17 tables through `Codecs` (incl. non-ASCII
keys); every single-byte flip (4 masks × every byte of 5 sample frames incl. a 2-byte varint) detected; every prefix of
every sample refused as truncated; segment of 60 frames (some > 64 KiB) read randomly and sequentially; 300 random durable
limits — no read requested past the limit and every frame accepted/refused exactly as it fits; garbage and a valid COMMIT
after `durable` never accepted; segment shorter than its limit refused; an on-disk flip is damage; a stale segment is
truncated by `start`; 40 torn-append trials with `killAfterAppendedBytes`.

Deviations / additions beyond the design text (all stricter):
* Parser limits: key ≤ 64 KiB, value ≤ 64 MiB (garbage cannot cause a huge allocation); non-canonical varints rejected;
  after the CRC, structure is checked (PUT/DEL table 1..17, DEL value empty, COMMIT table 0 / empty key / 8-byte value,
  HEADER table 0 / key `sp-v2-ds`).
* Table ids 1..17 follow the `V2Store` accessor order (files, features, hashes, embeddings, relationships, duplicateGroups,
  backups, pairChecks, recommendations, actionPlans, operations, feedback, preferences, models, scanSessions, volumes,
  directories) — `FrameTest.tables(V2Store)` holds that order; S2 should move it into a production `TableIds` and keep the
  test using it.

## S2 — DiskStore core (done)

Exists (production code; nothing in the pipeline uses DiskStore yet, MemoryStore stays the default everywhere):
* `v2.store.disk.TableIds` — ids 1..17 ↔ codec ↔ snapshot name, `derived(id)` (features, hashes, embeddings, pair checks).
  `FrameTest` now checks its order against `FrameTest.tables(V2Store)`.
* `v2.store.disk.KeyCmp` — keys as UTF-8 extended to unpaired surrogates ("WTF-8": every Java String round-trips, distinct
  Strings never share bytes; equal to `getBytes(UTF_8)` for valid Strings); `compare` = `String.compareTo` order (F-03), ASCII
  fast path, decode from the first differing char otherwise. `pack/unpack/comparePacked`: the in-memory index form, where a
  key that is exactly 32 lower-case hex chars, or ends with NUL + 32 hex, stores those as 0xFF + 16 bytes (the S6 "packed hex
  keys" option, pulled forward, see deviations). Frames always carry the plain form.
* `v2.store.disk.KeyIndex` — sorted base (`keyArena/keyOff/val` + live `BitSet`, never modified in place), delta `TreeMap`,
  merge when delta or dead base entries exceed max(mergeMin, size/8) (constructor parameter); `range(lo, hi)`/`keys()` take a
  snapshot (base array refs + copied live bits + copied delta keys); bulk `load`/`endLoad` for the index file; `visit`.
* `v2.store.DiskStore implements V2Store` (+ `StoreCorruption` unchecked, `StoreStats`). `open/create(blobs, checkpointEveryMs,
  clock[, Options])`; `Options` = maxSegmentBytes (32 MiB), mergeMin (4,096), indexTailBytes (8 MiB), lruRecords/lruBytes
  (4,096 / 8 MiB), indexAtClose. Per record a slot (paged arrays, 4,096 per page): locator seg:16|off:48, 32-bit folded
  FNV-1a of the JSON, frame length. Secondary indexes are `KeyIndex` sets: files `vol\0path\0fileId` (byPath, byVolume,
  underPrefix, childrenOf as a range scan skipping deeper paths), relationships `node\0edgeId`, recommendations
  `fileId\0recId`, operations `opId\0key`; bySize/bySha256 lazy maps built on first call and kept up to date after; directories
  use the primary key. Read path: LRU → locator → `SegmentReader.readAt(off, limit)` (active segment: buffer flushed first,
  limit = flushed length) → F-04 checks (type PUT, table, key bytes, frame length, value hash, then decoded `key()`), any
  mismatch on a CRC-valid frame ⇒ `StoreCorruption` (+ manifest `damaged:true`), for derived tables too. Damage (CRC/length/
  truncated): derived ⇒ `null` + `damagedKeys()`/`stats().damaged`; others ⇒ `StoreCorruption` + manifest `damaged:true`.
  Writes: no-op detection by hash then byte compare (same as MemoryStore's JSON compare, `changedPutsSinceOpen` matches);
  PUT/DEL frames; undo list (key, previous locator/hash/length); secondaries updated from the old record (read and verified
  at its locator) and the new one. `commit` writes COMMIT(txn, commitSeq) only for a txn with frames; checkpoint cadence as
  MemoryStore (0 = every commit, −1 = adaptive max(5 s, 20 × last), else ms); rotation at a commit boundary checkpoints first.
  Checkpoint: sync active segment, index file when the uncovered tail > indexTailBytes (or at `close`), then the manifest.
  Manifest 3.3 with `hw`, `index {name, idxSeq, crc32}` (F-01) and the CRC trailer. A new segment raises `hw` in a manifest
  first (F-02 c), then `SegmentWriter.start` truncates (F-02 a); HEADER carries gen/set/seq/epoch. Open: manifest (damaged or
  newer version ⇒ `StoreException`), every listed segment ≥ durable and its HEADER matching gen/set/seq (F-02 b, F-05), index
  file used only if magic/version/gen/idxSeq/crc equal the manifest and every covers ≤ durable and every locator lies inside
  the covered range (F-01), else rebuild by scan; replay `[covers, durable)` COMMIT-gated with strictly increasing commitSeq ≤
  the manifest's; any damaged frame ⇒ `StoreException` (F-05). Appends always go to a new segment after an open.
  `create` writes a fresh manifest (gen+1, hw kept or probed), then truncates old segments of both sets, index files and
  `store.snap`. Test hooks: `debugLocator`, `debugSetLocator`, `debugDropCache`, `loadedFromIndex`, `merges`,
  `checkpointsWritten`, `commitSeq`.
* Interface changes of 3.8: `Table.keys()`, `V2Store.stats()`, `FileTable/DirTable.childrenOf` → `Iterable`. MemoryStore:
  `keys()` = copied key list, `stats()` = records × the 1.3 per-record figures, childrenOf unchanged (still returns its List).
  Callers: `DirectoryReconciler` unchanged (iterates), `StoreContractTest` counts with a helper. `GraphStage.diff`/`Perceptual`
  still decode whole tables to collect keys (switching them to `keys()` is a caller change for S5).

Tests (all in `run_tests.sh`; 36 suites):
* `DiskStoreTest` (88 checks): KeyCmp vs `String.compareTo` on 200,000 random pairs (emoji, U+10000, U+E000, U+FF01–FFEF,
  unpaired surrogates, 32-hex and NUL+32-hex packed forms) incl. round trips; KeyIndex vs TreeMap (60 seeds × 2,000 ops,
  mergeMin 1/8, snapshots compared after later writes); `StoreContractTest.run(DiskStore)`; persistence (fill of all 17
  tables equals MemoryStore, `dumpAll` equal, reopen by scan and by index, NaN/sentinels, checkpoint interval, missing
  manifest, future version refused); **kill trials** (300 by default, `-Dv2.killTrials`): a 160-op workload over all tables
  with commits/flush/close+reopen, killed by one of the four `KillingBlobStore` points (after/before replace commit k, after
  b appended bytes = torn frame, before truncate k), a third also with power loss; the reopened store's commitSeq must be the
  last completed checkpoint or the one being written, and its 17-table dump must equal the oracle at that commitSeq; then it
  must accept writes and reopen. **F-01**: kill between the index and the manifest replace ⇒ newer index rejected, exactly
  the previous checkpoint; every 40th byte of the index flipped or cut ⇒ rebuilt, identical; durable shrunk under the index's
  covers ⇒ index refused and the cut frame refuses the open. **F-02**: a committed phantom in segment 2 beyond the manifest
  (natural crash, hw already raised) never replayed, reuse truncates it, HEADER at 0; a forged segment 3 with matching HEADER,
  next txn and commitSeq never replayed, reuse truncates; a listed segment with another gen's HEADER refused. **F-04**: for
  each of the 17 tables a locator pointed at another key's frame (with its hash and length) ⇒ `StoreCorruption` and the
  next open refuses; files↔features frames of the same key; a forged frame whose record key differs; a forged frame under
  another key with value, hash, length and record key all matching (only the key-bytes check catches it). **F-05/3.11**: per
  table one record flipped and one segment cut inside it, read through an index-loaded store: derived ⇒ null + counted, rest
  readable; others ⇒ `StoreCorruption`, next open refuses, `create` resets and works; the same damage with the index removed
  ⇒ open refused (34/34); garbage after durable ignored (index and scan); damaged manifest refused. **F-03 in the store**:
  12×12 non-BMP paths, mergeMin 1: `all()` order, `byPath` finds every one, `underPrefix`/`childrenOf` equal MemoryStore,
  after a scan reopen and an index reopen.
* `StoreOracleTest`: per seed 300 random ops over all 17 tables against `MemoryStore.volatileStore()`: put, unchanged re-put
  (must not count in `changedPutsSinceOpen`), remove, commit, rollback, flush, reopen after clean close / process kill /
  power loss (`PowerLossBlobStore`: unsynced tails lost or partly kept, junk appended where blocks were in flight, unsynced
  files dropped), get/size, byPath, childrenOf (files, dirs), underPrefix (files, dirs), byVolume, bySize, bySha256,
  relationships.of, forFile, forOp, openOps, cache drops, and `all/keys/underPrefix/byVolume` iterators interleaved with writes.
  Full dumps of all 17 tables (pair checks included) after every commit/rollback/flush and every reopen; after a kill or power
  loss the disk store must equal the oracle at its last checkpoint. Options randomised per seed: mergeMin 1/8/4,096, segment
  700 B/4 KiB/1 MiB, index tail 0/2 KiB/8 MiB, LRU 0/3/4,096, index at close or not, checkpoint every commit/7 ms/adaptive.
  Default 300 seeds (≈ 4.5 s); `-Dv2.oracleSeeds=N`, `-Dv2.oracleOps=N`.
* `PowerLossBlobStore` (jvmtest): in-memory BlobStore with synced lengths and `powerLoss(rnd, keepUnsynced, garbage)`; also
  the S3 power-loss fake.
* `DiskStoreScale N` (manual, not in run_tests): the S2 exit measurement.

Numbers:
* Oracle: 2,000 seeds × 300 ops: 968,478 comparisons, 12,162 reopens (4,080 kills, 4,014 power losses), 114,475 interleaved
  iterations, 0 failures (28 s). 10,000 seeds (before packing): 0 failures (2 min 15 s). Kill trials: 3,000 (750 per kill
  point, 1,021 with power loss, 533 landed on the checkpoint being written): 0 failures.
* Mutation check (scratch copies): raw UTF-8 byte order in the comparator, replay without COMMIT gating, and rollback not
  restoring secondaries are each caught by the oracle and DiskStoreTest; removing the key-bytes check is caught by F-04 (d).
* S2 exit, `DiskStoreScale 100000` (files + features + hashes per file, relationships 20 %, pair checks 10 %,
  recommendations 5 %, one dir per 100 files; `-XX:+UseSerialGC` so G1 humongous-region rounding is not counted):
  `indexBytesEstimate` **261 B/file**, measured heap of the open store **265 B/file** (same after a reopen from the index);
  ≤ 280 met. Without packing: 321 / 375 (G1). Fill 5.4 s, iterate all files 1.3 s, reopen from the index 0.2 s; disk
  1,725 B/file live.

Deviations (with reasons):
* Packed hex keys (S6 option) are in S2: without them the index measured 321 B/file (> 280). They also pack the fileId
  suffix of the files path index.
* Per slot a 32-bit hash (folded FNV-1a 64) and a 4-byte frame length instead of a 64-bit hash: same 16 B as the design's
  loc+hash; the length gives exact live/garbage bytes and one more F-04 cross-check.
* Secondary indexes are KeyIndex sets of composite strings (not hash64 multimaps); results come back in key order and are
  re-checked against the decoded record (`of`/`forFile`/`forOp`; `byPath` fails closed if the index names a missing file).
* No pinned small tables: everything goes through the LRU (small tables are tiny; pinning changes no result).
* `StoreCorruption` is also thrown for I/O failures inside get/put (an unchecked type is needed there); those do not mark
  the manifest damaged, and the store refuses further commits (`StoreException`). F-04 mismatches mark it damaged (fail
  closed + reset; a bad locator in an index file would otherwise recur).
* `childrenOf` on DiskStore is a lazy key snapshot (values read when reached, F-16); MemoryStore still returns copies made at
  call time. They differ only if a caller writes the listed records while iterating; the only caller (`DirectoryReconciler`)
  writes the record it is on or other subtrees, so results are equal. The oracle compares childrenOf without interleaved writes.
* The oracle's power-loss junk is appended only to files that had an unsynced tail (a fully synced file, e.g. the manifest
  after `replace`, keeps its length).
* An empty commit increments commitSeq without a COMMIT frame (replay checks increasing commitSeq ≤ the manifest's).
* Keys: unpaired surrogates are stored as 3-byte sequences (WTF-8) rather than replaced by `?` as `getBytes` would.

## S3 — crash safety on the pipeline; CLI `--store` (partly done)

Done (both scripts green; production pipeline still uses MemoryStore only — `Stores` is used by the CLI alone):
* `v2.store.Stores` (5.1): `Kind {MEMORY, DISK}`, `open/create`. `V2Cli --store memory|disk` (default memory; without
  `--state-dir`, disk runs on an in-memory `PowerLossBlobStore`). Test hook `V2Cli.blobsHook`; `V2Cli.run` now resets the
  static result holders (`V2Json.graphCoverage`, `AssessJson`, `PlanJson.plan/staleness`) so it can run many times per JVM.
* `run_tests.sh`: the end-to-end kill test runs once per store (`--store memory|disk --snapshot-every-ms 0
  --halt-after-commits 5`, resume equals clean), then the 5.2 equality on that tree: memory vs disk with `--target-bytes max`
  must be identical apart from `timings`.
* `DiskKillTest` (in AllV2Tests, default 16 trials, `-Dv2.diskKillTrials`; `-Dv2.diskKillStore=memory` runs the same on
  MemoryStore as a control): 300-file tree (40 PNGs, 15 JPEG near-duplicates, 10 exact image copies, 225 docs with exact
  duplicates, non-ASCII dirs), the CLI pipeline with `--target-bytes max` in process on a DiskStore over
  `KillingBlobStore(PowerLossBlobStore)`; one of the four kill points per trial (k/bytes drawn from a clean run's counts), a
  third with power loss, then `--resume`. Required: equal to the clean MemoryStore run, under evaluate.py's IGNORED plus
  `coverage.extraction` and `graphCoverage.decodes` (per-run work counters: a resumed run re-uses or re-does work; F-18).
  When the kill lands after the scan's COMPLETED state became durable (CLI writes after the pipeline), the "resume" is a
  second scan; then the reference is MemoryStore's second run, ignoring `plan.planId/planVersion` (the killed run's plan was
  not durable). Also: clean disk run = clean memory run; second run over a persistent DiskStore = MemoryStore's second run.
  **200 trials: 200 killed (20 after completion, 66 with power loss), 200 equal** (37 s).
* `ResetSafetyTest` (F-06, in AllV2Tests): no `feedback()/preferences()/operations()` use under v2/ (except v2/store),
  v2adapter/ or app/; self-test with a planted use.
* `DiskStore`: once a fail-closed damage was seen, every later manifest keeps `damaged:true` (before, `Pipeline.fail`'s flush
  wrote `damaged:false` again and the damaged store would have reopened). `DiskStoreTest` now runs Pipeline.fail's
  rollback + FAILED session + flush before checking that the next open refuses (mutation-checked).

Found and fixed on the way:
* **`FeatureCodec` did not persist `ArchiveEntry.localOffset`.** In-run MemoryStore objects kept it, every reloaded copy lost it
  (-1), so `ArchiveMatcher.verify` answered `UNKNOWN/PRECONDITION` instead of `VERIFIED/SHA256_MATCH` after any reload — on
  MemoryStore after a kill/resume, and always on DiskStore (found by the dataset equality: two backups differed). Now
  written as `"localOffset"` (null when unknown) and read back; older snapshots read it as -1 as before. The only change to
  MemoryStore CLI JSON is that new field in `files[].archive.entries[]` (the CLI writes archives through the codec); the
  phase-4/phase-5 gate outputs on the dataset are identical to the baseline (both modes, `diff` of the four `.txt` files).
* `ExactGrouperTest` "changed member": rewrote a same-size file and relied on the coarse filesystem clock giving it a new
  mtime; it failed whenever the rewrite fell in the same tick (it started failing after DiskKillTest ran before it). The
  test now sets the rewritten file's mtime 2 s later.

Numbers: dataset (2,414 files, `--target-bytes max --snapshot-every-ms -1`, two runs over one state dir per store): memory
vs disk **identical apart from timings on both runs**. run_tests tree: identical. DiskKillTest 200/200.

Not done (next implementer starts here, in this order):
1. `D_STORE_DAMAGED` session event with the damaged count (from `DiskStore.damagedKeys()`), and the `V2Runner`/CLI rule
   "StoreCorruption or failed open ⇒ one automatic retry with a fresh store" (the CLI already falls back to `create` on a
   failed open and sets `cfg.storeReset`, giving `D_STORE_RESET`).
2. `DiskCorruptionTest` on the pipeline (features/hashes/pairChecks flip ⇒ next scan re-extracts, equals clean, damaged
   counted; files/relationships flip ⇒ run FAILED with StoreCorruption, next open resets, rescan equals clean; damaged/
   truncated `.idx` ⇒ rebuilt identical; garbage after durable; damaged manifest) and the F-07 between-stage damage test.
   The store-level halves exist in `DiskStoreTest.damage()`.
3. F-12 bullets still missing at pipeline level: kill between the index and the manifest replace inside a pipeline run
   (store level: `DiskStoreTest.indexMismatch`), pre-filled stale segment with a forced rebuild (store level: done), multi-run
   evolving tree (files added/removed/moved/modified between three runs) vs MemoryStore.
4. `tools/v2eval/store_equal.sh <tree>` and `ScenarioFixtureTest` with a store parameter (5.2).
