# Phase 4 — Utility, recoverability and expected-loss engine

Status: design, for review. Author: architect agent (phases 3–5). Spec: §8 (U, L, R, C, I, B), §9 (ten stages, expected loss,
action utility, categories, aggressive policy), §16.4 scenarios 1–11. Binding: ARCHITECTURE §2.9 (Assessment), §2.11 (tiers),
§6 (reason codes), §7 (hard rules H1–H10), A5, A7, A9. Builds on PHASE_3 (`GraphView`). ARCHITECTURE edits are listed in §15.

**Honesty statement.** Every numeric constant in this document is a *deterministic heuristic awaiting calibration*. There are no
outcome data yet (spec 8.1 asks for calibration "against actual outcomes"; that needs Phase 8 feedback). What makes the engine safe
before calibration is not the numbers but the structure: (1) hard rules run first and cannot be outvoted by any score; (2) tiers are
decided by *kinds of evidence* (structural vs verified vs plausible), and scores only gate and rank within what the evidence allows;
(3) every unknown takes the pessimistic bound. §13 lists which numbers matter for which outcome.

---

## 0. Deliverables

| Where | What |
|---|---|
| `v2/model/` | `Assessment` additions (§1.2), `Interval` helpers, `EvidenceFamily`, `Signal`, `UsageClass`, `Candidate`, `Verdict`, `Constraint` |
| `v2/content/` | `ContentClassifier`, `Classification` (plug point, §10); `RuleClassifier` (deterministic, name/path keywords) |
| `v2/risk/` | `FileContext`, `ContextBuilder`, `UsageClassifier`, `SignalExtractor`, `Keywords`, `UtilityEstimator`, `LossEstimator`, `RecoverabilityEstimator`, `ConfidenceEstimator`, `IrreversibilityEstimator`, `BenefitEstimator`, `CompressionEstimates`, `ExpectedLoss`, `ActionUtility`, `HardRules`, `CandidateRules`, `Assessor` (stage `ASSESS`), `RiskConfig` |
| `v2/plan/` (implemented now, ARCHITECTURE package map puts them here) | `TierAssigner`, `PresentationPolicy`, `ReasonCatalog` additions of §11 |
| `jvmtest/.../v2test/risk/` | tests of §14; `V2Cli` writes `files[].assessment` and a provisional `recommendations` list (Phase 5 replaces it with the planner's) |

---

## 1. Data structures

### 1.1 `FileContext` (built once per file by `ContextBuilder`; in memory only)

```java
public final class FileContext {
    public FileRecord f; public FeatureRecord feat /*null*/; public HashRecord hash /*null*/;
    public Volume volume; public AccessGrant grant /*the grant it was seen through*/;
    public InstalledApp ownerApp /*null*/; public Availability appsAvailability;
    public GraphView g;                 // PHASE_3 §9
    public UsageClass usage;            // §3.1
    public long now;                    // Clock
    public long ageMs = Unknown.LONG;   // now - createdAt (createdAt known) else now - modifiedAt for S_ rules only (field ageSource)
    public String ageSource;            // "CREATED", "MTIME", null
    public java.util.List<Signal> signals = new java.util.ArrayList<Signal>();
    public Classification classification /*null = none*/;
}
```

### 1.2 `Assessment` (ARCHITECTURE §2.9) — fields added

```java
public Interval lossRange;              // L as (low, mid, high); `loss` = lossRange.mid (kept for FileRecord.lossSeverity)
public double rvMid = NaN, rvPess = NaN; // numeric recoverability used in EL (§5.2)
public String recoveryKind;             // REGENERABLE_CACHE, REGENERABLE_INSTALLER, EXACT_COPY_KEPT, BACKUP_VERIFIED, SUPERSET_COPY, UNVERIFIED_COPY, POSSIBLE_ARCHIVE_COPY, NONE, null
public java.util.EnumMap<Action, Double> elMid, elPess, utility;   // per candidate action; NaN = not applicable
public java.util.Map<String, Double> confidenceParts;              // c_extract, c_graph, c_class, c_indep, c_avail (§6)
public boolean contradiction; public String contradictionA, contradictionB;
public java.util.List<String> familiesFor;  // evidence families supporting disposability
public java.util.List<String> familiesAgainst; // families supporting value
public UsageClass usage;
```

### 1.3 Evidence

```java
public enum EvidenceFamily { STRUCT, PROV, CAPTURE, PIXEL, GRAPH, APP, TIME, CONTENT, USER }
public final class Signal {
    public EvidenceFamily family; public String code;      // e.g. PROV:"PATH_KEYWORD_FINANCIAL", APP:"APK_SAME_VERSION_SAME_SIGNER"
    public int direction;                                  // +1 = supports value / need, -1 = supports disposability
    public double strength;                                // 0..1
    public String ref;                                     // evidence reference (edgeId, "hash:<id>", field name)
}
```

| Family | What belongs to it | Why grouped |
|---|---|---|
| `STRUCT` | type signature, size (incl. 0 bytes), OS-defined locations (`.thumbnails`, `.trashed-`, `.pending-`, `Android/*/cache`, `.Statuses`) | facts about the file system, not guesses |
| `PROV` | `area`, folder names, file-name patterns (WhatsApp `IMG-…-WA…`, `Screenshot_…`, keywords like "statement") | path and name are written by the same app at the same time: strongly correlated (a WhatsApp path implies a WhatsApp name) |
| `CAPTURE` | EXIF make/model/GPS/DateTimeOriginal, container creation time, camera-native format | written by the capturing device |
| `PIXEL` | sharpness, brightness, contrast, clipping, colour | measured from pixels; correlated with each other |
| `GRAPH` | edges, groups, backups (PHASE_3) | derived from comparisons |
| `APP` | installed state, signer, versions, cache/usage stats | from PackageManager |
| `TIME` | age, last-used | correlated with each other; never the only non-structural disposability evidence (H10) |
| `CONTENT` | classifier / OCR / model output (Phase 6/7) | one model's errors are correlated across its outputs |
| `USER` | protection, feedback (Phase 8) | |

**Independence rule (spec 9.2 "avoid treating correlated evidence as independent").** (a) Within a family, only the single strongest
signal per direction is used (max, never sum or product). (b) Across families, contributions are combined by `max` for L factors and by
a *clamped* product of per-family multipliers for U (each family multiplier ∈ [0.5, 2], the product ∈ [0.25, 4]); a family contributes
at most one multiplier. (c) Confidence counts the number of distinct families supporting the decision (§6), never the number of
signals. (d) The `RuleClassifier` uses names and paths, so its output is a `PROV` signal, never `CONTENT` (it must not count as a second,
independent family next to the path it read).

---

## 2. The ten decision stages (spec 9.1) — `Assessor` (stage `ASSESS`) + `TierAssigner`

| Stage | Code | Output |
|---|---|---|
| 1 validate access and identity | `Assessor.identity(ctx)`: `presence == PRESENT`; `FeatureRecord.fp`/`HashRecord.fp` current (stale → treated as absent); canonical alias (PHASE_3 §2.3); `readable` | `IdentityResult {ok, reason}`; aliases and non-present files get no assessment |
| 2 hard safety constraints | `HardRules.constraints(ctx)` (§8), **before any score exists** | `Constraint {Tier floorTier; Set<Action> forbidden; List<String> blocks; Tier forcedTier; String forcedReason}` |
| 3 relationships | `ContextBuilder` reads `GraphView` into signals (`GRAPH` family) and group roles | roles: keeper / redundant member / edit / alias / backed-up |
| 4 content and metadata | `SignalExtractor` (all families) + `ContentClassifier`s | `ctx.signals`, `ctx.classification` |
| 5 future utility | `UtilityEstimator` (§3) | `u30, u90, u365, u1095` |
| 6 loss severity and recoverability | `LossEstimator` (§4), `RecoverabilityEstimator` (§5) | `lossRange`, `recoverability`, `rvMid`, `rvPess` |
| 7 possible actions | `CandidateRules.generate(ctx, a)` (§9) | `List<Candidate>` with reason code, proposed tier, action, families, dependencies |
| 8 confidence and consequences | `ConfidenceEstimator`, `IrreversibilityEstimator`, `BenefitEstimator`, `ExpectedLoss`, `ActionUtility`; `HardRules.check` per candidate (second entry point, same rules) | `confidence`, `irreversibility`, `benefit`, `elMid/elPess/utility`, per-candidate blocks |
| 9 priority / tier | `TierAssigner.assign(ctx, a, candidates, constraint, policy)` (§9.3) | `Verdict {Tier tier; List<Candidate> chosen; List<String> reasonCodes; Map params; List<String> evidenceRefs}` |
| 10 to the planner | `Assessor` returns `List<Verdict>`; Phase 5 `RECOMMEND` turns them into `Recommendation`s | — |

`Assessor` writes the `FileRecord` copies (`utility = u90.mid`, `lossSeverity = lossRange.mid`, `recoverability`, `confidence`) only
on change, commits every 500 files, is idempotent (pure function of the store + clock + installed apps + policy).

---

## 3. U — future utility (`UtilityEstimator`)

### 3.1 Usage class (`UsageClassifier.of(ctx)`, first match)

| # | `UsageClass` | Rule (area from `core.Classifier`, type from Phase 2) | λ needs/yr (mid) | age decays | 
|---|---|---|---|---|
| 1 | `EMPTY` | `sizeBytes == 0` | 0 | — |
| 2 | `TRASH` | name `.trashed-*` | 0.05 | no |
| 3 | `TEMP` | area `temp` **and (REVIEW_2 F-05)** the content does not contradict the name: `typeSource != SIGNATURE`, or the signature type is in no content family (document, image, video, audio, archive, apk), or `integrity == TRUNCATED` (an unfinished download). Classic `area temp` is decided by the *extension* (`.tmp/.part/.crdownload`, `.pending-`): that is a PROV fact, not a STRUCT one, and the dataset's `Download/temp123.tmp` is a complete bank-statement PDF (must_never_delete). A file failing the clause is classified by its detected type (rows 8–22) and additionally gets `R_NAME_CONTENT_MISMATCH` | 0.05 | no |
| 4 | `THUMBNAIL` | area `thumbnails` | 0 (regenerable) | — |
| 5 | `CACHE` | area `ext_cache`, or path component `cache`/`.cache` under `Android/(data|media)/<pkg>/` | 0 (regenerable) | — |
| 6 | `APP_LOG` | under `Android/(data|media)/<pkg>/`, a path component `logs`/`log`, ext ∈ {log, txt, trace} | 0.05 | no |
| 7 | `STATUS` | area `wa_status` | 0.05 | no |
| 8 | `CHAT_BACKUP` | area `wa_old_backup`, or name `msgstore*.db.crypt*` | 0.5 | no |
| 9 | `INSTALLER` | type `apk`/`apks`/`xapk`, or ext ∈ {exe, msi, dmg} | 0.3 | yes |
| 10 | `ARCHIVE` | zip/tar family not otherwise typed | 0.5 | no |
| 11 | `DOCUMENT_RECORD` | document type (pdf, ooxml, odf, txt, csv, image in `Documents/`) and `Keywords.record(path)` hit (§4.2) | 1.5 | **no** (scenario 2) |
| 12 | `DOCUMENT` | document type | 1.0 | **no** |
| 13 | `CAMERA_PHOTO` | image, area `camera` | 2.0 | no |
| 14 | `CAMERA_VIDEO` | video, area `camera` or `movies` | 1.0 | no |
| 15 | `SCREENSHOT` | area `screenshot` | 0.5 | yes |
| 16 | `CHAT_MEDIA` | area `wa_image, wa_video, wa_sent, wa_sent_video, wa_gif, wa_sticker, telegram_media` | 0.3 | yes |
| 17 | `VOICE` | area `wa_voice`, or under `Recordings/`, or audio with `Voice`/`Call` in name | 0.5 | no |
| 18 | `MUSIC` | area `music`, audio | 3.0 | no |
| 19 | `PICTURES` | area `pictures` | 1.0 | no |
| 20 | `DOWNLOAD` | area `download` | 0.3 | yes |
| 21 | `APP_MEDIA` | area `app_media`, `app_obb`, `ext_data`, `residue` | 0.5 | no |
| 22 | `OTHER` | everything else with a known type | 1.0 | no |
| 23 | `UNKNOWN` | `type == "unknown"` | 1.0 (interval ×0.1…×3) | no |

### 3.2 Formula

```
λmid  = λ(class) × Π_f m_f            (family multipliers, each ∈ [0.5, 2], product clamped to [0.25, 4]; §1.3)
λlow  = 0.5 × λmid × ageFactor        λhigh = 2 × λ(class) × max(1, Π_f m_f)  × (anyUnknown ? 1.5 : 1)
λmid := λmid × ageFactor
ageFactor (only classes with "age decays", age from createdAt): ≤ 30 d: 1.0; ≤ 180 d: 0.7; ≤ 365 d: 0.5; > 365 d: 0.35; unknown: 1.0
U_h = 1 − exp(−λ × h / 365)  for h ∈ {30, 90, 365, 1095}, separately for low/mid/high    → each in [0, 1)
```
Age moves only `low`/`mid`, never `high` (H10: age can rank, never decide — tier gates read the pessimistic bound, §7). Unknown
inputs (`anyUnknown`: class `UNKNOWN`, extraction `PARTIAL/FAILED/UNSUPPORTED`, or pixel features missing for an image) widen `high` by
1.5 and never lower anything (H4). Classes with λ = 0 get `U = (0, 0, 0)`; their safety comes from `REGENERABLE`, not from U.

Multipliers (one per family, strongest wins): `PROV` ×1.5 record keyword or `Documents/` sub-folder named after a person/institution
(`Keywords.record`); ×0.7 WhatsApp forward pattern in name ("WhatsApp Image … (n)"). `CAPTURE` ×1.2 GPS or camera make present. `APP` ×0.3
installer whose same version is installed. `USER` (Phase 8) ×2 opened/kept recently. `CONTENT` (Phase 6/7) ×0.5…×2 by category.

Range: every `U` ∈ [0, 1); `low ≤ mid ≤ high` enforced by `Interval.of(low, mid, high)` (sorts; asserts no NaN mixed with numbers).

---

## 4. L — loss severity (`LossEstimator`)

### 4.1 Factors (spec 8.2: personal, financial, legal, sentimental, uniqueness, density, replacement)

Each factor is an `Interval` in [0, 1]. Default per class from the table, raised (never lowered) by signals. Unknown content → `high`
of every importance factor = 0.9 (H4), `mid` = class default.

| UsageClass | personal | financial | legal | sentimental | origin (uniqueness) | replacement |
|---|---|---|---|---|---|---|
| CAMERA_PHOTO / CAMERA_VIDEO | .7 | .1 (high .6: could be a document photo) | .1 (high .6) | .6 | 1.0 | 1.0 |
| SCREENSHOT | .4 | .3 (high .8: payments, tickets) | .2 (high .7) | .2 | 1.0 | .8 |
| CHAT_MEDIA | .4 | .1 (high .6) | .1 (high .6) | .4 | .5 (high 1.0: sender may not have it) | .6 |
| VOICE | .6 | .1 | .1 | .6 | 1.0 | 1.0 |
| DOCUMENT_RECORD | .5 | **.9** (if financial keyword) | **.9** (if legal/medical keyword) | .3 | .8 | .9 |
| DOCUMENT | .4 | .3 (high .8) | .3 (high .8) | .2 | .7 | .7 |
| INSTALLER | .1 | 0 | 0 | .1 | .3 (high .9: may be delisted) | .5 (high .9) |
| ARCHIVE | .4 (high .9) | .3 (high .9) | .3 (high .9) | .3 | .7 | .8 |
| CHAT_BACKUP | .8 | .2 | .2 | .8 | 1.0 | 1.0 (current backup) / .6 (older) |
| MUSIC / PICTURES / DOWNLOAD / APP_MEDIA / OTHER | .4 | .2 (high .7) | .2 (high .7) | .3 | .6 | .6 |
| UNKNOWN | .5 (high .9) | .5 (high .9) | .5 (high .9) | .5 (high .9) | 1.0 | 1.0 |
| TEMP / TRASH / THUMBNAIL / CACHE / APP_LOG / STATUS / EMPTY | .05 | 0 | 0 | 0 | .1 | .1 |

`low` = 0.5 × mid unless stated; **`high` = min(1, mid + 0.2) unless stated** (REVIEW_2 F-08: the table gave no default high, so
`L.high` — the bound every gate reads — was undefined for most cells). Signals that raise factors (max with the table): `Keywords.financial` hit → financial (.9,.9,1.0);
`Keywords.legal`/`medical`/`identity` → legal (.9,.9,1.0); `Keywords.sentimental` (wedding, baby, family, birthday, funeral, `Family/`
folders) → sentimental (.7,.8,1.0); `CAPTURE` GPS → personal +.1; Phase 6: faces → personal (.8,.9,1.0); Phase 7 categories → per §10.

### 4.2 `Keywords` (closed lists, lower-case, matched on NFC-normalised path tokens split on `/ _ - . space ( )`, plus whole-path substrings for multi-word entries)

* financial: statement, bank, passbook, tax, itr, form16, gst, invoice, receipt, bill, salary, payslip, paystub, policy, insurance,
  premium, loan, emi, mortgage, deposit, w-2, w2, 1099, demat, mutual, pension, epf, uan, fd, sanction.
* legal/identity/medical: passport, aadhaar, aadhar, pan, licence, license, voter, certificate, marksheet, degree, agreement, contract,
  deed, lease, affidavit, will, court, registration, property, birth, marriage, visa, identity, prescription, report (only under
  `Documents/` or with a medical word), discharge, vaccination, lab.
* sentimental: wedding, marriage, baby, family, birthday, anniversary, funeral, memories.
* `Keywords.record(path)` = financial ∪ legal/identity/medical hit.
Lists live in `Keywords` with a test that each word is lower-case ASCII or Devanagari NFC; additions need a dataset re-run.

### 4.3 Formula

```
imp_b  = max(personal_b, financial_b, legal_b, sentimental_b)          for b ∈ {low, mid, high}   (max: the factors overlap; never summed)
repl_b = max(origin_b, replacement_b)                                     (copies that exist elsewhere are NOT counted here — that is R, §5)
dens   = (sizeBytes == 0) ? 0 : 1                                         (density is only "is there any information"; integrity never lowers it, H4b)
L_b    = imp_b × (0.4 + 0.6 × repl_b) × dens                              ∈ [0, 1]
```
Double-counting guard: "uniqueness" in spec 8.2 is split. *Origin* (was the information created on this device: camera, recorder,
screenshots, user documents) is in L. *Existence of other copies* (exact twins, backups, higher-quality versions) is only in R. A test
asserts that adding a VERIFIED exact twin changes `rv` and leaves `lossRange` unchanged.

---

## 5. R — recoverability (`RecoverabilityEstimator`)

### 5.1 States (spec 8.3) — first matching row

| # | `Recoverability` | `recoveryKind` | Condition (all edges must be current, PHASE_3 `edgeCurrent`) | rvMid | rvPess |
|---|---|---|---|---|---|
| 1 | `REGENERABLE` | `REGENERABLE_CACHE` | usage ∈ {THUMBNAIL, CACHE, APP_LOG, STATUS, TEMP} by STRUCT evidence | 1.0 | 1.0 |
| 2 | `REGENERABLE` | `REGENERABLE_INSTALLER` | APK `installState == SAME_VERSION` ∧ `sameSigner == TRUE` ∧ apps availability ≠ UNAVAILABLE (the installed package's own APK is the same content) | 1.0 | 1.0 |
| 3 | `VERIFIED_BACKUP` | `EXACT_COPY_KEPT` | a VERIFIED `EXACT_DUPLICATE` (G1, G2) to the group's keeper, the keeper is eligible and ≠ this file. **The keeper itself does not count its own group's redundant members** (they are about to be offered for removal) | 1.0 | 1.0 |
| 4 | `VERIFIED_BACKUP` | `BACKUP_VERIFIED` | a `BackupRecord` with `state == VERIFIED` (SHA256_MATCH) whose archive is PRESENT, eligible and not itself a removal candidate | 1.0 | 1.0 |
| 5 | `POSSIBLE_BACKUP` | `SUPERSET_COPY` | redundant member of a `CONTENT_EQUIVALENT` group whose keeper is eligible (same picture, kept at ≥ this quality; pixels VERIFIED) **with its own current VERIFIED edge to the keeper's content node** (REVIEW_2 F-02: no reliance on a chain C→B→K, whose small per-edge differences can add up) | 0.7 | 0.5 if edge confidence ≥ 0.8 else 0 |
| 6 | `POSSIBLE_BACKUP` | `UNVERIFIED_COPY` | `EXACT_DUPLICATE` UNVERIFIED (G2 unknown), or `AMBIGUOUS` pair to a larger image | 0.3 | 0 |
| 7 | `POSSIBLE_BACKUP` | `POSSIBLE_ARCHIVE_COPY` | `BackupRecord` POSSIBLE (CRC32 or name+size) | 0.3 | 0 |
| 8 | `NO_VERIFIED_BACKUP` | `NONE` | the graph is complete for this file: hash known or size unique (Phase 2 COLLISIONS policy), and for images `perceptualStatus != UNKNOWN`, and no archive listing pending | 0 | 0 |
| 9 | `UNKNOWN` | null | otherwise (hash failed/pending, image not comparable, HEIC on JVM, budget-pending checks) | 0 | 0 |

`recoveryEvidence` = the edgeIds / backupIds of the matching row. Corrupt or missing backups (`state ∈ {CORRUPT, MISSING}`) are listed
in `recoveryEvidence` with prefix `"x:"` so reasons can explain why there is no backup (scenario 10), and never change the state.
Cloud presence (Telegram, Google Photos) is never a state (spec 2.2).

### 5.2 Use

`rvMid`/`rvPess` enter expected loss (§7). The *state* alone decides tier eligibility (H1: only rows 1–4 can support SAFE/NO_LOSS removals,
and only through the specific candidate rules of §9 that cite them).

---

## 6. C — confidence (`ConfidenceEstimator`)

`C = min(c_extract, c_graph, c_class, c_indep, c_avail)`, then `C = min(C, 0.4)` when `contradiction`.
**REVIEW_2 F-07:** for a *structural* candidate (`Candidate.structural`: S_ rules, `N_EXACT_DUPLICATE`, `N_INSTALLER_INSTALLED`) the
components that do not bear on its evidence are 1.0: `c_extract` (byte equality / location does not depend on content extraction — an
exact copy of a video with PARTIAL extraction is still byte-identical) and `c_avail` (U/L unknowns do not touch a structural claim).
Otherwise HC15 (`C ≥ 0.9` for NO_LOSS) silently blocks exact duplicates of every file whose `extractionConfidence < 0.9`, and the
statement in §13 "exact/installer rules always reach 1.0 by construction" would be false. c_graph still applies (UNVERIFIED → 0.7 → blocked). Minimum, not product: the
weakest component bounds trust, and a product would treat the components as independent probabilities, which they are not.

| Component | Value |
|---|---|
| `c_extract` | `f.extractionConfidence` (Phase 2); NaN → 0.3 |
| `c_graph` | 1.0 if the decision cites no relationship or only VERIFIED ones; 0.7 if it cites an UNVERIFIED one; 0.5 if it depends on uniqueness and `perceptualStatus == UNKNOWN` |
| `c_class` | 1.0 if no classification is cited; else the classifier's declared reliability (RuleClassifier 0.6; models: calibrated precision, §10) |
| `c_indep` | decision supported only by `STRUCT`/verified-`GRAPH`/`APP`-structural facts → 1.0; else by number of distinct families in `familiesFor`: 1 → 0.6, 2 → 0.8, ≥ 3 → 0.9 |
| `c_avail` | `1 − 0.15 × k`, k = number of unknown among {U interval, L interval, R state}; floor 0.4 |

**Contradiction** (spec 8.4): a disposability candidate exists **and** a `familiesAgainst` signal of strength ≥ 0.8 from a family not in
`familiesFor` (e.g. `Y_CHAT_COPY` but the name says "invoice"; a temp file inside `Documents/Bank/`). → `contradiction = true`, the candidate
is replaced by `R_CONTRADICTORY` with params `{a: reason sentence of the candidate, b: the strongest opposing signal}` — except STRUCT
rules S_TEMP_FILE/S_TRASHED/S_THUMBNAIL/S_APP_CACHE_FILE/S_EMPTY_FILE, which are structural facts and are not contradicted by a keyword
(a `.crdownload` named "statement" is still an unfinished download).

---

## 7. I, B, expected loss, action utility, normalisation

### 7.1 I — irreversibility (`IrreversibilityEstimator`)

`I_raw(action)`: DELETE 1.0; QUARANTINE 0.25 (reversible until purge; the residual is the chance the need arises after purge); COMPRESS
lossless 0.0 (execution verifies byte/pixel identity, spec 11.3); COMPRESS lossy 0.0 for information *loss* (accounted in QualityPenalty)
; ARCHIVE 0.1; CLEAR_APP_CACHE 0.0; ARCHIVE_APP 0.05; UNINSTALL_APP 1.0. `Assessment.irreversibility[a] = I_raw(a) × (1 − rvMid)` — the
spec 8.5 examples (cache low, verified duplicate low, unique photo high, original document very high via L) follow from it. Unknown R → rvMid 0
→ I stays at its raw value (pessimistic).

### 7.2 B — storage benefit (`BenefitEstimator`)

| Action | B (bytes) | Exact? |
|---|---|---|
| DELETE, QUARANTINE | `sizeBytes` for a canonical, PRESENT file; 0 for aliases and 0-byte files; `Unknown.LONG` if size unknown. Quarantine frees it only at purge (`Recommendation.reasonParams.deferred = "purge"`) | yes |
| COMPRESS | `sizeBytes − estimatedOutput − 4096`, `estimatedOutput` from `CompressionEstimates` (classic `core.Estimates` ratios re-declared as v2 constants: WAV→FLAC 0.55; PNG screenshot lossless (zopfli-class) 0.80; BMP/TIFF→PNG 0.45; lossy photo/video ratios from classic, Phase 6+ only); ≤ 0 → candidate dropped | no (`exact=false`) |
| ARCHIVE | `Σ size × (1 − ratio(type))`, ratio: txt/csv/log/json/xml 0.25, bmp/wav/uncompressed tiff 0.6, everything else 1.0 (not archived) | no |
| CLEAR_APP_CACHE | `InstalledApp.cacheBytes` (−1 → unknown → no candidate) | yes |
| ARCHIVE_APP | `codeBytes` | yes |
| UNINSTALL_APP | `codeBytes + dataBytes` | yes |

Spec 8.6 "actual usable storage": B never counts aliases, never counts both an exact copy and its keeper, and the planner (Phase 5) never
counts a file twice across groups. Allocation rounding is not modelled (sizes are what the evaluator compares).

### 7.3 Expected loss, uncertainty penalty, utility (`ExpectedLoss`, `ActionUtility`)

```
EL_mid(a)  = U90.mid  × L.mid  × (1 − rvMid)  × I_raw(a)
EL_pess(a) = U90.high × L.high × (1 − rvPess) × I_raw(a)
UncertaintyPenalty(a) = EL_pess(a) − EL_mid(a)                  (≥ 0; zero when nothing is uncertain)
Bn(a) = B / (B + B0),  B0 = 50 MiB;  B unknown → Bn = 0           (concave: 50 MiB ≈ 0.5, 5 MB ≈ 0.09, 500 MB ≈ 0.91)
ExecutionCost: DELETE 0.001, QUARANTINE 0.002, COMPRESS 0.01 + 0.02 × size/1 GiB, ARCHIVE 0.01, app actions 0.001
QualityPenalty: lossy COMPRESS 0.1 × L.mid; else 0
ActionUtility(a) = Bn(a) − EL_mid(a) − ExecutionCost(a) − QualityPenalty(a) − UncertaintyPenalty(a)
                 = Bn(a) − EL_pess(a) − ExecutionCost(a) − QualityPenalty(a)
```
Normalisation (spec 9.3): every term is a dimensionless number in [0, 1] on one scale, "probability-weighted fraction of a maximal loss".
`Bn` maps bytes onto that scale with one constant (`B0`): freeing `B0` is worth as much as a 0.5 probability of a maximal loss. `B0` is a
user-preference constant, not a fact; it only orders YOUR_CALL items and gates them (`utility > 0`), it never makes anything SAFE/NO_LOSS.
Horizon: tiering uses U90 (spec lists horizons; 90 days matches the quarantine retention of Phase 9); U30/U365/U1095 are reported.

---

## 8. Hard safety constraints (`HardRules`) — evaluated before any scoring

Single implementation, two entry points: `Constraint constraints(FileContext ctx)` (stage 2, before scores) and
`List<String> check(FileRecord f, Assessment a, GraphView g, Action action, Tier proposed)` (ARCHITECTURE §7 signature; used by
`TierAssigner`, again by Phase 5 `PlanValidator`). No mode parameter (H7). Rules are evaluated **in this order, all of them**; each
contributes forbidden (action, tier) combinations, a forced tier, or both; the result is the most restrictive combination.

| # | Rule (ARCH id) | Condition | Effect | Code |
|---|---|---|---|---|
| HC1 | protection (H6) | `protection != NONE` | forced PROTECTED; every action except KEEP forbidden | `P_USER_PROTECTED` |
| HC2 | identity | not PRESENT, stale fingerprint, non-canonical alias | no verdict at all | — |
| HC3 | readability | PRESENT and (`readable == FALSE` or `extractionStatus == UNREADABLE`) | forced REVIEW, no action | `R_UNREADABLE` |
| HC4 | dependency (H8) | file is a `COMPANION_OF` sidecar of a primary that is not itself a removal candidate in the same bundle, or the `dst` of a `COMPANION_OF` from an obb/split whose package is installed | DELETE/QUARANTINE forbidden at every tier unless bundled (Phase 5 bundles) | `H_DEPENDENCY` |
| HC5 | last verified copy (H1) | removal at SAFE/NO_LOSS without (a) an `S_` structural rule or (b) a VERIFIED interchangeable copy (R rows 2–4) that is present, eligible and kept | forbidden | `H_LAST_VERIFIED_COPY` |
| HC6 | similarity (H2) | the removal's justification cites VISUALLY_SIMILAR/SEMANTICALLY_SIMILAR/EDITED_FROM/COMPRESSED_FROM/THUMBNAIL_OF/VERSION_OF | SAFE/NO_LOSS forbidden (YOUR_CALL allowed) | `H_SIMILAR_NOT_IDENTICAL` |
| HC7 | classification (H3) / age (H10) | the removal's non-STRUCT support consists only of `CONTENT` signals, or only of `TIME` (+ size) signals | any removal forbidden; and SAFE/NO_LOSS forbidden whenever CONTENT/TIME is cited at all | `H_CLASSIFICATION_ONLY` |
| HC8 | unknown features (H4) | content features unknown (`extractionStatus ∈ {PARTIAL, FAILED, UNSUPPORTED}`, image pixels unknown, video frames unknown) | SAFE/NO_LOSS forbidden unless the rule is an `S_` structural rule or `N_EXACT_DUPLICATE` (byte equality needs no content); YOUR_CALL forbidden if the candidate's own evidence needs the unknown feature; **lossy COMPRESS forbidden** (scenario 8) | `H_UNKNOWN_FEATURES` |
| HC9 | integrity (H4b) | `integrity ∈ {TRUNCATED, CORRUPT, ENCRYPTED}` or archive `listingStatus ∈ {LIMIT, CORRUPT}`, `bombSuspect/pathTraversal/absolutePaths == TRUE`, `nestedListed < nestedArchives`, or any `BackupRecord` of one of its entries in state `CORRUPT` (PHASE_3 §6) | forced REVIEW (`R_UNREADABLE` / `R_ENCRYPTED` / `R_ARCHIVE_UNSAFE`) unless an `S_` structural rule or `N_EXACT_DUPLICATE` applies; the verdict never lowers U/L, never raises R | as listed |
| HC10 | app visibility | `S_UNINSTALLED_LEFTOVER` while `InstalledApps.availability() != FULL`; any rule citing "not installed" while not FULL | forbidden; REVIEW `R_UNKNOWN_DEPENDENCY` instead | `H_UNKNOWN_FEATURES` |
| HC11 | installers not installed (S07) | APK/xapk with `installState == NOT_INSTALLED` or `UNKNOWN`, any non-Android installer | every removal forbidden at every tier and in both modes (redownloadability cannot be known offline) → REVIEW for APKs | `R_INSTALLER_NOT_INSTALLED` |
| HC12 | personal capture quality (S01) | usage CAMERA_PHOTO/CAMERA_VIDEO/VOICE and the removal's evidence is PIXEL quality (sharpness, exposure) | forbidden, except the "nothing visible" rule (`Y_LOW_QUALITY`, §9.2) | `H_CLASSIFICATION_ONLY` |
| HC13 | keepers | file is the keeper of any group | removal forbidden; COMPRESS/ARCHIVE forbidden while any group member has a removal candidate (would break V4) | `H_LAST_VERIFIED_COPY` |
| HC14 | obb / app data | `Android/obb/**`, `Android/data/<installed pkg>/files/**` | removal forbidden except `S_UNINSTALLED_LEFTOVER` for `Android/data` (never obb) | `H_DEPENDENCY` |
| HC15 | confidence floor | `C < 0.9` for NO_LOSS; `C < policy.cYourCall` for YOUR_CALL | that tier forbidden for the candidate | — (the candidate falls through) |

`Constraint.floorTier` = most aggressive tier any removal may take (e.g. HC6 → YOUR_CALL; HC9 → REVIEW). A forced tier (HC1, HC3, HC9,
HC11) ends tiering with that tier and reason.

---

## 9. Candidate generation and tier assignment

### 9.1 `Candidate`

```java
public final class Candidate {
    public Action action; public Tier proposed; public String reasonCode;
    public java.util.Map<String,String> params; public java.util.List<String> evidenceRefs;
    public java.util.EnumSet<EvidenceFamily> families;   // families supporting it
    public java.util.List<String> dependencyIds;        // files that must stay (keeper, primary)
    public java.util.List<String> bundle;               // files that must be removed together (split APKs, sidecars of a removed primary)
    public boolean structural;                           // S_ rules and verified exact/installer rules
    public String fallbackCode; public Tier fallbackTier; // what it becomes when blocked (e.g. N_EXACT_DUPLICATE -> R_UNCERTAIN_COPY / REVIEW); null = dropped
}
```

### 9.2 `CandidateRules` (closed list; new codes marked ★, catalogue in §11)

| Code | Tier | Action | Condition | Families | Fallback when blocked |
|---|---|---|---|---|---|
| `S_TEMP_FILE` | SAFE | DELETE | usage TEMP (incl. the F-05 content clause of §3.1) and mtime age ≥ 24 h | STRUCT | dropped |
| `S_TRASHED` | SAFE | DELETE | usage TRASH | STRUCT | dropped |
| `S_THUMBNAIL` | SAFE | DELETE | usage THUMBNAIL | STRUCT | dropped |
| `S_APP_CACHE_FILE` | SAFE | DELETE | usage CACHE | STRUCT | dropped |
| `S_APP_LOG` ★ | SAFE | DELETE | usage APP_LOG, mtime age ≥ 24 h | STRUCT | dropped |
| `S_EXPIRED_STATUS` ★ | SAFE | DELETE | usage STATUS, mtime age ≥ 24 h, name ≠ `.nomedia` | STRUCT | dropped |
| `S_EMPTY_FILE` ★ | SAFE | DELETE | `sizeBytes == 0`, regular file, name not starting with `.`, not under `Android/(data|obb)`, ext ∉ {nomedia, lock} | STRUCT | dropped |
| `S_UNINSTALLED_LEFTOVER` | SAFE | DELETE | area `residue` under `Android/data/` only, non-media type, apps FULL | STRUCT+APP | `R_UNKNOWN_DEPENDENCY`/REVIEW |
| `N_EXACT_DUPLICATE` | NO_LOSS | DELETE | redundant member of an exact group, edge to keeper VERIFIED, keeper eligible, `rvPess == 1`. **Lead ruling on REVIEW_2 F-06** (spec 9.4 stands): different names do **not** change the tier. The keeper is chosen by name and location (PHASE_3 §8.1). When `NameStem(copy) ≠ NameStem(keeper)`, the rec gets the secondary, explanatory code `N_SAME_CONTENT_DIFFERENT_NAME` with params `{name, keeperName}` | GRAPH(verified) | `R_UNCERTAIN_COPY`/REVIEW |
| `N_INSTALLER_INSTALLED` | NO_LOSS | DELETE | R row 2; bundle = its split companions (all must satisfy row 2) | APP | `Y_INSTALLER_UNVERIFIED`/YOUR_CALL |
| `N_LOSSLESS_SMALLER` | NO_LOSS | COMPRESS | type ∈ {wav, bmp, tiff-uncompressed, png (screenshot area)}, B > 1 MiB and B ≥ 10 % of size | STRUCT | dropped |
| `Y_CHAT_COPY` | YOUR_CALL | DELETE | R row 5 with the member in a chat area or WhatsApp name | GRAPH+PROV | `R_UNCERTAIN_COPY`/REVIEW (aggressive) or dropped |
| `Y_SMALLER_COPY` ★ | YOUR_CALL | DELETE | R row 5, not chat (resized copies, small copies outside thumbnail folders) | GRAPH | as above |
| `Y_CONVERTED_COPY` ★ | YOUR_CALL | DELETE | R row 5 via `VERSION_OF` (format conversion) | GRAPH | as above |
| `Y_SIMILAR_SHOT` | YOUR_CALL (aggressive) | DELETE | non-keeper member of a `BURST` group | GRAPH+CAPTURE(time) | — |
| `R_SIMILAR_SHOT` ★ | REVIEW (standard) | REVIEW | same as `Y_SIMILAR_SHOT` | GRAPH+CAPTURE | — |
| `Y_OLDER_INSTALLER` | YOUR_CALL | DELETE | `installState == INSTALLED_NEWER`, `sameSigner == TRUE` | APP | REVIEW `R_INSTALLER_SIGNER_MISMATCH` |
| `Y_INSTALLER_UNVERIFIED` ★ | YOUR_CALL | DELETE | `SAME_VERSION`, `sameSigner == UNKNOWN` | APP | REVIEW |
| `R_INSTALLER_SIGNER_MISMATCH` ★ | REVIEW | REVIEW | installed and `sameSigner == FALSE` | APP | — |
| `R_INSTALLER_NEWER` ★ | REVIEW | REVIEW | `installState == INSTALLED_OLDER` (APK newer than installed) | APP | — |
| `R_INSTALLER_NOT_INSTALLED` ★ | REVIEW | REVIEW | HC11 | APP | — |
| `Y_OLD_CHAT_BACKUP` ★ | YOUR_CALL | DELETE | `VERSION_OF` (BACKUP_NAME_DATE) to a PRESENT newer `msgstore` backup whose size ≥ 0.5 × this one | GRAPH+PROV | dropped |
| `Y_LEFTOVER_MEDIA` | YOUR_CALL | DELETE | area `residue` under `Android/media/`, apps FULL | STRUCT+APP | REVIEW `R_UNKNOWN_DEPENDENCY` |
| `Y_LOW_QUALITY` | YOUR_CALL | DELETE | image with `contrast < 4`, or `contrast < 10` and (`clipLow ≥ 0.9` or `clipHigh ≥ 0.9`) — "almost nothing visible"; params `problem` = dark/bright/blank | PIXEL | dropped |
| `R_OLD_DOWNLOAD` ★ | REVIEW (aggressive only) | REVIEW | usage DOWNLOAD, type video/audio/archive, ≥ 10 MiB, age ≥ 180 d; never documents or installers | TIME+PROV | — |
| `Y_OLD_DOWNLOAD`, `Y_OLD_SCREENSHOT`, `Y_FORWARDED_MEDIA` | YOUR_CALL | DELETE | need a `CONTENT` signal plus a second family — **not produced until Phase 6** (no CONTENT family yet) | — | — |
| `R_UNCERTAIN_COPY` | REVIEW | REVIEW | derived member of an `AMBIGUOUS` pair, or non-keeper of an UNVERIFIED exact group, or any member of a `keeperFallback` group | GRAPH | — |
| `R_ENCRYPTED`, `R_UNREADABLE`, `R_ARCHIVE_UNSAFE` | REVIEW | REVIEW | HC3/HC9 | STRUCT | — |
| `R_NAME_CONTENT_MISMATCH` | REVIEW | REVIEW | `extMismatch == SUSPICIOUS`, or a temp/partial name holding a complete content file (§3.1 row 3, F-05) | STRUCT | — |
| `R_UNKNOWN_DEPENDENCY` | REVIEW | REVIEW | `Android/obb/<pkg>` residue with no APK of `pkg` present; fallbacks above | STRUCT+APP | — |
| `P_UNIQUE_RECORD` | PROTECTED | KEEP | usage DOCUMENT_RECORD (or CHAT_BACKUP current `msgstore.db.crypt*`), `lossRange.high ≥ 0.8`, R ∉ {VERIFIED_BACKUP, REGENERABLE}, not a redundant member; param `category` = financial/legal/medical/identity/chat backup | PROV | — |
| `P_ARCHIVE_UNIQUE_CONTENT` | PROTECTED | KEEP | listed archive (OK) with ≥ 1 entry of document type or record keyword that no PRESENT file matches (no BackupRecord of state ≥ POSSIBLE points at it); param `n` = count | GRAPH+PROV | — |
| `P_CRITICAL_DEPENDENCY` | PROTECTED | KEEP | obb/split that is `COMPANION_OF` a PRESENT APK whose package is not installed (reinstalling needs it), or `DEPENDS_ON` dst (none yet) | GRAPH | — |
| `P_PERSONAL_MEDIA_UNIQUE` | PROTECTED | KEEP | needs people evidence (faces, Phase 6) — not produced in Phase 4/5 | — | — |

Keeper annotation (no recommendation): a group keeper gets **no** Recommendation record; `files[].assessment.keeperOf` lists its groups and
`G_KEEPER_*` reasons for the UI. (The catalogue's `P_KEEPER` becomes an annotation code, §15 B-3: the dataset expects most originals to be
KEEP, and a PROTECTED tier on every keeper would be over-caution on 300+ files.)

### 9.3 `TierAssigner.assign` — deterministic decision table

Evaluated top-down; the first row that fires decides the file's **removal-class** verdict. Transform candidates (COMPRESS/ARCHIVE) are
decided separately in row T.

| Row | Condition | Tier | Reason (primary) |
|---|---|---|---|
| 1 | HC2 fires | — (no verdict) | — |
| 2 | HC1 | PROTECTED | `P_USER_PROTECTED` |
| 3 | an `S_` candidate passes `HardRules.check` | SAFE | that `S_` code (several: first in §9.2 order) |
| 4 | an `N_` removal candidate passes `check` (incl. HC15 `C ≥ 0.9`) | NO_LOSS | that `N_` code |
| 5 | forced REVIEW (HC3, HC9, HC11) | REVIEW | forced code |
| 6 | a `P_` rule fires | PROTECTED | that `P_` code |
| 7 | contradiction on any remaining removal candidate | REVIEW | `R_CONTRADICTORY` |
| 8 | a `Y_` candidate passes `check` ∧ `EL_pess(DELETE) ≤ policy.tYourCall` ∧ `C ≥ policy.cYourCall` ∧ `utility(DELETE) > 0` | YOUR_CALL | best `Y_` by utility, tie by §9.2 order |
| 9 | a blocked candidate has `fallbackTier == REVIEW`, or a `Y_` candidate failed row 8 and `policy.showFailedYourCallAsReview` | REVIEW | fallback code / `R_UNCERTAIN_COPY` with `{other}` |
| 10 | an `R_` candidate (non-forced) exists and is shown by the policy | REVIEW | that code |
| 11 | otherwise | KEEP (no removal recommendation) | — |
| T | independently: `N_LOSSLESS_SMALLER` passes `check` and rows 1–7 did not fire (no SAFE/NO_LOSS removal, not REVIEW/PROTECTED) | NO_LOSS (COMPRESS) | `N_LOSSLESS_SMALLER` |

REVIEW_2 F-10: a transform replaces the original file. Row T additionally requires `lossRange.high < 0.8` (no transform is proposed on
a likely record: payment screenshots, scanned receipts); and every transform rec carries `reasonParams.keepOriginal = "until-purge"`,
binding on Phase 9: the original goes to quarantine (not deleted) after the output is verified, and is purged only with the normal
retention. Lossy transforms remain forbidden under HC8 and, from Phase 6, on any file with `lossRange.high ≥ 0.8`.

Every verdict carries ≥ 1 reason code, every `{param}` of its template filled (`ReasonCatalogTest`), the evidence refs of the chosen
candidate (edgeIds, backupIds, `hash:<fileId>`), and the `hardBlocks` collected on the way (blocked candidates are explained, e.g. "Similar
is not identical, so it is only offered as your call").

### 9.4 Standard vs aggressive (`PresentationPolicy`) — thresholds only (spec 9.5, H7)

| Knob | STANDARD | AGGRESSIVE |
|---|---|---|
| `tYourCall` (max `EL_pess` for YOUR_CALL) | 0.25 | 0.40 |
| `cYourCall` (min C for YOUR_CALL) | 0.50 | 0.35 |
| bursts (`BURST` non-keepers) | REVIEW `R_SIMILAR_SHOT` | YOUR_CALL `Y_SIMILAR_SHOT` |
| `showFailedYourCallAsReview` | false (dropped → KEEP) | true (REVIEW with uncertainty explained) |
| `R_OLD_DOWNLOAD` | off | on (REVIEW only) |
| `HardRules`, S_/N_ rules, P_ rules, HC11 | identical | identical |

A property test runs both policies on random contexts and asserts `HardRules` output is identical and that every aggressive verdict is
either the standard verdict or one listed row change above.

---

## 10. Classifier plug point (A7: deterministic now, model later)

```java
package com.storageplanner.v2.content;
public interface ContentClassifier {
    String id(); int version();
    EvidenceFamily family();                // RuleClassifier: PROV; Phase 6 OCR rules: CONTENT; Phase 7 models: CONTENT
    double reliability();                   // calibrated precision on the dataset at its operating point; c_class uses it
    Classification classify(com.storageplanner.v2.risk.FileContext ctx);   // null = no opinion; must not do I/O except via ctx.device
}
public final class Classification { public String category; public double confidence; public String source; public java.util.List<String> evidence; }
```
Registered in `RiskConfig.classifiers` (ordered). Allowed effects of a classification: raise L factors (§4.1); raise U multipliers in
[1, 2]; fire `P_UNIQUE_RECORD`; support a `Y_` candidate **only together with a second family** (HC7/H3). Forbidden: lowering L, lowering U
below the class prior, any SAFE/NO_LOSS, `contradiction` suppression. A model ships only if it improves a §10 metric by a pre-registered
margin (ARCHITECTURE Phase 7) and its `reliability()` is measured, not declared.

---

## 11. Reason codes added to the catalogue (ARCHITECTURE §6)

| Code | Tier | Sentence |
|---|---|---|
| `S_APP_LOG` | SAFE | "Log file written by {app}; apps do not need old logs." |
| `S_EXPIRED_STATUS` | SAFE | "Viewed WhatsApp status older than a day; WhatsApp no longer shows it." |
| `S_EMPTY_FILE` | SAFE | "Empty file (0 bytes); it holds no content." |
| `Y_SMALLER_COPY` | YOUR_CALL | "Smaller copy of {original}, which is kept at full quality." |
| `Y_CONVERTED_COPY` | YOUR_CALL | "{format} copy of {original}, which is kept; the pictures look the same." |
| `Y_INSTALLER_UNVERIFIED` | YOUR_CALL | "Installer for {app} {version}, which is installed, but we could not confirm it comes from the same developer." |
| `Y_OLD_CHAT_BACKUP` | YOUR_CALL | "Older chat backup from {date}; WhatsApp restores from the newest one ({newer}). Backups are encrypted, so we could not check it." |
| `R_SIMILAR_SHOT` | REVIEW | "One of {n} nearly identical shots taken within seconds; {keeper} looks best. Pick the ones you want to keep." |
| `R_INSTALLER_NOT_INSTALLED` | REVIEW | "Installer for {app}, which is not installed. We cannot tell whether it can be downloaded again." |
| `R_INSTALLER_SIGNER_MISMATCH` | REVIEW | "Installer for {app}, but signed by a different developer than the installed app." |
| `R_INSTALLER_NEWER` | REVIEW | "Installer for a newer {app} ({version}) than the one installed ({installed})." |
| `R_OLD_DOWNLOAD` | REVIEW | "Large {kind} downloaded {age} ago; Android does not tell us whether you opened it since." |
| `P_KEEPER` | — (annotation) | unchanged sentence; no longer a recommendation tier |
| `N_SAME_CONTENT_DIFFERENT_NAME` (F-06, secondary reason, never primary, no tier effect) | NO_LOSS | "Same content as {keeperName}, which is kept; only the name differs ({name})." |

`Y_INSTALLER_NOT_INSTALLED` stays in the catalogue unused (HC11 makes it unreachable; retiring it would break the "codes never removed" rule).

---

## 12. Scenarios 1–11 (spec 16.4) — required outcome and the rule that guarantees it

| # | Scenario (dataset id) | Required outcome (dataset acceptable tiers) | Guaranteed by | Phase-4/5 expectation |
|---|---|---|---|---|
| 1 | Blurry important family photo (S01) | never SAFE/NO_LOSS/YOUR_CALL; PROTECTED/KEEP/REVIEW | HC12 (quality never decides for personal capture), HC5 (no verified copy) | KEEP (PROTECTED needs faces, Phase 6); pocket shots → `Y_LOW_QUALITY` |
| 2 | Old financial document (S02) | PROTECTED/KEEP | HC7/H10 (age never decides), DOCUMENT classes have no age decay, no removal rule exists for unique documents | `P_UNIQUE_RECORD` when the path/name carries a record keyword, else KEEP; `Download/temp123.tmp` (a PDF statement with a temp name) → never `S_TEMP_FILE` (F-05): if it is the only copy → `R_NAME_CONTENT_MISMATCH` REVIEW; if it is byte-identical to a verified, physically distinct, properly named copy → ordinary `N_EXACT_DUPLICATE` NO_LOSS with that file as keeper (`nameRank`/`locRank`, PHASE_3 §8.1) plus `N_SAME_CONTENT_DIFFERENT_NAME`. Byte-identical documents with different names (the dataset's invoice pair is a generator bug the lead will fix) → NO_LOSS for the non-keeper with the explanatory code. All three are fixtures in `ScenarioFixtureTest` |
| 3 | Exact duplicate, original kept (S03) | copy deletable without loss; original kept | `N_EXACT_DUPLICATE` (G1, G2, H1), keeper order (PHASE_3 §8.1), HC13 | copy NO_LOSS (dataset "SAFE" ≡ v2 SAFE∪NO_LOSS, PHASE_5 §9.1), keeper KEEP |
| 4 | Near duplicate with meaningful edits (S04) | edits not interchangeable; bursts REVIEW | PHASE_3 provenance rule + edit families have no redundant members; HC6 | edits KEEP; burst siblings REVIEW (`R_SIMILAR_SHOT`) |
| 5 | WhatsApp copy of a unique photo (S05) | only copy never deleted; with original present the copy is SAFE/YOUR_CALL | `Y_CHAT_COPY` requires R row 5 (a kept higher-quality copy); HC6 caps at YOUR_CALL; no other rule touches chat media before Phase 6 | unique copies KEEP; copies with original YOUR_CALL |
| 6 | APK of an installed app (S06) | same version+signer SAFE; older same signer SAFE/YOUR_CALL; other signer / newer REVIEW | `N_INSTALLER_INSTALLED` (R row 2), `Y_OLDER_INSTALLER`, `R_INSTALLER_SIGNER_MISMATCH`, `R_INSTALLER_NEWER` | as required |
| 7 | Old installer that cannot be redownloaded (S07) | REVIEW/PROTECTED, never SAFE (and never YOUR_CALL: must_never_delete) | HC11 (every not-installed installer → REVIEW, both modes), HC14 + `P_CRITICAL_DEPENDENCY` for its obb, `.exe` installers: no rule removes them | REVIEW; obb PROTECTED |
| 8 | Long video with brief activity (S08) | never SAFE/NO_LOSS; mnd videos never YOUR_CALL | HC8 (video frames unknown → no SAFE/NO_LOSS, no lossy COMPRESS), no removal rule for camera video | KEEP until Phase 6 sampling |
| 9 | Encrypted PDF (S09) | REVIEW/PROTECTED/KEEP | HC9 forced REVIEW `R_ENCRYPTED` | REVIEW |
| 10 | Backup with missing/corrupt content (S10) | originals not "backed up"; problem backups REVIEW | R row 4 needs `BackupRecord.state == VERIFIED` (SHA-256); HC9 for corrupt archives; no rule deletes an original because of a backup | originals KEEP; Gallery zip REVIEW (`R_ARCHIVE_UNSAFE` from the CRC failure); tar.gz REVIEW/KEEP (unparsed until Phase 6, unknown) |
| 11 | Archive with important documents (S11) | important zips PROTECTED; unsafe zips REVIEW; loose copies KEEP | `P_ARCHIVE_UNIQUE_CONTENT`, HC9 (`R_ARCHIVE_UNSAFE` for bomb/traversal/nested), R row 4 removes `P_UNIQUE_RECORD` from loose files that are verified in a zip (KEEP is acceptable) | as required; junk sticker zip KEEP (needs Phase 6) |

---

## 13. What is heuristic, and what each number affects

| Constant | Value | Affects | Safety-relevant? | Calibration path |
|---|---|---|---|---|
| λ table (§3.1), age factors | heuristic | U mid/low; ranking of YOUR_CALL | no (gates use U.high, which ignores age) | Phase 8 outcomes (kept vs deleted vs restored) → per-class rates |
| L factor table (§4.1) | heuristic | `EL_pess` gate for YOUR_CALL; `P_UNIQUE_RECORD` threshold 0.8 | partly: a too-low L.high could admit a YOUR_CALL. Guard: YOUR_CALL also needs an explicit `Y_` reason with GRAPH/APP/PIXEL evidence | dataset tier confusion; Phase 8 |
| rv values (§5.1) | heuristic for rows 5–7; exact (1/0) for 1–4, 8–9 | EL only | no (tiers read states, not rv) | — |
| `tYourCall` 0.25 / 0.40, `cYourCall` 0.5 / 0.35 | heuristic | how many Y_ items show | no (YOUR_CALL needs approval; mnd guarded by HC11/HC12/HC8 and Y_ rule conditions) | sweep on dataset: maximise YOUR_CALL precision ≥ 0.80 with exposure 0 |
| `B0` 50 MiB | preference | YOUR_CALL ranking, utility > 0 gate | no | owner preference |
| C component values | heuristic | NO_LOSS floor 0.9 (exact/installer rules always reach 1.0 by construction), YOUR_CALL floor | no for NO_LOSS (structural), mild for YOUR_CALL | dataset |
| `Keywords` lists | heuristic | `P_UNIQUE_RECORD`, contradictions | protective direction only | dataset + Phase 6 OCR |

---

## 14. Tests (`jvmtest/.../v2test/risk/`)

| Test | Cases |
|---|---|
| `UnknownInputPropertyTest` (U1, H4) | for each estimator: random contexts; set any input to unknown → `U.high`, `L.high`, `I` never decrease, `rvPess` never increases, `C` never increases; tier never moves to a lower rank |
| `UtilityEstimatorTest` | each class row; age decay applies only to flagged classes and never to `high`; documents: 10-year-old statement has the same `U.high` as a new one; λ=0 classes → 0 |
| `LossEstimatorTest` | keyword hits raise factors; unknown content → high .9; adding a VERIFIED twin leaves `lossRange` unchanged (double-count guard); 0-byte → 0 |
| `RecoverabilityTest` | each row of §5.1 incl. keeper not counting its own group, CORRUPT/MISSING backups never VERIFIED, stale edge ignored, G2-unknown exact → POSSIBLE |
| `ConfidenceTest` | min-combination; contradiction caps at 0.4; independence count per family (two PROV signals count once) |
| `ExpectedLossTest` | formulas, `UP ≥ 0`, `utility = Bn − EL_pess − EC − QP`, normalisation monotone in B |
| `HardRulesTest` | one positive and one negative case per HC1–HC15; `check` and `constraints` agree; no mode parameter (reflection-free: compile-time signature test) |
| `TierAssignerTableTest` | one fixture per decision-table row 1–11 and T; first-match order (S beats N; forced REVIEW beats P; P beats Y) |
| `PolicyInvarianceTest` (H7) | 5 000 random contexts × both policies: identical `HardRules`, aggressive differs only in §9.4 rows |
| `ClassifierStubTest` (H3) | stub classifier returning "meme 0.99" for every file → zero new SAFE/NO_LOSS, zero YOUR_CALL whose only non-STRUCT family is CONTENT |
| `ScenarioFixtureTest` | synthetic fixtures for scenarios 1–11 (§12) assert the required tier and primary code |
| `ReasonCatalogTest` | every code emitted exists; params complete |
| `DatasetAssessTest` (dataset present) | §16 gates via `evaluate.py --gate phase4` |

---

## 15. ARCHITECTURE changes

| Id | Section | Edit |
|---|---|---|
| B-1 | §2.9 | add the `Assessment` fields of §1.2 (`lossRange`, `rvMid`, `rvPess`, `recoveryKind`, `elMid`, `elPess`, `utility`, `confidenceParts`, `contradiction*`, `families*`, `usage`) |
| B-2 | §6 | add the codes of §11 |
| B-3 | §6 | `P_KEEPER` tier column → "— (annotation)"; ARCHITECTURE §12 row 3 "original PROTECTED (`P_KEEPER`)" → "original KEEP, annotated `P_KEEPER`" |
| B-4 | §7 | add HC11–HC15 as H11–H15 (installers not installed; personal-capture quality; keeper transforms; obb/app data; confidence floors) and the HC8 clause "lossy COMPRESS forbidden when content features are unknown" |
| B-5 | §8 Risk bullet | replace `Rv` values by §5.1 (state-based tiers, rv only in EL), replace `λ × (U90.high − U90.low) × L` by `UP = EL_pess − EL_mid` (§7.3) |
| B-6 | §12 row 6 | "cert unknown → YOUR_CALL" stays (`Y_INSTALLER_UNVERIFIED`); add "not installed → REVIEW (`R_INSTALLER_NOT_INSTALLED`), never YOUR_CALL" |
| B-7 | §1 package map | `v2.plan.TierAssigner`, `PresentationPolicy`, `ReasonCatalog` are built in Phase 4 |
| B-8 | §11 Phase 4 row | exit criteria of §16 |
| B-9 (REVIEW_2) | §7 H4/H10 notes, §6 | SAFE "structural" means location **and** type (ARCHITECTURE §2.11 already says so): a temp *name* over a complete content file is not structural (F-05); add the explanatory code `N_SAME_CONTENT_DIFFERENT_NAME` (F-06 as ruled by the lead); exact keeper order of PHASE_3 §8.1 replaces ARCHITECTURE §8's; structural candidates' confidence ignores `c_extract`/`c_avail` (F-07) |

---

## 16. Exit criteria (Phase 4), on `/home/user/sp-dataset` with `installed_apps.json`, `--mode standard` and `aggressive`

Phase 4 emits *provisional* recommendations = one per non-KEEP verdict (no planner, no hashing on demand).

| Metric | Gate | Expected |
|---|---|---|
| DDRR (must_never_delete with SAFE/NO_LOSS removal) | **0**, both modes | 0 |
| exposure (must_never_delete with any removal incl. YOUR_CALL) | **0**, both modes | 0 (HC11, HC12, HC8; no Y_ rule fires on unique media/docs) |
| must_never_delete coverage | 178/178 have `protection != NONE` or `lossRange.high ≥ 0.5` or `recoverability == VERIFIED_BACKUP` | 178/178 (pessimistic highs) |
| `P_UNIQUE_RECORD` on dataset financial/id documents (S02 files) | ≥ 0.6 of S02 files PROTECTED, the rest KEEP | ≈ 0.75 (Documents/Bank, Finance, Tax, Old Papers names carry keywords) |
| scenarios 1–11 (PHASE_5 §9.3 rule) | no FAIL | 0 FAIL |
| HardRules invariance | identical blocks across modes on the dataset | |
| unknown-input property tests | pass | |
| determinism | byte-identical `assessment` JSON across two runs | |
| runtime | ASSESS ≤ 5 s on the dataset (JVM) | |

---

## 17. Design choices

| # | Choice | Rejected alternative | Why |
|---|---|---|---|
| P4-1 | Tiers decided by evidence *kind* (structural / verified / plausible) in a first-match table; scores only gate and rank inside YOUR_CALL | A single risk score with tier cut-offs | Uncalibrated scores cannot carry a safety guarantee; the spec's categories are defined by evidence type (9.4), and H1–H10 are categorical. |
| P4-2 | Uncertainty penalty = `EL_pess − EL_mid`, so utility uses the pessimistic expected loss | `λ × (U.high − U.low) × L` (ARCHITECTURE draft) | Ours covers uncertainty in L and R too and is zero exactly when nothing is unknown; one less constant. |
| P4-3 | Correlated evidence: families with max-within, clamped product across, confidence counts families | Naïve-Bayes product of all signals | Path and name, or two outputs of one model, are not independent; products overstate certainty (spec 9.2). |
| P4-4 | Copies of the information live only in R; L keeps "origin" | Uniqueness in both L and R | Otherwise a verified twin lowers expected loss twice. |
| P4-5 | Age moves only U.mid/low | Age decays all bounds | H10 by construction: no age value can push a file through a gate that reads the pessimistic bound. |
| P4-6 | Not-installed installers are REVIEW in both modes (no YOUR_CALL) | `Y_INSTALLER_NOT_INSTALLED` (catalogue) | Redownloadability is unknowable offline (no INTERNET permission) and S07 files are must-never-delete; the dataset's `store_availability` is an oracle v2 must not read. |
| P4-7 | Keepers get an annotation, not a PROTECTED recommendation | `P_KEEPER` at PROTECTED | Nothing proposes removing a keeper (HC13), so a tier adds no safety, and 80 % of dataset originals are labelled KEEP-only. |
| P4-8 | `RuleClassifier` counts as PROV, not CONTENT | Treat it as a classifier family | It reads the same path the PROV signals read; counting it twice would inflate confidence. |
| P4-9 | No YOUR_CALL from quality for camera photos/videos except "nothing visible" | Classic BLURRY (sharpness < 0.25 × median) as R4 | Scenario 1; blur says nothing about value (spec 16.4), and faces are not available until Phase 6. |
