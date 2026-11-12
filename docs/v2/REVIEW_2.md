# REVIEW_2 — independent review of PHASE_3, PHASE_4, PHASE_5 (design)

Reviewer: independent design agent, 2026-11-12. Inputs: PHASE_3/4/5.md, ARCHITECTURE.md, PHASE_2.md (field availability),
MANAGER_LOG (A15–A17), spec §6, 8, 9, 10, 16, the dataset (`/home/user/sp-dataset/manifest.json`, `scenarios.json`, sampled by
script), classic `core/{Unit,Option,Planner,Classifier}.java`.

Fixes are applied in place in the phase documents, tagged `REVIEW_2 F-nn`. ARCHITECTURE.md got the phase documents' "ARCHITECTURE
changes" (A-1…A-9, B-1…B-9, C-1…C-8) as additions/annotations only; nothing Phase 1/2 uses was renamed or removed.

## Rulings check

| Ruling | Status |
|---|---|
| R1 / A15 (per-item parity, exemption list naming spec rules) | PHASE_5 §6.2–6.3 implement it; two rows named no spec rule (older APK, aliases) — fixed (F-14), list made closed |
| R2 / A16 (spec tiers authoritative, translation never weakens mnd) | DDRR/exposure/scenario FAIL already read raw tiers. But only v2's side was translated, so a NO_LOSS delete of a WhatsApp copy still scored "correct" — fixed with a two-sided translation and a `spec_violations = 0` gate (F-12) |
| R3 / A17 (keeper = label, not PROTECTED) | PHASE_4 §9.2 correct; ARCHITECTURE §6 and §12 row 3 still said PROTECTED — fixed (B-3 applied) |

## Findings

| Id | Sev | Document § | Problem | Required change | Status |
|---|---|---|---|---|---|
| F-05 | BLOCKER | PHASE_4 §3.1 row 3, §9.2 `S_TEMP_FILE`, §6 | `TEMP` = classic `area temp`, which is decided by the **extension** (`.tmp/.part/.crdownload`). That's a name (PROV), not a structural fact, and the contradiction rule explicitly exempts S_TEMP_FILE. Dataset: `Download/temp123.tmp` is a complete PDF bank statement, must_never_delete → SAFE DELETE → DDRR ≠ 0, S02 FAIL. It also breaks ARCHITECTURE §2.11 ("structural = location **and** type") | TEMP only when the signature type is unknown/non-content or the file is TRUNCATED; otherwise classify by the detected type + `R_NAME_CONTENT_MISMATCH` | FIXED |
| F-06 | BLOCKER | PHASE_4 §9.2 `N_EXACT_DUPLICATE` | Dataset has two byte-identical invoices with different names (`Invoice_20210607_056.pdf`, `Invoice_20220109_055.pdf`), both must_never_delete. The design gives one an NO_LOSS DELETE → DDRR ≠ 0, so the Phase 4/5 "DDRR 0 expected" claim is false | For DOCUMENT/DOCUMENT_RECORD usage, NO_LOSS requires equal NameStem (after copy suffixes are stripped); otherwise REVIEW `R_SAME_CONTENT_DIFFERENT_NAME` ★. Bank-statement copies (`… (1).pdf`) are unaffected | FIXED (dataset question → L-1) |
| F-01 | MAJOR | PHASE_3 §4.2 | The MIH pigeonhole argument is correct (4 bands, radius 1 ⇒ all pairs ≤ 7 bits), but the 4 096-entry bucket truncation and the 64/32/32 per-image caps silently void "finds every pair". Truncated images are then reported `perceptualStatus = TRUE` ("compared") instead of unknown. The cost claim also ignores bucket size: with template screenshots at Hamming 0 the worst case is n·68·4096 popcounts | Bucket cap 512, budgeted; any truncation or cap hit → `perceptualStatus = UNKNOWN`; test with 1 000 identical hashes | FIXED |
| F-02 | MAJOR | PHASE_3 §8.2 BURST; PHASE_4 §5.1 row 5 | BURST = union–find connected components of weak `NEAR_SCENE` verdicts (ssim64Mean ≥ 0.25 within 120 s) ⇒ chains A~B~C merge a whole photo walk into one "burst" with one keeper (REVIEW in standard mode, YOUR_CALL in aggressive). The same chaining applies to CONTENT_EQUIVALENT members that reach the keeper only through another member | Complete linkage to the keeper (own NEAR_SCENE check with the keeper and Δt ≤ 120 s; the rest re-seed). R row 5 needs the member's own VERIFIED edge to the keeper | FIXED |
| F-03 | MAJOR | PHASE_3 §11.2, §15 | Thresholds are fitted on the dataset's labelled pairs and the exit gates are measured on the same pairs (leakage). Leave-one-transform-out alone does not cover this | Deterministic 2-fold split by source original / HN group; fit on fold 0; accuracy gates on fold 1; safety gates on all pairs + Drive corpus (veto only, never used for fitting) | FIXED |
| F-04 | MAJOR | PHASE_3 §11.3, §15; ARCH §10 | Exact truth = closure of `EXACT_DUPLICATE` labels. The unlabelled identical invoice pair (F-06) makes P = 1.000 unreachable for a correct implementation (measured: 129 labelled pairs, 1 unlabelled) | Truth = equal manifest `sha256` | FIXED |
| F-07 | MAJOR | PHASE_4 §6, HC15 | `C = min(c_extract, …)` and HC15 needs C ≥ 0.9 for NO_LOSS, so exact duplicates of any file with `extractionConfidence < 0.9` (videos, PARTIAL extractions) are blocked. That contradicts §13 ("exact rules reach 1.0 by construction") and loses no-loss recall | Structural candidates ignore `c_extract` and `c_avail` | FIXED |
| F-11 | MAJOR | PHASE_5 §4.3 | (a) `key` sorted by priority class before tier, so class-6 YOUR_CALL deletions came before a class-7 NO_LOSS archive in the YOUR_CALL pass: risk goes up although a lower-risk option is unused (spec 10.2). (b) `swap` could replace NO_LOSS items plus YOUR_CALL items with one larger YOUR_CALL item (lower "cost", more risky bytes). Counterexample: target 1 GB; NO_LOSS N 0.5 GB (cost 0.001), YC A 0.5 GB (0.05), YC S 1.0 GB (0.04) → swap picks S alone | Key = (tier, class, cost/byte, …); swap only among tier-k options, with R computed after the lower-tier selection | FIXED |
| F-12 | MAJOR | PHASE_5 §9.1 | R2 is only half done: dataset `SAFE` + acceptable `[SAFE, YOUR_CALL]` on WhatsApp/resized copies means a spec-violating NO_LOSS delete still counts as correct | Cap the expected side for similar-only copies at YOUR_CALL; add `spec_violations = 0`; mnd checks run before any translation | FIXED |
| F-13 | MAJOR | PHASE_5 §11 | `core.Planner.tiered/bestWithin` pick per unit with no conflicts or requires. Adapted `cache:<pkg>` plus its cache files (and `app:<pkg>` uninstall plus its files) would be double counted | Adapter emits a conflict-free option set (UnitBuilder2's resolution first); test that `saved ≤ distinct bytes` | FIXED |
| F-08 | MINOR | PHASE_4 §4.1 | No default for `L.high`, the bound every gate reads | high = min(1, mid + 0.2) unless stated | FIXED |
| F-10 | MINOR | PHASE_4 §9.3 row T | A lossless transform replaces the original. Nothing kept the original or excluded likely records | Row T needs `lossRange.high < 0.8`; `keepOriginal = until-purge` binds Phase 9 | FIXED |
| F-14 | MINOR | PHASE_5 §6.2 | Two exemptions named no spec rule; the list was open-ended | Spec rule per row; closed `EXEMPTIONS` list | FIXED |
| F-15 | MINOR (cut) | PHASE_3 §7 | InstallerLinker groups, CompanionLinker C1/C4, document DocVersionLinker feed no Phase 4/5 decision | Deferred to Phase 6; keep C2, C3, chat-backup rule | FIXED (cut) |
| F-16 | MINOR (cut) | PHASE_5 §1.1, §2 rule 3 | `N_ARCHIVE_FOLDER` (≤ 2 dataset subjects, new subject kind and executor) and `ApprovalCarry` (nothing approves before Phase 9) | Deferred to Phase 6 / Phase 9 | FIXED (cut) |
| F-17 | MINOR | PHASE_4 §8 | HC2 (identity) is listed second but has to run first (row 1 of §9.3 does). Cosmetic | Implement in §9.3 order | LEFT (no doc change needed) |
| F-18 | MINOR | PHASE_4 §9.2 `Y_LOW_QUALITY` | Single-family PIXEL YOUR_CALL. A near-blank photo of a white receipt page can match "contrast < 4" | Implementer: skip `Y_LOW_QUALITY` when usage ∈ DOCUMENT* or a record keyword hits (the contradiction rule mostly covers this) | LEFT (minor) |

Checked and found sound (no finding): G2 `Distinctness` matches ARCHITECTURE; aliases get no edge, no unit and 0 bytes; one keeper per
exact group, never in `redundantMembers`; HC13 blocks removal of any keeper. This rules out "each member deleted because the other is
the keeper", including across exact ↔ content-equivalent ↔ burst groups (checked by hand on the content-node collapse). Keeper eligibility
covers an inaccessible or unverifiable keeper (`Volume.mounted`, readable, hash current), and a changed keeper is caught by
`dependencyIds` fingerprints, staleness and execution V1–V7. Similarity never yields SAFE/NO_LOSS (HC6; only VERIFIED exact and backup
edges count, and BACKED_UP_BY is VERIFIED only on SHA-256). Unknown, encrypted and undecodable files get pessimistic highs, forced REVIEW
(HC9), and `perceptualStatus UNKNOWN` (never "unique"). Age is used only for U.mid/low, and no rule removes a unique document. Aggressive
mode changes only `PresentationPolicy`. U, L, R, C, I, B are separate, each has a range and unknown handling, and the evidence families
keep path/name/RuleClassifier in one family. The tier table is total (row 11) and deterministic. Scenarios 1–11 each map to a named rule
(S02 needs F-05). Phase 2 provides every field Phase 3–5 read: upright dims, contrast, EXIF DTO/software, NameDate, ApkInfo
install/signer, HashRecord.crc32, and archive entries for plain zips.

## Verdicts

| Document | Verdict |
|---|---|
| PHASE_3 | **Approved with the applied fixes** (F-01–F-04, cut F-15). |
| PHASE_4 | **Approved after the two BLOCKER fixes** (F-05, F-06, applied) and F-07/F-08/F-10. |
| PHASE_5 | **Approved with the applied fixes** (F-11–F-14, cut F-16). |

## Left for the lead

* **L-1** The dataset's two `Download/Invoices` PDFs are byte-identical although the note says "different numbers". That looks like a generator bug (the numbers were not rendered). *Recommendation:* fix `tools/v2dataset` so the hard-negative invoices differ, and keep F-06 anyway: it costs no dataset recall, and identical files with differently identified names are a real-world risk.
* **L-2** F-06 deviates from spec 9.4 ("verified identical duplicate → NO-LOSS") for documents whose names differ. *Recommendation:* accept it as a cautious deviation (REVIEW, not KEEP) and log it as a decision.
