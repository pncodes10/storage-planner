# REVIEW_S6 — app switch to the disk store (independent review)

Scope: `git diff 5a0dbaa 6c7a925` on the production path (V2Runner, AndroidV2Device, V2Capacity, V2Adapter, V2Analysis) and
what V2Runner calls in v2/ (Stores, DiskStore open/create/close/space checks, SpaceProbe, StoreLowSpace); PHASE_S_NOTES S5/S6,
PHASE_A_NOTES checklist, REVIEW_A, tools/audit_apk.py. Store internals (oracle, kill, corruption) were not re-reviewed.

Note: the reviewer's fixes were committed to the branch by an automatic session commit (a75ba7b, "work in progress, not yet
verified") while this review was in progress; the reviewer did not commit or push. The final edits (R-06, this file, the
checklist) are uncommitted.

## Findings

| id | sev | where | problem | fix |
|---|---|---|---|---|
| R-01 | MAJOR | `app/V2Runner.java` runInner / restore (before: no `close()` anywhere); `v2/store/DiskStore.java:767` | The app never closed the disk store (PHASE_S_NOTES S3 left this "for S6"; S6 did not do it). Every run and every restore leaked the segment writer and one read handle per segment until GC; no index file was ever written, so each later open (next run, restore after process death at app start) rebuilt the store by reading every segment (≈ 250 MB at 100k files) behind "Loading your last results…", and derived damage always reset the store (S3 note). On the corruption retry the first store's writer was still open while `create` truncated its files. Also `DiskStore.close()` released handles only on `StoreException`, not on the unchecked `StoreLowSpace`/`StoreCorruption`, and wrote the index file even after the free space fell below the reserve. | V2Runner: the store is held in a one-slot holder and closed exactly once on every path (closed right after ASSESS/PLAN, before publish, so its indexes can be collected; otherwise in `finally`, failures ignored); `restore` closes in `finally` and now passes the space probe. `DiskStore.close`: handles released in `finally`; no index at close when `lowSpace` or `broken`. JVM test `DiskStoreTest.closeReleases` (handle counting: clean close, double close, reopen from index, low-space close). |
| R-02 | MAJOR | `app/V2Runner.java:92`, `:193` | After a low-storage stop the store kept everything it had written — the very bytes that pushed the phone under 256 MB — while the message told the user to "free some space and run again". Same after the memory guard / disk preflight stops (records of a library that will stop again). On an app whose purpose is freeing space this leaves hundreds of MB hidden in app data. | `V2Runner.resetStore`: `Stores.create(DISK, privateBlobs)` = fresh manifest + truncation of every `ds-*` segment, both index files and an old `store.snap` (no delete/rename), then close. Called after `StoreLowSpace`, guard stop and preflight stop. |
| R-03 | MAJOR | `app/AnalysisRunner.java:56` | Returning to Classic left v2's working data (≈ 2.5–5 KB per file, live + garbage; an old memory-store `store.snap` from an earlier build likewise) on the phone indefinitely; the only way to reclaim it was "Clear storage", which also erases the saved Classic result. | After a Classic run has saved its result and set the restore marker to classic, `V2Runner.resetStoreIfPresent` truncates the v2 store (only if a manifest or a non-empty `store.snap` exists, so users who never ran v2 get no `files/v2`). Cost: the next v2 run re-extracts everything. |
| R-04 | MINOR | `v2adapter/V2Capacity.java` notEnoughStorageMessage | The preflight refusal gave no way forward. | Message now ends "Free some space and run again, or choose Classic (default) on the start screen: it needs no working space." (test prefix unchanged). |
| R-05 | MINOR | `app/v2/AndroidV2Device.java:92`, `V2Runner.java:233,276` | Free space (preflight and in-run reserve) is `StatFs` of the shared root, not of `getFilesDir()`. On current Android both are the /data partition (FUSE/sdcardfs over /data/media), so the figures match; on an adopted-storage or unusual device they could differ. The preflight also counts the existing store's live bytes as "needed" again (conservative). | Not changed; watch item 3a on the phone. |
| R-06 | MINOR | `v2/store/DiskStore.java:227` | `create` took the high-water marks from a CRC-less (lenient) manifest unbounded; a damaged manifest with a huge `hw` would make the reset probe billions of names (hang). | Clamped to 0xFFFF (the segment-number limit). |
| R-07 | MINOR | `app/V2Runner.java:100` | When the corruption retry also fails (e.g. a persistent I/O error, which DiskStore reports as `StoreCorruption`), the user sees "saved data was damaged and has been reset; please run again" without the cause. The retry is bounded (two attempts per tap, no loop) and the store is truncated, so the next tap starts clean. | Not changed; checklist item 13 tells the owner to capture logcat if it repeats. |
| R-08 | MINOR | design | The disk preflight (8 KiB per file + 64 MiB + 256 MiB reserve ⇒ ≈ 1.15 GB at 100k files, ≈ 500 MB at 20k) is a worst case (live + garbage up to max(64 MiB, live) + a compaction copy, PHASE_S 3.10/3.12), not a reservation; measured final size is ≈ 2.7 KB per file (s4-disk: 268.9 MB at 100k). Refusing a run that could exhaust the phone is the right default for a read-only space planner, and the message now explains the alternative. A tighter bound would need compaction to be bounded by free space first (it already skips when free < live + reserve). | Documented in the checklist (3a). |

### Checked, no finding
* **Read-only / audit.** Every store write goes through `AndroidBlobStore` rooted at `getFilesDir()/v2`. Names handed to
  `truncate`/`append`/`replace` are only `ds.manifest`, `ds-{a,b}.idx`, `store.snap` and `segName(set, seq)` with `set` ∈ {a, b}
  (manifest `set` validated at load, line 288; `create` uses literals; manifest segment names re-derived and compared by
  `seqOf`; index name compared with `idxName(set)`). No path component from user data, no `/` or `..` can reach a name, so no
  user file can be truncated. No `File.delete`/`renameTo`/`java.nio.file` writes on the path (AtomicFile's internal rename is
  the framework's, as before). `RandomAccessFile(…, "r")` only.
* **Store files never counted as user storage:** the scan reads the shared root only; `files/v2` is app-private; the app's own
  package is in `essentialPackages`, so its data size is never a recommendation.
* **Capacity guard arithmetic:** 256 MiB, 20 MiB used ⇒ min(170,903 GRAPH, 84,973 END) = 84,973; 512 MiB ⇒ 188,069. Uses
  `Runtime.maxMemory()` after a `gc()` and `usedBefore`; runs in T1 before any file is read, and every 10,000 records during
  DISCOVER (F-17). OOM still caught first in `run()` ⇒ `OUT_OF_MEMORY`. Margin: the 85k claim at 256 MB rests on a JVM run that
  passed 85k at a 148 MiB *total* heap, while the guard grants 85k only at 0.8 × 256 − 36 MiB = 169 MiB for v2 alone (≈ 1.38×
  headroom on top of the 0.8 fraction). ART has smaller object headers and compressed strings but a non-moving large-object
  space for the big arrays/maps of the end phase; the margin is judged adequate but is unverified on a device.
* **Retry bound:** at most one fresh-store retry per tap; errors other than corruption and OOM end in readable messages.
* **Threading:** the store is used by the analysis worker thread only (DISCOVER's progress callback runs on the pipeline thread;
  DiskStore methods are synchronized anyway); restore runs on the loading thread while Analyse is disabled (`ResultFiles.loading`).
  Progress still goes through `AnalysisState.progress` → UI handler.
* **Restore after kill:** marker cleared at run start, set to `v2` only after the final flush and close; restore opens the disk
  store (now from the index file), finds the last COMPLETED session and re-plans without a rescan. A kill mid-run ⇒ no
  restore (setup screen), next run reopens or resets.
* **Stale data:** an older build's `store.snap` has no `ds.manifest` ⇒ `open` creates a fresh store, which truncates `store.snap`.
* **API 26:** `RandomAccessFile`, `StatFs.getAvailableBytes` (18), `StandardCharsets` (19); no `java.nio.file` in the store.

## Verification
`bash tools/v2eval/check_android_compile.sh` OK; `bash tools/v2eval/run_tests.sh` 41 suites, 0 failures (DiskStoreTest 120
checks incl. `closeReleases`), kill tests and store equality OK; `tools/v2eval/store_equal.sh /home/user/sp-dataset`: memory
and disk results identical (7,609,134 bytes). Not verifiable here: anything on ART (heap margin, StatFs vs /data, timings).

## Verdict
**Fit for a phone test** with the R-01–R-03 fixes: read-only holds, failures end in readable messages, and v2 no longer leaves
its working data behind. It is not yet a release: the heap margin on ART, the restore time at 50–85k files, and free-space
behaviour on a nearly full phone must be measured on the device (PHASE_A_NOTES checklist items 3, 3a, 8, 13).
