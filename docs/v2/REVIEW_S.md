# REVIEW_S: independent review of PHASE_S_STORE.md (disk-backed v2 store)

Scope: the design in `docs/v2/PHASE_S_STORE.md`, checked against the current store (`v2/store/`), its callers (pipeline,
stages, graph, risk, plan, `v2adapter`, `app/V2Runner.java`, `jvmtest/.../V2Cli.java`), `BlobStore` and its three
implementations, ARCHITECTURE 4–5.2, the kill test in `tools/v2eval/run_tests.sh`, and decisions A3, A13, A21, A31, A32.
Nothing was run; claims were checked by reading code. Fixes for every BLOCKER and MAJOR finding are written into
PHASE_S_STORE.md in place, tagged `REVIEW_S F-nn`.

## Verdict

**Approved with the fixes applied.** The direction is right: pure Java, testable here, behind `V2Store`, plus the streaming
caller changes without which a disk store buys little. As first written, the design had three ways to return the wrong
state silently: an index file not bound to its manifest, stale bytes in a reused segment name, and a key order that differs
from `String.compareTo`. The fixes are small and are now part of the spec. Implementation can start with S0, S5 and S7, which
do not depend on the store, and continue with S1–S6 as amended. The 150,000-file target at 256 MB holds only with the S6
option as specified by F-09. Without it the figure is about 133,000.

Counts: **3 BLOCKER, 10 MAJOR, 5 MINOR.** All BLOCKER and MAJOR findings are FIXED in the design. One open question for the
lead is in F-06.

## Findings

| Id | Sev | Section | Problem | Required change | Status |
|---|---|---|---|---|---|
| F-01 | BLOCKER | 3.3, 3.4, 3.9 | The index file (`ds-<set>.idx`) is replaced in place under one name, and open only checks its CRC and `gen`. A kill between the index `replace` and the manifest `replace` leaves an old manifest that names a newer index with the same gen and `covers` beyond `durable`. The store then reopens past its checkpoint, and a later rebuild-by-scan disagrees with it. If the index is written before the segments are synced, a power cut leaves it pointing at lost bytes. | The index carries an `idxSeq`. The manifest records `{name, idxSeq, crc32}` of the index file. On open the index is used only on an exact match and when `covers[i] ≤ durable[i]`; otherwise the indexes are rebuilt by scanning. The index is written only after segment sync and right before the manifest that names it. | FIXED |
| F-02 | BLOCKER | 3.1, 3.9, 3.10 | "Start a new segment" after open or rotation can reuse a file name that already holds bytes from a run that crashed after its last checkpoint, including COMMIT frames whose txn numbers the new run reuses. Append mode then writes after those bytes, so the HEADER is not at offset 0, and a rebuild-by-scan replays phantom commits. Compaction must also "truncate every ds-b-* file", but `BlobStore` cannot list a directory. | Truncate and sync every segment before its first append. The HEADER sits at offset 0 and carries gen, set, seq and epoch, and replay requires it to match the manifest. The manifest gains a per-set high-water mark `hw`, raised by a checkpoint before any segment beyond it is created. Compaction truncates segments 1…`hw`. | FIXED |
| F-03 | BLOCKER | 3.5 | Binary search compares UTF-8 bytes, which is code-point order. `String.compareTo` (MemoryStore's TreeMap order) is UTF-16 code-unit order. The two differ when a supplementary character (emoji in a file name) meets a character in U+E000–U+FFFF. Effects: `byPath` misses an existing file, so the file reads as absent and DISCOVER creates a second record for it, and iteration order changes. The "fallback on mismatch" had no runtime trigger. | Compare the arena keys as UTF-16 code units, with an ASCII fast path, so the order is exactly `String.compareTo`. The oracle test includes emoji, U+FF01–U+FFEF and private-use characters. | FIXED |
| F-04 | MAJOR | 3.6, 7 | The check of the decoded key against the requested key was only mentioned under Risks. The read path checked only magic and CRC, so a stale locator to a valid frame returns a different record. | Every read checks CRC, frame type, table id, frame key, the record's own key, and that the frame ends within the durable or written length. A mismatch on a frame with a valid CRC throws `StoreCorruption` for every table, derived tables included, and is never turned into `null`. | FIXED |
| F-05 | MAJOR | 3.9, 3.11 | The design did not say what happens when a CRC fails below `durable` during replay or rebuild, or when a listed segment is shorter than its `durable` (a new file's directory entry lost in a power cut). "Stop at the bad frame and continue" is the A13 failure: it silently loses later commits. | Fail closed: the open throws `StoreException` and the caller starts a fresh store (subject to F-06). | FIXED |
| F-06 | MAJOR | 3.11 | A reset discards `feedback`, `preferences` and `action_operations`, which hold user decisions and the Phase 9 journal of what was moved or trashed. Today no production code writes them (checked by grep), so nothing is lost yet. The design did not guard that assumption. | Add `ResetSafetyTest`, which fails if production code writes those tables. The phase that first writes them must define how they survive a reset before it removes the test. | FIXED (guard). Salvage design LEFT FOR LEAD (question below) |
| F-07 | MAJOR | 3.11, S3 | "Damaged derived record ⇒ re-extract" holds only when the producing stage still has to run. Damage found after T1–T3 have run in the same run is not re-extracted in that run. Safety then rests on "missing ⇒ unknown". The code supports that (`RecoverabilityEstimator` gives UNKNOWN to an image without a TRUE/FALSE perceptual status, and a missing hash only removes duplicate evidence), but nothing tested it. | Inject damage between T3 and GRAPH and between GRAPH and ASSESS. Required: the run completes, its removal recommendations are a subset of the clean run's, it adds no `NO_VERIFIED_BACKUP` and no perceptual FALSE, and the next run equals the clean run. | FIXED (tests) |
| F-08 | MAJOR | 0, 2, S6 | The 1.25 was labelled an "ART header" correction. ART headers are 8 B and ART strings are compressed, so objects on ART are smaller, not larger. The real unknowns are GC headroom, large-object-space fragmentation for the multi-MB index arrays, and unmeasured transients, about ±25 %. Scale test (b) at `-Xmx200m` spends the whole factor, so it does not prove what the guard would claim. | Keep 1.25 as an explicit safety factor. Run (b) at `-Xmx148m` = (0.8 × 256 − 20) / 1.25. `V2Capacity` may not claim more than passes at that cap. Recomputed capacity: about 133k without S6, about 175k with S6. | FIXED |
| F-09 | MAJOR | 2, S6 | The S6 option ("compact projection, then close the store") was neither costed nor specified, and it conflicts with `StoreFileMap`, which needs an open store. The 150k claim rests on it. | Keep a `long[]` of the locators of PRESENT files in ascending id (8 B/file) and release all indexes. `adapt` decodes each locator in turn and builds the `FileItem`s directly. Peak = 798 B × 1.25 per file, about 177k at 256 MB. | FIXED |
| F-10 | MAJOR | 4.4, 4.5 | Equality risks that were not pinned. (a) Today recommendations see the FileRecords after ASSESS has written U/L/R/C; `ofOne` must get the same records. (b) A store-backed `GraphView` sees ASSESS's writes in the middle of the loop. (c) `GraphView` list orders. (d) `StoreFileMap` inherits `AbstractMap`'s `containsKey`/`get`, which scan and decode the whole table. | Pin each point with a test. `ofOne` gets the post-write record. No rule may read another file's U/L/R/C (true today), or the view masks those fields. Orders stay as `GraphView.of` gives them. `StoreFileMap` overrides `get`, `containsKey`, `size` and the iterators; every other method throws. Golden JSON is recorded from the unchanged code. | FIXED |
| F-11 | MAJOR | 3.10, 3.12 | With compaction only at stage boundaries, the log can reach about 3 × live before compaction, and the compaction copy adds another live, about 4 × live ≈ 10 KiB/file in total. The preflight assumed 6 KiB/file. "Checked at checkpoints" and "run inside flush() at a stage boundary" also contradicted each other. | Compact at any checkpoint where the trigger holds; a checkpoint is always outside a txn, and iterators hold keys only. Peak ≈ 3 × live. Preflight = files × 8 KiB + 64 MiB + reserve, about 1.2 GB + 256 MiB at 150k. | FIXED |
| F-12 | MAJOR | S3 | `KillingBlobStore` kills keep every unsynced byte, so no test exercises "what was fsynced and when". There were also no tests for F-01, F-02, F-04 or F-05, and none for the app's real usage: repeated runs over one persistent store without resume. | Add a `PowerLossBlobStore` fake: on a kill each file reverts to its last synced length, a random garbage tail may follow, unsynced new files are dropped, and an uncommitted replace is lost. Add targeted tests for F-01, F-02, F-04 and F-05, and a three-run evolving-tree test against MemoryStore. | FIXED |
| F-13 | MAJOR | 1.6, 6, 7 | The decode cap (`GraphConfig.MAX_DECODES_PER_RUN`, checked in `Perceptual.run`) makes GRAPH return `BUDGET_EXHAUSTED`. The pipeline then pauses and `V2Runner` shows no result. A phone with about 30k photos never gets a result, whatever the store does. Downstream is already safe: a pending pair gives perceptual UNKNOWN and then `Recoverability.UNKNOWN`, never "unique". | New slice S7: order pending pairs by component for cache reuse, and turn the cap into a per-run budget. When the cap is reached, GRAPH ends `DONE` with pending counts, the result is shown with "run again to finish", and the persisted PairChecks carry the work over. Persisting signatures is rejected: about 195 KB each, about 6 GB for 30k photos. Tests are in S7. | FIXED (slice added) |
| F-14 | MINOR | 3.5 | A merge briefly holds the old and the new base arrays (about +17 MB for `files` at 150k, during DISCOVER). Delta TreeMaps can reach about 2.4 MB per large table. Neither is in the budget. | Count both in `STORE_FIXED` and measure them in S6. | FIXED |
| F-15 | MINOR | 3.5 | `long[] pathHash` (8 B/file) has no consumer. | Cut it. | FIXED |
| F-16 | MINOR | 3.8, 3.13 | Cursor lifetime and writing during iteration were left implicit, and `DirectoryReconciler` writes inside `childrenOf` loops. | State that there are no Closeable cursors (handles stay in the store's LRU) and that the key snapshot is taken when the iterator is created. The oracle test interleaves writes with open iterators. | FIXED |
| F-17 | MINOR | 5.3 | "m-11 goes away" overstates the gain. DISCOVER still grows the index at about 110–170 B/file. | `CapacityGuard` also checks the file count every 10,000 records during DISCOVER. | FIXED |
| F-18 | MINOR | S3, S7 | Above the decode cap, a killed-and-resumed run gets a fresh per-run counter, so it legitimately differs from a clean run. | Run the kill-equality tests on trees below the cap. This is noted in S7. | FIXED |

## Checks that passed (no finding)

* Truncation uses `BlobStore.truncate`, which is `FileOutputStream(f, false)` + fsync on Android and `FileChannel.truncate(0)`
  on the JVM. Neither is on the audit's forbidden list. `RandomAccessFile` reads are allowed. Nothing deletes or renames:
  the manifest and index files go through `AtomicFile`, as today.
* Commit, checkpoint and manifest: segment bytes are synced before the manifest that names their `durable` length, so with
  F-01, F-02 and F-05 the manifest never points past durable data. Compaction commits at the manifest replace; a kill before
  it leaves gen G intact, and a kill after it leaves only stale files of the other set.
* Rollback: frames without a COMMIT are ignored on replay. The undo list and forced merges are covered by the oracle test
  with the merge threshold forced to 8.
* Signature changes: `childrenOf` has two production callers (`DirectoryReconciler:15,30`), both plain for-each loops, and
  `StoreContractTest:68` uses `.size()`. `keys()` must also be added to `MemoryStore` (implied). `bySize` and `bySha256` have
  no production caller, which confirms the lazy indexes.
* GRAPH and ASSESS equality: `Assessor.run` iterates `files().all()` and writes only U/L/R/C. `HashOnDemand` reads `fm` and
  does not mutate it. `V2Adapter.adapt` sorts the ids ascending, which matches `StoreFileMap`'s ascending-id stream once
  F-03 holds.
* Slices: S0, S5 and S7 do not touch the store; S1–S4 add code the app does not use until S6. Each slice can stop green.

## Memory, recomputed

The per-file figures below are JVM bytes from the document's targets. Budget = 0.8 × 256 MiB − 20 MiB used − 16 MiB store
fixed = 176.9 MB.

| Phase | JVM B/file | × 1.25 | Files at 256 MB |
|---|---:|---:|---:|
| GRAPH (index 270 + graph 270; minus 64 MiB reserve) | 540 | 675 | ≈ 163k |
| ASSESS (270 + view 30 + recommendations 290) | 590 | 738 | ≈ 240k |
| End of PLAN + adapter (270 + 290 + 500) | 1,060 | 1,325 | **≈ 133k** |
| Same, S6 option per F-09 (8 + 290 + 500) | 798 | 998 | **≈ 177k** |

The binding phase is the adapter. 150k is honest only with S6, and only once test (b) passes at `-Xmx148m`.

## Over-engineering

Cut `pathHash` (F-15). Everything else is needed or already conditional: packed hex keys only if S2 measures over target,
and lazy `bySize`/`bySha256`. The index file is the most delicate part. With F-01 it is safe, and it avoids decoding every
FileRecord on each open (about 15 s on a phone at 150k). If S2 measures rebuild-by-scan on synth 100k under 5 s on a
desktop, it can be deferred.

## Question for the lead

1. **F-06: how should user decisions survive a store reset once `feedback`, `preferences` or the Phase 9 journal are written?**
   Recommendation: keep the user-authored tables and the operation journal out of the resettable log (A13 already gives the
   Phase 9 journal its own log). Until then, `ResetSafetyTest` blocks any production write to them.
