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
6. The heap holds the whole store: about 2.7 KB per file (A21), plus about 0.2 KB per file for the adapted FileItem and Unit.
7. `elapsedRealtimeNanos` works as the monotonic clock.

## Phone test checklist

1. Install the APK from the CI run and grant All-files access and usage access as usual.
2. On the setup screen, check that **Algorithm** shows "Classic (default)" selected. Run once with Classic and note the Overview totals.
3. Select **v2 (new)** and tap "Analyse my storage". Progress should read "Finding files (v2)", "Reading file details (v2)", "Comparing contents (v2)", "Looking at pictures (v2)", "Finding copies (v2)", "Assessing", "Confirming identical copies" and "Planning". The finish message starts "Done (v2)". Note the time and the file count.
4. In the Overview, groups are named "Safe · …", "No loss · …", "Your call · …", "Review · …" and "Protected · …". Open an item and check that the "why" text starts with "v2 tier:" followed by sentences.
5. "Your call", "Review" and "Protected" items must show 0 B and must never appear in the plan, even after you swipe or accept them. Switch the plan mode both ways and check the same.
6. A copy in a duplicate group is in the plan, and its kept original is not listed as a removable item.
7. Test process death: with the v2 result open, run `adb shell am kill com.storageplanner` (or let the system kill the app) and reopen it. The v2 groups must come back without a rescan. They must not come back as Classic groups.
8. Switch back to **Classic**, analyse, kill the app and reopen it. You should see Classic groups.
9. Cancel a v2 run halfway, then kill the app and reopen it. Nothing from that run should be shown (the setup screen only).
10. Check memory and time on a phone with more than 20,000 files: watch for "Analysis failed (v2): java.lang.OutOfMemoryError".
11. To switch back, select "Classic (default)" and analyse again. The Classic saved result is never overwritten by a v2 run.

## Known limitations

* **Memory.** The store stays in memory at about 2.7 KB per file, plus the adapted copy. With a typical 256 MB app heap (512 MB with largeHeap on some devices), the risk starts at about **40,000–50,000 files** and an OOM is likely above about 80,000. The record shrink (A21: don't serialise unknown fields) was **not done** in this phase, and SQLite remains the fix.
* **Time.** T3 decodes every image at reduced size, and hash-on-demand reads up to 2 GB. A library of 30,000 photos may take tens of minutes on a mid-range phone, which is not measured. The work runs in the existing foreground service.
* **Restore re-assesses.** It reads the store, then runs Assessor and Plans again, re-hashing up to 2 GB. Restore can therefore take a while and shows the "loading" state meanwhile.
* There is no YOUR_CALL opt-in control, so YOUR_CALL is display-only. Bundles (split APKs or sidecars) are shown on their primary file with the bundle's bytes. File units included in an app's cache are not listed separately, because their bytes count under the cache.
* The v2 result has no picture categories (Session.pictures is empty), so the Pictures screen is empty in v2 mode. Areas are the v2 areas, so the Overview breakdown may put more in "Other".
* The staleness re-check before any approval request is not needed: the app never executes a plan (read-only). It becomes mandatory when Phase 9 executes plans.
* Classic results and v2 results are kept apart. A v2 run never touches `last_results.bin`. A classic run sets `result_algorithm=classic`.
