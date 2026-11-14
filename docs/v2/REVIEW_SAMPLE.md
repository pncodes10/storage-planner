# Review: "Quick v2 test" button (commit 5265bc6)

Independent review of `git diff 130a013 5265bc6` (A38). One pass over the diff plus targeted reads of
DiscoveryEngine/Walker, DiskStore `remove`/checkpoint, V2Runner, MainActivity restore, OverviewScreen and Report.
Nothing here has run on a device.

## Verdict

**Fit for a phone test** after the fixes below. No BLOCKER found: the app stays read-only, the sample store is
`getFilesDir()/v2sample` on every path (run, corruption retry, guard stop, low-space stop, restore), and nothing a sample run
does can open, truncate or prune `files/v2`. Classic is the same path as before plus one existence check of
`files/v2sample`; a full v2 run is the same plus emptying `files/v2sample` after it has published. The MAJOR findings were all
about the result overclaiming (the Overview told the user the target was "not reachable" and showed a whole-phone "System and
other" bar computed from a 1,100-file sample; the saved/shared report and the notification did not say "sample"). They are
fixed.

## What was checked and holds

* **Read-only / audit.** New code has no `File.delete`, `renameTo`, reflection, `java.net` or manifest change. Truncation
  targets are DiskStore's own names inside `new File(getFilesDir(), "v2sample")` (constant `AndroidV2Device.SAMPLE_DIR`);
  `resetSampleStoreIfPresent` only tests `exists()`/`length()` and creates nothing.
* **Store selection.** `sample` flows Intent extra -> `AnalysisService.work` -> `AnalysisRunner.run` -> `V2Runner.run` ->
  `runInner` -> `device(app, pf, sample)` -> `AndroidBlobStore(sampleStoreDir)`; all three `resetStore` calls pass `sample`; a
  sample run always `Stores.create`s (never resumes), so a `D_STORE_DAMAGED` retry also lands in `v2sample`.
  `MainActivity` restores `"v2sample"` only with `restore(app, true)`. `SampleStage.prune` is only called from the sample
  branch of the discovery guard and from the SAMPLE stage, which is only inserted when `sample` is true.
* **Marker.** Missing = Classic; `"v2"` / `"v2sample"` restore their own store; empty = interrupted -> nothing. A sample run
  empties the marker only if it was `"v2sample"` (the store it is about to truncate); a full run only if it was `"v2"`. So an
  interrupted sample run leaves an earlier full / Classic result restorable, and vice versa.
* **Pruning mid-discovery.** `control.progress("DISCOVER")` is called only by `Walker.walk` between directories, after the
  directory is fully written and before `commitNow`, never inside an iteration; prune collects keys first, then removes inside
  the open transaction. In a fresh walk every directory is processed once, so a pruned record is never looked up again; the
  heap keeps the global top N of everything seen (total order: mtime, path, volume, id). Directory records and the checkpoint
  are untouched; the sample store is never resumed, so a kill mid-discovery just restarts from empty. New tests cover all of
  this (below).
* **Guards.** Discovery guard sees the pruned count; `CapacityGuard` (heap + disk preflight) runs at T1, after SAMPLE, so it
  measures ~1,100 records. Full runs keep the old `(n/10000+1)*10000` cadence and limits.
* **Concurrency.** Both buttons go through `AnalysisState.begin()` and are disabled while running or loading.
* **App recommendations.** `AppSubjects.recs` skipped, empty app list to the adapter. Exact-duplicate keepers come from
  the sample store, which holds only sample records, so a keeper outside the sample is impossible.
* **UI.** Button via `Ui.button` (48 dp min height), content description, text not colour; helper line is a plain
  TextView in reading order after the button. Banner is text in an outline box, on Overview, List, Detail and Deck.

## Findings

| id | sev | where | problem | fix |
|---|---|---|---|---|
| S-01 | MAJOR | `app/OverviewScreen.java` summaryCard (now :222, `sampleSummary`) | In sample mode the card said "Target not reachable: best is X GB" / "After this plan: X GB" and drew the meter with the whole phone's used space minus the sample's savings: a 1,100-file sample read as the phone's verdict. | **Fixed.** Sample mode shows "The sample plan frees X", exact/estimated split, "Whether your target is reached is not judged on a sample", the phone's used/capacity labelled "for reference", and the unique-deletion warning. No meter, no target verdict. |
| S-02 | MAJOR | `app/OverviewScreen.java` breakdown (:336) | "What's using space" included "System and other" = phone used minus the sample's files, i.e. ~the whole phone, shown as a category. | **Fixed.** Heading "What the sample uses" with a sample-only explanation; the SYSTEM row is hidden in sample mode. |
| S-03 | MAJOR | `app/MainActivity.java` saveReport / shareSummary (:424, :439) | The saved `.txt` and the shared text are `core.Report.text` (phone used, target, "Target NOT reachable") with no sample notice; they leave the app and lose the banner. | **Fixed.** `samplePrefix()` puts the sample note and a one-line caveat first. (`.csv` / `.json` unchanged: see S-08.) |
| S-04 | MINOR | `app/AnalysisService.java` :104 | Result notification said "Your storage plan is ready: frees X" for a sample. | **Fixed.** `Outcome.sample`; title "Quick v2 test ready: the sample plan frees X". |
| S-05 | MINOR | `v2/sample/SampleStage.java` :37 | `insertAfterDiscovery` inserted at index 0 if no DISCOVER stage existed, so the final prune would run on an empty store and every discovered file would go on to extraction. Not reachable today. | **Fixed.** Throws instead. |
| S-06 | MINOR | `app/V2Runner.java` :200 | Pruning is triggered from the per-directory progress call, so the bound is "sample + one directory", not "sample + 10,000": a 40,000-file `DCIM/Camera` is fully in the store (DiskStore: index entries in heap, records on disk) before the first prune. Fine for the disk store; noted. | Doc only. |
| S-07 | MINOR | DiskStore during a sample run | Every discovered record is appended, then removed: garbage grows until a checkpoint compaction (garbage >= max(64 MB, live)). On a phone close to the 256 MB reserve a sample run can still stop with the low-storage message. | Doc only (PHASE_A_NOTES limits). |
| S-08 | MINOR | `MainActivity.saveReport` | `.csv` and `.json` reports carry no sample marker (core formats, not changed). | Open. The `.txt` next to them says it. |
| S-09 | MINOR | `v2/risk/CandidateRules.java` :46, thumbnails | Images in cache or thumbnail folders are images, so they can be in the sample and get file-level "App cache files" / "Thumbnails" groups. The notes claimed "no app or cache groups". App-level (CLEAR_APP_CACHE, idle apps) are absent as required. | Notes corrected. |
| S-10 | MINOR | selection invariant | Top-N under pruning assumes a kept PRESENT record never later turns non-PRESENT in the same walk. True for a fresh walk (subtree/vanished marks only touch directories not yet listed); if a later change breaks it, the sample would be short, never wrong-store. | Doc only. |
| S-11 | MINOR | `app/V2Runner.java` :350 | A sample run that completes over a full v2 result leaves `files/v2` on disk (not emptied); only a later Classic run frees it. Disk use only. | Open. |
| S-12 | MINOR | `jvmtest/.../SampleTest.java` :237 | "full run after a sample run = full alone" compared two independent full runs; the sample never ran before the second full run on the same store. | **Fixed**: relabelled, and the real check added (`sampleThenFull`). |
| S-13 | MINOR | tests | No adversarial arrival order, no kill-and-rerun of a sample run, no zero-media case. | **Fixed**: `pruneAdversarial` (memory + disk, 12 orders incl. oldest-first, ties, non-PRESENT, prune every 1-200 records, plus the "late newer file wins" case), `killThenRerun`, `sampleThenFull` (byte-identical full stores), `zeroMedia`. SampleTest 135 checks. |

## Verification

`LC_ALL=C.UTF-8`: `run_tests.sh` 43 suites, 0 failures (SampleTest 135 pass); `check_android_compile.sh` OK (`--release 8`
and 17); store equality in `run_tests.sh` identical, and `store_equal.sh` on the Drive dataset with `--sample 40 5` identical.

## Not verifiable here (watch on the phone)

`getFilesDir()/v2sample` creation and DiskStore `remove` at 50,000+ records on flash; discovery time on the real tree;
banner and sample summary at large font sizes; TalkBack order on the setup screen; restore after process death.
