# Phase 4 notes (implementer)

Status: built, `bash tools/v2eval/run_tests.sh` → 28 suites, 0 failures. Not committed.

## What exists

| Where | Classes |
|---|---|
| `v2/model/` | `Tier`, `Action`, `EvidenceFamily`, `UsageClass`, `Signal`, `Interval`, `Assessment` (ARCH 2.9 + PHASE_4 1.2), `Candidate`, `Constraint`, `Verdict`; `Recommendation` gained the ARCH 2.11 fields (codec still persists key fields only) |
| `v2/content/` | `ContentClassifier` (plug point), `Classification`, `RuleClassifier` (PROV, reliability 0.6) |
| `v2/risk/` | `FileContext`, `GraphView`, `ContextBuilder`, `UsageClassifier`, `Types`, `Keywords`, `SignalExtractor`, `UtilityEstimator`, `LossEstimator`, `RecoverabilityEstimator`, `ConfidenceEstimator`, `IrreversibilityEstimator`, `BenefitEstimator` (incl. compression ratios), `ExpectedLoss` (incl. action utility), `HardRules`, `CandidateRules`, `Assessor`, `RiskConfig` |
| `v2/plan/` | `TierAssigner`, `PresentationPolicy`, `ReasonCatalog`, `ProvisionalRecommendations` |
| tests | `ScenarioFixtureTest` (scenarios 1–11, both modes), `HardRulesTest` (HC1–HC15 ±, constraints/check agreement), `EstimatorsTest` (U, L, R, C, I, B, EL), `RiskPropertyTest` (invariants a–f, unknown-input, policy invariance, classifier stub, decision-table rows 1–11 + T, reason catalogue) |
| CLI / eval | `V2Cli --assess [--mode standard|aggressive]` writes `files[].assessment` and provisional `recommendations`; `evaluate.py --gate phase4`, `evaluate.py --summary --result R` (no manifest) |

## Deviations

| # | Design said | Done | Why |
|---|---|---|---|
| E-1 | stage `ASSESS` in the pipeline | `Assessor.run(store, apps, now, policy, cfg)` called by `V2Cli` after the pipeline | budget; same purity/idempotence, commits every 500 changed files |
| E-2 | `HardRules.check(FileRecord, Assessment, GraphView, Action, Tier)` | `check(FileContext, Assessment, Candidate)` | candidate-level rules (HC6/7/8/10/12) need the candidate's families/cites; HC15 lives in `TierAssigner` so `HardRules` has no policy input (H7) |
| E-3 | contradiction: opposing family not in the candidate's families | also when the opposing signal is a PATH_KEYWORD (record/sentimental), and never for structural candidates | spec's own example ("Y_CHAT_COPY but the name says invoice") is PROV vs PROV; structural exclusion keeps A19 (renamed identical invoices stay NO_LOSS). Side effect: `com.specimen.bank_v3.1.0.apk` (older, same signer) is REVIEW `R_CONTRADICTORY` because of "bank" |
| E-4 | APP multiplier ×0.3 | ×0.5 | the family range [0.5, 2] of §1.3 clamps it |
| E-5 | HC13 transforms forbidden "while any member has a removal candidate" | forbidden for every keeper | simpler, more restrictive |
| E-6 | R row 4 "archive not itself a removal candidate" | archive eligible, not a non-keeper exact member, not in temp/thumbnail/cache areas | verdicts are per file; no fixed point |
| E-7 | R row 6 "AMBIGUOUS pair to a larger image" | not read (`pair_checks` not consulted) | budget; such files fall to rows 8/9 (no removal either way) |
| E-8 | aliases: PHASE_3 canonical choice | canonical = smallest fileId per (volume, physicalKey) | no GraphView from Phase 3; a wrong pick only drops a verdict (safe direction) |
| E-9 | split APK bundles (`N_INSTALLER_INSTALLED` with bundle) | split APKs get no installer candidate | bundles are Phase 5 |
| E-10 | `Y_OLDER_INSTALLER` fallback | none when `sameSigner == UNKNOWN` (KEEP) | no catalogue sentence fits |
| E-11 | evaluator: cumulative gates | `--gate phase4` runs the phase-1 check + phase 4 only | phase 2/3 gates are run separately on the same snapshot |
| E-12 | evaluator: scenario/precision scoring | inside a set of byte-identical non-mnd manifest files, a v2 keeper different from the manifest's swaps the expectations (27 swaps, PHASE_5 9.2 "or a member equal to it by SHA"); raw mnd checks never use it | Phase 3 keeper order (`G_KEEPER_OLDEST`) keeps `WhatsApp Images/Sent/…` older copies where the dataset keeps the newer received one |
| E-13 | delete recs need a full hash (V3) | delete recs on files with `sha256 == null` carry `reasonParams.hashPending = "true"` | hash on demand is Phase 5 |

## Results (`/home/user/sp-dataset`, `--now 1794500000000`)

DDRR 0/178, exposure 0, spec_violations 0, both modes. Deletion precision SAFE 124/124, NO_LOSS 116/116; YOUR_CALL 0 recs.
Scenarios (standard): PASS S01 S02 S03 S04 S07 S09; CAUTIOUS S05 S06 S08 S10 S11; FAIL none. Aggressive: S10 PASS too.
Assessment JSON byte-identical across runs; ASSESS ≈ 1.1 s. Photo corpus (268 real photos): KEEP 209, REVIEW 59 (`R_SIMILAR_SHOT`),
0 delete-type recommendations.

## For Phase 5

* **No YOUR_CALL fires on the dataset.** `utility > 0` with `B0 = 50 MiB` needs `Bn > EL_pess`; a chat copy (EL_pess ≈ 0.05) needs
  ≥ 2.6 MB, an older APK ≈ 2 MB, camera-pocket shots never pass `tYourCall`. S05/S06 copies are therefore KEEP (standard) /
  REVIEW `R_UNCERTAIN_COPY` (aggressive). The fixtures show YOUR_CALL works for large files. `B0` (owner preference) is the knob.
* Keeper order disagrees with the dataset on 27 exact groups (see E-12) — a Phase 3 `KeeperSelector` question for the lead.
* Record keepers of exact groups are `P_UNIQUE_RECORD` PROTECTED (their own group's copies do not count, R row 3).
* `Verdict` carries `row`, `chosen`, `transform`, `hardBlocks`; recommendations are provisional (`planVersion 0`, `provisional: true`).
* Not done: `UnknownInputPropertyTest` is folded into `RiskPropertyTest`; must_never_delete coverage metric (178/178 with
  L.high ≥ 0.5 …) and the `P_UNIQUE_RECORD ≥ 0.6 of S02` row are not in the evaluator (S02 passes: 34/34 acceptable);
  `Keywords` has no Devanagari entries; `R_OLD_DOWNLOAD` untested on the dataset.
* Every number (λ table, L factors, rv rows 5–7, `tYourCall`, `cYourCall`, `B0`, C components, keyword lists) is an uncalibrated heuristic.
