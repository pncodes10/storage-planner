# Review A: Phase A (Classic / v2 switch in the app)

Reviewer: an independent agent, not the author. Scope: commit `2b43513` against `b396066` (`app/`), `docs/v2/PHASE_A_NOTES.md`,
the v2 device interfaces, `tools/audit_apk.py` and the manifest. Nothing here could run on a device. The checks were reading the
code, `tools/v2eval/check_android_compile.sh`, `tools/v2eval/run_tests.sh` (33 suites), the classic `PlannerTest`, `ClassifierTest`
and `TextRulesTest`, and a JVM heap measurement on `/home/user/sp-dataset` (2,414 files).

## Verdict

**Safe to put on the phone for a test after the fixes below. v2 is not yet usable on a large library.** v2 is read-only, and the
Classic path behaves as before. The blocker was memory. With tens of thousands of files, v2 would have run into an
`OutOfMemoryError`. A hard guard now stops v2 before extraction, with a clear message that points to Classic. On a typical
`largeHeap` phone (512 MB) that limit is about **41,000 files**, and about **15,000** on a 256 MB heap. A 128 GB phone holding tens
of thousands of photos will probably reach that limit. The real fix is the SQLite store (A21), not more trimming.

Severity counts: **1 BLOCKER, 2 MAJOR, 12 MINOR.** The blocker, both majors and 5 minors are fixed in the working tree (not committed).

## Findings

| id | sev | file:line | problem | fix |
|---|---|---|---|---|
| B-1 | BLOCKER | `app/V2Runner.java` (run, before fix) | Nothing limits memory. The whole store is in the heap, and the run does not check the file count. On the owner's phone the expected result was `Analysis failed (v2): java.lang.OutOfMemoryError` after many minutes of work. An OOM thrown on another thread (UI, publish) could crash the app. | **Fixed.** `V2Capacity` (new, Android-free) estimates capacity as `(0.8 × maxMemory − heap used before the run − 64 MB reserve) / 8 KB per file`. `V2Runner.CapacityGuard` wraps T1 (same stage id). It counts the file records right after DISCOVER and stops the run *before any file is read*, with the message "v2 cannot handle this many files yet: it found N files … Choose Classic (default) …". The scan is not truncated: the run ends without a result, and the session is stored as CANCELLED. `OutOfMemoryError` is also caught at the runner boundary (`V2Runner.java:72`) and gives the same advice. The work runs in `runInner`, so its frame and records are unreachable when the message is built. The JVM test is `V2AdapterTest` › "memory guard (V2Capacity)" (14 checks). |
| M-1 | MAJOR | `app/V2Runner.java:115` | The `stages` list stayed referenced through ASSESS, hash-on-demand, PLAN and the adapter. `GraphStage.last` (`Perceptual`) holds the picture-signature cache (about 50 MB of `int[]` at its 256-entry cap) and copies of every node. Measured: **25.0 KB retained per file** after the pipeline while the stages were referenced, against 3.1 KB without them. The ART interpreter keeps dead locals alive, so this would have happened on the phone. | **Fixed.** `stages.clear()` runs as soon as the pipeline returns. The `PipelineResult`, store and device references are also dropped before publishing. |
| M-2 | MAJOR | `app/V2Runner.java:65` | `SNAPSHOT_EVERY_MS = 30000` rewrote the whole store snapshot every 30 s. That is about 2.7 KB per file, so roughly 100 MB every 30 s for 40,000 files, which costs time and flash writes. A v2 run is never resumed (`cfg.resume` is false), so those mid-run snapshots are never used. | **Fixed.** Set to `-1`, the store's adaptive interval (at least 60 s, at most about 5 % of the time spent writing). The final flush is unchanged. |
| m-1 | MINOR | `app/V2Runner.java:205` | The verdicts (about 2.5 KB per file: `Assessment`, `EnumMap`, boxed `Double`s) stayed alive through hash-on-demand, PLAN and the adapter. | **Fixed.** `vs = null` right after `ProvisionalRecommendations.of`. Those phases drop from 6.4 to 3.8 KB per file. The peak inside `Assessor.run` is unchanged. |
| m-2 | MINOR | `app/V2Runner.java:134` | `publish()` returned false on a 60 s timeout or an exception, but the run still reported "Done (v2)" with 0 bytes. | **Fixed.** The run now reports that the result could not be shown. |
| m-3 | MINOR | `app/OverviewScreen.java:105` | In v2 mode, "Your pictures" said "Pictures were not sorted … Turn on 'Sort pictures into categories' … and tap Analyse". That advice does not help in v2. | **Fixed.** In v2 mode the section explains that v2 does not sort pictures into categories yet, and to use Classic for that. |
| m-4 | MINOR | `app/SetupScreen.java:97` | The v2 choice did not say that it is new or experimental, or that it only recommends. | **Fixed.** The label is now "v2 (new, experimental): sorts suggestions into five levels from Safe to Protected and explains each one. It only recommends, like Classic." The 48 dp minimum, the text label and the RadioGroup description were already correct. |
| m-5 | MINOR | `v2/discovery/PosixStorageAccess.java:73` | `Files.getFileStore` on Android FUSE can throw a RuntimeException (no mount entry). That would fail DISCOVER. | **Fixed.** The RuntimeException is caught too, and total/free become unknown (-1). |
| m-6 | MINOR | `app/V2Runner.java:207` (restore) | `restore()` runs ASSESS and hash-on-demand again, re-reading up to 2 GB on every cold start while a v2 result is shown. The "loading" state can last a long time. The hashes are not persisted. | Open. Persist the confirmed hashes, or skip hash-on-demand on restore and show those items as pending. |
| m-7 | MINOR | `app/AnalysisService.java:33` | The wake lock times out after 60 min. A FULL v2 run decodes every image (T3, and GRAPH again at 1024 px), so a large library with the screen off may slow down after an hour. | Open. Measure on the phone first. |
| m-8 | MINOR | `v2/discovery/Walker.java:92` | DISCOVER progress counts directories, not files ("Finding files (v2) 812"). | Open. The UI does not mislead much, because the label is not "files found". |
| m-9 | MINOR | `app/Session.java:235` | Unit ids and the "review" preferences are shared by both algorithms. An "accept" on a display-only v2 unit (REVIEW, PROTECTED, YOUR_CALL) is stored and shown as approved, although `V2Plans` never plans it. A Keep made in v2 also applies in Classic. That is conservative, and Classic ignores v2 technique names. | Open. It is safe because nothing is planned or executed. Hide Accept on `notPlannable` units in Phase B. |
| m-10 | MINOR | `v2/store/Codecs.java` | The snapshot writes every unknown field as `null` (2.7 KB per file on disk). This does **not** affect the heap: the snapshot is streamed, and restore parses it line by line. | Not done. The heap was the binding limit, and SQLite (A21) replaces this. |
| m-11 | MINOR | `app/V2Runner.java:113` | The guard runs after DISCOVER. Discovery itself holds about 1–1.5 KB per file, so a tree well beyond the limit (more than about 150,000 files at 256 MB) could still run out of memory during discovery. | Covered by the OOM catch (same message). |
| m-12 | MINOR | `v2adapter/V2Adapter.java:205` | Display-only units carry 0-byte R4 options with actions `v2_review` and `v2_protected`. Classic screens have never seen 0-byte options in groups. It is unverified how the group lists render "0 B" and an unknown action. | Phone checklist item 5. |

### Checked and fine

* **Read-only (priority 1).** v2 opens user files only through `FileChannel.open(READ)`, `FileInputStream` (hashing),
  `BitmapFactory.decodeFile` and `ExifInterface(String)`. It writes only through `AndroidBlobStore` into `getFilesDir()/v2` (constant
  blob names, `AtomicFile`, `FileOutputStream`), plus SharedPreferences. It has no delete, rename, move or truncate on any user
  path. `AtomicFile`'s internal rename and delete happen inside the framework and stay in the private dir. No executor exists
  (Phase 9 is not wired), and no v2 path writes under Download/StoragePlanner except the existing report export.
* **Classic regression (priority 2).** `algorithm()` defaults to classic. A missing `result_algorithm` means classic, so an existing
  install restores exactly as before. A classic run differs only by `setResultAlgorithm(classic)` after `ResultFiles.save`.
  `Session.set` resets `notPlannable` to null, so `replan()` and `saveSessionAsync` take the old paths. A v2 run never touches
  `last_results.bin`. Statics that v2 leaves behind are `notPlannable` (reset by classic `set`), `Session.mode` (restored from the
  classic save) and the shared "review" preferences (m-9). Classic `PlannerTest` 2000/2000, `ClassifierTest` 25/25 and
  `TextRulesTest` 21/21 pass, and `core/` is unchanged.
* **Process death.** The `result_algorithm` marker reads `""` while a v2 run that replaces a v2 result is in progress, `v2` after
  it completes, and stays `classic` while a v2 run follows a classic result. Restore requires the last scan session to be
  COMPLETED, and a guard stop, cancel or OOM leaves it CANCELLED, FAILED or RUNNING. No combination shows a half-written v2 store or
  shows v2 groups as classic.
* **Threads.** The pipeline runs on the service worker. `Session` is changed only on the UI thread (`publish` posts and waits).
  The restore runs on the `load-results` thread, and Analyse is disabled while it loads. Progress comes from one thread.
  Cancel sets `AnalysisState.cancelled`, which the pipeline, hash-on-demand and `sha256Of` all check. Every `Throwable` becomes
  a message through `AnalysisState.finished`.
* **API 26.** Every Android API above 26 is guarded: thermal status (29), `getLongVersionCode` (28), HEIC (28) and AVIF (31).
  `java.nio.file` is available from 26. Everything else is API ≤ 23.
* **Decoding (4a).** `inSampleSize` is the largest power of two that keeps the long edge ≥ the requested 1024 px. A 12 MP photo is
  decoded at 2000×1500 and then scaled to 1024 px. Both bitmaps are recycled (`finally`, and `sc != bm`). Pixels larger than
  `maxPixels` (100 MP) are refused from the bounds alone. Bitmap pixels live in the native heap on API 26+. Per decode the Java heap
  holds two `int[]` of about 3 MB each (ARGB and luma), which falls under the fixed reserve.
* **Adapter (priority 5).** `V2Adapter` keeps the tiers apart by prefix ("Safe · ", "No loss · ", "Your call · ", "Review · ",
  "Protected · ") and by the "v2 tier:" sentence. Only SAFE and NO_LOSS become plannable. YOUR_CALL without opt-in, REVIEW and
  PROTECTED get 0 bytes and go into `notPlannable`. `V2Plans` stops at R1 and ignores their accepts and any `chosen:`. Units that
  touch a keeper are skipped, and hash-pending deletes are dropped by `UnitBuilder2`. App and cache options with conflicts are
  dropped, so the classic planner counts no byte twice. These points are covered by the `V2AdapterTest` properties. The text says
  "would free … if you chose it" and "never planned", and nothing implies that v2 deletes.
* **Audit (priority 7).** The new code calls none of the patterns in `tools/audit_apk.py` (network, `File.delete`/`renameTo`,
  `Files.delete`/`move`, `Runtime.exec`, class loading or reflection, MediaStore trash or delete). `Runtime.gc()`/`maxMemory()` are not
  matched. `FileOutputStream` is used only for the private blobs and is listed in the informational "file writes" line. The manifest
  is unchanged: same permissions, one dataSync service and `largeHeap="true"`. `V2Capacity` is compiled from
  `app/src/main/java`, so the "dex contains exactly the compiled classes" check holds.

## Memory measurement (dataset, JVM 21, SerialGC, after a full GC)

`HeapProbe` (scratchpad) runs exactly the V2Runner sequence: DISCOVER, T1–T3, GRAPH, ASSESS, ProvisionalRecommendations,
hash-on-demand, Plans.make, V2Adapter. 2,414 files, 2,414 feature records, 295 hash records.

| point | before | after |
|---|---|---|
| after the pipeline, stage objects still referenced (as V2Runner did) | 25.0 KB/file | — (released) |
| after the pipeline (store only) | 3.2 KB/file | 3.1 KB/file |
| inside ASSESS (store + all verdicts): the peak | 6.4 KB/file (+ 50 MB graph cache still held) | 6.4 KB/file |
| hash-on-demand + PLAN | 6.4 KB/file (+ 50 MB) | 3.8 KB/file |
| result on screen (store dropped, adapted kept) | 0.5 KB/file | 0.5 KB/file |
| restore (snapshot reopened) | 3.8 KB/file | 3.8 KB/file |
| snapshot on disk | 2.7 KB/file | 2.7 KB/file |

Guard constants: 8 KB per file (6.4 KB peak + 25 % for ART and longer real paths), a 64 MB fixed reserve (graph cache and decode
buffers) and 80 % of `maxMemory`. **Safe file count**, with about 20 MB already in use: **15,462 at a 256 MB heap** and **41,676 at a
512 MB heap**. With an empty heap the counts are 18,022 and 44,236. The guard counts every file record (photos, documents, app files,
everything under shared storage), not only photos.
