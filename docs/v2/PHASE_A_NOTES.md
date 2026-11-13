# Phase A: the Classic / v2 switch in the app

Status: built and compiled against the Android framework classes (`tools/v2eval/check_android_compile.sh`). JVM tests pass.
**Not run on a device yet.** The APK is built only by CI. Nothing here adds deletion, renaming, moving or compression of user
files, and v2's execution engine (Phase 9) is not wired in.

## What was built

| File | What |
|---|---|
| `app/.../v2adapter/V2Adapter.java` | Android-free adapter. It turns v2 plan units and recommendations into classic `Unit`s and `Option`s and builds a `Scanner.Result` of present files and apps. `ub.det.duplicateOf` is filled from the exact groups. It lives outside `com.storageplanner.v2` because `ForbiddenApiTest` forbids classic-core imports there. |
| `app/.../v2adapter/V2Plans.java` | Session planner used while a v2 result is shown. It uses only SAFE and NO_LOSS (risk at most R1, A29), never plans display-only units, ignores "accepts" of options above R1 and ignores `chosen:` user deletions (`Unit.userDelete`). |
| `app/.../app/v2/AndroidV2Device.java` | The `V2Device` for Android. Storage: `PosixStorageAccess` on the classic shared-storage root, mode B, with `Android/data` and `Android/obb` restricted and `fileKeyStable = FALSE`. Media: `BitmapFactory` with `inSampleSize` (A22) plus EXIF orientation. HEIC/HEIF decode from API 28 and AVIF from API 31. Apps: converted from `AndroidPlatform.apps()`. Blobs: `getFilesDir()/v2`, replaced atomically through `AtomicFile`. Resources: heap, StatFs, battery, thermal (API 29+) and low-RAM profile. |
| `app/.../v2adapter/V2Capacity.java` | (REVIEW_A) Memory guard arithmetic and its messages. Tested in `V2AdapterTest`. |
| `app/.../app/V2Runner.java` | Runs DISCOVER, T1, T2, T3, GRAPH, then ASSESS, hash-on-demand (2 GB budget), app subjects and `Plans.make(MAX_SAFE, allowYourCall=false)`, then adapts the result and puts it into `Session`. Progress goes to `AnalysisState`. It cancels cooperatively through `AnalysisState.cancelled`. `restore()` runs after process death. |
| `AnalysisRunner` | Adds `algorithm()` / `setAlgorithm()` (prefs "analysis", key `algorithm`, default `classic`), the branch at the top of `run`, and the `result_algorithm` marker (classic writes it after `ResultFiles.save`). |
| `SetupScreen` | Adds an "Algorithm" radio group (Classic (default) / v2 (new)) under the sort checkbox. Each choice is at least 48 dp and has a text label, so meaning never depends on colour alone. |
| `Session` | `setV2(...)` and `notPlannable`. While a v2 result is shown, `replan()` uses `V2Plans`. `set(...)` (classic) resets `notPlannable` to null, so classic planning is exactly as before. |
| `MainActivity` | Restore path: a `result_algorithm` of `v2` calls `V2Runner.restore` and `classic` (or a missing marker, which covers old installs) uses the old `ResultFiles` path. An empty marker means a v2 run was interrupted, and nothing is restored. |
| `ResultFiles.saveSessionAsync` | For a v2 result it only remembers the plan mode (`v2_mode`). It never writes a v2 result into `last_results.bin`. |
| `jvmtest/.../V2AdapterTest` | Tests the tier table and that REVIEW, PROTECTED, keepers, hash-pending items and non-opted YOUR_CALL items are never selectable. It also checks conflict-free options (F-13), that adapted bytes equal v2 `achievableBytes`, and a 200-target property test. |

`core/` is unchanged.

### Tier mapping

| v2 | classic `action` | `risk` | technique prefix | plannable |
|---|---|---|---|---|
| SAFE | delete / clear_cache | R0 | `Safe · ` | yes |
| NO_LOSS | delete / archive_app / v2_compress / v2_archive | R1 | `No loss · ` | yes |
| YOUR_CALL | delete / uninstall_app (R4), lossy v2_compress (R3) | R4/R3 | `Your call · ` | no. It is display-only (0 bytes, with "would free X" in the why text) because the app has no opt-in control yet (A29). |
| REVIEW | v2_review | R4 | `Review · ` | never |
| PROTECTED | v2_protected | R4 | `Protected · ` | never |

`Option.why` starts with "v2 tier: <name> (...)." followed by the rendered reason sentences.

## Unverified assumptions about Android

1. `PosixStorageAccess` (java.nio.file) works under `/storage/emulated/0` with All-files access on API 30+, and with READ_EXTERNAL_STORAGE on API 26–29. Listing `Android/data` on API 30+ returns permission denied, which is reported as restricted, not as an error flood.
2. On API 28+, `BitmapFactory` decodes HEIC/HEIF and gives correct bounds for them. On API 31+ it decodes AVIF. Some devices may still return null, which is reported as CORRUPT. That is a status, not evidence about the image.
3. Applying `ExifInterface` orientation to `BitmapFactory` output matches the JVM decoder's upright convention, so hashes and perceptual hashes are comparable.
4. `AtomicFile.finishWrite` gives the durability the store expects (fsync, then rename inside the framework).
5. The `QUERY_ALL_PACKAGES` grant means the installed list is complete (`Availability.FULL`). Without usage access, `lastUsed` and `essential` are reported as unknown.
6. Since PHASE_S slice S6 the store is on disk (`getFilesDir()/v2`, segment files, an index file and a manifest) and the heap holds only its indexes and the stages' working sets. The JVM figures (≈ 514 B per file at the GRAPH peak plus a fixed ≈ 80 MB, ≈ 1.4 KB per file at the end of the run including the classic result; PHASE_S_NOTES "S6") carry over to ART within the 1.25 safety factor of `V2Capacity` (REVIEW_S F-08). Random record reads cost ≈ 10 µs on the JVM; assume ≈ 100 µs on a phone.
7. `elapsedRealtimeNanos` works as the monotonic clock.

## Phone test checklist

Updated after REVIEW_A, and again for PHASE_S slice S6 (disk store). Items marked **new** test the review fixes, **S6** the
disk store.

1. Install the APK from the CI run and grant All-files access and usage access as usual.
2. On the setup screen, check that **Algorithm** shows "Classic (default)" selected and that the v2 choice reads "v2 (new, experimental): …". With TalkBack, check that both choices are read with their text. Run once with Classic and note the Overview totals.
3. **S6, run this first:** select **v2 (new, experimental)** and tap "Analyse my storage". The limit is now about **100,000 files at a 256 MB heap and 221,000 at 512 MB** (`largeHeap`; check `adb shell getprop dalvik.vm.heapsize`), minus what the screen already uses. Most phones are now **under** the limit: note the file count and go on with item 4. To test the stop, use a phone (or a test folder tree) above the limit: the run must stop during or soon after "Finding files (v2)" (the count is checked every 10,000 records while discovering, REVIEW_S F-17), **before** "Reading file details (v2)". It must show "v2 cannot handle this many files yet: it found N files, and this phone's memory allows about M for v2 …", and no crash, no endless spinner and no partial result. Write down N and M. Tap Analyse with Classic afterwards: it must work as before.
3a. **S6 (disk):** with less free storage than v2 needs (about 8 KB per file + 320 MB; e.g. fill the phone to under 400 MB free), v2 must stop after "Finding files (v2)" with "Not enough free storage for v2's working data (about N MB needed). …". If free storage drops below 256 MB **while** v2 runs, it must stop with "v2 stopped because the phone's free storage fell below 256 MB …" and show no partial result; after freeing space the next run must work. `adb shell du -sh /data/data/com.storageplanner/files/v2` (debug build: `run-as`) shows the store; write down its size and the file count (expected ≤ about 5 KB per file, at most twice the live data plus 64 MB).
4. If the phone is under the limit (or on a smaller test phone): progress should read "Finding files (v2)", "Reading file details (v2)", "Comparing contents (v2)", "Looking at pictures (v2)", "Finding copies (v2)", "Assessing", "Confirming identical copies" and "Planning". The finish message starts "Done (v2)". Note the time and the file count. Watch for "v2 ran out of memory …". If it appears, report N and the heap size, because the S6 per-file figures (643 B at GRAPH, 1,770 B at the end, both already × 1.25) are then too low for ART. **S6:** expect the run to take somewhat longer than before S6 (records are read from flash); write down the time next to the file count.
5. In the Overview, groups are named "Safe · …", "No loss · …", "Your call · …", "Review · …" and "Protected · …". Open an item and check that the "why" text starts with "v2 tier:" followed by sentences. "Your call", "Review" and "Protected" items must show 0 B and must never appear in the plan, even after you swipe or accept them. Switch the plan mode both ways and check the same. Check that the 0 B groups render sensibly (no "NaN", no empty rows).
6. A copy in a duplicate group is in the plan, and its kept original is not listed as a removable item.
7. **new:** the "Your pictures" section of the Overview must say that v2 does not sort pictures into categories yet. It must not be blank, and it must not say to turn on sorting.
8. Test process death: with the v2 result open, run `adb shell am kill com.storageplanner` (or let the system kill the app) and reopen it. The v2 groups must come back without a rescan, and they must not come back as Classic groups. Note how long the "loading" state lasts, because restore re-hashes up to 2 GB (REVIEW_A m-6). **S6:** restore opens the disk store (its index file plus a replay of the log written after it, or a full rebuild by reading the segments when no index file was written yet — small libraries); on a large library note this extra time separately if you can.
9. Switch back to **Classic**, analyse, kill the app and reopen it. You should see Classic groups.
10. Cancel a v2 run halfway, then kill the app and reopen it. Nothing from that run should be shown (the setup screen only). Repeat after a guard stop (item 3): you should get the setup screen, or the earlier Classic result if Classic ran last.
11. Leave a v2 run going with the screen off. It must finish (or report an error) and post the "plan is ready" or "Analysis failed" notification. Note whether it takes more than 60 minutes, since the wake lock times out there (m-7).
12. To switch back, select "Classic (default)" and analyse again. The Classic saved result is never overwritten by a v2 run.

## Known limitations

* **Memory.** Since PHASE_S slice S6 the store is on disk and no stage holds a whole-library copy (PHASE_S slice S5). **A hard guard** (`V2Capacity`, `V2Runner.CapacityGuard`) stops v2 after discovery (and, F-17, during it every 10,000 records) and before any file is read when the record count is above `min((0.8 × maxMemory − used − 16 MB − 64 MB) / 643 B, (0.8 × maxMemory − used − 16 MB) / 1,770 B)`: about 100,000 files at a 256 MB heap and 221,000 at 512 MB. The binding phase is the end of the run, where the classic result model (≈ 0.8 KB per file incl. its lookup map) is the largest single cost. The user is told to use Classic. An `OutOfMemoryError` is also caught and reported the same way. MemoryStore remains selectable (`V2Runner.STORE_KIND`) with its old guard (about 15,000 / 41,000).
* **Disk.** The store needs about 2.5 KB per file live and at most twice that plus 64 MB before compaction; the preflight asks for 8 KB per file + 320 MB free, and the store stops writing at 256 MB free (`D_LOW_STORAGE`).
* **Time.** T3 decodes every image at reduced size, and hash-on-demand reads up to 2 GB. A library of 30,000 photos may take tens of minutes on a mid-range phone, which is not measured. The work runs in the existing foreground service.
* **Restore re-assesses.** It reads the store, then runs Assessor and Plans again, re-hashing up to 2 GB. Restore can therefore take a while and shows the "loading" state meanwhile.
* There is no YOUR_CALL opt-in control, so YOUR_CALL is display-only. Bundles (split APKs or sidecars) are shown on their primary file with the bundle's bytes. File units included in an app's cache are not listed separately, because their bytes count under the cache.
* The v2 result has no picture categories (Session.pictures is empty), so the Pictures screen is empty in v2 mode. Areas are the v2 areas, so the Overview breakdown may put more in "Other".
* The staleness re-check before any approval request is not needed: the app never executes a plan (read-only). It becomes mandatory when Phase 9 executes plans.
* Classic results and v2 results are kept apart. A v2 run never touches `last_results.bin`. A classic run sets `result_algorithm=classic`.
