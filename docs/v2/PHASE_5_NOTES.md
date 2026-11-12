# Phase 5 notes (implementer)

Status: slices 0–4 and 6 built; slice 5 has app subjects but no `ClassicParityTest`. `bash tools/v2eval/run_tests.sh` → 32 suites, 0 failures.
Not committed by the implementer (commit `c2e85cd` "work in progress" was made by someone else mid-run).

## What exists

| Where | Classes |
|---|---|
| `v2/plan/` | `PlanUnit` (+`Option2`), `PlanPriority`, `UnitBuilder2`, `Planner2`, `PlanValidator`, `Plans` (make + versioning), `PlanStaleness`, `HashOnDemand`, `AppSubjects`, `Fmt` |
| `v2/model/` | `ActionPlan` (C-5 fields, `PlanItem`) |
| `v2/plan/ReasonCatalog` | `S_APP_CACHE`, `N_APP_ARCHIVE`, `Y_IDLE_APP`, `PL_NEEDS_YOUR_CALL`, `PL_TARGET_UNREACHABLE`, `PL_HASH_PENDING`, `PL_VALIDATOR_DROPPED` |
| tests | `UnitBuilder2Test`, `Planner2Test` (incl. tier order F-11, A/B/C swap, 2 000 brute-force instances: mean cost gap 0.033, 30 GB fixture, determinism), `PlanPropertyTest` (9 000 random plans, lead's property list), `YourCallEligibilityTest` (A25, hash on demand, staleness, app subjects); fixture `v2test/plan/installed_apps_ext.json` |
| CLI / eval | `V2Cli --target-bytes N|max|1GB|… [--max-plan-tier T] [--sdk N] [--installed-apps-ext J] [--check-plan prev.json] [--hash-budget-bytes N]` writes `plan`; `evaluate.py --gate phase5 --plans name=R,… [--stale R]` |

## Slice 0 (ruling A25)

* `TierAssigner` row 8: `utility > 0` removed from YOUR_CALL eligibility; utility still ranks Y_ candidates. `Planner2` admits a YOUR_CALL option only
  with utility > 0 (plan inclusion).
* Two tightenings were needed, because the size gate had been hiding them (both only move YOUR_CALL to REVIEW/KEEP):
  * `CandidateRules`: a copy whose mtime is > 1 day before its claimed original's → `R_UNCERTAIN_COPY` (chronology contradicts derivation).
  * Aggressive `Y_SIMILAR_SHOT` only for camera captures in the same area as the keeper; two edits of one photo stay `R_SIMILAR_SHOT`.
* **Exposure is 1, not 0 (both modes)** — stopped there, nothing loosened: `WhatsApp/Media/WhatsApp Images/IMG-20240122-WA0001.jpg`
  (must_never_delete, manifest "unique") gets `Y_CHAT_COPY` against `IMG-20231220-WA0000.jpg`. Their sources are `obama-720p.jpg` and
  `obama-1080p.jpg` from face_recognition's examples: the same picture at two resolutions (pHash 0, SSIM64 0.99995, VERIFIED COMPRESSED_FROM).
  v2's evidence is correct; the label says unique. The 480p sibling is caught by the chronology rule. Lead decision needed (label defect like A19's invoices?).
* YOUR_CALL hit rate 0.609 standard / 0.636 aggressive (gate 0.8). Blocked rows (standard, expected YOUR_CALL predicted KEEP): screenshots 129 + 19
  (ads/QR screenshots), memes/ads in chat without a copy relation 100, QR / photo-of-screen camera shots 66, downloads (ads, QR) 19 — all need
  `Y_OLD_SCREENSHOT` / `Y_FORWARDED_MEDIA` / `Y_OLD_DOWNLOAD`, which PHASE_4 §9.2 defers to Phase 6 (CONTENT family). Remaining ~26: WebP
  conversions undecodable on the JVM (A23), 4 chat backups, a few VERSION_OF memes.

## Deviations

| # | Design said | Done | Why |
|---|---|---|---|
| D-1 | stages `RECOMMEND`, `PLAN` | `Plans.make` + `HashOnDemand.run` called by `V2Cli` after ASSESS (as E-1) | budget |
| D-2 | rec ids with planVersion | Phase 4 provisional recs (planVersion 0) are planned as they are | ids stay stable across plans; RecommendationTable not written |
| D-3 | `InstalledApps.platformSdk()` (C-3) | `sdk` parameter of `AppSubjects.recs`, CLI `--sdk` | avoids touching the Android implementation / default methods |
| D-4 | `PlanValidator` re-runs `HardRules.check` | re-checks tier, rec identity, hashPending, file twice, keepers, requires, sums | `Verdict.hardBlocks` lists blocks of *other* candidates, so it is not a veto; no FileContext at plan time |
| D-5 | `RecommendationExpiry` F1 hook in DISCOVER | not built | `PlanStaleness` covers the plan side (fingerprint, presence, rec tier/reason, app stats, plus re-hash) |
| D-6 | — | `PlanStaleness` re-hashes planned files that have a recorded sha256 | S12 `overwrite_same_size` keeps size and mtime; only the hash shows it |
| D-7 | swap only replaces a set | swap also replaces a single tier-k item by a cheaper one that still covers the remainder | F-11 tier-order case (minimum YC bytes) |
| D-8 | app-vs-file: "lower tier wins" | implemented literally: a SAFE file under `Android/media/<pkg>` blocks a YOUR_CALL uninstall of `<pkg>` | as designed; noted because it can hide a large uninstall |
| D-9 | tests in `v2test/plan/` | in `v2test/` (package-private fixtures `RiskFx`) | only the JSON fixture is in `plan/` |
| D-10 | bundles from Phase 4 | `UnitBuilder2` supports bundles (tested); Phase 4 still produces none (E-9) | |
| D-11 | `ApprovalCarry`, `N_ARCHIVE_FOLDER` | not built (F-16 cuts) | |
| D-12 | uninstall expected loss | `UNINSTALL_EL_MID = 0.05`, uncertainty 0.05 | uncalibrated, like every PHASE_4 constant |

## Missing

`ClassicParityTest` (A14/A15 per-item parity with the exemption list), `RecommendationExpiryTest`, `ModeInvarianceTest`, `ClassifierStubPlanTest`,
`AgeOnlyTest`, `DatasetPlanTest`; `PL_*` sentences not in `files[].assessment`; Phase A adapter (outline only, as instructed).

## For the Phase A adapter

* Use `Plans.make(...).built`: `units` are conflict-free per F-13 except where `Option2.conflicts` is non-empty (app vs file at different tiers)
  — emit only one side to classic. `displayOnly` units are "included in {includedIn}".
* Keepers never have a unit (`UnitBuilder2` drops removal recs on keepers, `Plans.keepers` = every group keeper); `userDelete` must not re-add them.
* hashPending deletes have no unit; YOUR_CALL options with utility ≤ 0 are recommendations but never planned.
* `ActionPlan.items[].fps`/`hashes` are what staleness checks; re-check before any approval request.
