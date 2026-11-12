# Phase 5 — Storage optimisation planner

Status: design, for review. Author: architect agent (phases 3–5). Spec: §10 (objective, targets, priorities), §9.4–9.5, §11.1
(revalidation inputs), §16.2–16.4 (metrics, scenarios). Binding: ARCHITECTURE §2.11–2.13, §7, §8 (planner bullet), §10, §13 (Phase A),
A6, A8, A14. Builds on PHASE_3 (`GraphView`) and PHASE_4 (`Verdict`, `HardRules`, `TierAssigner`, `PresentationPolicy`). ARCHITECTURE
edits are listed in §12.

---

## 0. Deliverables

| Where | What |
|---|---|
| `v2/plan/` | `RecommendStage` (stage `RECOMMEND`), `PlanStage` (stage `PLAN`), `RecommendationBuilder`, `HashOnDemand`, `AppSubjects`, `PlanUnit`, `UnitBuilder2`, `Planner2`, `PlanValidator`, `PlanPriority`, `RecommendationExpiry` (F1 hook), `PlanStaleness`, `ApprovalCarry` |
| `v2/discovery/` | one call at the end of `DISCOVER`: `RecommendationExpiry.onChanges(store, changedFileIds)` (ARCHITECTURE §2.11 F1) |
| `v2/device/` | `InstalledApps.platformSdk()` (int, −1 unknown) — §12 C-3 |
| `jvmtest/.../v2test/plan/` | tests of §10; `ClassicParityTest` (A14, §6.3); fixture `installed_apps_ext.json` (app storage/usage fields the dataset sidecar lacks) |
| `tools/v2eval/evaluate.py` | §9 additions |
| `app/.../app/v2/V2Adapter.java` (outline only; built in Phase A) | §11 |

---

## 1. Candidate actions per subject

| Subject | Possible actions (from PHASE_4 verdicts) | Tier range | Bytes |
|---|---|---|---|
| file | KEEP (no rec) · DELETE (S_/N_/Y_ codes) · COMPRESS (`N_LOSSLESS_SMALLER`; lossy only from Phase 6) · ARCHIVE (§1.1) · REVIEW · PROTECTED(KEEP) | SAFE…PROTECTED | PHASE_4 §7.2 |
| exact / content-equivalent group | no group action: each redundant member is its own file subject; the keeper is never a subject | — | Σ planned members only |
| bundle (split APK set; primary + sidecars) | one DELETE for all members together | tier = max of members | Σ members |
| `cache:<pkg>` | `CLEAR_APP_CACHE` (`S_APP_CACHE`) | SAFE | `cacheBytes` |
| `app:<pkg>` | `ARCHIVE_APP` (`N_APP_ARCHIVE`, sdk ≥ 35) · `UNINSTALL_APP` (`Y_IDLE_APP`) | NO_LOSS / YOUR_CALL | `codeBytes` / `codeBytes + dataBytes` |
| QUARANTINE | not proposed by the planner; Phase 9 may execute a DELETE as quarantine-then-purge (execution policy, not a plan choice) | — | — |

### 1.1 ARCHIVE candidates (spec 10.3 priority 7)

A folder-level subject `dir:<volumeId>:<path>`: a directory whose direct children are ≥ 20 files of types in PHASE_4 §7.2's
compressible list, total ≥ 20 MiB, estimated saving ≥ 30 %, none with a removal recommendation, none PROTECTED, none modified in the
last 365 days (mtime) **and** `U90.high < 0.05` for all of them. Tier NO_LOSS (lossless, verified at execution), reason
`N_ARCHIVE_FOLDER` ★: "{n} rarely used {kind} files in {folder} can be packed into one archive, saving about {saving}; nothing is lost."
Rarely produced; on the dataset expected ≤ 2 subjects (Android logs are SAFE removals instead).
**REVIEW_2 F-16 (cut): deferred to Phase 6** together with lossy compression — it needs a new subject kind, a folder-level executor
and its own tests for ≤ 2 dataset subjects. Phase 5 emits no `dir:` subjects and no `N_ARCHIVE_FOLDER`; priority class 7 stays empty.

---

## 2. Recommendations (`RecommendStage`, `RecommendationBuilder`)

For every PHASE_4 `Verdict` with a tier (removal-class) and every transform verdict:

```
rec.recommendationId = hex(SHA-256(subject + "\0" + action + "\0" + planVersion))[0..24)      (ARCHITECTURE §2.11)
rec.tier, proposedAction, reasonCodes (primary first), reasonParams, evidenceRefs, dependencyIds (keeper, primaries), fp, createdScanId
rec.expectedSavingBytes = B(action)   (unknown -> Unknown.LONG; such recs never enter a plan)
rec.expectedLoss = elMid(action); rec.uncertainty = elPess(action) - elMid(action); rec.confidence = C
```
Rules: (1) per file at most one removal-class rec (DELETE) **or** one REVIEW **or** one PROTECTED(KEEP), plus at most one transform rec
(COMPRESS/ARCHIVE) only when the removal-class verdict is KEEP or YOUR_CALL. (2) **Hash on demand** (`HashOnDemand`, ARCHITECTURE V3):
every DELETE rec needs a current full SHA-256 of its file; missing ones are computed by Phase 2's `HashStage` code with
`reason = "PLAN"`, in plan-priority order (§4.2) then bytes descending, charging the scan budget. A rec whose hash is still missing when
the budget ends is stored with `reasonParams.hash = "pending"` and is **not plannable** (counted under `PL_HASH_PENDING` ★: "{n} items
are still being checked and will be offered later."). The hash goes into `evidenceRefs` as `"sha256:<hex>"`. (3) Approval carry — **REVIEW_2 F-16 (cut): deferred to Phase 9**; nothing approves a v2 rec before Phase 9 (H5), so Phase 5 writes
every rec `NOT_REQUESTED` and the rule below is the Phase 9 specification
(`ApprovalCarry`): a new rec inherits `approvalState` from the previous plan's rec for the same `(subject, action)` only if fp, tier,
primary reason and every `dependencyIds` fingerprint are unchanged; otherwise `NOT_REQUESTED`. (4) Recs of the previous version that are
not regenerated are removed from the table (history is in the plans; Phase 9 journal keeps executed ones).

**F1 expiry** (`RecommendationExpiry.onChanges`): called at the end of `DISCOVER` with every fileId whose `change` was written in this
scan as `CHANGED, REPLACED, REMOVED` or whose presence became `UNVERIFIABLE/VOLUME_ABSENT`, plus every rec that lists such a file in
`dependencyIds`: `PENDING/APPROVED → EXPIRED` in the same commit. App subjects expire when `versionCode` changes or `cacheBytes`/
`dataBytes` move by > 20 %.

---

## 3. Mutual exclusion, dependencies, no double counting (`UnitBuilder2`, `PlanValidator`)

A **plan unit** is the atomic thing the planner chooses: `PlanUnit { String id; List<String> fileIds; List<Option2> options; Set<String>
requires; Set<String> conflictsWith; int priorityClass; }`, `Option2 { Action action; Tier tier; long bytes; double cost; String recId; }`.

| Constraint | Encoding |
|---|---|
| one action per file | a file's removal rec and transform rec are two `Option2` of one unit (mutually exclusive, keep = implicit no-option) |
| never remove a keeper | keepers have no removal option (PHASE_4 HC13); `PlanValidator` re-checks: no unit that removes a file may be selected if that file is in `requires` of any selected unit |
| never all members of an exact group | consequence of the previous row (the keeper stays); re-checked per group by `PlanValidator` |
| verified copy must not itself be in the plan (H1) | `unit.requires = rec.dependencyIds`; selecting a unit whose `requires` intersects the planned removals is refused; selection order (§4.2) puts N_ before Y_, so a Y_ removal of a file that an N_ unit depends on is the one refused |
| keeper transform (V4) | a COMPRESS/ARCHIVE option on a file that appears in `requires` of a selected removal is refused |
| bundles | split APKs + base, primary + sidecars: one unit with all fileIds; a bundle is dropped entirely if any member is ineligible |
| dependency of a kept file (H8) | sidecars of a kept primary get no removal option (PHASE_4 HC4) |
| app vs file subjects | `cache:<pkg>` conflicts with file units of `S_APP_CACHE_FILE` under `Android/data/<pkg>/cache/**` (StorageStatsManager's cacheBytes includes external cache); `app:<pkg>` UNINSTALL conflicts with every file unit under `Android/(data|media|obb)/<pkg>/**` (removed with the app; dataBytes includes them). The app unit wins when both are selectable at the same tier; otherwise the lower-tier one wins |
| group bytes counted once | every unit's bytes are its own files' `sizeBytes`; no unit contains another's files; aliases have 0 bytes and no unit (PHASE_3 §2.3); `ActionPlan.achievableBytes = Σ selected option bytes` and `PlanValidator` asserts it equals `Σ item.bytes` and that no fileId appears twice |
| REVIEW / PROTECTED | never units |

`PlanValidator.validate(ActionPlan, store, graph)` runs after selection and re-runs `HardRules.check` for every item (ARCHITECTURE §7). Any
violation is a bug: the item is removed, `PL_VALIDATOR_DROPPED` ★ is logged in coverage, and the test suite fails on any non-zero count.

---

## 4. Objective, priorities, algorithm (`Planner2`)

### 4.1 Objective and constraints (spec 10.1)

```
maximise   Σ_selected [ Bn(o) − EL_mid(o) − EC(o) − QP(o) − UP(o) ]          (= Σ ActionUtility, PHASE_4 §7.3)
subject to all of §3; tier(o) ∈ {SAFE, NO_LOSS} for automatic items; YOUR_CALL only as "needs your decision" items (§5);
           REVIEW/PROTECTED never; approval required for every destructive item at execution (H5);
           Σ bytes ≥ target where achievable; every item executable within current grants (canReadContent != FALSE; V5 at execution)
```
In practice the hard constraints and the tier lexicography dominate: SAFE/NO_LOSS options have EL ≈ 0 by construction (rv = 1), so
among them the objective is "free bytes"; utility only orders YOUR_CALL options. The algorithm therefore optimises lexicographically:
(1) never use a higher tier than needed, (2) follow spec 10.3 priority classes, (3) within a class, best cost per byte.

### 4.2 Priority classes (spec 10.3) — `PlanPriority.of(option)`

| Class | Spec item | Options |
|---|---|---|
| 1 | rebuildable data | `S_TEMP_FILE, S_TRASHED, S_THUMBNAIL, S_APP_CACHE_FILE, S_APP_LOG, S_EXPIRED_STATUS, S_EMPTY_FILE, S_UNINSTALLED_LEFTOVER`, `CLEAR_APP_CACHE` |
| 2 | verified exact duplicates | `N_EXACT_DUPLICATE` |
| 3 | verified redundant installers | `N_INSTALLER_INSTALLED`, `ARCHIVE_APP` (`N_APP_ARCHIVE`) |
| 4 | verified redundant backups | `N_OLDER_BACKUP` (no producer until Phase 6) |
| 5 | re-encoding | `N_LOSSLESS_SMALLER` (NO_LOSS); lossy COMPRESS (YOUR_CALL, Phase 6+) |
| 6 | user-review candidates | every `Y_` DELETE, `UNINSTALL_APP` |
| 7 | archive candidates | `N_ARCHIVE_FOLDER` |

### 4.3 Algorithm

```
units = UnitBuilder2.build(recs, apps, graph)                         // §3; options with unknown bytes or pending hash dropped
cost(o) = EL_pess(o) + EC(o) + QP(o)                                    // = Bn(o) − utility(o); ≥ 0
key(o)  = (tierRank(o), priorityClass(o), cost(o)/max(1,bytes(o)), −bytes(o), unitId)   // total order, deterministic
          // REVIEW_2 F-11: tier first. With priorityClass first, class-6 YOUR_CALL deletions sorted before the class-7 NO_LOSS
          // archive option, so the k = YOUR_CALL pass could reach the target with YOUR_CALL items while a NO_LOSS option was left
          // unused — raising risk the spec 10.2 forbids. Spec 10.3 order applies *within* a tier.
cap(k)  = Σ over units of the largest-bytes option with tier ≤ k (after §3 conflict resolution by key order)

plan(target, mode):
  base = all units' best option with tier == SAFE (class 1 first)     // rebuildable data is always taken (zero expected loss)
  if target == MAX_SAFE:  select base + every NO_LOSS option (classes 2,3,5,7, key order, §3 checks); return reachesTarget = true
  for k in [NO_LOSS, YOUR_CALL]:
      if bytes(base) ≥ target: return base (highestTier SAFE)
      if cap(k) < target: continue
      sel = base; for o in options with SAFE < tier(o) ≤ k sorted by key: if §3 allows o: sel += o (replacing a lower-bytes option of
            the same unit if any); stop when bytes(sel) ≥ target
      prune(sel, target): for selected non-SAFE options in reverse key order: drop (or downgrade to the unit's next option ≤ k) if
            bytes(sel) − Δ ≥ target
      swap(sel, target): (REVIEW_2 F-11) only among tier-k options: let G = selected options with tier == k; R = target − bytes(sel \ G);
            candidate single option s ∉ G with tier == k, bytes(s) ≥ R and cost(s) < Σ cost(G): replace G by s if §3 allows (best such s
            by cost, then key). Options below k are never swapped out (they are lower risk; trading them for a tier-k item raises risk)
      return sel with highestTier = k, reachesTarget = true
  // unreachable
  sel = base + all NO_LOSS (as MAX_SAFE) + all YOUR_CALL options with utility > 0 in key order (§3 checks)
  reachesTarget = false; reason PL_TARGET_UNREACHABLE {target, achievable = bytes(SAFE+NO_LOSS items), withYourCall = bytes(sel)}
```
Complexity: O(u log u) for u units (sorting); prune and swap are linear. 100 k units < 1 s on a phone.

### 4.4 Why greedy with prune and swap, not knapsack/ILP

* The problem after §3 is a *min-cost covering knapsack* with item-level exclusions, in which the cost scale is lexicographic by tier
  (SAFE/NO_LOSS costs are ≈ 0 by construction). A DP over bytes is O(u × target/granularity) memory (100 k × 20 GB / 1 MB ≈ 2·10⁹ cells),
  and an ILP needs a solver library (forbidden, ARCHITECTURE §0).
* Optimality only matters among YOUR_CALL options, whose costs are uncalibrated heuristics (PHASE_4 §13): exact optimisation of an
  uncalibrated objective buys nothing. The classic planner's research comparison found greedy+prune within half a cost point of the exact
  DP on the same kind of input (`core.Planner` comment).
* Known failure of plain greedy, and its fix: target 1 GB, YOUR_CALL items A (0.9 GB, cost 0.09), B (0.9 GB, cost 0.09), C (1.0 GB,
  cost 0.11). Density picks A then B (cost 0.18, overshoot 0.8 GB, prune cannot drop either); the optimum is C alone (0.11). This shape
  (two large chat videos vs one old download) is realistic, so the `swap` pass above replaces the selected non-free set by the best
  single option that covers the remainder. With prune + swap, the remaining gap to optimal requires ≥ 3-item combinations beating a pair,
  which `Planner2Test` bounds by brute force on random instances (≤ 12 units): mean gap ≤ 5 % of optimal cost, never a constraint violation.

---

## 5. Storage targets (spec 10.2)

| Target | `ActionPlan.targetBytes` | Behaviour |
|---|---|---|
| Free 1 / 5 / 10 / 20 GB | `n × 1 000 000 000` (decimal GB, as the UI and classic show) | §4.3 |
| Maximum safe recovery | `Long.MAX_VALUE` | SAFE + NO_LOSS only; never YOUR_CALL |

Reporting: `achievableBytes` = Σ SAFE + NO_LOSS item bytes (these need only one approval screen, no individual judgement);
`withYourCallBytes` = plus YOUR_CALL items in the plan; `reachesTarget`; `highestTier`. When the plan uses YOUR_CALL items the plan
carries reason `PL_NEEDS_YOUR_CALL` ★: "To reach {target}, {n} items ({bytes}) need your decision; {safe} can be freed without any."
When the target is unreachable: `PL_TARGET_UNREACHABLE` (existing). **The planner never selects REVIEW/PROTECTED, never relaxes a hard
rule and never switches policy (standard → aggressive) to reach a target**: aggressive mode is only what the user selected (H7).
COMPRESS/ARCHIVE bytes are estimates (`exact=false`); `achievableBytes` is reported both total and `exactOnly`.

---

## 6. App caches and idle apps (A14)

### 6.1 `AppSubjects` (same thresholds as classic `UnitBuilder`, so outcomes are comparable)

| Subject | Condition (InstalledApp fields, ARCHITECTURE §3.3) | Rec | Unknowns |
|---|---|---|---|
| `cache:<pkg>` | `cacheBytes > 0` | `CLEAR_APP_CACHE`, SAFE, `S_APP_CACHE`, class 1 | `cacheBytes == -1` → no rec |
| `app:<pkg>` archive | `system == FALSE`, `hasLauncher == TRUE`, `essential != TRUE`, `platformSdk ≥ 35`, idle = now − `lastUsed` ≥ 60 d | `ARCHIVE_APP`, NO_LOSS, `N_APP_ARCHIVE`, class 3, bytes `codeBytes` | any of system/hasLauncher/lastUsed/sdk unknown → no rec |
| `app:<pkg>` uninstall | same base conditions, idle ≥ 120 d and (`game == TRUE` or `platformSdk < 35`) | `UNINSTALL_APP`, YOUR_CALL, `Y_IDLE_APP`, class 6, bytes `codeBytes + dataBytes` | same; `dataBytes == -1` → no rec |

`lastUsed` comes from UsageStatsManager (APP family, a measured usage fact), not from file timestamps; H10 is about file ages and atime.
Recommendation `fileId` = subject id (`cache:<pkg>`/`app:<pkg>`), `fp = null`; revalidation re-reads the stats (ARCHITECTURE §2.11).

### 6.2 Why "v2 safe bytes ≥ classic safe bytes" cannot hold literally

Classic puts these in R1 ("no loss") and v2 deliberately does not (each is a safety rule, not an omission):

| Classic technique (risk) | Classic evidence | v2 | Rule |
|---|---|---|---|
| `SENT` WhatsApp sent copy (R1) | pHash ≤ 8 bits + aspect, no structural check | `Y_CHAT_COPY` YOUR_CALL | H2; PHASE_3 probe: same-template screens reach 0 bits |
| `TELEGRAM` media (R1) | "re-downloadable from Telegram's cloud" | KEEP (no rec) | spec 2.2: cloud backup is never verified; dataset labels these KEEP/YOUR_CALL |
| `INSTALLED_APK` older version (R1) | installed versionCode ≥ APK's, no signer check | `Y_OLDER_INSTALLER` YOUR_CALL (signer equal) / REVIEW | spec 9.4 NO-LOSS "verified *redundant* installer": an older version is a rollback copy, not redundant, and without a signer check not even verified (S06) |
| `WA_BACKUP` older chat backup (R1) | name pattern | `Y_OLD_CHAT_BACKUP` YOUR_CALL | spec 9.4 NO-LOSS "verified redundant backup" + spec 8.3 (a backup must be verified): crypt files cannot be verified |
| `DUPLICATES` across alias routes (R1) | same SHA-256 | none / REVIEW | spec 9.4 NO-LOSS "identical duplicate with **another** accessible copy" (an alias is the same file; ARCHITECTURE G2) |
| `lossless_image` screenshots (R1, estimate) | estimate | `N_LOSSLESS_SMALLER` only when B > 1 MiB and ≥ 10 % | spec 10.1/8.6 "actual usable storage": estimates are reported `exact=false`, not counted as safe bytes |

REVIEW_2 F-14: this table is the **closed** exemption list (`ClassicParityTest.EXEMPTIONS`, one entry per row: classic technique,
required v2 code or "no rec", spec rule). An exemption matches only if v2's output on that subject is exactly the listed code; any new
exemption needs a row here naming the spec rule it protects (ruling A15).

### 6.3 `ClassicParityTest` (replaces the literal A14 test)

Runs classic `Scanner` + `UnitBuilder` and v2 on the same dataset root with the same installed apps (`installed_apps_ext.json`, which
adds `cacheBytes`, `codeBytes`, `dataBytes`, `lastUsed`, `system`, `hasLauncher`, `game` for the 5 dataset packages plus 3 cache-only
packages) and `sdk = 35`. For every classic option with risk R0/R1 and `exact = true`:
* **matched**: v2 has a SAFE/NO_LOSS rec on the same subject (any removal for files, same action for app subjects) — counted in `matchedBytes`;
* **exempt**: v2's rec/absence is explained by a row of §6.2 (the test checks the specific v2 code or rule) — `exemptBytes`, listed by technique;
* **missed**: anything else — test fails and prints the subject.
Gates: `missed = 0`; `v2 SAFE+NO_LOSS exact bytes ≥ classic R0+R1 exact bytes − exemptBytes`; app-cache bytes equal. The report shows
`exemptBytes` per technique so the lead sees exactly what v2 gives up relative to classic and why.

---

## 7. Plan versioning and staleness (`PlanStage`, `PlanStaleness`)

* `planVersion` = previous `ActionPlan.planVersion + 1` (1 for the first); `planId = "p" + planVersion`; the last 3 plans are kept, older
  ones removed. `createdAt = clock`, `scanId` = current scan.
* A plan becomes `STALE` (state written by `PlanStaleness.check` at the end of every scan and before any approval request) when any item:
  has an EXPIRED rec (F1); its file's or any `requires` file's fingerprint differs from the rec's; its tier or primary reason would change
  in the current assessment; an app subject's stats moved > 20 % or its versionCode changed; or a grant used by an item lost
  `canReadContent`. A STALE plan is never executed (V1); the UI re-plans (new version, approvals carried per §2 rule 3).
* `PRESENTED/PARTIALLY_APPROVED/APPROVED/EXECUTING/DONE/CANCELLED` transitions belong to Phase 9 / Phase A; Phase 5 writes `DRAFT` only.

---

## 8. V2Cli JSON additions

`recommendations[]` as ARCHITECTURE §9 plus `"subject"` (file path, `cache:<pkg>`, `app:<pkg>`, `dir:<path>`), `"priorityClass"`, `"exact"`
(bytes measured or estimated), `"hashPending"`. `plan` gains `withYourCallBytes`, `achievableExactBytes`, `reasonCodes`
(`PL_*`), `items[].priorityClass`. `files[].assessment` = PHASE_4 §1.2 fields (intervals as `[low, mid, high]`), `keeperOf`. CLI flags:
`--target-bytes N|max|1GB|5GB|10GB|20GB`, `--mode standard|aggressive`, `--sdk N` (JVM `platformSdk`, default 34),
`--installed-apps-ext <json>`.

---

## 9. Evaluator additions (`evaluate.py --gate phase5`)

### 9.1 Label translation (dataset semantics ≠ spec 9.4 semantics)

The dataset's tiers mean: SAFE = "deletable with no information loss" (exact copies, caches, **and** chat/resized copies); NO_LOSS =
"compressible without meaningful loss, never deleted". Spec 9.4 / ARCHITECTURE: SAFE = rebuildable; NO_LOSS = verified identical copy;
similar copies are YOUR_CALL at most (H2). The evaluator therefore scores v2 output through this translation:

| v2 output on a file | Translated prediction |
|---|---|
| PROTECTED rec | `PROTECTED` |
| DELETE at SAFE or NO_LOSS | `SAFE` |
| DELETE at YOUR_CALL | `YOUR_CALL` |
| REVIEW rec | `REVIEW` |
| only a transform rec (COMPRESS/ARCHIVE) | removal prediction `KEEP`; transform scored separately against dataset `NO_LOSS` |
| nothing / file not in result (e.g. OS-restricted `Android/obb`) | `KEEP` |

Ranks for removal predictions: `SAFE 0 < YOUR_CALL 2 < REVIEW 3 < KEEP 4 < PROTECTED 5` (as ARCHITECTURE §10).

**REVIEW_2 F-12 (ruling R2 — the translation goes both ways).** Translating v2 output alone would still *reward* a spec violation: a v2
NO_LOSS DELETE on a WhatsApp-recompressed copy translates to `SAFE`, which is in that file's acceptable tiers. The evaluator therefore
also translates the **expected** side to spec tiers before scoring:
* `specCap(file)` = `YOUR_CALL` when the file's manifest relationships are only `COMPRESSED_FROM`, `VERSION_OF`, `EDITED_FROM`,
  `VISUALLY_SIMILAR` (no `EXACT_DUPLICATE`, not `thumbnail_cache`/`temp_cache` category); otherwise none.
* For capped files, `SAFE` and `NO_LOSS` are removed from `acceptable_tiers` (and `expected = SAFE` becomes `YOUR_CALL`) for every metric.
* New metric `spec_violations` = capped files with a SAFE/NO_LOSS DELETE; gate **0**, both modes.
* The must_never_delete checks (DDRR, exposure, scenario FAIL) read v2's raw tiers and the raw manifest flag and are applied **before**
  and independently of any translation; no translation can turn them into a pass.
The capped set is printed in the report (count per category) so the lead can audit it.

### 9.2 Metrics (all computable offline from `result.json` + manifest + sidecars)

| Metric | Definition | Gate (standard and aggressive unless noted) |
|---|---|---|
| DDRR | must_never_delete files with a DELETE at SAFE/NO_LOSS ÷ must_never_delete files | **= 0** |
| exposure | must_never_delete files with any DELETE (incl. YOUR_CALL) | **= 0** (dataset `_scoring`: YOUR_CALL on such a file is a critical failure) |
| mnd transforms | must_never_delete files with a COMPRESS/ARCHIVE rec | reported (lossless only; lossy forbidden by HC8 until Phase 6) |
| deletion precision per v2 tier T ∈ {SAFE, NO_LOSS, YOUR_CALL} | correct iff ¬mnd ∧ (translated(T) ∈ acceptable_tiers ∨ (expected ∈ {SAFE, YOUR_CALL} ∧ rank(expected) ≤ rank(translated(T)))) | SAFE ≥ 0.98, NO_LOSS ≥ 0.98, YOUR_CALL ≥ 0.80 |
| deletion recall (acceptable-aware) | files with expected `SAFE`: fraction with a DELETE whose translated tier ∈ acceptable_tiers | ≥ 0.90 |
| no-loss recall (strict) | files with expected `SAFE` and `YOUR_CALL ∉ acceptable_tiers` (the dataset insists: exact copies, thumbnails, statuses): fraction with SAFE/NO_LOSS DELETE | ≥ 0.90 |
| keeper agreement | exact groups where the kept member is the manifest's relationship target (or a member equal to it by SHA) | ≥ 0.95, disagreements listed |
| storage recovery | `pred` = Σ bytes of SAFE+NO_LOSS DELETE recs; `correct_bytes` = part on files whose translated `SAFE` ∈ acceptable; `wrong_bytes` = rest; `dangerous_bytes` = part on mnd; `byte_recall` = correct_bytes ÷ Σ size(expected SAFE ∧ `YOUR_CALL ∉ acceptable`) | dangerous = 0; wrong ≤ 2 % of pred; byte_recall ≥ 0.90 |
| plan consistency | `achievableBytes == Σ items (SAFE/NO_LOSS)`, no fileId twice, no keeper/REVIEW/PROTECTED in items, every item's `requires` absent from removals | exact, 0 violations |
| target behaviour | runs with `--target-bytes` 1 GB, 5 GB, 10 GB, 20 GB, max: `reachesTarget == (achievable ≥ target)` or YOUR_CALL used with `PL_NEEDS_YOUR_CALL`; unreachable → `PL_TARGET_UNREACHABLE` and achievable reported | all true (the dataset is 716 MB, so 1 GB+ exercises the unreachable path; a synthetic 30 GB size-only fixture in `Planner2Test` exercises reachable targets) |
| tier confusion (translated, 6×6) | rows expected, columns translated prediction; diagonal counted as "prediction ∈ acceptable_tiers" | reported; SAFE row acceptable-hit ≥ 0.90 |
| compression | dataset `NO_LOSS` files with a transform rec / total; transform recs on files whose acceptable lacks NO_LOSS (reported, not an error) | reported (Phase 5 expects WAVs only: 3/6) |
| false backup verification rate | PHASE_3 §11.3 | 0 |
| duplicate cluster accuracy | PHASE_3 §11.3 / A-4 | PHASE_3 gates |
| reason completeness | every rec has ≥ 1 catalogue code, every template param filled | 100 % |
| undo success, compression quality | — | `N/A` until Phase 9 |
| operational | scan time, files/s, peak heap, vs `--baseline` | reported; > 25 % slowdown warns |

### 9.3 Per-scenario pass/fail (against `scenarios.json`, scenarios 1–11)

For each scenario, over its listed files (missing files → `KEEP`):
* **FAIL** if any member is must_never_delete with any DELETE, or has a translated prediction of `SAFE`/`YOUR_CALL` that is not in its
  acceptable_tiers while `rank(prediction) < rank(expected)` (under-cautious);
* **PASS** if every member's translated prediction ∈ its acceptable_tiers;
* **CAUTIOUS** otherwise (no danger, some member more conservative than every acceptable tier).
Scenarios 12–15: `N/A` until Phase 9 (they read `execution`).
Gate: no FAIL in 1–11 (both modes); ≥ 8 PASS (standard). Expected: PASS 2, 3, 4, 5, 6, 7, 9, 10; CAUTIOUS 1 (pocket-shot distractors need
`Y_LOW_QUALITY` to fire on all), 8 (control video needs Phase 6), 11 (sticker-pack zip needs Phase 6).

---

## 10. Tests (`jvmtest/.../v2test/plan/`)

| Test | Cases |
|---|---|
| `UnitBuilder2Test` | removal + transform options of one file are exclusive; bundle (base + 2 splits) atomic, dropped if one split ineligible; cache unit vs cache files conflict; uninstall unit vs Android/media files conflict; alias → no unit |
| `Planner2Test` | MAX_SAFE = all SAFE+NO_LOSS; target reachable at SAFE → no NO_LOSS; reachable only with NO_LOSS → highestTier NO_LOSS; needs YOUR_CALL → `PL_NEEDS_YOUR_CALL`; unreachable → `PL_TARGET_UNREACHABLE` with achievable; prune keeps ≥ target; swap fixes the A/B/C case of §4.4; brute-force comparison on 2 000 random instances (≤ 12 units): no violation, mean cost gap ≤ 5 %; determinism under shuffled input |
| `PlanValidatorTest` (property, ARCHITECTURE H1 P5) | random graphs (exact groups, content groups, bundles, companions) × random tiers → no plan ever removes a keeper, all members of an exact group, a `requires` file, a REVIEW/PROTECTED file, or a file twice; `achievableBytes == Σ items` |
| `HashOnDemandTest` | DELETE recs get `sha256:` evidence; budget exhaustion → `hashPending`, not planned; stale hash recomputed |
| `RecommendationExpiryTest` | CHANGED/REPLACED/REMOVED/UNVERIFIABLE file → its PENDING/APPROVED recs EXPIRED in the same commit; dependency change expires dependents; app version change expires app recs |
| `ApprovalCarryTest` | deferred to Phase 9 (F-16) |
| `Planner2TierOrderTest` (F-11) | synthetic NO_LOSS option with a higher priority class than the YOUR_CALL options (as `N_ARCHIVE_FOLDER` will be in Phase 6), target reachable with NO_LOSS + one YC item → the NO_LOSS option is selected and only the minimum YC bytes; swap never removes a NO_LOSS item |
| `PlanStalenessTest` | each STALE trigger of §7 |
| `ModeInvarianceTest` (H7) | same input both modes: identical HardRules output; aggressive plan ⊇ standard plan's SAFE/NO_LOSS items |
| `ClassifierStubPlanTest` (H3) | classifier stub "meme 0.99" for all → no new SAFE/NO_LOSS items, DDRR unchanged |
| `AgeOnlyTest` (H10) | files differing only in age/size → identical tiers |
| `ClassicParityTest` (A14) | §6.3 |
| `ReasonCatalogTest` | all `PL_*` and new codes present with sentences |
| `DatasetPlanTest` (dataset present) | `evaluate.py --gate phase5` in both modes, and the five target runs |

---

## 11. Phase A adapter outline (`com.storageplanner.app.v2.V2Adapter`, android-free; ARCHITECTURE §13)

```java
public static Adapted adapt(V2Store store, ActionPlan plan, long now, int sdk)
// 1. result = new Scanner.Result(); one core.FileItem per PRESENT canonical v2 file (rel=path, abs=localPath, size, mtime, area, type,
//    sha256, phash/hasPhash, sharp, width, height); apps -> core.AppItem from InstalledApp (pkg, label, codeBytes, dataBytes, cacheBytes,
//    lastUsed, isSystem, hasLauncher, isEssential, isGame; unknown -1/false where classic has no unknown, only for display).
// 2. ub = new UnitBuilder(emptyResult, new Estimates(), sdk); ub.units.addAll(v2Units); ub.det.duplicateOf <- exact groups (copy -> keeper)
// 3. per subject: Unit.forFile(item) / Unit.forCache(app) / Unit.forApp(app); one Option per v2 rec of that subject:
```

| v2 rec | `Option.action` | `Option.risk` | `technique` | in `notPlannable`? |
|---|---|---|---|---|
| SAFE DELETE | `delete` | R0 | `"Safe · " + label(primary code)` | no |
| SAFE `CLEAR_APP_CACHE` | `clear_cache` | R0 | `"Safe · App caches"` | no |
| NO_LOSS DELETE | `delete` | R1 | `"No loss · " + label` (e.g. "No loss · Identical copies") | no |
| NO_LOSS `ARCHIVE_APP` | `archive_app` | R1 | `"No loss · Archive unused apps"` | no |
| NO_LOSS COMPRESS (lossless) / ARCHIVE | `v2_compress` / `v2_archive` | R1 | `"No loss · Smaller without loss"` | no |
| YOUR_CALL DELETE | `delete` | R4 | `"Your call · " + label` | no (classic R4 = needs confirmation) |
| YOUR_CALL `UNINSTALL_APP` | `uninstall_app` | R4 | `"Your call · Unused apps"` | no |
| YOUR_CALL lossy COMPRESS (Phase 6+) | `v2_compress` | R3 | `"Your call · Smaller, slight quality loss"` | no |
| REVIEW | `v2_review` | R4 | `"Review · " + label` | **yes** |
| PROTECTED | `v2_protected` | R4 | `"Protected · " + label` | **yes** |

`Option.saved` = `expectedSavingBytes` (0 for REVIEW/PROTECTED, so `Unit.add` would drop them: the adapter adds them via a dedicated
display list `Adapted.displayOnly`, not as Options — `Unit.add` ignores `saved <= 0`). `Option.exact` = rec bytes measured. `Option.why` =
the reason sentences. Required checks in `V2AdapterTest`: five tiers distinguishable by `(action, risk, technique prefix)`; REVIEW/PROTECTED
never reach `Planner.tiered`; classic `Planner.tiered` over adapted units selects only SAFE/NO_LOSS for a target ≤ v2 `achievableBytes`;
**keepers**: classic `Unit.userDelete(DUPLICATES)` offers R1 deletion of any unit the user picks — the adapter must give keepers no unit
(or `Session.replan` must drop the copies' options when the user deletes a keeper), otherwise a user could delete keeper and copies
together; the test simulates it.

**REVIEW_2 F-13 (classic Planner knows no conflicts).** `core.Planner.tiered/bestWithin` choose one option per `Unit` independently; they
have no `requires`/`conflictsWith`. Handing them the raw v2 units would double count `cache:<pkg>` against the `S_APP_CACHE_FILE` files
under `Android/data/<pkg>/cache/**`, and `app:<pkg>` UNINSTALL against the files under that package (§3). The adapter therefore emits
only a **conflict-free** option set: it runs `UnitBuilder2`'s conflict resolution (§3 "app vs file subjects" rule) first and gives the
losing side no `Option` (display-only, reason "included in {app}'s cache/uninstall"). Removal options whose `requires` contain a file
that has any removal option are not emitted (cannot happen while keepers have no unit, HC13; asserted). `V2AdapterTest` adds: for random
targets, `Σ Planner.tiered(adapted).saved() ≤ Σ distinct file and app bytes` and equals v2 `achievableBytes` for `MAX_SAFE`.

---

## 12. ARCHITECTURE changes

| Id | Section | Edit |
|---|---|---|
| C-1 | §10 | replace the evaluator tier rules by §9 (label translation; acceptable-tier-aware precision/recall; exposure gate; strict no-loss recall; byte metrics replacing "relative error ≤ 0.05", which H2 makes unattainable because the dataset labels 78 COMPRESSED_FROM copies SAFE; scenario rule using acceptable_tiers — the current "PASS iff predicted == expected" and rank-based FAIL would FAIL S04 for burst siblings at YOUR_CALL although the dataset accepts YOUR_CALL) |
| C-2 | §11 Phase 5 row | replace "v2's SAFE+NO_LOSS bytes ≥ classic's R0+R1 bytes" by `ClassicParityTest` (§6.3) |
| C-3 | §3.3 | add `int platformSdk()` to `InstalledApps` (JVM: `--sdk`, default 34) |
| C-4 | §6 | add `N_ARCHIVE_FOLDER`, `PL_NEEDS_YOUR_CALL`, `PL_HASH_PENDING`, `PL_VALIDATOR_DROPPED` |
| C-5 | §2.12 | `ActionPlan` gains `long withYourCallBytes, achievableExactBytes; List<String> reasonCodes`; `PlanItem` gains `int priorityClass` |
| C-6 | §8 Planner bullet | replace "within the lowest feasible tier, greedy by benefit/expected-loss then prune" with §4.3 (SAFE always taken; lexicographic tier → priority class → cost/byte; prune + single-item swap) |
| C-7 | §13 step 4 | REVIEW/PROTECTED go to `Adapted.displayOnly` (they have 0 bytes and `Unit.add` drops 0-byte options); keeper/userDelete requirement of §11 |
| C-8 (REVIEW_2) | §10, §13 step 3 | `spec_violations` metric and two-sided translation (F-12); adapter emits a conflict-free option set (F-13); planner key is tier-first, swap within tier k only (F-11) |

---

## 13. Exit criteria (Phase 5), dataset `/home/user/sp-dataset` + `installed_apps.json` (+ `installed_apps_ext.json` for app subjects)

| Metric | Gate | Expected |
|---|---|---|
| DDRR | 0, both modes | 0 (needs PHASE_4 F-05 for `temp123.tmp`; the identical must_never_delete invoice pair is a dataset-generator bug the lead will fix — until then it shows as 1 DDRR hit and is reported as a known label defect, not a v2 failure) |
| exposure (any DELETE on must_never_delete) | 0, both modes | 0 |
| `spec_violations` (F-12) | 0, both modes | 0 |
| deletion precision SAFE / NO_LOSS / YOUR_CALL | ≥ 0.98 / ≥ 0.98 / ≥ 0.80 | ≈ 1.0 / ≈ 1.0 / ≈ 0.95 |
| deletion recall (acceptable-aware) | ≥ 0.90 | ≈ 0.95 (misses: collage/unlabelled edits do not count; misses are memes/forwards without a copy relation, which have `SAFE` expected only when they are exact/compressed copies) |
| no-loss recall (strict) | ≥ 0.90 | ≈ 0.98 (exact 126, thumbnails 80, statuses, trashed) |
| keeper agreement | ≥ 0.95 | ≈ 0.98 |
| storage: dangerous / wrong / byte_recall | 0 / ≤ 2 % / ≥ 0.90 | 0 / ≈ 0 / ≈ 0.97 |
| plan consistency | 0 violations | |
| targets 1/5/10/20 GB/max | §9.2 row | |
| scenarios 1–11 | no FAIL; ≥ 8 PASS | 8 PASS, 3 CAUTIOUS |
| ClassicParityTest | `missed = 0`, bytes gate | exempt: SENT, TELEGRAM (100 files), older APK, WA_BACKUP |
| reason completeness | 100 % | |
| all §10 tests, `ForbiddenApiTest` | pass | |
| runtime | RECOMMEND + PLAN ≤ 10 s on the dataset (JVM), excluding on-demand hashing | |

---

## 14. Design choices

| # | Choice | Rejected alternative | Why |
|---|---|---|---|
| P5-1 | Lexicographic greedy (tier → spec priority class → cost/byte) with prune and a single-item swap | Knapsack DP / ILP | No solver library; DP memory infeasible at 100 k units; costs are uncalibrated so exactness buys nothing; the one realistic greedy failure (two large items vs one) is fixed by the swap, and a brute-force test bounds the rest. |
| P5-2 | SAFE items always taken, whatever the target | Minimum intervention for SAFE too | Zero expected loss by construction; matches classic (`bestWithin(R0)` is always taken), so users see the same behaviour. |
| P5-3 | Keepers are never plan units; a removal depending on a file blocks that file's own removal (first-come by priority) | Allow both and re-justify later | Simple, deterministic, provably keeps one copy; the bytes given up are small (a chat copy whose twin is being removed). |
| P5-4 | YOUR_CALL items enter a target plan only when SAFE+NO_LOSS cannot reach it, labelled `PL_NEEDS_YOUR_CALL` | Silent inclusion; or never including them | Spec 10.2 "never raise risk silently"; the user still sees what reaching the target would take. |
| P5-5 | Evaluator translates dataset tiers to spec tiers instead of changing v2 to match the dataset | Re-tier exact copies as SAFE and WhatsApp copies as SAFE | Spec 9.4 defines NO-LOSS as verified identical copies, and H2/A9 forbid SAFE for similar-only copies; the dataset's own acceptable tiers already allow YOUR_CALL for those copies. |
| P5-6 | A14 measured per item with an explicit exemption list | Literal byte comparison | Classic's R1 includes evidence v2 rejects on purpose (pHash-only copies, cloud media, unsigned-checked older APKs, encrypted backups); the literal test would force v2 to copy classic's unsafe calls. |
| P5-7 | No QUARANTINE choice in the planner | Planner chooses quarantine vs delete | Quarantine frees nothing until purge and is an execution safety mechanism (spec 11.2); Phase 9 decides it per policy. |
| P5-8 | Hash on demand before a DELETE rec becomes plannable | Hash at execution only | V3 needs the hash stored at recommendation time; otherwise a change between plan and execution would be undetectable for files Phase 2 never hashed. |
