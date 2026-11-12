# Phase 3 — Exact duplicates and the relationship graph

Status: design, for review. Author: architect agent (phases 3–5). Spec: §6 (graph, six duplicate stages, group decisions),
§8.3 (backup evidence), §16.2 (duplicate cluster accuracy). Binding context: ARCHITECTURE §2.6–2.8, §7 (H1, H2, H8),
§8 (outline), G1/G2; lead decisions A5, A9, A10. Edits this phase needs in ARCHITECTURE are listed in §14, not applied.

Everything below is Java 8, no libraries, no `android.*`, deterministic (ARCHITECTURE §0).

---

## 0. Deliverables

| Where | What |
|---|---|
| `v2/model/` | `PairCheck`, `PairVerdict`, `PairMetrics`, `CandidateSource`, `Distinctness`, `NearGroupKind`; `DuplicateGroup` gains `kind`, `keeperFallback` (§2.4); `Relationship` unchanged |
| `v2/store/` | new table `pair_checks` (`Table<PairCheck>`), codec, snapshot order after `backup_records` (§14 A-1) |
| `v2/graph/` | `GraphStage`, `GraphConfig`, `NodeRef`, `AliasResolver`, `ExactGrouper`, `NameStem`, `MultiIndexHash`, `TimeIndex`, `CandidateGenerator`, `PerceptualGate`, `StructSig`, `StructSigCache`, `StructuralComparator`, `VerdictRules`, `EdgeTyper`, `ContentClasses`, `NearGrouper`, `KeeperSelector`, `ArchiveMatcher`, `BackupVerifier`, `InstallerLinker`, `CompanionLinker`, `DocVersionLinker`, `GraphView` (read-only API for Phase 4), `GraphCoverage` |
| `v2/content/` | interfaces only: `EmbeddingProvider` (stage 5 plug point, §5.5), `ContentClassifier` (used by Phase 4) |
| `jvmtest/.../v2test/` | `Fx*` fixture builders for pairs (§12), tests of §12, `V2Cli pairs` sub-command (§11.1) |
| `tools/v2eval/` | `calibrate.py` (stdlib only, §11), Phase 3 metrics in `evaluate.py` (§11.3) |

Pipeline: `GRAPH` (stage order 5) is implemented now and does stages 1–4 and 6 of spec §6.2 itself (§1). `T6_DETAIL`
(order 8) stays empty until Phase 6 (video sampling).

---

## 1. The six duplicate-detection stages (spec 6.2) mapped to code

| Spec stage | Class | Input | Output | Cost |
|---|---|---|---|---|
| 1 SHA-256 exact | `AliasResolver` + `ExactGrouper` | `files.bySha256`, `HashRecord` | `EXACT_DUPLICATE` edges, exact `DuplicateGroup`s (`x:`) | memory only |
| 2 perceptual hash | `CandidateGenerator` (`MultiIndexHash`, `TimeIndex`, `NameStem` index) | `phash`, capture time, names | `CandidatePair` stream (deduplicated, capped) | O(n · probes) |
| 3 dimensions / aspect | `PerceptualGate` | `ImageInfo` upright dims, Hamming | gated pairs (or rejected with reason) | O(1) per pair |
| 4 structural / visual | `StructuralComparator` + `VerdictRules` | `StructSig` of both images (one decode each) | `PairCheck` (metrics + verdict), persisted | 1 decode per image, budgeted |
| 5 semantic embedding | `EmbeddingProvider` (null in Phases 3–6) | — | nothing until Phase 7; can never produce an interchangeable edge (spec 6.2, G1) | — |
| 6 manual review | `VerdictRules` → `AMBIGUOUS` | pair checks inside the ambiguity bands of §4.4 | `PairCheck.verdict = AMBIGUOUS`; Phase 4 turns it into `R_UNCERTAIN_COPY` (REVIEW) on the derived member only | — |

`GraphStage.run(ctx)` passes (all deterministic; ids and paths sorted ascending at every step):

```
A. snapshot   : load PRESENT FileRecords + FeatureRecords + HashRecords of the session's volumes into GraphInput (arrays, no copies kept beyond the stage)
B. exact      : AliasResolver -> ExactGrouper                                   (stage 1)
C. candidates : CandidateGenerator -> PerceptualGate                            (stages 2, 3)
D. detail     : for gated pairs without a current PairCheck, in priority order (§4.6): StructuralComparator    (stage 4, budgeted)
                ArchiveMatcher candidates -> BackupVerifier (inflate + SHA-256, budgeted)
E. assemble   : VerdictRules + EdgeTyper over current PairChecks -> perceptual edges; InstallerLinker, CompanionLinker,
                DocVersionLinker; ContentClasses -> NearGrouper -> KeeperSelector; write edges, groups, BackupRecords,
                FileRecord.duplicateGroupId (write only on change); delete stale edges/groups (Table.remove) (stage 6 marks)
F. coverage   : GraphCoverage counters into the session
```

Resume: D persists every finished `PairCheck` / `BackupRecord` in the same commit as nothing else (commit every 200 checks or 3 s).
A–C and E are pure functions of the store, so a killed or budget-exhausted stage re-runs them and continues D with the pairs that
still lack a current check. If D stops on budget, E still runs: pairs without a check produce **no** perceptual edge and are counted
as `pending` in coverage; the stage returns `BUDGET_EXHAUSTED` and the next run continues. Edges are rebuilt, never patched: E
computes the full edge set and diffs it against the table (put new/changed, remove absent).

---

## 2. Records

### 2.1 `PairCheck` (table `pair_checks`, key = `pairKey`)

```java
public final class PairCheck implements Record {
    public String pairKey;        // hex(SHA-256("pc1\0" + a + "\0" + fpA + "\0" + b + "\0" + fpB + "\0" + GraphConfig.MATCHER_VERSION))[0..32), a < b (fileIds)
    public String a, b;           // fileIds, a < b
    public Fingerprint fpA, fpB;  // fingerprints the check ran on (rule F1: a mismatch makes it stale; key changes too)
    public int matcherVersion;    // GraphConfig.MATCHER_VERSION; bump when any threshold or metric changes
    public int sources;           // CandidateSource bitmask that proposed the pair
    public int hamming = -1;      // pHash Hamming distance, -1 = a hash missing
    public PairMetrics metrics;   // null if no structural comparison ran
    public PairVerdict verdict;   // §4.4
    public String transform;      // null or "rot90","rot180","rot270","mirror","mirror+rot90",... (dihedral, §4.3)
    public String note;           // short machine reason for NOT_COMPARABLE / errors (no file content)
    public long checkedScanId;
}
public enum CandidateSource { PHASH_MIH /*1*/, TIME_WINDOW /*2*/, NAME_STEM /*4*/ }   // bit = 1 << ordinal
public enum PairVerdict { SAME_CONTENT, LOCAL_EDIT, COLOR_EDIT, GEOMETRIC_EDIT, NEAR_SCENE, DIFFERENT, AMBIGUOUS, NOT_COMPARABLE }
public final class PairMetrics {           // all NaN = not computed
    public double ssim64Mean, ssim64Min, diff64Max;      // coarse grid (§4.2)
    public double ssimFineMin, diffFineMax;              // fine grid; NaN when the fine check does not apply (band B)
    public double chroma16;                              // mean |Δ(R−G)|+|Δ(B−G)| / 2 on a 16×16 RGB grid, 0..255
    public double fracBlocksSame;                        // fraction of coarse blocks with SSIM >= 0.95
    public int gridW, gridH, fineN;                      // geometry actually used
    public int band;                                     // 1 = A (both long edges >= 512 after upright), 2 = B
}
```
Size: ≈ 200 bytes per check in the snapshot; only gated pairs get one (typically ≤ 0.2 per image, §4.6).

### 2.2 Edge records (ARCHITECTURE §2.6, unchanged class)

Per type, what this phase stores (direction per ARCHITECTURE §2.6: `src TYPE dst`):

| Type | src → dst | `verification` meaning in Phase 3 | `confidence` |
|---|---|---|---|
| `EXACT_DUPLICATE` | lower fileId → higher (symmetric) | `VERIFIED` = both SHA-256 current (HashRecord.fp sameAs FileRecord fp) **and** `Distinctness.PROVEN_DISTINCT` (G2); `UNVERIFIED` = hashes current but distinctness unknown; never stored for `SAME_FILE` | 1.0 / 0.9 |
| `COMPRESSED_FROM` | smaller copy → original | `VERIFIED` = stage 4 verdict `SAME_CONTENT` on current fps | §4.5 |
| `THUMBNAIL_OF` | thumbnail → original | same | §4.5 |
| `VERSION_OF` (image↔image) | derived → original (§5.2) | same | §4.5 |
| `VERSION_OF` (documents, installers, chat backups) | newer → older (spec direction), see §7 | always `UNVERIFIED` (name/metadata evidence) | 0.5–0.8 |
| `EDITED_FROM` | edit → original | `VERIFIED` = stage 4 verdict `LOCAL_EDIT`/`COLOR_EDIT`/`GEOMETRIC_EDIT` **and** provenance evidence (§5.3); `UNVERIFIED` = provenance only (crop/collage hints) | 0.6–0.9 |
| `VISUALLY_SIMILAR` | lower → higher (symmetric), stored as a star: every non-keeper member → group keeper | `VERIFIED` = verdict `NEAR_SCENE` + time window | 0.6–0.9 |
| `CONTAINED_IN` | `archiveId!entryPath` → `archiveId` | `VERIFIED` (structural fact from the listing) | 1.0 |
| `BACKED_UP_BY` | fileId → `archiveId!entryPath` | `VERIFIED` only at `level = SHA256_MATCH` (§6); else `UNVERIFIED` | 1.0 / 0.6 (CRC) / 0.3 (name+size) |
| `COMPANION_OF` | sidecar → primary | `UNVERIFIED` (name rule) / `VERIFIED` (package equality for obb/split) | 0.7 / 0.95 |
| `DEPENDS_ON` | — | not produced in Phase 3 (no evidence source yet) | — |
| `SEMANTICALLY_SIMILAR` | — | Phase 7 only; never interchangeable | — |

Every edge has ≥ 1 `Evidence`; evidence codes (closed list, `GraphConfig.EVIDENCE_CODES`, checked by a test): `SHA256_EQUAL`,
`PHYSKEY_DISTINCT`, `PHYSKEY_UNKNOWN`, `VOLUMES_DISJOINT`, `PHASH_DIST`, `ASPECT_DIFF`, `DIMS`, `SSIM64_MEAN`, `SSIM64_MIN`,
`DIFF64_MAX`, `SSIM_FINE_MIN`, `DIFF_FINE_MAX`, `CHROMA16`, `FRAC_SAME_BLOCKS`, `TRANSFORM`, `NAME_STEM_EQUAL`, `NAME_PATTERN_WA`,
`AREA_PAIR`, `CAPTURE_DT_MS`, `EXIF_DTO_EQUAL`, `EXIF_SOFTWARE`, `EDITOR_FOLDER`, `BYTES_RATIO`, `FORMAT_PAIR`, `PIXELS_RATIO`,
`ZIP_SIZE_CRC_EQUAL`, `ENTRY_NAME_EQUAL`, `ENTRY_SHA256_EQUAL`, `ENTRY_CRC_MISMATCH`, `APK_PKG_VER_SIGNER`, `APK_PKG`, `COMPANION_RULE`,
`DOC_STEM_RULE`, `BACKUP_NAME_DATE`. `structural = true` for SHA/PHYSKEY/VOLUMES/ZIP/ENTRY/APK codes, `false` otherwise.

### 2.3 `Distinctness` (G2)

```java
public enum Distinctness { PROVEN_DISTINCT, SAME_FILE, UNKNOWN }
// AliasResolver.distinct(FileRecord x, FileRecord y, Volume vx, Volume vy):
//  1. x.physicalKey != null && y.physicalKey != null: equal -> SAME_FILE, different -> PROVEN_DISTINCT
//  2. vx.volumeId != vy.volumeId && vx.fsUuid != null && vy.fsUuid != null && !vx.fsUuid.equals(vy.fsUuid)
//     && neither volume kind is SAF_TREE or DIRECTORY                       -> PROVEN_DISTINCT
//  3. otherwise                                                            -> UNKNOWN
```
Records with `SAME_FILE` form an **alias set**; `AliasResolver.canonical(set)` = member with a non-SAF access mode first
(`B_ALL_FILES` > `D_APP_PRIVATE` > `A_MEDIASTORE` > `C_SAF_TREE`), then smallest fileId. Non-canonical aliases are excluded from
every later stage, get no edge and are reported in `GraphView.aliasOf(fileId)`; Phase 5 never plans an action on an alias and counts
0 bytes for it.

### 2.4 `DuplicateGroup` additions

```java
public NearGroupKind kind;     // EXACT, CONTENT_EQUIVALENT, BURST, EDIT_FAMILY, INSTALLER (enum, new)
public boolean keeperFallback; // true = no eligible keeper by the primary rule; keeper chosen by fallback (§8) — Phase 4 treats such groups as REVIEW
```
`groupId`: `x:<sha256>` for EXACT, `n:<kind letter>:<smallest member fileId>` for the others (`c`, `b`, `e`, `i`).

---

## 3. Stage 1 — exact duplicates (`ExactGrouper`)

```
input: PRESENT records with sha256 != null, sizeBytes >= 1, HashRecord.fp sameAs record fp (else the hash is stale: treated as missing)
1. alias sets: group all PRESENT records by non-null physicalKey; sets of size >= 2 -> alias set (canonical per §2.3)
2. for each sha256 (ascending) with >= 2 canonical records:
     members = canonical records, sorted by fileId
     for every unordered pair (x,y): d = distinct(x,y)
     edge(x,y) EXACT_DUPLICATE: VERIFIED if d == PROVEN_DISTINCT, UNVERIFIED if UNKNOWN (SAME_FILE impossible after step 1
        unless a physicalKey is null on one side: then UNKNOWN)
     group "x:"+sha: exact=true, members, verification = VERIFIED iff all pairs VERIFIED, kind EXACT
     keeper = KeeperSelector.exact(members) (§8.1)
3. members whose hash is missing/stale but whose size equals the group's: no edge (unknown is not "different", not "same");
   Phase 4 sees them as "hash unknown" (no recommendation from this group)
```
Pairs inside a group: k·(k−1)/2 edges; groups larger than `MAX_EXACT_EDGES_GROUP = 64` members store a star (each member →
keeper) instead of the clique, flagged by evidence `"STAR"`; evaluator reads groups, not edges, for exact accuracy.
Zero-byte files never group (spec A: unknown content is not identical content; and 0 bytes has no recovery value).
Archive APKs and any other type are grouped the same way (spec 5.6 "duplicate installers" byte-identical case).

---

## 4. Stages 2–4 — perceptual matching

### 4.1 Eligibility (`PerceptualGate.eligible(f)`)

Image family, `hasPhash`, `decodeStatus == OK`, `uprightWidth/Height` known, `contrast >= 4.0` (flat images — black frames,
blank screenshots — have meaningless pHashes; they are excluded and counted as `flat` in coverage), not a non-canonical alias.
**Ineligible images (HEIC/AVIF/WebP on the JVM, decode failures, TOO_LARGE)**: no perceptual candidate is generated for them at all;
`GraphView.perceptualStatus(fileId) = UNKNOWN`. Phase 4 must read "no near-duplicate found" for these as *unknown*, never as unique
(A5, H4). Exact matching (§3) still applies to them.

### 4.2 Candidate generation (`CandidateGenerator`) — no O(n²)

Three independent sources, union, deduplicated by `(a,b)`, each pair carrying the bitmask of its sources:

| Source | Index | Guarantee | Per-image cap |
|---|---|---|---|
| `PHASH_MIH` | `MultiIndexHash`: the 63-bit hash split into 4 bands (16,16,16,15 bits); `HashMap<Integer,int[]>` per band; query probes each band's exact value and all values at Hamming 1 (17 probes per band) | finds **every** pair with Hamming ≤ 7 (pigeonhole: 4 bands × (1+1) − 1); pairs found with larger distance are kept only if ≤ `T_CAND = 7` | 64 nearest by Hamming, ties by fileId |
| `TIME_WINDOW` | images with a capture time of precision ≤ SECOND (`ExifInfo.dateTimeOriginal`, else `NameDate` with precision SECOND/MILLI), sorted; sliding window `|Δt| ≤ 120 s` | every pair within the window with Hamming ≤ `T_BURST = 14` | 32 nearest in time |
| `NAME_STEM` | `NameStem.of(name)` (§5.3) → `TreeMap<String, int[]>`; only stems ≥ 6 characters and groups ≤ 32 | every pair of eligible images in a stem group (no Hamming limit: rotations, crops) | 32 |

Cost for n = 100 000 eligible images: MIH = n × 4 × 17 = 6.8 M hash-map probes; bucket sizes are capped by the per-image cap (a
probe returning a bucket larger than 4 096 members is truncated to the 4 096 entries closest in fileId order and counted as
`bucketOverflow` — happens only for degenerate hashes, which §4.1 mostly removes). Time window and stems are O(n log n). Memory:
the index holds `long[] hash` + `int[]` ids ≈ 12 bytes per image plus buckets ≈ 4 × 4 bytes per image ≈ 3 MB for 100 k images.

### 4.3 Stage 3 gate (`PerceptualGate.gate(a, b)`) → pass or a reject reason (counted, not stored)

| Check | Rule | Reject reason |
|---|---|---|
| G-1 | not the same alias set; not already in the same exact group (exact wins; perceptual comparison of byte-identical files is pointless) | `SAME_FILE` / `EXACT` |
| G-2 | `ar = aspectA/aspectB` (upright); proceed to identity grid if `|ar − 1| ≤ 0.02`; else proceed **only** for `NAME_STEM` pairs (crop / rotation, §4.3b) | `ASPECT` |
| G-3 | Hamming ≤ `T_CAND` (7) for PHASH_MIH-only pairs; ≤ `T_BURST` (14) for TIME_WINDOW pairs; none for NAME_STEM pairs | `HAMMING` |
| G-4 | both upright long edges ≥ 64 px | `TOO_SMALL` |

**4.3b dihedral check** (NAME_STEM pairs only, aspect may be swapped): the comparator evaluates the 8 dihedral transforms of image
b's coarse grid (identity, rot90/180/270, mirror, mirror∘rot90/180/270) against a; the best transform by `ssim64Mean` is kept in
`PairCheck.transform`. Rotated/mirrored edits score Hamming 29–38 on the identity hash (measured, §13), so only name stems find them.

### 4.4 Stage 4 structural comparison (`StructSig`, `StructuralComparator`, `VerdictRules`)

`StructSig` per image (computed once per run, held in `StructSigCache`, an access-ordered `LinkedHashMap` LRU of 256 entries ≈ 20 MB):
`media.decode(vol, path, 256, Limits.MAX_PIXELS, wantArgb = true)` → upright luma `L256` (long edge ≤ 256, never upscaled), plus
`rgb16` = 16×16 box-averaged RGB of the same decode. A decode failure → verdict `NOT_COMPARABLE`, `note = decode status`.

Comparison of a (reference = the image with more pixels) and b:

1. **Grid.** Coarse grid: long edge 64, short edge `round(64 × short/long)` of *a*'s aspect (b resampled to the same grid by box
   averaging; b's transform applied first). Fine grid: `fineN = min(256, longEdge(L256 of the smaller image))`, same aspect.
   Band = A if both upright long edges ≥ 512, else B (thumbnail scale: the fine grid is meaningless, `ssimFine*` = NaN).
2. **Coarse metrics** over non-overlapping 8×8 blocks: per-block SSIM (C1 = (0.01·255)², C2 = (0.03·255)², population moments)
   → `ssim64Mean`, `ssim64Min`; per-block mean |Δ| → `diff64Max`; `fracBlocksSame` = share of blocks with SSIM ≥ 0.95.
3. **Fine metrics** (band A only) over 16×16 blocks of the fine grid → `ssimFineMin`, `diffFineMax`.
4. **Colour**: `chroma16 = mean over 256 cells of (|Δ(R−G)| + |Δ(B−G)|)/2`.

`VerdictRules.verdict(PairCheck pc, pair context)` — first matching row wins; thresholds are `GraphConfig` constants, the values here
are the **initial values from the 2026-11-12 probe** (§13) and are replaced by `calibrate.py` output before the exit run (§11):

| # | Verdict | Condition |
|---|---|---|
| V1 | `NOT_COMPARABLE` | a decode failed, or grid < 8×8 |
| V2 | `SAME_CONTENT` | identity transform ∧ `hamming ≤ T_SAME_HAM (6)` ∧ `ssim64Mean ≥ 0.985` ∧ `ssim64Min ≥ 0.90` ∧ `diff64Max ≤ 6.0` ∧ `chroma16 ≤ 2.5` ∧ (band A: `ssimFineMin ≥ 0.93` ∧ `diffFineMax ≤ 9.5`; band B: smaller image is in a thumbnail location (`area == "thumbnails"` or a path component `.thumbnails`/`.thumbs`/`cache`) — band-B pairs outside thumbnail locations cannot be `SAME_CONTENT`, they fall through to V6/V8) |
| V3 | `GEOMETRIC_EDIT` | non-identity transform ∧ `ssim64Mean ≥ 0.95` ∧ `ssim64Min ≥ 0.70` |
| V4 | `COLOR_EDIT` | identity ∧ `ssim64Mean ≥ 0.95` ∧ `chroma16 > 2.5` ∧ `fracBlocksSame ≥ 0.50` |
| V5 | `LOCAL_EDIT` | identity ∧ `ssim64Mean ≥ 0.75` ∧ `fracBlocksSame ≥ 0.60` ∧ fails V2 |
| V6 | `AMBIGUOUS` | identity ∧ fails V2 by a margin: every failing V2 metric is within its ambiguity band (`ssim64Mean ≥ 0.975`, `ssim64Min ≥ 0.85`, `diff64Max ≤ 8`, `chroma16 ≤ 3.5`, `ssimFineMin ≥ 0.85`, `diffFineMax ≤ 12`), or band B outside thumbnail locations satisfying all band-A-free V2 conditions |
| V7 | `NEAR_SCENE` | `ssim64Mean ≥ 0.25` ∧ pair has source `TIME_WINDOW` |
| V8 | `DIFFERENT` | otherwise |

Note the deliberate asymmetry: V5 (`LOCAL_EDIT`) matches same-template hard negatives (chat screenshots share most blocks). A
`LOCAL_EDIT` verdict alone creates **no** edge: `EdgeTyper` needs provenance (§5.3). Similarity is never enough (spec 6.2, 6.3).

### 4.5 Edge confidence for pixel-verified edges

`confidence = min(0.98, 0.5 + 0.5 × m)` with `m = min over the V2 metrics of (value − threshold)/(ideal − threshold)` clamped to
[0, 1] (`ideal` = 1.0 for SSIMs, 0 for diffs/chroma). An edge exactly at threshold has 0.5; an identical-pixels re-encode ≈ 0.98.
`NEAR_SCENE` edges: `0.6 + 0.3 × clamp((120 000 − |Δt ms|)/120 000)`. Edges from provenance only (crop/collage): 0.6.

### 4.6 Budget and priority of stage 4

Gated pairs are processed in descending `min(sizeA, sizeB)` (what deletion could recover), then by pairKey. Each new image decode
charges one decode and its ms to `Budget`; a cached `StructSig` costs nothing. `GraphConfig.MAX_DECODES_PER_RUN = 20 000`
(phone; `PipelineConfig` may override). Expected load (dataset: ~520 gated pairs, ~700 decodes ≈ 20 s on the JVM; 100 k-image phone
with ~10 % in candidate pairs: ~10 k decodes at 256 px ≈ 5–10 min in the background, resumable).

---

## 5. Edge typing and direction (`EdgeTyper`)

### 5.1 Context features used (all already in the store)

`area` (classic `Classifier`), `uprightW×H` → `pixels`, `sizeBytes`, `type` (format), `createdAt` + source, `ExifInfo` (`make`,
`model`, `software`, `dateTimeOriginal`), `NameDate`, `NameStem`, `hidden`.

### 5.2 Typing table for `SAME_CONTENT` pairs (first match; `o` = original, `c` = copy)

Direction: `o` = the member with more pixels; tie → larger `bytes / pixels` (higher fidelity); tie → camera-native format
(`jpeg`,`heic`,`dng` from area `camera`) ; tie → earlier `createdAt` (known before unknown); tie → smaller fileId.

| # | Condition on `c` relative to `o` | Type `c → o` | Evidence added |
|---|---|---|---|
| T1 | `c` in a thumbnail location, or `c` long edge ≤ 400 and ≤ 0.35 × `o` long edge | `THUMBNAIL_OF` | `AREA_PAIR`, `PIXELS_RATIO` |
| T2 | `c` area ∈ {`wa_image`,`wa_sent`,`wa_doc`,`telegram_media`} or name matches `NameStem.WA` (`IMG-YYYYMMDD-WAnnnn`, `WhatsApp Image … (n)`) | `COMPRESSED_FROM` | `NAME_PATTERN_WA` / `AREA_PAIR` |
| T3 | format differs (`FORMAT_PAIR`, e.g. jpeg→png/webp) and pixels within 1 % | `VERSION_OF` | `FORMAT_PAIR` |
| T4 | `c.pixels ≤ 0.81 × o.pixels` (long edge ≤ 0.9×) or `c.bytes ≤ 0.8 × o.bytes` | `COMPRESSED_FROM` | `PIXELS_RATIO` / `BYTES_RATIO` |
| T5 | otherwise (same format, same pixels, similar bytes: a re-save) | `VERSION_OF` | `BYTES_RATIO` |

Safety note: H2 treats `COMPRESSED_FROM`, `THUMBNAIL_OF` and `VERSION_OF` identically (never SAFE/NO_LOSS justification), so
mistyping among them changes reason wording, never the tier ceiling. The group they form is `CONTENT_EQUIVALENT` (§8.2).

### 5.3 Edits require provenance

`NameStem.of(name)`: NFC, lower-case, strip extension, then strip one trailing *edit suffix* repeatedly (max 3 times) from the
closed list `GraphConfig.EDIT_SUFFIXES`: `~\d+`, ` ?\(\d+\)`, `[-_]edited`, `[-_]edit`, `[-_]\d{1,2}` (only after a 6+ digit
time stamp), `[-_](annotated|markup|text_added|filtered|cropped|crop|mirrored|rotated|collage|snapseed|copy)`, `[-_]\d{3,4}(px)?`
(resize width), ` - copy`. A stem never strips the timestamp itself; screenshots keep app suffixes (`Screenshot_20210204-084544_Messages`
is its own stem).

Provenance evidence (≥ 1 required for any `EDITED_FROM`): `NAME_STEM_EQUAL`; `EXIF_DTO_EQUAL` (both `dateTimeOriginal` known and
equal to the second); `EDITOR_FOLDER` (path component in `{Edited, Snapseed, Markup, Collage, Editor, PhotoEditor, PicsArt,
Photoshop Express, Lightroom}` case-insensitive) **together with** `ssim64Mean ≥ 0.95`; `EXIF_SOFTWARE` naming an editor (closed
list) together with equal capture time.

| Verdict | With provenance | Without provenance |
|---|---|---|
| `LOCAL_EDIT`, `COLOR_EDIT`, `GEOMETRIC_EDIT` | `EDITED_FROM` (edit → original; original = earlier `createdAt`, tie: the member without edit suffix, tie: smaller fileId), `VERIFIED` | no edge (template look-alikes: chat screens, receipts, greetings) |
| `NOT_COMPARABLE` with aspect mismatch, NAME_STEM pair, `EXIF_DTO_EQUAL` or same `NameDate` to the second | `EDITED_FROM` `UNVERIFIED`, conf 0.6, evidence `"CROP_OR_RECOMPOSE"` | no edge |
| `NEAR_SCENE` | `VISUALLY_SIMILAR` (time window is the provenance) | — (V7 requires the window) |
| `AMBIGUOUS` | no edge; `PairCheck` kept; Phase 4 reads it via `GraphView.ambiguousWith(fileId)` | same |
| `DIFFERENT` | no edge | no edge |

Collages (`collage member`, 16 dataset pairs) are not detected in Phase 3: no stem, no shared geometry. Expected recall for them is 0.

---

## 6. Archives and backups (`ArchiveMatcher`, `BackupVerifier`)

Nodes: `fileId!entryPath` for each stored `ArchiveEntry` (plain zip only — ARCHITECTURE §2.8, PHASE_2 §2; tar is Phase 6, A11).

```
for each PRESENT archive A with archive.listingStatus == OK and entries != null:
    for each entry e (not dir, size >= 1): edge  A!e  CONTAINED_IN  A   (VERIFIED, structural)
index entries by (size, crc32)   [crc32 known]   and by (lower(baseName(e.path)), size)
for each PRESENT file F (not inside the same archive, not an archive's own alias), HashRecord current:
    matches = entries with (size == F.size && crc32 == F.crc32)            -> level CRC32_MATCH, state POSSIBLE
             ∪ entries with (baseName equal && size equal) and crc unknown -> level SIZE_MATCH,  state POSSIBLE (name+size)
    for each match m (cap 8 per file): BackupRecord(subject F, copyRef A!m, kind ARCHIVE_ENTRY)
        edge F BACKED_UP_BY A!m   UNVERIFIED, conf 0.6 (CRC) / 0.3 (name+size)
BackupVerifier (stage-4 budget, priority = F.size desc), for CRC32_MATCH records whose state is not already decided for the current fps:
    preconditions: A.integrity ∉ {CORRUPT, TRUNCATED, ENCRYPTED}, entry.encrypted != TRUE, entry.method ∈ {0 stored, 8 deflate},
                   entry.size <= Limits.VERIFY_MAX_ENTRY (64 MiB), ratio entry.size / max(1, compressedSize) <= 100,
                   F.sha256 current
    inflate with v2.util.BoundedInflater in memory, streaming SHA-256 + CRC32
    CRC mismatch vs header  -> state CORRUPT,  evidence ENTRY_CRC_MISMATCH; edge removed (no BACKED_UP_BY)
    SHA-256 == F.sha256     -> level SHA256_MATCH, state VERIFIED, verifiedAt = now; edge VERIFIED conf 1.0, evidence ENTRY_SHA256_EQUAL
    SHA-256 != F.sha256     -> state MISSING (content differs); edge removed
    precondition fails      -> state UNKNOWN, edge stays UNVERIFIED (reason in BackupRecord.note)
    inflate error / budget  -> state UNKNOWN (retry next run)
```
`BackupRecord` keyed by edgeId (ARCHITECTURE §2.8) and also kept for `CORRUPT`/`MISSING` outcomes (so Phase 4 can say why there is
no backup). A VERIFIED record is invalidated (state `UNKNOWN`, edge `STALE`) when F's or A's fingerprint changes (F1).
Scenario 10: a truncated `tar.gz` is not listed (Phase 6), a zip with a bad-CRC member yields `CORRUPT` for that member only;
members never listed (missing) produce no record at all. Neither gives `VERIFIED`, which is the only state that ever counts for
recoverability (G1). False VERIFIED requires an SHA-256 collision.

---

## 7. Installers, companions, document versions

**`InstallerLinker`** (spec 5.6): APKs with `ApkInfo.packageName` known. Group `n:i:` per package: members sorted by `versionCode`.
Within a package: byte-identical members already have `EXACT_DUPLICATE`; for different versions `newer VERSION_OF older`
(`APK_PKG`, UNVERIFIED, conf 0.8); same `(packageName, versionCode, signer set)` but different SHA-256 → `VERSION_OF` with evidence
`APK_PKG_VER_SIGNER` and conf 0.7 (never interchangeable: same version ≠ same bytes). Installed-state comparison is Phase 4's job
(`ApkInfo.installState`, `sameSigner` from Phase 2).

**`CompanionLinker`** (closed rules, each `COMPANION_RULE=<id>`):

| Rule | Sidecar → primary |
|---|---|
| C1 subtitle/metadata | same directory, same stem, sidecar ext ∈ {srt, vtt, ass, ssa, sub, xmp, aae, thm, lrv, json(Google Takeout)} → primary ext ∈ media types |
| C2 split APK | `split_*.apk` or `config.*.apk` in the same directory as an APK of the same `packageName` → that base APK (VERIFIED when package names equal) |
| C3 OBB | `Android/obb/<pkg>/*.obb` → every present APK with `packageName == pkg` (VERIFIED), and → the installed app when `InstalledApps.get(pkg) != null` (recorded as evidence `APP_INSTALLED`, no node) |
| C4 multi-part archive | `name.zip.001`, `.z01`, `.part1.rar`: parts → first part |

**`DocVersionLinker`** (documents: pdf, ooxml, odf, txt, crypt*): same directory or {Download ↔ Documents}, `NameStem` equal after
also stripping `[-_ ](v\d+|final|draft|old|new|protected|signed|copy)` → `newer VERSION_OF older` by `createdAt` (UNVERIFIED, conf 0.5,
`DOC_STEM_RULE`). WhatsApp chat backups `msgstore-YYYY-MM-DD.n.db.cryptNN` → `msgstore.db.cryptNN`: `older VERSION_OF current`
(`BACKUP_NAME_DATE`, conf 0.8). These edges only inform Phase 4 (context, `Y_OLD_CHAT_BACKUP`); they can never justify SAFE/NO_LOSS (H2).

---

## 8. Groups and keepers (spec 6.3)

### 8.1 Keeper eligibility and exact keeper

`eligible(m)`: `presence == PRESENT`, `readable != FALSE`, `extractionStatus != UNREADABLE`, fingerprint current, hash current
(exact groups), not a non-canonical alias, and `Volume.mounted`. "Accessible" (spec 6.3 step 23) = eligible.

`KeeperSelector.exact(members)`: among eligible members, the first by ARCHITECTURE §8 order — `protection != NONE` →
canonical `area` rank (`camera, screenshot, document, music, movies, pictures, download, wa_doc, wa_image, wa_video, telegram_media,
wa_sent, wa_sent_video`, others after) → earliest known `createdAt` (unknown last) → no edit/copy suffix in the name (`NameStem`
stripped nothing) → shortest path → smallest fileId. `keeperReasons`: `G_KEEPER_CANONICAL` if decided by area, `G_KEEPER_OLDEST`
by time, `G_KEEPER_QUALITY` never for exact groups. No eligible member → `keeperId = null`, `keeperFallback = true`: no member of
that group gets a group-based recommendation (Phase 4: REVIEW `R_UNCERTAIN_COPY` if the group was otherwise actionable).
Trashed members (`.trashed-*`) are never keepers while another member is eligible.

### 8.2 Content classes and near groups (`ContentClasses`, `NearGrouper`)

Near groups are built over **content nodes**: each exact group collapses into one node represented by its keeper (byte-identical
files carry the same information), each other eligible image is its own node. This removes the double counting between "exact
copy" and "compressed copy of the same original" and lets an exact copy of a near-group keeper be deleted (its twin carries the
information).

| Group kind | Built from | Keeper (spec 6.3 "highest-quality original") |
|---|---|---|
| `CONTENT_EQUIVALENT` (`n:c:`) | connected components of VERIFIED `COMPRESSED_FROM ∪ THUMBNAIL_OF ∪ VERSION_OF(image)` edges between content nodes | the sink of the edges (the node no edge points away from); several sinks → most pixels → highest bytes/pixel → camera-native format → earliest `createdAt` → canonical area → smallest fileId. Must be eligible; else next by the same order |
| `BURST` (`n:b:`) | connected components of `NEAR_SCENE` pair verdicts (union–find), then **star**: `VISUALLY_SIMILAR` edges member → keeper | name marker `_COVER`/`_BURST001` → highest `sharpness` if it beats the runner-up by ≥ 10 % → earliest capture time → smallest fileId |
| `EDIT_FAMILY` (`n:e:`) | original + every node with `EDITED_FROM` → it | the original. Edits are **not** redundant members: an edit family yields no deletion candidate (spec 6.3 step 21 "meaningful modification") |
| `INSTALLER` (`n:i:`) | §7 | highest versionCode (informational; installer decisions are Phase 4 per-file) |

A node can be in one `CONTENT_EQUIVALENT`, one `BURST`, one `EDIT_FAMILY`: they answer different questions. Conflict rule: if a
node would be a redundant member of a `CONTENT_EQUIVALENT` group *and* is the original of an `EDIT_FAMILY`, it stays a redundant
member only if the group keeper is eligible (the edit's relationship is then re-pointed in `GraphView.originalOf(edit)` to the keeper).

### 8.3 Group recovery (no double counting)

`DuplicateGroup.recoverableBytes` is left `Unknown.LONG` in Phase 3 (ARCHITECTURE §2.7: Phase 5 computes it after dependency checks).
Phase 3 stores `memberBytes = Σ sizeBytes of canonical members`. `GraphView.redundantMembers(groupId)` = eligible-or-not members
minus keeper minus aliases; Phase 5 sums only members it actually plans (one action per file).

### 8.4 Never the last verified copy

Phase 3 guarantees structurally: (1) every exact group with ≥ 2 eligible members has exactly one keeper; (2) the keeper is never in
`redundantMembers`; (3) aliases are never members. Phase 4 H1 and Phase 5 `PlanValidator` re-check it on the final plan.

---

## 9. `GraphView` — the read-only API Phase 4 and 5 use

```java
public final class GraphView {
    public static GraphView load(V2Store store);                      // reads relationships, groups, backups, pair_checks once
    public List<Relationship> edges(String nodeRef);                  // both directions, sorted by type then edgeId
    public DuplicateGroup exactGroupOf(String fileId);                // null if none
    public DuplicateGroup groupOf(String fileId, NearGroupKind kind); // null if none
    public boolean isKeeper(String fileId);                           // keeper of any group
    public String keeperFor(String fileId, NearGroupKind kind);       // null if fileId is keeper or not in a group
    public String aliasOf(String fileId);                             // canonical record of its alias set, or null
    public Tri perceptualStatus(String fileId);                       // TRUE = compared, FALSE = eligible but no candidate, UNKNOWN = ineligible or checks pending
    public List<PairCheck> ambiguousWith(String fileId);              // verdict AMBIGUOUS, current fps
    public List<BackupRecord> backupsOf(String fileId);               // all states
    public String originalOf(String editFileId);                      // §8.2
    public List<String> companionsOf(String primaryId);               // sidecars (COMPANION_OF src) of a primary
    public List<String> primariesOf(String sidecarId);
    public boolean edgeCurrent(Relationship e, FileRecord src, FileRecord dst); // fp check (F1) — stale edges never count
}
```

---

## 10. Errors and unknowns

| Situation | Behaviour |
|---|---|
| hash missing/stale | no exact edge; counted `hashUnknown` |
| physicalKey unknown on either side | exact edge `UNVERIFIED` (G2); never interchangeable |
| decode fails in stage 4 | `NOT_COMPARABLE`, `note` = status; pair retried only when a fingerprint or `MATCHER_VERSION` changes |
| `MediaDecoder` lacks the format (HEIC on JVM) | image ineligible (§4.1); `perceptualStatus = UNKNOWN` |
| archive listing `TRUNCATED/LIMIT/CORRUPT` | no `CONTAINED_IN` beyond listed entries; no `BACKED_UP_BY` from that archive; archive integrity reported to Phase 4 |
| inflate exceeds caps / encrypted entry | `BackupRecord.state = UNKNOWN`, never VERIFIED |
| budget exhausted in D | partial graph, coverage `pending`, outcome `BUDGET_EXHAUSTED`; Phase 4 treats pending as unknown |
| a store record references a file now REMOVED | edge removed in E; groups recomputed |
| exceptions in a comparator/inflater (bug) | caught per pair, `NOT_COMPARABLE` with `note = "ERROR:" + class`, stage continues; test asserts zero such notes on the dataset |

---

## 11. Calibration plan (`tools/v2eval/calibrate.py` + `V2Cli pairs`)

### 11.1 Producing pair metrics (Java, so the numbers are exactly what v2 computes)

```
V2Cli pairs <root> --manifest <manifest.json> --out pairs.jsonl [--state-dir d] [--random 2000 --seed 7] [--include-drive <dir>]
```
After a normal scan up to `T3`, it computes `PairCheck` metrics (no verdict filtering, all transforms for NAME_STEM pairs) for:
(1) every pair the `CandidateGenerator` + `PerceptualGate` produce; (2) every manifest-labelled image↔image pair (relationships of all
types, both members decodable), even if not generated (tagged `generated=false` → measures candidate recall); (3) every within-group
pair of every `hard_negative_group`; (4) `--random` uniformly sampled pairs with equal aspect (±2 %). One JSON line per pair:
`{a, b, label, hnGroup, sources, generated, hamming, aspectDiff, metrics…, transform, timeDeltaMs, stemEqual, areaA, areaB}`.

### 11.2 Sweep (`calibrate.py`, Python stdlib only)

```
python3 -I tools/v2eval/calibrate.py --pairs pairs.jsonl [--drive-pairs drive.jsonl] --out thresholds.json --report report.md
```
1. Classes: POS_SAME = labels `COMPRESSED_FROM, THUMBNAIL_OF, VERSION_OF` (image↔image), `EXACT_DUPLICATE`; POS_EDIT = `EDITED_FROM`;
   POS_SCENE = `VISUALLY_SIMILAR`; NEG = hard-negative pairs ∪ random pairs ∪ Drive-corpus cross-time pairs.
2. For V2 (`SAME_CONTENT`): grid sweep over each threshold (SSIM steps 0.005, diffs 0.25, chroma 0.25, Hamming 1) holding the
   others at their current value, then a coordinate-descent pass (3 rounds). Objective, **lexicographic**:
   (a) zero NEG pairs, zero POS_EDIT pairs and zero POS_SCENE pairs classified `SAME_CONTENT` ("false interchangeable-looking pairs");
   (b) maximise POS_SAME recall; (c) maximise the minimum normalised margin to the nearest negative (thresholds placed at the
   midpoint of the gap between the worst negative and the worst positive, not at the boundary).
   If (a) cannot be met with recall > 0, the script exits 1 and the current (stricter) constants stay.
3. Ambiguity bands (V6): set so that every NEG pair within 1.5× the gap is `AMBIGUOUS` or worse, never `SAME_CONTENT`.
4. Edit/scene verdicts (V3–V5, V7): maximise F1 of POS_EDIT (with provenance applied) and POS_SCENE subject to precision ≥ 0.95 on NEG.
5. Report per edge type (directed, after `EdgeTyper` replay in Python using the same tables): TP/FP/FN, P/R/F1; HN false positives per
   group; candidate recall (labelled pairs with `generated=false`); the margins; a histogram per metric per class (text).
6. Leave-one-transform-out check: thresholds re-fitted with each transform (`whatsapp…`, `re-forward…`, `resize…`, thumbnails,
   conversions) held out must still classify the held-out positives as `SAME_CONTENT` ≥ 90 %; otherwise the report flags over-fitting.
7. The chosen values are copied **by hand** into `GraphConfig` with a comment citing the report hash; `MATCHER_VERSION` is bumped. Runtime
   never reads `thresholds.json` (determinism; auditable diffs).

### 11.3 Evaluator additions (`evaluate.py`, Phase 3 gate)

* exact cluster accuracy (existing definition, ARCHITECTURE §10) from `duplicateGroups[exact=true]`;
* **content-equivalence accuracy**: truth = undirected manifest `COMPRESSED_FROM ∪ THUMBNAIL_OF ∪ VERSION_OF` image↔image pairs (+ closure
  through exact groups); prediction = undirected VERIFIED edges of those three types; P/R/F1; plus `hn_false_pairs` = predicted pairs
  inside one `hard_negative_group` (gate: 0);
* near-duplicate accuracy (ARCHITECTURE §10) changed as in §14 A-4 (bursts compared as clusters);
* relationship accuracy per type (directed), backup verification: `false_verified` = VERIFIED `BACKED_UP_BY` whose subject is not in
  `backup_manifest.json[archive].intact` (gate 0).

---

## 12. Tests (`jvmtest/.../v2test/graph/`, `main()` programs registered in `AllV2Tests`)

| Test class | Cases (each a named assertion) |
|---|---|
| `AliasResolverTest` | equal physicalKey → SAME_FILE, no edge, canonical = Mode B record; different keys → PROVEN_DISTINCT; null key + same volume → UNKNOWN → UNVERIFIED edge; different fsUuid non-SAF volumes → DISTINCT; SAF volume + Mode B volume, keys null → UNKNOWN |
| `ExactGrouperTest` | 3 identical files → 3 VERIFIED edges, one group, keeper by area order (camera beats download); zero-byte twins → no group; stale HashRecord (fp changed) → no edge; group of 70 → star edges; trashed member never keeper; ineligible (unreadable) keeper candidate skipped; all ineligible → keeperFallback |
| `NameStemTest` | table of ≥ 40 names → stems (`IMG_20240712_074308~2.jpg`, `PXL_…_mirrored.jpg`, `Statement (1).pdf`, `Screenshot_20210204-084544_Messages.png` unchanged, `IMG_20230401_152003_1024.jpg`, Devanagari names NFC) |
| `MultiIndexHashTest` | property: random 63-bit hashes + planted pairs at distances 0..7 → all found; distance 8+ pairs may or may not be found but are dropped above T_CAND; bucket overflow counted, deterministic |
| `CandidateGeneratorTest` | 10 000 synthetic hashes: total candidate pairs ≤ 64 × n; time window pairs found at 119 s, not at 121 s; stem groups > 32 skipped; output identical across two runs |
| `PerceptualGateTest` | aspect 1.019 passes, 1.021 rejected (non-stem); flat image excluded; HEIC (NO_DECODER) never a candidate |
| `StructuralComparatorTest` | synthetic luma fixtures: identical → SSIM 1, diff 0; JPEG re-encode q60 of a fixture → V2 `SAME_CONTENT`; a 40×40 px pasted rectangle → `LOCAL_EDIT` (fracBlocksSame ≥ 0.6, ssim64Min < 0.9); hue shift → `COLOR_EDIT`; rot90 with stem → `GEOMETRIC_EDIT` transform `rot90`; mirror → `mirror`; two different text renderings on one template → `LOCAL_EDIT` and **no edge** without provenance |
| `VerdictRulesTest` | one row per V1–V8 boundary (just inside / just outside each threshold), ambiguity band membership |
| `EdgeTyperTest` | T1–T5 table rows; direction ties; WA name pattern; provenance matrix of §5.3 (each cell) |
| `ArchiveMatcherTest` | zip with an intact member → VERIFIED BACKED_UP_BY; bad-CRC member → CORRUPT, no edge; member absent → no record; name+size only → POSSIBLE conf 0.3; encrypted entry → UNKNOWN; entry > 64 MiB → UNKNOWN; file changed after verification → edge STALE |
| `InstallerCompanionTest` | split APK → COMPANION_OF base; obb → COMPANION_OF APK of same package; `.srt` → video; same pkg+version+signer different bytes → VERSION_OF not EXACT |
| `NearGrouperTest` | exact twin of a near-group keeper collapses into one content node; burst of 5 → star of 4 VISUALLY_SIMILAR to the `_COVER` member; edit family yields no redundant members; ineligible sink → next keeper |
| `GraphStageResumeTest` | budget of 10 decodes → BUDGET_EXHAUSTED, partial edges; second run completes; final store equals an unbudgeted run (byte-equal snapshot minus timings) |
| `GraphDeterminismTest` | two runs, shuffled input order of the store → identical edges/groups JSON |
| `DatasetGraphTest` (needs `/home/user/sp-dataset`, skipped with a message otherwise) | runs V2Cli to `GRAPH`, then asserts the §15 numbers via `evaluate.py --gate phase3` |

---

## 13. Evidence behind the initial thresholds (probe run, 2026-11-12)

A throw-away Python probe (PIL; same pHash algorithm as `core.ImageFeatures`, metrics as in §4.4) over the dataset's labelled image pairs,
all hard-negative within-group pairs and 400 random pairs; ranges are [min, median, max].

| Class | n | Hamming | ssim64Min | diff64Max | chroma16 | ssimFineMin (256) | diffFineMax |
|---|---|---|---|---|---|---|---|
| COMPRESSED whatsapp 1600 q75 | 30 | 0,0,1 | .997–1.0 | ≤ 1.0 | ≤ .42 | ≥ .963 | ≤ 5.2 |
| COMPRESSED re-forward 800 q60 | 20 | 0,0,3 | ≥ .985 | ≤ 2.2 | ≤ 1.3 | ≥ .968 | ≤ 8.7 |
| COMPRESSED resize / recompression | 25 | ≤ 2 | ≥ .997 | ≤ .9 | ≤ 1.7 | ≥ .994 | ≤ 2.1 |
| THUMBNAIL_OF (band B) | 80 | 0,0,3 | ≥ .919 | ≤ 5.3 | ≤ 1.3 | (.79–.97, not used) | — |
| VERSION_OF conversions / re-encode | 16 | 0 | ≥ .997 | ≤ .8 | ≤ .43 | ≥ .984 | ≤ 3.3 |
| EDITED annotated | 8 | 0,1,5 | .38–.96 | 3.2–12.8 | 1.0–2.1 | ≤ .62 | ≥ 9.4 |
| EDITED filtered | 7 | 0–1 | .81–1.0 | .3–26 | **4.5–32.7** | — | — |
| EDITED text_added | 8 | 2–11 | ≤ .57 | ≥ 13 | — | — | — |
| EDITED rotated/mirrored/cropped/collage | 36 | 23–39 | — (aspect or geometry differs) | | | | |
| VISUALLY_SIMILAR bursts | 51 | 1,7,14 | ≤ .50 | ≥ 21 | — | — | — |
| VISUALLY_SIMILAR video frames (screenshots 2 s apart) | 16 | 0,4,12 | ≤ .98 | ≥ 5.2 | — | ≤ .90 | ≥ 12 |
| HN chat/payment/ticket/settings screens | 1 947 (558 with Hamming ≤ 10) | **0**,15,37 | ≤ .989 | ≥ 1.3 | 0–7.9 | **≤ .772** | **≥ 10.8** |
| HN receipts / greetings / IDs / scenes | 313 | ≥ 1 | ≤ .43 | ≥ 9 | | ≤ .62 | ≥ 26 |
| random | 400 | ≥ 15 | | | | | |
| Drive corpus, real bursts (70 same-time pairs) | — | 0–12 | ≤ .83 | ≥ 12.4 | | | |

Conclusions that shaped the design: (1) pHash alone cannot separate same-template screenshots (Hamming 0 occurs) — the fine grid is
mandatory; (2) the coarse grid cannot separate annotations from thumbnails — fine grid again, and thumbnails need the location rule;
(3) colour filters are invisible in luma — `chroma16` is needed; (4) rotations/crops are unreachable by pHash — name stems;
(5) time is what separates bursts and video frames from template look-alikes. All positive classes clear the initial V2 thresholds with
margin except re-forwards on `diffFineMax` (8.7 vs 9.5) and screens on `ssimFineMin` (.772 vs .93): the calibration's margin
objective will move these.

---

## 14. ARCHITECTURE changes (to apply after the current implementer's phase lands)

| Id | Section | Edit |
|---|---|---|
| A-1 | §4.1 `V2Store`, §4.2 snapshot order, §4.3 | add `Table<PairCheck> pairChecks(); // pair_checks` after `backups()`; SQLite index none (key only) |
| A-2 | §2.7 | add fields `NearGroupKind kind; boolean keeperFallback;` and the `n:<letter>:` id scheme of §2.4 |
| A-3 | §5.1 row 8 | "`T6_DETAIL`: Phase 3 performs stage-4 structural checks and backup inflate verification inside `GRAPH` (budgeted, resumable via `pair_checks`/`backup_records`); `T6_DETAIL` is reserved for Phase 6 video sampling" |
| A-4 | §10 near-duplicate row | truth: undirected `EDITED_FROM ∪ COMPRESSED_FROM ∪ THUMBNAIL_OF ∪ VERSION_OF(image)` edges, plus `VISUALLY_SIMILAR` **compared as clusters** (pairwise co-membership, truth = transitive closure of manifest VS edges, prediction = co-membership in a `BURST` group); THUMBNAIL_OF added (it was missing although the dataset labels 80 pairs); gate unchanged (P ≥ 0.95, R ≥ 0.80) plus `content-equivalence P ≥ 0.99, hn_false_pairs = 0` |
| A-5 | §8 near-duplicates bullet | replace "4 bands × 16 bits" LSH with the multi-index scheme + time window + name stems of §4.2, and "downscaled SSIM ≥ T_ssim" with the V2 rule (coarse + fine + chroma) |
| A-6 | §8 sub-typing bullet | replace by §5.2/§5.3 (provenance required for EDITED_FROM; "same burst timestamp ≤ 10 min" becomes 120 s with SECOND-precision capture time) |
| A-7 | §2.6 | note: `VISUALLY_SIMILAR` stored as a star to the group keeper; `EXACT_DUPLICATE` groups > 64 members stored as a star |
| A-8 | §11 Phase 3 row | exit criteria as §15 below |

---

## 15. Exit criteria (Phase 3), measured with `evaluate.py --gate phase3` on `/home/user/sp-dataset`

| Metric | Gate | Expected (reasoning) |
|---|---|---|
| exact cluster precision / recall | P = 1.000, R ≥ 0.99 | R = 1.000: every exact pair has a size collision so Phase 2 hashes both; no aliases in the dataset |
| content-equivalence precision (COMPRESSED/THUMBNAIL/VERSION image pairs) | ≥ 0.99 and `hn_false_pairs = 0` | 1.000 in the probe |
| content-equivalence recall | ≥ 0.95 | ≈ 0.99 (all 171 probed positives clear the initial thresholds; scanned-PDF VERSION_OF pairs are excluded from the truth as non-image) |
| near-duplicate P / R (A-4 definition) | P ≥ 0.95, R ≥ 0.80 | R ≈ 0.90: bursts + video frames 67/67, compressed 78/78, edits 43/59 (no collages; crops only via stem+capture time) |
| EDITED_FROM directed precision | ≥ 0.90 | provenance requirement removes template look-alikes |
| backup `false_verified` | 0 | needs an SHA-256 collision |
| BACKED_UP_BY recall (manifest `BACKED_UP_BY ∪ CONTAINED_IN`, VERIFIED) | ≥ 0.9 | 17/17 if Phase 2 lists both zips and hashed the 17 originals (size ∈ entrySizes) |
| determinism | byte-identical JSON across 2 runs (timings removed) | |
| Drive corpus (`--include-drive`) | 0 `SAME_CONTENT` pairs between files from different capture seconds; HEIC: `perceptualStatus = UNKNOWN` for 36/36 | measured 0 in the probe |
| performance | GRAPH ≤ 60 s on the dataset (JVM, 1 worker); candidate pairs ≤ 64 × eligible images | |
| tests | all §12 tests pass, `ForbiddenApiTest` passes | |

---

## 16. Design choices

| # | Choice | Rejected alternative | Why |
|---|---|---|---|
| P3-1 | Multi-index hashing (4×16 bits, radius 1 per band) guaranteeing all pairs ≤ 7 bits, plus a time window and name stems as separate candidate sources | Classic 4-band exact LSH; all-pairs within area | Exact 4-band LSH only guarantees distance ≤ 3; all-pairs is O(n²). Copies sit at ≤ 3 bits (measured) so radius 7 has margin; bursts reach 14 bits but are always close in time; rotations/crops are 23–39 bits and only names connect them. Each source is O(n log n) or O(n·probes). |
| P3-2 | Three-part structural test (coarse SSIM grid, fine 256-px grid, 16×16 chroma) | One downscaled SSIM threshold (ARCHITECTURE §8 draft) | The probe shows a single 64-px SSIM cannot separate template screenshots (ssim64Min up to 0.989) from re-forwards (0.985), nor colour filters (luma-identical) from copies. |
| P3-3 | Pixel similarity alone creates edges only for `SAME_CONTENT`; edits need provenance; scene similarity needs a time window | Edge whenever pixels are close | Same-template hard negatives share most blocks; without provenance they would become EDITED_FROM/VISUALLY_SIMILAR in bulk (558 screen pairs ≤ 10 bits), destroying near-dup precision. Spec 6.3: "not safe merely because files look similar". |
| P3-4 | Near groups over content nodes (exact groups collapsed first) | Independent exact and near groups | Removes double counting and the paradox of a "keeper" that is itself one of two identical files. |
| P3-5 | Bursts stored as a star to a keeper, evaluated as clusters | Clique edges | k(k−1)/2 edges are noise; the user decision is "which shot to keep". |
| P3-6 | `GRAPH` does its own stage-4 work (budgeted) instead of waiting for `T6_DETAIL` at order 8 | Follow the stage table literally | Phase 4 needs verified edges in the same run; T4/T5 do not exist until Phases 6/7, so splitting adds a stage and a second assembly pass for nothing. |
| P3-7 | Thresholds are compiled constants chosen by a lexicographic objective (zero false SAME_CONTENT on negatives first, recall second, max margin third) | Runtime-loaded config; F1 maximisation | F1 trades false merges for recall; a false "same content" is the expensive error. Constants keep runs reproducible and changes reviewable. |
| P3-8 | Archive backups verified only by in-memory inflate + SHA-256 | CRC32 + size + name as "verified" | 32-bit CRC is not a content proof (spec 8.3; ARCHITECTURE F-M7). |
| P3-9 | No embeddings, no OCR in matching | Text comparison for screenshots | Not available until Phases 6/7 (A7); the fine grid already separates the measured hard negatives. |
