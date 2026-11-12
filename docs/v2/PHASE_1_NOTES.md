# Phase 1 — implementation notes

Records the places where the implementer departed from `PHASE_1.md` / `ARCHITECTURE.md`, or had to choose because
the design was ambiguous. Each entry says what the design said, what was done, and why.

## Deviations and choices

| # | Design said | Done | Why |
|---|---|---|---|
| N1 | Test runner `tools/v2test.sh` | `tools/v2eval/run_tests.sh` (the lead's name); CI step "v2 tests" runs it. No `tools/v2test.sh`. | The lead asked for one command at that path. |
| N2 | `CapabilityRegistryTest`: `authoritativeFor` is **true** for `.jpg` under an image-only grant | Asserts **false** for both `.jpg` and `.pdf` under any Mode A grant | §1.1 and REVIEW_1 F-M2 are binding: a Mode A listing never proves absence. The test case in §10 is the pre-review wording. |
| N3 | `FileUpsert` guard: on an `ino` volume with no inode, mark a PRESENT record UNVERIFIABLE; otherwise create the record with token `"ino:"+inode` | The guard also covers records that are already UNVERIFIABLE/VOLUME_ABSENT (they stay UNVERIFIABLE, same id). When no record exists, no record is created (counter `statFailed`). | Otherwise a second failing scan would mark the record REPLACED, and `"ino:-1"` would create a fake id that is then REPLACED once stat works. Both break F-M1. |
| N4 | `DiscoveryEngine()`: installed packages come from `ctx.device.apps()`, "else empty set" | No InstalledApps in Phase 1, so the set is `null` (not known completely). Added the constructor `DiscoveryEngine(WalkLimits, Set<String> installedOrNull)`, which tests use to give a FULL set. | An empty set would label every `Android/media/<pkg>` file `residue`, which F-M3 forbids. |
| N5 | `PipelineConfig` contains `WalkLimits`, `HashPolicy`, `PlanMode`, `targetBytes` | `WalkLimits` is passed to `DiscoveryEngine` instead. The other fields are left out until the phase that needs them. Added `storeReset` (session event `D_STORE_RESET`). | `WalkLimits` is in `v2.discovery`, which depends on `v2.stage`, so putting it in the config would create the package cycle F-m13 removed. |
| N6 | Walker: `commitIfDue()` after every directory | The walker commits after **every** directory, and every `commitEvery` entries / `commitEveryMs` inside a directory. | ARCHITECTURE 5.2 says "every directory". A commit is in-memory; the snapshot cadence decides durable I/O. |
| N7 | Counters "derived at the end" | `added/changed/removed/replaced` and `dirs.<STATUS>` are derived from records at stage end. The anomaly counters (`invalidNames`, `vanishedDuringScan`, …) and the walker's events are buffered per directory and merged into the session only when the directory is finished. | Anomalies cannot be derived from records. Buffering keeps them exact when a directory is re-processed after a kill. |
| N8 | Frontier directories were not re-checked against grants | `processDirectory` first checks `caps.canList(dir)`, giving `NOT_GRANTED` + `D_NOT_GRANTED`. This also handles a root that is not listable (§7 step 3). | After a mid-scan revocation, the directories already on the frontier must not be listed. |
| N9 | Mid-scan revocation test: "grant for `Documents` revoked" | The test uses a SAF tree on its own pseudo-volume `saf:docs` (§1, Mode C). Its root DirRecord is the "Documents" entry. | With prefix semantics, a covering B grant `""` would still allow listing a revoked sub-prefix, and §7 cannot walk a volume whose root is not covered. A SAF pseudo-volume is the case where revocation actually matters. |
| N10 | End-of-stage reason rewrite: `D_NOT_GRANTED` → `D_PERMISSION_REVOKED` under revoked grants | `D_PARENT_UNREADABLE` is rewritten too, but only under grants that were revoked in this scan (`revokedAt ≥ startedAt`). | A revoked tree that is already on the frontier fails its listing (PERMISSION_DENIED) before the probe runs (the probe is rate-limited). |
| N11 | Area classification for SAF trees: prepend the mapped shared-storage prefix | `SAF_TREE` and `APP_PRIVATE` volumes are always `user_other`. | The JVM has no document-id mapping, and `Volume` has no field to store one. Phase A must add that mapping. |
| N12 | Excluded and too-long *files* | A known record at an excluded or too-long path becomes UNVERIFIABLE (`D_EXCLUDED` / `D_PATH_TOO_LONG`). A directory gets `subtreeUnverifiable` with the same reasons. | Otherwise the old record would stay PRESENT with nothing checking it. |
| N13 | TOTAL_LIMIT marks the "frontier" directories | The directories already collected from the current directory, and its unprocessed DIRECTORY entries, also get `TOTAL_LIMIT`. | Otherwise their subtrees would not appear in the coverage report. |
| N14 | `directoryGone` sets every DirRecord `NOT_FOUND` + `lastVisitedScanId` | DirRecords that are already `NOT_FOUND` are not rewritten. Their files are still REMOVED if needed: this is test 18, where the parent's next authoritative listing removes them. | Avoids rewriting records that have not changed, every scan. |
| N15 | `UNDECODABLE_NAMES` coverage entries | Derived from the session's `D_NAME_UNDECODABLE` events. | No record stores them, so directories past the 1000-event cap are not listed; the counter stays exact. |
| N16 | V2Cli file JSON fields per ARCHITECTURE §9 | Added `changeScanId` and `presenceReason` after `change`. `change` is non-null only when the change happened in this scan. | The mutation evaluator needs per-scan change states. Otherwise an unchanged file would still show `ADDED` from scan 1. |
| N17 | `--compare` ignores `timings`, `scan.resumedCount`, `coverage.events` | Also ignores `coverage.resumedCount` and `coverage.eventsDropped`. | These also differ legitimately after a resume. |
| N18 | Shell kill test `--halt-after-commits 2` | Uses 5, with `--snapshot-every-ms 0`. | Commits 1–3 happen before the first directory (session, stage start, checkpoint). Halting at 5 kills mid-walk. |
| N19 | `DiscoveryResumeTest` asserts `resumedCount == 1` for every kill point | Asserted whenever the reopened store holds a session. `killBeforeReplaceCommit(1)` kills the very first snapshot, which leaves an empty store; then the test asserts a fresh scan 1 that equals the reference. | Nothing exists to resume. |
| N20 | Float formatting "-0.0 → 0" | Doubles always carry a decimal point (`0.0`, `1.0`), so they read back as doubles; `-0.0` prints as `0.0`. | Round-trip type stability. |
| N21 | `tools/v2eval/apply_mutations.py` | Not written. | The dataset ships `mutate.py` (allowed by §0). `evaluate.py mutations` maps its ops: `overwrite_same_size` is expected to be invisible in Phase 1 (same size and mtime; documented limit §4.7). |
| N22 | Volume record "no write when nothing changed" | `lastSeenAt = now` is set on every refresh, so a volume record is rewritten each scan whenever the clock moves. | This follows the design's step 3. It is harmless: one record per volume. |
| N23 | Phase-2 enum fields of `FileRecord` | `TypeSource`, `ExtMismatch`, `Integrity`, `TimeSource` were created now (values as in ARCHITECTURE 2.3), so the record and codec are complete. | Phase 2 lists them as deliverables; they now exist. |
| N24 | jvmtest helper classes | Added `Fx` (fixture), `SystemClock` (wall clock for V2Cli; v2 never reads the time itself), `V2Json`. | Test support. |

## Dataset checks (§10.1)

* `/home/user/sp-dataset-drive/files`: 268 files found (225 jpg, 36 heic, 7 jpeg), 738,823,835 bytes, complete, about 130–170 ms.
* `/home/user/sp-dataset` (2,409 manifest entries; sidecars excluded with `--exclude`): 2,405 PRESENT. The 3 symlinks
  are `SYMLINK_SKIPPED`, which is correct. One manifest file is under `Android/obb/...` and is `OS_RESTRICTED` by the
  default restriction (correct for Mode B on API 30+), so `--gate phase1` fails by that one file. With
  `--restricted Android/data` the gate PASSES: 2,406/2,406 files found, sizes equal.
  **Lead decision needed:** should the dataset keep a file under `Android/obb` (which tests restriction), or should the
  gate run use `--restricted Android/data`?
* Mutation check (copy of the dataset, `mutate.py`, incremental scan): `GATE mutations PASSED` (0 missing, 0 unexpected).
  A third unchanged scan reports 0 changes.

## Not done / for later phases

* `V2Device` has no `media()`, `apps()`, `text()`, `fileOps()` yet (Phases 2, 6, 9). Phase 2 should pass the installed
  set from `InstalledApps` (when `availability == FULL`) into `DiscoveryEngine`.
* `Budget` and `Throttle` exist but have only basic use. Pause by throttle is implemented but not covered by a test.
