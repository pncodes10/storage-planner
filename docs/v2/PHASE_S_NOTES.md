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

## Next: S2 — DiskStore core (not started)

Start here. Nothing of S2 exists yet; the tree is green. Suggested order, each step compiling and green:
1. `v2.store.disk.TableIds` (id ↔ codec ↔ snapshot name, from the order above) and a `KeyCmp` comparing UTF-8 key bytes
   as UTF-16 code units (= `String.compareTo`, F-03) with an ASCII fast path; unit-test it against `String.compareTo` on
   random strings mixing emoji, U+E000, U+FF01–U+FFEF.
2. `KeyIndex` (base arrays `keyArena/keyOff/slot`, per-slot `loc` (segment:16|offset:48) and `hash` (FNV-1a 64 of the
   JSON), delta `TreeMap<String,Integer>`, `DELETED`, merge at max(4,096, n/8) — make the threshold a constructor parameter
   so the oracle can force 8), with `keys()` snapshot (base refs + copied delta).
3. `DiskStore implements V2Store` on `SegmentWriter`/`SegmentReader`: get (pending/LRU → loc → `readAt` with the limit =
   `durable` or the writer's `flushedLength()`, flushing the buffer first if needed) with the F-04 checks (type PUT, table,
   key bytes, decoded record key) → `StoreCorruption` (new unchecked exception) on a valid-CRC mismatch; put/remove with
   undo list; commit/rollback; manifest (3.3 incl. F-01 `idxSeq`/`crc32` and F-02 `hw`) via `replace`; open/replay
   (COMMIT-gated, F-05 fail closed); index file last (it is an optimisation: rebuild-by-scan must be complete first).
   Secondary indexes (files by vol\0path\0fileId, relationships by node, recommendations by file, operations by op) can
   start as simple sorted/`TreeMap` structures if needed for correctness first; S6 measures bytes.
4. Interface changes of 3.8 (`childrenOf` → `Iterable`, `Table.keys()`, `V2Store.stats()`) applied to MemoryStore too, with
   the callers listed there; MemoryStore behaviour must not change.
5. Tests: `StoreContractTest.run(DiskStore)`, `StorePersistenceTest` on DiskStore, `StoreOracleTest` (2,000 seeds × 300 ops,
   MemoryStore oracle; note `StorePersistenceTest.dumpAll` omits `pairChecks` — the oracle dump must include it), forced
   merge threshold 8. Exit: `stats().indexBytesEstimate` ≤ 280 B/file on a 100k synthetic store.
