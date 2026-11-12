# Phase 3 — implementation notes

Records where the implementation departs from `PHASE_3.md` (with its binding `REVIEW_2` edits), or had to choose. Not committed.

## Slices

| Slice | State |
|---|---|
| 1 Exact groups (SHA-256, G2 `AliasResolver`, `DuplicateGroup` records, keeper order §8.1, star > 64) | done (`ExactGrouper`, `KeeperSelector`, `AliasResolver`) |
| 2 Candidates (multi-index pHash 4 bands radius 1, 120 s capture window, name stems; F-01 caps → UNKNOWN) | done (`CandidateGenerator`) |
| 3 Stage-4 confirmation (coarse 64-grid SSIM, fine 256 grid, chroma16, dihedral for stem pairs), `pair_checks` table, budget/resume | done (`StructSig`, `VerdictRules`, `Perceptual`) |
| 4 Edge typing (T1–T5, provenance-gated EDITED_FROM, F-02 bursts as star, CONTAINED_IN, BACKED_UP_BY verified by inflate + SHA-256), C2/C3 companions, WhatsApp chat-backup VERSION_OF | done (`Perceptual`, `ArchiveMatcher`, `CompanionLinker`) |
| 5 Near groups over collapsed exact groups (CONTENT_EQUIVALENT, BURST, EDIT_FAMILY), `memberBytes`, no double counting | done; `recoverableBytes` stays unknown (Phase 5) |
| 6 `calibrate.py` (fold 0 fit, fold 1 report), `evaluate.py --gate phase3 --fold`, `V2Cli --graph / --pairs-out` | done |

## Deviations and choices

| # | Design said | Done | Why |
|---|---|---|---|
| D-1 | ~25 classes in `v2/graph/` incl. `GraphView`, `GraphCoverage`, `NodeRef`, `MultiIndexHash`, `TimeIndex`, `PerceptualGate`, `EdgeTyper`, `NearGrouper`, `BackupVerifier`, `InstallerLinker`, `DocVersionLinker` | 14 classes: `GraphStage`, `GraphConfig`, `Node`, `AliasResolver`, `ExactGrouper`, `KeeperSelector`, `NameStem`, `CandidateGenerator`, `StructSig`, `VerdictRules`, `Perceptual` (gate, stage 4, typing, near groups), `ArchiveMatcher` (+ verifier), `CompanionLinker`, `Edges` | Budget. Behaviour is the design's except the rows below. **`GraphView` is not written**: Phase 4 must add it (or read the tables directly). `perceptualStatus` is stored in `FeatureRecord.extra["graph.perceptual"]` (TRUE/FALSE/UNKNOWN) and in the CLI file entry. `v2/content/` interfaces not written. |
| D-2 | `PairCheck.metrics` doubles | metrics rounded to 6 decimals before the verdict | The snapshot stores 6 decimals; a resumed run must give the same verdict. |
| D-3 | V-rows in order V2…V8 | V6 (AMBIGUOUS) is evaluated before V4/V5 | Otherwise a near-miss copy (inside the ambiguity band) is always caught by V5 LOCAL_EDIT first and V6 is unreachable. |
| D-4 | `decode(…, 256, …)` | decode at long edge 1024, then box-average to 256 inside `StructSig` | The JVM decoder subsamples (pixel skipping) then scales bilinearly; at 256 this aliased differently for a 4000 px original and a 1600 px copy and the fine grid failed real WhatsApp copies (ssimFineMin 0.46). Costs ~2× decode time. Android `BitmapFactory` should get the same treatment. |
| D-5 | thresholds = probe values | calibrated (below) | §11. |
| D-6 | — | A pair whose two capture times (EXIF DTO or name stamp, second precision) are known and differ is never SAME_CONTENT/AMBIGUOUS; with a time-window source it is NEAR_SCENE before any edit verdict | Video-player screenshots 2 s apart and synthetic photos reusing one source were typed as content-equivalent. Implements the §15 Drive rule "0 SAME_CONTENT across capture seconds" structurally. |
| D-7 | original order: pixels → bytes/pixel → camera-native | pixels → camera-native → bytes/pixel | A JPEG→PNG conversion has more bytes per pixel and was taken as the original. |
| D-8 | provenance list §5.3 | NAME_STEM_EQUAL needs a 6+ digit stamp in the stem; EXIF_DTO_EQUAL ignored when the two name dates disagree by a day; no EDITED_FROM from/to a thumbnail location, between two derived names (edit/copy suffixes) or two editor-folder files; edit direction: the non-derived member is the original first, then earlier `createdAt` | `IMG_0001` in two folders, synthetic EXIF collisions, thumbnails matching an edit in band B and sibling edits produced 15 false EDITED_FROM. |
| D-9 | content-equivalence truth = labelled pairs + exact closure | evaluator compares CE as clusters (transitive closure both sides, pure byte-identical pairs left to the exact rows); also prints a precision with *unlabelled same-source pairs* excluded (both manifest `source.id` equal, neither labelled EDITED_FROM) and a recall with undecodable (WebP/HEIC) members excluded | A thumbnail of an original and a WhatsApp copy of it are content-equivalent but unlabelled; the dataset also has unlabelled re-uses of one source picture (e.g. `genai_teacup_1` as a WhatsApp forward and a camera photo). The gate row uses the same-source-excluded precision — **lead to confirm**. |
| D-10 | CONTENT_EQUIVALENT = connected components | components are seeds split by complete linkage to the keeper (like F-02), and CE edges are stored as a star member → keeper | A band-B thumbnail matched both an original and its annotated edit, chaining the edit into the original's CE group. |
| D-11 | `backup false_verified` = subject not in `backup_manifest.intact` | evaluator reads the archive member with `zipfile` and compares SHA-256 | The sticker-pack zip's members are byte-identical to WhatsApp images; they are genuine verified backups. |
| D-12 | BACKED_UP_BY name+size (crc unknown) branch | not written | Every plain-zip entry has a CRC (Phase 2). |
| D-13 | `InstallerLinker`, C1/C4, document `DocVersionLinker` | deferred (F-15 allows it) | — |
| D-14 | ambiguity bands fitted (step 3), edit/scene F1 fit (step 4), leave-one-transform-out (step 6) | not done; bands keep the initial values | Budget. |
| D-15 | `GraphStageResumeTest`, `GraphDeterminismTest`, `DatasetGraphTest`, `InstallerCompanionTest`, `NearGrouperTest` (burst-of-5 `_COVER`) | not written; covered partly: `PerceptualTest` re-run (same checks, identical edges/groups, 0 decodes), `ExactGrouperTest`, `ArchiveMatcherTest` | Time. |

## Calibration (F-03)

`V2Cli scan <dataset> --pairs-out pairs.jsonl --manifest manifest.json --random 600` → 3,542 pairs (generated, every labelled
image pair, all hard-negative within-group pairs, 600 random equal-aspect pairs). `calibrate.py` fits on fold 0 (2,552 pairs),
reports fold 1 (720). Objective: zero NEG/EDIT/SCENE pairs SAME_CONTENT, then max POS_SAME recall, then each threshold placed at the
tight end relaxed by ≤ 2 steps (never tighter than the reviewed initial value while that is feasible).

| threshold | initial | chosen |
|---|---|---|
| T_SAME_HAM | 6 | 6 |
| V2_SSIM_MEAN | 0.985 | 0.958629 |
| V2_SSIM_MIN | 0.90 | 0.816365 |
| V2_DIFF_MAX | 6.0 | 11.5 |
| V2_CHROMA | 2.5 | 2.5 |
| V2_FINE_SSIM_MIN | 0.93 | 0.907909 |
| V2_FINE_DIFF_MAX | 9.5 | 17.25 |

Fold 0: 0 false SAME, POS_SAME recall 0.781; fold 1: 0 false SAME, recall 0.793; all pairs 0 false SAME (2,860 negatives incl.
2,260 hard-negative pairs). Hard-negative screens stay ≤ 0.77 on ssimFineMin, so 0.908 keeps a margin. `MATCHER_VERSION = 3`.

## Dataset results (`evaluate.py --gate phase3 --fold 1`, held-out)

exact P 1.000 / R 1.000 (129 pairs); CE precision 0.976 strict, 0.992 same-source excluded; **CE recall 0.909 (FAIL, gate 0.95)**,
0.960 without WebP members; hn_false_pairs 0; near-dup P 0.983 strict / 0.994, R 0.874; EDITED_FROM P 1.000 (R 0.52);
VISUALLY_SIMILAR P/R 1.0/1.0; backup false_verified 0, BACKED_UP_BY recall 17/17; images with relationships unknown 56/2142
(48 WebP + 8 others). GRAPH: 27 s cold (670 decodes at 1024), 0.13 s warm. Remaining CE misses: WebP conversions (no JVM decoder), three
band-B thumbnails whose coarse diff exceeds the fit, three WhatsApp re-forward pairs with ssimFineMin 0.88 (no such pair in fold 0).

Drive corpus (268 photos): 44 BURST groups + 1 edit family, 59 VISUALLY_SIMILAR edges, 0 SAME_CONTENT pairs, HEIC 36/36
`perceptualStatus = UNKNOWN`; burst co-membership vs `bursts_by_name.json` (41 of 49 groups resolvable by name stamp): pair P 0.818,
R 0.358 (the label is weak; complete linkage to the keeper plus HEIC members being unknown cut recall). GRAPH 19 s.

## For Phases 4–5

* Interchangeable = `EXACT_DUPLICATE` VERIFIED (G2 proven distinct) or `BACKED_UP_BY` VERIFIED only. Exact edges are UNVERIFIED when
  `physicalKey` is missing on a side; hard links/aliases get no edge and no group membership.
* Exact groups only contain files with a *current* SHA-256; Phase 2 hashes every size collision, so every exact member is hashed.
  Files with a stale/missing hash are simply absent from groups ("unknown", not "different").
* `keeperId == null` + `keeperFallback` → no group-based recommendation. Keeper reasons: `G_KEEPER_PROTECTED/CANONICAL/NAME/OLDEST/TIEBREAK`.
* CE groups (`n:c:`) and their edges are a star to the keeper; edits are re-pointed to the CE keeper; EDIT_FAMILY never yields
  redundant members. BURST (`n:b:`) is a star of VISUALLY_SIMILAR — context only, never interchangeable.
* `perceptualStatus` UNKNOWN (HEIC/WebP on the JVM, decode failures, flat images, truncated searches, pending checks) must read as
  "relationships unknown", never "unique". AMBIGUOUS pair checks are in `pair_checks` (no `GraphView.ambiguousWith` yet).
* `BackupRecord` states: VERIFIED (SHA-256), CORRUPT (CRC mismatch), MISSING (content differs), UNKNOWN/POSSIBLE (not verified).
* Chat-backup edges: `msgstore-DATE…crypt14 VERSION_OF msgstore.db.crypt14` (UNVERIFIED, context for `Y_OLD_CHAT_BACKUP`).
