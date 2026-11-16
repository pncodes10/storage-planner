# REVIEW_6 — independent review of `PHASE_6.md` (content signals)

Reviewer: independent design reviewer. Inputs: spec §5.2–5.5, 7, 9.4–9.5, 14.1, 16.4; `MANAGER_LOG.md` (A39–A43); `ARCHITECTURE.md`;
the code under `app/src/main/java/com/storageplanner/{v2,core,app}`; the rebuilt dataset `/home/user/sp-dataset` (2,409 entries).
Fixes are applied in place in `PHASE_6.md`, tagged `REVIEW_6 F-…`. No production code was written; nothing committed.

## Verification done

* **Video claim — confirmed.** An independent `stsz/stts/stss` parser (scratchpad `r6/stsz.py`) with the §8.1 rule finds peaks
  `[454,464]`, `[210,216]`, `[376,384]`, `[301,306]` s in the four scenario-8 videos (4/4 labelled windows) and 0 peaks in the control
  (max second 707 B vs threshold 2,561 B). One video also has a spurious first-second peak (harmless: peaks only protect). All five
  have B-frames and a regular 10-s GOP; none is fragmented.
* **Code claims** checked by grep: `ImageSignals.measure/S.wordy`, `Codes.read(luma,w,h,thorough)`, `Faces.detect` (score index 3),
  `PhotoQuality.MIN_FACE_CONFIDENCE = 0.7`, `TextRules.{Words,Verdict,goodWords,clean,classify(6 args),date,parseJson}`,
  `MediaDecoder.decode/decodableTypes/videoFrame`, `Budget.chargeDecode/chargeBytes`, `UsageClassifier.of`, `ImageInfo.upright*`,
  `PdfInfo.encrypted/title/author/pages`, `BoundedInflater.inflate(byte[],int)`, `NameStem.of`, `Keywords.tokens` + lists,
  `HardRules.ORDER/check`, `Candidate.needsFeatures/fallbackCode`, `Signal(…)` ctor, `LossEstimator` f[] lines, `PresentationPolicy`
  ctor, `RelType.COMPANION_OF/VERSION_OF`, `GraphStage.noteIncomplete/pendingImages`, `yunet.bin` asset, `OcrEngine.read` — all exist
  as described except the mismatches in the table (F-M5, F-M6, F-M7, F-M9, F-m1, F-m2, F-m5, F-m6).
* **Dataset ages:** every expected-YOUR_CALL image in Screenshots / WhatsApp Images / Download is older than the standard minimum age,
  so age does not limit the hit rate.

## Findings

| Id | Severity | Section | Problem | Required change | Status |
|---|---|---|---|---|---|
| F-B1 | BLOCKER | §4.3, §5.3 | `T1MetadataStage.extract` builds `new FeatureRecord()` and, with `ScanKind.FULL` (default in `PipelineConfig`, `V2Cli` and on the phone), re-selects every file every run. All content sub-records would be erased on each run: OCR restarts from zero, a budgeted run never converges, the phone never gets past 300 OCR'd images. | T1 carries `visual/text/doc/activity` and `versions[20..23]` over when the fingerprint is unchanged; `ContentCarryOverTest`. | FIXED |
| F-B2 | BLOCKER | §6.3–6.4, §9.2 | A40: `PARTIAL_SCRIPT` greetings were eligible ("rule F step 4"), and `READ` relied only on `readRatio`, so an image with one unreadable line could still be READ. | READ requires 0 garbage tokens (conf < 50, non-alphanumeric, or a character outside Latin); only READ enables; rule F step 4 deleted; tests (j) in S7 and the PARTIAL greeting case in S8. | FIXED |
| F-B3 | BLOCKER | §8.2–8.3, §10, S11–S12 | A41: `Y_STATIC_VIDEO`, `Y_SILENT_RECORDING`, the frame sampler and HC12 extension are out. | Cut §8.2/8.3, `FrameInfo`, `T6_DETAIL`, frame bridge, `AudioProbe`/silence; activity profile kept, protect only. | FIXED |
| F-B4 | BLOCKER | §9.5, §11.3 | A42: exposure gate had a `KNOWN_LABEL_DEFECTS` exemption. | Exactly 0, runs A/U/N, no exemption; S9 stops if the label is still wrong. | FIXED |
| F-B5 | BLOCKER | §4.2, §5.2, §9.4 | A43: verification stored per record at extraction time (revoking an engine would not take effect; verifying it needs re-OCR); production set contained the test id; no test that the Android build defaults to unverified. | `ContentTrust.textEngineVerified(id)` checked live at assessment; `ANDROID_OCR_VERIFIED = false`; `OcrTextReader.id()` uses the constant; `ContentTrustTest`; "unverified invariance" gate run U. | FIXED |
| F-M1 | MAJOR | §9.1, HC16 | V-UNKNOWN entries were appended to `vetoes`, so HC16 could never emit `H_CONTENT_NOT_CHECKED`, and "vetoes empty" conflated two meanings. | Separate `unknowns` list (U-VISUAL/QR/FACES/TEXT/ENGINE); HC16 code given verbatim, placed last in `ORDER`. | FIXED |
| F-M2 | MAJOR | §6.3 | Reader `NO_TEXT`, empty word list and `OcrEngine.read` returning `null` (timeout) were unspecified — an empty OCR could have been read as "no text". | null/FAILED ⇒ FAILED; NO_TEXT or empty on a wordy picture ⇒ LOW_CONFIDENCE; tests (d), (e). | FIXED |
| F-M3 | MAJOR | §6.5, §9.1 | A39 not tested; `TextFacts` amounts pattern referenced §7.3 (wrong; it is §7.4 step 3). | OTP + bank / OTP + amount tests; reference corrected; OTP = kind OTP or classes exactly [OTP]. | FIXED |
| F-M4 | MAJOR | §8.1 | Keyframes are excluded from `B_w`; an encoder that inserts a scene-cut keyframe at an event hides it. Non-MP4 / fragmented / malformed handling not tied to "unknown, never static"; sample-count lie could allocate before checking. | Irregular-keyframe windows count as peaks (test 9); every non-OK status is unknown and nothing reads absence; count checked against box size before allocation; Matroska-named-mp4 test. | FIXED |
| F-M5 | MAJOR | §13 | Slices referred to "the lead's gate.sh", which lives in the lead's scratchpad (`v2out/gate.sh`), and to a CLASSES dir nobody builds. | S1 step 0 creates `tools/v2eval/p6_gate.sh` (compiles like `run_tests.sh`), baselines under `/home/user/p6runs/S<k>`. | FIXED |
| F-M6 | MAJOR | §7.3 | OOXML re-implemented local-header parsing although `ZipProbe.list/read(rf,size,entry,maxOut,maxRatio)` exists; OLE2 "read the directory sector chain minimally" is too vague for an implementer. | Reuse `ZipProbe` (per entry 4 MiB, null ⇒ LIMIT/skip); OLE2 = byte search for the UTF-16LE names in the first 4 MiB. | FIXED |
| F-M7 | MAJOR | §3, §5.1, S10 | The phone's stage list is built in `app/V2Runner.runOpen` (`cfg.maxTier = 3` there), not in `V2Analysis` (which is in `v2adapter`). | Exact wiring places listed; sample run (A38) included. | FIXED |
| F-M8 | MAJOR | §4.1–4.2 | Privacy: `DocInfo.title/author` stored (personal names); `textDate/latestDate` could store a birth date from an ID card. | No title/author fields; dates stored only within 400 days before extraction; codec test "no raw text". Identifier/amount values were already counts only. | FIXED |
| F-M9 | MAJOR | §6.6 | Categories written to `extra["category"]` "unless a PATH category exists" — no such category exists; `FileRecord.category` is discovery's and unused; no rule reads categories. | Not stored; `ImageCategorizer` computed at JSON output time. | FIXED |
| F-M10 | MAJOR | §6.4, §9.2 | Aggressive mode changed more than thresholds: an extra eligible kind (chat screenshots) and a weaker veto (recent-date 7 d). | Aggressive changes only the three minimum ages; CHAT kind removed (its files already score as hits via KEEP). | FIXED |
| F-M11 | MAJOR | §11.1 | Sim decided the script from the font (`font_script`), but the generator draws Latin lines with a "deva" font; derived copies and rebuild reproducibility undefined. | Script from code points; recorder keyed by image object, derived images get no truth (unknown); > 5 % sha mismatches ⇒ stop and report. Recorder writes no label fields; sim reads only the truth file; output keys asserted. | FIXED |
| F-M12 | MAJOR | §9.5, §11.3 | Hit-rate expectations assumed Q1–Q3 = yes (0.86) and the gate 0.80. | Honest ≈ 0.757, labelled "desktop, simulated OCR, upper bound"; gate 0.75; phone 0.61 until verified. | FIXED |
| F-M13 | MAJOR | §11.3, §13 | `calibrate_content.py` fitted the safety thresholds on the scored dataset in the same slice that creates removals (tuning to labels); S3 (600 lines) and S9 (rules + gate + calibration) too large; slices lacked exact allowed-change rules. | Calibration cut; thresholds are constants; S3 split into S3a/S3b; S9 without calibration; Implementer brief; each slice lists files, tests and allowed changes. | FIXED |
| F-m1 | MINOR | §6.1 tests | zxing *encoder* is not vendored (only decoders). | Module matrix generated once with python `qrcode` and pasted. | FIXED |
| F-m2 | MINOR | §13 | `ForbiddenApiTest` does not forbid `javax.xml` although the design relies on it. | S3a adds the pattern. | FIXED |
| F-m3 | MINOR | §4.1 | `Sub.copy()` is shallow; lists/arrays are shared. | Never mutate after put (stated). | FIXED |
| F-m4 | MINOR | §7.2 | Searching for `stream` also matches `endstream`. | Not preceded by `end`. | FIXED |
| F-m5 | MINOR | §9.3 | `Y_OLD_SCREENSHOT` sentence marked "unchanged" but no Y_OLD_* code exists in `ReasonCatalog`. | All entries marked new, with the `c(…)` pattern. | FIXED |
| F-m6 | MINOR | §3 | Existing `v2.content.{ContentClassifier,RuleClassifier,Classification}` plug point not mentioned. | Stated they stay; `ContentFacts` added alongside (C12). | FIXED |
| F-m7 | MINOR | §6.3 | OCR selection excluded "verified exact copies", which needs graph data the stage does not have. | Exclusion cut. | FIXED |
| F-m8 | MINOR | §10 | `AudioProbe` under the existing `AUDIO` id at version 1 would never run on files already stamped 1. | Noted for whoever reinstates it (bump to 2). | FIXED (note) |
| F-m9 | MINOR | §7.4 | New path keywords also change `UsageClassifier` (`Keywords.record` ⇒ DOCUMENT_RECORD). | S4 notes list usage changes. | FIXED |
| F-m10 | MINOR | §6.5, §7.4 | "Whole token" undefined; regex `\b` breaks Devanagari words at vowel signs. | Explicit split patterns, NFC. | FIXED |
| L1 | — | §10 | A41 says audio features are "extracted and used only to protect", but no protective rule consumes them. | Cut applied; lead to confirm. | LEFT FOR LEAD |
| L2 | — | §16 Q5 | Default `--max-tier 4` and per-run caps (300 OCR / 2,000 visual at BALANCED) on the phone. | Recommend accepting; protection-only on the phone until A43 verification. | LEFT FOR LEAD |

## Safety walk-through (every path to YOUR_CALL)

The only new paths are `Y_OLD_SCREENSHOT`, `Y_FORWARDED_MEDIA`, `Y_OLD_DOWNLOAD` (jpeg/png, YOUR_CALL DELETE). Each requires
READ text from a verified engine, visual + QR + faces run, no veto, no record category, a positive disposable kind, no keeper role,
no user protection and a known age; HC16 re-checks vetoes/record/unknowns independently; HC7 blocks any SAFE/NO-LOSS with CONTENT;
HC8 blocks unknown features. The brief's adversarial cases and the dataset's mnd groups are mapped to the stopping rule in §6.4 and §0.2.
No Phase 6 code path produces SAFE or NO-LOSS; content only raises L (via `max`), adds signals with +1, and never removes a block.
Documents, camera media, voice and video get no removal rule.

## Verdict

**Approved for implementation with the fixes applied** (all BLOCKER/MAJOR fixed in place). S1–S9 are ready for a Sonnet implementer;
S10 is ready but only compile-checked; S11 optional. Two items left for the lead (L1, L2), neither blocks S1–S9.
