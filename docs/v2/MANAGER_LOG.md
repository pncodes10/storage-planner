# Storage Planner v2 — manager log

Lead's running record: state, decisions with reasons, next steps. The hourly
check-in reads this file first. Spec: `Storage_Planner_Technical_Specification_v2.docx`
(text copy kept in the session scratchpad; phases listed in section 17).

## Operating rules (from the owner)

- The lead decides and delegates; all implementation is done by Opus subagents.
- Per phase: expand the phase in detail -> independent agent reviews the plan ->
  implementer agent builds it -> tests verified -> commit and push -> log updated.
- v2 lives in its own package next to the existing algorithm; the app gets a
  switch between "Classic" and "v2".
- Testing runs in the cloud session against sample images. No APK is installed
  on the phone.
- Branch: `claude/algorithm-project-management-7k8b5r` only.

## Decisions

| # | Decision | Reason |
|---|----------|--------|
| D1 | Work on the session's assigned branch rather than a newly named one. | The session may only push to this branch. |
| D2 | No APK install on the phone tonight; cloud-only testing. | Owner allowed either; cloud runs are repeatable and need nobody at the phone. |
| D3 | Evaluation data = generated labelled dataset (`tools/v2dataset/`, output in `/home/user/sp-dataset/`, not in git) + the owner's Drive photos. | Network policy blocks most image hosts (Wikimedia, Unsplash, Pexels, Flickr, Hugging Face, Kaggle). Reachable: raw.githubusercontent.com, PyPI, storage.googleapis.com, Maven. Generated derivatives give exact ground truth for duplicate/near-duplicate groups. |
| D4 | Drive photos stay outside git, in `/home/user/sp-dataset-drive/`; Drive is read-only. | Personal photos, some owned by other accounts. |
| D5 | Drive videos skipped. | Drive tool caps downloads at 10 MB; smallest video is 18.6 MB. No direct Google API access used. |
| D6 | Hourly scheduled check-in wakes this session. | Owner asked that work continue unattended. |
| D7 | Relaunched the survey and dataset agents at 22:25 UTC. | Both had been silently stopped at 20:25/20:33 by the owner's interrupts (made to reconnect Drive), and could not be resumed. Two hours lost. Lesson: after any interrupt, check that background agents are still alive. |
| D8 | At most two Opus agents at a time; work committed as soon as it exists. | Both running agents hit the account's 5-hour usage limit at ~22:50 UTC and were stopped until 01:20 UTC (2.5 h lost). Resumed 01:27 UTC from their saved state. |
| D9 | One Opus agent at a time from here on. | At ~08:20 UTC the account hit its *weekly* usage limit (reset 09:00 UTC); the session was idle until 11:24 UTC. Total lost to stoppages so far: about 11 of 15 hours. |
| D10 | Hourly check-in disabled at 07:27 UTC on 13 Nov at the owner's request. No new agents are started without the owner from this point. | Owner instruction. |

## Architecture decisions (after the codebase survey, 22:30 UTC)

Survey facts that drive these: the app is Java 8 with zero third-party
libraries; the real build is `tools/build_offline.sh` (no Gradle); the APK audit
fails on INTERNET permission, java.net, reflection, and `File.delete` outside the
tools classes; the classic core has no android.* imports and is tested with
plain `main()` programs in `jvmtest/`; there is no Android SDK in this container,
so only CI can build an APK. The spec's "Assessor" and "Need" classes do not
exist; the real classic pipeline is Scanner -> Sorter -> UnitBuilder -> Planner/Review.

| # | Decision | Reason |
|---|----------|--------|
| A1 | v2 is Java 8, no libraries, in `app/src/main/java/com/storageplanner/v2/`, with no android.* imports. | Matches the app's build and audit; keeps the decision engine desktop-testable, which the spec requires (section 3). |
| A2 | Classic `core/` stays behaviourally untouched; v2 may reuse its utilities (pHash, sharpness, QR, faces, magic bytes); only visibility widenings allowed there. | Two algorithms must stay comparable; no risk to the shipped one. |
| A3 | Persistence behind a store interface: in-memory + snapshot file now, Android SQLite in Phase 10. | Spec wants SQLite, but the JVM has none without a library. |
| A4 | Tests are `main()` programs in `jvmtest/.../v2test/`; a CLI runs v2 on a folder and writes JSON; `tools/v2eval/evaluate.py` scores it against the dataset manifest. | Same style as the repo; gives one fixed regression number per change (spec 16.3). |
| A5 | Undecodable files (e.g. HEIC on the JVM) = "unknown", never "unimportant". | Spec 5.1/5.3 safety rule; 38 of the owner's photos are HEIC. |
| A6 | App switch "Algorithm: Classic / v2" on the setup screen; v2 results adapted into the existing Unit/Option/Plan types. Done as "Phase A" after Phase 5. | Reuses every existing screen; the glue stays small because it can only be compiled by CI. |
| A7 | ML (spec Phase 7, LiteRT) deferred; plug-in interface only. | Spec section 17: no ML becomes a dependency before a measured gain. LiteRT would also add native libraries the current build cannot package. |
| A8 | Execution engine (delete/quarantine/compress) built and tested on the JVM against a FileOps interface, NOT wired to a delete button. | The shipped app is read-only and the audit forbids deletion; turning that on is the owner's call. |
| A9 | Safety invariants tested in every phase: unknown != zero; classification never authorises deletion; never the last verified copy; similarity alone != interchangeable; aggressive mode never weakens hard rules. | Spec sections 2.2, 6.3, 9.5. |
| A10 | SHA-256 is computed only where a decision needs it (size collisions, and every delete candidate before it is proposed), not for every file. | Spec 5.1 lists a hash per file, but spec 14.1 forbids running expensive work on everything; hashing a 128 GB phone in full is hours of I/O. Reviewer agreed. Deviation from 5.1 recorded here on purpose. |
| A11 | Phase 2 scope cut: MKV, AVI, TAR, extra audio formats, ICO and GIF-frame parsers move to Phase 6; scanning is single-threaded until Phase 10. | Reviewer finding: high cost, no effect on phases 1-5 results. Unparsed formats stay "unknown", which is safe. |
| A12 | Phase A (app switch) waits for a memory measurement after Phase 2: at most about 1 KB per file in the in-memory store, otherwise SQLite is brought forward. | The phone build holds the whole store in memory until Phase 10. |
| A13 | Store = one snapshot file replaced atomically at intervals, not a write-ahead log. | Reviewer found a log bug that silently lost every commit after a half-written line; snapshots are simpler and enough for scan recovery. The Phase 9 operation journal gets its own log. |
| A14 | v2 includes app caches and idle apps (data gathered in Phase 2, planned in Phase 5); exit test: v2's safe bytes are at least classic's. | Spec 10.3 ranks rebuildable data first; the first draft ignored it. |
| A15 | A14 amended: v2 need not match classic's safe bytes where classic's call is unsafe (WhatsApp "sent" copies matched on look alone, Telegram media assumed cloud-backed, older APKs, encrypted chat backups). Parity is checked per item against a documented exemption list. | Architect showed strict parity would force v2 to copy calls the spec forbids (6.2: similarity alone never makes files interchangeable; 8.3: a backup must be verified). Safety outranks matching the old number. |
| A16 | The spec's tier definitions are the authority, not the dataset's labels; the evaluator translates dataset tiers to spec tiers. A delete at SAFE/NO-LOSS on a must-never-delete file fails under any translation. | The dataset marked exact and WhatsApp copies SAFE; the spec reserves NO-LOSS for verified identical copies and caps look-alike copies at YOUR CALL. |
| A17 | A duplicate group's keeper is a label with no recommendation, not a PROTECTED item. | Otherwise about 300 ordinary originals would show as "protected", which reads as over-caution. |
| A18 | `Android/data` and `Android/obb` stay restricted by default; the scoring gate expects them reported as inaccessible, not scanned. | Android 11+ blocks them on real phones. The dataset's one file under `Android/obb` made the Phase 1 gate fail by a single file; the algorithm was right and the gate was wrong. |
| A19 | A verified byte-identical copy stays NO-LOSS even when the names differ; the keeper is the copy with the more descriptive name and more organised folder, and the reason shows both names. Reviewer's proposal to send such pairs to REVIEW was overruled. | Spec 9.4 says so, and the proposal was a workaround for a dataset bug (two "different" invoices generated identical). Demoting every renamed identical file would hide the safest recoveries. The generator bug gets fixed instead. |
| A20 | A temp-looking name over complete, real content (e.g. a full bank-statement PDF named `temp123.tmp`) goes to REVIEW when it is the only copy. | Reviewer blocker: deciding "temp" from the extension alone would have produced a SAFE delete of a must-never-delete file. |
| A21 | The in-memory store fails the A12 limit (measured 2.7 KB heap and 2.1 KB snapshot per file, limit ~1 KB). Before Phase A the records are shrunk (unknown fields not written, features loaded on demand); if still over 1 KB, SQLite moves into Phase A. Cloud phases 3-5 proceed meanwhile. | 100,000 files would need about 270 MB of heap, more than a phone app can count on. Desktop evaluation is unaffected. |
| A22 | On the phone, photos must be decoded at reduced size by the device layer. | Full-size JVM decoding costs about 180 ms per photo (42 s for 268 photos); 30,000 photos would take about 90 minutes. |
| A23 | Phase 3 accepted with the same-content recall gate measured over images the desktop can decode (0.960, gate 0.95); over all images it is 0.909. | The shortfall is WebP files the desktop JVM cannot decode (Android can). They come out "relationships unknown", which only costs missed savings, never a wrong delete. All safety gates pass: 0 false matches inside look-alike groups, 0 false verified backups, exact-duplicate precision 1.0. |
| A24 | Same-content results are scored as clusters, so two derived copies of one original count as related even if the dataset did not link them directly. | The dataset links each copy to its original only; a WhatsApp copy and a thumbnail of the same photo really are the same content. Strict pair precision 0.976, cluster precision 0.992. |
| A25 | Phase 4 accepted on safety, but sent back on usefulness: "your call" items must be decided by the kind of evidence, not by an absolute size floor. The utility score ranks them and decides plan inclusion; it no longer hides them. | First run: 0 dangerous deletes, but 452 of 453 files that should be "your call" got no recommendation, because a 50 MiB benefit scale made every small chat copy and APK score below zero. Spec section 1 asks for aggressive discovery with risk-aware recommendations; hiding candidates is not the safe side, it is just less useful. Fix assigned to the Phase 5 implementer; gate: "your call" hit rate at least 0.8 with dangerous deletes still 0 and no "your call" on must-never-delete files. |
| A26 | In byte-identical groups, v2 may keep a different copy than the dataset labelled; the scoring accepts either. | The copies are identical, so which one stays is a preference (A19 order), not a correctness question. 27 groups differ. |
| A27 | The one "your call" recommendation on a must-never-delete file is a dataset label defect, not an algorithm fault; the label will be corrected in the generator. | The file is a lower-resolution copy of the same picture as another file (sources `obama-720p.jpg` / `obama-1080p.jpg`), yet labelled unique. Offering the smaller copy as "your call" is the intended behaviour. Until fixed, the scored run shows exposure 1 and scenario 5 failing. |
| A28 | Old screenshots, forwarded chat media and old downloads stay deferred to Phase 6; the 0.8 "your call" hit-rate gate moves there. Current hit rate 0.61. | Offering these on age and folder alone contradicts spec 2.2 ("do not assume old files are unnecessary") and would put must-never-delete screenshots in front of the user as deletion candidates. They need content signals (text, QR, document layout), which is Phase 6. |
| A29 | A plan contains only SAFE and NO-LOSS items by default; "your call" items enter only when the user opts that tier in, ranked by score. | Spec 10.2: if a target cannot be reached safely, report the achievable amount instead of raising risk silently. |
| A30 | Lead failure recorded: the GitHub build has failed on every push since v2 code landed, and it was not checked until 12:35 UTC. The APK compile step reports 10 errors in v2 code (desktop-only Java APIs). Fix assigned; a local compile check against Android framework classes is being added so this is caught before pushing. | The local tests compile against the desktop JDK only, so "all tests pass" never covered "compiles for Android". Phase A depends on this. |
| A31 | v2 on the phone is limited by a guard: about 15,000 files on a 256 MB heap and about 42,000 on 512 MB. Above that it stops after discovery with a message pointing to Classic. Moving the store to SQLite (spec section 13, planned for Phase 10) is now the next engineering priority, ahead of Phase 6. | Measured peak is 6.4 KB of heap per file; the record shrink does not reduce it. The owner's phone has tens of thousands of files, so v2 will most likely hit the guard there. A clean stop with an explanation is acceptable for a first test; a crash is not. |
| A32 | The memory fix is a pure-Java disk-backed store behind the existing store interface, not Android SQLite, for now. An architect profiles the heap first and may overturn this. | Spec section 13 says SQLite, but an Android-SQLite store cannot be compiled-and-run or tested anywhere in this environment, while a pure-Java store is fully testable on the desktop and runs unchanged on the phone. Untested persistence code under a tool that advises on deleting files is the larger risk. SQLite can implement the same interface later, once a device is available. |
| A33 | User decisions (feedback, preferences, later the operation journal) are kept outside the resettable store log, in their own durable file. Until that exists, a test blocks production writes to those tables. | The store resets itself after unrecoverable damage; scan data can be rebuilt by rescanning, a user's "keep this" cannot. |
| A34 | The 20,000-decode cap in the duplicate stage becomes a per-run budget: a capped run finishes with a result, marks unchecked photos "unknown" (never "unique") and says to run again; finished pair checks carry over. Decoded signatures are not stored. | Storing signatures would cost about 195 KB per photo, roughly 6 GB for 30,000 photos - on a phone that is short of space. |
| A35 | Store design accepted as reviewed: one shared append-only log, capacity about 133,000 files at 256 MB (about 177,000 if the store is closed before results are shown), with a 1.25 safety factor because Android's memory overhead is unmeasured. | Reviewer recomputed the architect's 150,000 figure; the scale test runs under a hard 148 MB cap. |
| A36 | Store accepted at about 85,000 files on a 256 MB heap and about 188,000 on 512 MB, short of the 150,000-at-256 MB target. The app is switched to the disk store now; the remaining memory work (drop the store's indexes before results are shown, slimmer on-screen result) is a follow-up. | The limit was about 15,000 / 42,000 before, so this is a 4-5x gain that is tested and result-identical. The 150k run under a 148 MB cap fails; 85k passes. Waiting for the last step would keep a working improvement off the phone. |
| A37 | v2 refuses to start unless about 8 KB per file plus 320 MB is free (about 1.15 GB for 100,000 files), and says to use Classic instead. Accepted for the phone test; reducing it is a follow-up. | The app exists for phones that are short of space, so this requirement works against its purpose - but running out of disk mid-scan is worse, and the estimate is deliberately conservative until real numbers come from a device. |
| D11 | From 14 Nov the owner's instruction is: Sonnet agents write the code. Design and independent review stay on Opus. | Owner instruction ("use the sonnet agents to code"); design and review were not mentioned, and they are where a weaker model costs most. Designs are now written with more explicit, smaller slices so Sonnet can follow them. |
| A38 | "Quick v2 test" button: always runs v2, on the 1,000 most recently modified images and the 100 most recently modified videos, nothing else (no documents, apps or caches). It uses its own store folder, shows a "sample result" notice on the results, and is saved as its own result kind. | Owner asked for a separate button running on "first 1000 images, 100 videos". "First" is read as newest, the files a user cares most about and can check by eye. A separate store keeps a sample run from marking the rest of the phone as removed in the full-scan data. The notice stops a sample being read as a whole-phone result. |
| A39 | Phase 6 Q1: a one-time-password message is treated as disposable unless bank, payment or amount text appears with it. | OTPs expire in minutes; treating every OTP screenshot as protected contradicts how they work and the dataset labels. The result is still only "your call", never an automatic removal. |
| A40 | Phase 6 Q2: a picture whose text the OCR cannot read (for example a Devanagari line, since the bundled OCR is English-only) is NOT eligible for "your call". The desktop hit-rate target becomes about 0.75, stated as a desktop figure. | Architect proposed letting greetings with an unreadable Devanagari line qualify, which would reach 0.86. Unreadable text is unknown text: it may be a note or a document. Spec 7.2 singles out regional-language material; guessing there is the wrong place to buy hit rate. The real fix is Hindi/Marathi OCR data, verified on a device. |
| A41 | Phase 6 Q3: static videos and silent recordings are not offered as candidates in Phase 6. Video motion and audio features are extracted and used only to protect (for example a long video with a short burst of activity). | Spec 5.5: no speech or music is never proof a file is unnecessary; scenario 8: a long, mostly static video can hold the one moment that matters. Quality-style signals alone should not create candidates. |
| A42 | Phase 6 Q4: the mislabelled dataset file (A27) is fixed now, before Phase 6 code, so the exposure gate can read zero. | The gate cannot tell a new real exposure from the known label defect while it is red. |
| A43 | On the phone, OCR and frame-based signals may protect files but may not make any file a "your call" candidate until the owner's device checklist shows they work. | They cannot be tested here; an OCR that silently returns nothing would otherwise look like "no protective text". |

## Data on disk (ephemeral container — rebuild if lost)

- `/home/user/sp-dataset-drive/files/…`: 268 of 270 Drive images (706 MiB), no exact
  duplicates, 49 name-timestamp burst groups (`bursts_by_name.json`),
  26 WhatsApp `IMG-…-WA…` + 6 "WhatsApp Image … (n)". 2 HEIC files (~8 MB) too
  large for the tool. `inventory.jsonl`, `downloaded.jsonl` alongside.
- `/home/user/sp-dataset/`: generated dataset — build in progress.

## State (03:24 UTC, 14 Nov)

- Phases 1-5, Phase A, the store (S0-S7) and the quick-test button (A38) done;
  43 suites green (re-run by the lead). Button reviewed (`REVIEW_SAMPLE.md`:
  0 blockers, 3 major fixed). **Build to test on the phone: CI run 74**
  (commit 595cf30, succeeded 03:24 UTC, 14 Nov):
  https://github.com/pncodes10/storage-planner/actions/runs/43551271466 Nothing has run on a device.
- Phase 6 design written (`PHASE_6.md`, 13 slices); rulings A39-A43 given.
- Running: Opus reviewer for the Phase 6 design; Sonnet agent fixing the
  dataset label (A42).
- Hourly check-in stays disabled (D10).
- Open: ClassicParityTest and five other tests, minor review findings,
  free-space requirement (A37).

## Next steps

1. Confirm CI for 595cf30 -> build to test.
2. Phase 6 review -> Sonnet implementers, slice by slice (S1 model/codec,
   S2 MP4 activity, S3 document text, S4 document categories, ...).
3. Phases 7-10.
